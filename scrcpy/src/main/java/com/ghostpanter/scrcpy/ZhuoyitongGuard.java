package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Intentional block: refuse to run in, or connect to, 卓易通 (Zhuoyitong) —
 * the Android compatibility container on HarmonyOS NEXT / HarmonyOS PC.
 * Regular Android, including Huawei/Honor EMUI and HarmonyOS 2–4 (which are
 * Android-based and run a Linux kernel), must keep working.
 *
 * <p>One evaluator ({@link #evaluate}) is fed either local data (this app's
 * own process, {@link #checkLocal}) or the output of {@link #REMOTE_PROBE}
 * run over adb on the controlled device ({@link #checkRemote}).
 *
 * <h3>Signals</h3>
 * STRONG — any one blocks:
 * <ul>
 *   <li>K1 kernel: {@code /proc/version}, {@code uname -a} or {@code os.version}
 *       contains "HongMeng". Zhuoyitong shares the HongMeng kernel of
 *       HarmonyOS NEXT (Device Info HW shows kernel "HongMeng"); EMUI and
 *       HarmonyOS 2–4 run a Linux kernel.</li>
 *   <li>C1 cgroup: {@code /proc/self/cgroup} / {@code /proc/1/cgroup} has the
 *       token "isulad", "zhuoyi" or "anco" (container is started by iSulad;
 *       the image is anco_hmos.img).</li>
 *   <li>M1 mounts: {@code /proc/self/mountinfo} mentions "anco_hmos", "isulad",
 *       "zhuoyi" or an "anco" path token.</li>
 *   <li>I1 installer: this APK was installed by {@code com.zhuoyi.appstore.lite}
 *       (the Zhuoyitong store / installer inside the container). Local only.</li>
 *   <li>P1 props: any system property key or value contains "zhuoyi" or "isulad".</li>
 *   <li>B1 build: a Build field / build prop has the token "zhuoyi".</li>
 * </ul>
 * WEAK — two or more distinct weak signals block:
 * <ul>
 *   <li>C2 cgroup has an "lxc" token (alone this also fits Waydroid/LXC).</li>
 *   <li>P2 a property key has the token "anco".</li>
 *   <li>B2 Build fields / ro.build.characteristics have a token "droi", "zyt",
 *       "anco" or "easyabroad" (Droi also ships FreemeOS, so weak only).</li>
 *   <li>G1 package {@code com.zhuoyi.appstore.lite} / {@code com.droi.*} is
 *       installed (the Zhuoyitong APK can be side-loaded on plain Android).</li>
 * </ul>
 * Tokens are split on non-alphanumerics so e.g. "android" never matches "droi".
 */
public final class ZhuoyitongGuard {

    public static final String ZYT_STORE_PKG = "com.zhuoyi.appstore.lite";

    private static final long GETPROP_TIMEOUT_MS = 1_500L;
    private static final int MAX_READ = 256 * 1024;

    /** Shell probe for the controlled device; sections are split on the @@ markers. */
    public static final String REMOTE_PROBE =
            "echo '@@K'; cat /proc/version 2>/dev/null; uname -a 2>/dev/null;"
            + " echo '@@C'; cat /proc/self/cgroup 2>/dev/null; cat /proc/1/cgroup 2>/dev/null;"
            + " echo '@@M'; grep -iE 'anco|isulad|zhuoyi' /proc/self/mountinfo 2>/dev/null | head -n 20;"
            + " echo '@@P'; getprop 2>/dev/null;"
            + " echo '@@G'; pm list packages 2>/dev/null | grep -iE 'zhuoyi|droi';"
            + " echo '@@E'";

    /** Thrown from Session bring-up when the controlled device is Zhuoyitong. */
    public static final class BlockedException extends IOException {
        public final Result result;

        public BlockedException(Result result) {
            super("controlled device is Zhuoyitong: " + result.summary());
            this.result = result;
        }
    }

    public static final class Result {
        public final List<String> strong = new ArrayList<>();
        public final List<String> weak = new ArrayList<>();

        public boolean detected() {
            if (!strong.isEmpty()) return true;
            Set<String> kinds = new HashSet<>();
            for (String w : weak) kinds.add(w.substring(0, Math.min(2, w.length())));
            return kinds.size() >= 2;
        }

        public String summary() {
            return "strong=" + strong + " weak=" + weak;
        }
    }

    /** Raw inputs; any may be null/empty when unavailable. */
    public static final class Inputs {
        String kernel = "";
        String cgroup = "";
        String mountinfo = "";
        String props = "";       // `getprop` dump: [key]: [value]
        String build = "";       // Build fields, one per line (local only)
        String installer = null; // local only
        String packages = "";    // matching package names, any format
    }

    private static volatile Result localCache;

    private ZhuoyitongGuard() {}

    // ---------------------------------------------------------------- local

    /** Cached; safe on any thread (spawns getprop with a short timeout). */
    public static Result checkLocal(Context ctx) {
        Result cached = localCache;
        if (cached != null) return cached;
        synchronized (ZhuoyitongGuard.class) {
            if (localCache != null) return localCache;
            Result r;
            try {
                Inputs in = new Inputs();
                in.kernel = readFile("/proc/version") + "\n" + nz(System.getProperty("os.version"));
                in.cgroup = readFile("/proc/self/cgroup") + "\n" + readFile("/proc/1/cgroup");
                in.mountinfo = readFile("/proc/self/mountinfo");
                in.props = runGetprop();
                in.build = localBuild();
                in.installer = installerOf(ctx);
                in.packages = localPackages(ctx);
                r = evaluate(in);
            } catch (Throwable t) {
                Log.w("zyt: local check failed, treating as normal Android: %s", t);
                r = new Result();
            }
            if (r.detected()) Log.w("zyt: LOCAL Zhuoyitong detected %s", r.summary());
            else Log.i("zyt: local env ok %s", r.summary());
            localCache = r;
            return r;
        }
    }

    /**
     * Launcher/activity gate. Returns true (and starts the exit flow) when the
     * controller itself runs inside Zhuoyitong; the caller must return at once.
     */
    /** Cached local-process result (set from Application.attachBaseContext). */
    public static volatile boolean localBlocked;

    /** True when this process already decided to refuse to run. */
    public static boolean isLocalBlocked() { return localBlocked; }

    /**
     * Silent exit when this process is inside the blocked environment.
     * No dialog, toast, or user-visible message — finishAffinity + kill.
     */
    public static boolean blockIfLocal(Activity a) {
        if (!localBlocked) {
            try { localBlocked = checkLocal(a.getApplicationContext()).detected(); }
            catch (Throwable t) { localBlocked = false; }
        }
        if (!localBlocked) return false;
        exitNow(a);
        return true;
    }

    // --------------------------------------------------------------- remote

    /** Probe the controlled device over the live adb link. Never throws. */
    public static Result checkRemote(AdbRemote remote) {
        Result r;
        try {
            String out = remote.shell(REMOTE_PROBE, false);
            Inputs in = new Inputs();
            in.kernel = section(out, "@@K", "@@C");
            in.cgroup = section(out, "@@C", "@@M");
            in.mountinfo = section(out, "@@M", "@@P");
            in.props = section(out, "@@P", "@@G");
            in.packages = section(out, "@@G", "@@E");
            r = evaluate(in);
        } catch (Throwable t) {
            Log.w("zyt: remote probe failed, allowing connection: %s", t);
            return new Result();
        }
        if (r.detected()) Log.w("zyt: REMOTE Zhuoyitong detected %s", r.summary());
        else Log.i("zyt: remote env ok %s", r.summary());
        return r;
    }

    // ------------------------------------------------------------ evaluator

    static Result evaluate(Inputs in) {
        Result r = new Result();

        String kernel = lower(in.kernel);
        if (kernel.contains("hongmeng")) r.strong.add("K1 kernel=" + firstLineWith(in.kernel, "hongmeng"));

        Set<String> cg = tokens(in.cgroup);
        for (String t : new String[]{"isulad", "zhuoyi", "anco"}) {
            if (cg.contains(t)) r.strong.add("C1 cgroup:" + t);
        }
        if (lower(in.cgroup).contains("zhuoyi") && !cg.contains("zhuoyi")) r.strong.add("C1 cgroup:*zhuoyi*");
        if (cg.contains("lxc")) r.weak.add("C2 cgroup:lxc");

        String mi = lower(in.mountinfo);
        Set<String> miTok = tokens(in.mountinfo);
        if (mi.contains("anco_hmos")) r.strong.add("M1 mount:anco_hmos");
        if (mi.contains("isulad")) r.strong.add("M1 mount:isulad");
        if (mi.contains("zhuoyi")) r.strong.add("M1 mount:zhuoyi");
        if (miTok.contains("anco")) r.strong.add("M1 mount:anco");

        if (ZYT_STORE_PKG.equals(in.installer)) r.strong.add("I1 installer=" + in.installer);

        boolean p2 = false;
        String chars = "";
        for (String line : nz(in.props).split("\n")) {
            int a = line.indexOf('['), b = line.indexOf(']');
            if (a < 0 || b <= a) continue;
            String key = line.substring(a + 1, b);
            int c = line.indexOf('[', b), d = line.lastIndexOf(']');
            String val = (c > 0 && d > c) ? line.substring(c + 1, d) : "";
            String lk = lower(key), lv = lower(val);
            if (lk.contains("zhuoyi") || lv.contains("zhuoyi")
                    || lk.contains("isulad") || lv.contains("isulad")) {
                if (r.strong.size() < 8) r.strong.add("P1 prop " + key + "=" + clip(val));
            }
            if (!p2 && tokens(key).contains("anco")) {
                r.weak.add("P2 propkey " + key);
                p2 = true;
            }
            if (key.equals("ro.build.characteristics")
                    || key.equals("ro.build.display.id")
                    || key.equals("ro.build.fingerprint")
                    || key.equals("ro.build.host")
                    || key.equals("ro.build.user")
                    || key.equals("ro.build.flavor")
                    || key.equals("ro.product.name")
                    || key.equals("ro.product.device")) {
                chars += "\n" + val;
            }
        }

        String build = nz(in.build) + chars;
        Set<String> bt = tokens(build);
        if (bt.contains("zhuoyi")) r.strong.add("B1 build:zhuoyi");
        for (String t : new String[]{"droi", "zyt", "anco", "easyabroad"}) {
            if (bt.contains(t)) {
                r.weak.add("B2 build:" + t);
                break;
            }
        }

        String pk = lower(in.packages);
        if (pk.contains(ZYT_STORE_PKG) || pk.contains("com.droi.")) {
            r.weak.add("G1 pkg:" + (pk.contains(ZYT_STORE_PKG) ? ZYT_STORE_PKG : "com.droi.*"));
        }
        return r;
    }

    // ------------------------------------------------------------- exit UI

    /** Silent finishAffinity + process kill (no dialog / toast / message). */
    public static void showAndExit(Activity a, String ignoredMessage) {
        exitNow(a);
    }

    private static volatile boolean exiting;

    private static void exitNow(Activity a) {
        if (exiting) return;
        exiting = true;
        Log.w("env: exit");
        try {
            if (a != null) a.finishAffinity();
        } catch (Throwable ignored) {}
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Process.killProcess(Process.myPid());
            System.exit(0);
        }, 200L);
    }

    // ------------------------------------------------------------- helpers

    private static String localBuild() {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{Build.DISPLAY, Build.FINGERPRINT, Build.HOST, Build.USER,
                Build.TAGS, Build.PRODUCT, Build.DEVICE, Build.BOARD, Build.HARDWARE,
                Build.BRAND, Build.MANUFACTURER, Build.MODEL, Build.ID, Build.TYPE}) {
            sb.append(nz(s)).append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private static String installerOf(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return pm.getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
            }
            return pm.getInstallerPackageName(ctx.getPackageName());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String localPackages(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(ZYT_STORE_PKG, 0);
            return ZYT_STORE_PKG;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String runGetprop() {
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("getprop").redirectErrorStream(true).start();
            java.lang.Process proc = p;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    copyLimited(in, buf);
                } catch (IOException ignored) {}
            }, "zyt-getprop");
            reader.setDaemon(true);
            reader.start();
            p.waitFor(GETPROP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            reader.join(300L);
            synchronized (buf) {
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            Log.w("zyt: getprop failed: %s", t);
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String readFile(String path) {
        File f = new File(path);
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            copyLimited(in, buf);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void copyLimited(InputStream in, ByteArrayOutputStream buf) throws IOException {
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) {
            synchronized (buf) {
                int room = MAX_READ - buf.size();
                if (room <= 0) break;
                buf.write(tmp, 0, Math.min(n, room));
            }
        }
    }

    static String section(String out, String start, String end) {
        if (out == null) return "";
        int a = out.indexOf(start);
        if (a < 0) return "";
        a += start.length();
        int b = out.indexOf(end, a);
        return b < 0 ? out.substring(a) : out.substring(a, b);
    }

    static Set<String> tokens(String s) {
        if (s == null || s.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String t : lower(s).split("[^a-z0-9]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String firstLineWith(String s, String needle) {
        for (String line : nz(s).split("\n")) {
            if (lower(line).contains(needle)) return clip(line.trim());
        }
        return "?";
    }

    private static String clip(String s) {
        s = nz(s);
        return s.length() > 96 ? s.substring(0, 96) + "…" : s;
    }

    private static String lower(String s) {
        return nz(s).toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
