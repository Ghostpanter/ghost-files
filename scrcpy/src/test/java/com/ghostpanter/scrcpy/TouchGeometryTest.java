package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TouchGeometryTest {

    @Test
    public void viewportBeforeTargetDoesNotEnableInput() {
        TouchGeometry geometry = new TouchGeometry();
        geometry.setViewport(0, 0, 0, 1080, 608);
        assertNull(geometry.snapshot());
    }

    @Test
    public void targetResizeInvalidatesViewport() {
        TouchGeometry geometry = new TouchGeometry();
        long first = 7;
        geometry.setTargetSize(first, 1920, 1080);
        geometry.setViewport(first, 0, 100, 1080, 608);

        long second = 8;
        geometry.setTargetSize(second, 1080, 1920);

        assertNull(geometry.snapshot());
        geometry.setViewport(first, 100, 0, 608, 1080);
        assertNull(geometry.snapshot());

        geometry.setViewport(second, 100, 0, 608, 1080);
        TouchGeometry.Snapshot snapshot = geometry.snapshot();
        assertEquals(1080, snapshot.targetW);
        assertEquals(1920, snapshot.targetH);
        assertEquals(100, snapshot.x);
        assertEquals(608, snapshot.w);
    }

    @Test
    public void emptyViewportDisablesInput() {
        TouchGeometry geometry = new TouchGeometry();
        long version = 1;
        geometry.setTargetSize(version, 1920, 1080);
        geometry.setViewport(version, 0, 0, 1080, 608);
        geometry.setViewport(version, 0, 0, 0, 0);
        assertNull(geometry.snapshot());
    }
}
