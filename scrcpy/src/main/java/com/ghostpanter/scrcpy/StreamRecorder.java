package com.ghostpanter.scrcpy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Records the scrcpy mirror stream into MP4 without re-encoding video.
//
//  - Video packets are passed through (H.264 / H.265 Annex-B; AV1 when the muxer supports it).
//    Config packets (SPS/PPS/VPS, AV1 sequence header) become the track's csd and are not
//    written as samples. A segment starts on the first keyframe after start(); timestamps are
//    rebased to that keyframe and forced strictly increasing.
//  - Audio: Opus passthrough when the muxer supports it, else the decoded PCM tap (and raw PCM)
//    is encoded by the sink. Audio before the first video sample is dropped.
//  - A resolution / codec-config change (rotation) closes the file and continues in a new
//    segment (MP4 tracks cannot change format mid-file).
//
// Thread-safe: video, audio and UI threads call in concurrently.
public final class StreamRecorder {

    public interface Listener {
        void onSegment(String location);           // a finished file
        void onError(Exception e);
    }

    public static final int AUDIO_NONE = 0, AUDIO_OPUS = 1, AUDIO_PCM = 2;
    public static final int PCM_RATE = 48_000, PCM_CHANNELS = 2;

    private final Mp4Sink.Factory factory;
    private final Listener listener;
    private final List<String> finished = new ArrayList<>();

    // stream state (known before / during recording)
    private int fourcc, width, height;
    private byte[] config;
    private int audioMode = AUDIO_NONE;
    private byte[] opusHead;

    // recording state
    private boolean recording;
    private Mp4Sink sink;
    private int segment;
    private int videoTrack = -1, audioTrack = -1;
    private boolean audioPassthrough;
    private long basePts = -1, lastVideoPts = -1, lastAudioPts = -1;
    private long videoSamples, audioSamples;

    public StreamRecorder(Mp4Sink.Factory factory, Listener listener) {
        this.factory = factory;
        this.listener = listener;
    }

    // ---------- stream taps ----------

    public synchronized void onVideoFormat(int codec, int w, int h) {
        boolean changed = recording && sink != null && (codec != fourcc || w != width || h != height);
        fourcc = codec; width = w; height = h;
        if (changed) rotate();
    }

    public synchronized void onVideoPacket(byte[] data, int off, int len, long ptsUs, boolean isConfig, boolean keyframe) {
        if (isConfig) {
            byte[] c = Arrays.copyOfRange(data, off, off + len);
            boolean changed = config != null && !Arrays.equals(config, c);
            config = c;
            if (changed && recording && sink != null) rotate();
            return;
        }
        if (!recording) return;
        try {
            if (sink == null) {
                if (!keyframe || config == null || width <= 0) return;
                begin(ptsUs);
            }
            long t = Math.max(0, ptsUs - basePts);
            if (t <= lastVideoPts) t = lastVideoPts + 1;
            lastVideoPts = t;
            sink.writeVideo(videoTrack, data, off, len, t, keyframe);
            videoSamples++;
        } catch (Exception e) {
            fail(e);
        }
    }

    /** The audio wire codec (Wire.CODEC_OPUS / CODEC_RAW); call when the audio stream starts. */
    public synchronized void onAudioStart(int codec) {
        if (codec == Wire.CODEC_OPUS) audioMode = factory.supportsOpusPassthrough() ? AUDIO_OPUS : AUDIO_PCM;
        else if (codec == Wire.CODEC_RAW) audioMode = AUDIO_PCM;
        else audioMode = AUDIO_NONE;
    }

    public synchronized void onAudioPacket(byte[] data, int off, int len, long ptsUs, boolean isConfig) {
        if (audioMode != AUDIO_OPUS) return;
        if (isConfig) { opusHead = Arrays.copyOfRange(data, off, off + len); return; }
        if (sink == null || audioTrack < 0 || !audioPassthrough) return;
        writeAudio(data, off, len, ptsUs, true);
    }

    /** Decoded (or raw) PCM s16le 48 kHz stereo with its wire pts. */
    public synchronized void onPcm(byte[] data, int off, int len, long ptsUs) {
        if (audioMode != AUDIO_PCM || sink == null || audioTrack < 0 || audioPassthrough) return;
        writeAudio(data, off, len, ptsUs, false);
    }

    private void writeAudio(byte[] data, int off, int len, long ptsUs, boolean opus) {
        if (ptsUs < basePts) return;
        long t = ptsUs - basePts;
        if (t <= lastAudioPts) t = lastAudioPts + 1;
        lastAudioPts = t;
        try {
            if (opus) sink.writeOpus(audioTrack, data, off, len, t);
            else sink.writePcm(audioTrack, data, off, len, t);
            audioSamples++;
        } catch (Exception e) {
            fail(e);
        }
    }

    // ---------- control ----------

    /** Starts recording; the file opens on the next keyframe. Throws if the codec cannot be muxed. */
    public synchronized void start() throws IOException {
        if (recording) return;
        if (fourcc != 0 && !factory.supportsVideo(fourcc)) {
            throw new UnsupportedCodecException(fourcc);
        }
        recording = true;
        segment = 0;
        finished.clear();
    }

    public synchronized boolean isRecording() { return recording; }

    /** True once the current segment has its first frame (i.e. data is being written). */
    public synchronized boolean isWriting() { return sink != null; }

    /** Stops and finalizes. Returns the finished files (segments) in order. */
    public synchronized List<String> stop() {
        if (!recording) return new ArrayList<>(finished);
        recording = false;
        closeSink();
        return new ArrayList<>(finished);
    }

    public synchronized long videoSamples() { return videoSamples; }
    public synchronized long audioSamples() { return audioSamples; }

    // ---------- internals ----------

    private void begin(long ptsUs) throws IOException {
        if (!factory.supportsVideo(fourcc)) throw new UnsupportedCodecException(fourcc);
        Mp4Sink s = factory.open(++segment);
        try {
            videoTrack = s.addVideo(fourcc, width, height, csdFor(fourcc, config, width, height));
            audioTrack = -1;
            audioPassthrough = false;
            if (audioMode == AUDIO_OPUS && opusHead != null) {
                audioTrack = s.addOpus(opusHead);
                audioPassthrough = true;
            } else if (audioMode == AUDIO_PCM) {
                audioTrack = s.addPcm(PCM_RATE, PCM_CHANNELS);
            }
            s.start();
        } catch (IOException | RuntimeException e) {
            try { s.finish(); } catch (Exception ignored) {}
            throw e;
        }
        sink = s;
        basePts = ptsUs;
        lastVideoPts = -1;
        lastAudioPts = -1;
    }

    private void rotate() {
        closeSink();
        // next keyframe opens segment+1 with the new format
    }

    private void closeSink() {
        Mp4Sink s = sink;
        sink = null;
        videoTrack = audioTrack = -1;
        if (s == null) return;
        try {
            String loc = s.finish();
            if (loc != null) {
                finished.add(loc);
                if (listener != null) listener.onSegment(loc);
            }
        } catch (Exception e) {
            if (listener != null) listener.onError(e);
        }
    }

    private void fail(Exception e) {
        Log.e(e, "recorder: write failed");
        recording = false;
        closeSink();
        if (listener != null) listener.onError(e);
    }

    public static final class UnsupportedCodecException extends IOException {
        public final int fourcc;
        public UnsupportedCodecException(int fourcc) {
            super("recording " + Wire.fourccName(fourcc) + " is not supported by this device's muxer");
            this.fourcc = fourcc;
        }
    }

    // ---------- codec specific data ----------

    /** csd buffers for MediaFormat: AVC → [SPS, PPS] (start codes kept); HEVC → [VPS+SPS+PPS]; AV1 → [av1C]. */
    public static byte[][] csdFor(int fourcc, byte[] config, int w, int h) {
        if (fourcc == Wire.CODEC_H264) {
            List<byte[]> nals = splitAnnexB(config);
            byte[] sps = null, pps = null;
            for (byte[] n : nals) {
                int type = nalType(n) & 0x1f;
                if (type == 7 && sps == null) sps = n;
                else if (type == 8 && pps == null) pps = n;
            }
            if (sps != null && pps != null) return new byte[][] { sps, pps };
            return new byte[][] { config };
        }
        if (fourcc == Wire.CODEC_AV1) return new byte[][] { av1c(config) };
        return new byte[][] { config };
    }

    /** NAL units with their start codes (00 00 01 / 00 00 00 01). */
    public static List<byte[]> splitAnnexB(byte[] b) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 2 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                int s = (i > 0 && b[i - 1] == 0) ? i - 1 : i;
                starts.add(s);
                i += 2;
            }
        }
        List<byte[]> out = new ArrayList<>();
        for (int k = 0; k < starts.size(); k++) {
            int s = starts.get(k);
            int e = k + 1 < starts.size() ? starts.get(k + 1) : b.length;
            out.add(Arrays.copyOfRange(b, s, e));
        }
        return out;
    }

    /** First byte after the start code. */
    static int nalType(byte[] nal) {
        int i = 0;
        while (i < nal.length && nal[i] == 0) i++;
        return i + 1 < nal.length ? nal[i + 1] & 0xff : 0;
    }

    /**
     * AV1CodecConfigurationRecord (ISO/IEC av1-isobmff 2.3) from the sequence header OBU(s):
     * marker/version, seq_profile + seq_level_idx_0 parsed from the header, 8-bit 4:2:0 defaults
     * for the rest, followed by the config OBUs.
     */
    public static byte[] av1c(byte[] obus) {
        int profile = 0, level = 8, tier = 0;
        try {
            int p = 0;
            // OBU header: forbidden(1) type(4) extension(1) has_size(1) reserved(1)
            int hdr = obus[p] & 0xff;
            boolean ext = (hdr & 0x04) != 0, hasSize = (hdr & 0x02) != 0;
            p += 1 + (ext ? 1 : 0);
            if (hasSize) { while ((obus[p] & 0x80) != 0) p++; p++; } // leb128
            Bits r = new Bits(obus, p);
            profile = r.read(3);
            r.read(1); // still_picture
            boolean reduced = r.read(1) == 1;
            if (reduced) {
                level = r.read(5);
            } else {
                boolean timing = r.read(1) == 1;
                if (!timing) {
                    boolean initialDelay = r.read(1) == 1;
                    r.read(5); // operating_points_cnt_minus_1 (first op only)
                    r.read(12); // operating_point_idc[0]
                    level = r.read(5);
                    if (level > 7) tier = r.read(1);
                    if (initialDelay && r.read(1) == 1) r.read(4);
                }
            }
        } catch (RuntimeException ignored) {
            // keep defaults
        }
        byte[] out = new byte[4 + obus.length];
        out[0] = (byte) 0x81;
        out[1] = (byte) (((profile & 7) << 5) | (level & 0x1f));
        // tier, high_bitdepth=0, twelve_bit=0, monochrome=0, ss_x=1, ss_y=1, chroma_pos=0
        out[2] = (byte) ((tier << 7) | 0x0C);
        out[3] = 0;
        System.arraycopy(obus, 0, out, 4, obus.length);
        return out;
    }

    private static final class Bits {
        private final byte[] b; private int bit;
        Bits(byte[] b, int byteOff) { this.b = b; this.bit = byteOff * 8; }
        int read(int n) {
            int v = 0;
            for (int i = 0; i < n; i++, bit++) v = (v << 1) | ((b[bit >> 3] >> (7 - (bit & 7))) & 1);
            return v;
        }
    }
}
