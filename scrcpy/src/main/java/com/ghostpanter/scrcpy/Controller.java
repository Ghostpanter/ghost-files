package com.ghostpanter.scrcpy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

// Encodes UI events into scrcpy ControlMessage byte arrays and pushes
// them at ControlStream. Also mirrors the remote clipboard locally and
// echoes local clipboard changes the other way.
//
// Forwards multi-pointer touch and AOSP keycodes. Mouse buttons and
// scroll wheels are not forwarded yet.
public final class Controller implements ControlStream.InboundSink {

    private final Consumer<byte[]>  sender;
    // Null when the user has turned clipboard sync off, which disables
    // both directions: nothing is read from this device and nothing the
    // target sends is written to it.
    private final ClipboardManager  clipboard;
    private final boolean           clipboardSync;
    // Held once so add/removePrimaryClipChangedListener see the same
    // listener reference. Method references generate fresh lambdas
    // each call site and the remove silently no-ops otherwise.
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            this::onLocalClipboardChanged;
    private final TouchGeometry geometry = new TouchGeometry();

    // The latest value applied from the target. Keeping the value, rather
    // than a one-shot boolean, cannot consume an unrelated user clipboard
    // change when Android delays or omits our own callback.
    private final AtomicReference<String> remoteClipboardText = new AtomicReference<>();
    // Dedup / size guard: some OEMs (and autosync loops) re-fire the same
    // multi-KB clip dozens of times per second, flooding the control socket
    // ("clipboard to target 23778 chars") and starving video.
    private static final int MAX_SYNC_CLIPBOARD_CHARS = 4096;
    private final AtomicReference<String> lastSentClipboardText = new AtomicReference<>();
    private volatile long lastSentClipboardAtMs;

    public Controller(Context ctx, Consumer<byte[]> sender) {
        this.sender = sender;
        this.clipboardSync = Settings.clipboardSync(ctx);
        Context app = ctx.getApplicationContext();
        this.clipboard = clipboardSync
                ? (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE)
                : null;
        if (clipboard != null) {
            clipboard.addPrimaryClipChangedListener(clipListener);
        }
    }

    public void release() {
        if (clipboard != null) {
            try { clipboard.removePrimaryClipChangedListener(clipListener); }
            catch (Exception ignored) {}
        }
    }

    public void setTargetSize(long version, int w, int h) {
        geometry.setTargetSize(version, w, h);
        Log.i("controller: target %dx%d", w, h);
    }

    // The rectangle the video occupies in the activity window, which is
    // the coordinate space MotionEvents arrive in.
    public void setViewport(long version, int x, int y, int w, int h) {
        geometry.setViewport(version, x, y, w, h);
    }

    // ---- inbound ----

    @Override
    public void onRemoteClipboard(String text) {
        if (!clipboardSync || clipboard == null) {
            Log.i("clipboard from target ignored: sync is off");
            return;
        }
        if (text.length() > MAX_SYNC_CLIPBOARD_CHARS) {
            Log.w("clipboard from target truncated: %d -> %d chars",
                    text.length(), MAX_SYNC_CLIPBOARD_CHARS);
            text = text.substring(0, MAX_SYNC_CLIPBOARD_CHARS);
        }
        Log.i("clipboard from target: %d chars", text.length());
        remoteClipboardText.set(text);
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("scrcpy-android", text));
        } catch (Exception e) {
            Log.w("clipboard set local failed: %s", e);
            remoteClipboardText.compareAndSet(text, null);
        }
    }

    private void onLocalClipboardChanged() {
        if (clipboard == null) return;
        ClipData data;
        try { data = clipboard.getPrimaryClip(); }
        catch (Exception e) { Log.w("clipboard get local failed: %s", e); return; }
        if (data == null || data.getItemCount() == 0) return;
        // The scrcpy control protocol carries UTF-8 text, not URI or Intent
        // clipboard items. Do not coerce URI items: that invokes an arbitrary
        // local ContentProvider and may materialize unbounded content before
        // our wire-size check. Explicit text fails closed.
        CharSequence cs = data.getItemAt(0).getText();
        if (cs == null) return;
        String text = cs.toString();
        if (text.equals(remoteClipboardText.get())) return;
        if (text.length() > MAX_SYNC_CLIPBOARD_CHARS) {
            Log.w("clipboard to target skipped: %d chars > %d limit",
                    text.length(), MAX_SYNC_CLIPBOARD_CHARS);
            return;
        }
        String last = lastSentClipboardText.get();
        long now = android.os.SystemClock.elapsedRealtime();
        if (text.equals(last) && (now - lastSentClipboardAtMs) < 2_000L) {
            return; // same payload within 2s — ignore OEM/autosync re-fires
        }
        remoteClipboardText.set(null);
        if (sendSetClipboard(text, false)) {
            lastSentClipboardText.set(text);
            lastSentClipboardAtMs = now;
            Log.i("clipboard to target: %d chars", cs.length());
        }
    }

    // Android 10+ denies clipboard reads while an app is not focused. A copy
    // made in another app therefore cannot be forwarded by the listener at
    // copy time. Mirror calls this after regaining focus so that ordinary
    // copy, return-to-mirror is reliable.
    public void syncLocalClipboard() {
        onLocalClipboardChanged();
    }

    // ---- outbound ----

    public void onTouch(MotionEvent ev) {
        TouchGeometry.Snapshot g = geometry.snapshot();
        if (g == null) return;

        // scrcpy's wire protocol uses ACTION_DOWN/UP/MOVE/CANCEL with a
        // pointerId per message. The server tracks which pointers are
        // currently down. So we translate Android's masked actions:
        //   ACTION_POINTER_DOWN[i] -> ACTION_DOWN  (this pointer joins)
        //   ACTION_POINTER_UP[i]   -> ACTION_UP    (this pointer leaves)
        //   ACTION_MOVE            -> ACTION_MOVE for every current pointer
        //   ACTION_CANCEL          -> ACTION_UP    for every current pointer
        int action = ev.getActionMasked();
        int idx    = ev.getActionIndex();
        int n      = ev.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                sendPointer(ev, 0, MotionEvent.ACTION_DOWN, g);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                sendPointer(ev, idx, MotionEvent.ACTION_DOWN, g);
                break;
            case MotionEvent.ACTION_UP:
                sendPointer(ev, 0, MotionEvent.ACTION_UP, g);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                sendPointer(ev, idx, MotionEvent.ACTION_UP, g);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < n; i++) sendPointer(ev, i, MotionEvent.ACTION_MOVE, g);
                break;
            case MotionEvent.ACTION_CANCEL:
                // Sent as UP, not CANCEL. The server releases a pointer
                // only on ACTION_UP (Controller.injectTouch calls
                // pointer.setUp(action == ACTION_UP)), so a forwarded
                // CANCEL leaves it down in PointersState for the rest of
                // the session and every later touch behaves as an extra
                // finger. Cancels are routine on the source device: an
                // edge swipe or the notification shade stealing the
                // gesture produces one.
                for (int i = 0; i < n; i++) sendPointer(ev, i, MotionEvent.ACTION_UP, g);
                break;
            default:
                return;
        }
    }

    private void sendPointer(MotionEvent ev, int index, int action,
                             TouchGeometry.Snapshot g) {
        long pointerId = ev.getPointerId(index);
        int tx = TouchMap.map((int) ev.getX(index), g.x, g.w, g.targetW);
        int ty = TouchMap.map((int) ev.getY(index), g.y, g.h, g.targetH);
        // getPressure is calibrated around 1.0 but may exceed it on some
        // digitizers; clamp instead of masking so hard presses don't wrap
        // around to a light touch.
        int pressure = (action == MotionEvent.ACTION_UP) ? 0
                : Math.max(0, Math.min((int)(ev.getPressure(index) * 0xffff), 0xffff));
        sender.accept(ControlMessages.touch(action, pointerId, tx, ty,
                g.targetW, g.targetH, pressure, /* actionButton */ 0, /* buttons */ 0));
    }

    public void onKey(KeyEvent ev) {
        if (geometry.snapshot() == null) return;
        int action = ev.getAction(); // ACTION_DOWN=0, ACTION_UP=1
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return;
        sendKeycode(action, ev.getKeyCode(), ev.getRepeatCount(), ev.getMetaState());
    }

    // Fire a tap of the target's Back. scrcpy server treats the
    // BACK_OR_SCREEN_ON message as Back when the target is on, screen-
    // on when it's off - useful for waking a locked target too.
    public void onBack() {
        sender.accept(ControlMessages.backOrScreenOn(ControlMessages.ACTION_DOWN));
        sender.accept(ControlMessages.backOrScreenOn(ControlMessages.ACTION_UP));
    }

    // Synthetic key tap for the blind-unlock keypad (PIN digits, DEL,
    // ENTER, POWER). Does not require geometry — key inject is
    // coordinate-free, so it still works while the picture is black.
    public void injectKeycode(int keycode) {
        sendKeycode(ControlMessages.ACTION_DOWN, keycode, /* repeat */ 0, /* meta */ 0);
        sendKeycode(ControlMessages.ACTION_UP, keycode, 0, 0);
    }

    public void resetVideo() {
        sender.accept(ControlMessages.resetVideo());
        Log.i("controller: reset video");
    }

    // ---- encoders (delegate to pure-java ControlMessages) ----

    private void sendKeycode(int action, int keycode, int repeat, int metaState) {
        sender.accept(ControlMessages.keycode(action, keycode, repeat, metaState));
    }

    private boolean sendSetClipboard(String text, boolean paste) {
        try {
            sender.accept(ControlMessages.setClipboard(/* sequence */ 0L, paste, text));
            return true;
        } catch (IllegalArgumentException e) {
            Log.w("clipboard not sent: %s", e.getMessage());
            return false;
        }
    }
}
