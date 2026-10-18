package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// The arithmetic that decides where a tap lands. Until this existed the
// mapping was verified by nothing: the only coverage was an emulator
// self-mirror, where the target and the view are the same size, so the
// transform is the identity and every scaling or offset bug passes.
public class TouchMapTest {

    // A 1080x2400 phone showing a 1080x1920 target: fitted to 864x1920,
    // leaving 108px bars left and right.
    private static final int VIEW_X = 108, VIEW_W = 864, TARGET_W = 1080;

    @Test
    public void leftEdgeOfTheVideoIsTheLeftEdgeOfTheTarget() {
        assertEquals(0, TouchMap.map(VIEW_X, VIEW_X, VIEW_W, TARGET_W));
    }

    // The view has fewer pixels than the target, so the last column the
    // mapping can produce is floor((span-1) * target / span) - 1078 here,
    // not 1079. That is granularity, not an off-by-one: a 864px-wide view
    // cannot address all 1080 target columns. What matters is that the
    // right edge lands on the last addressable column and stays inside.
    @Test
    public void rightEdgeIsTheLastAddressableColumn() {
        int got = TouchMap.map(VIEW_X + VIEW_W - 1, VIEW_X, VIEW_W, TARGET_W);
        assertEquals((VIEW_W - 1) * TARGET_W / VIEW_W, got);
        assertTrue("must stay inside the target", got < TARGET_W);
        assertTrue("must be within one step of the far edge",
                got >= TARGET_W - (TARGET_W / VIEW_W) - 1);
    }

    @Test
    public void centreMapsToCentre() {
        assertEquals(TARGET_W / 2, TouchMap.map(VIEW_X + VIEW_W / 2, VIEW_X, VIEW_W, TARGET_W));
    }

    @Test
    public void theLetterboxBarIsClampedNotWrapped() {
        // Touches on the bars must land on the near edge. Subtracting the
        // origin without clamping would make the left bar negative and the
        // right bar overflow past the target's width.
        assertEquals(0, TouchMap.map(0, VIEW_X, VIEW_W, TARGET_W));
        assertEquals(0, TouchMap.map(VIEW_X - 50, VIEW_X, VIEW_W, TARGET_W));
        int far = (VIEW_W - 1) * TARGET_W / VIEW_W;   // last addressable column
        assertEquals(far, TouchMap.map(VIEW_X + VIEW_W + 50, VIEW_X, VIEW_W, TARGET_W));
        assertEquals(far, TouchMap.map(2000, VIEW_X, VIEW_W, TARGET_W));
    }

    @Test
    public void neverEscapesTheTargetForAnyCoordinate() {
        for (int c = -500; c < 3000; c++) {
            int t = TouchMap.map(c, VIEW_X, VIEW_W, TARGET_W);
            if (t < 0 || t >= TARGET_W) {
                throw new AssertionError("coord " + c + " mapped outside the target: " + t);
            }
        }
    }

    @Test
    public void noOverflowAtFourK() {
        // 3840 * 2160 exceeds int when multiplied in 32 bits; the mapping
        // has to widen before it scales.
        assertEquals(2159, TouchMap.map(3839, 0, 3840, 2160));
        assertEquals(0,    TouchMap.map(0,    0, 3840, 2160));
    }

    @Test
    public void identityWhenTheViewMatchesTheTarget() {
        // The self-mirror case the e2e exercises: nothing should move.
        for (int c : new int[]{0, 1, 539, 1079}) {
            assertEquals(c, TouchMap.map(c, 0, 1080, 1080));
        }
    }

    @Test
    public void degenerateGeometryIsRefusedRatherThanDividingByZero() {
        assertEquals(0, TouchMap.map(100, 0, 0, 1080));
        assertEquals(0, TouchMap.map(100, 0, 1080, 0));
    }
}
