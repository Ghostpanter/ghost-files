package com.ghostpanter.scrcpy;

import java.io.IOException;

// Tees the parsed mirror streams into a StreamRecorder before the decoders see them.
// Android-free; the recorder ignores everything while not recording (but keeps the latest
// codec config so a recording can start on the next keyframe).
final class RecordingTaps {
    private RecordingTaps() {}

    static final class Video implements VideoFrames {
        private final VideoFrames next;
        private final StreamRecorder rec;
        Video(VideoFrames next, StreamRecorder rec) { this.next = next; this.rec = rec; }

        @Override public void configure(int codecFourcc, int width, int height) throws IOException {
            rec.onVideoFormat(codecFourcc, width, height);
            next.configure(codecFourcc, width, height);
        }
        @Override public void reconfigure(int codecFourcc, int width, int height) throws IOException {
            rec.onVideoFormat(codecFourcc, width, height);
            next.reconfigure(codecFourcc, width, height);
        }
        @Override public void feed(byte[] data, long ptsUs, boolean isConfig, boolean isKeyframe) {
            rec.onVideoPacket(data, 0, data.length, ptsUs, isConfig, isKeyframe);
            next.feed(data, ptsUs, isConfig, isKeyframe);
        }
        @Override public void release() { next.release(); }
    }

    static final class Audio implements AudioFrames {
        private final AudioFrames next;
        private final StreamRecorder rec;
        private int fourcc;
        Audio(AudioFrames next, StreamRecorder rec) { this.next = next; this.rec = rec; }

        @Override public void start(int fourcc) {
            this.fourcc = fourcc;
            rec.onAudioStart(fourcc);
            next.start(fourcc);
        }
        @Override public void feed(byte[] data, int off, int len, long ptsUs, boolean isConfig) {
            if (fourcc == Wire.CODEC_RAW) rec.onPcm(data, off, len, ptsUs);
            else rec.onAudioPacket(data, off, len, ptsUs, isConfig);
            next.feed(data, off, len, ptsUs, isConfig);
        }
        @Override public void release() { next.release(); }
    }
}
