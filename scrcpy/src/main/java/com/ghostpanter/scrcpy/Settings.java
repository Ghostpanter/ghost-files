package com.ghostpanter.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

// Thin wrapper around SharedPreferences for the small set of user
// choices the app exposes. No reactivity, no DataStore - the values
// are read once when a Session starts and applied to the scrcpy
// server command line.
public final class Settings {

    private static final String FILE = "scrcpy-android";

    public static final String VIDEO_CODEC    = "video_codec";
    public static final String AUDIO_CODEC    = "audio_codec";
    public static final String MAX_SIZE       = "max_size";        // px, long edge; 0 = no cap
    public static final String VIDEO_BIT_RATE = "video_bit_rate";  // bits/sec
    public static final String MAX_FPS        = "max_fps";         // 0 = uncapped
    public static final String LOW_LATENCY   = "low_latency";     // prefer low-latency encoder
    public static final String HINT_BACK_SHOWN = "hint_back_shown"; // first-run UI hint
    public static final String CLIPBOARD       = "clipboard";       // two-way clipboard sync
    public static final String ROOT_CAPTURE   = "root_capture";    // su-elevate server
    public static final String PREFER_SHIZUKU = "prefer_shizuku";  // use Shizuku when available
    /** UI mode override: auto / force phone / force tablet. */
    public static final String UI_MODE = "ui_mode";
    /** Split pane: tools on left (true) or preview|tools default (false). */
    public static final String TOOLS_ON_LEFT = "tools_on_left";

    public static final int UI_MODE_AUTO = 0;
    public static final int UI_MODE_PHONE = 1;
    public static final int UI_MODE_TABLET = 2;

    public static final String DEFAULT_VIDEO_CODEC    = "h264";
    public static final String DEFAULT_AUDIO_CODEC    = "opus";
    // Default = "流畅" preset: 720p @ 6 Mbit/s @ 60 fps H.264 with
    // low-latency keyframes. Smooth on typical Wi-Fi / VPN uplinks;
    // use the "画质" preset (or raise knobs) on a strong LAN.
    public static final int    DEFAULT_MAX_SIZE       = 720;
    public static final int    DEFAULT_VIDEO_BIT_RATE = 6_000_000;
    public static final int    DEFAULT_MAX_FPS        = 60;
    public static final boolean DEFAULT_LOW_LATENCY  = true;
    // On by default: it is a headline feature and the target is one the
    // user deliberately paired with. It is a setting because that trust
    // is not absolute - a compromised target
    // can read whatever is copied on this device and write anything it
    // likes back - and because there was previously no way to decline.
    public static final boolean DEFAULT_CLIPBOARD     = true;
    // Default OFF: opt-in only. Prefer Magisk Zygisk ih8SecureLock-scrcpy for
    // FLAG_SECURE; do not auto-force Magisk secure-elevate on connect.
    public static final boolean DEFAULT_ROOT_CAPTURE  = false;
    public static final boolean DEFAULT_PREFER_SHIZUKU = true;
    public static final int DEFAULT_UI_MODE = UI_MODE_AUTO;
    public static final boolean DEFAULT_TOOLS_ON_LEFT = false;

    private Settings() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String videoCodec(Context ctx) {
        String value = string(ctx, VIDEO_CODEC, DEFAULT_VIDEO_CODEC);
        return "h264".equals(value) || "h265".equals(value) || "av1".equals(value)
                ? value : DEFAULT_VIDEO_CODEC;
    }

    public static String audioCodec(Context ctx) {
        String value = string(ctx, AUDIO_CODEC, DEFAULT_AUDIO_CODEC);
        return "opus".equals(value) || "raw".equals(value)
                ? value : DEFAULT_AUDIO_CODEC;
    }

    public static int maxSize(Context ctx) {
        int value = integer(ctx, MAX_SIZE, DEFAULT_MAX_SIZE);
        switch (value) {
            case 0: case 480: case 720: case 1080: case 1440: case 2160:
                return value;
            default:
                return DEFAULT_MAX_SIZE;
        }
    }

    public static int videoBitRate(Context ctx) {
        int value = integer(ctx, VIDEO_BIT_RATE, DEFAULT_VIDEO_BIT_RATE);
        switch (value) {
            case 1_000_000: case 2_000_000: case 4_000_000:
            case 6_000_000: case 8_000_000: case 16_000_000:
                return value;
            default:
                return DEFAULT_VIDEO_BIT_RATE;
        }
    }

    public static void setVideoCodec(Context ctx, String v) {
        if (!"h264".equals(v) && !"h265".equals(v) && !"av1".equals(v)) {
            throw new IllegalArgumentException("invalid video codec");
        }
        prefs(ctx).edit().putString(VIDEO_CODEC, v).apply();
    }

    public static void setAudioCodec(Context ctx, String a) {
        if (!"opus".equals(a) && !"raw".equals(a)) {
            throw new IllegalArgumentException("invalid audio codec");
        }
        prefs(ctx).edit().putString(AUDIO_CODEC, a).apply();
    }

    public static void setMaxSize(Context ctx, int v) {
        if (v != 0 && v != 480 && v != 720 && v != 1080 && v != 1440 && v != 2160) {
            throw new IllegalArgumentException("invalid maximum size");
        }
        prefs(ctx).edit().putInt(MAX_SIZE, v).apply();
    }

    public static void setVideoBitRate(Context ctx, int v) {
        if (v != 1_000_000 && v != 2_000_000 && v != 4_000_000
                && v != 6_000_000 && v != 8_000_000 && v != 16_000_000) {
            throw new IllegalArgumentException("invalid video bit rate");
        }
        prefs(ctx).edit().putInt(VIDEO_BIT_RATE, v).apply();
    }


    public static int maxFps(Context ctx) {
        int value = integer(ctx, MAX_FPS, DEFAULT_MAX_FPS);
        switch (value) {
            case 0: case 15: case 30: case 60: case 120:
                return value;
            default:
                return DEFAULT_MAX_FPS;
        }
    }

    public static void setMaxFps(Context ctx, int v) {
        if (v != 0 && v != 15 && v != 30 && v != 60 && v != 120) {
            throw new IllegalArgumentException("invalid max fps");
        }
        prefs(ctx).edit().putInt(MAX_FPS, v).apply();
    }

    public static boolean lowLatency(Context ctx) {
        return bool(ctx, LOW_LATENCY, DEFAULT_LOW_LATENCY);
    }

    public static void setLowLatency(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(LOW_LATENCY, v).apply();
    }

    public static boolean hintBackShown(Context ctx) {
        return bool(ctx, HINT_BACK_SHOWN, false);
    }

    public static void setHintBackShown(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(HINT_BACK_SHOWN, v).apply();
    }

    public static boolean clipboardSync(Context ctx) {
        return bool(ctx, CLIPBOARD, DEFAULT_CLIPBOARD);
    }

    public static void setClipboardSync(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(CLIPBOARD, v).apply();
    }

    public static boolean rootCaptureSecure(Context ctx) {
        return bool(ctx, ROOT_CAPTURE, DEFAULT_ROOT_CAPTURE);
    }

    public static void setRootCaptureSecure(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(ROOT_CAPTURE, v).apply();
    }

    public static boolean preferShizuku(Context ctx) {
        return bool(ctx, PREFER_SHIZUKU, DEFAULT_PREFER_SHIZUKU);
    }

    public static void setPreferShizuku(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(PREFER_SHIZUKU, v).apply();
    }

    /** @return {@link #UI_MODE_AUTO}, {@link #UI_MODE_PHONE}, or {@link #UI_MODE_TABLET}. */
    public static int uiMode(Context ctx) {
        int value = integer(ctx, UI_MODE, DEFAULT_UI_MODE);
        switch (value) {
            case UI_MODE_AUTO:
            case UI_MODE_PHONE:
            case UI_MODE_TABLET:
                return value;
            default:
                return DEFAULT_UI_MODE;
        }
    }

    public static void setUiMode(Context ctx, int mode) {
        if (mode != UI_MODE_AUTO && mode != UI_MODE_PHONE && mode != UI_MODE_TABLET) {
            throw new IllegalArgumentException("invalid ui mode");
        }
        prefs(ctx).edit().putInt(UI_MODE, mode).apply();
    }

    /** Cycle Auto → Phone → Tablet → Auto. Returns the new mode. */
    public static int cycleUiMode(Context ctx) {
        int next = (uiMode(ctx) + 1) % 3;
        setUiMode(ctx, next);
        return next;
    }

    /** Tablet split: tools pane on the left when true; preview|tools when false. */
    public static boolean toolsOnLeft(Context ctx) {
        return bool(ctx, TOOLS_ON_LEFT, DEFAULT_TOOLS_ON_LEFT);
    }

    public static void setToolsOnLeft(Context ctx, boolean left) {
        prefs(ctx).edit().putBoolean(TOOLS_ON_LEFT, left).apply();
    }

    public static boolean toggleToolsOnLeft(Context ctx) {
        boolean next = !toolsOnLeft(ctx);
        setToolsOnLeft(ctx, next);
        return next;
    }

    private static String string(Context ctx, String key, String fallback) {
        try {
            String value = prefs(ctx).getString(key, fallback);
            return value == null ? fallback : value;
        } catch (ClassCastException e) {
            Log.w("settings: %s has the wrong type", key);
            return fallback;
        }
    }

    private static int integer(Context ctx, String key, int fallback) {
        try {
            return prefs(ctx).getInt(key, fallback);
        } catch (ClassCastException e) {
            Log.w("settings: %s has the wrong type", key);
            return fallback;
        }
    }

    private static boolean bool(Context ctx, String key, boolean fallback) {
        try {
            return prefs(ctx).getBoolean(key, fallback);
        } catch (ClassCastException e) {
            Log.w("settings: %s has the wrong type", key);
            return fallback;
        }
    }
}
