package com.ghostpanter.scrcpy;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Audio output: AudioTrack writing 48 kHz stereo 16-bit PCM. The
// upstream feed is either raw PCM (passthrough) or Opus packets
// (decoded through MediaCodec into PCM first).
public final class AudioSink implements AudioFrames {

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING    = AudioFormat.ENCODING_PCM_16BIT;
    private static final int MAX_PENDING_OPUS = 16;
    private static final int MAX_PENDING_PCM = 16;
    private static final int MAX_PACKET_BYTES = 256 * 1024;

    // Defaults documented at <https://developer.android.com/reference/android/media/MediaCodec#CSD>.
    private static final long DEFAULT_PRE_ROLL_NS = 80_000_000L;

    private AudioTrack    track;
    private volatile MediaCodec opusCodec;
    private HandlerThread opusThread;
    private Handler       opusHandler;
    private Thread        opusOutputThread;
    private volatile boolean opusConfigured;
    private int           fourcc;

    private final Object lock = new Object();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean playbackReported = new AtomicBoolean();
    private final Deque<Integer> freeOpusInputs = new ArrayDeque<>();
    private final Deque<Packet> pendingOpus = new ArrayDeque<>(MAX_PENDING_OPUS);
    private final ArrayBlockingQueue<byte[]> pendingPcm =
            new ArrayBlockingQueue<>(MAX_PENDING_PCM);

    private static final class Packet {
        final byte[] data;
        final long ptsUs;

        Packet(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    private volatile boolean released;
    @Override
    public void start(int fourcc) {
        this.fourcc = fourcc;

        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
        if (minBuf <= 0) throw new IllegalStateException("AudioTrack.getMinBufferSize=" + minBuf);
        int bufSize = Math.max(minBuf, 32 * 1024);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .setEncoding(ENCODING)
                .build();

        track = new AudioTrack(
                attrs, fmt, bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            track = null;
            throw new IllegalStateException("audio sink: AudioTrack failed to initialize");
        }
        track.play();
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            track.release();
            track = null;
            throw new IllegalStateException("audio sink: AudioTrack failed to start");
        }
        Log.i("audio sink: AudioTrack started sr=%d ch=2 buf=%d", SAMPLE_RATE, bufSize);

        if (fourcc == Wire.CODEC_OPUS) {
            opusThread = new HandlerThread("audio-mc");
            opusThread.start();
            opusHandler = new Handler(opusThread.getLooper());
            // Codec is created here, but only configured once the first
            // FLAG_CONFIG frame arrives with the OpusHead bytes.
            try {
                opusCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            } catch (IOException e) {
                throw new IllegalStateException("audio sink: no opus decoder", e);
            }
            opusOutputThread = new Thread(this::playOpus, "audio-out");
            opusOutputThread.start();
            opusCodec.setCallback(new MediaCodec.Callback() {
                @Override public void onInputBufferAvailable(MediaCodec mc, int idx) {
                    synchronized (lock) {
                        if (released || failed.get() || mc != opusCodec) return;
                        Packet packet = pendingOpus.pollFirst();
                        if (packet == null) {
                            freeOpusInputs.offerLast(idx);
                        } else {
                            lock.notifyAll();
                            submitOpus(mc, idx, packet);
                        }
                    }
                }
                @Override public void onOutputBufferAvailable(MediaCodec mc, int idx,
                                                              MediaCodec.BufferInfo info) {
                    byte[] pcm = null;
                    try {
                        ByteBuffer out = mc.getOutputBuffer(idx);
                        if (out != null && info.size > 0 && !released && !failed.get()) {
                            pcm = new byte[info.size];
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            out.get(pcm);
                        }
                    } catch (IllegalStateException e) {
                        reportFatal(e, "audio sink: opus output");
                    } finally {
                        try { mc.releaseOutputBuffer(idx, false); }
                        catch (IllegalStateException ignored) {}
                    }
                    if (pcm != null) queuePcm(pcm);
                }
                @Override public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    reportFatal(e, "audio sink: opus codec error");
                }
                @Override public void onOutputFormatChanged(MediaCodec mc, MediaFormat f) {
                    Log.i("audio sink: opus output format %s", f);
                }
            }, opusHandler);
        }
    }

    @Override
    public void feed(byte[] data, int off, int len, long ptsUs, boolean isConfig) {
        if (data == null || off < 0 || len < 0 || off > data.length - len) {
            throw new IndexOutOfBoundsException("invalid audio frame range");
        }
        if (len > MAX_PACKET_BYTES) {
            reportFatal(null, "audio sink: packet too large (" + len + " bytes)");
            return;
        }
        if (released || failed.get() || len == 0) return;
        if (fourcc == Wire.CODEC_OPUS) {
            feedOpus(data, off, len, ptsUs, isConfig);
        } else {
            // Raw PCM: passthrough.
            writePcm(data, off, len);
        }
    }

    private void feedOpus(byte[] data, int off, int len, long ptsUs, boolean isConfig) {
        if (isConfig) {
            if (opusConfigured) return; // already configured
            // OpusHead is 19 bytes; pre_skip lives at bytes [10..11]. Reject
            // anything shorter before indexing into it.
            if (len < 19) {
                reportFatal(null, "audio sink: opus head too short (" + len + " bytes)");
                return;
            }
            try {
                byte[] head = new byte[len];
                System.arraycopy(data, off, head, 0, len);
                int preSkipSamples = ((head[10] & 0xff) | ((head[11] & 0xff) << 8));
                long preSkipNs = preSkipSamples * 1_000_000_000L / SAMPLE_RATE;

                MediaFormat fmt = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 2);
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(head));
                fmt.setByteBuffer("csd-1", longLeBytes(preSkipNs));
                fmt.setByteBuffer("csd-2", longLeBytes(DEFAULT_PRE_ROLL_NS));
                opusCodec.configure(fmt, null, null, 0);
                opusCodec.start();
                opusConfigured = true;
                Log.i("audio sink: opus configured, pre_skip=%d ns", preSkipNs);
            } catch (Exception e) {
                reportFatal(e, "audio sink: opus configure");
            }
            return;
        }
        if (!opusConfigured) return;
        byte[] packet = new byte[len];
        System.arraycopy(data, off, packet, 0, len);
        Packet p = new Packet(packet, ptsUs);
        synchronized (lock) {
            if (released || opusCodec == null) return;
            // Apply socket backpressure during decoder bursts. Dropping a
            // compressed packet corrupts playback; growing the queue only
            // postpones the same failure.
            while (!released && !failed.get() && freeOpusInputs.isEmpty()
                    && pendingOpus.size() == MAX_PENDING_OPUS) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (released || failed.get() || opusCodec == null) return;
            Integer idx = freeOpusInputs.pollFirst();
            if (idx != null) {
                submitOpus(opusCodec, idx, p);
                return;
            }
            pendingOpus.offerLast(p);
        }
    }

    // Must be called with lock held. Async MediaCodec input indices belong
    // to the codec instance that delivered them.
    private void submitOpus(MediaCodec codec, int idx, Packet packet) {
        try {
            ByteBuffer in = codec.getInputBuffer(idx);
            if (in == null || packet.data.length > in.capacity()) {
                reportFatal(null,
                        "audio sink: opus packet exceeds codec input ("
                                + packet.data.length + " bytes)");
                return;
            }
            in.clear();
            in.put(packet.data);
            codec.queueInputBuffer(idx, 0, packet.data.length, packet.ptsUs, 0);
        } catch (IllegalStateException e) {
            reportFatal(e, "audio sink: opus queueInputBuffer");
        }
    }

    // Encode a long little-endian for MediaFormat csd-1 / csd-2.
    private static ByteBuffer longLeBytes(long v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v);
        b.flip();
        return b;
    }

    private void queuePcm(byte[] pcm) {
        try {
            while (!released && !failed.get()
                    && !pendingPcm.offer(pcm, 100, TimeUnit.MILLISECONDS)) {
                // Wait for the blocking AudioTrack writer to make room.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void playOpus() {
        try {
            while (!released && !failed.get()) {
                byte[] pcm = pendingPcm.take();
                writePcm(pcm, 0, pcm.length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            reportFatal(e, "audio sink: AudioTrack write");
        }
    }

    private void writePcm(byte[] data, int off, int len) {
        AudioTrack t = track;
        if (released || failed.get() || t == null || len <= 0) return;
        int end = off + len;
        while (!released && !failed.get() && off < end) {
            int written = t.write(data, off, end - off, AudioTrack.WRITE_BLOCKING);
            if (written <= 0) {
                if (!released) {
                    reportFatal(null, "audio sink: AudioTrack write rc=" + written);
                }
                return;
            }
            off += written;
            if (playbackReported.compareAndSet(false, true)) {
                Log.i("audio sink: playback started");
            }
        }
    }

    private void reportFatal(Exception error, String message) {
        if (!failed.compareAndSet(false, true)) return;
        if (error == null) Log.e("%s", message);
        else Log.e(error, "%s", message);
        synchronized (lock) {
            pendingOpus.clear();
            lock.notifyAll();
        }
        pendingPcm.clear();
        Thread t = opusOutputThread;
        if (t != null) t.interrupt();
    }

    @Override
    public void release() {
        MediaCodec c;
        synchronized (lock) {
            released = true;
            c = opusCodec;
            opusCodec = null;
            freeOpusInputs.clear();
            pendingOpus.clear();
            lock.notifyAll();
        }
        AudioTrack t = track;
        track = null;
        // Stop playback first so the blocking writer can return before
        // AudioTrack is released.
        if (t != null) {
            try { t.pause(); t.flush(); t.stop(); } catch (Exception ignored) {}
        }

        HandlerThread ht = opusThread;
        opusThread = null;
        opusHandler = null;
        Thread out = opusOutputThread;
        opusOutputThread = null;
        if (out != null) out.interrupt();
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) {}
            try { c.release(); } catch (Exception ignored) {}
        }
        if (ht != null) ht.quitSafely();
        if (out != null && out != Thread.currentThread()) {
            try { out.join(1_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (out.isAlive()) Log.e("audio sink: output thread did not stop");
        }
        pendingPcm.clear();
        if (t != null) {
            try { t.release(); } catch (Exception ignored) {}
        }
    }
}
