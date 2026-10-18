// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class AdbProtocolTest {

    @Test
    public void oversizedPayloadIsRejectedBeforeAllocation() throws Exception {
        byte[] header = header(AdbProtocol.A_WRTE, 1, 1,
                AdbProtocol.MAX_PAYLOAD_V1 + 1, 0);
        try {
            AdbProtocol.Message.parse(new ByteArrayInputStream(header),
                    AdbProtocol.A_VERSION_MIN, AdbProtocol.MAX_PAYLOAD_V1);
            fail("oversized payload accepted");
        } catch (StreamCorruptedException expected) {
            // expected
        }
    }

    @Test
    public void zeroProgressHeaderReadFails() throws Exception {
        InputStream input = zeroThen(new byte[AdbProtocol.ADB_HEADER_LENGTH]);
        try {
            AdbProtocol.Message.parse(input, AdbProtocol.A_VERSION_MIN,
                    AdbProtocol.MAX_PAYLOAD_V1);
            fail("zero-progress input accepted");
        } catch (IOException expected) {
            assertEquals("ADB input made no progress", expected.getMessage());
        }
    }

    @Test
    public void zeroProgressPayloadReadFails() throws Exception {
        byte[] packet = AdbProtocol.generateWrite(1, 2, new byte[]{1}, 0, 1);
        InputStream input = new InputStream() {
            int offset;
            @Override public int read() { return -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (offset < AdbProtocol.ADB_HEADER_LENGTH) {
                    int n = Math.min(len, AdbProtocol.ADB_HEADER_LENGTH - offset);
                    System.arraycopy(packet, offset, b, off, n);
                    offset += n;
                    return n;
                }
                return 0;
            }
        };
        try {
            AdbProtocol.Message.parse(input, AdbProtocol.A_VERSION_MIN,
                    AdbProtocol.MAX_PAYLOAD_V1);
            fail("zero-progress payload accepted");
        } catch (IOException expected) {
            assertEquals("ADB input made no progress", expected.getMessage());
        }
    }

    @Test
    public void openUsesUtf8ByteLength() throws Exception {
        String destination = "shell:printf caf\u00e9";
        byte[] packet = AdbProtocol.generateOpen(7, destination);
        AdbProtocol.Message message = AdbProtocol.Message.parse(
                new ByteArrayInputStream(packet), AdbProtocol.A_VERSION_MIN,
                AdbProtocol.MAX_PAYLOAD_V1);
        byte[] expected = (destination + "\0").getBytes(StandardCharsets.UTF_8);
        assertEquals(expected.length, message.dataLength);
        assertArrayEquals(expected, message.payload);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void invalidOutgoingRangeIsRejected() {
        AdbProtocol.generateWrite(1, 2, new byte[4], 3, 2);
    }

    private static byte[] header(int command, int arg0, int arg1, int length, int checksum) {
        return ByteBuffer.allocate(AdbProtocol.ADB_HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(length)
                .putInt(checksum).putInt(~command).array();
    }

    private static InputStream zeroThen(byte[] data) {
        return new InputStream() {
            boolean first = true;
            int offset;
            @Override public int read() { return -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (first) {
                    first = false;
                    return 0;
                }
                if (offset == data.length) return -1;
                int n = Math.min(len, data.length - offset);
                System.arraycopy(data, offset, b, off, n);
                offset += n;
                return n;
            }
        };
    }
}
