package com.ghostpanter.scrcpy;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

// Catches uncaught exceptions on any thread, writes them as plain
// text to <externalFilesDir>/crash-<timestamp>.log, then chains to
// the system default handler so the OS crash dialog still appears.
// Testers can share the file from the app's storage instead of
// fishing for it in adb logcat.
public final class Crashlog {

    private static final int MAX_LOGS = 5;

    private Crashlog() {}

    public static void install(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            Log.w("crashlog: no external storage, skipping install");
            return;
        }
        try {
            prune(dir, MAX_LOGS);
        } catch (IOException e) {
            Log.w("crashlog: prune failed: %s", e);
        }
        Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",
                        Locale.ROOT).format(new Date());
                File out = File.createTempFile("crash-" + ts + "-", ".log", dir);
                try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
                    pw.println("# " + new Date());
                    pw.println("# thread=" + t.getName());
                    pw.println();
                    e.printStackTrace(pw);
                }
                prune(dir, MAX_LOGS);
                Log.e(e, "crashlog: wrote %s", out.getAbsolutePath());
            } catch (Throwable ignored) {
                // best effort - do not mask the original crash
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
        Log.i("crashlog: installed -> %s", dir.getAbsolutePath());
    }

    static void prune(File dir, int keep) throws IOException {
        if (keep < 0) throw new IllegalArgumentException("negative retention");
        File[] logs = dir.listFiles((parent, name) ->
                name.startsWith("crash-") && name.endsWith(".log"));
        if (logs == null) throw new IOException("cannot list crashlog directory: " + dir);
        Arrays.sort(logs, (left, right) -> {
            int modified = Long.compare(right.lastModified(), left.lastModified());
            return modified != 0 ? modified : right.getName().compareTo(left.getName());
        });
        for (int i = keep; i < logs.length; i++) {
            Files.deleteIfExists(logs[i].toPath());
        }
    }
}
