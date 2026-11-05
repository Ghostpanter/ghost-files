package com.ghostpanter.files.core.apk

import com.ghostpanter.files.core.ShellResult
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.tr

/**
 * Convert an installed user app into a system app (and back), Root only.
 *
 * Two install paths, picked at run time:
 *  - [Method.MODULE] (preferred): a systemless Magisk / KernelSU / APatch module under
 *    `/data/adb/modules/ghostfiles_sysapp_<pkg>/system/{app,priv-app}/<pkg>/`. The root manager
 *    overlays it onto /system at boot, so it works on A/B + dynamic (super / EROFS / shared_blocks
 *    ext4) partitions where /system can no longer be remounted read-write, survives nothing across
 *    OTA (module stays, OTA images are untouched), and is undone by deleting the module.
 *  - [Method.DIRECT]: only when no module directory exists (non-Magisk su). Remounts `/system`
 *    (or `/` for system-as-root) rw and copies into `/system/{app,priv-app}/<pkg>/`. Fails with a
 *    clear message on read-only dynamic partitions.
 *
 * Files: base APK as `<pkg>.apk`, split APKs keep their names, extracted native libs (`lib/<abi>`)
 * are copied too; dirs 755, files 644, owner 0:0, SELinux `system_file`. For priv-app a
 * `privapp-permissions-<pkg>.xml` whitelist is written so `ro.control_privapp_permissions=enforce`
 * ROMs do not bootloop. The user copy in /data/app is kept and becomes the "update" of the new
 * system package, so app data is untouched.
 */
object SystemApp {
    enum class Kind(val dir: String) { APP("app"), PRIV_APP("priv-app") }
    enum class Method { MODULE, DIRECT }

    /** Runs [script] as root on the target (local su or remote `su 0` over ADB). */
    fun interface Exec { fun run(script: String, timeoutMs: Long): ShellResult }

    class Failure(message: String) : Exception(message)

    const val MARKER = ".ghost_files_sysapp"
    const val OK = "GF_SYSAPP_OK"
    private const val ERR_REMOUNT = "GF_SYSAPP_ERR_REMOUNT"

    private val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PERM_RE = Regex("^[A-Za-z0-9_.]+$")

    fun validPackage(pkg: String) = PKG_RE.matches(pkg)
    fun moduleId(pkg: String) = "ghostfiles_sysapp_" + pkg.replace(Regex("[^A-Za-z0-9_]"), "_")
    fun dirName(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")
    fun moduleDir(pkg: String, root: String = "") = "$root/data/adb/modules/${moduleId(pkg)}"
    private fun q(s: String) = VPath.shellQuote(s)

    // ---------------------------------------------------------------- environment

    data class Env(val uid0: Boolean, val modules: Boolean, val manager: String, val dynamic: Boolean, val slot: String, val sdk: Int)

    const val PROBE = "echo uid=\$(id -u); " +
        "if [ -d /data/adb/modules ]; then echo modules=1; else echo modules=0; fi; " +
        "if [ -e /data/adb/ksud ] || [ -d /data/adb/ksu ]; then echo mgr=KernelSU; " +
        "elif [ -e /data/adb/apd ] || [ -d /data/adb/ap ]; then echo mgr=APatch; " +
        "elif [ -d /data/adb/magisk ] || command -v magisk >/dev/null 2>&1; then echo mgr=Magisk; else echo mgr=; fi; " +
        "echo dyn=\$(getprop ro.boot.dynamic_partitions); echo slot=\$(getprop ro.boot.slot_suffix); echo sdk=\$(getprop ro.build.version.sdk)"

    fun parseEnv(out: String): Env {
        val m = out.lineSequence().mapNotNull { l -> l.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }.toMap()
        return Env(
            uid0 = m["uid"] == "0", modules = m["modules"] == "1", manager = m["mgr"].orEmpty(),
            dynamic = m["dyn"] == "true", slot = m["slot"].orEmpty(), sdk = m["sdk"]?.toIntOrNull() ?: 0,
        )
    }

    // ---------------------------------------------------------------- status

    data class Status(val method: Method, val kind: Kind?, val removing: Boolean, val path: String)

    fun statusScript(pkg: String, root: String = ""): String {
        val d = dirName(pkg); val m = moduleDir(pkg, root)
        return "M=${q(m)}; if [ -d \"\$M\" ]; then " +
            "if [ -e \"\$M/remove\" ]; then echo st=module_removing; else echo st=module; fi; " +
            "for k in app priv-app; do [ -d \"\$M/system/\$k/${d}\" ] && echo kind=\$k; done; fi; " +
            "for k in app priv-app; do [ -e \"$root/system/\$k/${d}/$MARKER\" ] && echo direct=\$k; done; true"
    }

    fun parseStatus(pkg: String, out: String): Status? {
        var st: String? = null; var kind: Kind? = null; var direct: Kind? = null
        for (l in out.lineSequence().map { it.trim() }) {
            when {
                l.startsWith("st=") -> st = l.removePrefix("st=")
                l.startsWith("kind=") -> kind = kindOf(l.removePrefix("kind="))
                l.startsWith("direct=") -> direct = kindOf(l.removePrefix("direct="))
            }
        }
        if (st != null) return Status(Method.MODULE, kind, st == "module_removing", moduleDir(pkg))
        if (direct != null) return Status(Method.DIRECT, direct, false, "/system/${direct.dir}/${dirName(pkg)}")
        return null
    }

    private fun kindOf(s: String) = Kind.entries.firstOrNull { it.dir == s }

    fun status(exec: Exec, pkg: String): Status? = parseStatus(pkg, exec.run(statusScript(pkg), 15_000).stdout)

    // ---------------------------------------------------------------- inputs

    fun parsePmPaths(out: String): List<String> =
        out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toList()

    /** `requested permissions:` of the first package block in `dumpsys package <pkg>`. */
    fun parseRequestedPermissions(dumpsys: String): List<String> {
        val lines = dumpsys.lines()
        val start = lines.indexOfFirst { it.trim() == "requested permissions:" }
        if (start < 0) return emptyList()
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        val out = LinkedHashSet<String>()
        for (l in lines.drop(start + 1)) {
            if (l.isBlank()) break
            val ind = l.indexOfFirst { !it.isWhitespace() }
            if (ind <= indent) break
            val name = l.trim().substringBefore(':').trim()
            if (PERM_RE.matches(name)) out += name
        }
        return out.toList()
    }

    fun privappXml(pkg: String, perms: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Ghost Files: priv-app whitelist for $pkg -->\n<permissions>\n")
        append("    <privapp-permissions package=\"").append(pkg).append("\">\n")
        for (p in perms.filter { PERM_RE.matches(it) }.distinct()) append("        <permission name=\"").append(p).append("\"/>\n")
        append("    </privapp-permissions>\n</permissions>\n")
    }

    fun moduleProp(pkg: String, label: String): String {
        val clean = label.replace(Regex("[\\r\\n=]"), " ").trim().ifEmpty { pkg }
        return listOf(
            "id=${moduleId(pkg)}",
            "name=Ghost Files 系统化: $clean",
            "version=1.0",
            "versionCode=1",
            "author=Ghost Files",
            "description=Systemless: $pkg as system app (created by Ghost Files). Remove this module to revert.",
        ).joinToString("\n", postfix = "\n")
    }

    /** Base first, then splits; target file names inside the app dir. */
    fun layout(pkg: String, apks: List<String>): List<Pair<String, String>> {
        val base = apks.firstOrNull { it.endsWith("/base.apk") } ?: apks.first()
        val splits = apks.filter { it != base }
        val used = HashSet<String>()
        return listOf(base to "${dirName(pkg)}.apk") + splits.mapIndexed { i, s ->
            var n = s.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!n.endsWith(".apk")) n += ".apk"
            if (!used.add(n) || n == "${dirName(pkg)}.apk") n = "split_$i.apk"
            s to n
        }
    }

    // ---------------------------------------------------------------- scripts

    private fun copyBlock(d: String, pkg: String, apks: List<String>): List<String> {
        val l = layout(pkg, apks)
        val base = l.first().first
        return buildList {
            add("mkdir -p \"$d\"")
            for ((src, name) in l) add("cp -f ${q(src)} \"$d/$name\"")
            // Extracted native libs of the installed copy (absent when extractNativeLibs=false).
            add("SRC=\$(dirname ${q(base)}); if [ -d \"\$SRC/lib\" ]; then cp -rf \"\$SRC/lib\" \"$d/\"; fi")
        }
    }

    private fun permsBlock(dir: String, pkg: String, xml: String?): List<String> =
        if (xml == null) emptyList() else listOf(
            "mkdir -p \"$dir/etc/permissions\"",
            "printf '%s' ${q(xml)} > \"$dir/etc/permissions/privapp-permissions-${dirName(pkg)}.xml\"",
        )

    private fun fixPerms(top: String): List<String> = listOf(
        "chown -R 0:0 \"$top\" 2>/dev/null || echo GF_WARN chown",
        "find \"$top\" -type d -exec chmod 755 {} \\;",
        "find \"$top\" -type f -exec chmod 644 {} \\;",
    )

    /** Systemless module under /data/adb/modules ([root] is a test prefix). */
    fun moduleScript(pkg: String, label: String, apks: List<String>, kind: Kind, privXml: String?, root: String = ""): String {
        require(apks.isNotEmpty())
        val m = moduleDir(pkg, root)
        val d = "\$M/system/${kind.dir}/${dirName(pkg)}"
        return (listOf("set -e", "M=${q(m)}", "rm -rf \"\$M\"") +
            copyBlock(d, pkg, apks) +
            permsBlock("\$M/system", pkg, privXml) +
            listOf(
                "printf '%s' ${q(moduleProp(pkg, label))} > \"\$M/module.prop\"",
                "rm -f \"\$M/remove\" \"\$M/disable\" \"\$M/skip_mount\"",
            ) + fixPerms("\$M") + listOf(
                "chcon -R u:object_r:system_file:s0 \"\$M/system\" 2>/dev/null || true",
                "echo $OK module \"$d\"",
            )).joinToString("\n")
    }

    private const val REMOUNT_RW = "W=0; for MP in /system / /system_root; do mount -o rw,remount \$MP 2>/dev/null; done; " +
        "if touch /system/.gf_rw_probe 2>/dev/null; then rm -f /system/.gf_rw_probe; W=1; fi; " +
        "if [ \$W != 1 ]; then echo $ERR_REMOUNT; exit 3; fi"
    private const val REMOUNT_RO = "for MP in /system_root / /system; do mount -o ro,remount \$MP 2>/dev/null; done; true"

    /** In-place copy into /system after remount rw (no Magisk modules dir). */
    fun directScript(pkg: String, apks: List<String>, kind: Kind, privXml: String?): String {
        require(apks.isNotEmpty())
        val d = "/system/${kind.dir}/${dirName(pkg)}"
        return (listOf(REMOUNT_RW, "set -e", "rm -rf \"$d\"") +
            copyBlock(d, pkg, apks) +
            permsBlock("/system", pkg, privXml) +
            listOf("touch \"$d/$MARKER\"") + fixPerms(d) + listOf(
                "restorecon -R \"$d\" 2>/dev/null || chcon -R u:object_r:system_file:s0 \"$d\" 2>/dev/null || true",
                if (privXml != null) "restorecon \"/system/etc/permissions/privapp-permissions-${dirName(pkg)}.xml\" 2>/dev/null || true" else "true",
                "set +e", REMOUNT_RO,
                "echo $OK direct \"$d\"",
            )).joinToString("\n")
    }

    fun revertModuleScript(pkg: String, root: String = ""): String =
        "M=${q(moduleDir(pkg, root))}; [ -d \"\$M\" ] || { echo not_found; exit 2; }; touch \"\$M/remove\"; echo $OK remove"

    fun revertDirectScript(pkg: String, kind: Kind): String {
        val d = "/system/${kind.dir}/${dirName(pkg)}"
        return listOf(REMOUNT_RW, "rm -rf \"$d\"",
            "rm -f \"/system/etc/permissions/privapp-permissions-${dirName(pkg)}.xml\"", REMOUNT_RO, "echo $OK revert").joinToString("\n")
    }

    /**
     * Reinstalls the system copy into /data (as an update) so that dropping the system copy keeps
     * the package and its data. Used before revert when no /data/app copy is left.
     */
    fun reinstallToDataScript(apks: List<String>): String {
        val tmp = "/data/local/tmp/gf-sysapp-" + Integer.toHexString(apks.hashCode())
        if (apks.size == 1) return "set -e; T=$tmp.apk; cp -f ${q(apks[0])} \$T; chmod 644 \$T; " +
            "R=\$(pm install -r \$T 2>&1); rm -f \$T; echo \"\$R\"; case \"\$R\" in *Success*) ;; *) exit 1 ;; esac"
        val sb = StringBuilder("set -e; S=\$(pm install-create -r | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p'); [ -n \"\$S\" ]; ")
        apks.forEachIndexed { i, p -> sb.append("pm install-write -S \$(stat -c %s ${q(p)}) \$S split_$i.apk ${q(p)} >/dev/null; ") }
        sb.append("R=\$(pm install-commit \$S 2>&1); echo \"\$R\"; case \"\$R\" in *Success*) ;; *) exit 1 ;; esac")
        return sb.toString()
    }

    const val REBOOT = "svc power reboot 2>/dev/null || setprop sys.powerctl reboot 2>/dev/null || reboot"

    // ---------------------------------------------------------------- high level

    data class Result(val method: Method, val kind: Kind, val path: String, val manager: String, val output: String)

    val NO_ROOT: String get() = tr(
        "需要 Root（Magisk / KernelSU / APatch）。未获得 Root 授权，无法转为系统应用。请先在 Root 管理器中授权本应用后重试。",
        "Root (Magisk / KernelSU / APatch) is required. Root was not granted, so the app cannot be made a system app.",
    )

    private fun probe(exec: Exec): Env {
        val r = runCatching { exec.run(PROBE, 20_000) }.getOrNull()
        val env = r?.let { parseEnv(it.stdout) }
        if (env == null || !env.uid0) throw Failure(NO_ROOT)
        return env
    }

    fun convert(exec: Exec, pkg: String, label: String, kind: Kind): Result {
        if (!validPackage(pkg)) throw Failure(tr("包名无效：$pkg", "Invalid package: $pkg"))
        val env = probe(exec)
        status(exec, pkg)?.takeIf { !it.removing }?.let {
            throw Failure(tr("已转为系统应用（${it.path}），重启后生效；如需撤销请用「还原为用户应用」。", "Already converted (${it.path}); reboot to apply, or revert first."))
        }
        val paths = parsePmPaths(exec.run("pm path ${q(pkg)}", 20_000).stdout)
        if (paths.isEmpty()) throw Failure(tr("未安装：$pkg", "Not installed: $pkg"))
        if (paths.any { !it.startsWith("/data/") }) throw Failure(tr("$label 已经是系统应用。", "$label is already a system app."))
        val xml = if (kind == Kind.PRIV_APP) privappXml(pkg, parseRequestedPermissions(exec.run("dumpsys package ${q(pkg)}", 30_000).stdout)) else null
        val method = if (env.modules) Method.MODULE else Method.DIRECT
        val script = if (method == Method.MODULE) moduleScript(pkg, label, paths, kind, xml) else directScript(pkg, paths, kind, xml)
        val r = exec.run(script, 300_000)
        val out = r.stdout + r.stderr
        if (out.contains(ERR_REMOUNT)) throw Failure(tr(
            "系统分区无法挂载为读写（A/B + 动态分区/EROFS 的常见情况）。请安装 Magisk / KernelSU / APatch 后重试，将以模块方式（不改动系统分区）转换。",
            "System partition cannot be remounted rw (dynamic partitions / EROFS). Install Magisk / KernelSU / APatch and retry; the module method does not touch /system.",
        ))
        if (!r.ok || !out.contains(OK)) throw Failure(tr("转换失败：", "Conversion failed: ") + out.trim().takeLast(400).ifEmpty { "exit ${r.code}" })
        val path = out.lineSequence().firstOrNull { it.startsWith(OK) }?.substringAfter(' ')?.substringAfter(' ')?.trim().orEmpty()
        return Result(method, kind, path, env.manager, out)
    }

    fun revert(exec: Exec, pkg: String): Result {
        if (!validPackage(pkg)) throw Failure(tr("包名无效：$pkg", "Invalid package: $pkg"))
        val env = probe(exec)
        val st = status(exec, pkg) ?: throw Failure(tr("未找到由 Ghost Files 转换的系统应用记录，无法还原。", "No Ghost Files system-app conversion found for this package."))
        if (st.removing) throw Failure(tr("已标记还原，重启后生效。", "Already marked for revert; reboot to apply."))
        // Make sure a /data copy exists, otherwise the package (and its data) vanishes on reboot.
        val paths = parsePmPaths(exec.run("pm path ${q(pkg)}", 20_000).stdout)
        var extra = ""
        if (paths.isNotEmpty() && paths.none { it.startsWith("/data/") }) {
            val r = exec.run(reinstallToDataScript(paths), 300_000)
            if (!r.ok) throw Failure(tr("还原前重新安装到用户区失败：", "Reinstall to /data before revert failed: ") + (r.stdout + r.stderr).trim().takeLast(300))
            extra = r.stdout
        }
        val kind = st.kind ?: Kind.APP
        val r = exec.run(if (st.method == Method.MODULE) revertModuleScript(pkg) else revertDirectScript(pkg, kind), 60_000)
        val out = extra + r.stdout + r.stderr
        if (out.contains(ERR_REMOUNT)) throw Failure(tr("系统分区无法挂载为读写，无法删除 ${st.path}。", "Cannot remount /system rw to remove ${st.path}."))
        if (!r.ok || !out.contains(OK)) throw Failure(tr("还原失败：", "Revert failed: ") + out.trim().takeLast(400))
        return Result(st.method, kind, st.path, env.manager, out)
    }
}
