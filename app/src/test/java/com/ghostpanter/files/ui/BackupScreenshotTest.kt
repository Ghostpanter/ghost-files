package com.ghostpanter.files.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.ghostpanter.files.backup.BackupLine
import com.ghostpanter.files.backup.BackupOutcome
import com.ghostpanter.files.backup.BackupProgress
import com.ghostpanter.files.core.backup.BackupKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 备份与恢复: backup page, restore page, progress, restore result (phone light / dark / OLED, tablet). */
@RunWith(Parameterized::class)
class BackupScreenshotTest(private val name: String, private val device: DeviceConfig, private val oled: Boolean) {
    companion object {
        init { java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> = listOf(
            arrayOf("phone", DeviceConfig.PIXEL_5.copy(locale = "zh-rCN"), false),
            arrayOf("phone_dark", DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, locale = "zh-rCN"), false),
            arrayOf("phone_oled", DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, locale = "zh-rCN"), true),
            arrayOf("tablet", DeviceConfig.PIXEL_C.copy(orientation = ScreenOrientation.LANDSCAPE, locale = "zh-rCN"), false),
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 1.0)

    private val dark get() = device.nightMode == NightMode.NIGHT

    private fun render(ui: BackupUi) = paparazzi.snapshot { GhostTheme(dark = dark, oledBlack = oled) { BackupContent(ui, BackupCallbacks()) } }

    private val items = listOf(
        BackupItemUi(BackupKind.SMS, 12_486, true),
        BackupItemUi(BackupKind.CALLS, 3_201, true),
        BackupItemUi(BackupKind.CONTACTS, 642, true),
        BackupItemUi(BackupKind.CALENDAR, null, false, missing = "需要授权才能读取，点按授权"),
    )

    @Test fun backupPage() = render(BackupUi(BackupTab.BACKUP, items, "内部存储/双栏文件管理器/备份", zip = false))

    @Test fun backupProgress() = render(BackupUi(BackupTab.BACKUP, items, "内部存储/双栏文件管理器/备份", zip = true,
        progress = BackupProgress(false, BackupKind.SMS, 1, 4, 7_310, 12_486, "本机  /storage/emulated/0/双栏文件管理器/备份/ghost-backup-2026-10-22_093012.zip")))

    private val restoreItems = listOf(
        BackupItemUi(BackupKind.SMS, 12_486, true), BackupItemUi(BackupKind.CALLS, 3_201, true),
        BackupItemUi(BackupKind.CONTACTS, 642, true), BackupItemUi(BackupKind.CALENDAR, 158, false),
    )
    private val recent = listOf(
        RecentBackup("2026-10-22_093012", "2026-10-22 09:30 · 内部存储/双栏文件管理器/备份"),
        RecentBackup("ghost-backup-2026-10-15_220145.zip", "2026-10-15 22:01 · 内部存储/双栏文件管理器/备份"),
    )

    @Test fun restorePage() = render(BackupUi(BackupTab.RESTORE, items, "", zip = false,
        restoreLabel = "ghost-backup-2026-10-15_220145.zip", restoreSub = "2026-10-15 22:01 · Xiaomi 23127PN0CC · 本机", restoreItems = restoreItems, recent = recent))

    @Test fun restoreDone() = render(BackupUi(BackupTab.RESTORE, items, "", zip = false,
        restoreLabel = "ghost-backup-2026-10-15_220145.zip", restoreSub = "2026-10-15 22:01 · Xiaomi 23127PN0CC · 本机", restoreItems = restoreItems,
        outcome = BackupOutcome(true, listOf(
            BackupLine(BackupKind.SMS, "新增 312，已存在跳过 12,174"),
            BackupLine(BackupKind.CALLS, "新增 40，已存在跳过 3,161"),
            BackupLine(BackupKind.CONTACTS, "新增 3，已存在跳过 639"),
        ), "本机  …"), holdsSms = true))
}
