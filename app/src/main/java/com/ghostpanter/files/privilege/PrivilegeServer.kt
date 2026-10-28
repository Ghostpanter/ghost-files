package com.ghostpanter.files.privilege

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.ghostpanter.files.core.NioListing
import com.ghostpanter.files.core.privilege.acceptCallingUid
import java.io.File
import kotlin.concurrent.thread

/**
 * Entry point for `app_process … PrivilegeServer <package> <uid>`.
 * Runs as shell (or root), exposes [IPrivilegeService], and hands the binder back to the app
 * through [PrivilegeBinderProvider].
 */
object PrivilegeServer {
    const val VERSION = 1
    private const val TAG = "ghost-priv"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("$TAG usage: PrivilegeServer <packageName> <appUid>")
            System.exit(2)
        }
        val pkg = args[0]
        val appUid = args[1].toIntOrNull() ?: run {
            System.err.println("$TAG bad uid"); System.exit(2); return
        }
        log("start pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT} pkg=$pkg appUid=$appUid")
        prepareLooper()
        val stub = PrivilegeService(appUid)
        // Retry: the provider can be briefly unavailable (app cold-starting, ROM freezing cached apps).
        var sent = false
        for (attempt in 1..15) {
            try {
                BinderSender.send(pkg, PrivilegeBinderProvider.AUTHORITY_SUFFIX, stub, appUid / 100_000)
                log("binder sent to $pkg (attempt $attempt)")
                sent = true
                break
            } catch (t: Throwable) {
                log("send binder failed (attempt $attempt): ${describe(t)}")
                if (attempt == 1) unwrap(t).printStackTrace()
                Thread.sleep(if (attempt < 5) 500L else 2_000L)
            }
        }
        if (!sent) {
            log("giving up: provider never accepted the binder")
            System.exit(1)
        }
        // Block forever (binder threads serve calls).
        try {
            while (true) Thread.sleep(60_000)
        } catch (_: InterruptedException) {
            System.exit(0)
        }
    }

    private fun log(msg: String) {
        System.err.println("$TAG $msg")
        System.err.flush()
    }

    internal fun unwrap(t: Throwable): Throwable {
        var c = t
        while ((c is java.lang.reflect.InvocationTargetException || c is java.lang.reflect.UndeclaredThrowableException) && c.cause != null) c = c.cause!!
        return c
    }

    internal fun describe(t: Throwable): String {
        val c = unwrap(t)
        return c.javaClass.name + ": " + (c.message ?: "")
    }

    private fun prepareLooper() {
        // No ActivityThread.systemMain(): it builds a *system* context, which a shell-uid process cannot
        // do cleanly on many ROMs. Binder threads are already started by app_process.
        runCatching { android.os.Looper.prepareMainLooper() }
            .onFailure { log("prepareMainLooper: ${describe(it)}") }
    }
}

internal class PrivilegeService(private val expectedUid: Int) : IPrivilegeService.Stub() {
    private fun guard() {
        val uid = Binder.getCallingUid()
        if (!acceptCallingUid(uid, expectedUid)) {
            throw SecurityException("uid $uid not allowed (want $expectedUid)")
        }
    }

    override fun destroy() {
        guard()
        thread(isDaemon = true) { Thread.sleep(50); System.exit(0) }
    }

    override fun version(): Int { guard(); return PrivilegeServer.VERSION }

    override fun ownerUid(): Int { guard(); return expectedUid }

    private fun pipe(text: () -> String): ParcelFileDescriptor {
        val (r, w) = ParcelFileDescriptor.createPipe()
        thread(isDaemon = true, name = "priv-pipe") {
            ParcelFileDescriptor.AutoCloseOutputStream(w).use { o ->
                val s = runCatching(text).getOrElse { "${NioListing.ERR}\t" + (it.message ?: it.javaClass.simpleName) }
                o.write(s.toByteArray(Charsets.UTF_8))
            }
        }
        return r
    }

    override fun list(path: String): ParcelFileDescriptor { guard(); return pipe { NioListing.list(path) } }

    override fun stat(path: String): String {
        guard()
        return runCatching { NioListing.stat(path) }.getOrElse { "${NioListing.ERR}\t${it.message}" }
    }

    override fun openRead(path: String): ParcelFileDescriptor? {
        guard()
        return runCatching { ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    }

    override fun openWrite(path: String, offset: Long): ParcelFileDescriptor? {
        guard()
        return runCatching {
            val f = File(path)
            f.parentFile?.mkdirs()
            if (offset > 0) {
                java.io.RandomAccessFile(f, "rw").use { it.setLength(offset) }
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND)
            } else ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }.getOrNull()
    }

    override fun mkdirs(path: String): String {
        guard()
        val f = File(path)
        return if (f.isDirectory || f.mkdirs() || f.isDirectory) "" else "mkdir failed: $path"
    }

    override fun remove(path: String, recursive: Boolean): String {
        guard()
        return NioListing.delete(File(path), recursive) ?: ""
    }

    override fun rename(from: String, to: String): String {
        guard()
        val a = File(from); val b = File(to)
        if (b.exists() || java.nio.file.Files.isSymbolicLink(b.toPath())) return "EEXIST"
        return if (a.renameTo(b)) "" else "FALLBACK"
    }

    override fun exec(script: String): String {
        guard()
        if (script.isEmpty() || script.length > 100_000) return "error: bad script"
        return runCatching {
            val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor()
            if (out.length > 512_000) out.substring(0, 512_000) else out
        }.getOrElse { "error: ${it.message}" }
    }
}

/**
 * Sends an IBinder to the app's [PrivilegeBinderProvider] via ActivityManager.getContentProviderExternal
 * + ContentProvider.call (same pattern Shizuku uses).
 */
internal object BinderSender {
    private const val METHOD = PrivilegeBinderProvider.METHOD_SEND
    private const val KEY = PrivilegeBinderProvider.KEY_BINDER

    fun send(packageName: String, authoritySuffix: String, binder: IBinder, userId: Int = 0) {
        val authority = "$packageName.$authoritySuffix"
        val am = activityManager()
        val token = Binder()
        val holder = getContentProviderExternal(am, authority, userId, token)
        try {
            val provider = providerFromHolder(holder)
                ?: throw IllegalStateException("provider field null")
            val extras = android.os.Bundle()
            extras.putBinder(KEY, binder)
            callProvider(provider, authority, extras)
        } finally {
            runCatching { removeContentProviderExternal(am, authority, token) }
        }
    }

    private fun activityManager(): Any {
        // ActivityManager.getService() since API 26; fall back to ActivityManagerNative.getDefault().
        runCatching {
            val c = Class.forName("android.app.ActivityManager")
            return c.getDeclaredMethod("getService").invoke(null)!!
        }
        val c = Class.forName("android.app.ActivityManagerNative")
        return c.getDeclaredMethod("getDefault").invoke(null)!!
    }

    private fun getContentProviderExternal(am: Any, authority: String, userId: Int, token: IBinder): Any {
        val methods = am.javaClass.methods.filter { it.name == "getContentProviderExternal" }
        if (methods.isEmpty()) throw IllegalStateException("IActivityManager.getContentProviderExternal missing")
        var last: Throwable? = null
        for (m in methods.sortedByDescending { it.parameterTypes.size }) {
            val r = runCatching {
                when (m.parameterTypes.size) {
                    4 -> m.invoke(am, authority, userId, token, "ghost-priv")
                    3 -> m.invoke(am, authority, userId, token)
                    else -> null
                }
            }
            r.exceptionOrNull()?.let { last = it }
            r.getOrNull()?.let { return it }
        }
        throw IllegalStateException(
            "provider $authority (user $userId) unavailable" +
                (last?.let { " — " + PrivilegeServer.describe(it) } ?: " — returned null (app not installed for this user / provider disabled?)"),
        )
    }

    private fun removeContentProviderExternal(am: Any, authority: String, token: IBinder) {
        val methods = am.javaClass.methods.filter { it.name.startsWith("removeContentProviderExternal") }
        for (m in methods) {
            val ok = runCatching {
                when (m.parameterTypes.size) {
                    2 -> m.invoke(am, authority, token)
                    3 -> m.invoke(am, authority, token, 0)
                    else -> null
                }
            }.isSuccess
            if (ok) return
        }
    }

    private fun providerFromHolder(holder: Any): Any? {
        // ContentProviderHolder.provider (IContentProvider)
        val f = holder.javaClass.fields.firstOrNull { it.name == "provider" }
            ?: holder.javaClass.declaredFields.firstOrNull { it.name == "provider" }?.also { it.isAccessible = true }
        return f?.get(holder)
    }

    private fun callProvider(provider: Any, authority: String, extras: android.os.Bundle) {
        val methods = provider.javaClass.methods.filter { it.name == "call" }
        var last: Throwable? = null
        for (m in methods.sortedByDescending { it.parameterTypes.size }) {
            val attempt = runCatching { m.invoke(provider, *adjustArgs(m, authority, extras)) }
            if (attempt.isSuccess) {
                val reply = attempt.getOrNull() as? android.os.Bundle
                if (reply != null && !reply.getBoolean("ok", true)) throw IllegalStateException("provider rejected the binder")
                return
            }
            last = attempt.exceptionOrNull()
        }
        throw last ?: IllegalStateException("no working ContentProvider.call overload")
    }

    private fun callerPackage(): String =
        if (android.os.Process.myUid() == 0) "root" else "com.android.shell"

    /** API 31+: build an AttributionSource for shell/root; prefer the public Builder. */
    private fun attributionSource(cls: Class<*>): Any {
        val uid = android.os.Process.myUid()
        runCatching {
            val b = Class.forName("android.content.AttributionSource\$Builder")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(uid)
            b.javaClass.getMethod("setPackageName", String::class.java).invoke(b, callerPackage())
            return b.javaClass.getMethod("build").invoke(b)!!
        }
        return cls.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .newInstance(uid, callerPackage(), null)
    }

    private fun adjustArgs(m: java.lang.reflect.Method, authority: String, extras: android.os.Bundle): Array<Any?> {
        val pts = m.parameterTypes
        return when (pts.size) {
            4 -> arrayOf(callerPackage(), METHOD, null, extras)
            5 -> {
                if (pts[0].name.contains("AttributionSource")) {
                    arrayOf(attributionSource(pts[0]), authority, METHOD, null, extras)
                } else {
                    // callingPkg, authority, method, arg, extras
                    arrayOf(callerPackage(), authority, METHOD, null, extras)
                }
            }
            6 -> arrayOf(callerPackage(), null, authority, METHOD, null, extras)
            else -> throw IllegalArgumentException("unsupported call arity ${pts.size}")
        }
    }
}
