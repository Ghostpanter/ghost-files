package com.ghostpanter.files.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Epoch millis, 0 when unknown. */
    val lastModified: Long,
    val isLink: Boolean = false,
    /** ls-style string such as "drwxr-xr-x" when the backend knows it. */
    val permissions: String? = null,
    val owner: String? = null,
    val group: String? = null,
) {
    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = if (isDirectory) "" else VPath.extension(name)
}

enum class Capability {
    /** openRead(offset > 0) is efficient (no re-download). */
    RESUME_READ,
    /** openWrite(offset > 0) appends/continues an existing partial file. */
    RESUME_WRITE,
    /** serverCopy() can copy without streaming bytes through the phone. */
    SERVER_COPY,
    /** rename() can move across directories on the same backend. */
    RENAME_MOVE,
    CHMOD,
    /** Backend reads hidden/system areas the app itself cannot (root/Shizuku). */
    ELEVATED,
    REMOTE,
}

open class FsException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Blocking file-system abstraction. Every call may block on I/O and must be
 * invoked off the main thread. Implementations must be safe for concurrent
 * use from several threads (UI listing while transfers run).
 */
interface VirtualFs : Closeable {
    val id: String
    val displayName: String
    val capabilities: Set<Capability>

    /** The path a fresh tab should open at. */
    val homePath: String get() = VPath.ROOT

    /**
     * Lists [path]. Large directories may be delivered incrementally through
     * [onChunk] before the full list is returned.
     */
    fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)? = null): List<FileEntry>

    /** Returns null when the path does not exist. */
    fun stat(path: String): FileEntry?

    fun openRead(path: String, offset: Long = 0): InputStream

    /**
     * offset == 0 truncates/creates; offset > 0 continues (needs RESUME_WRITE).
     * [sizeHint] is the final total size when known (lets HTTP use a fixed
     * Content-Length instead of chunked uploads).
     */
    fun openWrite(path: String, offset: Long = 0, sizeHint: Long = -1): OutputStream

    fun mkdirs(path: String)

    fun delete(path: String, recursive: Boolean)

    /** Rename/move inside this backend. */
    fun rename(from: String, to: String)

    /** Copy without streaming through the device. Returns false if not possible. */
    fun serverCopy(from: String, to: String): Boolean = false

    fun chmod(path: String, mode: String, recursive: Boolean = false): Unit =
        throw FsException("chmod not supported on $displayName")

    fun setLastModified(path: String, millis: Long) {}

    /** Drop pooled connections so the next call reconnects (after errors). */
    fun reset() {}

    override fun close() {}

    fun exists(path: String): Boolean = stat(path) != null

    /**
     * Random-access read used for media probing (video thumbnails, moov-at-end MP4): reads up to [len] bytes at
     * [offset] into [buf]. Returns the number of bytes read, or -1 at EOF. Default: a fresh [openRead] at offset.
     */
    fun readAt(path: String, offset: Long, buf: ByteArray, off: Int, len: Int): Int =
        openRead(path, offset).use { input ->
            var n = 0
            while (n < len) { val r = input.read(buf, off + n, len - n); if (r < 0) break; n += r }
            if (n == 0 && len > 0) -1 else n
        }

    /**
     * How long a single blocking call may legitimately take before the transfer watchdog treats
     * the stream as dead (0 = use the engine default). Lets backends with long per-request
     * timeouts (SMB) avoid being cut off mid-request.
     */
    val stallHintMs: Long get() = 0

    fun has(c: Capability) = c in capabilities
}

/** A stream that can be torn down from another thread faster/more thoroughly than close(). */
interface Abortable {
    fun abort()
}

/**
 * A stream that heals itself (reconnect + resume at the same offset). [onRecovery] gets the cause
 * when a reconnect starts and null once it succeeded; the engine counts these separately from
 * job retries and treats them as activity for its watchdog.
 */
interface RecoveringStream {
    var onRecovery: ((cause: Throwable?, attempt: Int) -> Unit)?
}
