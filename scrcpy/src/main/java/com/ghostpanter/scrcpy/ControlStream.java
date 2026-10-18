package com.ghostpanter.scrcpy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

// Bidirectional bridge to the scrcpy control socket.
//
// Outbound: Controller hands us pre-encoded ControlMessage byte arrays
// via send(); the writer thread drains them onto the OutputStream.
// Queue is bounded; on overflow, the *oldest* intermediate
// INJECT_TOUCH_EVENT with action=MOVE is dropped. Touch-down/up and key
// events are never dropped, otherwise pointers get orphaned on the target.
//
// Inbound: the reader thread parses DeviceMessage frames from the
// InputStream. v1 only cares about TYPE_CLIPBOARD; ACK_CLIPBOARD is
// logged, UHID_OUTPUT is consumed and ignored.
//
// Constructor takes plain streams so this class is android-free.
public final class ControlStream {

    public interface InboundSink {
        void onRemoteClipboard(String text);
    }

    private static final int TYPE_INJECT_TOUCH_EVENT = 2;
    private static final int ACTION_MOVE              = 2;

    private static final int DEV_TYPE_CLIPBOARD     = 0;
    private static final int DEV_TYPE_ACK_CLIPBOARD = 1;
    private static final int DEV_TYPE_UHID_OUTPUT   = 2;

    private static final int MAX_QUEUED = 256;

    private final InputStream  in;
    private final OutputStream out;
    private final Runnable     onFatalError;
    private final LinkedBlockingDeque<byte[]> outbox = new LinkedBlockingDeque<>(MAX_QUEUED);
    private final AtomicBoolean fatalReported = new AtomicBoolean();

    private volatile InboundSink sink;
    private Thread writer;
    private Thread reader;
    private volatile boolean stop;

    public ControlStream(InputStream in, OutputStream out, Runnable onFatalError) {
        this.in = in;
        this.out = out;
        this.onFatalError = onFatalError;
    }

    // Wired after construction: the Controller that consumes inbound
    // messages needs this stream's send() to exist first.
    public void setInboundSink(InboundSink sink) {
        this.sink = sink;
    }

    public void start() {
        writer = new Thread(this::runWriter, "control-writer");
        reader = new Thread(this::runReader, "control-reader");
        writer.start();
        reader.start();
    }

    public void stop() {
        stop = true;
        outbox.clear();
        stopThread(writer);
        stopThread(reader);
    }

    public void send(byte[] msg) {
        Objects.requireNonNull(msg);
        if (stop) return;
        if (outbox.offerLast(msg)) return;

        // Full: try to drop an intermediate touch-MOVE to make room.
        // Touch-down/up and non-touch messages stay.
        for (byte[] b : outbox) {
            if (b.length >= 2 && b[0] == TYPE_INJECT_TOUCH_EVENT && (b[1] & 0xff) == ACTION_MOVE) {
                if (outbox.remove(b)) break;
            }
        }
        if (outbox.offerLast(msg)) return;

        // A queue containing only state transitions is unhealthy, but
        // silently dropping UP/CANCEL/key events leaves input stuck on the
        // target. Fail the control stream visibly instead.
        stop = true;
        if (writer != null) writer.interrupt();
        if (reader != null) reader.interrupt();
        try { out.close(); } catch (IOException ignored) {}
        try { in.close(); } catch (IOException ignored) {}
        Log.e("control: outbox saturated with non-droppable events");
        reportFatal();
    }

    // ---- writer ----

    public void runWriter() {
        if (writer == null) writer = Thread.currentThread();
        try {
            while (!stop) {
                byte[] msg = outbox.takeFirst();
                out.write(msg);
                out.flush();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            if (!stop) {
                Log.e(e, "control writer");
                reportFatal();
            }
        } finally {
            Log.i("control writer: end");
        }
    }

    // ---- reader ----

    public void runReader() {
        if (reader == null) reader = Thread.currentThread();
        try {
            byte[] tmp = new byte[12];
            while (!stop) {
                Wire.readFully(in, tmp, 0, 1);
                int type = tmp[0] & 0xff;
                switch (type) {
                    case DEV_TYPE_CLIPBOARD: {
                        Wire.readFully(in, tmp, 0, 4);
                        int len = Wire.readBe32(tmp, 0);
                        if (len < 0 || len > ControlMessages.MAX_DEVICE_CLIPBOARD_BYTES) {
                            throw new IOException("clipboard len out of range: " + len);
                        }
                        byte[] data = new byte[len];
                        Wire.readFully(in, data);
                        String text = Wire.decodeUtf8(data);
                        InboundSink s = sink;
                        if (s != null) s.onRemoteClipboard(text);
                        break;
                    }
                    case DEV_TYPE_ACK_CLIPBOARD: {
                        Wire.readFully(in, tmp, 0, 8);
                        long seq = (long) Wire.readBe32(tmp, 0) << 32
                                | (Wire.readBe32(tmp, 4) & 0xffffffffL);
                        Log.i("control: ack clipboard seq=%d", seq);
                        break;
                    }
                    case DEV_TYPE_UHID_OUTPUT: {
                        Wire.readFully(in, tmp, 0, 4);
                        int len = ((tmp[2] & 0xff) << 8) | (tmp[3] & 0xff);
                        if (len > 0) {
                            byte[] skip = new byte[len];
                            Wire.readFully(in, skip);
                        }
                        break;
                    }
                    default:
                        throw new IOException("unknown DeviceMessage type=" + type);
                }
            }
        } catch (IOException e) {
            if (!stop) {
                Log.e(e, "control reader");
                reportFatal();
            }
        } catch (Exception e) {
            Log.e(e, "control reader unexpected");
            if (!stop) reportFatal();
        } finally {
            Log.i("control reader: end");
        }
    }

    private void reportFatal() {
        if (onFatalError != null && fatalReported.compareAndSet(false, true)) {
            onFatalError.run();
        }
    }

    private static void stopThread(Thread t) {
        if (t == null) return;
        t.interrupt();
        if (t == Thread.currentThread()) return;
        try { t.join(1_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
