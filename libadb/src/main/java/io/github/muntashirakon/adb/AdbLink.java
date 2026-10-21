// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * LOCAL ADDITION: a non-socket ADB byte link (USB host bulk endpoints). The reader asks for exact
 * lengths (24-byte header, then the payload), which matches how adbd sends one USB transfer per
 * header / payload. {@link #writePacket} receives one whole message (header + payload) and may
 * split it into separate transfers. Links never negotiate TLS.
 */
public interface AdbLink extends Closeable {
    @NonNull
    InputStream getInputStream();

    void writePacket(@NonNull byte[] packet) throws IOException;

    boolean isClosed();

    /** Peer allows connecting without AUTH (adbd with ro.adb.secure=0). */
    default boolean allowsNoAuth() { return true; }
}
