package com.ghostpanter.scrcpy;

import java.io.IOException;

// One MP4 file. MediaMuxerSink is the production implementation; tests record calls.
// Kept android-free so StreamRecorder's framing logic runs on the JVM.
public interface Mp4Sink {

    /** Adds the video track. csd = codec specific data buffers (csd-0, csd-1...) for the codec. */
    int addVideo(int fourcc, int width, int height, byte[][] csd) throws IOException;

    /** Opus passthrough track (API 29+ muxers). */
    int addOpus(byte[] opusHead) throws IOException;

    /** PCM s16le input that the sink encodes (AAC). */
    int addPcm(int sampleRate, int channels) throws IOException;

    void start() throws IOException;

    void writeVideo(int track, byte[] data, int off, int len, long ptsUs, boolean keyframe) throws IOException;

    void writeOpus(int track, byte[] data, int off, int len, long ptsUs) throws IOException;

    void writePcm(int track, byte[] data, int off, int len, long ptsUs) throws IOException;

    /** Finalizes the file. Returns a user-facing location (path or content uri), or null if empty. */
    String finish() throws IOException;

    interface Factory {
        /** New output for segment n (1-based; > 1 after a rotation / resolution change). */
        Mp4Sink open(int segment) throws IOException;
        boolean supportsVideo(int fourcc);
        boolean supportsOpusPassthrough();
    }
}
