package com.ghostpanter.scrcpy;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.adb.AdbStream;

// Best-effort remote helpers over the live wireless ADB connection.
// Used by tablet tool panes while a mirror Session may also hold streams
// on the same AdbConnection (multiplexed). Prefer root (`su 0`) when the
// caller asks and it works; otherwise fall back to plain shell.
public final class AdbRemote {

    private static final int FILE_MODE = 0100644;
    private static final int MAX_SHELL_OUT = 512 * 1024;
    private static final long SHELL_JOIN_MS = 20_000L;

    private static final Pattern PKG = Pattern.compile("^package:(\\S+)");
    private static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)");
    private static final Pattern DENSITY = Pattern.compile("(\\d+)");

    public static final class FileEntry {
        public final String name;
        public final boolean directory;

        public FileEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    public static final class AppInfo {
        public final String packageName;
        public final boolean system;

        public AppInfo(String packageName, boolean system) {
            this.packageName = packageName;
            this.system = system;
        }
    }

    public static final class DisplaySize {
        public final int physicalW, physicalH;
        public final int currentW, currentH;
        public final boolean overridden;

        public DisplaySize(int pw, int ph, int cw, int ch, boolean overridden) {
            this.physicalW = pw;
            this.physicalH = ph;
            this.currentW = cw;
            this.currentH = ch;
            this.overridden = overridden;
        }

        public String physicalLabel() {
            return physicalW + "x" + physicalH;
        }

        public String currentLabel() {
            return currentW + "x" + currentH;
        }
    }

    public static final class DisplayDensity {
        public final int physical;
        public final int current;
        public final boolean overridden;

        public DisplayDensity(int physical, int current, boolean overridden) {
            this.physical = physical;
            this.current = current;
            this.overridden = overridden;
        }
    }

    private final Adb adb;

    public AdbRemote(Adb adb) {
        this.adb = adb;
    }

    public String shell(String cmd) throws Exception {
        return shell(cmd, false);
    }

    public String shell(String cmd, boolean preferRoot) throws Exception {
        if (cmd == null || cmd.isEmpty() || cmd.length() > 3500 || cmd.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid shell command");
        }
        if (preferRoot) {
            try {
                String elevated = "su 0 sh -c " + singleQuote(cmd);
                String out = shellRaw(elevated);
                if (out != null && !looksLikeSuDenied(out)) return out;
                Log.w("adb-remote: su denied, falling back to shell");
            } catch (Exception e) {
                Log.w("adb-remote: su failed, falling back: %s", e);
            }
        }
        return shellRaw(cmd);
    }

    private static boolean looksLikeSuDenied(String out) {
        String lower = out.toLowerCase(Locale.ROOT);
        return lower.contains("not found")
                || lower.contains("permission denied")
                || lower.contains("inaccessible")
                || lower.contains("can't find")
                || lower.startsWith("su:");
    }

    private String shellRaw(String cmd) throws Exception {
        AdbStream stream = null;
        try {
            stream = adb.openShell(cmd);
            AdbStream ref = stream;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = ref.openInputStream()) {
                    byte[] tmp = new byte[4096];
                    int n;
                    while ((n = in.read(tmp)) >= 0) {
                        if (n == 0) break;
                        if (buf.size() + n > MAX_SHELL_OUT) {
                            buf.write(tmp, 0, Math.min(n, MAX_SHELL_OUT - buf.size()));
                            break;
                        }
                        buf.write(tmp, 0, n);
                    }
                } catch (IOException ignored) {}
            }, "adb-shell-out");
            reader.setDaemon(true);
            reader.start();
            reader.join(SHELL_JOIN_MS);
            if (reader.isAlive()) {
                reader.interrupt();
                throw new IOException("shell timed out");
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
            }
        }
    }

    public long push(InputStream src, String remotePath) throws Exception {
        AdbStream sync = adb.openSync();
        try {
            int mtime = (int) (System.currentTimeMillis() / 1000L);
            return Sync.push(src, sync.openOutputStream(), sync.openInputStream(),
                    remotePath, FILE_MODE, mtime);
        } finally {
            try { sync.close(); } catch (IOException ignored) {}
        }
    }

    public long pull(String remotePath, OutputStream dst) throws Exception {
        AdbStream sync = adb.openSync();
        try {
            return Sync.pull(dst, sync.openOutputStream(), sync.openInputStream(), remotePath);
        } finally {
            try { sync.close(); } catch (IOException ignored) {}
        }
    }

    public List<FileEntry> listDir(String path) throws Exception {
        String p = normalizePath(path);
        String out = shell("ls -1p " + singleQuote(p), true);
        List<FileEntry> entries = new ArrayList<>();
        if (out == null || out.trim().isEmpty()) return entries;
        // Failure messages from ls
        String trimmed = out.trim();
        if (trimmed.contains("No such file") || trimmed.contains("Permission denied")
                || trimmed.contains("Not a directory")) {
            throw new IOException(trimmed.split("\n")[0]);
        }
        for (String line : out.split("\n")) {
            String name = line.trim();
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                    || "./".equals(name) || "../".equals(name)) continue;
            boolean dir = name.endsWith("/");
            if (dir) name = name.substring(0, name.length() - 1);
            if (name.isEmpty()) continue;
            // Strip any unexpected absolute prefix
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            if (name.isEmpty()) continue;
            entries.add(new FileEntry(name, dir));
        }
        Collections.sort(entries, (a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return entries;
    }

    public List<AppInfo> listPackages(boolean includeSystem) throws Exception {
        // -3 = third-party only; -s = system. When includeSystem we want both.
        String userOut = shell("pm list packages -3", true);
        String sysOut = includeSystem ? shell("pm list packages -s", true) : "";
        List<AppInfo> apps = new ArrayList<>();
        parsePackages(userOut, false, apps);
        if (includeSystem) parsePackages(sysOut, true, apps);
        Collections.sort(apps, (a, b) -> a.packageName.compareToIgnoreCase(b.packageName));
        return apps;
    }

    private static void parsePackages(String out, boolean system, List<AppInfo> apps) {
        if (out == null) return;
        for (String line : out.split("\n")) {
            Matcher m = PKG.matcher(line.trim());
            if (m.find()) apps.add(new AppInfo(m.group(1), system));
        }
    }

    public String installApk(String remotePath) throws Exception {
        return shell("pm install -r " + singleQuote(remotePath), true).trim();
    }

    public DisplaySize readSize() throws Exception {
        String out = shell("wm size", true);
        int pw = 0, ph = 0, cw = 0, ch = 0;
        boolean override = false;
        for (String line : out.split("\n")) {
            String t = line.trim();
            Matcher m = SIZE.matcher(t);
            if (!m.find()) continue;
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            if (t.toLowerCase(Locale.ROOT).contains("override")) {
                cw = w; ch = h; override = true;
            } else if (t.toLowerCase(Locale.ROOT).contains("physical") || pw == 0) {
                pw = w; ph = h;
            }
        }
        if (pw <= 0 || ph <= 0) throw new IOException("cannot parse wm size: " + out.trim());
        if (!override) { cw = pw; ch = ph; }
        return new DisplaySize(pw, ph, cw, ch, override);
    }

    public DisplayDensity readDensity() throws Exception {
        String out = shell("wm density", true);
        int physical = 0, current = 0;
        boolean override = false;
        for (String line : out.split("\n")) {
            String t = line.trim();
            Matcher m = DENSITY.matcher(t);
            if (!m.find()) continue;
            int v = Integer.parseInt(m.group(1));
            if (t.toLowerCase(Locale.ROOT).contains("override")) {
                current = v; override = true;
            } else if (t.toLowerCase(Locale.ROOT).contains("physical") || physical == 0) {
                physical = v;
            }
        }
        if (physical <= 0) throw new IOException("cannot parse wm density: " + out.trim());
        if (!override) current = physical;
        return new DisplayDensity(physical, current, override);
    }

    public String applySize(int w, int h) throws Exception {
        if (w < 200 || h < 200 || w > 8192 || h > 8192) {
            throw new IllegalArgumentException("size out of range");
        }
        return shell("wm size " + w + "x" + h, true).trim();
    }

    public String resetSize() throws Exception {
        return shell("wm size reset", true).trim();
    }

    public String applyDensity(int dpi) throws Exception {
        if (dpi < 72 || dpi > 640) throw new IllegalArgumentException("dpi out of range");
        return shell("wm density " + dpi, true).trim();
    }

    public String resetDensity() throws Exception {
        return shell("wm density reset", true).trim();
    }

    // Persistent interactive shell for the tablet terminal pane.
    public ShellSession openShellSession(ShellSession.Listener listener) throws Exception {
        return new ShellSession(adb, listener);
    }

    public static final class ShellSession implements Closeable {
        public interface Listener {
            void onOutput(String chunk);
            void onClosed(String reason);
        }

        private final AdbStream stream;
        private final OutputStream stdin;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread reader;

        ShellSession(Adb adb, Listener listener) throws Exception {
            if (listener == null) throw new IllegalArgumentException("listener");
            stream = adb.openInteractiveShell();
            stdin = stream.openOutputStream();
            AdbStream ref = stream;
            reader = new Thread(() -> {
                try (InputStream in = ref.openInputStream()) {
                    byte[] tmp = new byte[4096];
                    int n;
                    while ((n = in.read(tmp)) >= 0) {
                        if (n == 0) continue;
                        listener.onOutput(new String(tmp, 0, n, StandardCharsets.UTF_8));
                    }
                    if (!closed.get()) listener.onClosed(null);
                } catch (IOException e) {
                    if (!closed.get()) {
                        String m = e.getMessage();
                        listener.onClosed(m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
                    }
                }
            }, "adb-shell-session");
            reader.setDaemon(true);
            reader.start();
        }

        public boolean isOpen() {
            return !closed.get();
        }

        public void write(String text) throws IOException {
            if (text == null || text.isEmpty()) return;
            writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }

        public void writeBytes(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length == 0) return;
            if (closed.get()) throw new IOException("shell session closed");
            synchronized (stdin) {
                stdin.write(bytes);
                stdin.flush();
            }
        }

        /** Send ASCII Ctrl-C (0x03) to interrupt the foreground remote process. */
        public void sendInterrupt() throws IOException {
            writeBytes(new byte[]{0x03});
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { stdin.close(); } catch (IOException ignored) {}
            try { stream.close(); } catch (IOException ignored) {}
            try { reader.interrupt(); } catch (Exception ignored) {}
        }
    }

    public static String joinPath(String dir, String name) {
        String d = normalizePath(dir);
        if (name == null || name.isEmpty()) return d;
        if ("/".equals(d)) return "/" + name;
        return d + "/" + name;
    }

    public static String parentPath(String path) {
        String p = normalizePath(path);
        if ("/".equals(p)) return "/";
        int i = p.lastIndexOf('/');
        if (i <= 0) return "/";
        return p.substring(0, i);
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String p = path.replace('\\', '/').trim();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    public static String singleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
