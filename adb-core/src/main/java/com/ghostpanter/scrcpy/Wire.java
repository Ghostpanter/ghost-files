package com.ghostpanter.scrcpy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

// Byte-order helpers used everywhere we touch raw streams.
//
// scrcpy server framing is big-endian (java standard).
// adb sync framing is little-endian (legacy).
//
// FOURCCs are read as a big-endian uint32, which matches the literal
// 4-char ASCII tag, so '"h264"' becomes 0x68323634.
public final class Wire {

    private Wire() {}

    // ---- big-endian (scrcpy) ----

    public static int readBe32(byte[] b, int off) {
        checkRange(b, off, 4);
        return ((b[off]     & 0xff) << 24)
             | ((b[off + 1] & 0xff) << 16)
             | ((b[off + 2] & 0xff) <<  8)
             |  (b[off + 3] & 0xff);
    }

    public static long readBe64(byte[] b, int off) {
        checkRange(b, off, 8);
        return ((long)(readBe32(b, off)) << 32) | (readBe32(b, off + 4) & 0xffffffffL);
    }

    public static void writeBe32(byte[] b, int off, int v) {
        checkRange(b, off, 4);
        b[off]     = (byte)(v >>> 24);
        b[off + 1] = (byte)(v >>> 16);
        b[off + 2] = (byte)(v >>>  8);
        b[off + 3] = (byte) v;
    }

    public static void writeBe64(byte[] b, int off, long v) {
        checkRange(b, off, 8);
        writeBe32(b, off,     (int)(v >>> 32));
        writeBe32(b, off + 4, (int) v);
    }

    // ---- little-endian (adb sync) ----

    public static int readLe32(byte[] b, int off) {
        checkRange(b, off, 4);
        return  (b[off]     & 0xff)
             | ((b[off + 1] & 0xff) <<  8)
             | ((b[off + 2] & 0xff) << 16)
             | ((b[off + 3] & 0xff) << 24);
    }

    public static void writeLe32(byte[] b, int off, int v) {
        checkRange(b, off, 4);
        b[off]     = (byte) v;
        b[off + 1] = (byte)(v >>>  8);
        b[off + 2] = (byte)(v >>> 16);
        b[off + 3] = (byte)(v >>> 24);
    }

    // ---- I/O helpers ----

    public static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        if (in == null || buf == null) throw new NullPointerException();
        if (off < 0 || len < 0 || off > buf.length || len > buf.length - off) {
            throw new IndexOutOfBoundsException();
        }
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException(
                    "short read: wanted " + len + " got " + got);
            if (n == 0) throw new IOException(
                    "input made no progress: wanted " + len + " got " + got);
            got += n;
        }
    }

    public static void readFully(InputStream in, byte[] buf) throws IOException {
        readFully(in, buf, 0, buf.length);
    }

    public static String decodeUtf8(byte[] data, int off, int len)
            throws CharacterCodingException {
        checkRange(data, off, len);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, off, len)).toString();
    }

    public static String decodeUtf8(byte[] data) throws CharacterCodingException {
        return decodeUtf8(data, 0, data.length);
    }

    // scrcpy codec identifiers, mirrored from
    // vendor/scrcpy/server/src/main/java/com/genymobile/scrcpy/video/VideoCodec.java
    // and .../audio/AudioCodec.java, which are the authoritative source.
    // Every id is the 4-char ASCII name NUL-padded on the LEFT for names
    // shorter than four characters (av1, raw, aac), never space-padded on
    // the right. WireTest asserts that rule rather than copying the
    // literals, so a mis-transcribed id fails the build.
    // Declared as int-literal constants so they can drive switch-case labels.
    public static final int CODEC_H264 = 0x68_32_36_34; // 'h264'
    public static final int CODEC_H265 = 0x68_32_36_35; // 'h265'
    public static final int CODEC_AV1  = 0x00_61_76_31; // '\0av1'
    public static final int CODEC_OPUS = 0x6f_70_75_73; // 'opus'
    public static final int CODEC_RAW  = 0x00_72_61_77; // '\0raw'

    public static String fourccName(int v) {
        // Skip any leading NUL bytes - scrcpy left-pads short names like
        // 'raw' and 'aac' with \0, which would otherwise render as
        // non-printable characters in logs.
        int[] bytes = {(v >>> 24) & 0xff, (v >>> 16) & 0xff,
                (v >>> 8) & 0xff, v & 0xff};
        int from = 0;
        while (from < bytes.length && bytes[from] == 0) from++;
        StringBuilder out = new StringBuilder(4);
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = from; i < bytes.length; i++) {
            int b = bytes[i];
            if (b >= 0x20 && b <= 0x7e) {
                out.append((char) b);
            } else {
                out.append("\\x").append(hex[b >>> 4]).append(hex[b & 0xf]);
            }
        }
        return out.toString();
    }

    private static void checkRange(byte[] data, int off, int len) {
        if (data == null) throw new NullPointerException("data");
        if (off < 0 || len < 0 || off > data.length - len) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len
                    + " size=" + data.length);
        }
    }
}
