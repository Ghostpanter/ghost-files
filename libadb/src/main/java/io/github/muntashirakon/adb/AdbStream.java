// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import java.io.Closeable;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class abstracts the underlying ADB streams
 */
// Copyright 2013 Cameron Gutman
public class AdbStream implements Closeable {

    // Small transport seam around the connection. It keeps this stream state
    // machine testable without a socket or Android runtime.
    interface Transport {
        int getMaxData();
        void sendPacket(byte[] packet) throws IOException;
        void flushPacket() throws IOException;
    }

    /**
     * The AdbConnection object that the stream communicates over
     */
    private final Transport mTransport;

    /**
     * The local ID of the stream
     */
    private final int mLocalId;

    /**
     * The remote ID of the stream
     */
    private volatile int mRemoteId;

    /**
     * Indicates whether WRTE is currently allowed
     */
    private final AtomicBoolean mWriteReady;

    /**
     * A queue of data from the target's WRTE packets
     */
    private final Queue<byte[]> mReadQueue;

    /**
     * Store data received from the first WRTE packet in order to support buffering.
     */
    private final ByteBuffer mReadBuffer;

    /**
     * Indicates whether the connection is closed already
     */
    private volatile boolean mIsClosed;

    /**
     * Whether the remote peer has closed but we still have unread data in the queue
     */
    private volatile boolean mPendingClose;

    /** One WRTE has been received and has not yet been fully consumed. */
    private boolean mReadPacketPending;

    /**
     * Creates a new AdbStream object on the specified AdbConnection
     * with the given local ID.
     *
     * @param adbConnection AdbConnection that this stream is running on
     * @param localId       Local ID of the stream
     */
    AdbStream(Transport transport, int localId) {
        this.mTransport = transport;
        this.mLocalId = localId;
        this.mReadQueue = new ConcurrentLinkedQueue<>();
        this.mReadBuffer = (ByteBuffer) ByteBuffer.allocate(transport.getMaxData()).flip();
        this.mWriteReady = new AtomicBoolean(false);
        this.mIsClosed = false;
    }

    public AdbInputStream openInputStream() {
        return new AdbInputStream(this);
    }

    public AdbOutputStream openOutputStream() {
        return new AdbOutputStream(this);
    }

    /**
     * Called by the connection thread to indicate newly received data.
     *
     * @param payload Data inside the WRTE message
     */
    void addPayload(byte[] payload) throws IOException {
        synchronized (mReadQueue) {
            if (mReadPacketPending) {
                throw new StreamCorruptedException(
                        "ADB peer sent WRTE before the previous packet was acknowledged");
            }
            mReadPacketPending = true;
            mReadQueue.add(payload);
            mReadQueue.notifyAll();
        }
    }

    /**
     * Called by the connection thread to send an OKAY packet, allowing the
     * other side to continue transmission.
     *
     * @throws IOException If the connection fails while sending the packet
     */
    void sendReady() throws IOException {
        // Generate and send a OKAY packet
        mTransport.sendPacket(AdbProtocol.generateReady(mLocalId, mRemoteId));
    }

    /**
     * Called by the connection thread to update the remote ID for this stream
     *
     * @param remoteId New remote ID
     */
    void updateRemoteId(int remoteId) {
        if (remoteId == 0) throw new IllegalArgumentException("remote stream ID is zero");
        if (mRemoteId != 0 && mRemoteId != remoteId) {
            throw new IllegalStateException("remote stream ID changed");
        }
        this.mRemoteId = remoteId;
    }

    boolean hasRemoteId(int remoteId) {
        return mRemoteId != 0 && mRemoteId == remoteId;
    }

    /**
     * Called by the connection thread to indicate the stream is okay to send data.
     */
    void readyForWrite() {
        synchronized (this) {
            mWriteReady.set(true);
            notifyAll();
        }
    }

    boolean isOpen() {
        return mRemoteId != 0 && !mIsClosed;
    }

    /**
     * Called by the connection thread to notify that the stream was closed by the peer.
     */
    void notifyClose(boolean closedByPeer) {
        // We don't call close() because it sends another CLSE
        if (closedByPeer && hasUnreadData()) {
            // The remote peer closed the stream, but we haven't finished reading the remaining data
            mPendingClose = true;
        } else {
            mIsClosed = true;
        }

        // Notify readers and writers
        synchronized (this) {
            notifyAll();
        }
        synchronized (mReadQueue) {
            mReadQueue.notifyAll();
        }
    }

    /**
     * Read bytes from the ADB daemon.
     *
     * @return the next byte of data, or {@code -1} if the end of the stream is reached.
     * @throws IOException If the stream fails while waiting
     */
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        if (mReadBuffer.hasRemaining()) {
            return readBufferAndAcknowledge(bytes, offset, length);
        }
        while (true) {
            // Buffer has no data, grab from the queue
            synchronized (mReadQueue) {
                byte[] data;
                // Wait for the connection to close or data to be received
                while ((data = mReadQueue.poll()) == null && !mIsClosed) {
                    try {
                        mReadQueue.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw (IOException) new IOException("interrupted while reading ADB stream")
                                .initCause(e);
                    }
                }

                // Add data to the buffer
                if (data != null) {
                    mReadBuffer.clear();
                    mReadBuffer.put(data);
                    mReadBuffer.flip();
                    if (mReadBuffer.hasRemaining()) {
                        return readBufferAndAcknowledge(bytes, offset, length);
                    }
                    // Empty WRTE packets are legal but carry no bytes. Ack
                    // and continue instead of returning a false EOF.
                    finishReadPacket();
                }

                if (mIsClosed) return -1;

                if (mPendingClose && !hasUnreadData()) {
                    mPendingClose = false;
                    mIsClosed = true;
                    return -1;
                }
            }
        }
    }

    private int readBuffer(byte[] bytes, int offset, int length) {
        int count = 0;
        for (int i = offset; i < offset + length; ++i) {
            if (mReadBuffer.hasRemaining()) {
                bytes[i] = mReadBuffer.get();
                ++count;
            }
        }
        return count;
    }

    private int readBufferAndAcknowledge(byte[] bytes, int offset, int length) throws IOException {
        int count = readBuffer(bytes, offset, length);
        if (!mReadBuffer.hasRemaining()) finishReadPacket();
        return count;
    }

    private void finishReadPacket() throws IOException {
        boolean closeAfterDrain;
        synchronized (mReadQueue) {
            mReadPacketPending = false;
            closeAfterDrain = mPendingClose && mReadQueue.isEmpty();
            if (closeAfterDrain) {
                mPendingClose = false;
                mIsClosed = true;
            }
        }
        // A peer that already sent CLSE neither needs nor expects OKAY.
        if (!closeAfterDrain && !mIsClosed) sendReady();
    }

    private boolean hasUnreadData() {
        synchronized (mReadQueue) {
            return mReadPacketPending || mReadBuffer.hasRemaining() || !mReadQueue.isEmpty();
        }
    }

    /**
     * Sends a WRTE packet with a given byte array payload. It does not flush the stream.
     *
     * @param bytes Payload in the form of a byte array
     * @throws IOException If the stream fails while sending data
     */
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return;
        // Split and send data as WRTE packet
        int maxData = mTransport.getMaxData();
        while (length != 0) {
            int count = Math.min(length, maxData);
            sendWrite(bytes, offset, count);
            offset += count;
            length -= count;
        }
    }

    private void sendWrite(byte[] bytes, int offset, int count) throws IOException {
        synchronized (this) {
            while (!mIsClosed && !mWriteReady.compareAndSet(true, false)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw (IOException) new IOException("interrupted while writing ADB stream")
                            .initCause(e);
                }
            }
            if (mIsClosed) throw new IOException("Stream closed");
            // Keep consuming OKAY and sending WRTE atomic with close(). A
            // concurrent close must never put WRTE on the wire after CLSE.
            mTransport.sendPacket(
                    AdbProtocol.generateWrite(mLocalId, mRemoteId, bytes, offset, count));
        }
    }

    public void flush() throws IOException {
        if (mIsClosed) {
            throw new IOException("Stream closed");
        }
        mTransport.flushPacket();
    }

    /**
     * Closes the stream. This sends a close message to the peer.
     *
     * @throws IOException If the stream fails while sending the close message.
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            // This may already be closed by the remote host
            if (mIsClosed)
                return;

            // Notify readers/writers that we've closed
            notifyClose(false);
        }

        mTransport.sendPacket(AdbProtocol.generateClose(mLocalId, mRemoteId));
    }

    /**
     * Returns whether the stream is closed or not
     *
     * @return True if the stream is close, false if not
     */
    public boolean isClosed() {
        return mIsClosed;
    }

    /**
     * Returns an estimate of available data.
     *
     * @return an estimate of the number of bytes that can be read from this stream without blocking.
     * @throws IOException if the stream is close.
     */
    public int available() throws IOException {
        synchronized (this) {
            if (mReadBuffer.hasRemaining()) {
                return mReadBuffer.remaining();
            }
            if (mIsClosed) return 0;
            byte[] data = mReadQueue.peek();
            return data == null ? 0 : data.length;
        }
    }
}
