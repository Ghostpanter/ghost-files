package com.ghostpanter.files.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.data.SortBy
import com.ghostpanter.files.data.ViewPrefs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Renders the MT-style frame with fake data (no device needed). */
@RunWith(Parameterized::class)
class UiScreenshotTest(private val name: String, private val device: DeviceConfig) {

    companion object {
        init { java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> = listOf(
            arrayOf("phone_portrait", DeviceConfig.PIXEL_5.copy(locale = "zh-rCN")),
            arrayOf("phone_portrait_dark", DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, locale = "zh-rCN")),
            arrayOf("tablet_landscape", DeviceConfig.PIXEL_C.copy(orientation = ScreenOrientation.LANDSCAPE, locale = "zh-rCN")),
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 1.0)

    private val day = 86_400_000L
    private val now = 1_791_000_000_000L

    private fun fe(dir: String, name: String, isDir: Boolean, size: Long = 0, ageDays: Int = 1, perms: String? = null) =
        FileEntry("$dir/$name", name, isDir, size, now - ageDays * day, permissions = perms)

    private class Prefs : ViewPrefs {
        override val showHidden = false
        override val foldersFirst = true
        override val ascending = true
        override val sortBy = SortBy.NAME
    }

    private class FakeHost(override val panes: List<PaneState>, val apps: Map<String, AppMeta> = emptyMap()) : PaneHost {
        override fun appPackage(fsId: String, dir: String, e: FileEntry) =
            if (e.isDirectory) com.ghostpanter.files.core.AppFolders.packageOf(dir, e.name) else null
        override fun cachedApp(fsId: String, pkg: String) = apps[pkg]
        override suspend fun loadApp(fsId: String, pkg: String) = apps[pkg]
        override var activePane by mutableIntStateOf(0)
        override val prefs: ViewPrefs = Prefs()
        override fun fsLabel(fsId: String) = when (fsId) { "local" -> "本机"; else -> "NAS (SMB)" }
        override fun onEntryClick(pane: PaneState, e: FileEntry) {}
        override fun onEntryLongPress(pane: PaneState, e: FileEntry) {}
        override fun onEntrySwipe(pane: PaneState, e: FileEntry) {}
        override fun navigateTo(pane: PaneState, path: String) {}
        override fun onPathLongPress(pane: PaneState) {}
        override fun selectTab(pane: PaneState, i: Int) {}
        override fun closeTab(pane: PaneState, i: Int) {}
        override fun reload(tab: TabState) {}
        override fun cachedThumbnail(fsId: String, e: FileEntry, sizePx: Int) = thumbnail(fsId, e, sizePx) { false }
        /** Fake media thumbnails / APK icon so the snapshots show the thumbnail path. */
        override fun thumbnail(fsId: String, e: FileEntry, sizePx: Int, cancelled: () -> Boolean): android.graphics.Bitmap? {
            val ext = e.extension.lowercase()
            val colors = when (ext) {
                "jpg", "png" -> intArrayOf(0xFFF6B48B.toInt(), 0xFF8E7CC3.toInt())
                "mp4", "mkv" -> intArrayOf(0xFF2B3A55.toInt(), 0xFF0F1420.toInt())
                "apk" -> null
                else -> return null
            }
            val n = 96
            val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            if (colors == null) {
                paint.color = 0xFF2FBF71.toInt()
                c.drawRoundRect(8f, 8f, 88f, 88f, 22f, 22f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                c.drawCircle(48f, 48f, 18f, paint)
            } else {
                paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, n.toFloat(), colors[0], colors[1], android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), paint)
                paint.shader = null
                if (ext == "jpg" || ext == "png") {
                    paint.color = 0xFFFFE6A8.toInt(); c.drawCircle(30f, 30f, 12f, paint)
                    paint.color = 0xFF5B6E8C.toInt()
                    c.drawPath(android.graphics.Path().apply { moveTo(0f, 96f); lineTo(38f, 52f); lineTo(64f, 76f); lineTo(80f, 60f); lineTo(96f, 96f); close() }, paint)
                }
            }
            return b
        }
    }

    private fun host(selecting: Boolean): FakeHost {
        val l = "/storage/emulated/0"
        val left = PaneState(0).apply {
            tabs.add(TabState("local", l).apply {
                entries = listOf(
                    fe(l, "Android", true, ageDays = 30), fe(l, "DCIM", true, ageDays = 2), fe(l, "Download", true),
                    fe(l, "Movies", true, ageDays = 9), fe(l, "Music", true, ageDays = 40), fe(l, "Pictures", true, ageDays = 3),
                    fe(l, "Telegram", true, ageDays = 1), fe(l, "备份", true, ageDays = 12),
                    fe(l, "旅行照片.zip", false, 734_003_200, 4), fe(l, "会议纪要.txt", false, 12_480, 2),
                    fe(l, "app-release.apk", false, 15_437_330, 1), fe(l, "IMG_20261003_181522.jpg", false, 4_812_331, 8),
                    fe(l, "演示视频 1080p.mp4", false, 3_221_225_472, 5), fe(l, "notes.md", false, 2_048, 6),
                )
                if (selecting) { multiSelect = true; selected = setOf("$l/旅行照片.zip", "$l/IMG_20261003_181522.jpg") }
            })
        }
        val r = "/public/影视"
        val right = PaneState(1).apply {
            tabs.add(TabState("net:nas", r).apply {
                entries = listOf(
                    fe(r, "电视剧", true, ageDays = 3), fe(r, "电影", true, ageDays = 1), fe(r, "纪录片", true, ageDays = 20),
                    fe(r, "Interstellar.2014.2160p.mkv", false, 58_291_142_656, 200),
                    fe(r, "家庭录像 2025.mp4", false, 2_147_483_648 + 9_000_000, 30),
                    fe(r, "字幕.srt", false, 88_120, 30), fe(r, "封面.png", false, 1_204_311, 31),
                )
            })
            tabs.add(TabState("local", "/storage/emulated/0/Download"))
        }
        return FakeHost(listOf(left, right))
    }

    private fun appIcon(color: Long, glyph: String): android.graphics.Bitmap {
        val n = 96
        val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = color.toInt(); c.drawRoundRect(4f, 4f, 92f, 92f, 26f, 26f, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 44f; p.textAlign = android.graphics.Paint.Align.CENTER
        p.isFakeBoldText = true
        c.drawText(glyph, 48f, 63f, p)
        return b
    }

    private fun androidDataHost(): FakeHost {
        val d = "/storage/emulated/0/Android/data"
        val left = PaneState(0).apply {
            tabs.add(TabState("local", d).apply {
                entries = listOf(
                    fe(d, "com.eg.android.AlipayGphone", true, ageDays = 1), fe(d, "com.netease.cloudmusic", true, ageDays = 3),
                    fe(d, "com.old.removed.app", true, ageDays = 90), fe(d, "com.tencent.mm", true, ageDays = 0),
                    fe(d, "com.xingin.xhs", true, ageDays = 2), fe(d, "tv.danmaku.bili", true, ageDays = 1),
                    fe(d, ".nomedia", false, 0, 300),
                )
            })
        }
        val o = "/storage/emulated/0/Android/obb"
        val right = PaneState(1).apply {
            tabs.add(TabState("local", o).apply {
                entries = listOf(fe(o, "com.miHoYo.Yuanshen", true, ageDays = 6), fe(o, "com.tencent.tmgp.sgame", true, ageDays = 9))
            })
        }
        return FakeHost(listOf(left, right), mapOf(
            "com.eg.android.AlipayGphone" to AppMeta("支付宝", appIcon(0xFF1677FF, "支")),
            "com.netease.cloudmusic" to AppMeta("网易云音乐", appIcon(0xFFE60026, "云")),
            "com.tencent.mm" to AppMeta("微信", appIcon(0xFF07C160, "微")),
            "com.xingin.xhs" to AppMeta("小红书", appIcon(0xFFFF2442, "书")),
            "tv.danmaku.bili" to AppMeta("哔哩哔哩", appIcon(0xFFFB7299, "B")),
            "com.miHoYo.Yuanshen" to AppMeta("原神", appIcon(0xFF5B6FD8, "原")),
            "com.tencent.tmgp.sgame" to AppMeta("王者荣耀", appIcon(0xFFC9932C, "王")),
        ))
    }

    private fun drawerState(dark: Boolean) = DrawerUiState(
        version = "0.7.2-test",
        volumes = listOf(Volume("内部存储", "/storage/emulated/0", 256L shl 30, 87L shl 30), Volume("U 盘", "/storage/1A2B-3C4D", 64L shl 30, 51L shl 30)),
        rootGranted = true,
        elevatedTitle = "Shizuku",
        elevatedSubtitle = "已授权 · Android/data、obb",
        elevatedNeedsPrompt = false,
        showSafAndroidData = false,
        safTrees = listOf("content://x" to "Android/data"),
        bookmarks = listOf(com.ghostpanter.files.data.Bookmark("相机", "local", "/storage/emulated/0/DCIM/Camera"),
            com.ghostpanter.files.data.Bookmark("下载", "local", "/storage/emulated/0/Download")),
        servers = listOf(com.ghostpanter.files.core.ServerConfig("nas", "家里 NAS", com.ghostpanter.files.core.Protocol.SMB, "192.168.1.10"),
            com.ghostpanter.files.core.ServerConfig("vps", "云主机", com.ghostpanter.files.core.Protocol.SFTP, "vps.example.com")),
        adbDevices = listOf("192.168.1.23:5555" to "Xiaomi Pad 6", "usb-R5CT30ABCDE:1" to "Galaxy Tab S9"),
        showHidden = false, thumbnails = true, language = "zh",
        themeMode = if (dark) "dark" else "system", oledBlack = false,
        layoutMode = "auto", listDensity = "normal", gridView = false, tabletPreview = true,
        foldersFirst = true, recycleBin = true, jobs = 3, jobsPerServer = 2,
    )

    @Test fun drawer() {
        if (device.orientation == ScreenOrientation.LANDSCAPE) return
        val dark = device.nightMode == NightMode.NIGHT
        paparazzi.snapshot {
            GhostTheme(dark = dark) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    Frame(host(false), dark)
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
                    androidx.compose.material3.Surface(
                        androidx.compose.ui.Modifier.fillMaxHeight().width(312.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) { DrawerBody(drawerState(dark), DrawerActions()) }
                }
            }
        }
    }

    @Test fun androidData() {
        val h = androidDataHost()
        paparazzi.snapshot { Frame(h, device.nightMode == NightMode.NIGHT) }
    }

    @Test fun installSheet() {
        if (device.orientation == ScreenOrientation.LANDSCAPE) return
        val dark = device.nightMode == NightMode.NIGHT
        val splits = listOf(
            com.ghostpanter.files.core.apk.ApkBundle.classify("base.apk", 182_331_904),
            com.ghostpanter.files.core.apk.ApkBundle.classify("split_config.arm64_v8a.apk", 61_204_480),
            com.ghostpanter.files.core.apk.ApkBundle.classify("split_config.armeabi_v7a.apk", 48_112_640),
            com.ghostpanter.files.core.apk.ApkBundle.classify("split_config.xxhdpi.apk", 4_300_800),
            com.ghostpanter.files.core.apk.ApkBundle.classify("split_config.zh.apk", 512_000),
            com.ghostpanter.files.core.apk.ApkBundle.classify("split_config.en.apk", 498_000),
        )
        val sel = setOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk", "split_config.zh.apk", "split_config.en.apk")
        paparazzi.snapshot {
            GhostTheme(dark = dark) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    Frame(host(false), dark)
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
                    androidx.compose.material3.Surface(
                        androidx.compose.ui.Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.BottomCenter),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(32.dp, 4.dp).background(androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                            }
                            InstallSheetContent(InstallUi(
                                title = "微信", packageName = "com.tencent.mm", version = "8.0.53 (2860)", installedVersion = "8.0.50 (2820)",
                                icon = appIcon(0xFF07C160, "微").asImageBitmap(), format = "APKS", splits = splits, selected = sel, auto = sel,
                                deviceLine = "arm64-v8a · 440dpi · zh-CN/en-US", obbLine = null, silentVia = "Shizuku", silent = true,
                            ), InstallActions(extract = {}))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Frame(h: FakeHost, dark: Boolean, oled: Boolean = false) = GhostTheme(dark = dark, oledBlack = oled) {
        MainFrame(
            host = h, activeJobs = 2, ratio = 0.5f, onRatio = {}, onRatioDone = {}, canForward = false,
            onDrawer = {}, onSearch = {}, onTransfers = {}, onSwap = {}, onTool = {}, onToolLong = {},
        )
    }

    @Composable
    private fun TabletFrame(h: FakeHost, dark: Boolean) = GhostTheme(dark = dark) {
        // Same decision as the app: phone_portrait renders "forced tablet" mode (no details panel there).
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        val layout = resolveLayout("tablet", cfg.screenWidthDp, cfg.screenHeightDp, panelOpen = false)
        androidx.compose.foundation.layout.Row {
            GhostNavRail(current = Screen.Main, activeJobs = 2, panelOpen = false, onMenu = {}, onPick = {})
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                TabletMainFrame(
                    host = h, ratio = 0.5f, onRatio = {}, onRatioDone = {}, canForward = false, hasClip = true,
                    showPreview = layout.previewAllowed, previewWidth = 300f, onPreviewWidth = {}, onPreviewWidthDone = {},
                    onSearch = {}, onSwap = {}, onTool = {}, onToolLong = {},
                )
            }
        }
    }

    @Test fun tabletFrame() {
        val h = host(selecting = false)
        h.panes[0].tab.highlight = "/storage/emulated/0/会议纪要.txt"
        paparazzi.snapshot { TabletFrame(h, device.nightMode == NightMode.NIGHT) }
    }

    @Test fun browse() {
        val h = host(selecting = false)
        paparazzi.snapshot { Frame(h, device.nightMode == NightMode.NIGHT) }
    }

    /** OLED pure-black variant (only meaningful in the dark configuration). */
    @Test fun multiSelectOled() {
        if (device.nightMode != NightMode.NIGHT) return
        val h = host(selecting = true)
        paparazzi.snapshot { Frame(h, true, oled = true) }
    }

    @Test fun multiSelect() {
        val h = host(selecting = true)
        paparazzi.snapshot { Frame(h, device.nightMode == NightMode.NIGHT) }
    }

    @Test fun longPressMenu() {
        val h = host(selecting = false)
        paparazzi.snapshot {
            GhostTheme(dark = device.nightMode == NightMode.NIGHT) {
                Frame(h, device.nightMode == NightMode.NIGHT)
                ActionMenuDialog(ActionMenu("旅行照片.zip", "700 MB  2026-10-07 09:20", listOf(
                    MenuAction("复制到对面", Icons.Filled.FileCopy, "→ NAS (SMB) /public/影视") {},
                    MenuAction("移动到对面", Icons.AutoMirrored.Filled.DriveFileMove, "→ NAS (SMB) /public/影视") {},
                    MenuAction("重命名", Icons.Filled.DriveFileRenameOutline) {},
                    MenuAction("删除", Icons.Filled.Delete) {},
                    MenuAction("解压…", Icons.Filled.Unarchive) {},
                    MenuAction("分享", Icons.Filled.Share) {},
                    MenuAction("属性", Icons.Filled.Info) {},
                ))) {}
            }
        }
    }
}
