package com.ghostpanter.scrcpy;

import java.io.IOException;

// Sink for parsed video frames. VideoSink is the only production
// implementation; tests use a recording stub. Kept android-free so
// it can be referenced from JVM-only test code.
public interface VideoFrames {

    void configure(int codecFourcc, int width, int height) throws IOException;

    void reconfigure(int codecFourcc, int width, int height) throws IOException;

    void feed(byte[] data, long ptsUs, boolean isConfig, boolean isKeyframe);

    void release();
}
