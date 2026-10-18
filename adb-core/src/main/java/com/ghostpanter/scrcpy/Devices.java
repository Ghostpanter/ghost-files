package com.ghostpanter.scrcpy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Tiny JSON-backed list of paired targets, stored at filesDir/devices.json.
// No SQLite, no Room. Read on demand, written whole-file on add/remove.
//
// parse() / serialize() are pure-java and android-free for unit tests.
// load() / save() add the Context-rooted file I/O.
public final class Devices {

    private static final String FILE = "devices.json";
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_HOST_CHARS = 255;

    public static final class Device {
        public final String host;
        public final int    port;

        public Device(String host, int port) {
            this.host = host;
            this.port = port;
        }

        // Bracket IPv6 literals so the port stays unambiguous. Round-trips
        // through parseAddress().
        @Override
        public String toString() {
            return host.indexOf(':') < 0
                    ? host + ":" + port
                    : "[" + host + "]:" + port;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Device d)) return false;
            return port == d.port && host.equals(d.host);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }
    }

    private Devices() {}

    // Parse "host:port" exactly as the target's Wireless debugging screen
    // prints it. IPv6 literals must be bracketed ("[fe80::1]:5555") because
    // the address itself contains colons; an unbracketed one is rejected
    // rather than silently split at the wrong colon. Returns null on
    // anything malformed - the only caller is a text field.
    public static Device parseAddress(String s) {
        if (s == null) return null;
        s = s.trim();
        String host, port;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0 || !s.startsWith("]:", end)) return null;
            host = s.substring(1, end);
            port = s.substring(end + 2);
        } else {
            int colon = s.indexOf(':');
            if (colon < 0 || colon != s.lastIndexOf(':')) return null;
            host = s.substring(0, colon);
            port = s.substring(colon + 1);
        }
        int p = parsePort(port);
        if (!validHost(host) || p < 0) return null;
        return new Device(host, p);
    }

    // Direct-connect field: "host:port" as parseAddress, or a bare host /
    // IPv4 which gets defaultPort (5555 for legacy `adb tcpip`).
    public static Device parseDirect(String s, int defaultPort) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        Device d = parseAddress(t);
        if (d != null) return d;
        if (t.indexOf(':') >= 0 || !validHost(t)) return null;
        if (defaultPort < 1 || defaultPort > 65535) return null;
        return new Device(t, defaultPort);
    }

    // Digits only, 1-65535. Returns -1 if it is not a usable port.
    // Integer.parseInt() alone would accept "+5555" and " 5555".
    public static int parsePort(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty() || s.length() > 5) return -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;
        }
        int p = Integer.parseInt(s);
        return p >= 1 && p <= 65535 ? p : -1;
    }

    // Pure-java parse: returns whatever rows are well-formed; logs and
    // skips anything malformed instead of nuking the list.
    public static List<Device> parse(String json) {
        List<Device> out = new ArrayList<>();
        if (json == null) return out;
        json = json.trim();
        if (json.isEmpty()) return out;
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("devices: expected a JSON array", e);
        }

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String host = o.getString("host");
                Object portValue = o.get("port");
                if (!(portValue instanceof Integer) && !(portValue instanceof Long)) {
                    throw new IllegalArgumentException("port is not an integer");
                }
                long portLong = ((Number) portValue).longValue();
                if (portLong < 1 || portLong > 65535) {
                    throw new IllegalArgumentException("port is invalid");
                }
                int port = (int) portLong;
                if (!validHost(host) || port < 1 || port > 65535) {
                    throw new IllegalArgumentException("invalid device fields");
                }
                out.add(new Device(host, port));
            } catch (Exception e) {
                Log.w("devices: skipping malformed row %d: %s", i, e);
            }
        }
        return out;
    }

    public static String serialize(List<Device> devices) {
        try {
            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                if (!validHost(d.host) || d.port < 1 || d.port > 65535) {
                    throw new IllegalArgumentException("invalid saved device");
                }
                JSONObject o = new JSONObject();
                o.put("host", d.host);
                o.put("port", d.port);
                arr.put(o);
            }
            return arr.toString(2);
        } catch (Exception e) {
            throw new IllegalStateException("devices: serialize failed", e);
        }
    }

    public static List<Device> load(Context ctx) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return new ArrayList<>();
        try {
            if (f.length() > MAX_FILE_BYTES) {
                throw new IOException("device list is too large: " + f.length());
            }
            byte[] data = readLimited(f);
            if (data.length == 0) throw new IOException("device list is empty");
            return parse(Wire.decodeUtf8(data));
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("devices: load failed", e);
        }
    }

    // The saved row for an address, or null if there is none.
    public static Device find(Context ctx, String host, int port) throws IOException {
        for (Device d : load(ctx)) {
            if (d.port == port && d.host.equals(host)) return d;
        }
        return null;
    }

    private static void save(Context ctx, List<Device> devices) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        AtomicFiles.write(f, serialize(devices).getBytes(StandardCharsets.UTF_8));
    }

    // Add or replace by host+port. Context-rooted; the in-place helper
    // is the test seam.
    public static synchronized List<Device> upsert(Context ctx, Device d) throws IOException {
        List<Device> list = load(ctx);
        list.removeIf(d::equals);
        list.add(d);
        save(ctx, list);
        return list;
    }

    // Add or replace every saved row for the same host (any port). Used when
    // switching a target from an ephemeral wireless-debug port to a fixed one.
    public static synchronized List<Device> upsertHost(Context ctx, Device d) throws IOException {
        List<Device> list = load(ctx);
        list.removeIf(x -> x.host.equals(d.host));
        list.add(d);
        save(ctx, list);
        return list;
    }

    // Remove the matching device (by host+port). Returns the updated list.
    public static synchronized List<Device> remove(Context ctx, Device d) throws IOException {
        List<Device> list = load(ctx);
        list.removeIf(d::equals);
        save(ctx, list);
        return list;
    }

    private static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > MAX_HOST_CHARS) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '[' || c == ']') {
                return false;
            }
        }
        return true;
    }

    private static byte[] readLimited(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            for (;;) {
                int n = in.read(buf);
                if (n < 0) return out.toByteArray();
                if (n == 0) throw new IOException("device list read made no progress");
                total += n;
                if (total > MAX_FILE_BYTES) throw new IOException("device list is too large");
                out.write(buf, 0, n);
            }
        }
    }
}
