package com.ghostpanter.files.core.privilege

/**
 * Which elevated (shell-uid) backend is active. Root is tracked separately.
 * Preference: installed+granted external Shizuku, else a live embedded binder, else none.
 */
enum class PrivilegeKind { EXTERNAL_SHIZUKU, EMBEDDED, NONE }

data class PrivilegeInputs(
    val externalInstalled: Boolean,
    val externalRunning: Boolean,
    val externalGranted: Boolean,
    val embeddedAlive: Boolean,
)

fun selectPrivilege(i: PrivilegeInputs): PrivilegeKind = when {
    i.externalInstalled && i.externalRunning && i.externalGranted -> PrivilegeKind.EXTERNAL_SHIZUKU
    i.embeddedAlive -> PrivilegeKind.EMBEDDED
    else -> PrivilegeKind.NONE
}

/** True when the selected elevated backend can run shell / file ops without prompting. */
fun privilegeReady(kind: PrivilegeKind): Boolean = kind != PrivilegeKind.NONE

/**
 * Drawer row for the merged Android/data+obb entry.
 * [needsPrompt] = true only when the user must still authorize or start a service.
 */
data class ElevatedDrawerRow(
    val title: String,
    val subtitle: String,
    val needsPrompt: Boolean,
)

/**
 * Build the single elevated-access drawer row (data + obb).
 * Titles/subtitles are Chinese by default; [en] flips to English.
 */
fun elevatedDrawerRow(kind: PrivilegeKind, en: Boolean = false): ElevatedDrawerRow {
    fun zhEn(zh: String, english: String) = if (en) english else zh
    return when (kind) {
        PrivilegeKind.EXTERNAL_SHIZUKU -> ElevatedDrawerRow(
            title = "Shizuku",
            subtitle = zhEn("已授权 · Android/data、obb", "Granted · Android/data, obb"),
            needsPrompt = false,
        )
        PrivilegeKind.EMBEDDED -> ElevatedDrawerRow(
            title = zhEn("内置提权", "Built-in privilege"),
            subtitle = zhEn("已授权 · Android/data、obb", "Granted · Android/data, obb"),
            needsPrompt = false,
        )
        PrivilegeKind.NONE -> ElevatedDrawerRow(
            title = "Shizuku",
            subtitle = zhEn("未授权 · 点此授权或启用内置提权", "Not granted · tap to authorize or enable built-in"),
            needsPrompt = true,
        )
    }
}

/** Root drawer subtitle + whether a Magisk/su prompt is still needed. */
data class RootDrawerRow(val subtitle: String, val needsPrompt: Boolean)

fun rootDrawerRow(granted: Boolean, en: Boolean = false): RootDrawerRow {
    fun zhEn(zh: String, english: String) = if (en) english else zh
    return if (granted) RootDrawerRow(
        subtitle = zhEn("已授权 · 用 Root 后端浏览 /", "Granted · Root backend for /"),
        needsPrompt = false,
    ) else RootDrawerRow(
        subtitle = zhEn("点此请求 su 授权", "Tap to request su"),
        needsPrompt = true,
    )
}

/** Display name of the active elevated provider for fs labels / toasts. */
fun privilegeLabel(kind: PrivilegeKind, en: Boolean = false): String = when (kind) {
    PrivilegeKind.EXTERNAL_SHIZUKU -> "Shizuku"
    PrivilegeKind.EMBEDDED -> if (en) "Built-in privilege" else "内置提权"
    PrivilegeKind.NONE -> "Shizuku"
}
