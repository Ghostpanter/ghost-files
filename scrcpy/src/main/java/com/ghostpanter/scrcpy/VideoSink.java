package com.ghostpanter.scrcpy;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

// MediaCodec async-mode video decoder writing to a Surface.
//
// MediaCodec hands us input buffer indices on its callback handler;
// VideoStream pushes encoded frames at us synchronously. The two ends
// meet through a small queue of pending frames waiting for input buffers,
// plus a corresponding pool of free input buffer indices.
//
// Back-pressure policy: when no input buffer is free and the pending
// queue is full, replace the oldest frame of the same kind, or the oldest
// frame overall. The queue remains bounded even if a peer floods CSD.
// After a decoder rebuild, delta frames are dropped until the next keyframe;
// feeding them first can leave MediaCodec waiting forever for missing refs.
//
// Output buffers render when decoded. The Surface compositor already
// synchronizes presentation to vsync; translating a hostile remote PTS
// into an absolute local clock can instead queue frames arbitrarily far
// into the future.
public final class VideoSink implements VideoFrames {

    private static final int MAX_PENDING = 8;
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
    private static final long MIN_ENCODER_RESET_INTERVAL_MS = 2_000L;

    private volatile Surface surface;
    private final Runnable onFatalError;
    private final Runnable requestVideoReset;
    private final AtomicBoolean fatalReported = new AtomicBoolean();
    private long             renderedFrames;
    private volatile MediaCodec codec;
    private HandlerThread handlerThread;
    private Handler handler;

    private final Object lock = new Object();
    private final Deque<Integer> freeInputs = new ArrayDeque<>(16);
    private final VideoQueue pending = new VideoQueue(MAX_PENDING, MAX_PENDING_BYTES);

    private boolean released;

    // The format the server last announced, held so the decoder can be
    // built later if there was no output surface when it arrived.
    private boolean haveFormat;
    private int     fmtFourcc, fmtW, fmtH;

    // Most recent CSD (SPS/PPS). A decoder built late, or rebuilt on
    // resize, needs it before it can decode anything, and the server only
    // sends it once per run. VideoStream hands us a fresh array per frame,
    // so holding the reference is enough.
    private byte[] lastConfig;
    private volatile long lastEncoderResetAtMs;

    public VideoSink(Surface surface, Runnable onFatalError, Runnable requestVideoReset) {
        this.surface = surface;
        this.onFatalError = onFatalError;
        this.requestVideoReset = requestVideoReset;
    }

    // Swap the output Surface. A destroyed Surface cannot remain attached to
    // MediaCodec, so null tears the decoder down but retains its format and
    // codec config. The reader keeps draining the wire; a new Surface rebuilds
    // the decoder and replays the cached config.
    public void setOutputSurface(Surface newSurface) {
        if (newSurface == null) {
            synchronized (lock) {
                if (released) return;
                surface = null;
            }
            teardownCodec(false);
            return;
        }
        synchronized (lock) {
            if (released) return;
            this.surface = newSurface;
            MediaCodec c = codec;
            if (c == null) {
                if (newSurface != null && haveFormat) startDeferredLocked();
                return;
            }
            try {
                c.setOutputSurface(newSurface);
            } catch (RuntimeException e) {
                reportFatal(e, "video sink: setOutputSurface");
            }
        }
    }

    @Override
    public void configure(int codecFourcc, int width, int height) throws IOException {
        String mime = mimeFor(codecFourcc);
        if (mime == null) throw new IOException("unsupported video codec " + Wire.fourccName(codecFourcc));
        synchronized (lock) {
            if (released) return;
            haveFormat = true;
            fmtFourcc = codecFourcc; fmtW = width; fmtH = height;
            if (surface == null) {
                // No output attached yet: the activity was backgrounded
                // during bring-up, or this is a reconnect that completed
                // while backgrounded. MediaCodec cannot be moved from
                // ByteBuffer mode to Surface mode afterwards, so
                // configuring with a null surface here would black the
                // session out permanently and setOutputSurface() would
                // throw for the rest of the session. Wait instead; the
                // wire keeps draining and frames are dropped until a
                // surface arrives.
                Log.i("video sink: no output surface, deferring decoder (%s %dx%d)",
                        mime, width, height);
                return;
            }
            startCodecLocked(mime, width, height);
        }
    }

    // Build the deferred decoder once a surface finally shows up. Failure
    // here is fatal to the session: without a decoder there is no picture
    // and no way to ask for one again.
    private void startDeferredLocked() {
        try {
            startCodecLocked(mimeFor(fmtFourcc), fmtW, fmtH);
        } catch (Exception e) {
            reportFatal(e, "video sink: deferred configure failed");
        }
    }

    // Must be called with `lock` held and `surface` non-null.
    private void startCodecLocked(String mime, int width, int height) throws IOException {
        Log.i("video sink: configure mime=%s %dx%d", mime, width, height);
        renderedFrames = 0;
        freeInputs.clear();
        pending.clear();

        // Created before the HandlerThread: createDecoderByType throws
        // IOException, which the RuntimeException cleanup below does not
        // cover, and an orphaned thread would survive until release().
        MediaCodec c = MediaCodec.createDecoderByType(mime);

        handlerThread = new HandlerThread("video-mc");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        MediaCodec.Callback callback = new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec mc, int idx) {
                onFreeInput(mc, idx);
            }
            @Override public void onOutputBufferAvailable(MediaCodec mc, int idx, MediaCodec.BufferInfo info) {
                try {
                    if (mc != codec) {
                        mc.releaseOutputBuffer(idx, false);
                        return;
                    }
                    mc.releaseOutputBuffer(idx, true);
                    if (++renderedFrames == 1) {
                        Log.i("video sink: rendered frame n=1");
                    }
                } catch (IllegalStateException e) {
                    if (mc == codec) reportFatal(e, "video sink: releaseOutputBuffer");
                }
            }
            @Override public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                if (mc == codec) reportFatal(e, "video sink: codec error");
            }
            @Override public void onOutputFormatChanged(MediaCodec mc, MediaFormat fmt) {
                Log.i("video sink: output format %s", fmt);
            }
        };

        try {
            c.setCallback(callback, handler);
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
            c.configure(fmt, surface, null, 0);
            // Publish before start() so the identity checks in the
            // callbacks match from the very first buffer.
            codec = c;
            c.start();
        } catch (RuntimeException e) {
            codec = null;
            try { c.release(); } catch (Exception ignored) {}
            HandlerThread ht = handlerThread;
            handlerThread = null;
            handler = null;
            if (ht != null) ht.quitSafely();
            throw e;
        }

        // A decoder built after the stream started - deferred for a
        // missing surface, or rebuilt on resize - has missed the CSD the
        // server only sends once. Replay it ahead of everything else.
        if (lastConfig != null) {
            pending.offer(new VideoQueue.Frame(lastConfig, 0L, true, false));
        }
    }

    // Called by VideoStream for every encoded frame, in order.
    @Override
    public void feed(byte[] data, long ptsUs, boolean isConfig, boolean isKeyframe) {
        boolean reset = false;
        synchronized (lock) {
            if (released) return;
            if (data == null || data.length == 0 || data.length > MAX_FRAME_BYTES) {
                reportFatal(null, "video sink: invalid frame size");
                return;
            }
            if (isConfig) lastConfig = data;
            // Keep draining the socket while backgrounded, but do not build a
            // queue that no decoder can consume. Surface attachment resets the
            // encoder and starts a fresh decodable generation.
            if (codec == null) return;
            // Try to drain immediately if there's a free input.
            while (!pending.isEmpty() && !freeInputs.isEmpty()) {
                submit(codec, pending.poll(), freeInputs.pollFirst());
            }
            boolean waiting = pending.needsKeyframe();
            if (!pending.offer(new VideoQueue.Frame(data, ptsUs, isConfig, isKeyframe))) {
                reset = !waiting && pending.needsKeyframe();
            } else {
                if (waiting && isKeyframe && !isConfig) {
                    Log.i("video sink: accepted keyframe after configure or overflow");
                }
                while (!pending.isEmpty() && !freeInputs.isEmpty()) {
                    submit(codec, pending.poll(), freeInputs.pollFirst());
                }
            }
        }
        if (reset && requestVideoReset != null) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastEncoderResetAtMs < MIN_ENCODER_RESET_INTERVAL_MS) {
                Log.w("video sink: input queue overflow, reset throttled");
            } else {
                lastEncoderResetAtMs = now;
                Log.w("video sink: input queue overflow, resetting encoder");
                requestVideoReset.run();
            }
        }
    }

    @Override
    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
        }
        teardownCodec(true);
    }

    // Tear down the current decoder and rebuild it at the new dimensions.
    // startCodecLocked replays the cached CSD into the new instance.
    @Override
    public void reconfigure(int codecFourcc, int width, int height) throws IOException {
        synchronized (lock) {
            if (released) return;
            // Disarm the deferred-start path for the window in which we
            // hold no codec: a setOutputSurface() landing between the
            // teardown and the configure below must not build a second
            // decoder behind our back.
            haveFormat = false;
        }
        teardownCodec(false);
        configure(codecFourcc, width, height);
    }

    // Claim the codec under `lock` so setOutputSurface() can never touch
    // an instance that is being released, and two callers cannot both
    // stop the same one. The stop/release themselves run unlocked: they
    // can take a while, and they do not wait on the callback looper, so
    // there is nothing to gain by holding the lock across them.
    private void teardownCodec(boolean clearConfig) {
        MediaCodec c;
        HandlerThread ht;
        synchronized (lock) {
            c = codec;
            codec = null;
            ht = handlerThread;
            handlerThread = null;
            handler = null;
            freeInputs.clear();
            pending.clear();
            if (clearConfig) lastConfig = null;
        }
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) {}
            try { c.release(); } catch (Exception ignored) {}
        }
        if (ht != null) ht.quitSafely();
    }

    // Internal - runs on the MediaCodec callback thread.
    private void onFreeInput(MediaCodec mc, int idx) {
        synchronized (lock) {
            if (released || mc != codec) return;
            if (!pending.isEmpty()) submit(mc, pending.poll(), idx);
            else                    freeInputs.offerLast(idx);
        }
    }

    // Must be called with `lock` held. codec can be null mid-reconfigure
    // (teardownCodec runs unlocked); the frame is dropped like any other
    // back-pressure casualty.
    private void submit(MediaCodec mc, VideoQueue.Frame f, int idx) {
        if (mc == null || mc != codec) return;
        try {
            ByteBuffer buf = mc.getInputBuffer(idx);
            if (buf == null || f.data.length > buf.capacity()) {
                reportFatal(null, "video sink: frame exceeds codec input ("
                        + f.data.length + " bytes)");
                return;
            }
            buf.clear();
            buf.put(f.data);
            int flags = f.config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
            mc.queueInputBuffer(idx, 0, f.data.length, f.ptsUs, flags);
        } catch (IllegalStateException e) {
            reportFatal(e, "video sink: queueInputBuffer");
        }
    }

    private void reportFatal(Exception error, String message) {
        if (!fatalReported.compareAndSet(false, true)) return;
        if (error == null) Log.e("%s", message);
        else Log.e(error, "%s", message);
        if (onFatalError != null) onFatalError.run();
    }

    private static String mimeFor(int fourcc) {
        switch (fourcc) {
            case Wire.CODEC_H264: return MediaFormat.MIMETYPE_VIDEO_AVC;
            case Wire.CODEC_H265: return MediaFormat.MIMETYPE_VIDEO_HEVC;
            case Wire.CODEC_AV1:  return MediaFormat.MIMETYPE_VIDEO_AV1;
            default: return null;
        }
    }
}
