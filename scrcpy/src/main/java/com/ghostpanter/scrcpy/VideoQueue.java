package com.ghostpanter.scrcpy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

// Bounded encoded-video queue. It never leaves delta frames queued without
// the keyframe that starts their decoder generation.
final class VideoQueue {

    static final class Frame {
        final byte[] data;
        final long ptsUs;
        final boolean config;
        final boolean keyframe;

        Frame(byte[] data, long ptsUs, boolean config, boolean keyframe) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.config = config;
            this.keyframe = keyframe;
        }
    }

    private final int maxFrames;
    private final int maxBytes;
    private final Deque<Frame> frames;
    private int bytes;
    private boolean needsKeyframe = true;

    VideoQueue(int maxFrames, int maxBytes) {
        if (maxFrames < 1 || maxBytes < 1) throw new IllegalArgumentException();
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
        frames = new ArrayDeque<>(maxFrames);
    }

    boolean offer(Frame frame) {
        if (frame.config) {
            clear();
            return append(frame);
        }
        if (frame.keyframe) {
            removeMediaFrames();
            needsKeyframe = false;
            if (append(frame)) return true;
            needsKeyframe = true;
            return false;
        }
        if (needsKeyframe) return false;
        if (append(frame)) return true;
        needsKeyframe = true;
        return false;
    }

    Frame poll() {
        Frame frame = frames.pollFirst();
        if (frame != null) bytes -= frame.data.length;
        return frame;
    }

    boolean isEmpty() {
        return frames.isEmpty();
    }

    boolean needsKeyframe() {
        return needsKeyframe;
    }

    void clear() {
        frames.clear();
        bytes = 0;
        needsKeyframe = true;
    }

    private boolean append(Frame frame) {
        if (frames.size() >= maxFrames || frame.data.length > maxBytes - bytes) {
            return false;
        }
        frames.offerLast(frame);
        bytes += frame.data.length;
        return true;
    }

    private void removeMediaFrames() {
        for (Iterator<Frame> it = frames.iterator(); it.hasNext(); ) {
            Frame frame = it.next();
            if (!frame.config) {
                it.remove();
                bytes -= frame.data.length;
            }
        }
    }
}
