// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import android.os.Build;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
/**
 * This class represents an ADB connection.
 */
// Copyright 2013 Cameron Gutman
public class AdbConnection implements Closeable, AdbStream.Transport {
    public static final String TAG = "scrcpy-android";

    /**
     * The underlying socket that this class uses to communicate with the target device.
     */
    @NonNull
    private final Socket mSocket;

    @NonNull
    private final String mHost;

    private final int mPort;

    /**
     * The last allocated local stream ID. The ID chosen for the next stream will be this value + 1.
     */
    private int mLastLocalId;

    /**
     * The input stream that this class uses to read from the socket.
     */
    @GuardedBy("lock")
    @NonNull
    private final InputStream mPlainInputStream;

    /**
     * The output stream that this class uses to read from the socket.
     */
    @GuardedBy("lock")
    @NonNull
    private final OutputStream mPlainOutputStream;

    /**
     * The input stream that this class uses to read from the TLS socket.
     */
    @GuardedBy("lock")
    @Nullable
    private volatile InputStream mTlsInputStream;

    /**
     * The output stream that this class uses to read from the TLS socket.
     */
    @GuardedBy("lock")
    @Nullable
    private volatile OutputStream mTlsOutputStream;

    /**
     * The backend thread that handles responding to ADB packets.
     */
    @NonNull
    private final Thread mConnectionThread;

    /**
     * Specifies whether a CNXN has been attempted.
     */
    private volatile boolean mConnectAttempted;

    /** The worker thread is one-shot, even after a failed connection. */
    private boolean mConnectionThreadStarted;

    /**
     * Specifies whether a CNXN packet has been received from the peer.
     */
    private volatile boolean mConnectionEstablished;

    /**
     * Exceptions that occur in {@link #createConnectionThread()}.
     */
    @Nullable
    private volatile Exception mConnectionException;

    /**
     * Specifies the maximum amount data that can be sent to the remote peer.
     * This is only valid after connect() returns successfully.
     */
    private volatile int mMaxData;

    private volatile int mProtocolVersion;

    private final int mLocalMaxData;

    private final int mLocalProtocolVersion;

    @NonNull
    private final KeyPair mKeyPair;

    /**
     * A hash map of our opened streams indexed by local ID.
     */
    @NonNull
    private final ConcurrentHashMap<Integer, AdbStream> mOpenedStreams;

    private volatile boolean mIsTls = false;

    @GuardedBy("lock")
    @NonNull
    private final Object mLock = new Object();

    /**
     * Internal constructor to initialize some internal state
     */
    // LOCAL PATCH: bound on the TCP connect; see the constructor.
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int CLOSE_TIMEOUT_MS = 5_000;

    @WorkerThread
    private AdbConnection(@NonNull String host, int port, @NonNull KeyPair keyPair)
            throws IOException {
        this.mHost = Objects.requireNonNull(host);
        this.mPort = port;
        this.mLocalProtocolVersion = AdbProtocol.getProtocolVersion(Build.VERSION_CODES.R);
        this.mLocalMaxData = AdbProtocol.getMaxData(Build.VERSION_CODES.R);
        this.mProtocolVersion = mLocalProtocolVersion;
        this.mMaxData = mLocalMaxData;
        this.mKeyPair = Objects.requireNonNull(keyPair);
        Socket socket = new Socket();
        try {
            // LOCAL PATCH: was `new Socket(host, port)`, which blocks on
            // the OS default TCP timeout - minutes on a mobile network -
            // with no way to cancel. A target that is off or on another
            // network is the common case, not an edge case, so bound it.
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException("cannot reach " + host + ":" + port
                    + " within " + CONNECT_TIMEOUT_MS + " ms").initCause(e);
        }
        InputStream plainInput;
        OutputStream plainOutput;
        try {
            // Disable Nagle because we're sending tiny packets.
            socket.setTcpNoDelay(true);
            plainInput = socket.getInputStream();
            plainOutput = socket.getOutputStream();
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw (IOException) new IOException("cannot initialize ADB socket").initCause(e);
        }
        this.mSocket = socket;
        this.mPlainInputStream = plainInput;
        this.mPlainOutputStream = plainOutput;

        this.mOpenedStreams = new ConcurrentHashMap<>();
        this.mLastLocalId = 0;
        this.mConnectionThread = createConnectionThread();
    }

    @GuardedBy("lock")
    @NonNull
    private InputStream getInputStream() {
        return mIsTls ? Objects.requireNonNull(mTlsInputStream) : mPlainInputStream;
    }

    @GuardedBy("lock")
    @NonNull
    private OutputStream getOutputStream() {
        return mIsTls ? Objects.requireNonNull(mTlsOutputStream) : mPlainOutputStream;
    }

    /**
     * Creates a new connection thread.
     *
     * @return A new connection thread.
     */
    @NonNull
    private Thread createConnectionThread() {
        return new Thread(() -> {
            while (!mConnectionThread.isInterrupted()) {
                try {
                    // Read and parse a message off the socket's input stream
                    AdbProtocol.Message msg = AdbProtocol.Message.parse(getInputStream(), mProtocolVersion, mMaxData);

                    switch (msg.command) {
                        // Stream-oriented commands
                        case AdbProtocol.A_OKAY:
                        case AdbProtocol.A_WRTE:
                        case AdbProtocol.A_CLSE: {
                            // Ignore all packets when not connected
                            if (!mConnectionEstablished) {
                                continue;
                            }

                            // Get the stream object corresponding to the packet
                            AdbStream waitingStream = mOpenedStreams.get(msg.arg1);
                            if (waitingStream == null) {
                                if (msg.command == AdbProtocol.A_WRTE) {
                                    throw new IOException("WRTE for unknown local stream " + msg.arg1);
                                }
                                continue;
                            }

                            synchronized (waitingStream) {
                                if (msg.command == AdbProtocol.A_OKAY) {
                                    // We're ready for writes
                                    waitingStream.updateRemoteId(msg.arg0);
                                    waitingStream.readyForWrite();
                                } else if (msg.command == AdbProtocol.A_WRTE) {
                                    if (!waitingStream.hasRemoteId(msg.arg0)) {
                                        throw new IOException("WRTE remote stream ID mismatch");
                                    }
                                    // Got some data from our partner
                                    waitingStream.addPayload(msg.payload);
                                } else { // if (msg.command == AdbProtocol.A_CLSE) {
                                    if (!waitingStream.hasRemoteId(msg.arg0) && msg.arg0 != 0) {
                                        throw new IOException("CLSE remote stream ID mismatch");
                                    }
                                    mOpenedStreams.remove(msg.arg1);
                                    // Notify readers and writers
                                    waitingStream.notifyClose(true);
                                }
                            }
                            break;
                        }
                        case AdbProtocol.A_STLS: {
                            if (mIsTls || mConnectionEstablished
                                    || msg.arg0 < AdbProtocol.A_STLS_VERSION_MIN) {
                                throw new IOException("Invalid or repeated STLS request");
                            }
                            sendPacket(AdbProtocol.generateStls());

                            SSLContext sslContext = SslUtils.getSslContext(mKeyPair);
                            SSLSocket tlsSocket = (SSLSocket) sslContext.getSocketFactory()
                                    .createSocket(mSocket, mHost, mPort, true);
                            tlsSocket.startHandshake();
                            Log.d(TAG, "Handshake succeeded.");

                            synchronized (AdbConnection.this) {
                                mTlsInputStream = tlsSocket.getInputStream();
                                mTlsOutputStream = tlsSocket.getOutputStream();
                                mIsTls = true;
                            }
                            break;
                        }
                        case AdbProtocol.A_AUTH: {
                            // Classic RSA AUTH (adbd in `adb tcpip 5555` mode, Android <= 10
                            // and any adbd without Wireless debugging). Sign the token once;
                            // if the key is unknown, offer the public key once so the target
                            // shows "Allow USB debugging?" and wait for CNXN.
                            if (mIsTls || mConnectionEstablished) {
                                throw new IOException("Unexpected AUTH after TLS/CNXN");
                            }
                            if (msg.arg0 != AdbProtocol.ADB_AUTH_TOKEN || msg.payload == null) {
                                throw new IOException("Invalid AUTH request type " + msg.arg0);
                            }
                            mLegacyAuth = true;
                            if (!mSentSignature) {
                                mSentSignature = true;
                                sendPacket(AdbProtocol.generateAuth(AdbProtocol.ADB_AUTH_SIGNATURE,
                                        AndroidPubkey.adbAuthSign(mKeyPair.getPrivateKey(), msg.payload)));
                            } else if (!mSentPublicKey) {
                                mSentPublicKey = true;
                                byte[] pub = AndroidPubkey.encodeWithName(
                                        (java.security.interfaces.RSAPublicKey) mKeyPair.getPublicKey(),
                                        AUTH_KEY_NAME);
                                sendPacket(AdbProtocol.generateAuth(AdbProtocol.ADB_AUTH_RSAPUBLICKEY, pub));
                                synchronized (AdbConnection.this) {
                                    mAwaitingUserAuth = true;
                                    AdbConnection.this.notifyAll();
                                }
                            }
                            // Further tokens after the public key: keep waiting for the user.
                            break;
                        }
                        case AdbProtocol.A_CNXN: {
                            if (!mIsTls && !mLegacyAuth) {
                                throw new IOException("ADB peer did not negotiate TLS or RSA AUTH");
                            }
                            if (msg.arg0 < AdbProtocol.A_VERSION_MIN || msg.arg1 <= 0) {
                                throw new IOException("Invalid CNXN parameters: version="
                                        + msg.arg0 + " maxData=" + msg.arg1);
                            }
                            synchronized (AdbConnection.this) {
                                // Both fields come from an unauthenticated
                                // peer. Negotiate down, never let the peer
                                // raise our allocation or checksum limits.
                                mProtocolVersion = Math.min(msg.arg0, mLocalProtocolVersion);
                                mMaxData = Math.min(msg.arg1, mLocalMaxData);
                                mConnectionEstablished = true;
                                mAwaitingUserAuth = false;
                                AdbConnection.this.notifyAll();
                            }
                            break;
                        }
                        case AdbProtocol.A_OPEN:
                        default:
                            throw new IOException(String.format(
                                    "Unexpected ADB command 0x%x", msg.command));
                    }
                } catch (Exception e) {
                    if (!mSocket.isClosed()) {
                        mConnectionException = e;
                        Log.e(TAG, "ADB connection failed", e);
                    }
                    // The cleanup is taken care of by a combination of this thread and close()
                    break;
                }
            }

            // This thread takes care of cleaning up pending streams
            synchronized (AdbConnection.this) {
                cleanupStreams();
                AdbConnection.this.notifyAll();
                mConnectionEstablished = false;
                mConnectAttempted = false;
            }
        });
    }

    /**
     * Whether a connection has been established. A connection has been established if a CONNECT request has been
     * received from the ADB daemon.
     */
    public boolean isConnectionEstablished() {
        return mConnectionEstablished;
    }

    /** Name shown next to the key in the target's "Allow USB debugging?" dialog. */
    private static final String AUTH_KEY_NAME = "scrcpy-ghostpanter";

    private volatile boolean mLegacyAuth;
    private volatile boolean mSentSignature;
    private volatile boolean mSentPublicKey;
    private volatile boolean mAwaitingUserAuth;

    /** True when the peer used classic RSA AUTH instead of TLS (adb tcpip mode). */
    public boolean isLegacyAuth() {
        return mLegacyAuth;
    }

    /** True after our public key was offered and the target is showing the allow dialog. */
    public boolean isAwaitingUserAuth() {
        return mAwaitingUserAuth;
    }

    @Override
    public int getMaxData() {
        return mMaxData;
    }

    /**
     * Connects to the remote device. This routine will block until the connection completes or the timeout elapses.
     *
     * @param timeout the time to wait for the lock
     * @param unit the time unit of the timeout argument
     * @return {@code true} if the connection was established, or {@code false} if the connection timed out
     * @throws IOException                      If the socket fails while connecting
     * @throws InterruptedException             If timeout has reached
     */
    public synchronized boolean connect(long timeout, @NonNull TimeUnit unit)
            throws IOException, InterruptedException {
        validateTimeout(timeout, unit);
        if (mConnectionThreadStarted) {
            throw new IllegalStateException("Connection already attempted");
        }

        // Send CONNECT
        sendPacket(AdbProtocol.generateConnect(Build.VERSION_CODES.R));

        // Start the connection thread to respond to the peer
        mConnectAttempted = true;
        mConnectionThreadStarted = true;
        mConnectionThread.start();

        return waitForConnection(timeout, Objects.requireNonNull(unit));
    }

    /**
     * Keep waiting for CNXN after {@link #connect} returned {@code false}
     * (e.g. while the target shows "Allow USB debugging?").
     */
    public boolean awaitConnection(long timeout, @NonNull TimeUnit unit)
            throws IOException, InterruptedException {
        validateTimeout(timeout, unit);
        if (!mConnectionThreadStarted) {
            throw new IllegalStateException("connect() must be called first");
        }
        return waitForConnection(timeout, Objects.requireNonNull(unit));
    }

    @NonNull
    public AdbStream open(@NonNull String destination, long timeout, @NonNull TimeUnit unit)
            throws IOException, InterruptedException {
        validateTimeout(timeout, unit);
        Objects.requireNonNull(destination);

        if (!mConnectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }

        if (!waitForConnection(timeout, unit)) {
            throw new SocketTimeoutException("ADB connection timed out.");
        }

        int localId = nextLocalId();

        // Add this stream to this list of half-open streams
        AdbStream stream = new AdbStream(this, localId);
        mOpenedStreams.put(localId, stream);

        long timeoutMillis = unit.toMillis(timeout);
        long started = System.nanoTime();
        try {
            // Send OPEN only after publishing the half-open stream so an
            // immediate response cannot race past the lookup table.
            sendPacket(AdbProtocol.generateOpen(localId, destination));
            synchronized (stream) {
                while (!stream.isOpen() && !stream.isClosed()) {
                    if (timeoutMillis == Long.MAX_VALUE) {
                        stream.wait();
                        continue;
                    }
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    long remaining = timeoutMillis - elapsed;
                    if (remaining <= 0) {
                        throw new SocketTimeoutException("ADB stream open timed out: " + destination);
                    }
                    stream.wait(remaining);
                }
            }
        } catch (IOException | InterruptedException e) {
            mOpenedStreams.remove(localId, stream);
            stream.notifyClose(false);
            throw e;
        }

        // Check if the OPEN request was rejected
        if (stream.isClosed()) {
            mOpenedStreams.remove(localId);
            throw new ConnectException("Stream open actively rejected by remote peer.");
        }

        return stream;
    }

    private synchronized int nextLocalId() throws IOException {
        if (mLastLocalId == Integer.MAX_VALUE) {
            throw new IOException("ADB stream ID space exhausted");
        }
        return ++mLastLocalId;
    }

    private boolean waitForConnection(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, IOException {
        synchronized (this) {
            // Block if a connection is pending, but not yet complete
            long timeoutMillis = Objects.requireNonNull(unit).toMillis(timeout);
            long started = System.nanoTime();
            while (!mConnectionEstablished && mConnectAttempted) {
                if (timeoutMillis == Long.MAX_VALUE) {
                    wait();
                    continue;
                }
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                long remainingMillis = timeoutMillis - elapsedMillis;
                if (remainingMillis <= 0) break;
                wait(remainingMillis);
            }

            if (!mConnectionEstablished) {
                if (mConnectAttempted) {
                    return false;
                } else {
                    throw new IOException("Connection failed", mConnectionException);
                }
            }
        }

        return true;
    }

    private static void validateTimeout(long timeout, @NonNull TimeUnit unit) {
        Objects.requireNonNull(unit);
        if (timeout < 0) throw new IllegalArgumentException("negative timeout");
    }

    /**
     * This function terminates all I/O on streams associated with this ADB connection
     */
    private void cleanupStreams() {
        // The socket is already unusable. Wake every stream without trying
        // to write CLSE packets back through the failed transport.
        for (AdbStream s : mOpenedStreams.values()) {
            s.notifyClose(false);
        }
        mOpenedStreams.clear();
    }

    /**
     * This routine closes the Adb connection and underlying socket
     *
     * @throws IOException if the socket fails to close
     */
    @Override
    public void close() throws IOException {
        // Closing the socket will kick the connection thread
        mSocket.close();

        // Wait for the connection thread to die
        mConnectionThread.interrupt();
        try {
            if (mConnectionThread != Thread.currentThread()) {
                mConnectionThread.join(CLOSE_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw (IOException) new IOException("interrupted while closing ADB connection")
                    .initCause(e);
        }
        if (mConnectionThread.isAlive()) {
            throw new IOException("ADB connection thread did not stop within "
                    + CLOSE_TIMEOUT_MS + " ms");
        }

        // The connection manager owns the keypair. A disconnect is routine
        // during reconnect and must not destroy the key reused by the next
        // connection.
    }

    @Override
    public void sendPacket(byte[] packet) throws IOException {
        synchronized (mLock) {
            OutputStream os = getOutputStream();
            os.write(packet);
            os.flush();
        }
    }

    @Override
    public void flushPacket() throws IOException {
        synchronized (mLock) {
            getOutputStream().flush();
        }
    }

    public static class Builder {
        private final String mHost;
        private final int mPort;
        private PrivateKey mPrivateKey;
        private Certificate mCertificate;

        public Builder(String host, int port) {
            mHost = host;
            mPort = port;
        }

        /**
         * Set generated/stored private key.
         */
        public Builder setPrivateKey(PrivateKey privateKey) {
            this.mPrivateKey = privateKey;
            return this;
        }

        /**
         * Set public key wrapped around a certificate
         */
        public Builder setCertificate(Certificate certificate) {
            this.mCertificate = certificate;
            return this;
        }

        /**
         * Creates a new {@link AdbConnection} associated with the socket and crypto object specified.
         *
         * @throws IOException If there was an error while establishing a socket connection
         */
        public AdbConnection build() throws IOException {
            if (mHost == null || mHost.isEmpty()) throw new IllegalArgumentException("host is empty");
            if (mPort < 1 || mPort > 65535) throw new IllegalArgumentException("port is invalid");
            if (mPrivateKey == null || mCertificate == null) {
                throw new UnsupportedOperationException("Private key and certificate must be set.");
            }
            return new AdbConnection(mHost, mPort,
                    new KeyPair(mPrivateKey, mCertificate));
        }
    }
}
