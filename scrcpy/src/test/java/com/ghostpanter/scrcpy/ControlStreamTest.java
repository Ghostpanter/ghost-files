package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ControlStreamTest {

    private static final class RecordingSink implements ControlStream.InboundSink {
        final List<String> clipboards = new ArrayList<>();
        @Override public void onRemoteClipboard(String text) { clipboards.add(text); }
    }

    // ---- inbound (DeviceMessage) parsing ----

    @Test
    public void clipboardMessageDispatched() throws Exception {
        String text = "héllo world";
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(0);                  // TYPE_CLIPBOARD
        out.writeInt(payload.length);
        out.write(payload);

        RecordingSink sink = new RecordingSink();
        ControlStream cs = new ControlStream(
                new ByteArrayInputStream(bos.toByteArray()),
                new ByteArrayOutputStream(), null);
        cs.setInboundSink(sink);
        cs.runReader();   // synchronous; returns on EOF

        assertEquals(1, sink.clipboards.size());
        assertEquals(text, sink.clipboards.get(0));
    }

    @Test
    public void ackClipboardConsumedSilently() throws Exception {
        // type=1, then a long sequence number. No dispatch should occur.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(1);
        out.writeLong(0x1234567890abcdefL);

        RecordingSink sink = new RecordingSink();
        ControlStream cs = new ControlStream(
                new ByteArrayInputStream(bos.toByteArray()),
                new ByteArrayOutputStream(), null);
        cs.setInboundSink(sink);
        cs.runReader();

        assertEquals(0, sink.clipboards.size());
    }

    @Test
    public void unknownTypeAborts() throws Exception {
        // type=42 - runReader should log and return.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(42);
        // a few trailing bytes so the reader doesn't EOF before processing
        bos.write(new byte[]{0, 0, 0, 0});

        RecordingSink sink = new RecordingSink();
        ControlStream cs = new ControlStream(
                new ByteArrayInputStream(bos.toByteArray()),
                new ByteArrayOutputStream(), null);
        cs.setInboundSink(sink);
        cs.runReader();
        assertEquals(0, sink.clipboards.size());
    }

    // ---- outbound (send + writer) ----

    @Test
    public void writerDrainsInOrder() throws Exception {
        // We submit three messages, then run the writer until the
        // outbox is empty AND we've signalled stop.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ControlStream cs = new ControlStream(
                new ByteArrayInputStream(new byte[0]), out, null);

        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{4, 5};
        byte[] c = new byte[]{6};
        cs.send(a);
        cs.send(b);
        cs.send(c);

        Thread writer = new Thread(cs::runWriter, "writer-under-test");
        writer.start();

        // give the writer a moment to drain
        for (int i = 0; i < 50 && out.size() < 6; i++) Thread.sleep(10);
        cs.stop();
        writer.join(1_000);

        byte[] got = out.toByteArray();
        assertEquals(6, got.length);
        assertEquals(1, got[0]); assertEquals(2, got[1]); assertEquals(3, got[2]);
        assertEquals(4, got[3]); assertEquals(5, got[4]);
        assertEquals(6, got[5]);
    }

    @Test
    public void keyEventLandsAfterOutboxOverflow() throws Exception {
        // Fill the bounded outbox with touch-MOVE messages, then enqueue
        // one key event. The MOVE must be evicted to make room; the key
        // event must be preserved and end up last in the drain.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ControlStream cs = new ControlStream(
                new ByteArrayInputStream(new byte[0]), out, null);

        for (int i = 0; i < 256; i++) {
            cs.send(ControlMessages.touch(/* MOVE */ 2, 0L, i, i, 1080, 2400, 0xffff, 0, 0));
        }
        byte[] key = ControlMessages.keycode(0, 29, 0, 0);
        cs.send(key);

        Thread w = new Thread(cs::runWriter, "drain");
        w.start();

        // Drain to quiescence (size stable for 100ms).
        long deadline = System.currentTimeMillis() + 2_000;
        int last = -1, stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int now = out.size();
            if (now == last) { stable++; if (stable > 5) break; }
            else { stable = 0; last = now; }
            Thread.sleep(20);
        }
        cs.stop();
        w.join(1_000);

        byte[] bytes = out.toByteArray();
        assertNotEquals(0, bytes.length);

        // The key event should be the final 14 bytes of the drained stream
        // (queued last, FIFO order, only touch-MOVEs evicted on overflow).
        byte[] tail = new byte[14];
        System.arraycopy(bytes, bytes.length - 14, tail, 0, 14);
        for (int i = 0; i < 14; i++) assertEquals(key[i], tail[i]);
    }

    @Test
    public void nonDroppableOverflowFailsStream() {
        AtomicBoolean failed = new AtomicBoolean();
        ControlStream cs = new ControlStream(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                () -> failed.set(true));

        byte[] key = ControlMessages.keycode(0, 29, 0, 0);
        for (int i = 0; i < 257; i++) cs.send(key);

        assertEquals(true, failed.get());
    }
}
