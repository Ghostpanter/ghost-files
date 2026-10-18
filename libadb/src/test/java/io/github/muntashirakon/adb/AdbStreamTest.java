// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AdbStreamTest {

    private static class RecordingTransport implements AdbStream.Transport {
        final int maxData;
        final List<byte[]> packets = Collections.synchronizedList(new ArrayList<>());
        RecordingTransport(int maxData) { this.maxData = maxData; }
        @Override public int getMaxData() { return maxData; }
        @Override public void sendPacket(byte[] packet) throws IOException { packets.add(packet); }
        @Override public void flushPacket() {}
    }

    private static final class BlockingTransport extends RecordingTransport {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);

        BlockingTransport() {
            super(4);
        }

        @Override public void sendPacket(byte[] packet) throws IOException {
            if (command(packet) == AdbProtocol.A_WRTE) {
                writeEntered.countDown();
                try {
                    if (!releaseWrite.await(1, TimeUnit.SECONDS)) {
                        throw new IOException("test write release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            super.sendPacket(packet);
        }
    }

    @Test
    public void zeroLengthWriteDoesNotConsumeReady() throws Exception {
        RecordingTransport transport = new RecordingTransport(4);
        AdbStream stream = openStream(transport);
        stream.write(new byte[0], 0, 0);
        stream.write(new byte[]{7}, 0, 1);
        assertEquals(1, transport.packets.size());
        assertEquals(1, payloadLength(transport.packets.get(0)));
    }

    @Test
    public void eachWritePacketWaitsForOkay() throws Exception {
        RecordingTransport transport = new RecordingTransport(4);
        AdbStream stream = openStream(transport);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                stream.write(new byte[6], 0, 6);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        writer.start();
        waitForPackets(transport, 1);
        assertTrue("writer sent all chunks without a second OKAY", writer.isAlive());
        assertEquals(4, payloadLength(transport.packets.get(0)));
        stream.readyForWrite();
        writer.join(1_000);
        assertFalse("writer did not finish after second OKAY", writer.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(2, transport.packets.size());
        assertEquals(2, payloadLength(transport.packets.get(1)));
    }

    @Test
    public void readAcknowledgesOnlyAfterPacketIsConsumed() throws Exception {
        RecordingTransport transport = new RecordingTransport(8);
        AdbStream stream = openStream(transport);
        transport.packets.clear();
        stream.addPayload(new byte[]{1, 2, 3, 4});

        byte[] out = new byte[4];
        assertEquals(2, stream.read(out, 0, 2));
        assertEquals(0, transport.packets.size());
        try {
            stream.addPayload(new byte[]{5});
            fail("peer bypassed ADB flow control");
        } catch (StreamCorruptedException expected) {
            // expected
        }
        assertEquals(2, stream.read(out, 2, 2));
        assertEquals(1, transport.packets.size());
        assertEquals(AdbProtocol.A_OKAY, command(transport.packets.get(0)));
    }

    @Test
    public void remoteCloseDrainsBufferedPayload() throws Exception {
        RecordingTransport transport = new RecordingTransport(8);
        AdbStream stream = openStream(transport);
        transport.packets.clear();
        stream.addPayload(new byte[]{1, 2, 3, 4});
        byte[] out = new byte[4];
        assertEquals(2, stream.read(out, 0, 2));
        stream.notifyClose(true);
        assertEquals(2, stream.read(out, 2, 2));
        assertEquals(-1, stream.read(out, 0, out.length));
        assertEquals("CLSE must not be answered with OKAY", 0, transport.packets.size());
    }

    @Test
    public void closeCannotPassAnInFlightWrite() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        AdbStream stream = openStream(transport);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                stream.write(new byte[]{1}, 0, 1);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread closer = new Thread(() -> {
            try {
                stream.close();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        writer.start();
        assertTrue(transport.writeEntered.await(1, TimeUnit.SECONDS));
        closer.start();
        assertTrue("close passed blocked WRTE", closer.isAlive());
        transport.releaseWrite.countDown();
        writer.join(1_000);
        closer.join(1_000);

        assertFalse(writer.isAlive());
        assertFalse(closer.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(2, transport.packets.size());
        assertEquals(AdbProtocol.A_WRTE, command(transport.packets.get(0)));
        assertEquals(AdbProtocol.A_CLSE, command(transport.packets.get(1)));
    }

    private static AdbStream openStream(RecordingTransport transport) throws Exception {
        AdbStream stream = new AdbStream(transport, 1);
        stream.updateRemoteId(2);
        stream.readyForWrite();
        return stream;
    }

    private static int command(byte[] packet) {
        return ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int payloadLength(byte[] packet) {
        return ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
    }

    private static void waitForPackets(RecordingTransport transport, int count)
            throws InterruptedException {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (transport.packets.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(count, transport.packets.size());
    }
}
