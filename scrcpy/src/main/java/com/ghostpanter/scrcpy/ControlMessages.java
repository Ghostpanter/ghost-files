package com.ghostpanter.scrcpy;

import java.nio.charset.StandardCharsets;

// Pure-java encoders for scrcpy control messages. Layouts mirror
// com.genymobile.scrcpy.control.ControlMessageReader in the scrcpy
// server source. Extracted from Controller so the wire format can be
// unit-tested without android.* on the classpath.
public final class ControlMessages {

    // scrcpy caps a whole control message at MESSAGE_MAX_SIZE = 256 KiB.
    // For SET_CLIPBOARD that leaves 256 KiB minus the 14-byte header
    // (type 1, sequence 8, paste flag 1, length 4); see the server's
    // ControlMessageReader.CLIPBOARD_TEXT_MAX_LENGTH. Going over makes
    // the server raise ControlProtocolException and drop the control
    // connection, taking the session with it, so refuse locally instead.
    static final int MAX_CLIPBOARD_BYTES = (1 << 18) - 14;

    // The reverse direction, from the server's DeviceMessageWriter:
    // 256 KiB minus its 5-byte header (type 1, length 4). Used to bound
    // what we are willing to read off the control socket.
    static final int MAX_DEVICE_CLIPBOARD_BYTES = (1 << 18) - 5;

    public static final int TYPE_INJECT_KEYCODE      = 0;
    public static final int TYPE_INJECT_TOUCH_EVENT  = 2;
    public static final int TYPE_BACK_OR_SCREEN_ON   = 4;
    public static final int TYPE_SET_CLIPBOARD       = 9;
    public static final int TYPE_RESET_VIDEO         = 17;

    // KeyEvent.ACTION_DOWN / ACTION_UP. Mirror the int values rather
    // than depend on android.view.KeyEvent so this stays android-free.
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP   = 1;

    public static final int TOUCH_MSG_LEN = 32; // 1 + 1 + 8 + 4 + 4 + 2 + 2 + 2 + 4 + 4
    public static final int KEY_MSG_LEN   = 14; // 1 + 1 + 4 + 4 + 4

    private ControlMessages() {}

    public static byte[] touch(int action, long pointerId,
                               int x, int y, int targetW, int targetH,
                               int pressureU16, int actionButton, int buttons) {
        if (targetW < 1 || targetW > 0xffff || targetH < 1 || targetH > 0xffff) {
            throw new IllegalArgumentException("touch target size is out of range");
        }
        if (x < 0 || x >= targetW || y < 0 || y >= targetH) {
            throw new IllegalArgumentException("touch position is out of range");
        }
        if (pressureU16 < 0 || pressureU16 > 0xffff) {
            throw new IllegalArgumentException("touch pressure is out of range");
        }
        byte[] m = new byte[TOUCH_MSG_LEN];
        m[0] = TYPE_INJECT_TOUCH_EVENT;
        m[1] = (byte) action;
        Wire.writeBe64(m, 2,  pointerId);
        Wire.writeBe32(m, 10, x);
        Wire.writeBe32(m, 14, y);
        m[18] = (byte)(targetW >>> 8); m[19] = (byte) targetW;
        m[20] = (byte)(targetH >>> 8); m[21] = (byte) targetH;
        m[22] = (byte)(pressureU16 >>> 8); m[23] = (byte) pressureU16;
        Wire.writeBe32(m, 24, actionButton);
        Wire.writeBe32(m, 28, buttons);
        return m;
    }

    public static byte[] keycode(int action, int keycode, int repeat, int metaState) {
        byte[] m = new byte[KEY_MSG_LEN];
        m[0] = TYPE_INJECT_KEYCODE;
        m[1] = (byte) action;
        Wire.writeBe32(m, 2,  keycode);
        Wire.writeBe32(m, 6,  repeat);
        Wire.writeBe32(m, 10, metaState);
        return m;
    }

    // Single button-press event. scrcpy server interprets this as Back
    // when the screen is on, or screen-on when it's off. We send a
    // DOWN+UP pair to make a tap; this helper builds one byte pair.
    public static byte[] backOrScreenOn(int action) {
        return new byte[]{(byte) TYPE_BACK_OR_SCREEN_ON, (byte) action};
    }

    public static byte[] resetVideo() {
        return new byte[]{(byte) TYPE_RESET_VIDEO};
    }

    public static byte[] setClipboard(long sequence, boolean paste, String text) {
        // UTF-8 is at least one byte per char, so this rejects the
        // hopeless cases without encoding a huge string first. The byte
        // count below is the check that actually matters.
        if (text.length() > MAX_CLIPBOARD_BYTES) {
            throw new IllegalArgumentException("clipboard text is too large");
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_CLIPBOARD_BYTES) {
            throw new IllegalArgumentException("clipboard UTF-8 data is too large");
        }
        byte[] m = new byte[1 + 8 + 1 + 4 + data.length];
        m[0] = TYPE_SET_CLIPBOARD;
        Wire.writeBe64(m, 1, sequence);
        m[9] = (byte)(paste ? 1 : 0);
        Wire.writeBe32(m, 10, data.length);
        System.arraycopy(data, 0, m, 14, data.length);
        return m;
    }
}
