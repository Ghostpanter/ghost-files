package com.ghostpanter.scrcpy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

// Full-screen mirror activity. Pulls target host/port from intent
// extras, starts a Sessions foreground service to keep the process
// alive during brief backgrounding, and owns the Session itself.
//
// Every build uses the same SurfaceView layout. Tests must exercise the
// renderer users receive, not a debug-only TextureView substitute.
//
// Surface lifetime is decoupled from session lifetime: when the surface
// goes away (rotation, background) we swap the session's video surface
// to null and let the wire keep draining. When the surface comes back
// we swap the new one in. Audio and control streams are unaffected.
//
// Session state vs activity lifetime: a fatal session error does NOT
// finish() the activity any more - instead the status bar transitions
// to DISCONNECTED and exposes a Reconnect button.
public final class Mirror extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ScrcpyHost.wrap(base));
    }

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";

    // Arbitrary request code for POST_NOTIFICATIONS - we don't react to
    // the result; the system caches the choice for next launch.
    private static final int RQ_POST_NOTIFICATIONS = 1001;

    private enum State { CONNECTING, CONNECTED, DISCONNECTED }

    private volatile Adb   adb;
    private Devices.Device target;
    private Session        session;
    private Surface        currentSurface;
    private volatile boolean destroyed;
    private long           sessionGeneration;
    private boolean        stoppingSession;
    private State          state = State.CONNECTING;
    private int            connectedW, connectedH;
    private long           connectedGeometryVersion;
    private int            gestureBottomInset;
    private int            systemTopInset;
    private boolean        immersiveOk;

    private SurfaceView surfaceView;

    // Always present (declared in both layouts).
    private View     root;
    private View     statusBar;
    private TextView statusText;
    private Button   reconnectBtn;
    private Button   disconnectBtn;

    // Minimal expandable actions (unlock + disconnect) + blind unlock keypad.
    private Button  mirrorMenuFab;
    private View    mirrorMenuPanel;
    private View    mirrorMenuScrim;
    private Button  unlockFab;
    private View    unlockPanel;
    private boolean mirrorMenuOpen;
    private boolean unlockPanelOpen;

    // Bound when effective tablet mode shows tools_pane (incl. force override).
    private TabletTools tabletTools;
    private View splitHandle;
    private Button uiModeCycleBtn;
    private Button splitSwapBtn;

    /** Fallback portrait aspect when video size is not yet known (9:16). */
    private static final float FALLBACK_VIDEO_ASPECT = 9f / 16f;
    /** Last applied tablet preview pane width (px); 0 when phone / unset. */
    private int splitPreviewWidthPx;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Restored task after process death can land here directly.
        if (ZhuoyitongGuard.blockIfLocal(this)) return;
        setContentView(R.layout.mirror);
        // Start CONNECTING with system bars visible so the status pill can
        // clear status/cutout. Immersive hide applies once CONNECTED.
        prepareEdgeToEdge();
        showSystemBarsForStatus();
        // Hold the source screen awake for as long as Mirror is in
        // front. Cleared automatically when the activity is destroyed.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String host = getIntent().getStringExtra(EXTRA_HOST);
        int port = getIntent().getIntExtra(EXTRA_PORT, -1);
        if (host == null || port <= 0 || port > 65535) {
            Log.e("mirror: bad extras host=%s port=%d", host, port);
            finish();
            return;
        }
        target = resolveTarget(host, port);
        if (target == null) {
            Log.e("mirror: refusing unsaved target %s:%d", host, port);
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        View video = findViewById(R.id.surface);
        if (!(video instanceof SurfaceView)) {
            Log.e("mirror: layout has no SurfaceView at R.id.surface");
            finish();
            return;
        }
        surfaceView = (SurfaceView) video;
        surfaceView.getHolder().addCallback(holderCallback);

        root = findViewById(R.id.root);
        // Rotation and insets change the container without touching the
        // video surface, so re-fit from here as well.
        root.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> applyLetterbox());
        View splitRoot = findViewById(R.id.split_root);
        if (splitRoot != null && splitRoot != root) {
            splitRoot.addOnLayoutChangeListener(
                    (view, l, t, r, b, ol, ot, or, ob) -> {
                        if (r - l != or - ol || b - t != ob - ot) {
                            applySplitPreviewSize();
                            applyLetterbox();
                        }
                    });
        }
        // Keep the target's bottom edge above the source's mandatory Home
        // gesture area. A target gesture can then start on the mirrored
        // handle instead of being claimed by the source system.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom;
            int top;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.mandatorySystemGestures()).bottom;
                Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
            } else {
                // API 28–29: approximate with system window insets.
                bottom = insets.getSystemWindowInsetBottom();
                top = insets.getSystemWindowInsetTop();
            }
            boolean changed = false;
            if (gestureBottomInset != bottom) {
                gestureBottomInset = bottom;
                changed = true;
            }
            if (systemTopInset != top) {
                systemTopInset = top;
                changed = true;
            }
            if (changed) {
                applyLetterbox();
                insetMirrorControls();
            }
            return insets;
        });
        root.requestApplyInsets();

        statusBar     = findViewById(R.id.status_bar);
        statusText    = findViewById(R.id.status_text);
        reconnectBtn  = findViewById(R.id.reconnect);
        disconnectBtn = findViewById(R.id.disconnect);
        insetStatusBar();
        reconnectBtn.setOnClickListener(view -> reconnect());
        if (disconnectBtn != null) {
            disconnectBtn.setOnClickListener(view -> disconnectAndLeave());
        }
        setupMirrorActions();
        updateStatusBar();

        // Effective tablet vs phone (Ui.isTablet honors Settings force override).
        // tools_pane is always in the unified layout; show/hide + split order apply here.
        Log.i("mirror: %s", Ui.deviceModeSummary(this));
        applyTabletChrome();
        setupUiModeControls();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackRequested);
        }

        requestNotificationsIfNeeded();
        startKeepalive();

        if (!Settings.hintBackShown(this)) {
            Toast.makeText(this, R.string.hint_back, Toast.LENGTH_LONG).show();
            Settings.setHintBackShown(this, true);
        }

        new Thread(() -> {
            try {
                Adb a = Adb.getInstance(getApplicationContext());
                runOnUiThread(() -> {
                    if (destroyed) return;
                    adb = a;
                    if (tabletTools != null) tabletTools.setAdb(a);
                    if (session == null && currentSurface != null) {
                        startSession(currentSurface);
                    }
                });
            } catch (Exception e) {
                Log.e(e, "mirror: adb init");
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Toast.makeText(this, getString(R.string.adb_init_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "adb-init").start();
    }

    // ---- surface lifecycle ----

    private final SurfaceHolder.Callback holderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i("mirror: surface created");
            attachSurface(holder.getSurface());
        }
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            Log.i("mirror: surface changed %dx%d", w, h);
            applyLetterbox();
        }
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i("mirror: surface destroyed");
            detachSurface();
        }
    };

    // ---- session driver ----

    private void attachSurface(Surface s) {
        if (session != null && currentSurface != null) session.swapSurface(null);
        currentSurface = s;
        if (session != null) {
            session.swapSurface(s);
            applyLetterbox();
            return;
        }
        if (adb == null) return; // adb-init thread will start the session
        if (stoppingSession) return; // stop worker starts the latest target
        if (state == State.DISCONNECTED) return; // wait for user to tap reconnect
        startSession(s);
        applyLetterbox();
    }

    // Size the video view to the target's aspect ratio inside the root
    // frame and centre it, so the black root shows through as letterbox
    // bars instead of the picture being stretched to the source's screen
    // shape. Also tells the session where the picture ended up, because
    // touches arrive in window coordinates and must be offset by the bars.
    //
    // On tablet split, first shrink the outer preview pane to the mirrored
    // aspect so leftover width goes to tools (no wasted side pillarboxes).
    //
    // No-ops until both the container and the target geometry are known;
    // every caller is a point where one of them may have just changed.
    private void applyLetterbox() {
        // Even before video size arrives, keep the tablet split sized to a
        // sensible fallback aspect so tools already have room.
        applySplitPreviewSize();

        View v = surfaceView;
        if (v == null || root == null || session == null) return;
        int cw = root.getWidth(), ch = root.getHeight();
        // Prefer the pending split width if layout has not applied yet.
        if (splitPreviewWidthPx > 0 && Ui.isTablet(this)) {
            cw = splitPreviewWidthPx;
        }
        // Full-window letterbox only when immersive hide succeeded; otherwise
        // keep the picture clear of the (visible) top system bar / cutout.
        int topInset = (state == State.CONNECTED && immersiveOk) ? 0 : systemTopInset;
        int availableH = ch - gestureBottomInset - topInset;
        int tw = connectedW, th = connectedH;
        if (cw <= 0 || availableH <= 0 || tw <= 0 || th <= 0) return;

        float scale = Math.min(cw / (float) tw, availableH / (float) th);
        int w = Math.min(cw, Math.max(1, Math.round(tw * scale)));
        int h = Math.min(availableH, Math.max(1, Math.round(th * scale)));
        int x = (cw - w) / 2;
        int y = topInset + (availableH - h) / 2;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.width != w || lp.height != h || lp.leftMargin != x
                || lp.topMargin != y || lp.gravity != (Gravity.TOP | Gravity.START)) {
            lp.width = w;
            lp.height = h;
            lp.leftMargin = x;
            lp.topMargin = y;
            lp.gravity = Gravity.TOP | Gravity.START;
            v.setLayoutParams(lp);   // re-layout re-enters here, then converges
            Log.i("mirror: letterbox %dx%d -> %dx%d in %dx%d top=%d gesture_bottom=%d immersive=%s",
                    tw, th, w, h, cw, ch, topInset, gestureBottomInset, immersiveOk);
        }
        session.setViewport(connectedGeometryVersion, x, y, w, h);
    }

    private void detachSurface() {
        if (session != null) session.swapSurface(null);
        currentSurface = null;
    }

    private void startSession(Surface s) {
        if (destroyed) return;
        // Re-read the row so forgetting a device invalidates stale tasks and
        // notifications before they open a new session.
        Devices.Device current = target;
        Devices.Device resolved = resolveTarget(current.host, current.port);
        if (resolved == null) {
            state = State.DISCONNECTED;
            updateStatusBar();
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            return;
        }
        target = resolved;
        state = State.CONNECTING;
        updateStatusBar();
        long generation = ++sessionGeneration;
        // Do not show Magisk-waiting UI until the server reports a pending grant.
        // Already-authorized devices skip the long wait and stay on「正在连接…」.
        session = new Session(this, adb, target, s, new Session.Listener() {
            @Override public void onConnected(long geometryVersion, int w, int h) {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.CONNECTED;
                    connectedGeometryVersion = geometryVersion;
                    connectedW = w; connectedH = h;
                    syncTargetFromSession();
                    updateStatusBar();
                    applyLetterbox();
                    if (session != null) session.syncClipboard();
                });
            }
            @Override public void onReconnecting() {
                Log.i("mirror: link lost, reconnecting");
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.CONNECTING;
                    updateStatusBar();
                });
            }
            @Override public void onElevateWaitingForGrant() {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    if (statusText != null && state == State.CONNECTING) {
                        statusText.setText(getString(R.string.elevate_waiting));
                    }
                    Toast.makeText(Mirror.this, R.string.elevate_waiting, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onElevateStatus(Server.ElevateStatus status) {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    showElevateStatus(status);
                });
            }
            @Override public void onRemoteSdk(int sdk) {
                if (sdk > 0 && sdk < 30) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(Mirror.this,
                                getString(R.string.android9_audio_unsupported, sdk),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }
            @Override public void onSecureOemRejected() {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    showElevateStatus(Server.ElevateStatus.SECURE_OEM_REJECTED);
                });
            }
            @Override public void onError(Throwable t) {
                runOnUiThread(() -> {
                    if (t instanceof ZhuoyitongGuard.BlockedException) {
                        ZhuoyitongGuard.showAndExit(Mirror.this, null);
                        return;
                    }
                    if (destroyed || generation != sessionGeneration) return;
                    Toast.makeText(Mirror.this, describe(t), Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onStopped() {
                Log.i("mirror: session stopped");
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.DISCONNECTED;
                    updateStatusBar();
                });
            }
        });
        session.start();
    }

    private void disconnectAndLeave() {
        Log.i("mirror: disconnect tapped — stop session and return to device list");
        // finish() → onDestroy stops the Session and Sessions service.
        finish();
    }

    private void showElevateStatus(Server.ElevateStatus status) {
        if (status == null || status == Server.ElevateStatus.DISABLED) return;
        int res;
        boolean offerLogs = false;
        switch (status) {
            case ROOT:
                res = R.string.elevate_root_ok;
                break;
            case DENIED:
                res = R.string.elevate_su_denied;
                offerLogs = true;
                break;
            case UNAVAILABLE:
                res = R.string.elevate_su_unavailable;
                offerLogs = true;
                break;
            case FALLBACK:
                res = R.string.elevate_fallback;
                offerLogs = true;
                break;
            case SECURE_OEM_REJECTED:
                res = R.string.elevate_secure_oem_rejected;
                offerLogs = true;
                break;
            default:
                return;
        }
        String msg = getString(res);
        // Long toast still truncates on many OEMs — also show a dialog with
        // an explicit「查看日志」action so the ring-buffer transcript is reachable.
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        if (statusText != null
                && status != Server.ElevateStatus.ROOT
                && (state == State.CONNECTING
                    || status == Server.ElevateStatus.SECURE_OEM_REJECTED)) {
            statusText.setText(msg);
            if (offerLogs) {
                statusText.setOnClickListener(v -> openLogViewer());
            }
        }
        if (offerLogs) {
            String dialogMsg = msg + "\n\n" + getString(R.string.elevate_open_logs_hint);
            if (status == Server.ElevateStatus.SECURE_OEM_REJECTED) {
                // Soft tip: exact Framework-only LSPosed recipe (toast truncates).
                dialogMsg = msg + "\n\n" + getString(R.string.secure_content_magisk)
                        + "\n\n" + getString(R.string.secure_content_lsposed)
                        + "\n\n" + getString(R.string.elevate_open_logs_hint);
            }
            new AlertDialog.Builder(this)
                    .setMessage(dialogMsg)
                    .setPositiveButton(R.string.elevate_fallback_view_logs,
                            (d, w) -> openLogViewer())
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void openLogViewer() {
        startActivity(new android.content.Intent(this, LogViewerActivity.class));
    }

    private void reconnect() {
        Log.i("mirror: reconnect tapped");
        state = State.CONNECTING;
        updateStatusBar();
        if (stoppingSession) return;
        stoppingSession = true;
        Session old = session;
        session = null;
        sessionGeneration++;
        connectedW = connectedH = 0;
        connectedGeometryVersion = 0;
        // Stop the old session off the UI thread (teardown closes
        // sockets and joins the server's log pump), THEN start the new
        // one. Sequencing matters: both sessions share the singleton
        // Adb, so the old teardown's disconnect must finish before the
        // new bring-up connects.
        new Thread(() -> {
            boolean stopped = old == null || old.stop();
            runOnUiThread(() -> {
                if (destroyed) return;
                stoppingSession = false;
                if (!stopped) {
                    state = State.DISCONNECTED;
                    updateStatusBar();
                    Toast.makeText(this, R.string.session_stop_timeout,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (session != null) return;  // another path already started one
                if (adb == null || currentSurface == null) return;
                startSession(currentSurface);
            });
        }, "session-stop").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (target == null) return;
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        if (host == null || port <= 0 || port > 65535) {
            Log.w("mirror: ignoring bad replacement target host=%s port=%d", host, port);
            return;
        }
        if (target.host.equals(host) && target.port == port) return;

        Devices.Device replacement = resolveTarget(host, port);
        if (replacement == null) {
            Log.w("mirror: refusing unsaved replacement target %s:%d", host, port);
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            return;
        }
        setIntent(intent);
        target = replacement;
        connectedW = connectedH = 0;
        connectedGeometryVersion = 0;
        startKeepalive();   // repoint the notification at the new target
        reconnect();
    }

    private void updateStatusBar() {
        if (statusText == null) return;
        String s;
        switch (state) {
            case CONNECTED:
                s = String.format(Locale.ROOT, "%s:%d  %dx%d  %s",
                        target.host, target.port, connectedW, connectedH,
                        Settings.videoCodec(this));
                break;
            case DISCONNECTED:
                s = String.format(Locale.ROOT, "%s:%d  %s",
                        target.host, target.port, getString(R.string.disconnected));
                break;
            default:
                s = String.format(Locale.ROOT, "%s:%d  %s",
                        target.host, target.port, getString(R.string.connecting));
        }
        statusText.setText(s);
        reconnectBtn.setVisibility(state == State.DISCONNECTED ? View.VISIBLE : View.GONE);

        // While CONNECTED the status pill hides so it does not cover the
        // preview; reconnect / connecting still need the pill. Disconnect
        // and unlock live in the minimal expandable menu FAB.
        if (statusBar != null) {
            statusBar.setVisibility(state == State.CONNECTED ? View.GONE : View.VISIBLE);
        }
        if (state != State.CONNECTED) {
            hideUnlockPanel();
        }
        refreshMirrorActionsVisibility();
        if (state == State.CONNECTED) {
            // Keep system bars visible beside the tools pane so tablet
            // controls stay reachable; phone full-screen still goes immersive.
            if (tabletTools != null) showSystemBarsForStatus();
            else immersive();
        } else {
            showSystemBarsForStatus();
        }
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // While the blind-unlock panel is open, swallow surface touches so
        // they are not injected as remote taps on a black lock/secure screen.
        if (unlockPanelOpen) return true;
        // Expanded actions menu uses its own scrim; if a touch still reaches
        // here, collapse rather than injecting a remote tap.
        if (mirrorMenuOpen) {
            collapseMirrorMenu();
            return true;
        }
        if (session != null) {
            session.onTouch(ev);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    // Back goes to the target; Back twice in quick succession leaves the
    // mirror.
    //
    // It used to be long-press-Back to reach the target and short-press
    // to do nothing. That stopped working twice over: gesture navigation
    // has no Back key to hold, and at targetSdk 35 and later predictive back is on
    // by default, so the framework routes Back through
    // OnBackInvokedDispatcher and onKeyDown/onKeyLongPress are never
    // called for it at all. The advertised feature was unreachable on
    // every current device.
    private static final long DOUBLE_BACK_MS = 600L;
    private long lastBackAtMs;

    private void onBackRequested() {
        if (unlockPanelOpen) {
            hideUnlockPanel();
            return;
        }
        if (mirrorMenuOpen) {
            collapseMirrorMenu();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackAtMs < DOUBLE_BACK_MS) {
            finish();
            return;
        }
        lastBackAtMs = now;
        if (session != null) session.onBack();
    }

    // Pre-33 devices (minSdk is 28) still deliver Back this way. API 33+
    // uses the OnBackInvokedDispatcher callback registered in onCreate;
    // lint does not follow that version split.
    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        onBackRequested();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (session != null && shouldForward(ev)) {
            session.onKey(ev);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // ---- lifecycle ----

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (tabletTools != null) tabletTools.onActivityResult(requestCode, resultCode, data);
    }

    // Legacy `adb tcpip` (Android 9/10): tell the user to tap "Allow" on the
    // controlled device while Adb.connect() waits for the RSA key approval.
    private final Adb.AuthPromptListener authPrompt = (h, p) -> runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, getString(R.string.auth_prompt_hint), Toast.LENGTH_LONG).show();
    });

    @Override
    protected void onResume() {
        super.onResume();
        Adb.setAuthPromptListener(authPrompt);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Adb.setAuthPromptListener(null);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        sessionGeneration++;
        ui.removeCallbacksAndMessages(null);
        if (tabletTools != null) {
            tabletTools.destroy();
            tabletTools = null;
        }
        currentSurface = null;
        Session s = session;
        session = null;
        if (s != null) {
            // Teardown blocks on socket closes and a thread join; keep
            // it off the UI thread.
            new Thread(() -> s.stop(), "session-stop").start();
        }
        stopService(new Intent(this, Sessions.class));
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (state == State.CONNECTED) {
                if (tabletTools != null) showSystemBarsForStatus();
                else immersive();
            }
            if (session != null) session.syncClipboard();
        }
    }

    // Android 13+ requires runtime grant for POST_NOTIFICATIONS. The
    // foreground service still starts without the grant, but granting it
    // keeps the active session visible in the notification drawer.
    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                RQ_POST_NOTIFICATIONS);
    }

    // Most of what reaches here has a null message - a bare IOException
    // from the socket, an SSLHandshakeException - and "session error:
    // null" is what the user was being shown for the commonest failure
    // there is.
    private String describe(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && !m.isEmpty()) return getString(R.string.session_error, m);
        }
        return getString(R.string.session_error,
                t == null ? getString(R.string.error_unknown) : t.getClass().getSimpleName());
    }

    // Missing rows fail closed. A stale notification, restored task, or
    // malformed internal intent must not create a session for an unsaved row.
    private Devices.Device resolveTarget(String host, int port) {
        // USB targets are never saved: they exist while attached.
        if (com.ghostpanter.adb.usb.UsbAdb.isUsbHost(host) && port == com.ghostpanter.adb.usb.UsbAdb.PORT) {
            return new Devices.Device(host, port);
        }
        try {
            Devices.Device exact = Devices.find(this, host, port);
            if (exact != null) return exact;
            // Port may have been rediscovered/upserted (e.g. reboot → new TLS port).
            for (Devices.Device d : Devices.load(this)) {
                if (d.host.equals(host)) return d;
            }
            return null;
        } catch (java.io.IOException e) {
            Log.e(e, "mirror: cannot read paired devices");
            return null;
        }
    }

    private void syncTargetFromSession() {
        if (session == null) return;
        Devices.Device ep = session.getTarget();
        if (ep == null) return;
        if (target != null && target.host.equals(ep.host) && target.port == ep.port) return;
        target = ep;
        startKeepalive();
    }

    // The foreground service exists only to keep this process alive while
    // mirroring. It carries the target so its notification can lead back
    // here rather than somewhere that would tear the session down.
    private void startKeepalive() {
        Intent i = new Intent(this, Sessions.class);
        i.putExtra(EXTRA_HOST, target.host);
        i.putExtra(EXTRA_PORT, target.port);
        startForegroundService(i);
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private void prepareEdgeToEdge() {
        // Without this the window stops at the cutout's safe area and the
        // system letterboxes it, which shows up as black bands down the
        // sides of what is supposed to be a full-screen mirror. Only the
        // overlay controls are moved around a display cutout.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            // ALWAYS is API 30; SHORT_EDGES exists from API 28.
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setAttributes(lp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private void immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                immersiveOk = true;
            } else {
                immersiveOk = false;
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            immersiveOk = true;
        }
        applyLetterbox();
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private void showSystemBarsForStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        immersiveOk = false;
        applyLetterbox();
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private void insetStatusBar() {
        int base = getResources().getDimensionPixelSize(R.dimen.space_sm);
        statusBar.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            // While CONNECTING/DISCONNECTED the pill must clear both the
            // status bar and any display cutout — cutout-only left it under
            // the system status area on many devices.
            int barL;
            int barT;
            int barR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                barL = bars.left;
                barT = bars.top;
                barR = bars.right;
            } else {
                barL = windowInsets.getSystemWindowInsetLeft();
                barT = windowInsets.getSystemWindowInsetTop();
                barR = windowInsets.getSystemWindowInsetRight();
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
            int left = base + barL;
            int top = base + barT;
            int right = base + barR;
            if (lp.leftMargin != left || lp.topMargin != top
                    || lp.rightMargin != right || lp.bottomMargin != base) {
                lp.setMargins(left, top, right, base);
                view.setLayoutParams(lp);
            }
            if (systemTopInset != barT) {
                systemTopInset = barT;
                applyLetterbox();
            }
            return windowInsets;
        });
        statusBar.requestApplyInsets();
    }

    // ---- tablet chrome / UI mode / split order ----

    /**
     * Show or hide the tools pane from {@link Ui#isTablet(android.content.Context)},
     * bind TabletTools when visible, and apply Preview|Tools vs Tools|Preview order.
     */
    private void applyTabletChrome() {
        View toolsPane = findViewById(R.id.tools_pane);
        splitHandle = findViewById(R.id.split_handle);
        boolean tabletMode = Ui.isTablet(this);

        if (tabletTools != null) {
            tabletTools.destroy();
            tabletTools = null;
        }

        if (toolsPane == null) {
            if (splitHandle != null) splitHandle.setVisibility(View.GONE);
            if (tabletMode) {
                Log.w("mirror: tablet mode but tools_pane missing; phone chrome only");
            } else {
                Log.i("mirror: phone mode — no tools sidebar");
            }
            return;
        }

        if (!tabletMode) {
            toolsPane.setVisibility(View.GONE);
            if (splitHandle != null) splitHandle.setVisibility(View.GONE);
            resetPreviewToFill(findViewById(R.id.root));
            splitPreviewWidthPx = 0;
            Log.i("mirror: phone mode — tools sidebar hidden (override=%s)", Ui.uiModeLabel(this));
            return;
        }

        applySplitPreviewSize();
        toolsPane.setVisibility(View.VISIBLE);
        if (splitHandle != null) splitHandle.setVisibility(View.VISIBLE);
        applySplitPaneOrder();
        setupSplitHandleDrag();

        tabletTools = new TabletTools(this);
        if (!tabletTools.bind()) {
            tabletTools = null;
            toolsPane.setVisibility(View.GONE);
            if (splitHandle != null) splitHandle.setVisibility(View.GONE);
            resetPreviewToFill(findViewById(R.id.root));
            splitPreviewWidthPx = 0;
            Log.w("mirror: tablet mode but tools bind failed; falling back to phone chrome");
            return;
        }
        if (adb != null) tabletTools.setAdb(adb);
        wireToolsPaneModeButtons();
        Log.i("mirror: tablet mode — split tools pane active (tools_on_left=%b)",
                Settings.toolsOnLeft(this));
    }

    /**
     * Phone mode: preview fills the window (weight 1). Tablet mode: size the
     * preview pane to the mirrored video aspect ratio and let tools_pane expand
     * into the leftover width (weight 1). Uses {@link #FALLBACK_VIDEO_ASPECT}
     * until {@code connectedW/H} arrive; re-runs on rotation / size change.
     */
    private void applySplitPreviewSize() {
        View toolsPane = findViewById(R.id.tools_pane);
        View preview = findViewById(R.id.root);
        ViewGroup split = findViewById(R.id.split_root);
        if (preview == null) return;

        if (!Ui.isTablet(this) || toolsPane == null
                || toolsPane.getVisibility() != View.VISIBLE || split == null) {
            resetPreviewToFill(preview);
            splitPreviewWidthPx = 0;
            return;
        }

        int splitW = split.getWidth();
        int splitH = split.getHeight();
        if (splitW <= 0 || splitH <= 0) {
            // Not measured yet; root/split layout listeners re-enter applyLetterbox.
            return;
        }

        int handleW = 0;
        if (splitHandle != null && splitHandle.getVisibility() == View.VISIBLE) {
            handleW = splitHandle.getWidth();
            if (handleW <= 0) {
                handleW = Math.round(12f * getResources().getDisplayMetrics().density);
            }
        }

        int minTools = getResources().getDimensionPixelSize(R.dimen.tools_pane_min_width);
        int comfortTools = getResources().getDimensionPixelSize(R.dimen.tools_pane_width);
        // Prefer the comfortable tools width as the clamp floor; never below min.
        int toolsFloor = Math.max(minTools, Math.min(comfortTools, splitW / 2));
        int maxPreviewW = Math.max(1, splitW - handleW - toolsFloor);

        float aspect;
        if (connectedW > 0 && connectedH > 0) {
            aspect = connectedW / (float) connectedH;
        } else {
            aspect = FALLBACK_VIDEO_ASPECT;
        }

        int desired = Math.max(1, Math.round(splitH * aspect));
        int previewW = Math.min(desired, maxPreviewW);
        float density = getResources().getDisplayMetrics().density;
        int minPreview = Math.round(120f * density);
        previewW = Math.max(minPreview, previewW);
        previewW = Math.min(previewW, maxPreviewW);

        ViewGroup.LayoutParams previewRaw = preview.getLayoutParams();
        ViewGroup.LayoutParams toolsRaw = toolsPane.getLayoutParams();
        if (!(previewRaw instanceof LinearLayout.LayoutParams)
                || !(toolsRaw instanceof LinearLayout.LayoutParams)) {
            Log.w("mirror: split children lack LinearLayout.LayoutParams; skip aspect sizing");
            return;
        }
        LinearLayout.LayoutParams previewLp = (LinearLayout.LayoutParams) previewRaw;
        LinearLayout.LayoutParams toolsLp = (LinearLayout.LayoutParams) toolsRaw;

        boolean changed = false;
        if (previewLp.width != previewW || previewLp.weight != 0f
                || previewLp.height != LinearLayout.LayoutParams.MATCH_PARENT) {
            previewLp.width = previewW;
            previewLp.weight = 0f;
            previewLp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            preview.setLayoutParams(previewLp);
            changed = true;
        }
        if (toolsLp.width != 0 || toolsLp.weight != 1f
                || toolsLp.height != LinearLayout.LayoutParams.MATCH_PARENT) {
            toolsLp.width = 0;
            toolsLp.weight = 1f;
            toolsLp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            toolsPane.setLayoutParams(toolsLp);
            changed = true;
        }
        splitPreviewWidthPx = previewW;
        if (changed) {
            Log.i("mirror: split preview %dpx (aspect=%.3f video=%dx%d split=%dx%d toolsFloor=%d)",
                    previewW, aspect, connectedW, connectedH, splitW, splitH, toolsFloor);
        }
    }

    /** Restore preview pane to fill remaining/full width (phone or tools hidden). */
    private void resetPreviewToFill(View preview) {
        ViewGroup.LayoutParams raw = preview.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
        if (lp.width != 0 || lp.weight != 1f
                || lp.height != LinearLayout.LayoutParams.MATCH_PARENT) {
            lp.width = 0;
            lp.weight = 1f;
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            preview.setLayoutParams(lp);
        }
    }

    /** Reorder split_root children to Preview|Handle|Tools or Tools|Handle|Preview. */
    private void applySplitPaneOrder() {
        ViewGroup split = findViewById(R.id.split_root);
        View preview = findViewById(R.id.root);
        View tools = findViewById(R.id.tools_pane);
        View handle = findViewById(R.id.split_handle);
        if (split == null || preview == null || tools == null) return;
        if (tools.getVisibility() != View.VISIBLE) return;

        boolean toolsLeft = Settings.toolsOnLeft(this);
        split.removeView(preview);
        if (handle != null) split.removeView(handle);
        split.removeView(tools);

        if (toolsLeft) {
            split.addView(tools);
            if (handle != null) split.addView(handle);
            split.addView(preview);
        } else {
            split.addView(preview);
            if (handle != null) split.addView(handle);
            split.addView(tools);
        }
        // root field still points at preview FrameLayout (id=root).
        root = preview;
    }

    private void setupSplitHandleDrag() {
        if (splitHandle == null) return;
        final float density = getResources().getDisplayMetrics().density;
        final float thresholdPx = 40f * density;
        splitHandle.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            boolean tracking;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        tracking = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        return tracking;
                    case MotionEvent.ACTION_UP:
                        if (tracking) {
                            float dx = event.getRawX() - downX;
                            if (Math.abs(dx) >= thresholdPx) {
                                // Significant horizontal drag toggles the only two layouts.
                                boolean toolsLeft = Settings.toolsOnLeft(Mirror.this);
                                // Drag left → prefer tools on left; drag right → tools on right.
                                boolean wantLeft = dx < 0;
                                if (wantLeft != toolsLeft) {
                                    swapSplitPanes(/*toast=*/ true);
                                }
                            } else if (Math.abs(dx) < 8f * density) {
                                // Tap on handle also swaps.
                                swapSplitPanes(/*toast=*/ true);
                            }
                        }
                        tracking = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        tracking = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
        splitHandle.setOnLongClickListener(v -> {
            swapSplitPanes(/*toast=*/ true);
            return true;
        });
    }

    private void swapSplitPanes(boolean toast) {
        boolean left = Settings.toggleToolsOnLeft(this);
        applySplitPaneOrder();
        applySplitPreviewSize();
        applyLetterbox();
        if (toast) {
            Toast.makeText(this,
                    left ? R.string.split_now_tools_preview : R.string.split_now_preview_tools,
                    Toast.LENGTH_SHORT).show();
        }
        Log.i("mirror: split order tools_on_left=%b", left);
    }

    private void setupUiModeControls() {
        uiModeCycleBtn = findViewById(R.id.ui_mode_cycle);
        splitSwapBtn = findViewById(R.id.split_swap);
        if (uiModeCycleBtn != null) {
            refreshUiModeCycleLabel();
            uiModeCycleBtn.setOnClickListener(v -> cycleUiModeAndRelaunch());
        }
        if (splitSwapBtn != null) {
            splitSwapBtn.setOnClickListener(v -> {
                collapseMirrorMenu();
                swapSplitPanes(/*toast=*/ true);
            });
            splitSwapBtn.setVisibility(Ui.isTablet(this) ? View.VISIBLE : View.GONE);
        }
        wireToolsPaneModeButtons();
    }

    private void wireToolsPaneModeButtons() {
        View toolsUiMode = findViewById(R.id.tools_ui_mode);
        if (toolsUiMode != null) {
            toolsUiMode.setOnClickListener(v -> cycleUiModeAndRelaunch());
        }
        View toolsSwap = findViewById(R.id.tools_split_swap);
        if (toolsSwap != null) {
            toolsSwap.setOnClickListener(v -> swapSplitPanes(/*toast=*/ true));
        }
    }

    private void refreshUiModeCycleLabel() {
        if (uiModeCycleBtn == null) return;
        int label;
        switch (Settings.uiMode(this)) {
            case Settings.UI_MODE_PHONE:
                label = R.string.ui_mode_phone;
                break;
            case Settings.UI_MODE_TABLET:
                label = R.string.ui_mode_tablet;
                break;
            default:
                label = R.string.ui_mode_auto;
        }
        uiModeCycleBtn.setText(getString(R.string.ui_mode_cycle) + " (" + getString(label) + ")");
    }

    @SuppressWarnings("deprecation")
    private void cycleUiModeAndRelaunch() {
        int mode = Settings.cycleUiMode(this);
        int toast;
        switch (mode) {
            case Settings.UI_MODE_PHONE:
                toast = R.string.ui_mode_now_phone;
                break;
            case Settings.UI_MODE_TABLET:
                toast = R.string.ui_mode_now_tablet;
                break;
            default:
                toast = R.string.ui_mode_now_auto;
        }
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        Log.i("mirror: ui_mode cycled to %s effective=%s",
                Ui.uiModeLabel(this), Ui.isTablet(this) ? "tablet" : "phone");
        // Recreate so phone/tablet chrome (tools visibility) re-binds cleanly.
        Intent intent = getIntent();
        finish();
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    // ---- minimal expandable mirror actions + blind unlock ----

    private void setupMirrorActions() {
        mirrorMenuFab = findViewById(R.id.mirror_menu_fab);
        mirrorMenuPanel = findViewById(R.id.mirror_menu_panel);
        mirrorMenuScrim = findViewById(R.id.mirror_menu_scrim);
        unlockFab = findViewById(R.id.unlock_fab);
        unlockPanel = findViewById(R.id.unlock_panel);
        if (mirrorMenuFab == null || mirrorMenuPanel == null) {
            Log.w("mirror: mirror actions views missing");
        } else {
            mirrorMenuFab.setOnClickListener(v -> toggleMirrorMenu());
            if (mirrorMenuScrim != null) {
                mirrorMenuScrim.setOnClickListener(v -> collapseMirrorMenu());
            }
        }
        if (unlockFab != null) {
            unlockFab.setOnClickListener(v -> {
                collapseMirrorMenu();
                showUnlockPanel();
            });
        }
        if (unlockPanel == null) {
            Log.w("mirror: unlock assist panel missing");
        } else {
            findViewById(R.id.unlock_close).setOnClickListener(v -> hideUnlockPanel());
            findViewById(R.id.unlock_wake).setOnClickListener(v -> {
                if (session != null) session.wakeOrBack();
            });
            findViewById(R.id.unlock_power).setOnClickListener(v ->
                    injectUnlockKey(KeyEvent.KEYCODE_POWER));

            int[] digitIds = {
                    R.id.unlock_key_0, R.id.unlock_key_1, R.id.unlock_key_2,
                    R.id.unlock_key_3, R.id.unlock_key_4, R.id.unlock_key_5,
                    R.id.unlock_key_6, R.id.unlock_key_7, R.id.unlock_key_8,
                    R.id.unlock_key_9,
            };
            int[] digitCodes = {
                    KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
                    KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
                    KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
                    KeyEvent.KEYCODE_9,
            };
            for (int i = 0; i < digitIds.length; i++) {
                final int code = digitCodes[i];
                findViewById(digitIds[i]).setOnClickListener(v -> injectUnlockKey(code));
            }
            findViewById(R.id.unlock_key_del).setOnClickListener(v ->
                    injectUnlockKey(KeyEvent.KEYCODE_DEL));
            findViewById(R.id.unlock_key_enter).setOnClickListener(v ->
                    injectUnlockKey(KeyEvent.KEYCODE_ENTER));
        }
        insetMirrorControls();
        refreshMirrorActionsVisibility();
    }

    private void toggleMirrorMenu() {
        if (mirrorMenuOpen) collapseMirrorMenu();
        else expandMirrorMenu();
    }

    private void expandMirrorMenu() {
        if (mirrorMenuPanel == null) return;
        mirrorMenuOpen = true;
        mirrorMenuPanel.setVisibility(View.VISIBLE);
        if (mirrorMenuScrim != null) mirrorMenuScrim.setVisibility(View.VISIBLE);
        if (mirrorMenuFab != null) {
            mirrorMenuFab.setVisibility(View.GONE);
            mirrorMenuFab.setContentDescription(getString(R.string.mirror_actions_collapse));
        }
        if (unlockFab != null) {
            unlockFab.setVisibility(state == State.CONNECTED ? View.VISIBLE : View.GONE);
        }
        if (disconnectBtn != null) disconnectBtn.setVisibility(View.VISIBLE);
        if (uiModeCycleBtn != null) uiModeCycleBtn.setVisibility(View.VISIBLE);
        if (splitSwapBtn != null) {
            splitSwapBtn.setVisibility(Ui.isTablet(this) ? View.VISIBLE : View.GONE);
        }
        insetMirrorControls();
    }

    private void collapseMirrorMenu() {
        mirrorMenuOpen = false;
        if (mirrorMenuPanel != null) mirrorMenuPanel.setVisibility(View.GONE);
        if (mirrorMenuScrim != null) mirrorMenuScrim.setVisibility(View.GONE);
        refreshMirrorActionsVisibility();
    }

    private void refreshMirrorActionsVisibility() {
        // FAB stays available in every session state so disconnect is one
        // tap away, but hides while the unlock keypad is open.
        boolean showFab = !unlockPanelOpen && !mirrorMenuOpen
                && (mirrorMenuFab != null);
        if (mirrorMenuFab != null) {
            mirrorMenuFab.setVisibility(showFab ? View.VISIBLE : View.GONE);
            mirrorMenuFab.setContentDescription(getString(R.string.mirror_actions_expand));
        }
        if (!mirrorMenuOpen && mirrorMenuPanel != null) {
            mirrorMenuPanel.setVisibility(View.GONE);
        }
        if (!mirrorMenuOpen && mirrorMenuScrim != null) {
            mirrorMenuScrim.setVisibility(View.GONE);
        }
        if (unlockFab != null && mirrorMenuOpen) {
            unlockFab.setVisibility(state == State.CONNECTED ? View.VISIBLE : View.GONE);
        }
    }

    private void injectUnlockKey(int keycode) {
        if (session == null) return;
        session.injectKeycode(keycode);
    }

    private void showUnlockPanel() {
        if (unlockPanel == null) return;
        unlockPanelOpen = true;
        unlockPanel.setVisibility(View.VISIBLE);
        collapseMirrorMenu();
        if (mirrorMenuFab != null) mirrorMenuFab.setVisibility(View.GONE);
        insetMirrorControls();
    }

    private void hideUnlockPanel() {
        unlockPanelOpen = false;
        if (unlockPanel != null) unlockPanel.setVisibility(View.GONE);
        refreshMirrorActionsVisibility();
    }

    private void insetMirrorControls() {
        int base = getResources().getDimensionPixelSize(R.dimen.space_lg);
        int bottom = base + gestureBottomInset;
        if (mirrorMenuFab != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mirrorMenuFab.getLayoutParams();
            if (lp.bottomMargin != bottom || lp.rightMargin != base) {
                lp.bottomMargin = bottom;
                lp.rightMargin = base;
                mirrorMenuFab.setLayoutParams(lp);
            }
        }
        if (mirrorMenuPanel != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mirrorMenuPanel.getLayoutParams();
            if (lp.bottomMargin != bottom || lp.rightMargin != base) {
                lp.bottomMargin = bottom;
                lp.rightMargin = base;
                mirrorMenuPanel.setLayoutParams(lp);
            }
        }
        if (unlockPanel != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) unlockPanel.getLayoutParams();
            int side = getResources().getDimensionPixelSize(R.dimen.space_md);
            // Cap width on tablets so the keypad stays thumb-reachable.
            int maxW = (int) (480f * getResources().getDisplayMetrics().density + 0.5f);
            int screenW = getResources().getDisplayMetrics().widthPixels;
            if (screenW > maxW + side * 2) {
                lp.width = maxW;
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.leftMargin = 0;
                lp.rightMargin = 0;
            } else {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.BOTTOM;
                lp.leftMargin = side;
                lp.rightMargin = side;
            }
            lp.bottomMargin = bottom;
            unlockPanel.setLayoutParams(lp);
        }
    }

    private static boolean shouldForward(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER) return true;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return true;
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return true;
        switch (code) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_INSERT:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_COMMA:
            case KeyEvent.KEYCODE_PERIOD:
            case KeyEvent.KEYCODE_SLASH:
            case KeyEvent.KEYCODE_BACKSLASH:
            case KeyEvent.KEYCODE_SEMICOLON:
            case KeyEvent.KEYCODE_APOSTROPHE:
            case KeyEvent.KEYCODE_GRAVE:
            case KeyEvent.KEYCODE_LEFT_BRACKET:
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_EQUALS:
                return true;
            default:
                return false;
        }
    }
}
