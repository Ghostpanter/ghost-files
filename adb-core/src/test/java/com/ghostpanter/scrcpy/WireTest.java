package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class WireTest {

    @Test
    public void readFullyRejectsZeroProgress() throws Exception {
        InputStream in = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] b, int off, int len) { return 0; }
        };
        try {
            Wire.readFully(in, new byte[1]);
            fail("zero-progress input accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no progress"));
        }
    }

    @Test
    public void be32RoundTrip() {
        byte[] b = new byte[4];
        int[] cases = {0, 1, -1, 0x7fffffff, 0x80000000, 0x12345678};
        for (int v : cases) {
            Wire.writeBe32(b, 0, v);
            assertEquals(v, Wire.readBe32(b, 0));
        }
    }

    @Test
    public void be32Layout() {
        byte[] b = new byte[4];
        Wire.writeBe32(b, 0, 0x12345678);
        assertArrayEquals(new byte[]{0x12, 0x34, 0x56, 0x78}, b);
    }

    @Test
    public void be64RoundTrip() {
        byte[] b = new byte[8];
        long[] cases = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x0123456789abcdefL};
        for (long v : cases) {
            Wire.writeBe64(b, 0, v);
            assertEquals(v, Wire.readBe64(b, 0));
        }
    }

    @Test
    public void le32RoundTrip() {
        byte[] b = new byte[4];
        int[] cases = {0, 1, -1, 0x7fffffff, 0x80000000, 0x12345678};
        for (int v : cases) {
            Wire.writeLe32(b, 0, v);
            assertEquals(v, Wire.readLe32(b, 0));
        }
    }

    @Test
    public void le32Layout() {
        byte[] b = new byte[4];
        Wire.writeLe32(b, 0, 0x12345678);
        assertArrayEquals(new byte[]{0x78, 0x56, 0x34, 0x12}, b);
    }

    @Test
    public void readFullyShortStreamThrows() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[3]);
        byte[] buf = new byte[8];
        try {
            Wire.readFully(in, buf);
            fail("expected EOFException");
        } catch (EOFException ignored) {
            // expected
        }
    }

    // Derive the id the way scrcpy's Codec enums do: the ASCII name, right
    // aligned in a big-endian uint32, NUL-padded on the left. Deriving it
    // from the name is the whole point - the previous version of this test
    // asserted the constants against hand-copied literals, so it passed
    // while CODEC_AV1 held 'av01' instead of '\0av1' and AV1 could never
    // negotiate.
    private static int idOf(String name) {
        if (name.length() > 4) throw new IllegalArgumentException(name);
        int v = 0;
        for (int i = 0; i < 4 - name.length(); i++) v <<= 8;
        for (int i = 0; i < name.length(); i++) v = (v << 8) | name.charAt(i);
        return v;
    }

    @Test
    public void fourccConstantsMatchScrcpy() {
        // Names as spelled in scrcpy's VideoCodec / AudioCodec enums.
        assertEquals(idOf("h264"), Wire.CODEC_H264);
        assertEquals(idOf("h265"), Wire.CODEC_H265);
        assertEquals(idOf("av1"),  Wire.CODEC_AV1);
        assertEquals(idOf("opus"), Wire.CODEC_OPUS);
        assertEquals(idOf("raw"),  Wire.CODEC_RAW);
    }

    @Test
    public void fourccNameRoundTripsEveryCodec() {
        assertEquals("h264", Wire.fourccName(Wire.CODEC_H264));
        assertEquals("h265", Wire.fourccName(Wire.CODEC_H265));
        assertEquals("av1",  Wire.fourccName(Wire.CODEC_AV1));
        assertEquals("opus", Wire.fourccName(Wire.CODEC_OPUS));
        assertEquals("raw",  Wire.fourccName(Wire.CODEC_RAW));
        // Left-NUL padding is the rule, not a special case for raw.
        assertEquals("aac",  Wire.fourccName(0x00_61_61_63));
    }

}
