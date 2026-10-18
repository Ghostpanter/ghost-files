package com.ghostpanter.scrcpy;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

// Single static wrapper around android.util.Log so every line in this app
// uses the same tag. Also keeps an in-memory ring buffer so Settings → 日志
// can show elevate / ADB / su / server transcripts (toasts truncate).
//
// Filter logcat with `adb logcat -s scrcpy-android`.
// String.format always uses Locale.ROOT so logs are unaffected by device
// locales that use comma decimals or other surprises.
public final class Log {
    public static final String TAG = "scrcpy-android";

    public enum Level {
        VERBOSE(2, "V"),
        DEBUG(3, "D"),
        INFO(4, "I"),
        WARN(5, "W"),
        ERROR(6, "E");

        final int priority;
        final String label;

        Level(int priority, String label) {
            this.priority = priority;
            this.label = label;
        }

        static Level from(int priority) {
            if (priority >= ERROR.priority) return ERROR;
            if (priority >= WARN.priority) return WARN;
            if (priority >= INFO.priority) return INFO;
            if (priority >= DEBUG.priority) return DEBUG;
            return VERBOSE;
        }
    }

    private static final int CAPACITY = 3000;
    private static final ArrayDeque<Entry> RING = new ArrayDeque<>(CAPACITY);
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private Log() {}

    public static void v(String fmt, Object... a) { log(Level.VERBOSE, fmt(fmt, a), null); }
    public static void d(String fmt, Object... a) { log(Level.DEBUG, fmt(fmt, a), null); }
    public static void i(String fmt, Object... a) { log(Level.INFO, fmt(fmt, a), null); }
    public static void w(String fmt, Object... a) { log(Level.WARN, fmt(fmt, a), null); }
    public static void e(String fmt, Object... a) { log(Level.ERROR, fmt(fmt, a), null); }

    public static void e(Throwable t, String fmt, Object... a) {
        log(Level.ERROR, fmt(fmt, a), t);
    }

    /** Snapshot lines at or above minLevel, oldest first. */
    public static String dump(Level minLevel) {
        if (minLevel == null) minLevel = Level.VERBOSE;
        StringBuilder sb = new StringBuilder(16 * 1024);
        synchronized (LOCK) {
            for (Entry e : RING) {
                if (e.level.priority < minLevel.priority) continue;
                sb.append(e.line).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return RING.size();
        }
    }

    private static void log(Level level, String msg, Throwable t) {
        final String line;
        synchronized (LOCK) {
            line = TS.format(new Date()) + ' ' + level.label + ' ' + msg;
            if (RING.size() >= CAPACITY) RING.removeFirst();
            RING.addLast(new Entry(level, line));
        }
        switch (level) {
            case VERBOSE:
                android.util.Log.v(TAG, msg, t);
                break;
            case DEBUG:
                android.util.Log.d(TAG, msg, t);
                break;
            case INFO:
                android.util.Log.i(TAG, msg, t);
                break;
            case WARN:
                android.util.Log.w(TAG, msg, t);
                break;
            case ERROR:
                android.util.Log.e(TAG, msg, t);
                break;
        }
    }

    private static String fmt(String fmt, Object... a) {
        return a.length == 0 ? fmt : String.format(Locale.ROOT, fmt, a);
    }

    private static final class Entry {
        final Level level;
        final String line;

        Entry(Level level, String line) {
            this.level = level;
            this.line = line;
        }
    }
}
