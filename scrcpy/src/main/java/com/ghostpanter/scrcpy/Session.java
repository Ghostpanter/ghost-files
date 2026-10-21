package com.ghostpanter.scrcpy;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// One mirroring session: owns the Adb connection, the spawned scrcpy
// server, the three streams, sinks, and the controller. start() and
// stop() are idempotent and may be called from any thread.
//
// The Listener contract is stated once, on the interface below.
//
// Two retry budgets, because there are two different failures.
// BACKOFF_MS covers bring-up, where the target is not answering yet.
// RECONNECT_BACKOFF_MS covers a link that came up and then died (target
// sleep, Wi-Fi blip, server crash) - and also the case where it dies
// every time for the same permanent reason, which is why that budget is
// finite. Either budget running out ends the session via onError +
// onStopped rather than retrying forever.
//
// Surface-readiness race: Mirror.surfaceCreated builds the Session and
// calls start(), but the viewport can be measured before run() has
// finished constructing Controller. setViewport() therefore stashes the
// value and applies it as soon as the Controller is available.
public final class Session {

    public interface Listener {
        // Fires the first time the server reports a session-meta packet
        // (wire open, frames about to flow), and again after each
        // successful auto-reconnect. Useful for the activity's status bar.
        default void onConnected(long geometryVersion, int w, int h) {}
        // A previously-live link dropped and we are bringing it back up.
        // Followed by onConnected (recovered), onError (gave up), or
        // onStopped alone (user stop() raced the reconnect).
        default void onReconnecting() {}
        // After server bring-up: whether Root elevate succeeded. Used to
        // toast Magisk grant / FLAG_SECURE fallback clearly in Chinese.
        default void onElevateStatus(Server.ElevateStatus status) {}
        // Fired only when su is pending Magisk grant (long wait). Not used
        // when Magisk already allowed ADB shell (quick probe succeeds).
        default void onElevateWaitingForGrant() {}
        /** DisplayManager rejected SECURE VD on this OEM — show LSPosed tip. */
        default void onSecureOemRejected() {}
        /** Controlled device API level (ro.build.version.sdk), once per session; 0 if unknown. */
        default void onRemoteSdk(int sdk) {}
        // Fatal: a retry budget ran out, or the failure was not
        // retriable. Always followed by onStopped().
        void onError(Throwable t);
        // Final state. Fires exactly once per session, whether from
        // stop() or from a budget running out.
        void onStopped();
    }

    // Bring-up retry budget, for a target that is not answering yet.
    // Total worst-case wait ~ sum of these delays + per-attempt bring-up
    // time.
    private static final long[] BACKOFF_MS =
            {0L, 1_500L, 5_000L, 10_000L, 20_000L};

    // Reconnect budget, for a link that dropped AFTER it came up. This is
    // a different failure from bring-up: something that worked has
    // stopped working, and it may be permanent (target has no decoder for
    // the selected codec, frame larger than the decoder's input buffer).
    // Without a budget those spin here forever at zero delay, re-pushing
    // the server jar and respawning app_process on the target on every
    // pass, with nothing shown to the user.
    //
    // A connection that stays up for HEALTHY_MS resets the budget, so a
    // long session that blips occasionally never exhausts it, while a
    // deterministic failure walks the ladder and then gives up.
    private static final long[] RECONNECT_BACKOFF_MS =
            {1_000L, 2_000L, 5_000L, 10_000L, 15_000L};
    private static final long HEALTHY_MS = 120_000L;

    private static final long BRING_UP_DEADLINE_MS = 120_000L;
    private static final long REDISCOVER_MS = 15_000L;
    private static final long STOP_JOIN_MS = 10_000L;

    private final Context        ctx;
    private final Adb            adb;
    private volatile Devices.Device target;
    private volatile Surface     surface;
    private final Listener       listener;

    private Server        server;
    private VideoStream   videoStream;
    private volatile VideoSink videoSink;
    private AudioStream   audioStream;
    private AudioSink     audioSink;
    private ControlStream controlStream;
    private volatile Controller controller;
    private Thread        runner;
    private volatile boolean stopped;
    private boolean remoteEnvChecked;
    private boolean        stoppedNotified;
    private long           geometryVersion;
    private volatile Viewport pendingViewport;

    private static final class Viewport {
        final long version;
        final int x, y, w, h;

        Viewport(long version, int x, int y, int w, int h) {
            this.version = version;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    // Counted down when video or control ends; audio is optional and never
    // ends a session. Swapped for a fresh latch on each reconnect cycle.
    private volatile CountDownLatch endSignal = new CountDownLatch(1);

    public Session(Context ctx, Adb adb, Devices.Device target, Surface surface, Listener listener) {
        this.ctx = ctx;
        this.adb = adb;
        this.target = target;
        this.surface = surface;
        this.listener = listener;
    }

    public synchronized void start() {
        if (runner != null || stopped) return;
        LIVE.add(this);
        runner = new Thread(this::run, "session");
        runner.start();
    }

    // Current endpoint (may change after mDNS rediscovery of TLS connect port).
    // ---- recording straight from the mirror stream (video passthrough + audio) ----

    private StreamRecorder recorder;
    private volatile StreamRecorder.Listener recordListener;

    public void setRecordListener(StreamRecorder.Listener l) { recordListener = l; }

    private synchronized StreamRecorder recorder() {
        if (recorder == null) {
            Devices.Device t = target;
            String base = "scrcpy-" + safeName(t == null ? "device" : t.host);
            recorder = new StreamRecorder(
                    MediaMuxerSink.factory(ctx, base, Settings.recordDir(ctx)),
                    new StreamRecorder.Listener() {
                        @Override public void onSegment(String location) {
                            StreamRecorder.Listener l = recordListener;
                            if (l != null) l.onSegment(location);
                        }
                        @Override public void onError(Exception e) {
                            StreamRecorder.Listener l = recordListener;
                            if (l != null) l.onError(e);
                        }
                    });
        }
        return recorder;
    }

    private static String safeName(String s) {
        String r = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        return r.isEmpty() ? "device" : r;
    }

    /** Starts recording; the file opens on the next keyframe (one is requested now). */
    public void startRecording() throws java.io.IOException {
        recorder().start();
        recordingSince = System.currentTimeMillis();
        Controller c = controller;
        if (c != null) c.resetVideo();
    }

    /** Stops and finalizes; returns the written files (more than one after a rotation). */
    public java.util.List<String> stopRecording() {
        return recorder().stop();
    }

    public boolean isRecording() {
        StreamRecorder r = recorder;
        return r != null && r.isRecording();
    }

    private volatile long recordingSince;

    /** Wall clock (ms) when the current recording was started, or 0. */
    public long recordingSince() { return isRecording() ? recordingSince : 0; }

    // ---- live registry: lets device tools reach the mirror of the same device ----

    private static final java.util.Set<Session> LIVE =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** The running mirror session for host[:port], or null. */
    public static Session liveFor(String host, int port) {
        for (Session s : LIVE) {
            Devices.Device t = s.target;
            if (t != null && !s.stopped && t.host.equals(host) && (port <= 0 || t.port == port)) return s;
        }
        return null;
    }

    public Devices.Device getTarget() {
        return target;
    }

    public boolean stop() {
        LIVE.remove(this);
        StreamRecorder rec = recorder;
        if (rec != null && rec.isRecording()) {
            java.util.List<String> files = rec.stop();
            StreamRecorder.Listener l = recordListener;
            Log.i("session: recording finalized on stop (" + files.size() + " file(s))");
            if (l == null) for (String f : files) Log.i("session: saved " + f);
        }
        Thread r;
        boolean notify;
        synchronized (this) {
            if (!stopped) {
                stopped = true;
                Log.i("session: stop");
            }
            notify = !stoppedNotified;
            stoppedNotified = true;
            r = runner;
        }
        if (r != null && r != Thread.currentThread()) r.interrupt();
        adb.abort();
        tearDownInstalled();
        endSignal.countDown();
        boolean joined = true;
        if (r != null && r != Thread.currentThread()) {
            boolean interrupted = false;
            long deadline = monotonicMs() + STOP_JOIN_MS;
            while (r.isAlive()) {
                long remaining = deadline - monotonicMs();
                if (remaining <= 0) {
                    joined = false;
                    break;
                }
                try {
                    r.join(remaining);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!joined) Log.e("session: runner did not stop within %d ms", STOP_JOIN_MS);
        if (notify && listener != null) listener.onStopped();
        return joined;
    }

    // ---- input forwarding (Controller stays internal) ----

    public void onTouch(MotionEvent ev) {
        Controller c = controller;
        if (c != null) c.onTouch(ev);
    }

    public void onKey(KeyEvent ev) {
        Controller c = controller;
        if (c != null) c.onKey(ev);
    }

    public void onBack() {
        Controller c = controller;
        if (c != null) c.onBack();
    }

    // Wake/back via BACK_OR_SCREEN_ON (screen-on when off, Back when on).
    public void wakeOrBack() {
        onBack();
    }

    public void injectKeycode(int keycode) {
        Controller c = controller;
        if (c != null) c.injectKeycode(keycode);
    }

    public void syncClipboard() {
        Controller c = controller;
        if (c != null) c.syncLocalClipboard();
    }

    public void setViewport(long version, int x, int y, int w, int h) {
        Viewport viewport = new Viewport(version, x, y, w, h);
        pendingViewport = viewport;
        Controller c = controller;
        if (c != null) c.setViewport(version, x, y, w, h);
    }

    // Swap the surface the video pipeline draws to. The audio and control
    // sides keep streaming, so audio + clipboard still work while the
    // activity is backgrounded. Pass null to detach; pass a new Surface
    // (from a recreated SurfaceView) to resume rendering.
    public void swapSurface(Surface s) {
        Surface previous = surface;
        surface = s;
        VideoSink vk = videoSink;
        if (vk != null) vk.setOutputSurface(s);
        if (s != null && previous == null) {
            Controller c = controller;
            if (c != null) c.resetVideo();
        }
    }

    // ---- internals ----

    // Supervisor loop: connect, park until the live pipeline dies, and
    // reconnect if the death wasn't a user stop(). Runs on one thread for
    // the whole session lifetime.
    private void run() {
        int drops = 0;                     // consecutive short-lived connections
        while (true) {
            if (stopped) return;
            if (!connect()) return;        // gave up: onError + onStopped fired
            long upAt = monotonicMs();
            CountDownLatch latch;
            synchronized (this) {
                if (stopped) return;
                latch = endSignal;
            }
            try {
                latch.await();
            } catch (InterruptedException ie) {
                return;
            }
            long lived = monotonicMs() - upAt;

            // Decide teardown-and-retry under the monitor so a concurrent
            // stop() can't interleave: without this, onReconnecting()
            // could fire after stop()'s onStopped(), breaking the
            // listener contract.
            boolean exhausted;
            synchronized (this) {
                if (stopped) return;       // user stop()
                if (lived >= HEALTHY_MS) drops = 0;
                exhausted = drops >= RECONNECT_BACKOFF_MS.length;
                if (!exhausted) {
                    Log.i("session: link lost after %d ms - reconnecting (%d/%d)",
                            lived, drops + 1, RECONNECT_BACKOFF_MS.length);
                    tearDownInstalled();
                    endSignal = new CountDownLatch(1);
                    if (listener != null) listener.onReconnecting();
                }
            }
            if (exhausted) {
                Log.e("session: gave up after %d reconnects without a healthy link",
                        RECONNECT_BACKOFF_MS.length);
                giveUp(new IOException("link kept dropping; gave up after "
                        + RECONNECT_BACKOFF_MS.length + " reconnects"));
                return;
            }

            long delay = RECONNECT_BACKOFF_MS[drops++];
            try { Thread.sleep(delay); }
            catch (InterruptedException ie) { return; }
        }
    }

    // Run the bring-up retry ladder once. Returns true when a session is
    // live (read threads started); false if the budget was exhausted, in
    // which case onError() + onStopped() have already fired.
    private boolean connect() {
        Exception lastErr = null;
        for (int attempt = 0; attempt < BACKOFF_MS.length && !stopped; attempt++) {
            if (BACKOFF_MS[attempt] > 0) {
                Log.i("session: retry %d/%d after %d ms",
                        attempt + 1, BACKOFF_MS.length, BACKOFF_MS[attempt]);
                try { Thread.sleep(BACKOFF_MS[attempt]); }
                catch (InterruptedException ie) { return false; }
                if (stopped) return false;
            }
            try {
                bringUp();
                return true; // success; the read threads own the live session
            } catch (InterruptedException ie) {
                return false;
            } catch (ZhuoyitongGuard.BlockedException blocked) {
                Log.e("session: remote environment blocked (%s)", blocked.result.summary());
                tearDownInstalled();
                return giveUp(blocked);
            } catch (Exception t) {
                Log.w("session: bring-up attempt %d/%d failed: %s",
                        attempt + 1, BACKOFF_MS.length, t);
                lastErr = t;
                tearDownInstalled();
            }
        }
        if (stopped) return false;
        Log.e(lastErr, "session: gave up after %d attempts", BACKOFF_MS.length);
        return giveUp(lastErr);
    }

    // Terminal failure. Marks the session stopped, tears down, and fires
    // the final listener pair exactly once. Must not be called while
    // holding the monitor: the listener runs on the caller's thread.
    // Always returns false so callers can `return giveUp(err)`.
    private boolean giveUp(Throwable err) {
        synchronized (this) {
            if (stopped) return false;
            stopped = true;
            stoppedNotified = true;
        }
        adb.abort();
        tearDownInstalled();
        endSignal.countDown();
        if (listener != null) {
            listener.onError(err);
            listener.onStopped();
        }
        return false;
    }

    // Build everything into locals first. Then install + start under the
    // monitor - but only if stop() hasn't already fired, in which case
    // we tear down the locals we just built so nothing leaks.
    private void bringUp() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread deadline = new Thread(() -> {
            try {
                if (!finished.await(BRING_UP_DEADLINE_MS, TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    Log.e("session: bring-up exceeded %d ms", BRING_UP_DEADLINE_MS);
                    adb.abort();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "bring-up-deadline");
        deadline.setDaemon(true);
        deadline.start();

        Exception failure = null;
        try {
            bringUpAttempt();
        } catch (Exception e) {
            failure = e;
        } finally {
            finished.countDown();
            deadline.interrupt();
        }
        if (timedOut.get()) {
            SocketTimeoutException timeout = new SocketTimeoutException(
                    "session bring-up exceeded " + BRING_UP_DEADLINE_MS + " ms");
            if (failure != null) timeout.initCause(failure);
            throw timeout;
        }
        if (failure != null) throw failure;
    }

    private void bringUpAttempt() throws Exception {
        if (!remoteEnvChecked) {
            ZhuoyitongGuard.Result env = ZhuoyitongGuard.checkRemote(new AdbRemote(adb));
            if (env.detected()) {
                try { adb.disconnect(); } catch (Exception ignored) {}
                throw new ZhuoyitongGuard.BlockedException(env);
            }
            remoteEnvChecked = true;
        }

        Devices.Device endpoint = target;
        Log.i("session: connect %s:%d", endpoint.host, endpoint.port);
        adb.disconnect();
        try {
            adb.connect(endpoint.host, endpoint.port);
        } catch (Exception connectErr) {
            if (!shouldRediscover(endpoint, connectErr)) throw connectErr;
            Log.w("session: connect %s failed (%s) — rediscovering wireless-debug port",
                    endpoint, connectErr);
            AdbDiscovery.Endpoint ep = AdbDiscovery.discoverConnectForHost(
                    ctx, endpoint.host, REDISCOVER_MS);
            if (ep == null) {
                IOException none = new IOException(
                        ctx.getString(R.string.wireless_debug_port_none));
                none.addSuppressed(connectErr);
                throw none;
            }
            Devices.Device discovered = new Devices.Device(ep.host, ep.port);
            Log.i("session: rediscovered %s — connecting", discovered);
            adb.connect(discovered.host, discovered.port);
            try {
                Devices.upsertHost(ctx.getApplicationContext(), discovered);
            } catch (Exception saveErr) {
                Log.e(saveErr, "session: failed to save rediscovered endpoint");
            }
            endpoint = discovered;
            target = discovered;
        }
        Log.i("adb connect ok at %s", endpoint);
        if (endpoint != target) {
            target = endpoint;
            Log.i("session: using rediscovered endpoint %s", endpoint);
        }

        if (remoteSdk == 0) {
            remoteSdk = probeRemoteSdk();
            Log.i("session: controlled device sdk=%d", remoteSdk);
            if (listener != null) listener.onRemoteSdk(remoteSdk);
        }

        Server srv = null;
        ControlStream cs = null;
        Controller ctrl = null;
        AudioSink ak = null;
        AudioStream as = null;
        VideoSink vk = null;
        VideoStream vs = null;
        boolean installed = false;
        try {
            srv = new Server(ctx, adb);
            srv.setRemoteSdk(remoteSdk);
            srv.setOnWaitingForGrant(() -> {
                if (listener != null) listener.onElevateWaitingForGrant();
            });
            srv.setOnSecureOemRejected(() -> {
                if (listener != null) listener.onSecureOemRejected();
            });
            Server.Streams s = srv.bringUp();
            if (listener != null) listener.onElevateStatus(srv.elevateStatus());

            // Bind the end callbacks to THIS generation's latch, not to
            // the mutable field. Teardown joins the readers with a 1 s
            // timeout, so on a black-holed link a reader can outlive its
            // generation; reading the field at fire time would let it
            // count down the next generation's latch and fake an
            // immediate drop on a connection that was fine.
            final CountDownLatch mine = endSignal;
            Runnable ended = mine::countDown;

            cs = new ControlStream(s.controlIn, s.controlOut, ended);
            ctrl = new Controller(ctx, cs::send);
            cs.setInboundSink(ctrl);

            // Audio is optional. Failure to capture or play it must not tear
            // down video and control.
            ak = new AudioSink();
            StreamRecorder rec = recorder();
            ak.setPcmTap(rec::onPcm);
            as = new AudioStream(s.audioIn, new RecordingTaps.Audio(ak, rec));

            vk = new VideoSink(surface, ended, ctrl::resetVideo);
            Controller ctrlRef = ctrl;
            vs = new VideoStream(s.videoIn, new RecordingTaps.Video(vk, rec),
                    (w, h) -> reportConnected(ctrlRef, w, h));
            vs.setOnEnd(ended);

            // Start locals before publishing them. stop() either tears down
            // a previously installed generation or marks this generation for
            // rollback; it can never release objects that bringUp then starts.
            cs.start();
            as.start();
            vs.start();

            synchronized (this) {
                if (stopped) throw new IOException("session: stopped during bring-up");
                server        = srv;
                controlStream = cs;
                controller    = ctrl;
                audioSink     = ak;
                audioStream   = as;
                videoSink     = vk;
                videoStream   = vs;
                Viewport viewport = pendingViewport;
                if (viewport != null && viewport.w > 0 && viewport.h > 0) {
                    ctrl.setViewport(viewport.version, viewport.x, viewport.y,
                            viewport.w, viewport.h);
                }
                installed = true;
            }
        } finally {
            if (!installed) tearDownLocals(srv, cs, ctrl, ak, as, vk, vs);
        }
    }

    // Fires for every session-meta packet, not just the first. The server
    // sends a fresh one whenever the target rotates or resizes, and the
    // listener needs it every time: it carries the geometry the view is
    // sized to and the touch viewport is derived from. Reporting only the
    // first left a rotated target drawn into a view shaped for its old
    // orientation, with touches mapped through the stale rectangle.
    // Repeats are harmless - the listener's handling is idempotent.
    private synchronized void reportConnected(Controller ctrl, int w, int h) {
        if (stopped) return;
        long version = ++geometryVersion;
        ctrl.setTargetSize(version, w, h);
        if (listener != null) listener.onConnected(version, w, h);
    }


    // Wireless-debug TLS connect ports change whenever Wireless debugging is
    // toggled or the phone reboots. On connect failure, rediscover via mDNS.
    private volatile int remoteSdk;

    // getprop ro.build.version.sdk on the controlled device; 0 when unknown.
    private int probeRemoteSdk() {
        try {
            return Server.parseSdk(new AdbRemote(adb).shell("getprop ro.build.version.sdk"));
        } catch (Exception e) {
            Log.w("session: sdk probe failed: %s", e);
            return 0;
        }
    }

    private boolean shouldRediscover(Devices.Device endpoint, Exception err) {
        if (endpoint == null || err == null) return false;
        // USB targets have no network endpoint to rediscover.
        if (com.ghostpanter.adb.usb.UsbAdb.isUsbHost(endpoint.host)) return false;
        // Legacy `adb tcpip` endpoint (Android 9/10): fixed port, no
        // _adb-tls-connect mDNS service exists, so never rescan.
        if (endpoint.port == Adb.LEGACY_TCPIP_PORT) return false;
        if (String.valueOf(err.getMessage()).contains("adb auth not allowed")) return false;
        if (err instanceof ConnectException
                || err instanceof SocketTimeoutException
                || err instanceof NoRouteToHostException) {
            return true;
        }
        for (Throwable c = err; c != null; c = c.getCause()) {
            if (c instanceof ConnectException
                    || c instanceof SocketTimeoutException
                    || c instanceof NoRouteToHostException) {
                return true;
            }
            String m = c.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("connection refused")
                    || lower.contains("econnrefused")
                    || lower.contains("timed out")
                    || lower.contains("timeout")
                    || lower.contains("no route")
                    || lower.contains("enetunreach")
                    || lower.contains("network is unreachable")) {
                return true;
            }
        }
        return false;
    }

    private synchronized void tearDownInstalled() {
        tearDownLocals(server, controlStream, controller,
                       audioSink, audioStream, videoSink, videoStream);
        server = null;
        controlStream = null;
        controller = null;
        audioSink = null;
        audioStream = null;
        videoSink = null;
        videoStream = null;
        try {
            adb.disconnect();
        } catch (IOException e) {
            Log.w("session: adb disconnect failed: %s", e);
        }
    }

    private static void tearDownLocals(Server srv, ControlStream cs, Controller ctrl,
                                       AudioSink ak, AudioStream as,
                                       VideoSink vk, VideoStream vs) {
        // Closing the owning ADB streams first unblocks readers. Join them
        // before releasing their sinks so no callback can recreate resources
        // after teardown.
        if (srv != null) srv.close();
        if (vs != null) vs.stop();
        if (as != null) as.stop();
        if (cs != null) cs.stop();
        if (vk != null) vk.release();
        if (ak != null) ak.release();
        if (ctrl != null) ctrl.release();
    }

    private static long monotonicMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
}
