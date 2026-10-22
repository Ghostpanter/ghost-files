package com.ghostpanter.files.core.apk

import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.tr

/**
 * Install source ("installer package") handling: what a backup recorded, what a silent install asks pm to
 * record, and how to check what Android actually stored.
 */
object InstallSource {
    const val PLAY = "com.android.vending"
    const val SHELL = "com.android.shell"

    /** Who pm records as the installer for a Root / Shizuku / ADB install. */
    enum class Choice { ORIGINAL, PLAY, SELF }

    /** PackageManager install source, as getInstallSourceInfo / dumpsys report it. Null = unknown / none. */
    data class Record(
        val installer: String?,
        val initiating: String? = null,
        val originating: String? = null,
        /** Android 14+ update owner (only it may update the app without the user confirming). */
        val updateOwner: String? = null,
    )

    /**
     * Options for the install sheet. Only meaningful when pm runs with shell / root rights ([elevated]);
     * the system installer always records the app that opened it.
     */
    fun options(original: String?, selfPkg: String, playPresent: Boolean, elevated: Boolean, installed: (String) -> Boolean = { true }): List<Choice> {
        if (!elevated) return emptyList()
        val out = ArrayList<Choice>()
        if (original != null && original != PLAY && original != selfPkg && installed(original)) out += Choice.ORIGINAL
        if (playPresent) out += Choice.PLAY
        out += Choice.SELF
        return out
    }

    /**
     * Default: keep the recorded original source; else Google Play when it is installed; else this app.
     * Null when there is no choice to make (system installer path).
     */
    fun defaultChoice(original: String?, selfPkg: String, playPresent: Boolean, elevated: Boolean, installed: (String) -> Boolean = { true }): Choice? {
        if (!elevated) return null
        if (original != null) when {
            original == PLAY && playPresent -> return Choice.PLAY
            original == selfPkg -> return Choice.SELF
            original != PLAY && installed(original) -> return Choice.ORIGINAL
        }
        return if (playPresent) Choice.PLAY else Choice.SELF
    }

    fun installerFor(c: Choice, original: String?, selfPkg: String): String = when (c) {
        Choice.PLAY -> PLAY
        Choice.SELF -> selfPkg
        Choice.ORIGINAL -> original ?: selfPkg
    }

    private fun q(s: String) = VPath.shellQuote(s)

    /** `pm install-create`; [installer] becomes the recorded install source (shell / root may set any). */
    fun pmCreate(total: Long, installer: String?, allowTest: Boolean = false): String =
        "pm install-create -r" + (if (allowTest) " -t" else "") + (installer?.let { " -i " + q(it) } ?: "") + " -S $total"

    /** Single-APK `pm install` (remote ADB). */
    fun pmInstall(path: String, installer: String?): String =
        "pm install -r -t" + (installer?.let { " -i " + q(it) } ?: "") + " " + q(path)

    fun setInstaller(pkg: String, installer: String) = "pm set-installer ${q(pkg)} ${q(installer)}"

    fun dumpsys(pkg: String) = "dumpsys package ${q(pkg)}"

    /** `pm set-installer` prints "Success" (or nothing on some builds) when it worked. */
    fun setInstallerOk(out: String): Boolean {
        val o = out.trim()
        return (o.isEmpty() || o.contains("Success", true)) && !o.contains("Exception") && !o.contains("Failure", true) && !o.contains("Error", true)
    }

    /**
     * Reads the install source of [pkg] from `dumpsys package <pkg>`. Handles Android 9 (installerPackageName only)
     * up to 14 (installInitiatingPackageName / installOriginatingPackageName / updateOwnerPackageName).
     */
    fun parseDumpsys(out: String, pkg: String): Record? {
        val start = out.indexOf("Package [$pkg]")
        if (start < 0) return null
        val next = Regex("\\n\\s*Package \\[").find(out, start + 1)?.range?.first ?: out.length
        val sec = out.substring(start, next)
        fun v(vararg keys: String): String? {
            for (k in keys) {
                val m = Regex("(?:^|[\\s,{])" + Regex.escape(k) + "=([^\\s,}]+)", RegexOption.MULTILINE).find(sec) ?: continue
                val x = m.groupValues[1]
                return if (x == "null" || x.isEmpty()) null else x
            }
            return null
        }
        return Record(
            installer = v("installerPackageName"),
            initiating = v("installInitiatingPackageName", "initiatingPackageName"),
            originating = v("installOriginatingPackageName", "originatingPackageName"),
            updateOwner = v("updateOwnerPackageName", "updateOwner"),
        )
    }

    /** Android 14+: installing as someone other than the update owner may need the user's OK, or fail. */
    fun ownershipConflict(current: Record?, installer: String?, sdk: Int): Boolean =
        sdk >= 34 && current?.updateOwner != null && installer != null && current.updateOwner != installer

    /** Turns pm / installer failures about the source into a sentence a user can act on; null = nothing special. */
    fun explain(out: String): String? {
        val o = out.lowercase()
        return when {
            "update_ownership" in o || "update owner" in o || "updateowner" in o || "ownership" in o ->
                tr("系统拒绝：此应用的更新所有权属于另一个安装来源（Android 14+）。请改选「保持原来源」，或在该来源（如 Google Play）中更新。",
                    "Rejected: another installer owns updates for this app (Android 14+). Keep the original source, or update it from that store.")
            "pending user action" in o || "user_action" in o ->
                tr("系统要求用户确认（更新所有权）。请在弹出的系统对话框中确认，或改选「保持原来源」。",
                    "Android asks the user to confirm (update ownership). Confirm the system dialog, or keep the original source.")
            "invalid_installer" in o || ("installer" in o && "not found" in o) ->
                tr("指定的安装来源应用不在本机上。", "The chosen installer app is not on this device.")
            "securityexception" in o || "permission denial" in o || "does not have" in o || "not allowed" in o ->
                tr("系统拒绝：修改安装来源需要当前安装来源本身或系统权限，本机的 Shizuku/ADB 权限不够。可以用「覆盖安装为 Google Play 来源」（保留数据）。",
                    "Rejected: changing the installer needs the current installer or system rights. Use “Reinstall as Google Play” (keeps data).")
            else -> null
        }
    }

    /** Friendly name for well-known installers; null = look the package up. */
    fun knownLabel(pkg: String?, selfPkg: String): String? = when (pkg) {
        null -> tr("未知", "Unknown")
        PLAY -> "Google Play"
        selfPkg -> tr("双栏文件管理器", "Ghost Files")
        SHELL -> "ADB / Shell"
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.packageinstaller",
        "com.oplus.appdetail", "com.coloros.packageinstaller", "com.samsung.android.packageinstaller" -> tr("系统安装器", "System installer")
        "com.xiaomi.market" -> tr("小米应用商店", "Xiaomi GetApps")
        "com.heytap.market", "com.oppo.market" -> tr("OPPO 软件商店", "OPPO App Market")
        "com.bbk.appstore" -> tr("vivo 应用商店", "vivo App Store")
        "com.huawei.appmarket" -> tr("华为应用市场", "Huawei AppGallery")
        "com.sec.android.app.samsungapps" -> "Galaxy Store"
        "com.amazon.venezia" -> "Amazon Appstore"
        "org.fdroid.fdroid" -> "F-Droid"
        else -> null
    }
}

/**
 * ghost-app.json inside our own .apks backups: where the app came from and on which device it was backed up.
 * Third-party .apks / .xapk / .apkm have no such entry; their source is unknown.
 */
data class BundleMeta(
    val packageName: String?,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val source: InstallSource.Record = InstallSource.Record(null),
    val deviceModel: String? = null,
    val abis: List<String> = emptyList(),
    val densityDpi: Int = 0,
    val locales: List<String> = emptyList(),
    val created: Long = 0,
) {
    fun toJson(): String = buildString {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        append("{\n  \"format\": \"ghost-app\",\n  \"version\": 1,\n")
        append("  \"package\": ${s(packageName)},\n  \"versionName\": ${s(versionName)},\n  \"versionCode\": ${versionCode ?: "null"},\n")
        append("  \"installer\": ${s(source.installer)},\n  \"initiating\": ${s(source.initiating)},\n")
        append("  \"originating\": ${s(source.originating)},\n  \"updateOwner\": ${s(source.updateOwner)},\n")
        append("  \"device\": ${s(deviceModel)},\n  \"abis\": [${abis.joinToString(", ") { s(it) }}],\n")
        append("  \"densityDpi\": $densityDpi,\n  \"locales\": [${locales.joinToString(", ") { s(it) }}],\n  \"created\": $created\n}\n")
    }

    companion object {
        const val NAME = "ghost-app.json"

        fun parse(json: String): BundleMeta? {
            val m = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull() ?: return null
            if (m["format"] != "ghost-app") return null
            fun s(k: String) = m[k]?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
            fun l(k: String) = (m[k] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            return BundleMeta(
                s("package"), s("versionName"), (m["versionCode"] as? Number)?.toLong(),
                InstallSource.Record(s("installer"), s("initiating"), s("originating"), s("updateOwner")),
                s("device"), l("abis"), (m["densityDpi"] as? Number)?.toInt() ?: 0, l("locales"), (m["created"] as? Number)?.toLong() ?: 0,
            )
        }
    }
}
