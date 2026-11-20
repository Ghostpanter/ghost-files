package com.ghostpanter.files.core.net

/**
 * Logging facade for core network clients.
 *
 * `:core` is a plain JVM module, so `android.util.Log` is not on the classpath here — and calling
 * it from a unit test throws `RuntimeException: Stub!`. The app installs [sink] once at startup
 * (see `WifiBypass.installLanHooks`) so these lines land in Logcat; without a sink we fall back to
 * stdout, which keeps `./gradlew :core:test` and JVM-side debugging readable.
 */
object NetLog {
    const val TAG = "GhostWebDav"

    /** `(tag, message) -> Unit`. App wires this to `Log.d`; null → stdout. */
    @Volatile
    var sink: ((tag: String, message: String) -> Unit)? = null

    /** Debug-only chatter switch (app sets it from `FLAG_DEBUGGABLE`). Warnings always print. */
    @Volatile
    var enabled: Boolean = true

    fun d(message: () -> String) {
        if (!enabled) return
        emit(TAG, message())
    }

    fun w(message: () -> String) = emit(TAG, message())

    private fun emit(tag: String, message: String) {
        val s = sink
        if (s != null) {
            runCatching { s(tag, message) }
        } else {
            println("$tag: $message")
        }
    }

    /** Passwords never reach Logcat: show the first char at most, the rest as stars. */
    fun mask(secret: String): String =
        if (secret.isEmpty()) "(empty)" else secret.take(1) + "***"
}
