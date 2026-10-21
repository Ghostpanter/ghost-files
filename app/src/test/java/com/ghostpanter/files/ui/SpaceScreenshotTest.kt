package com.ghostpanter.files.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.ghostpanter.files.core.space.AppStat
import com.ghostpanter.files.core.space.CategoryTotals
import com.ghostpanter.files.core.space.NodeFlags
import com.ghostpanter.files.core.space.SpaceFilter
import com.ghostpanter.files.core.space.SpaceNode
import com.ghostpanter.files.core.space.SpaceTree
import com.ghostpanter.files.core.space.SpaceTreeBuilder
import com.ghostpanter.files.core.space.StorageAggregate
import com.ghostpanter.files.core.space.SpacePolicy
import com.ghostpanter.files.space.SpaceNotice
import com.ghostpanter.files.space.SpaceSummary
import com.ghostpanter.files.space.SpaceTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.random.Random

/** 空间分析 with fake trees (no device): non-root aggregates, Shizuku leftovers, Root whole device. */
@RunWith(Parameterized::class)
class SpaceScreenshotTest(private val name: String, private val device: DeviceConfig, private val oled: Boolean) {

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
    private val now = 1_791_000_000_000L
    private val day = 86_400_000L
    private val MB = 1L shl 20
    private val GB = 1L shl 30

    private fun icon(color: Long, glyph: String): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = color.toInt(); c.drawRoundRect(4f, 4f, 92f, 92f, 26f, 26f, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 44f; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText(glyph, 48f, 63f, p)
        return b
    }

    private val apps by lazy {
        mapOf(
            "com.tencent.mm" to AppMeta("微信", icon(0xFF07C160, "微")),
            "tv.danmaku.bili" to AppMeta("哔哩哔哩", icon(0xFFFB7299, "B")),
            "com.ss.android.ugc.aweme" to AppMeta("抖音", icon(0xFF161823, "抖")),
            "com.xingin.xhs" to AppMeta("小红书", icon(0xFFFF2442, "书")),
            "com.miHoYo.Yuanshen" to AppMeta("原神", icon(0xFF5B6FD8, "原")),
            "com.netease.cloudmusic" to AppMeta("网易云音乐", icon(0xFFE60026, "云")),
        )
    }

    /** A plausible phone: camera roll, downloads, movies, WeChat media, many small files. */
    private fun storage(b: SpaceTreeBuilder, base: String, rnd: Random = Random(7)) {
        fun f(p: String, size: Long, age: Int = 3) = b.addFile("$base/$p", size, now - age * day)
        repeat(40) { f("DCIM/Camera/IMG_2026${1000 + it}.jpg", (3 + rnd.nextInt(6)) * MB, it) }
        repeat(9) { f("DCIM/Camera/VID_2026${2000 + it}.mp4", (180 + rnd.nextInt(900)) * MB, it * 3) }
        repeat(30) { f("DCIM/Screenshots/Screenshot_$it.png", (1 + rnd.nextInt(2)) * MB, it) }
        f("Download/ubuntu-24.04-desktop-amd64.iso", 5_900 * MB, 120)
        f("Download/旅行照片.zip", 734 * MB, 4)
        f("Download/app-release.apk", 15 * MB, 1)
        repeat(20) { f("Download/资料$it.pdf", (2 + rnd.nextInt(30)) * MB, 40 + it) }
        f("Movies/Interstellar.2014.2160p.mkv", 21_500 * MB, 400)
        f("Movies/家庭录像 2025.mp4", 2_100 * MB, 200)
        repeat(60) { f("Music/歌曲$it.flac", (20 + rnd.nextInt(30)) * MB, 300) }
        repeat(50) { f("Pictures/WeiXin/mmexport$it.jpg", (1 + rnd.nextInt(3)) * MB, it) }
        repeat(400) { f("Documents/notes/n$it.md", 2_000L + rnd.nextInt(20_000), it) }
        repeat(12) { f("Android/media/com.tencent.mm/WeiXin/v$it.mp4", (40 + rnd.nextInt(200)) * MB, it) }
        f("Telegram/Telegram Video/clip.mp4", 820 * MB, 6)
        b.addDir("$base/Empty1", now); b.addDir("$base/Download/新建文件夹", now)
    }

    private fun shizukuAppData(b: SpaceTreeBuilder, base: String) {
        fun f(p: String, size: Long) = b.addFile("$base/Android/$p", size, now - 5 * day)
        f("data/com.tencent.mm/cache/a.tmp", 2_300 * MB); f("data/com.tencent.mm/files/x.db", 900 * MB)
        f("data/tv.danmaku.bili/download/1.blv", 3_400 * MB); f("data/tv.danmaku.bili/cache/img.bin", 600 * MB)
        f("data/com.ss.android.ugc.aweme/cache/v.bin", 1_800 * MB)
        f("data/com.xingin.xhs/cache/c.bin", 700 * MB)
        f("data/com.old.removed.game/files/res.pak", 2_600 * MB)
        f("data/com.uninstalled.video/download/m.mp4", 1_200 * MB)
        f("obb/com.miHoYo.Yuanshen/main.obb", 4_100 * MB)
        f("obb/com.legacy.removed/main.obb", 900 * MB)
        for (p in listOf("com.old.removed.game", "com.uninstalled.video")) b.tree.find("$base/Android/data/$p")!!.let { it.pkg = p; it.flags = NodeFlags.RESIDUE }
        b.tree.find("$base/Android/obb/com.legacy.removed")!!.let { it.pkg = "com.legacy.removed"; it.flags = NodeFlags.RESIDUE }
        for (p in listOf("com.tencent.mm", "tv.danmaku.bili", "com.ss.android.ugc.aweme", "com.xingin.xhs")) b.tree.find("$base/Android/data/$p")!!.pkg = p
        b.tree.find("$base/Android/obb/com.miHoYo.Yuanshen")!!.pkg = "com.miHoYo.Yuanshen"
    }

    private fun render(ui: SpaceUi) = paparazzi.snapshot {
        GhostTheme(dark = dark, oledBlack = oled) { SpaceContent(ui, SpaceCallbacks()) }
    }

    @Test fun nonRootAggregates() {
        val base = "/storage/emulated/0"
        val b = SpaceTreeBuilder(base); storage(b, base)
        b.markUnreadable("$base/Android/data"); b.markUnreadable("$base/Android/obb")
        val stats = listOf(
            AppStat("com.tencent.mm", "微信", 600 * MB, 18_000 * MB, 2_300 * MB), AppStat("tv.danmaku.bili", "哔哩哔哩", 300 * MB, 6_200 * MB, 600 * MB),
            AppStat("com.ss.android.ugc.aweme", "抖音", 700 * MB, 4_100 * MB, 1_800 * MB), AppStat("com.miHoYo.Yuanshen", "原神", 800 * MB, 31_000 * MB, 100 * MB),
            AppStat("com.xingin.xhs", "小红书", 400 * MB, 2_300 * MB, 700 * MB), AppStat("com.netease.cloudmusic", "网易云音乐", 200 * MB, 3_100 * MB, 400 * MB),
        )
        val total = 256 * GB; val free = 71 * GB
        val r = StorageAggregate.compose("内部存储", total, free, b.root, StorageAggregate.appSpecificBytes(b.root), stats, "应用", "系统",
            detail = { a -> "安装包 ${formatSize(a.appBytes)} · 数据 ${formatSize(a.dataBytes)} · 缓存 ${formatSize(a.cacheBytes)}" })
        val tree = SpaceTree(r.root).also { it.scannedAt = now - 3_600_000 }
        render(SpaceUi(
            scopeLabel = "内部存储", tier = SpaceTier.BASIC, tree = tree, current = tree.root, selected = null, filter = SpaceFilter(), tab = SpaceTab.MAP, version = 1,
            summary = SpaceSummary(total, free, CategoryTotals(images = 310 * MB, videos = 36 * GB, audio = 1_800 * MB, apps = 0, total = 70 * GB)),
            scannedAt = tree.scannedAt, now = now, app = { apps[it] },
            notices = listOf(SpaceNotice("Android 11 起，Android/data 和 Android/obb 只有 Shizuku 或 Root 能读取，方块图里不含这两处（授予「使用情况访问」后计入「应用」）。", "用 Shizuku", "shizuku")),
        ))
    }

    @Test fun shizukuLeftovers() {
        val base = "/storage/emulated/0"
        val b = SpaceTreeBuilder(base); storage(b, base); shizukuAppData(b, base)
        val data = b.tree.find("$base/Android/data")!!
        render(SpaceUi(
            scopeLabel = "内部存储", tier = SpaceTier.SHIZUKU, tree = b.tree, current = data, selected = data.children!!.first { it.name == "com.old.removed.game" },
            filter = SpaceFilter(), tab = SpaceTab.MAP, version = 1, scanning = true, background = false, files = 48_231, bytes = 61 * GB,
            currentPath = "$base/Android/data/com.tencent.mm/cache", now = now, app = { apps[it] },
        ))
    }

    @Test fun rootWholeDevice() {
        val b = SpaceTreeBuilder("/")
        val rnd = Random(3)
        storage(b, "/data/media/0", rnd)
        shizukuAppData(b, "/data/media/0")
        fun f(p: String, size: Long) = b.addFile(p, size, now - 30 * day)
        listOf("com.tencent.mm" to 9_000L, "tv.danmaku.bili" to 2_400L, "com.miHoYo.Yuanshen" to 1_200L, "com.ss.android.ugc.aweme" to 2_100L, "com.xingin.xhs" to 900L).forEach { (p, mb) ->
            f("/data/data/$p/databases/main.db", mb * MB * 2 / 3); f("/data/data/$p/cache/c.bin", mb * MB / 3)
            f("/data/app/~~x${p.hashCode()}==/$p-1/base.apk", (150 + rnd.nextInt(600)) * MB)
            f("/data/dalvik-cache/arm64/data@app@$p@base.apk@classes.dex", (20 + rnd.nextInt(120)) * MB)
        }
        f("/data/log/bugreport.zip", 420 * MB); f("/data/misc/logd/logcat.1", 64 * MB)
        f("/data/ota_package/update.zip", 5_200 * MB)
        repeat(12) { f("/system/app/App$it/App$it.apk", (20 + rnd.nextInt(200)) * MB) }
        f("/system/framework/framework.jar", 48 * MB); f("/system/lib64/libart.so", 18 * MB)
        repeat(8) { f("/vendor/firmware/fw$it.bin", (10 + rnd.nextInt(90)) * MB) }
        repeat(10) { f("/product/app/P$it/P$it.apk", (30 + rnd.nextInt(300)) * MB) }
        f("/system_ext/priv-app/Settings/Settings.apk", 90 * MB)
        b.root.children!!.forEach { c -> if (SpacePolicy.readOnlyPartition(c.path())) c.flags = c.flags or NodeFlags.READ_ONLY }
        b.tree.find("/data/data/com.tencent.mm")!!.pkg = "com.tencent.mm"
        render(SpaceUi(
            scopeLabel = "整机", tier = SpaceTier.ROOT, tree = b.tree, current = b.root, selected = null, filter = SpaceFilter(), tab = SpaceTab.MAP, version = 1,
            scannedAt = now - 120_000, now = now, app = { apps[it] },
        ))
    }

    @Test fun largestFiltered() {
        val base = "/storage/emulated/0"
        val b = SpaceTreeBuilder(base); storage(b, base)
        render(SpaceUi(
            scopeLabel = "内部存储", tier = SpaceTier.BASIC, tree = b.tree, current = b.root, selected = null,
            filter = SpaceFilter(category = com.ghostpanter.files.core.space.FileCategory.VIDEO), tab = SpaceTab.TOP, version = 1, scannedAt = now - 86_400_000, now = now,
        ))
    }
}
