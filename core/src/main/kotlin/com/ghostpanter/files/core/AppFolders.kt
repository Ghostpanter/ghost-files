package com.ghostpanter.files.core

/**
 * Folders named after an app's package: Android/data|obb|media/<pkg>, and with Root
 * /data/data/<pkg>, /data/user[_de]/<n>/<pkg>, /data/app/[~~x==/]<pkg>-<suffix>.
 */
object AppFolders {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val USER = Regex("^/data/(user|user_de)/\\d+$")
    private val APP_DIR = Regex("^/data/app(/~~[^/]+)?$")
    private val SHARED = Regex("(^|/)Android/(data|obb|media)$")

    fun isPackageName(s: String) = s.length in 3..255 && PKG.matches(s)

    /** Package [name] stands for inside [parent], or null. */
    fun packageOf(parent: String, name: String): String? {
        val p = parent.trimEnd('/').ifEmpty { "/" }
        val candidate = when {
            SHARED.containsMatchIn(p) -> name
            p == "/data/data" || USER.matches(p) -> name
            APP_DIR.matches(p) -> if (name.startsWith("~~")) null else name.substringBeforeLast('-', "").takeIf { it.isNotEmpty() }
            else -> null
        } ?: return null
        return candidate.takeIf { isPackageName(it) }
    }

    /** True when [dir] is a folder whose children may be package folders (cheap pre-check for a listing). */
    fun isAppParent(dir: String): Boolean {
        val p = dir.trimEnd('/').ifEmpty { "/" }
        return SHARED.containsMatchIn(p) || p == "/data/data" || USER.matches(p) || APP_DIR.matches(p)
    }
}
