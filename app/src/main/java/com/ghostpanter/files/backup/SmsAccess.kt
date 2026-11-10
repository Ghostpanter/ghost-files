package com.ghostpanter.files.backup

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import com.ghostpanter.files.core.backup.SmsRom

/** Vendor SMS restrictions: ROM detection, MIUI 通知类短信 AppOp, and the page that unlocks it. */
object SmsAccess {
    /** MIUI / HyperOS AppOp for 「读取通知类短信」 (OP_READ_NOTIFICATION_SMS). */
    const val OP_MIUI_NOTIFICATION_SMS = 10018

    private fun prop(k: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as? String
    }.getOrNull().orEmpty().ifEmpty {
        runCatching { Runtime.getRuntime().exec(arrayOf("getprop", k)).inputStream.bufferedReader().use { it.readText().trim() } }.getOrDefault("")
    }

    fun rom(): SmsRom {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            prop("ro.miui.ui.version.name").isNotEmpty() || prop("ro.mi.os.version.name").isNotEmpty() ||
                m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> SmsRom.MIUI
            prop("ro.build.version.opporom").isNotEmpty() || prop("ro.build.version.oplusrom").isNotEmpty() ||
                m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> SmsRom.COLOROS
            prop("ro.build.version.emui").isNotEmpty() || prop("ro.build.version.magic").isNotEmpty() ||
                m.contains("huawei") || m.contains("honor") -> SmsRom.HUAWEI
            prop("ro.vivo.os.version").isNotEmpty() || m.contains("vivo") || m.contains("iqoo") -> SmsRom.VIVO
            else -> SmsRom.OTHER
        }
    }

    fun romLabel(r: SmsRom = rom()): String = when (r) {
        SmsRom.MIUI -> if (prop("ro.mi.os.version.name").isNotEmpty()) "HyperOS ${prop("ro.mi.os.version.name")}" else "MIUI ${prop("ro.miui.ui.version.name")}".trim()
        SmsRom.COLOROS -> "ColorOS ${prop("ro.build.version.oplusrom").ifEmpty { prop("ro.build.version.opporom") }}".trim()
        SmsRom.HUAWEI -> "EMUI/MagicOS"
        SmsRom.VIVO -> "OriginOS"
        SmsRom.OTHER -> {
            val display = (Build.DISPLAY + " " + prop("ro.build.display.id") + " " + prop("ro.modversion")).trim()
            val tag = if (display.contains("Infinity", ignoreCase = true)) "Infinity-X" else Build.MANUFACTURER
            "$tag Android ${Build.VERSION.RELEASE}"
        }
    }

    /** MIUI 通知类短信: true allowed, false denied, null unknown (not MIUI / API hidden). */
    fun miuiNotificationSmsAllowed(ctx: Context): Boolean? {
        if (rom() != SmsRom.MIUI) return null
        return runCatching {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            val mode = m.invoke(ops, OP_MIUI_NOTIFICATION_SMS, android.os.Process.myUid(), ctx.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    fun defaultSmsApp(ctx: Context): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()

    /** Opens the page where the restriction is switched off. Returns false if nothing could be opened. */
    fun openFix(ctx: Context): Boolean {
        val pkg = ctx.packageName
        val tries = ArrayList<Intent>()
        when (rom()) {
            SmsRom.MIUI -> {
                fun editor() = Intent("miui.intent.action.APP_PERM_EDITOR")
                    .putExtra("extra_pkgname", pkg).putExtra("extra_package_name", pkg).putExtra("extra_package_uid", android.os.Process.myUid())
                tries += editor().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                tries += editor().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"))
                tries += editor()
            }
            else -> {
                // ColorOS / Huawei / vivo: the switch lives in the system Messages app's settings.
                val sms = defaultSmsApp(ctx)
                listOfNotNull(sms, "com.android.mms", "com.coloros.mms", "com.oplus.mms", "com.huawei.message").distinct().forEach { p ->
                    ctx.packageManager.getLaunchIntentForPackage(p)?.let { tries += it }
                }
            }
        }
        tries += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        for (i in tries) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    fun fixLabel(r: SmsRom = rom()): String = when (r) {
        SmsRom.MIUI -> "允许读取通知类短信"
        SmsRom.OTHER -> "打开应用权限"
        else -> "打开「信息」设置"
    }
}
