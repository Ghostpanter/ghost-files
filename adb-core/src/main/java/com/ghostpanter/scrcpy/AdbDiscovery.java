package com.ghostpanter.scrcpy;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// Blocking mDNS helpers for Wireless debugging pairing / connect endpoints.
// Patterns mirror QrPair's NSD usage; callers should hold NEARBY_WIFI_DEVICES
// on API 33+ when required by the platform.
public final class AdbDiscovery {

    public static final String PAIRING_TYPE = "_adb-tls-pairing._tcp.";
    public static final String CONNECT_TYPE = "_adb-tls-connect._tcp.";

    public static final class Endpoint {
        public final String host;
        public final int port;
        public final String serviceName;

        public Endpoint(String host, int port, String serviceName) {
            this.host = host;
            this.port = port;
            this.serviceName = serviceName;
        }

        @Override
        public String toString() {
            String base = host.indexOf(':') < 0
                    ? host + ":" + port
                    : "[" + host + "]:" + port;
            return serviceName == null || serviceName.isEmpty()
                    ? base
                    : base + " (" + serviceName + ")";
        }
    }

    private AdbDiscovery() {}

    // Discover pairing servers until timeoutMs elapses. Returns all unique
    // host:port endpoints resolved in that window (may be empty).
    public static List<Endpoint> discoverPairing(Context ctx, long timeoutMs)
            throws InterruptedException {
        return discoverAll(ctx, PAIRING_TYPE, null, timeoutMs, /*stopOnFirst*/ false);
    }

    // Discover a connect endpoint. If preferredHost is non-null, prefer the
    // first resolved service whose host matches; otherwise return the first
    // resolved connect endpoint. Returns null if none found before timeout.
    public static Endpoint discoverConnect(Context ctx, String preferredHost, long timeoutMs)
            throws InterruptedException {
        List<Endpoint> found = discoverAll(ctx, CONNECT_TYPE, preferredHost, timeoutMs,
                /*stopOnFirst*/ true);
        return found.isEmpty() ? null : found.get(0);
    }

    // Discover _adb-tls-connect._tcp for a specific host (already paired).
    // Collects matching endpoints for the full timeout window, logs all, and
    // returns the one with the highest port (or the first if tied / single).
    // Returns null if none match before timeout.
    public static Endpoint discoverConnectForHost(Context ctx, String host, long timeoutMs)
            throws InterruptedException {
        if (host == null || host.isEmpty()) return null;
        List<Endpoint> found = discoverAll(ctx, CONNECT_TYPE, host, timeoutMs,
                /*stopOnFirst*/ false);
        if (found.isEmpty()) {
            Log.i("adb discovery: no connect endpoint for host=%s", host);
            return null;
        }
        for (Endpoint ep : found) {
            Log.i("adb discovery connect candidate for %s: %s", host, ep);
        }
        Endpoint best = found.get(0);
        for (int i = 1; i < found.size(); i++) {
            Endpoint ep = found.get(i);
            if (ep.port > best.port) best = ep;
        }
        if (found.size() > 1) {
            Log.i("adb discovery: %d connect endpoints for %s — prefer highest port %s",
                    found.size(), host, best);
        }
        return best;
    }

    private static List<Endpoint> discoverAll(
            Context ctx,
            String serviceType,
            String preferredHost,
            long timeoutMs,
            boolean stopOnFirst) throws InterruptedException {
        if (timeoutMs <= 0) return Collections.emptyList();
        Context app = ctx.getApplicationContext();
        NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            Log.w("adb discovery: NSD unavailable");
            return Collections.emptyList();
        }

        WifiManager.MulticastLock multicast = null;
        try {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("scrcpy-adb-discovery");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        } catch (Exception e) {
            Log.w("adb discovery: multicast lock failed: %s", e);
        }

        CountDownLatch done = new CountDownLatch(1);
        Map<String, Endpoint> found = Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicReference<NsdManager.DiscoveryListener> listenerRef = new AtomicReference<>();
        final WifiManager.MulticastLock multicastFinal = multicast;

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {
                Log.w("adb discovery start failed type=%s err=%d", t, e);
                done.countDown();
            }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {
                Log.i("adb discovery started: %s", t);
            }
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                String name = info.getServiceName();
                Log.i("adb discovery found: %s type=%s", name, serviceType);
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                            Log.w("adb discovery resolve failed: %s err=%d", name, errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            Endpoint ep = endpointOf(resolved);
                            if (ep == null) return;
                            if (preferredHost != null
                                    && !hostsMatch(preferredHost, ep.host)
                                    && !hostsMatch(preferredHost, hostnameOf(resolved))) {
                                Log.i("adb discovery skip host=%s name=%s want=%s",
                                        ep.host, hostnameOf(resolved), preferredHost);
                                return;
                            }
                            String key = ep.host + "|" + ep.port;
                            found.put(key, ep);
                            Log.i("adb discovery resolved %s", ep);
                            if (stopOnFirst) done.countDown();
                        }
                    });
                } catch (Exception e) {
                    Log.w("adb discovery resolve submit failed: %s", e);
                }
            }
        };
        listenerRef.set(listener);

        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {}
            if (multicastFinal != null && multicastFinal.isHeld()) {
                try { multicastFinal.release(); } catch (Exception ignored) {}
            }
        }

        synchronized (found) {
            return new ArrayList<>(found.values());
        }
    }

    static Endpoint endpointOf(NsdServiceInfo info) {
        if (info == null) return null;
        String host = hostOf(info);
        int port = info.getPort();
        if (host == null || host.isEmpty() || port < 1 || port > 65535) return null;
        return new Endpoint(host, port, info.getServiceName());
    }

    @SuppressWarnings("deprecation")
    static String hostOf(NsdServiceInfo info) {
        if (Build.VERSION.SDK_INT >= 34) {
            var addrs = info.getHostAddresses();
            if (addrs != null) {
                for (InetAddress addr : addrs) {
                    if (addr == null) continue;
                    String h = addr.getHostAddress();
                    if (h != null && !h.isEmpty()) return stripZoneId(h);
                }
            }
        }
        InetAddress host = info.getHost();
        if (host == null) return null;
        String h = host.getHostAddress();
        return h == null ? null : stripZoneId(h);
    }

    // Host name when NSD returns one (may differ from numeric hostOf).
    @SuppressWarnings("deprecation")
    static String hostnameOf(NsdServiceInfo info) {
        if (info == null) return null;
        try {
            InetAddress host = info.getHost();
            if (host == null) return null;
            String name = host.getHostName();
            if (name == null || name.isEmpty()) return null;
            // Prefer skipping pure numeric addresses already covered by hostOf.
            if (normalizeIpv4(name) != null) return null;
            if (name.indexOf(':') >= 0) return null; // IPv6 literal
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    // Match hosts: exact (ignore case / zone id), normalized IPv4, or DNS resolve.
    static boolean hostsMatch(String a, String b) {
        if (a == null || b == null) return false;
        a = stripZoneId(a.trim());
        b = stripZoneId(b.trim());
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equalsIgnoreCase(b)) return true;

        String na = normalizeIpv4(a);
        String nb = normalizeIpv4(b);
        if (na != null && nb != null) return na.equals(nb);

        try {
            InetAddress ia = InetAddress.getByName(a);
            InetAddress ib = InetAddress.getByName(b);
            if (ia.equals(ib)) return true;
            String ha = ia.getHostAddress();
            String hb = ib.getHostAddress();
            if (ha != null && hb != null
                    && stripZoneId(ha).equalsIgnoreCase(stripZoneId(hb))) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // "192.168.001.010" → "192.168.1.10"; null if not a dotted IPv4.
    static String normalizeIpv4(String s) {
        if (s == null) return null;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') return null;
            }
            int v;
            try {
                v = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
            if (v < 0 || v > 255) return null;
            o[i] = v;
        }
        return o[0] + "." + o[1] + "." + o[2] + "." + o[3];
    }

    // True if s is a usable IPv4 dotted quad (after normalize).
    public static boolean isIpv4(String s) {
        return normalizeIpv4(s == null ? null : s.trim()) != null;
    }

    private static String stripZoneId(String host) {
        int pct = host.indexOf('%');
        return pct < 0 ? host : host.substring(0, pct);
    }
}
