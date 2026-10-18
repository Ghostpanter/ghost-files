package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class ControlMessagesTest {

    @Test(expected = IllegalArgumentException.class)
    public void clipboardRejectsOversizedText() {
        char[] chars = new char[ControlMessages.MAX_CLIPBOARD_BYTES + 1];
        ControlMessages.setClipboard(0, false, new String(chars));
    }

    @Test
    public void clipboardLimitsMatchScrcpyMessageSize() {
        // The server caps a whole control message at MESSAGE_MAX_SIZE and
        // derives each direction's text limit by subtracting that
        // direction's header. Derive it the same way rather than copying
        // the numbers, so a server bump shows up here as a failure.
        final int messageMax = 1 << 18;                       // 256 KiB
        assertEquals(messageMax - (1 + 8 + 1 + 4),            // SET_CLIPBOARD
                ControlMessages.MAX_CLIPBOARD_BYTES);
        assertEquals(messageMax - (1 + 4),                    // DeviceMessage
                ControlMessages.MAX_DEVICE_CLIPBOARD_BYTES);
    }

    @Test
    public void clipboardAcceptsExactlyTheLimit() {
        char[] chars = new char[ControlMessages.MAX_CLIPBOARD_BYTES];
        java.util.Arrays.fill(chars, 'a');   // 1 byte per char in UTF-8
        byte[] m = ControlMessages.setClipboard(0, false, new String(chars));
        assertEquals(14 + ControlMessages.MAX_CLIPBOARD_BYTES, m.length);
        assertEquals(ControlMessages.MAX_CLIPBOARD_BYTES, Wire.readBe32(m, 10));
    }

    @Test
    public void touchByteLayout() {
        // ACTION_DOWN=0, single finger, target 1080x2400, x=100, y=200, full pressure
        byte[] m = ControlMessages.touch(/* action */ 0, /* pointerId */ 0L,
                /* x */ 100, /* y */ 200, /* tw */ 1080, /* th */ 2400,
                /* pressure */ 0xffff, /* actionButton */ 0, /* buttons */ 0);
        assertEquals(ControlMessages.TOUCH_MSG_LEN, m.length);
        assertEquals(ControlMessages.TYPE_INJECT_TOUCH_EVENT, m[0]);
        assertEquals(0, m[1]);                              // action
        assertEquals(0L, Wire.readBe64(m, 2));              // pointer id
        assertEquals(100, Wire.readBe32(m, 10));            // x
        assertEquals(200, Wire.readBe32(m, 14));            // y
        assertEquals(1080, ((m[18] & 0xff) << 8) | (m[19] & 0xff));  // tw
        assertEquals(2400, ((m[20] & 0xff) << 8) | (m[21] & 0xff));  // th
        assertEquals(0xffff, ((m[22] & 0xff) << 8) | (m[23] & 0xff)); // pressure
        assertEquals(0, Wire.readBe32(m, 24));              // actionButton
        assertEquals(0, Wire.readBe32(m, 28));              // buttons
    }

    @Test
    public void keycodeByteLayout() {
        // ACTION_DOWN=0, KEYCODE_A=29, repeat=0, metaState=0
        byte[] m = ControlMessages.keycode(0, 29, 0, 0);
        assertEquals(ControlMessages.KEY_MSG_LEN, m.length);
        assertEquals(ControlMessages.TYPE_INJECT_KEYCODE, m[0]);
        assertEquals(0, m[1]);
        assertEquals(29, Wire.readBe32(m, 2));
        assertEquals(0,  Wire.readBe32(m, 6));
        assertEquals(0,  Wire.readBe32(m, 10));
    }

    @Test
    public void resetVideoByteLayout() {
        assertArrayEquals(new byte[]{17}, ControlMessages.resetVideo());
    }

    @Test
    public void setClipboardWithUtf8Payload() {
        String text = "héllo \ud83c\udf89";   // includes a 4-byte surrogate pair
        byte[] expected = text.getBytes(StandardCharsets.UTF_8);
        byte[] m = ControlMessages.setClipboard(0xdeadbeefcafeL, /* paste */ true, text);
        assertEquals(1 + 8 + 1 + 4 + expected.length, m.length);
        assertEquals(ControlMessages.TYPE_SET_CLIPBOARD, m[0]);
        assertEquals(0xdeadbeefcafeL, Wire.readBe64(m, 1));
        assertEquals(1, m[9]);                              // paste
        assertEquals(expected.length, Wire.readBe32(m, 10));
        byte[] tail = new byte[expected.length];
        System.arraycopy(m, 14, tail, 0, expected.length);
        assertArrayEquals(expected, tail);
    }

    @Test
    public void setClipboardNoPaste() {
        byte[] m = ControlMessages.setClipboard(0L, false, "");
        assertEquals(0, m[9]);
        assertEquals(0, Wire.readBe32(m, 10));
        assertEquals(14, m.length);
    }
}
