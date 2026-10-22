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

    @Test fun drawerRowMergedNoPromptWhenGranted() {
        val ext = elevatedDrawerRow(PrivilegeKind.EXTERNAL_SHIZUKU)
        assertEquals("Shizuku", ext.title)
        assertTrue(ext.subtitle.contains("Android/data"))
        assertTrue(ext.subtitle.contains("obb"))
        assertFalse(ext.needsPrompt)

        val emb = elevatedDrawerRow(PrivilegeKind.EMBEDDED)
        assertTrue(emb.title.contains("内置") || emb.title.contains("Built-in") || emb.title == "内置提权")
        assertFalse(emb.needsPrompt)
    }

    @Test fun drawerRowPromptsOnlyWhenNone() {
        val n = elevatedDrawerRow(PrivilegeKind.NONE)
        assertTrue(n.needsPrompt)
        assertTrue(n.subtitle.contains("未授权") || n.subtitle.contains("Not granted"))
    }

    @Test fun rootNoPromptWhenGranted() {
        assertFalse(rootDrawerRow(true).needsPrompt)
        assertTrue(rootDrawerRow(false).needsPrompt)
        assertTrue(rootDrawerRow(true).subtitle.contains("已授权") || rootDrawerRow(true, en = true).subtitle.contains("Granted"))
    }

    @Test fun labels() {
        assertEquals("Shizuku", privilegeLabel(PrivilegeKind.EXTERNAL_SHIZUKU))
        assertEquals("内置提权", privilegeLabel(PrivilegeKind.EMBEDDED))
        assertEquals("Built-in privilege", privilegeLabel(PrivilegeKind.EMBEDDED, en = true))
    }
}
