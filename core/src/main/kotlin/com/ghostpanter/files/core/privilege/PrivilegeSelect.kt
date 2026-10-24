package com.ghostpanter.files.core.privilege

/**
 * Shell-uid elevated backend (Shizuku layer). Root is a higher tier tracked separately.
 * Preference among shell backends: installed+granted external Shizuku, else live embedded binder.
 */
enum class PrivilegeKind { EXTERNAL_SHIZUKU, EMBEDDED, NONE }

/**
 * Full privilege stack used for routing and drawer copy:
 * Root > Shizuku (external or embedded) > normal user.
 */
enum class PrivilegeTier { ROOT, EXTERNAL_SHIZUKU, EMBEDDED, NONE }

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

/** Highest available tier given Root grant + selected shell kind. */
fun selectTier(rootGranted: Boolean, shell: PrivilegeKind): PrivilegeTier = when {
    rootGranted -> PrivilegeTier.ROOT
    shell == PrivilegeKind.EXTERNAL_SHIZUKU -> PrivilegeTier.EXTERNAL_SHIZUKU
    shell == PrivilegeKind.EMBEDDED -> PrivilegeTier.EMBEDDED
    else -> PrivilegeTier.NONE
}

/** True when the selected shell elevated backend can run without prompting. */
fun privilegeReady(kind: PrivilegeKind): Boolean = kind != PrivilegeKind.NONE

fun tierReady(tier: PrivilegeTier): Boolean = tier != PrivilegeTier.NONE

/**
 * Drawer row for the merged Android/data+obb (Shizuku / 内置提权) entry.
 * [needsPrompt] = true only when neither Root nor shell is available (user must authorize).
 * When Root covers this row, subtitle says Root is active; tap should use Root backend.
 */
data class ElevatedDrawerRow(
    val title: String,
    val subtitle: String,
    val needsPrompt: Boolean,
    /** Root is active — this row is covered; do not nag for Shizuku auth on tap. */
    val coveredByRoot: Boolean = false,
    /** This row is the highest active shell tier (no Root) — offer re-authorize. */
    val canReauth: Boolean = false,
    /** Covered by Root but user may still authorize shell quietly from a menu. */
    val canAuthorizeInMenu: Boolean = false,
)

/**
 * Build the elevated-access drawer row (data + obb).
 * Hierarchy: Root covers Shizuku copy; only when both missing show unauthorized prompt.
 */
fun elevatedDrawerRow(
    rootGranted: Boolean,
    kind: PrivilegeKind,
    en: Boolean = false,
): ElevatedDrawerRow {
    fun zhEn(zh: String, english: String) = if (en) english else zh
    if (rootGranted) {
        return ElevatedDrawerRow(
            title = "Shizuku",
            subtitle = zhEn("Root 已授权 · Android/data、obb", "Root granted · Android/data, obb"),
            needsPrompt = false,
            coveredByRoot = true,
            canReauth = false,
            canAuthorizeInMenu = true,
        )
    }
    return when (kind) {
        PrivilegeKind.EXTERNAL_SHIZUKU -> ElevatedDrawerRow(
            title = "Shizuku",
            subtitle = zhEn("已授权 · Android/data、obb", "Granted · Android/data, obb"),
            needsPrompt = false,
            canReauth = true,
        )
        PrivilegeKind.EMBEDDED -> ElevatedDrawerRow(
            title = zhEn("内置提权", "Built-in privilege"),
            subtitle = zhEn("已授权 · Android/data、obb", "Granted · Android/data, obb"),
            needsPrompt = false,
            canReauth = true,
        )
        PrivilegeKind.NONE -> ElevatedDrawerRow(
            title = "Shizuku",
            subtitle = zhEn("未授权 · 点此授权或启用内置提权", "Not granted · tap to authorize or enable built-in"),
            needsPrompt = true,
        )
    }
}

/** Root drawer subtitle + whether a Magisk/su prompt is still needed on tap. */
data class RootDrawerRow(
    val subtitle: String,
    val needsPrompt: Boolean,
    /** Root is the highest tier — offer re-authorize (re-su). */
    val canReauth: Boolean = false,
    /** Covered by Shizuku — may authorize Root from menu without nag subtitle. */
    val canAuthorizeInMenu: Boolean = false,
)

/**
 * Root drawer row. When only Shizuku is granted, show that shell covers access (no Root)
 * instead of an unauthorized nag. Unauthorized prompt only when neither Root nor shell.
 */
fun rootDrawerRow(
    rootGranted: Boolean,
    shell: PrivilegeKind = PrivilegeKind.NONE,
    en: Boolean = false,
): RootDrawerRow {
    fun zhEn(zh: String, english: String) = if (en) english else zh
    return when {
        rootGranted -> RootDrawerRow(
            subtitle = zhEn("已授权 · 用 Root 后端浏览 /", "Granted · Root backend for /"),
            needsPrompt = false,
            canReauth = true,
        )
        privilegeReady(shell) -> RootDrawerRow(
            subtitle = zhEn(
                "Shizuku 已授权 · 无 Root（shell 权限）",
                "Shizuku granted · no Root (shell)",
            ),
            needsPrompt = false,
            canAuthorizeInMenu = true,
        )
        else -> RootDrawerRow(
            subtitle = zhEn("点此请求 su 授权", "Tap to request su"),
            needsPrompt = true,
        )
    }
}

/** Display name of the active shell elevated provider for fs labels / toasts. */
fun privilegeLabel(kind: PrivilegeKind, en: Boolean = false): String = when (kind) {
    PrivilegeKind.EXTERNAL_SHIZUKU -> "Shizuku"
    PrivilegeKind.EMBEDDED -> if (en) "Built-in privilege" else "内置提权"
    PrivilegeKind.NONE -> "Shizuku"
}

/** Label for the highest tier (used by space/install status). */
fun tierLabel(tier: PrivilegeTier, en: Boolean = false): String = when (tier) {
    PrivilegeTier.ROOT -> "Root"
    PrivilegeTier.EXTERNAL_SHIZUKU -> "Shizuku"
    PrivilegeTier.EMBEDDED -> if (en) "Built-in privilege" else "内置提权"
    PrivilegeTier.NONE -> if (en) "None" else "无"
}
