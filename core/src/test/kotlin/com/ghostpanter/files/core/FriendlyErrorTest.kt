package com.ghostpanter.files.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyErrorTest {
    @Test fun rawProcessErrorBecomesChinese() {
        val m = FriendlyError.describe(IllegalArgumentException("process hasn't exited"), "Shizuku")
        assertTrue(m.text, m.text.contains("Shizuku") && m.text.contains("重试"))
        assertFalse(m.text.contains("process hasn't exited"))
        assertTrue(m.detail!!.contains("process hasn't exited"))
    }

    @Test fun commonCases() {
        assertTrue(FriendlyError.describe(ProcTimeoutException(60_000), "Shizuku").text.contains("超时"))
        assertTrue(FriendlyError.describe(java.io.IOException("x", FsException("ls: /data: Permission denied"))).text.contains("没有权限"))
        assertTrue(FriendlyError.describe(java.io.FileNotFoundException("/a")).text.contains("不存在"))
        assertTrue(FriendlyError.describe(java.io.IOException("su not available")).text.contains("Root"))
        assertEquals("服务器配置已删除", FriendlyError.describe(FsException("服务器配置已删除")).text)
        assertTrue(FriendlyError.describe(RuntimeException("weird")).text.contains("操作失败"))
    }
}
