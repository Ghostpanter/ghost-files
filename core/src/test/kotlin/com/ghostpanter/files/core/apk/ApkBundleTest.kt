package com.ghostpanter.files.core.apk

import com.ghostpanter.files.core.apk.ApkBundle.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkBundleTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, body) in entries) { z.putNextEntry(ZipEntry(n)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private val phone = ApkBundle.Device(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), 440, listOf("zh-CN", "en-US"))

    @Test fun ownBackupFormatRoundTrips() {
        // What backupApk() writes: base.apk + the installed split file names, flat.
        val f = zip("微信_8.0.apks",
            "base.apk" to "b", "split_config.arm64_v8a.apk" to "a", "split_config.xxhdpi.apk" to "d",
            "split_config.zh.apk" to "z", "split_config.en.apk" to "e", "split_config.fr.apk" to "f")
        val b = ApkBundle.read(f)
        assertEquals(ApkBundle.Format.APKS, b.format)
        assertEquals("base.apk", b.base!!.entry)
        assertEquals(Kind.ABI, b.splits.first { it.name == "split_config.arm64_v8a.apk" }.kind)
        assertEquals(Kind.DENSITY, b.splits.first { it.name == "split_config.xxhdpi.apk" }.kind)
        val sel = b.select(phone)
        assertEquals(setOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk", "split_config.zh.apk", "split_config.en.apk"), sel)
        assertEquals("b", b.open("base.apk").use { it.readBytes().decodeToString() })
    }

    @Test fun bundletoolApksSkipsStandalonesAndToc() {
        val f = zip("x.apks", "toc.pb" to "", "standalones/standalone-arm64_v8a_hdpi.apk" to "s",
            "splits/base-master.apk" to "m", "splits/base-arm64_v8a.apk" to "a", "splits/base-x86_64.apk" to "x",
            "splits/base-hdpi.apk" to "h", "splits/base-xxhdpi.apk" to "xx", "splits/base-xxxhdpi.apk" to "xxx",
            "splits/base-en.apk" to "e", "splits/camera-master.apk" to "c", "splits/camera-arm64_v8a.apk" to "ca", "splits/camera-x86_64.apk" to "cx")
        val b = ApkBundle.read(f)
        assertEquals(10, b.splits.size)
        assertEquals("splits/base-master.apk", b.base!!.entry)
        assertEquals(Kind.FEATURE, b.splits.first { it.name == "camera-master.apk" }.kind)
        val sel = b.select(phone)
        assertEquals(setOf("splits/base-master.apk", "splits/base-arm64_v8a.apk", "splits/base-xxhdpi.apk", "splits/base-en.apk",
            "splits/camera-master.apk", "splits/camera-arm64_v8a.apk"), sel)
        val emu = b.select(ApkBundle.Device(listOf("x86_64"), 160, listOf("ja-JP")))
        assertTrue("splits/base-x86_64.apk" in emu && "splits/camera-x86_64.apk" in emu)
        assertTrue("splits/base-hdpi.apk" in emu)      // nearest bucket ≥ 160
        assertTrue("splits/base-en.apk" in emu)        // no language match → keep all languages
        assertFalse("splits/base-arm64_v8a.apk" in emu)
    }

    @Test fun xapkManifestAndObb() {
        val manifest = """{"xapk_version":2,"package_name":"com.game.x","name":"游戏 \"X\"","version_code":"1203","version_name":"1.2.3",
            "split_apks":[{"file":"com.game.x.apk","id":"base"},{"file":"config.arm64_v8a.apk","id":"config.arm64_v8a"},{"file":"config.xxxhdpi.apk","id":"config.xxxhdpi"}],
            "expansions":[{"file":"Android/obb/com.game.x/main.1203.com.game.x.obb","install_location":"EXTERNAL_STORAGE","install_path":"Android/obb/com.game.x/main.1203.com.game.x.obb"}]}"""
        val f = zip("g.xapk", "manifest.json" to manifest, "icon.png" to "png", "com.game.x.apk" to "b",
            "config.arm64_v8a.apk" to "a", "config.xxxhdpi.apk" to "d",
            "Android/obb/com.game.x/main.1203.com.game.x.obb" to "obbdata")
        val b = ApkBundle.read(f)
        assertEquals(ApkBundle.Format.XAPK, b.format)
        assertEquals("com.game.x", b.packageName)
        assertEquals("游戏 \"X\"", b.label)
        assertEquals("1.2.3", b.versionName)
        assertEquals(1203L, b.versionCode)
        assertEquals("icon.png", b.iconEntry)
        assertEquals("com.game.x.apk", b.base!!.entry)
        assertEquals(1, b.obbs.size)
        assertEquals("Android/obb/com.game.x/main.1203.com.game.x.obb", b.obbs[0].target)
        assertEquals(7L, b.obbs[0].size)
        assertEquals(setOf("com.game.x.apk", "config.arm64_v8a.apk", "config.xxxhdpi.apk"), b.select(phone))
    }

    @Test fun apkmInfoJson() {
        val f = zip("a.apkm", "info.json" to """{"pname":"com.a","app_name":"A","versioncode":42,"release_version":"4.2"}""",
            "base.apk" to "b", "split_config.armeabi_v7a.apk" to "v7", "split_config.mdpi.apk" to "m")
        val b = ApkBundle.read(f)
        assertEquals(ApkBundle.Format.APKM, b.format)
        assertEquals("com.a", b.packageName); assertEquals(42L, b.versionCode); assertEquals("4.2", b.versionName)
        // 32-bit only bundle on a 64-bit phone that also runs v7a
        assertTrue("split_config.armeabi_v7a.apk" in b.select(phone))
        // 440 dpi, only mdpi offered → largest available
        assertTrue("split_config.mdpi.apk" in b.select(phone))
    }

    @Test fun folderOfSplitsAndSingleApkZip() {
        val dir = tmp.newFolder("splits")
        File(dir, "base.apk").writeText("b"); File(dir, "split_config.arm64_v8a.apk").writeText("a"); File(dir, "note.txt").writeText("")
        assertTrue(ApkBundle.isSplitFolder(dir))
        val b = ApkBundle.read(dir)
        assertEquals(ApkBundle.Format.FOLDER, b.format)
        assertEquals(2, b.splits.size)
        assertEquals("b", b.open("base.apk").use { it.readBytes().decodeToString() })
        val one = ApkBundle.read(zip("one.apks", "whatever.apk" to "w"))
        assertEquals("whatever.apk", one.base!!.entry)
        assertNull(one.packageName)
    }

    @Test fun classifyNames() {
        assertEquals(Kind.LANGUAGE, ApkBundle.classify("split_config.zh_TW.apk", 1).kind)
        assertEquals(Kind.LANGUAGE, ApkBundle.classify("split_config.b+sr+Latn.apk", 1).kind)
        val f = ApkBundle.classify("split_dynamic_feature.config.arm64_v8a.apk", 1)
        assertEquals(Kind.ABI, f.kind); assertEquals("dynamic_feature", f.owner)
        assertEquals(Kind.FEATURE, ApkBundle.classify("split_dynamic_feature.apk", 1).kind)
        assertEquals(Kind.BASE, ApkBundle.classify("splits/base-master.apk", 1).kind)
        assertTrue(ApkBundle.isBundleName("A.XAPK"))
    }

    @Test fun miniJson() {
        val v = MiniJson.parse("""{"a":[1,2.5,true,null,{"b":"\u4e2d"}],"c":-3}""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, true, null, mapOf("b" to "中")), v["a"])
        assertEquals(-3L, v["c"])
    }
}
