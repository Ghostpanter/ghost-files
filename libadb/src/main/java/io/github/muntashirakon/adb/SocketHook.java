package io.github.muntashirakon.adb;

import java.io.IOException;
import java.net.Socket;

/**
 * LOCAL PATCH: per-thread hook run on every plain TCP socket (connect + pairing) right before
 * {@code connect()}. Used to bind the socket to a specific android.net.Network (e.g. Wi‑Fi) so a
 * full-tunnel VPN cannot swallow traffic to the device's own LAN address. Thread-local so a mirror
 * or file-browser connection on another thread is never affected.
 */
public final class SocketHook {
    public interface Configurer {
        void configure(Socket socket) throws IOException;
    }

    private static final ThreadLocal<Configurer> CURRENT = new ThreadLocal<>();

    private SocketHook() {}

    public static void set(Configurer c) {
        if (c == null) CURRENT.remove(); else CURRENT.set(c);
    }

    public static void clear() { CURRENT.remove(); }

    static void apply(Socket socket) throws IOException {
        Configurer c = CURRENT.get();
        if (c != null) c.configure(socket);
    }
}
