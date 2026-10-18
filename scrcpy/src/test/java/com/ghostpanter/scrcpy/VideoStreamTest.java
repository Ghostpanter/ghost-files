package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VideoStreamTest {

    // ---- recording sink ----

    private static final class RecordingFrames implements VideoFrames {
        static final class Cfg  { final int fourcc, w, h; Cfg(int f,int w,int h){this.fourcc=f;this.w=w;this.h=h;} }
        static final class Feed { final byte[] data; final long ptsUs; final boolean isCfg, isKey;
                                  Feed(byte[] d,long p,boolean c,boolean k){data=d;ptsUs=p;isCfg=c;isKey=k;} }

        final List<Cfg>  configs    = new ArrayList<>();
        final List<Cfg>  reconfigs  = new ArrayList<>();
        final List<Feed> feeds      = new ArrayList<>();
        int releases = 0;

        @Override public void configure(int f, int w, int h)    { configs.add(new Cfg(f, w, h)); }
        @Override public void reconfigure(int f, int w, int h)  { reconfigs.add(new Cfg(f, w, h)); }
        @Override public void feed(byte[] d, long pts, boolean c, boolean k){ feeds.add(new Feed(d, pts, c, k)); }
        @Override public void release() { releases++; }
    }

    // ---- byte builders ----

    private static byte[] fourcc(int v) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new DataOutputStream(bos).writeInt(v);
        return bos.toByteArray();
    }

    private static byte[] sessionMeta(int w, int h, boolean clientResize) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x80000000 | (clientResize ? 1 : 0));
        out.writeInt(w);
        out.writeInt(h);
        return bos.toByteArray();
    }

    private static byte[] frame(long ptsAndFlags, byte[] payload) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeLong(ptsAndFlags);
        out.writeInt(payload.length);
        out.write(payload);
        return bos.toByteArray();
    }

    private static byte[] cat(byte[]... parts) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) bos.write(p);
        return bos.toByteArray();
    }

    private static void runUntilEof(VideoStream s) {
        s.run(); // returns when the InputStream hits EOF (IOException)
    }

    // ---- tests ----

    @Test
    public void happyPath_configureCsdKeyframeDelta() throws Exception {
        byte[] csd      = new byte[]{0x67, 0x42, (byte)0xe0, 0x1e}; // bogus SPS-ish
        byte[] keyframe = new byte[]{1, 2, 3, 4, 5};
        byte[] delta    = new byte[]{6, 7, 8};

        long PTS_CFG  = 1L << 62;             // CONFIG
        long PTS_KEY  = (1L << 61) | 1_000_000L; // KEYFRAME, pts=1s
        long PTS_DELT = 2_000_000L;           // pts=2s

        byte[] bytes = cat(
                fourcc(Wire.CODEC_H264),
                sessionMeta(1080, 2400, false),
                frame(PTS_CFG,  csd),
                frame(PTS_KEY,  keyframe),
                frame(PTS_DELT, delta));

        RecordingFrames sink = new RecordingFrames();
        VideoStream s = new VideoStream(new ByteArrayInputStream(bytes), sink,
                (w, h) -> {});
        runUntilEof(s);

        assertEquals(1, sink.configs.size());
        assertEquals(Wire.CODEC_H264, sink.configs.get(0).fourcc);
        assertEquals(1080, sink.configs.get(0).w);
        assertEquals(2400, sink.configs.get(0).h);

        assertEquals(3, sink.feeds.size());
        assertTrue("first is config", sink.feeds.get(0).isCfg);
        assertEquals(0L, sink.feeds.get(0).ptsUs); // CONFIG strips pts to 0
        assertEquals(4,  sink.feeds.get(0).data.length);

        // keyframe: keyframe flag is stripped from ptsUs
        assertEquals(1_000_000L, sink.feeds.get(1).ptsUs);
        assertEquals(5,          sink.feeds.get(1).data.length);
        assertTrue("second is keyframe", sink.feeds.get(1).isKey);

        assertEquals(2_000_000L, sink.feeds.get(2).ptsUs);
    }

    @Test
    public void sessionMetaResizeTriggersReconfigure() throws Exception {
        byte[] bytes = cat(
                fourcc(Wire.CODEC_H264),
                sessionMeta(1080, 2400, false),
                frame(1L << 62, new byte[]{1}),     // config
                sessionMeta(720, 1600, true),       // resize
                frame(1L << 61, new byte[]{2}));    // keyframe at new size

        RecordingFrames sink = new RecordingFrames();
        new VideoStream(new ByteArrayInputStream(bytes), sink, null).run();

        assertEquals(1, sink.configs.size());
        assertEquals(1, sink.reconfigs.size());
        assertEquals(720,  sink.reconfigs.get(0).w);
        assertEquals(1600, sink.reconfigs.get(0).h);
    }

    @Test
    public void sizeListenerFiresOnEachSessionMeta() throws Exception {
        byte[] bytes = cat(
                fourcc(Wire.CODEC_H264),
                sessionMeta(1080, 2400, false),
                frame(1L << 62, new byte[]{1}),
                sessionMeta(720, 1600, false),
                frame(1L << 61, new byte[]{2}));

        List<int[]> sizes = new ArrayList<>();
        RecordingFrames sink = new RecordingFrames();
        new VideoStream(new ByteArrayInputStream(bytes), sink,
                (w, h) -> sizes.add(new int[]{w, h})).run();

        assertEquals(2, sizes.size());
        assertEquals(1080, sizes.get(0)[0]);
        assertEquals(720,  sizes.get(1)[0]);
    }

    @Test
    public void disabledCodecExits() throws Exception {
        byte[] bytes = fourcc(0);
        RecordingFrames sink = new RecordingFrames();
        new VideoStream(new ByteArrayInputStream(bytes), sink, null).run();
        assertEquals(0, sink.configs.size());
        assertEquals(0, sink.feeds.size());
    }

    @Test
    public void frameBeforeSessionMetaIsRejected() throws Exception {
        // A frame header arriving without a preceding session-meta should
        // raise - the stream is unconfigured.
        byte[] bytes = cat(
                fourcc(Wire.CODEC_H264),
                frame(1L << 62, new byte[]{1, 2, 3}));   // config-flag set, but no meta first
        RecordingFrames sink = new RecordingFrames();
        new VideoStream(new ByteArrayInputStream(bytes), sink, null).run();
        // The loop should have aborted without feeding anything.
        assertEquals(0, sink.feeds.size());
        assertEquals(0, sink.configs.size());
    }

    @Test
    public void payloadIntegrity() throws Exception {
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xff);
        byte[] bytes = cat(
                fourcc(Wire.CODEC_H264),
                sessionMeta(640, 480, false),
                frame(1L << 62, payload));

        RecordingFrames sink = new RecordingFrames();
        new VideoStream(new ByteArrayInputStream(bytes), sink, null).run();
        assertNotNull(sink.feeds.isEmpty() ? null : sink.feeds.get(0));
        byte[] got = sink.feeds.get(0).data;
        assertEquals(payload.length, got.length);
        for (int i = 0; i < payload.length; i++) {
            assertEquals("byte " + i, payload[i], got[i]);
        }
    }
}
