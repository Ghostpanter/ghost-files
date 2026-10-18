package com.ghostpanter.scrcpy;

// Publishes a target size and its matching on-screen viewport as one
// immutable value. A target resize invalidates the old viewport until the UI
// lays out the new generation.
final class TouchGeometry {

    static final class Snapshot {
        final int targetW, targetH;
        final int x, y, w, h;

        Snapshot(int targetW, int targetH, int x, int y, int w, int h) {
            this.targetW = targetW;
            this.targetH = targetH;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private volatile Snapshot snapshot;
    private long version;
    private int targetW;
    private int targetH;

    synchronized void setTargetSize(long nextVersion, int w, int h) {
        if (nextVersion <= version) throw new IllegalArgumentException("stale target version");
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("invalid target size");
        version = nextVersion;
        targetW = w;
        targetH = h;
        snapshot = null;
    }

    synchronized void setViewport(long expectedVersion, int x, int y, int w, int h) {
        if (expectedVersion != version) return;
        snapshot = version > 0 && targetW > 0 && targetH > 0 && w > 0 && h > 0
                ? new Snapshot(targetW, targetH, x, y, w, h)
                : null;
    }

    Snapshot snapshot() {
        return snapshot;
    }
}
