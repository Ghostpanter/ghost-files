package com.ghostpanter.files.core.apk

import com.ghostpanter.files.core.apk.ApkBundle.Kind
import com.ghostpanter.files.core.apk.ApkBundle.Severity
import com.ghostpanter.files.core.apk.InstallSource.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val SELF = "com.ghostpanter.files"

class InstallSourceChoiceTest {
    @Test fun noChoiceWithoutRootOrShizuku() {
        assertNull(InstallSource.defaultChoice(InstallSource.PLAY, SELF, playPresent = true, elevated = false))
        assertTrue(InstallSource.options(InstallSource.PLAY, SELF, true, elevated = false).isEmpty())
    }

    @Test fun recordedSourceWins() {
        assertEquals(Choice.PLAY, InstallSource.defaultChoice(InstallSource.PLAY, SELF, true, true))
        assertEquals(Choice.ORIGINAL, InstallSource.defaultChoice("com.xiaomi.market", SELF, true, true))
        assertEquals(Choice.SELF, InstallSource.defaultChoice(SELF, SELF, true, true))
        assertEquals("com.xiaomi.market", InstallSource.installerFor(Choice.ORIGINAL, "com.xiaomi.market", SELF))
    }

    @Test fun unknownSourceDefaultsToPlayOnlyWhenPlayIsInstalled() {
        assertEquals(Choice.PLAY, InstallSource.defaultChoice(null, SELF, playPresent = true, elevated = true))
        assertEquals(Choice.SELF, InstallSource.defaultChoice(null, SELF, playPresent = false, elevated = true))
        // Recorded Play source on a phone without Play: fall back to this app, never a missing installer.
        assertEquals(Choice.SELF, InstallSource.defaultChoice(InstallSource.PLAY, SELF, playPresent = false, elevated = true))
        // Recorded store that is not installed here.
        assertEquals(Choice.PLAY, InstallSource.defaultChoice("com.xiaomi.market", SELF, true, true) { false })
    }

    @Test fun optionsListOnlyWhatMakesSense() {
        assertEquals(listOf(Choice.ORIGINAL, Choice.PLAY, Choice.SELF), InstallSource.options("com.xiaomi.market", SELF, true, true))
        // Original == Play or == this app is not a separate option.
        assertEquals(listOf(Choice.PLAY, Choice.SELF), InstallSource.options(InstallSource.PLAY, SELF, true, true))
        assertEquals(listOf(Choice.PLAY, Choice.SELF), InstallSource.options(SELF, SELF, true, true))
        assertEquals(listOf(Choice.SELF), InstallSource.options(null, SELF, playPresent = false, elevated = true))
        assertEquals(listOf(Choice.PLAY, Choice.SELF), InstallSource.options("com.xiaomi.market", SELF, true, true) { false })
    }
}

class PmCommandTest {
    @Test fun installCreateCarriesInstaller() {
        assertEquals("pm install-create -r -i 'com.android.vending' -S 1234", InstallSource.pmCreate(1234, InstallSource.PLAY))
        assertEquals("pm install-create -r -t -S 99", InstallSource.pmCreate(99, null, allowTest = true))
        assertEquals("pm install -r -t -i 'com.android.vending' '/data/local/tmp/gf/0_a b.apk'", InstallSource.pmInstall("/data/local/tmp/gf/0_a b.apk", InstallSource.PLAY))
        assertEquals("pm install -r -t '/x.apk'", InstallSource.pmInstall("/x.apk", null))
    }

    @Test fun quotingIsShellSafe() {
        assertEquals("pm set-installer 'com.x' 'com.android.vending'", InstallSource.setInstaller("com.x", InstallSource.PLAY))
        assertEquals("dumpsys package 'a'\\''b'", InstallSource.dumpsys("a'b"))
    }

    @Test fun setInstallerResult() {
        assertTrue(InstallSource.setInstallerOk("Success\n"))
        assertTrue(InstallSource.setInstallerOk(""))
        assertFalse(InstallSource.setInstallerOk("Exception occurred while executing 'set-installer':\njava.lang.SecurityException: Caller does not have same cert as old installer package com.ghostpanter.files"))
        assertFalse(InstallSource.setInstallerOk("Failed setting installer"))
        assertNotNull(InstallSource.explain("java.lang.SecurityException: Caller does not have same cert as old installer package"))
    }

    @Test fun explainsUpdateOwnership() {
        val e = InstallSource.explain("Failure [INSTALL_FAILED_SESSION_INVALID: Installer is not the update owner (UPDATE_OWNERSHIP)]")!!
        assertTrue(e.contains("更新所有权") || e.contains("update", true))
        assertNull(InstallSource.explain("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
        assertTrue(InstallSource.ownershipConflict(InstallSource.Record(InstallSource.PLAY, updateOwner = InstallSource.PLAY), SELF, 34))
        assertFalse(InstallSource.ownershipConflict(InstallSource.Record(InstallSource.PLAY, updateOwner = InstallSource.PLAY), InstallSource.PLAY, 34))
        assertFalse(InstallSource.ownershipConflict(InstallSource.Record(InstallSource.PLAY, updateOwner = InstallSource.PLAY), SELF, 33))
        assertFalse(InstallSource.ownershipConflict(InstallSource.Record(SELF), InstallSource.PLAY, 34))
    }

    @Test fun parsesDumpsysAndroid14() {
        val out = """
            Packages:
              Package [ai.x.grok] (5c1d2e3):
                userId=10321
                pkg=Package{8a7b ai.x.grok}
                installerPackageName=com.ghostpanter.files
                installerPackageUid=10288
                installerAttributionTag=null
                installInitiatingPackageName=com.ghostpanter.files
                installOriginatingPackageName=null
                updateOwnerPackageName=com.android.vending
              Package [ai.x.grok.other] (1):
                installerPackageName=com.android.vending
        """.trimIndent()
        assertEquals(InstallSource.Record(SELF, SELF, null, InstallSource.PLAY), InstallSource.parseDumpsys(out, "ai.x.grok"))
        assertEquals(InstallSource.PLAY, InstallSource.parseDumpsys(out, "ai.x.grok.other")!!.installer)
        assertNull(InstallSource.parseDumpsys(out, "com.missing"))
    }

    @Test fun parsesDumpsysAndroid9() {
        val out = "  Package [com.foo] (abc):\n    versionCode=12 minSdk=21 targetSdk=28\n    installerPackageName=null\n    timeStamp=2026-01-01"
        assertEquals(InstallSource.Record(null), InstallSource.parseDumpsys(out, "com.foo"))
    }

    @Test fun labels() {
        assertEquals("Google Play", InstallSource.knownLabel(InstallSource.PLAY, SELF))
        assertNull(InstallSource.knownLabel("com.example.store", SELF))
    }
}

class BundleMetaAndIntegrityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry() } }
        return f
    }

    private val meta = BundleMeta("ai.x.grok", "1.2.3", 123, InstallSource.Record(InstallSource.PLAY, InstallSource.PLAY, null, InstallSource.PLAY),
        "Xiaomi \"14\"", listOf("arm64-v8a"), 440, listOf("zh-CN", "en-US"), 1_792_600_000_000)

    @Test fun metaRoundTripsAndIsReadFromOwnBackups() {
        assertEquals(meta, BundleMeta.parse(meta.toJson()))
        assertNull(BundleMeta.parse("{\"format\": \"other\"}"))
        assertEquals(BundleMeta(null), BundleMeta.parse("{\"format\": \"ghost-app\", \"installer\": null}"))
        val b = ApkBundle.read(zip("grok.apks", BundleMeta.NAME to meta.toJson(), "base.apk" to "b", "split_config.arm64_v8a.apk" to "a"))
        assertEquals(InstallSource.PLAY, b.meta!!.source.installer)
        assertEquals("ai.x.grok", b.packageName)
        assertEquals(2, b.splits.size) // the json is not an APK
    }

    @Test fun thirdPartyBundlesHaveUnknownSource() {
        val b = ApkBundle.read(zip("x.xapk", "manifest.json" to "{\"package_name\":\"com.x\",\"split_apks\":[{\"file\":\"com.x.apk\",\"id\":\"base\"}]}", "com.x.apk" to "b"))
        assertNull(b.meta)
    }

    private val arm = ApkBundle.Device(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), 440, listOf("zh-CN", "en-US"))

    @Test fun completeSetHasNoIssues() {
        val b = ApkBundle.read(zip("ok.apks", "base.apk" to "b", "split_config.arm64_v8a.apk" to "a", "split_config.xxhdpi.apk" to "d", "split_config.zh.apk" to "z"))
        assertTrue(b.integrity(arm, b.select(arm)).isEmpty())
    }

    @Test fun backupFromAnotherAbiIsAnError() {
        // Backed up on an x86_64 emulator / tablet, restored on an arm64 phone.
        val b = ApkBundle.read(zip("x86.apks", "base.apk" to "b", "split_config.x86_64.apk" to "x", "split_config.xhdpi.apk" to "d", "split_config.en.apk" to "e"))
        val zhOnly = arm.copy(languages = listOf("zh-CN"))
        val issues = b.integrity(zhOnly, b.select(zhOnly))
        val abi = issues.single { it.kind == Kind.ABI }
        assertEquals(Severity.ERROR, abi.severity)
        assertTrue(abi.text.contains("arm64-v8a") && abi.text.contains("x86-64"))
        // Only English on a Chinese-only phone: info, not a blocker.
        assertEquals(Severity.INFO, issues.single { it.kind == Kind.LANGUAGE }.severity)
        assertTrue(issues.none { it.kind == Kind.DENSITY })
    }

    @Test fun uncheckedConfigSplitsAreReported() {
        val b = ApkBundle.read(zip("u.apks", "base.apk" to "b", "split_config.arm64_v8a.apk" to "a", "split_config.xxhdpi.apk" to "d", "split_config.zh.apk" to "z"))
        val issues = b.integrity(arm, setOf("base.apk"))
        assertEquals(setOf(Kind.ABI, Kind.DENSITY, Kind.LANGUAGE), issues.map { it.kind }.toSet())
        assertEquals(Severity.ERROR, issues.first { it.kind == Kind.ABI }.severity)
        assertTrue(issues.first { it.kind == Kind.ABI }.text.contains("没有勾选") || issues.first { it.kind == Kind.ABI }.text.contains("not selected"))
        assertEquals(Severity.WARN, issues.first { it.kind == Kind.DENSITY }.severity)
    }

    @Test fun featureModulesAreCheckedOnlyWhenInstalled() {
        val b = ApkBundle.read(zip("f.apks", "toc.pb" to "", "splits/base-master.apk" to "m", "splits/base-arm64_v8a.apk" to "a",
            "splits/camera-master.apk" to "c", "splits/camera-x86_64.apk" to "cx"))
        val with = b.integrity(arm, setOf("splits/base-master.apk", "splits/base-arm64_v8a.apk", "splits/camera-master.apk"))
        assertEquals("camera", with.single().owner)
        assertTrue(b.integrity(arm, setOf("splits/base-master.apk", "splits/base-arm64_v8a.apk")).isEmpty())
    }

    @Test fun pureJavaAppsWithoutConfigSplitsAreFine() {
        val b = ApkBundle.read(zip("j.apks", "base.apk" to "b", "split_feature.apk" to "f"))
        assertTrue(b.integrity(arm, b.select(arm)).isEmpty())
    }
}
