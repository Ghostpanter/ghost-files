package com.ghostpanter.files.core.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSelectTest {
    @Test fun prefersExternalWhenGranted() {
        val k = selectPrivilege(PrivilegeInputs(
            externalInstalled = true, externalRunning = true, externalGranted = true, embeddedAlive = true,
        ))
        assertEquals(PrivilegeKind.EXTERNAL_SHIZUKU, k)
        assertTrue(privilegeReady(k))
    }

    @Test fun fallsBackToEmbedded() {
        assertEquals(
            PrivilegeKind.EMBEDDED,
            selectPrivilege(PrivilegeInputs(true, true, false, embeddedAlive = true)),
        )
        assertEquals(
            PrivilegeKind.EMBEDDED,
            selectPrivilege(PrivilegeInputs(false, false, false, embeddedAlive = true)),
        )
    }

    @Test fun noneWhenNeither() {
        assertEquals(
            PrivilegeKind.NONE,
            selectPrivilege(PrivilegeInputs(true, false, false, embeddedAlive = false)),
        )
        assertFalse(privilegeReady(PrivilegeKind.NONE))
    }

    @Test fun externalInstalledButNotGrantedUsesEmbedded() {
        assertEquals(
            PrivilegeKind.EMBEDDED,
            selectPrivilege(PrivilegeInputs(true, true, false, true)),
        )
    }

    @Test fun tierRootBeatsShizuku() {
        assertEquals(PrivilegeTier.ROOT, selectTier(true, PrivilegeKind.EXTERNAL_SHIZUKU))
        assertEquals(PrivilegeTier.ROOT, selectTier(true, PrivilegeKind.EMBEDDED))
        assertEquals(PrivilegeTier.ROOT, selectTier(true, PrivilegeKind.NONE))
        assertTrue(tierReady(PrivilegeTier.ROOT))
    }

    @Test fun tierShizukuWhenNoRoot() {
        assertEquals(PrivilegeTier.EXTERNAL_SHIZUKU, selectTier(false, PrivilegeKind.EXTERNAL_SHIZUKU))
        assertEquals(PrivilegeTier.EMBEDDED, selectTier(false, PrivilegeKind.EMBEDDED))
        assertEquals(PrivilegeTier.NONE, selectTier(false, PrivilegeKind.NONE))
        assertFalse(tierReady(PrivilegeTier.NONE))
    }

    @Test fun drawerMatrixRootCoversShizuku() {
        val elev = elevatedDrawerRow(rootGranted = true, kind = PrivilegeKind.NONE)
        assertEquals("Shizuku", elev.title)
        assertTrue(elev.subtitle.startsWith("Root 已授权") || elev.subtitle.startsWith("Root granted"))
        assertTrue(elev.subtitle.contains("Android/data"))
        assertFalse(elev.needsPrompt)
        assertTrue(elev.coveredByRoot)
        assertFalse(elev.canReauth)
        assertTrue(elev.canAuthorizeInMenu)

        val root = rootDrawerRow(rootGranted = true, shell = PrivilegeKind.NONE)
        assertFalse(root.needsPrompt)
        assertTrue(root.canReauth)
        assertTrue(root.subtitle.contains("已授权") || root.subtitle.contains("Granted"))
    }

    @Test fun drawerMatrixShizukuOnlyCoversRoot() {
        for (shell in listOf(PrivilegeKind.EXTERNAL_SHIZUKU, PrivilegeKind.EMBEDDED)) {
            val root = rootDrawerRow(rootGranted = false, shell = shell)
            assertFalse(root.needsPrompt)
            assertFalse(root.canReauth)
            assertTrue(root.canAuthorizeInMenu)
            assertTrue(root.subtitle.contains("Shizuku 已授权") || root.subtitle.contains("Shizuku granted"))
            assertTrue(root.subtitle.contains("无 Root") || root.subtitle.contains("no Root"))

            val elev = elevatedDrawerRow(rootGranted = false, kind = shell)
            assertFalse(elev.needsPrompt)
            assertTrue(elev.canReauth)
            assertFalse(elev.coveredByRoot)
            assertTrue(elev.subtitle.contains("Android/data"))
        }
        val embTitle = elevatedDrawerRow(false, PrivilegeKind.EMBEDDED).title
        assertTrue(embTitle.contains("内置") || embTitle.contains("Built-in"))
    }

    @Test fun drawerMatrixNeitherShowsUnauthorized() {
        val elev = elevatedDrawerRow(false, PrivilegeKind.NONE)
        assertTrue(elev.needsPrompt)
        assertTrue(elev.subtitle.contains("未授权") || elev.subtitle.contains("Not granted"))
        assertFalse(elev.canReauth)
        assertFalse(elev.coveredByRoot)

        val root = rootDrawerRow(false, PrivilegeKind.NONE)
        assertTrue(root.needsPrompt)
        assertFalse(root.canReauth)
        assertTrue(root.subtitle.contains("点此") || root.subtitle.contains("Tap"))
    }

    @Test fun drawerMatrixBothRootHighest() {
        val elev = elevatedDrawerRow(true, PrivilegeKind.EXTERNAL_SHIZUKU)
        assertTrue(elev.coveredByRoot)
        assertTrue(elev.subtitle.contains("Root"))
        assertFalse(elev.needsPrompt)
        val root = rootDrawerRow(true, PrivilegeKind.EXTERNAL_SHIZUKU)
        assertTrue(root.canReauth)
        assertFalse(root.needsPrompt)
    }

    @Test fun englishCopy() {
        val elev = elevatedDrawerRow(true, PrivilegeKind.NONE, en = true)
        assertTrue(elev.subtitle.startsWith("Root granted"))
        val root = rootDrawerRow(false, PrivilegeKind.EXTERNAL_SHIZUKU, en = true)
        assertTrue(root.subtitle.startsWith("Shizuku granted"))
    }

    @Test fun labels() {
        assertEquals("Shizuku", privilegeLabel(PrivilegeKind.EXTERNAL_SHIZUKU))
        assertEquals("内置提权", privilegeLabel(PrivilegeKind.EMBEDDED))
        assertEquals("Built-in privilege", privilegeLabel(PrivilegeKind.EMBEDDED, en = true))
        assertEquals("Root", tierLabel(PrivilegeTier.ROOT))
        assertEquals("内置提权", tierLabel(PrivilegeTier.EMBEDDED))
    }
}
