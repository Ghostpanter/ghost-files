package com.ghostpanter.scrcpy;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StreamRecorderTest {

    static final class Call {
        final String kind; final int track; final long pts; final boolean key; final int len;
        Call(String kind, int track, long pts, boolean key, int len) { this.kind = kind; this.track = track; this.pts = pts; this.key = key; this.len = len; }
        @Override public String toString() { return kind + "@" + pts + (key ? "K" : ""); }
    }

    static final class FakeSink implements Mp4Sink {
        final int segment;
        final List<Call> calls = new ArrayList<>();
        byte[][] videoCsd; int w, h, fourcc;
        byte[] opusHead; boolean pcm; boolean started, finished;
        FakeSink(int segment) { this.segment = segment; }
        @Override public int addVideo(int fourcc, int width, int height, byte[][] csd) { this.fourcc = fourcc; w = width; h = height; videoCsd = csd; return 0; }
        @Override public int addOpus(byte[] head) { opusHead = head; return 1; }
        @Override public int addPcm(int rate, int ch) { pcm = true; return 1; }
        @Override public void start() { started = true; }
        @Override public void writeVideo(int t, byte[] d, int o, int l, long pts, boolean k) { assertTrue(started); calls.add(new Call("v", t, pts, k, l)); }
        @Override public void writeOpus(int t, byte[] d, int o, int l, long pts) { assertTrue(started); calls.add(new Call("opus", t, pts, false, l)); }
        @Override public void writePcm(int t, byte[] d, int o, int l, long pts) { assertTrue(started); calls.add(new Call("pcm", t, pts, false, l)); }
        @Override public String finish() { finished = true; return calls.isEmpty() ? null : "seg" + segment + ".mp4"; }
    }

    static final class FakeFactory implements Mp4Sink.Factory {
        final List<FakeSink> sinks = new ArrayList<>();
        boolean opus = true, av1 = false;
        @Override public Mp4Sink open(int segment) { FakeSink s = new FakeSink(segment); sinks.add(s); return s; }
        @Override public boolean supportsVideo(int fourcc) { return fourcc != Wire.CODEC_AV1 || av1; }
        @Override public boolean supportsOpusPassthrough() { return opus; }
    }

    static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f};
    static final byte[] PPS = {0, 0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80};
    static byte[] concat(byte[] a, byte[] b) { byte[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }
    static final byte[] FRAME = {0, 0, 0, 1, 0x65, 1, 2, 3};
    static final byte[] HEAD = new byte[19];

    private StreamRecorder rec(FakeFactory f, List<String> segs) {
        return new StreamRecorder(f, new StreamRecorder.Listener() {
            @Override public void onSegment(String location) { segs.add(location); }
            @Override public void onError(Exception e) { throw new AssertionError(e); }
        });
    }

    private void config(StreamRecorder r) {
        r.onVideoFormat(Wire.CODEC_H264, 1080, 2400);
        r.onVideoPacket(concat(SPS, PPS), 0, 16, 0, true, false);
    }

    @Test public void startsOnKeyframeSplitsSpsPpsAndRebasesPts() throws IOException {
        FakeFactory f = new FakeFactory();
        List<String> segs = new ArrayList<>();
        StreamRecorder r = rec(f, segs);
        config(r);
        r.onVideoPacket(FRAME, 0, FRAME.length, 1_000, false, true);   // before start: ignored
        r.start();
        assertTrue(f.sinks.isEmpty());
        r.onVideoPacket(FRAME, 0, FRAME.length, 2_000, false, false);  // not a keyframe: waits
        assertTrue(f.sinks.isEmpty());
        r.onVideoPacket(FRAME, 0, FRAME.length, 5_000, false, true);
        r.onVideoPacket(FRAME, 0, FRAME.length, 5_000, false, false);  // duplicate pts -> +1
        r.onVideoPacket(FRAME, 0, FRAME.length, 38_333, false, false);
        FakeSink s = f.sinks.get(0);
        assertEquals(1080, s.w); assertEquals(2400, s.h);
        assertEquals(2, s.videoCsd.length);
        assertArrayEquals(SPS, s.videoCsd[0]);
        assertArrayEquals(PPS, s.videoCsd[1]);
        assertEquals("[v@0K, v@1, v@33333]", s.calls.toString());
        assertEquals(List.of("seg1.mp4"), r.stop());
        assertTrue(s.finished);
        assertEquals(List.of("seg1.mp4"), segs);
    }

    @Test public void opusPassthroughDropsAudioBeforeVideoBase() throws IOException {
        FakeFactory f = new FakeFactory();
        StreamRecorder r = rec(f, new ArrayList<>());
        config(r);
        r.onAudioStart(Wire.CODEC_OPUS);
        r.onAudioPacket(HEAD, 0, HEAD.length, 0, true);
        r.start();
        r.onAudioPacket(new byte[40], 0, 40, 9_000, false);            // no file yet
        r.onVideoPacket(FRAME, 0, FRAME.length, 10_000, false, true);
        r.onAudioPacket(new byte[40], 0, 40, 9_500, false);            // older than base: dropped
        r.onAudioPacket(new byte[40], 0, 40, 30_000, false);
        r.onPcm(new byte[64], 0, 64, 31_000);                          // pcm ignored in passthrough
        FakeSink s = f.sinks.get(0);
        assertArrayEquals(HEAD, s.opusHead);
        assertEquals("[v@0K, opus@20000]", s.calls.toString());
        r.stop();
    }

    @Test public void pcmWhenMuxerCannotTakeOpus() throws IOException {
        FakeFactory f = new FakeFactory();
        f.opus = false;
        StreamRecorder r = rec(f, new ArrayList<>());
        config(r);
        r.onAudioStart(Wire.CODEC_OPUS);
        r.onAudioPacket(HEAD, 0, HEAD.length, 0, true);
        r.start();
        r.onVideoPacket(FRAME, 0, FRAME.length, 100, false, true);
        r.onAudioPacket(new byte[40], 0, 40, 200, false);              // compressed: ignored
        r.onPcm(new byte[3840], 0, 3840, 300);
        FakeSink s = f.sinks.get(0);
        assertTrue(s.pcm);
        assertNull(s.opusHead);
        assertEquals("[v@0K, pcm@200]", s.calls.toString());
    }

    @Test public void rotationStartsANewSegmentOnNextKeyframe() throws IOException {
        FakeFactory f = new FakeFactory();
        List<String> segs = new ArrayList<>();
        StreamRecorder r = rec(f, segs);
        config(r);
        r.start();
        r.onVideoPacket(FRAME, 0, FRAME.length, 0, false, true);
        r.onVideoFormat(Wire.CODEC_H264, 2400, 1080);                   // rotated
        assertEquals(List.of("seg1.mp4"), segs);
        assertFalse(r.isWriting());
        r.onVideoPacket(FRAME, 0, FRAME.length, 50, false, false);      // waits for keyframe
        r.onVideoPacket(FRAME, 0, FRAME.length, 90, false, true);
        assertEquals(2, f.sinks.size());
        assertEquals(2400, f.sinks.get(1).w);
        assertEquals("[v@0K]", f.sinks.get(1).calls.toString());
        assertEquals(List.of("seg1.mp4", "seg2.mp4"), r.stop());
    }

    @Test public void changedConfigAlsoRotates() throws IOException {
        FakeFactory f = new FakeFactory();
        List<String> segs = new ArrayList<>();
        StreamRecorder r = rec(f, segs);
        config(r);
        r.start();
        r.onVideoPacket(FRAME, 0, FRAME.length, 0, false, true);
        r.onVideoPacket(concat(SPS, PPS), 0, 16, 10, true, false);      // same config: no rotation
        assertTrue(r.isWriting());
        byte[] sps2 = SPS.clone(); sps2[6] = 0x28;
        r.onVideoPacket(concat(sps2, PPS), 0, 16, 20, true, false);
        assertFalse(r.isWriting());
        assertEquals(1, segs.size());
    }

    @Test(expected = StreamRecorder.UnsupportedCodecException.class)
    public void av1WithoutMuxerSupportIsRejectedSoCallerFallsBack() throws IOException {
        FakeFactory f = new FakeFactory();
        StreamRecorder r = rec(f, new ArrayList<>());
        r.onVideoFormat(Wire.CODEC_AV1, 1920, 1080);
        r.start();
    }

    @Test public void av1cFromSequenceHeader() {
        // OBU header: type 1 (sequence header), has_size; size 3; payload bits:
        // profile=0 (000) still=0 reduced=0 timing=0 initDelay=0 opcnt=00000 idc=000000000000 level=01000 tier=0
        // -> 0000 0000 | 0000 0000 | 0000 0000 | 0100 0000 (padded)
        byte[] obu = {0x0A, 0x04, 0x00, 0x00, 0x00, 0x40 };
        byte[] c = StreamRecorder.av1c(obu);
        assertEquals((byte) 0x81, c[0]);
        assertEquals(8, c[1] & 0x1f);
        assertEquals(0, (c[1] >> 5) & 7);
        assertEquals(0x0C, c[2] & 0xff);
        assertArrayEquals(obu, Arrays.copyOfRange(c, 4, c.length));
    }

    @Test public void annexBSplitKeepsStartCodes() {
        List<byte[]> nals = StreamRecorder.splitAnnexB(concat(SPS, new byte[] {0, 0, 1, 0x68, 1}));
        assertEquals(2, nals.size());
        assertArrayEquals(SPS, nals.get(0));
        assertArrayEquals(new byte[] {0, 0, 1, 0x68, 1}, nals.get(1));
        byte[][] hevc = StreamRecorder.csdFor(Wire.CODEC_H265, SPS, 1, 1);
        assertEquals(1, hevc.length);
    }
}
