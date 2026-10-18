package com.ghostpanter.scrcpy;

// Maps a coordinate in the activity window onto the target's pixels.
//
// Android-free for the same reason Wire and ControlMessages are: this is
// the arithmetic that decides where a tap lands, and it is worth testing
// without an emulator. Controller cannot be tested directly because it
// takes MotionEvent.
//
// The video does not fill the window. It is letterboxed to the target's
// aspect ratio and centred, so a coordinate has to have the bar
// subtracted before it is scaled.
public final class TouchMap {

    private TouchMap() {}

    // coord      - along one axis, in window coordinates
    // viewOrigin - where the video rectangle starts on that axis
    // viewSpan   - how long the video rectangle is on that axis
    // targetSpan - the target's size on that axis
    //
    // Clamped into the rectangle rather than rejected: a drag that wanders
    // onto a letterbox bar and is released there must still deliver its
    // UP, or the target keeps the pointer down for the rest of the
    // session. Returns 0..targetSpan-1.
    public static int map(int coord, int viewOrigin, int viewSpan, int targetSpan) {
        if (viewSpan <= 0 || targetSpan <= 0) return 0;
        long v = (long) coord - viewOrigin;
        if (v < 0) v = 0;
        else if (v > viewSpan - 1) v = viewSpan - 1;
        // 64-bit intermediate: a 4K target times a 4K span overflows int.
        return (int) (v * targetSpan / viewSpan);
    }
}
