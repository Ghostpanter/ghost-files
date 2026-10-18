package com.ghostpanter.scrcpy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

// Pure-java adb sync v1 SEND/RECV framing. Extracted from Server.push() so
// it can be unit-tested without an AdbStream or any android coupling.
//
// SEND layout on the wire (little-endian uint32 lengths):
//   "SEND" | u32(len(path,mode)) | path,mode
//   "DATA" | u32(chunk_size)     | chunk_bytes      (repeats, chunk <= 64K)
//   "DONE" | u32(mtime_seconds)
//   ----> response:
//   "OKAY" | u32(0)
//     or
//   "FAIL" | u32(msg_len) | msg
//
// RECV layout:
//   "RECV" | u32(len(path)) | path
//   <--- "DATA" | u32(n) | bytes   (repeats)
//   <--- "DONE" | u32(mtime)
//     or "FAIL" | u32(msg_len) | msg
public final class Sync {

    public static final int CHUNK = 64 * 1024;

    // FAIL responses carry a short human-readable message; cap what a
    // corrupt or hostile length field can make us allocate.
    private static final int MAX_FAIL_MSG = 4 * 1024;
    private static final int MAX_PATH_BYTES = 1024;

    private Sync() {}

    // Push `src` to `remotePath` with the given mode and mtime, using the
    // already-open `out` and `in` of an adb sync stream. Returns the
    // total payload byte count.
    public static long push(InputStream src, OutputStream out, InputStream in,
            String remotePath, int mode, int mtimeSec)
            throws IOException {
        Objects.requireNonNull(src);
        Objects.requireNonNull(out);
        Objects.requireNonNull(in);
        Objects.requireNonNull(remotePath);
        if (remotePath.isEmpty() || remotePath.indexOf(',') >= 0
                || remotePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid sync path");
        }
        if (mode < 0) throw new IllegalArgumentException("invalid sync mode");
        String header = remotePath + "," + mode;
        byte[] hb = header.getBytes(StandardCharsets.UTF_8);
        if (hb.length > MAX_PATH_BYTES) {
            throw new IllegalArgumentException("sync path and mode are too long");
        }

        byte[] tag = new byte[8];
        putTag(tag, 0, "SEND");
        Wire.writeLe32(tag, 4, hb.length);
        out.write(tag);
        out.write(hb);

        byte[] chunk = new byte[CHUNK];
        byte[] dataHdr = new byte[8];
        putTag(dataHdr, 0, "DATA");
        long total = 0;
        int n;
        while ((n = src.read(chunk)) >= 0) {
            if (n == 0) throw new IOException("sync source made no progress");
            Wire.writeLe32(dataHdr, 4, n);
            out.write(dataHdr);
            out.write(chunk, 0, n);
            total += n;
        }

        byte[] done = new byte[8];
        putTag(done, 0, "DONE");
        Wire.writeLe32(done, 4, mtimeSec);
        out.write(done);
        out.flush();

        byte[] resp = new byte[8];
        Wire.readFully(in, resp);
        String code = new String(resp, 0, 4, StandardCharsets.US_ASCII);
        int len = Wire.readLe32(resp, 4);
        if ("OKAY".equals(code)) {
            if (len != 0) throw new IOException("sync OKAY length is not zero: " + len);
            return total;
        }
        if (!"FAIL".equals(code)) {
            throw new IOException("unknown sync response: " + safeCode(code));
        }

        if (len < 0 || len > MAX_FAIL_MSG) {
            throw new IOException("sync " + code + " response length out of range: " + len);
        }
        byte[] msg = new byte[len];
        if (msg.length > 0) Wire.readFully(in, msg);
        throw new IOException("sync FAIL: " + safeMessage(Wire.decodeUtf8(msg)));
    }

    // Pull remotePath into dst via adb sync RECV framing.
    public static long pull(OutputStream dst, OutputStream out, InputStream in,
            String remotePath) throws IOException {
        Objects.requireNonNull(dst);
        Objects.requireNonNull(out);
        Objects.requireNonNull(in);
        Objects.requireNonNull(remotePath);
        if (remotePath.isEmpty() || remotePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid sync path");
        }
        byte[] pathBytes = remotePath.getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length > MAX_PATH_BYTES) {
            throw new IllegalArgumentException("sync path is too long");
        }

        byte[] req = new byte[8];
        putTag(req, 0, "RECV");
        Wire.writeLe32(req, 4, pathBytes.length);
        out.write(req);
        out.write(pathBytes);
        out.flush();

        byte[] hdr = new byte[8];
        byte[] chunk = new byte[CHUNK];
        long total = 0;
        for (;;) {
            Wire.readFully(in, hdr);
            String code = new String(hdr, 0, 4, StandardCharsets.US_ASCII);
            int len = Wire.readLe32(hdr, 4);
            if ("DATA".equals(code)) {
                if (len < 0 || len > CHUNK) {
                    throw new IOException("sync DATA length out of range: " + len);
                }
                if (len > 0) {
                    Wire.readFully(in, chunk, 0, len);
                    dst.write(chunk, 0, len);
                    total += len;
                }
                continue;
            }
            if ("DONE".equals(code)) {
                return total;
            }
            if ("FAIL".equals(code)) {
                if (len < 0 || len > MAX_FAIL_MSG) {
                    throw new IOException("sync FAIL response length out of range: " + len);
                }
                byte[] msg = new byte[len];
                if (msg.length > 0) Wire.readFully(in, msg);
                throw new IOException("sync FAIL: " + safeMessage(Wire.decodeUtf8(msg)));
            }
            throw new IOException("unknown sync response: " + safeCode(code));
        }
    }

    private static void putTag(byte[] dst, int off, String tag) {
        byte[] b = tag.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, dst, off, 4);
    }

    private static String safeCode(String code) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            out.append(c >= 0x20 && c <= 0x7e ? c : '?');
        }
        return out.toString();
    }

    private static String safeMessage(String message) {
        StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            int type = Character.getType(c);
            out.append(Character.isISOControl(c) || type == Character.FORMAT ? '?' : c);
        }
        return out.toString();
    }
}
