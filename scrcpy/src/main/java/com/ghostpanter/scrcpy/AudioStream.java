package com.ghostpanter.scrcpy;

import java.io.IOException;
import java.io.InputStream;

// Reads the scrcpy audio socket and drives an AudioFrames sink.
//
// Wire format (big-endian, scrcpy 3.x/4.x):
//   1) Stream meta:    uint32 fourcc
//                      special values 0 = disabled, 1 = error
//   2) Loop: 12-byte frame header (uint64 ptsAndFlags | uint32 size)
//      followed by `size` bytes of payload.
//
// We honour FLAG_CONFIG on the per-frame header: for opus the first
// frame is the OpusHead (sent with FLAG_CONFIG set), subsequent are
// opus packets. For raw PCM the server never sets FLAG_CONFIG.
//
// Takes a plain InputStream; caller owns stream lifecycle.
public final class AudioStream {

    private static final long FLAG_SESSION  = 1L << 63;
    private static final long FLAG_CONFIG   = 1L << 62;
    private static final long FLAG_KEYFRAME = 1L << 61;
    private static final long PTS_MASK      = ~(FLAG_SESSION | FLAG_CONFIG | FLAG_KEYFRAME);

    // Generous upper bound for one audio packet (a raw PCM block or an
    // opus packet is a few KB). A corrupt or hostile length field must
    // not drive the allocation below.
    private static final int MAX_FRAME_SIZE = 256 * 1024;

    private final InputStream  source;
    private final AudioFrames  sink;
    private Thread             thread;
    private volatile boolean   stop;

    public AudioStream(InputStream source, AudioFrames sink) {
        this.source = source;
        this.sink = sink;
    }

    public void start() {
        thread = new Thread(this::run, "audio-reader");
        thread.start();
    }

    public void stop() {
        stop = true;
        Thread t = thread;
        if (t == null) return;
        t.interrupt();
        if (t == Thread.currentThread()) return;
        try { t.join(1_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void run() {
        try {
            byte[] four = new byte[4];
            Wire.readFully(source, four);
            int fourcc = Wire.readBe32(four, 0);
            if (fourcc == 0) {
                Log.w("audio: server reports stream disabled (target cannot capture)");
                return;
            }
            if (fourcc == 1) {
                // Soft-land like fourcc==0: do not throw a scary IOException.
                // Audio never countDowns session endSignal.
                Log.w("audio: server reports stream error; continuing without audio");
                return;
            }
            if (fourcc != Wire.CODEC_RAW && fourcc != Wire.CODEC_OPUS) {
                throw new IOException("audio: unexpected codec " + Wire.fourccName(fourcc));
            }
            Log.i("audio meta codec=%s", Wire.fourccName(fourcc));
            boolean playback = true;
            try {
                sink.start(fourcc);
            } catch (RuntimeException e) {
                Log.e(e, "audio output disabled");
                playback = false;
            }

            byte[] hdr = new byte[12];
            byte[] payload = new byte[16 * 1024];
            long frames = 0;
            while (!stop) {
                Wire.readFully(source, hdr);
                long ptsAndFlags = Wire.readBe64(hdr, 0);
                int  size = Wire.readBe32(hdr, 8);
                boolean cfg = (ptsAndFlags & FLAG_CONFIG) != 0;
                long ptsUs = ptsAndFlags & PTS_MASK;
                if (size <= 0 || size > MAX_FRAME_SIZE) {
                    throw new IOException("audio frame size out of range: " + size);
                }
                if (size > payload.length) payload = new byte[size];
                Wire.readFully(source, payload, 0, size);
                if (playback) {
                    try {
                        sink.feed(payload, 0, size, ptsUs, cfg);
                    } catch (RuntimeException e) {
                        Log.e(e, "audio output disabled");
                        playback = false;
                    }
                }
                if (++frames == 1) Log.i("audio frame n=1 size=%d cfg=%s", size, cfg);
            }
        } catch (IOException e) {
            if (!stop) Log.e(e, "audio reader");
        } catch (Exception e) {
            Log.e(e, "audio reader unexpected");
        } finally {
            Log.i("audio reader: end");
        }
    }
}
