package com.ghostpanter.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.ghostpanter.files.core.apk.ApkBundle
import com.ghostpanter.files.core.apk.InstallSource.Choice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Install sheet with install-source options / missing-split warning / Play hint, and the app detail source card. */
@RunWith(Parameterized::class)
class InstallSourceScreenshotTest(private val name: String, private val device: DeviceConfig) {
    companion object {
        init { java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> = listOf(
            arrayOf("phone", DeviceConfig.PIXEL_5.copy(locale = "zh-rCN", screenHeight = 2900)),
            arrayOf("phone_dark", DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, locale = "zh-rCN", screenHeight = 2900)),
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 1.0)

    private val dark get() = device.nightMode == NightMode.NIGHT

    private fun icon(): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF111111.toInt(); c.drawRoundRect(4f, 4f, 92f, 92f, 26f, 26f, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 46f; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText("G", 48f, 64f, p)
        return b
    }

    private fun split(n: String, size: Long) = ApkBundle.classify(n, size)

    private val grokSplits = listOf(
        split("base.apk", 96_331_904), split("split_config.arm64_v8a.apk", 31_204_480),
        split("split_config.xxhdpi.apk", 2_300_800), split("split_config.zh.apk", 412_000), split("split_config.en.apk", 398_000),
    )
    private val grokSel = grokSplits.map { it.entry }.toSet()

    private fun base(splits: List<ApkBundle.Split> = grokSplits, sel: Set<String> = grokSel) = InstallUi(
        title = "Grok", packageName = "ai.x.grok", version = "1.0.92 (1092)", installedVersion = "1.0.90 (1090)",
        icon = icon().asImageBitmap(), format = "APKS", splits = splits, selected = sel, auto = sel,
        deviceLine = "arm64-v8a · 440dpi · zh-CN/en-US", obbLine = null, silentVia = "Root", silent = true,
    )

    private val options = listOf(
        SourceOptionUi(Choice.PLAY, "Google Play（原来源）", "记为从 Play 安装，可避免「从 Play 获取此应用」提示"),
        SourceOptionUi(Choice.SELF, "双栏文件管理器", "记为由本应用安装"),
    )

    @Composable
    private fun Sheet(content: @Composable () -> Unit) = GhostTheme(dark = dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                    }
                    content()
                }
            }
        }
    }

    /** Root path: Google Play / this app, original recorded in the backup, Android 14 update-ownership note. */
    @Test fun installSource() = paparazzi.snapshot {
        Sheet {
            InstallSheetContent(base().copy(
                sourceOptions = options, source = Choice.PLAY, sourceLine = "备份记录的原来源：Google Play",
                sourceWarn = null,
            ), InstallActions(extract = {}))
        }
    }

    /** Same, after install: source verified, and the ownership caveat when switching away from the update owner. */
    @Test fun installSourceResult() = paparazzi.snapshot {
        Sheet {
            InstallSheetContent(base().copy(
                sourceOptions = options, source = Choice.SELF, sourceLine = "备份记录的原来源：Google Play",
                sourceWarn = "Android 14+：此应用的更新所有者是 Google Play。用其他来源覆盖安装时，系统可能弹出确认或直接拒绝；选 Google Play 来源最稳妥。",
                result = true to "安装成功", resultNote = false to "实际安装来源是 Google Play，不是所选的 双栏文件管理器",
            ), InstallActions(extract = {}, open = {}))
        }
    }

    /** Backup from an x86_64 tablet installed on an arm64 phone. */
    @Test fun installMissingSplit() {
        val splits = listOf(split("base.apk", 96_331_904), split("split_config.x86_64.apk", 33_100_000), split("split_config.xhdpi.apk", 1_900_000), split("split_config.en.apk", 398_000))
        val sel = splits.map { it.entry }.toSet()
        val b = ApkBundle(ApkBundle.Format.APKS, java.io.File("grok.apks"), splits)
        val issues = b.integrity(ApkBundle.Device(listOf("arm64-v8a", "armeabi-v7a"), 440, listOf("zh-CN")), sel)
        paparazzi.snapshot {
            Sheet {
                InstallSheetContent(base(splits, sel).copy(
                    issues = issues, backupDevice = "备份自 Lenovo TB-J716F · x86_64 · 320dpi · en-US",
                    sourceOptions = options, source = Choice.PLAY, sourceLine = "备份记录的原来源：Google Play",
                ), InstallActions(extract = {}))
            }
        }
    }

    /** No Root / Shizuku: system installer, source can't be changed → Play hint with “在 Play 中打开”. */
    @Test fun installPlayHint() = paparazzi.snapshot {
        Sheet {
            InstallSheetContent(base().copy(silentVia = null, silent = false, playHint = true, canOpenPlay = true), InstallActions(extract = {}))
        }
    }

    @Composable
    private fun Detail(card: @Composable () -> Unit) = GhostTheme(dark = dark) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp)) {
                    Text("Grok", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("ai.x.grok\n版本 1.0.92\n/data/app/~~kP3q==/ai.x.grok-Zt1==/base.apk", fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    card()
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.align(Alignment.End), horizontalAlignment = Alignment.End) {
                        listOf("备份 APK（APKS）", "启动", "应用信息", "卸载").forEach { TextButton({}) { Text(it, fontWeight = FontWeight.Medium) } }
                    }
                }
            }
        }
    }

    /** Installed app detail: installed by this app, Root available → 「改为 Google Play 来源」. */
    @Test fun appSource() = paparazzi.snapshot {
        Detail {
            AppSourceCard(AppSourceUi(
                installer = "双栏文件管理器", installerPkg = "com.ghostpanter.files", updateOwner = null,
                canSetPlay = true, canOpenPlay = true,
            ))
        }
    }

    /** set-installer refused (Shizuku): honest error + reinstall-in-place fallback. */
    @Test fun appSourceRefused() = paparazzi.snapshot {
        Detail {
            AppSourceCard(AppSourceUi(
                installer = "双栏文件管理器", installerPkg = "com.ghostpanter.files", updateOwner = "Google Play",
                canSetPlay = true, canOpenPlay = true, offerReinstall = true,
                note = false to "系统拒绝：修改安装来源需要当前安装来源本身或系统权限，本机的 Shizuku/ADB 权限不够。可以用「覆盖安装为 Google Play 来源」（保留数据）。\njava.lang.SecurityException: Caller does not have same cert as old installer package",
            ))
        }
    }
}
