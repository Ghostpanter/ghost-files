package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoQueueTest {

    private static VideoQueue.Frame frame(int id, boolean config, boolean keyframe) {
        return new VideoQueue.Frame(new byte[]{(byte) id}, id, config, keyframe);
    }

    @Test
    public void rejectsDeltaUntilKeyframe() {
        VideoQueue queue = new VideoQueue(4, 4);
        assertFalse(queue.offer(frame(1, false, false)));
        assertTrue(queue.offer(frame(2, false, true)));
        assertTrue(queue.offer(frame(3, false, false)));
    }

    @Test
    public void overflowKeepsQueuedDecoderGeneration() {
        VideoQueue queue = new VideoQueue(3, 3);
        VideoQueue.Frame keyframe = frame(1, false, true);
        VideoQueue.Frame delta = frame(2, false, false);
        assertTrue(queue.offer(keyframe));
        assertTrue(queue.offer(delta));
        assertTrue(queue.offer(frame(3, false, false)));
        assertFalse(queue.offer(frame(4, false, false)));
        assertTrue(queue.needsKeyframe());
        assertFalse(queue.offer(frame(5, false, false)));
        assertSame(keyframe, queue.poll());
        assertSame(delta, queue.poll());
    }

    @Test
    public void newKeyframeReplacesOldMediaButKeepsConfig() {
        VideoQueue queue = new VideoQueue(4, 4);
        VideoQueue.Frame config = frame(1, true, false);
        VideoQueue.Frame nextKeyframe = frame(4, false, true);
        assertTrue(queue.offer(config));
        assertTrue(queue.offer(frame(2, false, true)));
        assertTrue(queue.offer(frame(3, false, false)));

        assertTrue(queue.offer(nextKeyframe));

        assertSame(config, queue.poll());
        assertSame(nextKeyframe, queue.poll());
        assertTrue(queue.isEmpty());
    }
}
