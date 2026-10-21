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

    /** The host's appearance setting: "system" / "light" / "dark", plus the OLED pure-black toggle. */
    public interface ThemeHook {
        String themeMode();
        boolean oledBlack();
    }

    private static volatile ContextWrapperHook localeHook;
    private static volatile ThemeHook themeHook;
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

    public static void setThemeHook(ThemeHook hook) {
        themeHook = hook;
    }

    /**
     * Night bits for the View screens on every API level (28+): an explicit light / dark choice
     * overrides the system uiMode through a configuration context, so values / values-night and
     * Theme.Material(.Light) follow the in-app setting even where UiModeManager's per-app night
     * mode (API 31+) does not exist.
     */
    static Context applyNight(Context base) {
        ThemeHook h = themeHook;
        if (h == null) return base;
        int night;
        String mode = h.themeMode();
        if ("dark".equals(mode)) night = android.content.res.Configuration.UI_MODE_NIGHT_YES;
        else if ("light".equals(mode)) night = android.content.res.Configuration.UI_MODE_NIGHT_NO;
        else return base;
        android.content.res.Configuration cur = base.getResources().getConfiguration();
        if ((cur.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == night) return base;
        android.content.res.Configuration c = new android.content.res.Configuration(cur);
        c.uiMode = (cur.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(c);
    }

    /** True when the OLED overlay applies: the toggle is on and [ctx] resolves to night. */
    static boolean oledFor(Context ctx) {
        ThemeHook h = themeHook;
        if (h == null || !h.oledBlack()) return false;
        int ui = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Called from each activity's onApplyThemeResource: layers the OLED colors over Theme.Scrcpy*. */
    static void applyOled(Context ctx, android.content.res.Resources.Theme theme, boolean fullscreen) {
        if (!oledFor(ctx)) return;
        theme.applyStyle(fullscreen ? R.style.ThemeOverlay_Scrcpy_Oled : R.style.ThemeOverlay_Scrcpy_Oled_Window, true);
    }

    public static void setDeviceActions(DeviceActions actions) {
        deviceActions = actions;
    }

    public static DeviceActions deviceActions() {
        return deviceActions;
    }

    static Context wrap(Context base) {
        Context out = base;
        ContextWrapperHook h = localeHook;
        if (h != null) {
            try {
                Context c = h.wrap(base);
                if (c != null) out = c;
            } catch (RuntimeException e) {
                Log.w("host: locale hook failed: %s", e);
            }
        }
        try {
            return applyNight(out);
        } catch (RuntimeException e) {
            Log.w("host: night override failed: %s", e);
            return out;
        }
    }
}
