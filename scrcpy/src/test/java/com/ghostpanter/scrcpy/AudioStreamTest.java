package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AudioStreamTest {

    private static final long FLAG_CONFIG = 1L << 62;
    private static final long FLAG_KEYFRAME = 1L << 61;

    private static final class RecordingFrames implements AudioFrames {
        int starts = 0, releases = 0, startFourcc = 0;
        final List<byte[]>  feeds = new ArrayList<>();
        final List<Long>     pts   = new ArrayList<>();
        final List<Boolean> cfgs  = new ArrayList<>();
        @Override public void start(int fourcc) { starts++; startFourcc = fourcc; }
        @Override public void feed(byte[] data, int off, int len,
                                   long ptsUs, boolean isConfig) {
            byte[] cp = new byte[len];
            System.arraycopy(data, off, cp, 0, len);
            feeds.add(cp);
            pts.add(ptsUs);
            cfgs.add(isConfig);
        }
        @Override public void release() { releases++; }
    }

    private static byte[] fourcc(int v) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new DataOutputStream(bos).writeInt(v);
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

    @Test
    public void rawCodecFeedsFrames() throws Exception {
        byte[] pcm1 = new byte[]{1, 2, 3, 4};
        byte[] pcm2 = new byte[]{5, 6, 7, 8, 9, 10};
        byte[] bytes = cat(
                fourcc(Wire.CODEC_RAW),
                frame(1_000_000L, pcm1),
                frame(2_000_000L, pcm2));

        RecordingFrames sink = new RecordingFrames();
        new AudioStream(new ByteArrayInputStream(bytes), sink).run();

        assertEquals(1, sink.starts);
        assertEquals(Wire.CODEC_RAW, sink.startFourcc);
        assertEquals(2, sink.feeds.size());
        assertEquals(pcm1.length, sink.feeds.get(0).length);
        assertEquals(pcm2.length, sink.feeds.get(1).length);
        for (int i = 0; i < pcm1.length; i++) assertEquals(pcm1[i], sink.feeds.get(0)[i]);
        for (int i = 0; i < pcm2.length; i++) assertEquals(pcm2[i], sink.feeds.get(1)[i]);
        assertEquals(false, sink.cfgs.get(0));
        assertEquals(false, sink.cfgs.get(1));
        assertEquals(Long.valueOf(1_000_000L), sink.pts.get(0));
        assertEquals(Long.valueOf(2_000_000L), sink.pts.get(1));
    }

    @Test
    public void opusConfigFlagIsExposed() throws Exception {
        byte[] head = new byte[]{
                'O','p','u','s','H','e','a','d', // magic
                1,                                // version
                2,                                // channel count
                (byte)0x38, 0x01,                 // pre-skip = 312 LE
                (byte)0x80, (byte)0xBB, 0, 0,     // sample rate 48000 LE
                0, 0,                             // output gain
                0                                 // channel mapping family
        };
        byte[] pkt = new byte[]{0x10, 0x20, 0x30};
        byte[] bytes = cat(
                fourcc(Wire.CODEC_OPUS),
                frame(FLAG_CONFIG, head),
                frame(FLAG_KEYFRAME | 3_000_000L, pkt));

        RecordingFrames sink = new RecordingFrames();
        new AudioStream(new ByteArrayInputStream(bytes), sink).run();

        assertEquals(1, sink.starts);
        assertEquals(Wire.CODEC_OPUS, sink.startFourcc);
        assertEquals(2, sink.feeds.size());
        assertTrue("first frame must carry FLAG_CONFIG", sink.cfgs.get(0));
        assertTrue("second frame must not carry FLAG_CONFIG", !sink.cfgs.get(1));
        assertEquals(head.length, sink.feeds.get(0).length);
        assertEquals(pkt.length, sink.feeds.get(1).length);
        assertEquals(Long.valueOf(0L), sink.pts.get(0));
        assertEquals(Long.valueOf(3_000_000L), sink.pts.get(1));
    }

    @Test
    public void disabledCodecDoesNotStart() throws Exception {
        byte[] bytes = fourcc(0);
        RecordingFrames sink = new RecordingFrames();
        new AudioStream(new ByteArrayInputStream(bytes), sink).run();
        assertEquals(0, sink.starts);
        assertEquals(0, sink.feeds.size());
    }

    @Test
    public void errorCodecDoesNotStart() throws Exception {
        byte[] bytes = fourcc(1);
        RecordingFrames sink = new RecordingFrames();
        new AudioStream(new ByteArrayInputStream(bytes), sink).run();
        assertEquals(0, sink.starts);
        assertEquals(0, sink.feeds.size());
    }

    @Test
    public void outputFailureStillDrainsWire() throws Exception {
        byte[] bytes = cat(
                fourcc(Wire.CODEC_RAW),
                frame(1_000_000L, new byte[]{1, 2, 3, 4}));
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        AudioFrames sink = new AudioFrames() {
            @Override public void start(int fourcc) {
                throw new IllegalStateException("no audio output");
            }
            @Override public void feed(byte[] data, int off, int len,
                                       long ptsUs, boolean isConfig) {
                throw new AssertionError("disabled output must not receive frames");
            }
            @Override public void release() {}
        };

        new AudioStream(in, sink).run();

        assertEquals(0, in.available());
    }
}
