package com.ghostpanter.scrcpy;

// Sink for parsed audio frames. AudioSink is the only production
// implementation; tests use a recording stub. Kept android-free.
//
// start(fourcc) tells the sink which wire codec it should expect.
// ptsUs is the wire presentation timestamp with protocol flags removed.
// For raw payloads are interleaved s16le PCM. For opus the first
// frame has isConfig=true and payload is the OpusHead (the server
// pre-strips the AOPUSHDR/AOPUSDLY/AOPUSPRL container); subsequent
// frames are opus packets.
public interface AudioFrames {

    void start(int fourcc);

    void feed(byte[] data, int off, int len, long ptsUs, boolean isConfig);

    void release();
}
