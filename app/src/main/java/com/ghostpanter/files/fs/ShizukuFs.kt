package com.ghostpanter.files.fs

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.ghostpanter.files.BuildConfig
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.NioListing
import com.ghostpanter.files.core.ShellFs
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import com.ghostpanter.files.t
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Binds Shizuku's UserService ([FileService]) on demand; null whenever it isn't available (callers use the shell). */
object ShizukuFileService {
    @Volatile private var svc: IFileService? = null
    @Volatile private var bound = false
    @Volatile private var downUntil = 0L
    @Volatile private var latch = CountDownLatch(1)

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, FileService::class.java.name))
            .daemon(false).processNameSuffix("fs").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = if (binder != null && binder.pingBinder()) IFileService.Stub.asInterface(binder) else null
            latch.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null; bound = false }
    }

    /** Never blocks the main thread; elsewhere waits up to 6 s for the first bind. */
    fun get(): IFileService? {
        svc?.let { s -> if (runCatching { s.asBinder().pingBinder() }.getOrDefault(false)) return s; svc = null; bound = false }
        if (System.currentTimeMillis() < downUntil || !ShizukuRunner.granted()) return null
        if (runCatching { Shizuku.getVersion() }.getOrDefault(0) < 10) { downUntil = Long.MAX_VALUE; return null }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        synchronized(this) {
            svc?.let { return it }
            if (!bound) {
                latch = CountDownLatch(1)
                val ok = runCatching { Shizuku.bindUserService(args, conn); true }.getOrDefault(false)
                if (!ok) { failed(); return null }
                bound = true
            }
            if (!latch.await(6, TimeUnit.SECONDS) || svc == null) { failed(); return null }
            return svc
        }
    }

    /** Back off for a while after a Binder error; the shell path takes over meanwhile. */
    fun failed() {
        svc = null
        if (bound) runCatching { Shizuku.unbindUserService(args, conn, true) }
        bound = false
        downUntil = System.currentTimeMillis() + 30_000
    }
}

/**
 * Shizuku file system: list / stat / read / write / mkdir / delete / rename through the UserService
 * (java.io in Shizuku's shell-uid process), everything else and every failure through [shell] (`sh` commands).
 */
class ShizukuFs(val shell: ShellFs) : VirtualFs by shell {
    private inline fun <T> svc(block: (IFileService) -> T?): T? {
        val s = ShizukuFileService.get() ?: return null
        return try { block(s) } catch (e: android.os.RemoteException) { ShizukuFileService.failed(); null } catch (e: SecurityException) { ShizukuFileService.failed(); null }
    }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> {
        val dir = VPath.normalize(path)
        val text = svc { s -> s.list(dir)?.let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) } } }
        if (text != null && text.startsWith(NioListing.OK)) {
            val r = NioListing.parseList(dir, text)
            onChunk?.invoke(r)
            return r
        }
        // ERR from the service (permission, missing) or no service: the shell gives the authoritative answer.
        return shell.list(path, onChunk)
    }

    override fun stat(path: String): FileEntry? {
        val p = VPath.normalize(path)
        val text = svc { it.stat(p) } ?: return shell.stat(path)
        return NioListing.parseStat(p, text) ?: if (text.startsWith(NioListing.ERR + "\tENOENT")) null else shell.stat(path)
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val pfd = svc { it.openRead(VPath.normalize(path)) } ?: return shell.openRead(path, offset)
        val ins = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) runCatching { ins.channel.position(offset) }.onFailure { ins.close(); return shell.openRead(path, offset) }
        return ins
    }

    override fun readAt(path: String, offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val pfd = svc { it.openRead(VPath.normalize(path)) } ?: return shell.readAt(path, offset, buf, off, len)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { s ->
            var got = 0
            while (got < len) {
                val n = s.channel.read(ByteBuffer.wrap(buf, off + got, len - got), offset + got)
                if (n < 0) break
                got += n
            }
            return if (got == 0) -1 else got
        }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        val pfd = svc { it.openWrite(VPath.normalize(path), offset) } ?: return shell.openWrite(path, offset, sizeHint)
        return ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    }

    override fun mkdirs(path: String) {
        val r = svc { it.mkdirs(VPath.normalize(path)) }
        if (r == "") return
        shell.mkdirs(path)
    }

    override fun delete(path: String, recursive: Boolean) {
        val r = svc { it.remove(VPath.normalize(path), recursive) }
        if (r == "") return
        shell.delete(path, recursive)
    }

    override fun rename(from: String, to: String) {
        when (svc { it.rename(VPath.normalize(from), VPath.normalize(to)) }) {
            "" -> return
            "EEXIST" -> throw FsException(t("目标已存在", "Target exists"))
            else -> shell.rename(from, to)
        }
    }
}

/** The shell behind a pane's file system, for shell-only operations (chown, symlink). */
fun VirtualFs.shellOrNull(): ShellFs? = (this as? ShizukuFs)?.shell ?: this as? ShellFs
