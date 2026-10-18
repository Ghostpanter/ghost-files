package com.ghostpanter.scrcpy;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

// Shizuku on THIS device (controller). Grants shell uid here only.
// Does NOT elevate the remote target's scrcpy-server — Android 12+
// FLAG_SECURE lock/gallery on the target still need Root (su) elevate
// on the target. We integrate Shizuku for permission UX, status, and a
// local UserService shell helper.
public final class ShizukuHelper {

    public enum Status { NOT_INSTALLED, DEAD, DENIED, GRANTED }

    public static final int RQ_SHIZUKU = 0x5a1;

    private static final AtomicBoolean listenerAdded = new AtomicBoolean();
    private static final AtomicReference<IShellUserService> service =
            new AtomicReference<>();

    private ShizukuHelper() {}

    public static void ensureListener() {
        if (!listenerAdded.compareAndSet(false, true)) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(() ->
                    Log.i("shizuku: binder received"));
            Shizuku.addBinderDeadListener(() -> {
                Log.i("shizuku: binder dead");
                service.set(null);
            });
        } catch (Throwable t) {
            Log.w("shizuku: listener setup failed: %s", t);
        }
    }

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static Status status(Context ctx) {
        ensureListener();
        if (!isInstalled(ctx)) return Status.NOT_INSTALLED;
        try {
            if (!Shizuku.pingBinder()) return Status.DEAD;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return Status.GRANTED;
            }
            return Status.DENIED;
        } catch (Throwable t) {
            Log.w("shizuku: status failed: %s", t);
            return Status.DEAD;
        }
    }

    public static boolean isReady(Context ctx) {
        return status(ctx) == Status.GRANTED;
    }

    public static String detail(Context ctx) {
        Status s = status(ctx);
        switch (s) {
            case NOT_INSTALLED: return "not installed";
            case DEAD: return "service not running";
            case DENIED: return "permission denied";
            case GRANTED:
                try {
                    return "granted uid=" + Shizuku.getUid()
                            + " ctx=" + Shizuku.getSELinuxContext();
                } catch (Throwable t) {
                    return "granted";
                }
            default: return s.name();
        }
    }

    public static void requestPermission() {
        ensureListener();
        try {
            if (Shizuku.isPreV11()) {
                Log.w("shizuku: pre-v11 not supported");
                return;
            }
            Shizuku.requestPermission(RQ_SHIZUKU);
        } catch (Throwable t) {
            Log.w("shizuku: requestPermission failed: %s", t);
        }
    }

    public static void addPermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try { Shizuku.addRequestPermissionResultListener(l); }
        catch (Throwable t) { Log.w("shizuku: addPermissionListener: %s", t); }
    }

    public static void removePermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try { Shizuku.removeRequestPermissionResultListener(l); }
        catch (Throwable ignored) {}
    }

    // Bind local UserService (runs as shell under Shizuku). Best-effort.
    public static void bindUserService(Context ctx) {
        if (!isReady(ctx)) return;
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(ctx, ShellUserService.class))
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(false)
                    .version(1);
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    IShellUserService svc = IShellUserService.Stub.asInterface(binder);
                    service.set(svc);
                    Log.i("shizuku: UserService connected");
                    try {
                        Log.i("shizuku: local id -> %s", svc.exec("id"));
                    } catch (Exception e) {
                        Log.w("shizuku: exec id failed: %s", e);
                    }
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    service.set(null);
                    Log.i("shizuku: UserService disconnected");
                }
            });
        } catch (Throwable t) {
            Log.w("shizuku: bindUserService failed: %s", t);
        }
    }

    public static String execLocal(String cmd) {
        IShellUserService svc = service.get();
        if (svc == null) return null;
        try { return svc.exec(cmd); }
        catch (Exception e) {
            Log.w("shizuku: execLocal failed: %s", e);
            return null;
        }
    }
}
