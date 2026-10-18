package com.ghostpanter.scrcpy;

import android.app.Application;
import android.content.Context;

// Glue between the embedded scrcpy activities and the host app (双栏文件管理器).
// Replaces scrcpy-android's own Application subclass: the host calls install()
// from its Application.onCreate, and may provide a locale wrapper so these
// View-based activities follow the in-app language setting.
public final class ScrcpyHost {

    public interface ContextWrapperHook {
        Context wrap(Context base);
    }

    /** Optional extra actions the host offers on a connected device (file browser, ADB tools). */
    public interface DeviceActions {
        /** Open the host's ADB tools page for host:port. */
        void openTools(Context ctx, String host, int port, String name);
        /** Open the host's dual-pane file manager on the device's /sdcard. */
        void browseFiles(Context ctx, String host, int port, String name);
    }

    private static volatile ContextWrapperHook localeHook;
    private static volatile DeviceActions deviceActions;
    private static volatile boolean installed;

    private ScrcpyHost() {}

    public static synchronized void install(Application app) {
        if (installed) return;
        installed = true;
        try {
            ZhuoyitongGuard.localBlocked = ZhuoyitongGuard.checkLocal(app).detected();
        } catch (Throwable t) {
            ZhuoyitongGuard.localBlocked = false;
        }
        if (ZhuoyitongGuard.localBlocked) {
            Log.w("host: unsupported environment — init skipped");
            return;
        }
        ShizukuHelper.ensureListener();
        Crashlog.install(app);
    }

    public static void setLocaleHook(ContextWrapperHook hook) {
        localeHook = hook;
    }

    public static void setDeviceActions(DeviceActions actions) {
        deviceActions = actions;
    }

    public static DeviceActions deviceActions() {
        return deviceActions;
    }

    static Context wrap(Context base) {
        ContextWrapperHook h = localeHook;
        if (h == null) return base;
        try {
            Context c = h.wrap(base);
            return c != null ? c : base;
        } catch (RuntimeException e) {
            Log.w("host: locale hook failed: %s", e);
            return base;
        }
    }
}
