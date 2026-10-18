package com.ghostpanter.scrcpy;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.ConnectException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbStream;

// Bring the scrcpy server up on the target:
//   1. Push assets/scrcpy-server.jar to /data/local/tmp/scrcpy-server.jar
//      via the adb sync protocol.
//   2. Spawn `app_process / com.genymobile.scrcpy.Server <ver> key=value...`
//      via an adb shell stream and hold it open. cleanup=true means the
//      server exits when we close that stream.
//   3. Open three localabstract:scrcpy_<scid> streams in order
//      (video, audio, control). With tunnel_forward=true the server is
//      the listener, so we just dial.
//   4. Drain 1 probe byte + 64-byte device name from the FIRST stream
//      that the server accepts (video, if requested).
//
// Returns a Streams record with everything Session needs: typed
// InputStream/OutputStream wrappers (opened ONCE per AdbStream so the
// reader's offset is unambiguous), plus the underlying AdbStream
// handles so close() can release them.
public final class Server {

    private static final String REMOTE_PATH    = "/data/local/tmp/scrcpy-server.jar";
    // Written before su elevate so Magisk grants a short, stable cmdline
    // (avoids quoting breakage on long CLASSPATH=... app_process lines).
    private static final String REMOTE_START   = "/data/local/tmp/scrcpy-gp-start.sh";
    private static final String REMOTE_PROBE   = "/data/local/tmp/scrcpy-gp-probe.sh";
    // Written by elevated start script; polled via a *separate* shell so we
    // do not depend on Magisk/su forwarding stdout through ADB shell:cmd
    // (non-TTY + often fully buffered until process exit — and app_process
    // never exits, so a banner-only check falsely FALLBACKs despite Magisk
    // already granting shell).
    private static final String REMOTE_UID     = "/data/local/tmp/scrcpy-gp-uid";
    private static final int    SCRIPT_MODE    = 0100755;         // regular file, 0755
    private static final String ASSET_JAR      = "scrcpy-server.jar";
    private static final String ASSET_VERSION  = "scrcpy-server.version";
    private static final int    FILE_MODE      = 0100644;         // regular file, 0644
    private static final long   LISTENER_DEADLINE_MS = 20_000;
    // Elevated spawn may still be waiting on Magisk when we start accepting.
    private static final long   LISTENER_DEADLINE_ELEVATED_MS = 70_000;
    private static final long   LISTENER_RETRY_MS = 50;
    // Already-granted Magisk/KernelSU returns uid 0 almost immediately.
    private static final long   SU_QUICK_MS = 1_500L;
    // Only used when the quick probe hangs (first grant / Magisk dialog).
    private static final long   SU_PROMPT_MS = 60_000L;
    // Elevated start uid confirm: short when already granted, then extend.
    private static final long   ELEVATE_UID_QUICK_MS = 2_500L;
    private static final long   ELEVATE_UID_POLL_MS = 120L;
    public static final class Streams {
        public final AdbStream    videoAds, audioAds, controlAds;
        public final InputStream  videoIn, audioIn, controlIn;
        public final OutputStream controlOut;

        Streams(AdbStream va, AdbStream aa, AdbStream ca,
                InputStream vi, InputStream ai, InputStream ci, OutputStream co) {
            this.videoAds = va; this.audioAds = aa; this.controlAds = ca;
            this.videoIn = vi;  this.audioIn = ai;  this.controlIn = ci;
            this.controlOut = co;
        }
    }

    private final Context ctx;
    private final Adb     adb;
    private AdbStream     shell;
    private Thread        shellPump;
    private Streams       streams;
    private volatile boolean serverEof;

    public Server(Context ctx, Adb adb) {
        this.ctx = ctx;
        this.adb = adb;
    }

    public enum ElevateStatus {
        /** Settings toggle off — shell spawn only. */
        DISABLED,
        /** scrcpy-server launched as uid 0 or AID_SYSTEM (1000). */
        ROOT,
        /** No working su on the target (or binary missing). */
        UNAVAILABLE,
        /** Magisk/KernelSU prompt timed out or denied. */
        DENIED,
        /** su worked in probe but elevated spawn failed; fell back to shell. */
        FALLBACK,
        /**
         * Root/system identity worked but DisplayManager rejected SECURE VD
         * (packageName must match the owner uid). HyperOS A16 needs JingMatrix
         * LSPosed + Enable Screenshot with System Framework scope only —
         * Magisk alone is not enough.
         */
        SECURE_OEM_REJECTED
    }

    private volatile ElevateStatus elevateStatus = ElevateStatus.DISABLED;
    /** Which elevate wrapper succeeded: probe form or start recipe name. */
    private String suForm;
    /** Last elevate failure transcript (for toast / log viewer). */
    private volatile String lastElevateDetail = "";
    /** Stdout captured during elevated uid wait (structural-error detection). */
    private volatile String lastElevateStdout = "";
    /** Fired when we enter the long Magisk-grant wait (not on already-granted quick path). */
    private volatile Runnable onWaitingForGrant;
    /** Fired when server logs SECURE_VD_OEM_REJECT (may be after elevateStatus=ROOT). */
    private volatile Runnable onSecureOemRejected;

    public ElevateStatus elevateStatus() {
        return elevateStatus;
    }

    public String lastElevateDetail() {
        return lastElevateDetail == null ? "" : lastElevateDetail;
    }

    public void setOnWaitingForGrant(Runnable r) {
        onWaitingForGrant = r;
    }

    public void setOnSecureOemRejected(Runnable r) {
        onSecureOemRejected = r;
    }

    private void notifySecureOemRejected() {
        elevateStatus = ElevateStatus.SECURE_OEM_REJECTED;
        Runnable r = onSecureOemRejected;
        if (r != null) {
            try { r.run(); } catch (RuntimeException ignored) {}
        }
    }

    private void notifyWaitingForGrant() {
        Runnable r = onWaitingForGrant;
        if (r != null) {
            try { r.run(); } catch (RuntimeException ignored) {}
        }
    }

    public Streams bringUp() throws Exception {
        serverEof = false;
        elevateStatus = ElevateStatus.DISABLED;
        suForm = null;
        String version = readVersion();
        long pushed = push();
        Log.i("push %s bytes=%d", REMOTE_PATH, pushed);

        // Prefer root-elevated spawn when the user wants secure-layer capture
        // and the target has a working su (Magisk / KernelSU). Official
        // scrcpy-server.jar is unchanged; we only wrap the launch as uid 0
        // (scrcpy-root style) so createDisplay(secure) can succeed on
        // Android 12+. Any elevate failure falls back to a normal shell start.
        //
        // Magisk note: the first su request from ADB shell often shows a
        // Superuser dialog (or auto-grants + toast if Magisk already allowed
        // "shell"/ADB). The old 2.5s probe closed the AdbStream before the
        // user could tap Allow, cancelling the request so no lasting dialog
        // appeared and we silently fell back to shell — FLAG_SECURE stayed
        // black. Wait long enough for an interactive grant.
        boolean wantElevate = Settings.rootCaptureSecure(ctx);
        lastElevateDetail = "";
        if (wantElevate) {
            SuProbeResult probe = probeSu();
            if (probe == SuProbeResult.OK) {
                String scid = newScid();
                String serverArgs = buildServerArgs(version, scid);
                pushElevatedStartScript(serverArgs);
                Log.i("spawn server (root) ver=%s scid=%s probeForm=%s", version, scid, suForm);
                try {
                    Streams streams = tryElevatedRecipes(scid);
                    elevateStatus = ElevateStatus.ROOT;
                    return streams;
                } catch (Exception e) {
                    lastElevateDetail = String.valueOf(e.getMessage());
                    Log.w("server: root elevate FAILED all recipes — falling back to shell. detail=%s",
                            lastElevateDetail);
                    closeShell();
                    serverEof = false;
                    elevateStatus = ElevateStatus.FALLBACK;
                }
            } else if (probe == SuProbeResult.DENIED) {
                elevateStatus = ElevateStatus.DENIED;
                lastElevateDetail = "su denied/timeout form=" + suForm;
                Log.i("server: su denied/timeout; using shell (secure layers will stay black)");
            } else {
                elevateStatus = ElevateStatus.UNAVAILABLE;
                lastElevateDetail = "su unavailable";
                Log.i("server: root capture on but su not available; using shell");
            }
        }

        String scid = newScid();
        String cmd = buildCmdline(version, scid);
        Log.i("spawn server ver=%s scid=%s", version, scid);
        Log.i("cmdline: %s", cmd);
        return spawnAndConnect(cmd, scid);
    }

    private Streams spawnAndConnect(String cmd, String scid) throws Exception {
        AdbStream va = null, aa = null, ca = null;
        boolean committed = false;
        try {
            shell = adb.openShell(cmd);
            AdbStream shellRef = shell;
            InputStream shellIn = shellRef.openInputStream();
            shellPump = new Thread(() -> pump(shellIn), "server-stdout");
            shellPump.setDaemon(true);
            shellPump.start();

            // These accepts are ordered. If one times out, the whole ADB
            // connection is discarded by Session; retrying an individual
            // open could shift video/audio/control onto the wrong sockets.
            long acceptDeadline = LISTENER_DEADLINE_MS;
            va = openAbstract(scid, acceptDeadline);
            aa = openAbstract(scid, acceptDeadline);
            ca = openAbstract(scid, acceptDeadline);

            InputStream  vi = va.openInputStream();
            InputStream  ai = aa.openInputStream();
            InputStream  ci = ca.openInputStream();
            OutputStream co = ca.openOutputStream();

            Log.i("device name=%s", readDeviceMeta(vi));
            streams = new Streams(va, aa, ca, vi, ai, ci, co);
            committed = true;
            return streams;
        } finally {
            if (!committed) {
                closeQuietly(va);
                closeQuietly(aa);
                closeQuietly(ca);
                closeShell();
            }
        }
    }

    // Magisk / KernelSU elevate.
    //
    // Probe may already see uid 0 (Magisk Superuser shows shell ALLOWED), yet
    // awaitElevatedUid still missed the start banner: ADB shell:cmd is
    // non-TTY, Magisk's su -c often fully-buffers stdout until process exit,
    // and exec app_process never exits → false FALLBACK / 提权失败仍黑屏.
    //
    // vc19: write uid to REMOTE_UID; poll via separate non-elevated shell.
    // vc20: Magisk/mksh `exec CLASSPATH=jar app_process` treats CLASSPATH= as
    // argv0 ("inaccessible or not found"). export CLASSPATH then exec absolute
    // /system/bin/app_process; copy jar to /dev or /data/adb for mount-ns.
    // Prefer Magisk-friendly su -c; on uid0 + script structural failure do not
    // cascade recipes (keeps ADB connection alive).
    // Magisk/mksh: `exec CLASSPATH=jar app_process ...` treats CLASSPATH=... as the
    // binary name → "CLASSPATH=...: inaccessible or not found". Always export
    // CLASSPATH, then exec absolute /system/bin/app_process. Also copy the jar
    // into a root-visible path (/dev tmpfs or /data/adb) so Magisk mount-ns
    // isolation cannot hide adbd's /data/local/tmp.
    private void pushElevatedStartScript(String serverArgs) throws Exception {
        String body = "#!/system/bin/sh\n"
                + "UF=" + REMOTE_UID + "\n"
                + "JAR_SRC=" + REMOTE_PATH + "\n"
                + "JAR_DEV=/dev/.scrcpy-gp-server.jar\n"
                + "JAR_ADB=/data/adb/scrcpy-gp-server.jar\n"
                + "rm -f \"$UF\" \"$UF.tmp\"\n"
                + "uid=$(/system/bin/id -u 2>/dev/null || id -u)\n"
                + "printf '%s\\n' \"$uid\" > \"$UF.tmp\"\n"
                + "/system/bin/mv \"$UF.tmp\" \"$UF\" 2>/dev/null || mv \"$UF.tmp\" \"$UF\"\n"
                + "sync\n"
                + "/system/bin/sh -c \"printf 'scrcpy-gp:uid=%s\\n' \\\"$uid\\\"\"\n"
                + "printf 'scrcpy-gp:uid=%s\\n' \"$uid\" >&2\n"
                + "# Accept root (0) or AID_SYSTEM (1000) birth identity\n"
                + "[ \"$uid\" = \"0\" ] || [ \"$uid\" = \"1000\" ] || exit 42\n"
                + "JAR=\"\"\n"
                + "for c in \"$JAR_SRC\" \"$JAR_ADB\" \"$JAR_DEV\"; do\n"
                + "  if [ -r \"$c\" ]; then JAR=$c; break; fi\n"
                + "done\n"
                + "if [ -z \"$JAR\" ]; then\n"
                + "  printf 'scrcpy-gp:jar-missing src=%s\\n' \"$JAR_SRC\" >&2\n"
                + "  exit 43\n"
                + "fi\n"
                + "DST=\"$JAR\"\n"
                + "if cp -f \"$JAR\" \"$JAR_DEV\" 2>/dev/null; then DST=$JAR_DEV; \n"
                + "elif cp -f \"$JAR\" \"$JAR_ADB\" 2>/dev/null; then DST=$JAR_ADB; fi\n"
                + "if [ ! -r \"$DST\" ]; then\n"
                + "  printf 'scrcpy-gp:jar-unreadable path=%s\\n' \"$DST\" >&2\n"
                + "  exit 43\n"
                + "fi\n"
                // Never: exec CLASSPATH=... app_process (mksh treats assignment as argv0).
                + "export CLASSPATH=\"$DST\"\n"
                + "printf 'scrcpy-gp:classpath=%s\\n' \"$CLASSPATH\" >&2\n"
                + "exec /system/bin/app_process / "
                + serverArgs + "\n";
        pushTextFile(REMOTE_START, body, SCRIPT_MODE);
        Log.i("server: elevated start script ready path=%s argsLen=%d",
                REMOTE_START, serverArgs.length());
    }

    private static final class ElevateRecipe {
        final String name;
        final String openCmd;   // Adb shell: destination
        final String stdinCmd;  // if non-null, write this after open (interactive su)
        ElevateRecipe(String name, String openCmd, String stdinCmd) {
            this.name = name;
            this.openCmd = openCmd;
            this.stdinCmd = stdinCmd;
        }
    }

    private java.util.List<ElevateRecipe> elevateRecipes() {
        // Prefer Magisk-friendly `su -c` (caller's mount ns keeps /data/local/tmp
        // visible). Avoid hammering nsenter first — it can hide the jar and burn
        // the ADB transport while cascading.
        java.util.ArrayList<ElevateRecipe> out = new java.util.ArrayList<>();
        String shStart = "sh " + REMOTE_START;
        String q = shellSingleQuote(shStart);
        String preferred = suForm == null ? "" : suForm;
        java.util.function.Consumer<ElevateRecipe> add = r -> {
            for (ElevateRecipe e : out) if (e.name.equals(r.name)) return;
            out.add(r);
        };
        if (preferred.startsWith("su0")) {
            add.accept(new ElevateRecipe("su0_c", "su 0 -c " + q, null));
            add.accept(new ElevateRecipe("su_c", "su -c " + q, null));
        } else {
            add.accept(new ElevateRecipe("su_c", "su -c " + q, null));
            add.accept(new ElevateRecipe("su0_c", "su 0 -c " + q, null));
        }
        add.accept(new ElevateRecipe("sysbin_su_c",
                "/system/bin/su -c " + q, null));
        add.accept(new ElevateRecipe("debug_ramdisk_su_c",
                "/debug_ramdisk/su -c " + q, null));
        // Birth as AID_SYSTEM: Binder callingUid is 1000 from process start (no mid-flight
        // setresuid). Magisk: su 1000 -c … ; some builds need su -c 'su 1000 …'.
        add.accept(new ElevateRecipe("su1000_c", "su 1000 -c " + q, null));
        add.accept(new ElevateRecipe("su_c_su1000",
                "su -c " + shellSingleQuote("su 1000 sh " + REMOTE_START), null));
        add.accept(new ElevateRecipe("su0_c_su1000",
                "su 0 -c " + shellSingleQuote("su 1000 sh " + REMOTE_START), null));
        add.accept(new ElevateRecipe("su_stdin", "su", shStart + "\n"));
        // Last resort: enter init mount ns only after copying jar inside script.
        String ns = shellSingleQuote(
                "nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh " + REMOTE_START);
        add.accept(new ElevateRecipe("su_c_nsenter", "su -c " + ns, null));
        return out;
    }


    private Streams tryElevatedRecipes(String scid) throws Exception {
        java.util.List<ElevateRecipe> recipes = elevateRecipes();
        StringBuilder transcript = new StringBuilder();
        Exception last = null;
        boolean first = true;
        for (ElevateRecipe r : recipes) {
            // First recipe may wait for Magisk dialog; later ones fail fast.
            long uidWait = first ? SU_PROMPT_MS : ELEVATE_UID_QUICK_MS;
            first = false;
            Log.i("server: elevate recipe TRY name=%s open=%s stdin=%s uidWait=%d",
                    r.name, r.openCmd, r.stdinCmd == null ? "-" : "yes", uidWait);
            transcript.append("TRY ").append(r.name).append(" open=").append(r.openCmd);
            if (r.stdinCmd != null) transcript.append(" stdin=yes");
            transcript.append('\n');
            try {
                clearRemoteUidFile();
                lastElevateStdout = "";
                Streams streams = spawnAndConnectElevated(r, scid, uidWait);
                Log.i("server: elevate recipe OK name=%s", r.name);
                suForm = r.name;
                lastElevateDetail = "ok recipe=" + r.name;
                return streams;
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                Log.w("server: elevate recipe FAIL name=%s err=%s", r.name, msg);
                transcript.append("FAIL ").append(r.name).append(" ").append(msg).append('\n');
                last = e;
                // Only close the elevate shell stream — never the ADB connection.
                closeShell();
                serverEof = false;
                if (isElevateScriptStructuralFailure(msg)) {
                    // uid0 reached but start script/exec is wrong; more recipes
                    // will not help and may drop the ADB transport.
                    Log.e("server: elevate structural failure after uid0 — stop cascade: %s", msg);
                    break;
                }
            }
        }
        String detail = transcript.toString().trim();
        lastElevateDetail = detail;
        Log.e("server: elevate all recipes failed:\n%s", detail);
        if (last != null) throw new IOException("all elevate recipes failed: " + detail, last);
        throw new IOException("all elevate recipes failed: " + detail);
    }

    private Streams spawnAndConnectElevated(ElevateRecipe recipe, String scid, long uidWaitMs)
            throws Exception {
        AdbStream va = null, aa = null, ca = null;
        boolean committed = false;
        try {
            shell = adb.openShell(recipe.openCmd);
            AdbStream shellRef = shell;
            if (recipe.stdinCmd != null) {
                OutputStream shellOut = shellRef.openOutputStream();
                byte[] bytes = recipe.stdinCmd.getBytes(StandardCharsets.UTF_8);
                shellOut.write(bytes);
                shellOut.flush();
                Log.d("server: elevate stdin wrote %d bytes recipe=%s",
                        bytes.length, recipe.name);
            }
            InputStream shellIn = shellRef.openInputStream();
            int uid = awaitElevatedUid(shellIn, recipe.name, uidWaitMs);
            if (uid != 0 && uid != 1000) {
                throw new IOException("elevated spawn not uid 0/1000 (got " + uid
                        + ") recipe=" + recipe.name);
            }
            Log.i("server: elevated start confirmed uid=%d recipe=%s", uid, recipe.name);
            if (isElevateScriptStructuralFailure(lastElevateStdout)) {
                throw new IOException("elevated script structural failure after uid0"
                        + " recipe=" + recipe.name + ": " + lastElevateStdout.trim());
            }
            shellPump = new Thread(() -> pump(shellIn), "server-stdout");
            shellPump.setDaemon(true);
            shellPump.start();

            // Failed exec exits immediately; do not burn 70s accept or cascade ADB.
            long settleDeadline = monotonicMs() + 900;
            while (monotonicMs() < settleDeadline && !serverEof) {
                try { Thread.sleep(40); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (serverEof) {
                throw new IOException("elevated server exited immediately after uid0"
                        + " recipe=" + recipe.name
                        + (lastElevateStdout.isEmpty() ? "" : (": " + lastElevateStdout.trim())));
            }

            long acceptDeadline = LISTENER_DEADLINE_ELEVATED_MS;
            va = openAbstract(scid, acceptDeadline);
            aa = openAbstract(scid, acceptDeadline);
            ca = openAbstract(scid, acceptDeadline);

            InputStream  vi = va.openInputStream();
            InputStream  ai = aa.openInputStream();
            InputStream  ci = ca.openInputStream();
            OutputStream co = ca.openOutputStream();

            Log.i("device name=%s", readDeviceMeta(vi));
            streams = new Streams(va, aa, ca, vi, ai, ci, co);
            committed = true;
            return streams;
        } finally {
            if (!committed) {
                closeQuietly(va);
                closeQuietly(aa);
                closeQuietly(ca);
                closeShell();
            }
        }
    }

    private static String shellSingleQuote(String s) {
        // Wrap for sh -c; embed apostrophes as '\''.
        return "'" + s.replace("'", "'\''") + "'";
    }

    private enum SuProbeResult { OK, UNAVAILABLE, DENIED, PENDING }

    private SuProbeResult probeSu() {
        // Fast path: inline `id -u` with a short timeout. When Magisk has
        // already allowed ADB shell, this returns uid 0 in well under SU_QUICK_MS
        // and we skip the 60s Magisk-dialog wait entirely.
        // Prefer portable su -c (KernelSU / Magisk); then Magisk-style su 0 -c.
        String[][] quickForms = {
                {"su_c", "su -c " + shellSingleQuote("id -u")},
                {"su0", "su 0 -c " + shellSingleQuote("id -u")},
                {"su0_raw", "su 0 id -u"},
                {"sysbin_su_c", "/system/bin/su -c " + shellSingleQuote("id -u")},
                {"debug_ramdisk_su_c", "/debug_ramdisk/su -c " + shellSingleQuote("id -u")},
        };
        String pendingName = null;
        String pendingCmd = null;
        SuProbeResult worst = SuProbeResult.UNAVAILABLE;
        for (String[] form : quickForms) {
            SuProbeResult r = probeSuOnce(form[0], form[1], SU_QUICK_MS);
            if (r == SuProbeResult.OK) return SuProbeResult.OK;
            if (r == SuProbeResult.DENIED) return SuProbeResult.DENIED;
            if (r == SuProbeResult.PENDING) {
                // Magisk dialog likely showing — do not open a second su.
                pendingName = form[0];
                pendingCmd = form[1];
                break;
            }
            worst = r;
        }
        if (pendingName == null) return worst;

        // su is present but the quick probe hung → first Magisk grant.
        // One long wait only (do not stack 60s × N forms). Prefer a short
        // pushed script so Magisk's Superuser UI shows a stable path.
        notifyWaitingForGrant();
        Log.i("server: su already-pending; long Magisk wait form=%s", pendingName);
        try {
            pushTextFile(REMOTE_PROBE, "#!/system/bin/sh\nid -u\n", SCRIPT_MODE);
            String scriptName;
            String scriptCmd;
            if (pendingName.startsWith("su0")) {
                scriptName = "su0_script";
                scriptCmd = "su 0 -c " + shellSingleQuote("sh " + REMOTE_PROBE);
            } else if (pendingName.startsWith("sysbin")) {
                scriptName = "sysbin_su_c_script";
                scriptCmd = "/system/bin/su -c " + shellSingleQuote("sh " + REMOTE_PROBE);
            } else if (pendingName.startsWith("debug")) {
                scriptName = "debug_ramdisk_su_c_script";
                scriptCmd = "/debug_ramdisk/su -c " + shellSingleQuote("sh " + REMOTE_PROBE);
            } else {
                scriptName = "su_c_script";
                scriptCmd = "su -c " + shellSingleQuote("sh " + REMOTE_PROBE);
            }
            SuProbeResult longR = probeSuOnce(scriptName, scriptCmd, SU_PROMPT_MS);
            if (longR == SuProbeResult.PENDING) return SuProbeResult.DENIED;
            return longR;
        } catch (Exception e) {
            Log.w("server: long su probe via script failed (%s); retrying inline", e);
            SuProbeResult longR = probeSuOnce(pendingName, pendingCmd, SU_PROMPT_MS);
            if (longR == SuProbeResult.PENDING) return SuProbeResult.DENIED;
            return longR;
        }
    }

    private SuProbeResult probeSuOnce(String formName, String cmd, long timeoutMs) {
        AdbStream s = null;
        Thread reader = null;
        final StringBuilder out = new StringBuilder();
        final long started = monotonicMs();
        try {
            Log.i("server: su probe begin form=%s timeout=%d ms cmd=%s",
                    formName, timeoutMs, cmd);
            s = adb.openShell(cmd);
            AdbStream ref = s;
            reader = new Thread(() -> {
                try (InputStream in = ref.openInputStream()) {
                    byte[] buf = new byte[128];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xff);
                            if (c >= 0x20 && c <= 0x7e) out.append(c);
                            else if (c == '\n' || c == '\r') out.append(' ');
                        }
                        if (out.length() > 96) break;
                    }
                } catch (IOException ignored) {}
            }, "su-probe");
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);
            boolean timedOut = reader.isAlive();
            String textOut = out.toString().trim();
            boolean ok = looksLikeUid0(textOut);
            long elapsed = monotonicMs() - started;
            if (ok) {
                suForm = formName;
                Log.i("server: su probe OK form=%s elapsed=%d ms raw=%s",
                        formName, elapsed, textOut);
                return SuProbeResult.OK;
            }
            if (looksLikeSuDenied(textOut)) {
                Log.i("server: su probe DENIED form=%s elapsed=%d ms raw=%s",
                        formName, elapsed, textOut);
                return SuProbeResult.DENIED;
            }
            if (timedOut) {
                Log.i("server: su probe PENDING form=%s elapsed=%d ms (no uid yet) raw=%s",
                        formName, elapsed, textOut);
                return SuProbeResult.PENDING;
            }
            Log.i("server: su probe UNAVAILABLE form=%s elapsed=%d ms raw=%s",
                    formName, elapsed, textOut);
            return SuProbeResult.UNAVAILABLE;
        } catch (Exception e) {
            Log.i("server: su probe failed form=%s: %s", formName, e);
            return SuProbeResult.UNAVAILABLE;
        } finally {
            if (reader != null && reader.isAlive()) reader.interrupt();
            closeQuietly(s);
        }
    }

    /** Read start-script banner `scrcpy-gp:uid=N` from the elevated shell stdout. */

    private void clearRemoteUidFile() {
        AdbStream s = null;
        try {
            s = adb.openShell("rm -f " + REMOTE_UID + " " + REMOTE_UID + ".tmp");
            try (InputStream in = s.openInputStream()) {
                byte[] buf = new byte[64];
                long deadline = monotonicMs() + 800;
                while (monotonicMs() < deadline) {
                    if (in.read(buf) < 0) break;
                }
            }
            Log.d("server: cleared %s", REMOTE_UID);
        } catch (Exception e) {
            Log.d("server: clear uid file: %s", e);
        } finally {
            closeQuietly(s);
        }
    }

    /** Read uid from REMOTE_UID via a short-lived non-elevated shell. */
    private int readRemoteUidFile() {
        AdbStream s = null;
        try {
            s = adb.openShell("cat " + REMOTE_UID + " 2>/dev/null");
            StringBuilder out = new StringBuilder();
            try (InputStream in = s.openInputStream()) {
                byte[] buf = new byte[32];
                long deadline = monotonicMs() + 600;
                int n;
                while (monotonicMs() < deadline && (n = in.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        char c = (char) (buf[i] & 0xff);
                        if (c >= '0' && c <= '9') out.append(c);
                        else if (out.length() > 0) break;
                    }
                    if (out.length() > 0 && n < buf.length) break;
                }
            }
            if (out.length() == 0) return -1;
            return Integer.parseInt(out.toString());
        } catch (Exception e) {
            return -1;
        } finally {
            closeQuietly(s);
        }
    }

    /** Confirm elevated start via uid file (authoritative) and/or stdout banner. */
    private int awaitElevatedUid(InputStream in, String recipeName, long maxWaitMs) throws Exception {
        final StringBuilder out = new StringBuilder();
        final Object lock = new Object();
        final boolean[] done = {false};
        Thread reader = new Thread(() -> {
            try {
                byte[] buf = new byte[128];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (lock) {
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xff);
                            if (c >= 0x20 && c <= 0x7e) out.append(c);
                            else if (c == '\n' || c == '\r') out.append(' ');
                        }
                        if (out.indexOf("scrcpy-gp:uid=") >= 0 || out.length() > 400) {
                            done[0] = true;
                            lock.notifyAll();
                            return;
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        }, "elevate-uid");
        reader.setDaemon(true);
        reader.start();

        long started = monotonicMs();
        int fileUid = -1;
        int bannerUid = -1;
        boolean extended = false;

        long limit = maxWaitMs > 0 ? maxWaitMs : SU_PROMPT_MS;
        while (monotonicMs() - started < limit) {
            // Authoritative: separate-shell cat of REMOTE_UID.
            fileUid = readRemoteUidFile();
            if (fileUid == 0) {
                Log.i("server: elevated uid FILE ok uid=0 recipe=%s elapsed=%d ms",
                        recipeName, monotonicMs() - started);
                break;
            }
            synchronized (lock) {
                String text = out.toString();
                int idx = text.indexOf("scrcpy-gp:uid=");
                if (idx >= 0) {
                    int startUid = idx + "scrcpy-gp:uid=".length();
                    int endUid = startUid;
                    while (endUid < text.length() && Character.isDigit(text.charAt(endUid)))
                        endUid++;
                    if (endUid > startUid) {
                        try { bannerUid = Integer.parseInt(text.substring(startUid, endUid)); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (bannerUid == 0) {
                Log.i("server: elevated uid BANNER ok uid=0 recipe=%s elapsed=%d ms",
                        recipeName, monotonicMs() - started);
                break;
            }
            if (fileUid > 0) {
                Log.w("server: elevated uid FILE non-root uid=%d recipe=%s",
                        fileUid, recipeName);
                break;
            }
            if (bannerUid > 0) {
                Log.w("server: elevated uid BANNER non-root uid=%d recipe=%s",
                        bannerUid, recipeName);
                break;
            }
            long elapsed = monotonicMs() - started;
            if (!extended && elapsed >= ELEVATE_UID_QUICK_MS && !done[0]) {
                extended = true;
                notifyWaitingForGrant();
                Log.i("server: waiting for Magisk grant on elevated start recipe=%s",
                        recipeName);
            }
            if (done[0] && fileUid < 0 && bannerUid < 0 && elapsed >= ELEVATE_UID_QUICK_MS) {
                // Stream ended with no uid — recipe failed fast.
                break;
            }
            try { Thread.sleep(ELEVATE_UID_POLL_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!done[0]) reader.interrupt();
        try { reader.join(400); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        final String textOut;
        synchronized (lock) { textOut = out.toString(); }
        lastElevateStdout = textOut;
        Log.i("server: elevate uid wait done recipe=%s fileUid=%d bannerUid=%d elapsed=%d ms stdout=%s",
                recipeName, fileUid, bannerUid, monotonicMs() - started, textOut.trim());

        if (fileUid == 0 || bannerUid == 0) return 0;
        if (fileUid > 0) return fileUid;
        if (bannerUid > 0) return bannerUid;
        return -1;
    }

    /**
     * Magisk already granted uid 0 but the start script/exec is wrong — more
     * su recipes will not help and may drop the ADB connection. Detect both
     * the classic mksh CLASSPATH-as-argv0 error and post-uid0 immediate exit.
     */
    private static boolean isElevateScriptStructuralFailure(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("after uid0")) return true;
        if (t.contains("scrcpy-gp:jar-missing") || t.contains("scrcpy-gp:jar-unreadable"))
            return true;
        if (t.contains("structural failure")) return true;
        if (t.contains("classpath=") && (t.contains("inaccessible") || t.contains("not found")))
            return true;
        if (t.contains("app_process") && (t.contains("inaccessible") || t.contains("not found")))
            return true;
        return false;
    }

    private static boolean looksLikeUid0(String textOut) {
        if (textOut == null || textOut.isEmpty()) return false;
        if (textOut.contains("uid=0")) return true;
        int end = 0;
        while (end < textOut.length() && Character.isDigit(textOut.charAt(end))) end++;
        if (end > 0) {
            try { return Integer.parseInt(textOut.substring(0, end)) == 0; }
            catch (NumberFormatException ignored) {}
        }
        return false;
    }

    private static boolean looksLikeSuDenied(String textOut) {
        if (textOut == null || textOut.isEmpty()) return false;
        String lower = textOut.toLowerCase(Locale.ROOT);
        return lower.contains("permission denied")
                || lower.contains("not allowed")
                || lower.contains("access denied")
                || lower.contains("request rejected")
                || lower.contains("denied")
                || lower.startsWith("su:");
    }

    // Idempotent. Closes the three media/control streams first (lets
    // the wire drain), then the shell (which makes the scrcpy server
    // exit via cleanup=true), then gives the cleanup helper up to
    // CLOSE_GRACE_MS to restore device state - display power, screen
    // timeout, brightness, etc. - before we kill the adb connection
    // out from under it. Best-effort: cleanup is server-side and we
    // can't synchronously confirm it.
    public void close() {
        if (streams != null) {
            closeQuietly(streams.videoAds);
            closeQuietly(streams.audioAds);
            closeQuietly(streams.controlAds);
            streams = null;
        }
        closeShell();
    }

    private void closeShell() {
        Thread t = shellPump;
        AdbStream s = shell;
        shell = null;
        shellPump = null;
        closeQuietly(s);
        if (t != null) {
            try {
                t.join(CLOSE_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) t.interrupt();
        }
    }

    private static final long CLOSE_GRACE_MS = 500;

    private static void closeQuietly(AdbStream s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    // ---- helpers ----

    private String readVersion() throws IOException {
        try (InputStream in = ctx.getAssets().open(ASSET_VERSION)) {
            byte[] buf = new byte[64];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                if (r == 0) throw new IOException("scrcpy-server.version read made no progress");
                n += r;
            }
            if (n == buf.length && in.read() >= 0) {
                throw new IOException("scrcpy-server.version is too long");
            }
            String v = Wire.decodeUtf8(buf, 0, n).trim();
            if (!v.matches("[0-9]+(\\.[0-9]+)*")) {
                throw new IOException("scrcpy-server.version is invalid");
            }
            return v;
        }
    }

    private static String newScid() {
        // 31-bit random, 8 lowercase hex chars - matches scrcpy upstream client.
        int v = new SecureRandom().nextInt() & 0x7fffffff;
        return String.format(Locale.ROOT, "%08x", v);
    }

    // Controlled-device API level (0 = unknown). Used to keep server args
    // valid on old targets (Android 9 = 28).
    private int remoteSdk;

    public void setRemoteSdk(int sdk) { remoteSdk = sdk; }

    public static int parseSdk(String out) {
        if (out == null) return 0;
        String t = out.trim();
        if (!t.matches("[0-9]{1,3}")) return 0;
        int v = Integer.parseInt(t);
        return v >= 1 && v <= 200 ? v : 0;
    }

    // Video codec actually requested from the server for this target.
    // AV1 hardware encoders only exist on recent devices; H.265 encoders are
    // unreliable before Android 10 (API 29). Fall back to H.264 there so
    // Android 9 targets never fail on "encoder not found".
    static String effectiveVideoCodec(String requested, int sdk) {
        if (sdk <= 0 || requested == null) return requested;
        if ("av1".equals(requested) && sdk < 34) return "h264";
        if ("h265".equals(requested) && sdk < 29) return "h264";
        return requested;
    }

    /** Args after `app_process /` (no CLASSPATH / no app_process). */
    private String buildServerArgs(String version, String scid) {
        String videoCodec = effectiveVideoCodec(Settings.videoCodec(ctx), remoteSdk);
        if (!videoCodec.equals(Settings.videoCodec(ctx))) {
            Log.i("server: target sdk=%d, video codec %s -> %s", remoteSdk,
                    Settings.videoCodec(ctx), videoCodec);
        }
        String audioCodec = Settings.audioCodec(ctx);
        int maxSize     = Settings.maxSize(ctx);
        // Tablet mode (Ui.isTablet): prefer controlled-device native resolution
        // (max_size=0) until the user explicitly picks a size in Settings.
        if (Ui.isTablet(ctx) && !Settings.prefs(ctx).contains(Settings.MAX_SIZE)) {
            maxSize = 0;
        }
        int videoBitR   = Settings.videoBitRate(ctx);
        int maxFps      = Settings.maxFps(ctx);
        boolean lowLat  = Settings.lowLatency(ctx);
        List<String> args = new ArrayList<>();
        args.add("com.genymobile.scrcpy.Server");
        args.add(version);
        args.add("scid=" + scid);
        args.add("log_level=info");
        args.add("video=true");
        // Keep audio=true even on API < 30: scrcpy-server still accepts the
        // audio socket there and immediately writes a "disabled" marker
        // (AudioEncoder: "not supported before Android 11"), which keeps the
        // video/audio/control accept order this client relies on.
        args.add("audio=true");
        args.add("control=true");
        args.add("video_codec=" + videoCodec);
        args.add("audio_codec=" + audioCodec);
        args.add("max_size=" + maxSize);
        args.add("video_bit_rate=" + videoBitR);
        args.add("max_fps=" + maxFps);
        // Prefer the lowest-latency encoder option when the
        // target MediaCodec supports it (scrcpy server option).
        if (lowLat) args.add("video_codec_options=i-frame-interval=1");
        // Off means off at the source: the server never sends the
        // target's clipboard, rather than us receiving and discarding it.
        args.add("clipboard_autosync=" + Settings.clipboardSync(ctx));
        args.add("tunnel_forward=true");
        args.add("cleanup=true");
        args.add("power_on=true");
        return String.join(" ", args);
    }

    /** Non-elevated ADB shell cmdline. Env assignment works here (no exec). */
    private String buildCmdline(String version, String scid) {
        // adb shell: supports VAR=value cmd; do not wrap in exec.
        return "CLASSPATH=" + REMOTE_PATH + " app_process / " + buildServerArgs(version, scid);
    }

    private AdbStream openAbstract(String scid, long deadlineMs) throws Exception {
        String name = "scrcpy_" + scid;
        long deadline = monotonicMs() + deadlineMs;
        ConnectException last = null;
        while (monotonicMs() < deadline) {
            if (serverEof) {
                throw new IOException("server exited before opening " + name, last);
            }
            try {
                AdbStream stream = adb.openAbstract(name);
                Log.i("openAbstract %s ok", name);
                return stream;
            } catch (ConnectException e) {
                // A rejected OPEN consumed no server accept. Retry while the
                // server creates its listener; timeout failures remain fatal
                // because their acceptance state is ambiguous.
                last = e;
                Thread.sleep(LISTENER_RETRY_MS);
            }
        }
        throw new IOException("server did not open " + name + " within "
                + deadlineMs + " ms", last);
    }

    private static String readDeviceMeta(InputStream in) throws IOException {
        byte[] probe = new byte[1];
        Wire.readFully(in, probe);
        if (probe[0] != 0) throw new IOException("invalid scrcpy probe byte");
        byte[] name = new byte[64];
        Wire.readFully(in, name);
        int n = 0;
        while (n < name.length && name[n] != 0) n++;
        for (int i = n; i < name.length; i++) {
            if (name[i] != 0) throw new IOException("invalid device-name padding");
        }
        return safeLogText(Wire.decodeUtf8(name, 0, n));
    }

    private void pump(InputStream in) {
        // Do not use BufferedReader.readLine(): a hostile target can emit an
        // unterminated line of arbitrary size and make it allocate until OOM.
        byte[] bytes = new byte[2048];
        try (InputStream source = in) {
            int n;
            while ((n = source.read(bytes)) >= 0) {
                if (n == 0) throw new IOException("server stdout made no progress");
                String chunk = safeLogBytes(bytes, n).trim();
                if (!chunk.isEmpty()) {
                    Log.i("server: %s", chunk);
                    if (chunk.contains("SECURE_VD_OEM_REJECT")
                            && elevateStatus != ElevateStatus.SECURE_OEM_REJECTED) {
                        notifySecureOemRejected();
                    }
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) Log.w("server-stdout closed: %s", e);
        } finally {
            serverEof = true;
            Log.i("server: stream end");
        }
    }

    // ---- adb sync push ----

    private void pushTextFile(String remotePath, String body, int mode) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        AdbStream sync = adb.openSync();
        try (InputStream src = new ByteArrayInputStream(bytes)) {
            int mtime = (int) (System.currentTimeMillis() / 1000L);
            Sync.push(src, sync.openOutputStream(), sync.openInputStream(),
                    remotePath, mode, mtime);
        } finally {
            try { sync.close(); } catch (IOException ignored) {}
        }
        Log.i("push script %s bytes=%d", remotePath, bytes.length);
    }

    private long push() throws Exception {
        AdbStream sync = adb.openSync();
        // sync.openInput/OutputStream() return wrapper streams whose close()
        // is a no-op; the AdbStream itself owns the channel. Close it once
        // in finally.
        try (InputStream src = ctx.getAssets().open(ASSET_JAR)) {
            int mtime = (int)(System.currentTimeMillis() / 1000L);
            return Sync.push(src, sync.openOutputStream(), sync.openInputStream(),
                    REMOTE_PATH, FILE_MODE, mtime);
        } finally {
            try { sync.close(); } catch (IOException ignored) {}
        }
    }

    private static String safeLogBytes(byte[] data, int len) {
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xff;
            if (b == '\n' || b == '\r' || b == '\t') out.append(' ');
            else if (b >= 0x20 && b <= 0x7e) out.append((char) b);
            else out.append('.');
        }
        return out.toString();
    }

    private static String safeLogText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int type = Character.getType(c);
            out.append(Character.isISOControl(c) || type == Character.FORMAT ? '?' : c);
        }
        return out.toString();
    }

    private static long monotonicMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
}
