package com.ghostpanter.files.core.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeStarterTest {
    private val apk = "/data/app/~~m9G1==/com.ghostpanter.files-nIAf==/base.apk"

    @Test fun scriptNeverKillsItsOwnShell() {
        val s = PrivilegeStarter.script(apk, "com.ghostpanter.files", 10043)
        assertFalse("pkill -f matches the parent sh -c", s.contains("pkill -f"))
        assertTrue(s.contains("pidof ghost-priv"))
    }

    @Test fun scriptDetachesAndClearsLog() {
        val s = PrivilegeStarter.script(apk, "com.ghostpanter.files", 10043)
        assertTrue(s.contains("nohup"))
        assertTrue(s.contains("setsid"))
        assertTrue(s.contains("</dev/null"))
        assertTrue(s.contains("rm -f \$L"))
        assertTrue(s.contains("CLASSPATH='$apk'"))
        assertTrue(s.contains("PrivilegeServer 'com.ghostpanter.files' 10043"))
        assertTrue(s.contains(PrivilegeStarter.STARTED))
    }

    @Test fun diagnosis() {
        val d1 = PrivilegeStarter.diagnose("ghost-priv send binder failed: SecurityException\n#PIDS:1234", "无线调试")
        assertTrue(d1.message.contains("拒绝"))
        val d2 = PrivilegeStarter.diagnose("starter: uid=2000(shell)\n#PIDS:", "无线调试")
        assertTrue(d2.message.contains("没有启动"))
        val d3 = PrivilegeStarter.diagnose("java.lang.ClassNotFoundException: x\n#PIDS:", "Root")
        assertTrue(d3.message.contains("APK"))
        val d4 = PrivilegeStarter.diagnose(null, "无线调试")
        assertTrue(d4.message.contains("无法读取"))
    }
}
