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

    private class FakeHost(override val panes: List<PaneState>) : PaneHost {
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
