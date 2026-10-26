package com.ghostpanter.files.ui

import com.ghostpanter.files.core.ops.AppBackupFiles.Part
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackupOptionsTest {
    private fun enabled(o: List<PartOption>) = o.filter { it.enabled }.map { it.part }.toSet()

    @Test fun backupWithoutRootOnlyApk() {
        assertEquals(setOf(Part.APK), enabled(backupOptions(rootOk = false)))
        assertEquals(setOf(Part.APK, Part.DATA, Part.BOTH), enabled(backupOptions(rootOk = true)))
    }

    @Test fun restoreSingleNoElevationUsesInstaller() {
        val o = restoreOptions(rootOk = false, silentOk = false, hasApk = true, hasData = true, installed = true, allowSystemInstaller = true)
        assertEquals(setOf(Part.APK), enabled(o))
    }

    @Test fun restoreBatchNeedsSilent() {
        val o = restoreOptions(rootOk = false, silentOk = false, hasApk = true, hasData = true, installed = true, allowSystemInstaller = false)
        assertEquals(emptySet<Part>(), enabled(o))
        val s = restoreOptions(rootOk = false, silentOk = true, hasApk = true, hasData = true, installed = true, allowSystemInstaller = false)
        assertEquals(setOf(Part.APK), enabled(s)) // Shizuku: APK yes, data no
    }

    @Test fun restoreWithRoot() {
        assertEquals(setOf(Part.APK, Part.DATA, Part.BOTH),
            enabled(restoreOptions(true, true, hasApk = true, hasData = true, installed = true, allowSystemInstaller = true)))
        // only data backup present
        assertEquals(setOf(Part.DATA), enabled(restoreOptions(true, true, hasApk = false, hasData = true, installed = true, allowSystemInstaller = true)))
        // app not installed: data alone impossible, both works when APK exists
        assertEquals(setOf(Part.APK, Part.BOTH), enabled(restoreOptions(true, true, hasApk = true, hasData = true, installed = false, allowSystemInstaller = false)))
        assertEquals(emptySet<Part>(), enabled(restoreOptions(true, true, hasApk = false, hasData = true, installed = false, allowSystemInstaller = false)))
    }
}
