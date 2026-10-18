package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LegacyConnectTest {

    @Test
    public void bareIpDefaultsTo5555() {
        Devices.Device d = Devices.parseDirect(" 192.168.1.20 ", 5555);
        assertEquals("192.168.1.20", d.host);
        assertEquals(5555, d.port);
    }

    @Test
    public void explicitPortKept() {
        Devices.Device d = Devices.parseDirect("192.168.1.20:5556", 5555);
        assertEquals(5556, d.port);
        assertEquals(5555, Devices.parseDirect("[fe80::1]:5555", 5555).port);
    }

    @Test
    public void rejectsGarbage() {
        assertNull(Devices.parseDirect("", 5555));
        assertNull(Devices.parseDirect("1.2.3.4:", 5555));
        assertNull(Devices.parseDirect("1.2.3.4:99999", 5555));
        assertNull(Devices.parseDirect("fe80::1", 5555));
        assertNull(Devices.parseDirect("a b", 5555));
    }

    @Test
    public void parseSdk() {
        assertEquals(28, Server.parseSdk("28\n"));
        assertEquals(0, Server.parseSdk(""));
        assertEquals(0, Server.parseSdk("error: closed"));
        assertEquals(0, Server.parseSdk(null));
    }

    @Test
    public void codecFallbackOnAndroid9() {
        assertEquals("h264", Server.effectiveVideoCodec("h265", 28));
        assertEquals("h264", Server.effectiveVideoCodec("av1", 28));
        assertEquals("h264", Server.effectiveVideoCodec("h264", 28));
        assertEquals("h265", Server.effectiveVideoCodec("h265", 29));
        assertEquals("h265", Server.effectiveVideoCodec("h265", 0));
        assertEquals("av1", Server.effectiveVideoCodec("av1", 34));
    }
}
