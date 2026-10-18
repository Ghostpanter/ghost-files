package com.ghostpanter.scrcpy;

import java.io.IOException;
import java.io.InputStream;

// Reads the scrcpy video socket and drives a VideoFrames sink.
//
// Wire format (big-endian, scrcpy 3.x/4.x):
//   1) Stream meta:    uint32 fourcc
//                      special values 0 = disabled, 1 = error
//   2) Then a stream of variable packets. Each packet starts with 4 bytes
//      that disambiguate the type:
//      a) Session meta (12 bytes total): uint32 flags(bit31=1) | uint32 w | uint32 h
//         flags bit0 = client-resize.  May recur on rotation.
//      b) Frame header (12 bytes total): uint64 ptsAndFlags | uint32 size
//         ptsAndFlags top bits:
//             bit 63 = SESSION  (never set in frame headers)
//             bit 62 = CONFIG   (CSD, e.g. SPS/PPS for h264)
//             bit 61 = KEYFRAME
//             rest   = pts microseconds
//         followed by `size` bytes of encoded payload.
//
// The constructor takes a plain InputStream so this class is
// android-free and unit-testable. The caller owns stream lifecycle
// (close it to unblock reads on stop()).
public final class VideoStream {

    public interface SizeListener { void onSize(int w, int h); }

    private static final long FLAG_SESSION  = 1L << 63;
    private static final long FLAG_CONFIG   = 1L << 62;
    private static final long FLAG_KEYFRAME = 1L << 61;
    private static final long PTS_MASK      = ~(FLAG_SESSION | FLAG_CONFIG | FLAG_KEYFRAME);
    private static final int  FLAG_SESSION_INT_BIT = 0x80000000;

    private static final int MAX_FRAME_SIZE = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 16 * 1024;

    private final InputStream  source;
    private final VideoFrames  sink;
    private final SizeListener sizeListener;
    private volatile Runnable  onEnd;        // fired once when run() exits
    private Thread             thread;
    private volatile boolean   stop;

    // Parser state held across packets in run().
    private int     fourcc;
    private boolean configured;
    private int     curW, curH;

    public VideoStream(InputStream source, VideoFrames sink, SizeListener sizeListener) {
        this.source = source;
        this.sink = sink;
        this.sizeListener = sizeListener;
    }

    public void start() {
        thread = new Thread(this::run, "video-reader");
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

    // Fired exactly once, on the reader thread, when run() exits - whether
    // by EOF, error, or stop(). The video socket is the authoritative
    // stream: when it ends mid-session the link is gone, so Session uses
    // this to surface a disconnect to the UI instead of freezing.
    public void setOnEnd(Runnable r) {
        this.onEnd = r;
    }

    // Visible for tests: parse the same way the thread does, on the caller's thread.
    public void run() {
        try {
            byte[] four = new byte[4];
            Wire.readFully(source, four);
            fourcc = Wire.readBe32(four, 0);
            if (fourcc == 0)  throw new IOException("video: server reports stream disabled");
            if (fourcc == 1)  throw new IOException("video: server reports configuration error");
            if (fourcc != Wire.CODEC_H264 && fourcc != Wire.CODEC_H265
                    && fourcc != Wire.CODEC_AV1) {
                throw new IOException("video: unexpected codec " + Wire.fourccName(fourcc));
            }
            Log.i("video meta codec=%s", Wire.fourccName(fourcc));

            byte[] tail8 = new byte[8];
            long frames = 0;

            while (!stop) {
                Wire.readFully(source, four);
                int hi = Wire.readBe32(four, 0);

                if ((hi & FLAG_SESSION_INT_BIT) != 0) {
                    parseSessionMeta(hi, tail8);
                } else {
                    parseFrame(hi, tail8);
                    if (++frames == 1) Log.i("video frame n=1");
                }
            }
        } catch (IOException e) {
            if (!stop) Log.e(e, "video reader");
        } catch (Exception e) {
            Log.e(e, "video reader unexpected");
        } finally {
            Log.i("video reader: end");
            Runnable r = onEnd;
            if (r != null) r.run();
        }
    }

    private void parseSessionMeta(int hi, byte[] tail8) throws IOException {
        if ((hi & ~0x80000001) != 0) {
            throw new IOException("video session meta has unknown flags");
        }
        Wire.readFully(source, tail8);
        int newW = Wire.readBe32(tail8, 0);
        int newH = Wire.readBe32(tail8, 4);
        if (newW < 1 || newW > MAX_DIMENSION || newH < 1 || newH > MAX_DIMENSION) {
            throw new IOException("video size out of range: " + newW + "x" + newH);
        }
        boolean clientResize = (hi & 1) != 0;
        Log.i("video session meta %dx%d client_resize=%s", newW, newH, clientResize);

        if (!configured) {
            curW = newW; curH = newH;
            sink.configure(fourcc, curW, curH);
            configured = true;
        } else if (newW != curW || newH != curH) {
            Log.i("video resize %dx%d -> %dx%d", curW, curH, newW, newH);
            curW = newW; curH = newH;
            sink.reconfigure(fourcc, curW, curH);
        }
        if (sizeListener != null) sizeListener.onSize(curW, curH);
    }

    private void parseFrame(int hi, byte[] tail8) throws IOException {
        Wire.readFully(source, tail8);
        long pts = ((long) hi << 32) | (Wire.readBe32(tail8, 0) & 0xffffffffL);
        int  size = Wire.readBe32(tail8, 4);
        boolean cfg = (pts & FLAG_CONFIG) != 0;
        boolean keyframe = (pts & FLAG_KEYFRAME) != 0;
        long ptsUs = pts & PTS_MASK;

        if (size <= 0 || size > MAX_FRAME_SIZE) {
            throw new IOException("video frame size out of range: " + size);
        }
        // Validate ordering BEFORE allocating the payload buffer - a
        // malformed early frame could otherwise OOM on small devices.
        if (!configured) throw new IOException("video frame before session meta");

        byte[] payload = new byte[size];
        Wire.readFully(source, payload);
        sink.feed(payload, ptsUs, cfg, keyframe);
    }
}
