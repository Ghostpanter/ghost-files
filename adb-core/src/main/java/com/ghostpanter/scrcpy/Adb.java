package com.ghostpanter.scrcpy;

import android.content.Context;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;

// Thin facade over libadb-android. One instance per process: pairs once,
// connects per session, then exposes typed openers for the three streams
// the rest of the app cares about (shell, sync, localabstract).
//
// The keypair lives in filesDir/{adbkey,adbcert}: PKCS#8 DER for the key and
// X.509 DER for the certificate. This matches adb's file-backed identity and
// keeps both pairing and later TLS authentication on one stable key. Android
// backup is disabled so the private key stays on the device that created it.
public final class Adb {

    // Label shown in the target's "Paired devices" list.
    private static final String DEVICE_NAME = "ghost-files";
    private static final String KEY_FILE = "adbkey";
    private static final String CERT_FILE = "adbcert";

    // Per-operation timeout for adb protocol calls (pair/connect/openStream).
    // libadb-android defaults to Long.MAX_VALUE; a hung target would block
    // forever otherwise.
    private static final long OP_TIMEOUT_MS = 15_000L;

    private static final int MAX_KEY_FILE_BYTES = 16 * 1024;
    private static final int MAX_CERT_FILE_BYTES = 64 * 1024;

    // Singleton: Main and Mirror both want an Adb; building two would
    // race on the on-disk keypair and waste a RSA generation. Lazily
    // initialised on the FIRST caller, which should be off the UI thread.
    private static volatile Adb instance;

    public static Adb getInstance(Context ctx) throws Exception {
        Adb local = instance;
        if (local != null) return local;
        synchronized (Adb.class) {
            if (instance == null) instance = new Adb(ctx.getApplicationContext());
            return instance;
        }
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;
    private volatile AdbConnection connection;
    private volatile AdbConnection connecting;
    private volatile String connectedHost;
    // USB targets share one connection with AdbPool (one claim per interface): never closed here.
    private volatile boolean connectionShared;
    private volatile int connectedPort;

    private Adb(Context ctx) throws Exception {
        File keyFile = new File(ctx.getFilesDir(), KEY_FILE);
        File certFile = new File(ctx.getFilesDir(), CERT_FILE);
        KeyPair pair;
        if (keyFile.exists()) {
            try {
                pair = keyPair(loadKey(keyFile));
            } catch (Exception e) {
                // A missing or broken certificate is recoverable without
                // changing identity. A broken private key is not.
                pair = generateRsa();
                saveKey(keyFile, pair.getPrivate());
                Log.w("adb: replaced unreadable private key: %s", e);
            }
        } else {
            pair = generateRsa();
            saveKey(keyFile, pair.getPrivate());
            Log.i("adb: generated new private key at %s", keyFile);
        }

        privateKey = pair.getPrivate();
        Certificate loaded;
        try {
            loaded = loadCert(certFile);
            verifyKeyPair(privateKey, loaded);
        } catch (Exception e) {
            // The certificate is only a public wrapper around the stable
            // private key. Rebuild it after corruption or an interrupted
            // first launch without rotating the ADB identity.
            loaded = selfSignedCert(pair);
            saveCert(certFile, loaded);
            Log.w("adb: rebuilt certificate for stored private key: %s", e);
        }
        certificate = loaded;
        Log.i("adb: identity ready from %s", keyFile);
    }

    // Pairing authenticates the six-digit-code exchange with SPAKE2 and
    // authorizes this app's stable client key on the target.
    public synchronized void pairDevice(String host, int port, String code) throws Exception {
        validateEndpoint(host, port);
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("pairing code must be exactly six digits");
        }
        byte[] password = code.getBytes(StandardCharsets.US_ASCII);
        try {
            try (PairingConnectionCtx pairing = new PairingConnectionCtx(
                    host, port, password, privateKey, certificate, DEVICE_NAME)) {
                pairing.start();
            }
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /** Notified (on the connecting thread) when the target shows "Allow USB debugging?". */
    public interface AuthPromptListener {
        void onAwaitingUserAuth(String host, int port);
    }

    // Default port of legacy `adb tcpip` mode (Android 9/10 without Wireless debugging).
    public static final int LEGACY_TCPIP_PORT = 5555;
    // How long to wait for the user to tap "Allow" on the controlled device.
    static final long USER_AUTH_WAIT_MS = 60_000L;
    private static final long CONNECT_SLICE_MS = 500L;

    private static volatile AuthPromptListener authPromptListener;
    private volatile boolean lastLegacyAuth;

    /** Global UI hook; Main / Mirror set it while visible. */
    public static void setAuthPromptListener(AuthPromptListener l) {
        authPromptListener = l;
    }

    /** True when the current/last connection used classic RSA AUTH (adb tcpip). */
    public boolean isLegacyAuth() {
        return lastLegacyAuth;
    }

    public synchronized void connect(String host, int port) throws Exception {
        connect(host, port, null);
    }

    // Plain TCP + either TLS (Wireless debugging) or classic RSA AUTH
    // (`adb tcpip 5555`). For RSA AUTH with an unknown key the target shows
    // "Allow USB debugging?"; extend the wait to USER_AUTH_WAIT_MS then.
    public synchronized void connect(String host, int port, AuthPromptListener onPrompt)
            throws Exception {
        validateEndpoint(host, port);
        disconnect();
        if (com.ghostpanter.adb.usb.UsbAdb.isUsbHost(host)) {
            AdbConnection usb = com.ghostpanter.adb.usb.UsbAdb.sharedConnection(host, onPrompt);
            lastLegacyAuth = usb.isLegacyAuth();
            connection = usb;
            connectionShared = true;
            connectedHost = host;
            connectedPort = port;
            return;
        }
        AdbConnection next = establish(host, port, onPrompt, true);
        lastLegacyAuth = next.isLegacyAuth();
        connection = next;
        connectedHost = host;
        connectedPort = port;
    }

    /**
     * A second, independent connection with the same identity (file browser / tools pool),
     * so a mirror reconnect never tears down an AdbFs transfer and vice versa.
     */
    public AdbConnection newConnection(String host, int port, AuthPromptListener onPrompt)
            throws Exception {
        validateEndpoint(host, port);
        if (com.ghostpanter.adb.usb.UsbAdb.isUsbHost(host)) {
            return com.ghostpanter.adb.usb.UsbAdb.sharedConnection(host, onPrompt);
        }
        return establish(host, port, onPrompt, false);
    }

    /** The mirror's live connection when it targets host:port, else null. */
    public AdbConnection liveConnectionFor(String host, int port) {
        AdbConnection c = connection;
        if (c == null || !c.isConnectionEstablished()) return null;
        return host.equals(connectedHost) && port == connectedPort ? c : null;
    }

    // Plain TCP + either TLS (Wireless debugging) or classic RSA AUTH
    // (`adb tcpip 5555`). For RSA AUTH with an unknown key the target shows
    // "Allow USB debugging?"; extend the wait to USER_AUTH_WAIT_MS then.
    private AdbConnection establish(String host, int port, AuthPromptListener onPrompt,
            boolean trackPending) throws Exception {
        return handshake(new AdbConnection.Builder(host, port)
                .setPrivateKey(privateKey)
                .setCertificate(certificate)
                .build(), host, port, onPrompt, trackPending);
    }

    /**
     * ADB handshake over a non-socket link (USB host). Same RSA AUTH flow as `adb tcpip`: the
     * target shows "Allow USB debugging?" for a new key and we wait up to USER_AUTH_WAIT_MS.
     */
    public AdbConnection establishLink(io.github.muntashirakon.adb.AdbLink link, String label,
            AuthPromptListener onPrompt) throws Exception {
        AdbConnection c;
        try {
            c = AdbConnection.overLink(link, privateKey, certificate);
        } catch (RuntimeException e) {
            try { link.close(); } catch (IOException ignored) {}
            throw e;
        }
        return handshake(c, label, com.ghostpanter.adb.usb.UsbAdb.PORT, onPrompt, false);
    }

    private AdbConnection handshake(AdbConnection built, String host, int port,
            AuthPromptListener onPrompt, boolean trackPending) throws Exception {
        AdbConnection next = built;
        try {
            if (trackPending) connecting = next;
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("ADB connect cancelled");
            }
            boolean ok = next.connect(CONNECT_SLICE_MS, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            boolean prompted = false;
            while (!ok) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("ADB connect cancelled");
                }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (next.isAwaitingUserAuth() && !prompted) {
                    prompted = true;
                    start = System.nanoTime();
                    elapsed = 0;
                    Log.i("adb: %s:%d waiting for 'Allow USB debugging' on target", host, port);
                    AuthPromptListener l = onPrompt != null ? onPrompt : authPromptListener;
                    if (l != null) {
                        try { l.onAwaitingUserAuth(host, port); }
                        catch (RuntimeException e) { Log.w("adb: auth prompt listener: %s", e); }
                    }
                }
                long budget = prompted ? USER_AUTH_WAIT_MS : OP_TIMEOUT_MS;
                if (elapsed >= budget) {
                    throw new IOException(prompted
                            ? "adb auth not allowed on target within " + (USER_AUTH_WAIT_MS / 1000) + " s"
                            : "adb connect timed out");
                }
                ok = next.awaitConnection(Math.min(CONNECT_SLICE_MS, budget - elapsed),
                        TimeUnit.MILLISECONDS);
            }
            Log.i("adb: connected %s:%d auth=%s", host, port, next.isLegacyAuth() ? "rsa(tcpip)" : "tls");
            AdbConnection done = next;
            next = null;
            return done;
        } catch (Exception e) {
            closeAfterFailure(next, e);
            throw e;
        } finally {
            if (trackPending) connecting = null;
        }
    }

    public synchronized void disconnect() throws IOException {
        AdbConnection current = connection;
        boolean shared = connectionShared;
        connection = null;
        connectionShared = false;
        connectedHost = null;
        connectedPort = 0;
        if (current != null && !shared) current.close();
    }

    // Cancel a blocked connect, open, read, or write without waiting for this
    // facade's monitor. Session uses this from its deadline and stop paths.
    public void abort() {
        AdbConnection pending = connecting;
        AdbConnection current = connection;
        closeQuietly(pending);
        // A shared USB connection also carries file browsing / tools; the session closes its own
        // streams, so only network connections are torn down here.
        if (current != pending && !connectionShared) closeQuietly(current);
    }

    private static void closeAfterFailure(AdbConnection candidate, Exception failure) {
        if (candidate == null) return;
        try { candidate.close(); }
        catch (IOException e) { failure.addSuppressed(e); }
    }

    private static void closeQuietly(AdbConnection candidate) {
        if (candidate == null) return;
        try { candidate.close(); }
        catch (IOException e) { Log.w("adb: abort failed: %s", e); }
    }

    // ---- typed openers ----

    public synchronized AdbStream openAbstract(String name) throws IOException, InterruptedException {
        if (name == null || name.isEmpty() || name.length() > 255 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid abstract socket name");
        }
        return openDestination("localabstract:" + name);
    }

    public synchronized AdbStream openShell(String cmd) throws IOException, InterruptedException {
        if (cmd == null || cmd.isEmpty() || cmd.length() > 4096 || cmd.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid shell command");
        }
        return openDestination("shell:" + cmd);
    }

    // Interactive shell: destination "shell:" with no command. Keeps stdin/stdout
    // open for a streaming session (tablet terminal). Not a full PTY on all
    // adbd builds, but far better than one-shot shell:<cmd>.
    public synchronized AdbStream openInteractiveShell() throws IOException, InterruptedException {
        return openDestination("shell:");
    }

    public synchronized AdbStream openSync() throws IOException, InterruptedException {
        return openDestination("sync:");
    }

    // Ask adbd to listen on a fixed TCP port (standard "tcpip:<port>" service).
    // adbd commonly drops the current transport afterward; that is expected.
    // Caller must reconnect to host:port. Always closes the service stream.
    public synchronized void enableTcpip(int port) throws Exception {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("tcpip port must be 1024-65535");
        }
        AdbStream stream = null;
        try {
            stream = openDestination("tcpip:" + port);
            try (java.io.InputStream in = stream.openInputStream()) {
                byte[] buf = new byte[256];
                // Drain any "restarting in TCP mode..." banner; EOF/IOError is fine.
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) break;
                }
            } catch (IOException e) {
                Log.w("adb: tcpip:%d stream ended: %s", port, e);
            }
        } finally {
            if (stream != null) {
                try { stream.close(); }
                catch (IOException e) { Log.w("adb: tcpip stream close: %s", e); }
            }
            // Transport is often dead after tcpip:; clear so the next connect is clean.
            try { disconnect(); }
            catch (IOException e) { Log.w("adb: disconnect after tcpip: %s", e); }
        }
        Log.i("adb: enableTcpip(%d) issued", port);
    }

    private AdbStream openDestination(String destination) throws IOException, InterruptedException {
        AdbConnection current = connection;
        if (current == null || !current.isConnectionEstablished()) {
            throw new IOException("not connected to ADB");
        }
        return current.open(destination, OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // ---- key + cert I/O ----

    private static KeyPair generateRsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        return gen.generateKeyPair();
    }

    private static KeyPair keyPair(PrivateKey key) throws Exception {
        if (!(key instanceof RSAPrivateCrtKey rsa)) {
            throw new IOException("stored ADB identity is not an RSA CRT key");
        }
        if (rsa.getModulus().bitLength() != 2048
                || (!BigInteger.valueOf(3).equals(rsa.getPublicExponent())
                && !BigInteger.valueOf(65537).equals(rsa.getPublicExponent()))) {
            throw new IOException("stored ADB identity has unsupported RSA parameters");
        }
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        return new KeyPair(publicKey, key);
    }

    private static PrivateKey loadKey(File f) throws Exception {
        byte[] data = readLimited(f, MAX_KEY_FILE_BYTES);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(data));
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private static Certificate loadCert(File f) throws Exception {
        byte[] data = readLimited(f, MAX_CERT_FILE_BYTES);
        return CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(data));
    }

    private static void saveKey(File f, PrivateKey k) throws IOException {
        byte[] data = k.getEncoded();
        try {
            AtomicFiles.write(f, data);
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private static void saveCert(File f, Certificate c) throws Exception {
        AtomicFiles.write(f, c.getEncoded());
    }

    private static void verifyKeyPair(PrivateKey key, Certificate cert) throws IOException {
        if (!(key instanceof RSAPrivateCrtKey) || !(cert.getPublicKey() instanceof RSAPublicKey)) {
            throw new IOException("stored ADB identity is not an RSA keypair");
        }
        RSAPrivateCrtKey privateRsa = (RSAPrivateCrtKey) key;
        RSAPublicKey publicRsa = (RSAPublicKey) cert.getPublicKey();
        if (!privateRsa.getModulus().equals(publicRsa.getModulus())
                || !privateRsa.getPublicExponent().equals(publicRsa.getPublicExponent())) {
            throw new IOException("stored ADB private key and certificate do not match");
        }
    }

    private static byte[] readLimited(File file, int maxBytes) throws IOException {
        if (!file.isFile()) throw new IOException("not a regular file: " + file);
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int total = 0;
            for (;;) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) throw new IOException("identity file read made no progress");
                if (total > maxBytes - n) throw new IOException("identity file is too large");
                out.write(buf, 0, n);
                total += n;
            }
            if (total == 0) throw new IOException("identity file is empty");
            return out.toByteArray();
        }
    }

    private static void validateEndpoint(String host, int port) {
        if (host == null || host.isEmpty() || host.length() > 255) {
            throw new IllegalArgumentException("host is invalid");
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '[' || c == ']') {
                throw new IllegalArgumentException("host is invalid");
            }
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port is invalid");
    }

    // Build a minimal self-signed X.509 certificate over the keypair.
    //
    // ASN.1 layout (RFC 5280 sec. 4.1):
    //
    //   Certificate ::= SEQUENCE {
    //     tbsCertificate       TBSCertificate,
    //     signatureAlgorithm   AlgorithmIdentifier,    -- sha256WithRSAEnc
    //     signature            BIT STRING               -- RSA over tbs
    //   }
    //
    //   TBSCertificate ::= SEQUENCE {
    //     version              [0] EXPLICIT v3,
    //     serialNumber         INTEGER 1,
    //     signatureAlgorithm   AlgorithmIdentifier,
    //     issuer  = subject    Name (CN=scrcpy-android),
    //     validity             { notBefore, notAfter },
    //     subjectPublicKeyInfo SubjectPublicKeyInfo
    //   }
    //
    // ADB authorizes this identity by the public key embedded in the
    // certificate. The DN is only a local label, but a valid self-signed
    // X.509 certificate is required by the TLS pairing and connect paths.
    private static Certificate selfSignedCert(KeyPair kp) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L);
        Date notAfter  = new Date(now + 50L * 365 * 24 * 3600 * 1000L);
        X500Name dn = new X500Name("CN=" + DEVICE_NAME);
        AlgorithmIdentifier sigAlg =
                new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption);

        V3TBSCertificateGenerator g = new V3TBSCertificateGenerator();
        g.setSerialNumber(new ASN1Integer(BigInteger.ONE));
        g.setSignature(sigAlg);
        g.setIssuer(dn);
        g.setSubject(dn);
        g.setStartDate(new Time(notBefore));
        g.setEndDate(new Time(notAfter));
        g.setSubjectPublicKeyInfo(
                SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded()));

        TBSCertificate tbs = g.generateTBSCertificate();

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs.getEncoded(ASN1Encoding.DER));
        byte[] sig = s.sign();

        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(tbs);
        v.add(sigAlg);
        v.add(new DERBitString(sig));
        byte[] certDer = new DERSequence(v).getEncoded(ASN1Encoding.DER);

        return CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
    }
}
