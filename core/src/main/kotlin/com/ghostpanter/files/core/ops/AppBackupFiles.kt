package com.ghostpanter.files.core.ops

/**
 * Naming and matching for app backups in Download/DualPane/apks:
 *  - APK:  `<pkg>_<label>_<version>.apk` or `.apks` (older builds wrote `<label>_<version>.apk(s)`)
 *  - data: `<pkg>-data.tar`
 * Both start with the package name, so a pair sorts next to each other.
 */
object AppBackupFiles {
    /** What to back up / restore. */
    enum class Part(val apk: Boolean, val data: Boolean) { APK(true, false), DATA(false, true), BOTH(true, true) }

    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\s]+")

    fun validPackage(pkg: String): Boolean = PKG.matches(pkg)

    fun dataTarName(pkg: String) = "$pkg-data.tar"

    /** Package name encoded in a `<pkg>-data.tar` file name, or null. */
    fun dataTarPackage(name: String): String? {
        if (!name.endsWith("-data.tar", ignoreCase = true)) return null
        val p = name.dropLast("-data.tar".length)
        return p.takeIf { validPackage(it) }
    }

    fun apkBaseName(pkg: String, label: String, version: String): String =
        listOf(pkg, label, version).filter { it.isNotBlank() }.joinToString("_").replace(UNSAFE, "_")

    fun apkName(pkg: String, label: String, version: String, split: Boolean) =
        apkBaseName(pkg, label, version) + if (split) ".apks" else ".apk"

    fun isApkBackupName(name: String): Boolean = name.endsWith(".apk", true) || name.endsWith(".apks", true)

    /** True when [name] is a new-style APK backup of [pkg] (`<pkg>_…`, `<pkg>.apk`). Older names need a content check. */
    fun apkNameMatches(name: String, pkg: String): Boolean {
        if (!isApkBackupName(name)) return false
        val stem = name.substringBeforeLast('.')
        return stem == pkg || stem.startsWith(pkg + "_")
    }

    data class Candidate(val name: String, val modified: Long)

    /**
     * Newest APK backup among [files] for [pkg]. [pkgOf] reads the real package from legacy-named files
     * (ghost-app.json / archive info); it is only called for names that don't already say the package.
     * A name match is only trusted when [pkgOf] does not contradict it (`com.a_b…` vs `com.a`).
     */
    fun pickApk(files: List<Candidate>, pkg: String, pkgOf: (String) -> String?): Candidate? =
        files.filter { isApkBackupName(it.name) }
            .sortedByDescending { it.modified }
            .firstOrNull { c ->
                if (apkNameMatches(c.name, pkg)) {
                    val real = if (c.name.substringBeforeLast('.') == pkg) pkg else pkgOf(c.name)
                    real == null || real == pkg
                } else pkgOf(c.name) == pkg
            }

    fun pickData(files: List<Candidate>, pkg: String): Candidate? =
        files.firstOrNull { it.name == dataTarName(pkg) }

    /** Group id of an app's cache dirs (`u<user>_a<id>_cache`), as installd sets it. */
    fun cacheGid(uid: Int): Int {
        val user = uid / 100_000
        val appId = uid % 100_000
        return user * 100_000 + 20_000 + (appId - 10_000)
    }

    /**
     * Shell script that restores `<tmp>` (tar of `<pkg>/…`) into /data/data/<pkg>:
     * force-stop, clear current data (keep the `lib` link), copy in, chown to the app uid,
     * cache dirs to its cache gid, then restorecon so SELinux labels match the app.
     */
    fun restoreScript(pkg: String, uid: Int, tmpTar: String): String {
        require(validPackage(pkg)) { "bad package" }
        require(uid >= 10_000) { "bad uid" }
        val d = "/data/data/$pkg"
        val s = "/data/local/tmp/gf-restore-$pkg"
        val cg = cacheGid(uid)
        return """
            set -e
            am force-stop $pkg >/dev/null 2>&1 || true
            [ -d $d ] || { echo NOT_INSTALLED >&2; exit 4; }
            rm -rf $s; mkdir -p $s
            tar -C $s -xf $tmpTar
            [ -d $s/$pkg ] || { rm -rf $s $tmpTar; echo BAD_TAR >&2; exit 3; }
            rm -rf $s/$pkg/lib
            find $d -mindepth 1 -maxdepth 1 ! -name lib -exec rm -rf {} +
            cp -a $s/$pkg/. $d/
            for f in $d/* $d/.[!.]*; do if [ -e "${'$'}f" ] && [ "${'$'}{f##*/}" != lib ]; then chown -R $uid:$uid "${'$'}f"; fi; done
            chown $uid:$uid $d
            for c in cache code_cache; do if [ -d $d/${'$'}c ]; then chown -R $uid:$cg $d/${'$'}c; fi; done
            restorecon -RF $d >/dev/null 2>&1 || restorecon -R $d >/dev/null 2>&1 || true
            rm -rf $s $tmpTar
            echo OK
        """.trimIndent()
    }

    /** Short reason from a failed restore's stderr markers. */
    fun explainRestore(stderr: String): String? = when {
        "NOT_INSTALLED" in stderr -> "NOT_INSTALLED"
        "BAD_TAR" in stderr -> "BAD_TAR"
        else -> null
    }
}
