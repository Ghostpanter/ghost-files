package com.ghostpanter.files.core.apk

import com.ghostpanter.files.core.ShellResult
import com.ghostpanter.files.core.apk.SystemApp.Kind
import com.ghostpanter.files.core.apk.SystemApp.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class SystemAppTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(script: String): ShellResult {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return ShellResult(p.exitValue(), out, "")
    }

    @Test fun parsesEnv() {
        val e = SystemApp.parseEnv("uid=0\nmodules=1\nmgr=KernelSU\ndyn=true\nslot=_a\nsdk=34\n")
        assertTrue(e.uid0); assertTrue(e.modules); assertEquals("KernelSU", e.manager); assertTrue(e.dynamic); assertEquals("_a", e.slot); assertEquals(34, e.sdk)
        assertFalse(SystemApp.parseEnv("uid=2000\nmodules=0\n").uid0)
    }

    @Test fun parsesRequestedPermissionsFirstBlockOnly() {
        val d = """
            Packages:
              Package [com.x.y] (abc):
                userId=10123
                requested permissions:
                  android.permission.INTERNET
                  android.permission.WRITE_SECURE_SETTINGS
                  android.permission.POST_NOTIFICATIONS: restricted=true
                install permissions:
                  android.permission.INTERNET: granted=true
            Hidden system packages:
              Package [com.x.y] (def):
                requested permissions:
                  android.permission.EVIL
        """.trimIndent()
        assertEquals(listOf("android.permission.INTERNET", "android.permission.WRITE_SECURE_SETTINGS", "android.permission.POST_NOTIFICATIONS"),
            SystemApp.parseRequestedPermissions(d))
        val xml = SystemApp.privappXml("com.x.y", listOf("a.b.C", "bad\"><x", "a.b.C"))
        assertTrue(xml.contains("<privapp-permissions package=\"com.x.y\">"))
        assertEquals(1, Regex("a.b.C").findAll(xml).count())
        assertFalse(xml.contains("bad"))
    }

    @Test fun layoutAndNames() {
        val l = SystemApp.layout("com.x.y", listOf("/data/app/~~a/com.x.y-1/split_config.arm64_v8a.apk", "/data/app/~~a/com.x.y-1/base.apk"))
        assertEquals("/data/app/~~a/com.x.y-1/base.apk" to "com.x.y.apk", l[0])
        assertEquals("split_config.arm64_v8a.apk", l[1].second)
        assertEquals("ghostfiles_sysapp_com_x_y", SystemApp.moduleId("com.x.y"))
        assertTrue(SystemApp.validPackage("com.x.y")); assertFalse(SystemApp.validPackage("com.x;rm -rf /"))
    }

    @Test fun moduleScriptBuildsMagiskLayout() {
        val root = tmp.newFolder("r").path
        val src = File(tmp.root, "data/app/~~q/com.x.y-1").apply { mkdirs() }
        File(src, "base.apk").writeText("BASE")
        File(src, "split_config.arm64_v8a.apk").writeText("SPLIT")
        File(src, "lib/arm64").apply { mkdirs() }.resolve("libz.so").writeText("SO")
        val s = SystemApp.moduleScript("com.x.y", "My 'App'\nX", listOf(File(src, "base.apk").path, File(src, "split_config.arm64_v8a.apk").path),
            Kind.PRIV_APP, SystemApp.privappXml("com.x.y", listOf("android.permission.WRITE_SECURE_SETTINGS")), root)
        val r = sh(s)
        assertEquals(r.stdout, 0, r.code)
        assertTrue(r.stdout.contains(SystemApp.OK))
        val m = File(SystemApp.moduleDir("com.x.y", root))
        val d = File(m, "system/priv-app/com.x.y")
        assertEquals("BASE", File(d, "com.x.y.apk").readText())
        assertEquals("SPLIT", File(d, "split_config.arm64_v8a.apk").readText())
        assertEquals("SO", File(d, "lib/arm64/libz.so").readText())
        val prop = File(m, "module.prop").readText()
        assertTrue(prop, prop.contains("id=ghostfiles_sysapp_com_x_y"))
        assertEquals(6, prop.lines().filter { it.isNotEmpty() }.size)
        assertTrue(File(m, "system/etc/permissions/privapp-permissions-com.x.y.xml").readText().contains("WRITE_SECURE_SETTINGS"))
        assertEquals("rw-r--r--", java.nio.file.Files.getPosixFilePermissions(File(d, "com.x.y.apk").toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })
        assertEquals("rwxr-xr-x", java.nio.file.Files.getPosixFilePermissions(d.toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })

        val st = SystemApp.parseStatus("com.x.y", sh(SystemApp.statusScript("com.x.y", root)).stdout)
        assertNotNull(st); assertEquals(Method.MODULE, st!!.method); assertEquals(Kind.PRIV_APP, st.kind); assertFalse(st.removing)
        assertEquals(0, sh(SystemApp.revertModuleScript("com.x.y", root)).code)
        assertTrue(SystemApp.parseStatus("com.x.y", sh(SystemApp.statusScript("com.x.y", root)).stdout)!!.removing)
        assertNull(SystemApp.parseStatus("com.other", sh(SystemApp.statusScript("com.other", root)).stdout))
    }

    @Test fun convertWithoutRootGivesChineseError() {
        val exec = SystemApp.Exec { _, _ -> ShellResult(0, "uid=2000\nmodules=0\n", "") }
        try { SystemApp.convert(exec, "com.x.y", "X", Kind.APP); fail() } catch (e: SystemApp.Failure) { assertTrue(e.message!!.contains("Root")) }
    }

    @Test fun convertPicksModuleWhenModulesDirExists() {
        val seen = ArrayList<String>()
        val exec = SystemApp.Exec { s, _ ->
            seen += s
            when {
                s == SystemApp.PROBE -> ShellResult(0, "uid=0\nmodules=1\nmgr=Magisk\ndyn=true\n", "")
                s.startsWith("M=") -> ShellResult(0, "", "")
                s.startsWith("pm path") -> ShellResult(0, "package:/data/app/~~a/com.x.y-1/base.apk\n", "")
                s.startsWith("set -e\nM=") -> ShellResult(0, "${SystemApp.OK} module \$M/system/app/com.x.y\n", "")
                else -> ShellResult(1, "?", "")
            }
        }
        val r = SystemApp.convert(exec, "com.x.y", "X", Kind.APP)
        assertEquals(Method.MODULE, r.method)
        assertTrue(seen.none { it.contains("dumpsys") })
    }

    @Test fun directRemountFailureExplainsDynamicPartitions() {
        val exec = SystemApp.Exec { s, _ ->
            when {
                s == SystemApp.PROBE -> ShellResult(0, "uid=0\nmodules=0\nmgr=\ndyn=true\n", "")
                s.startsWith("M=") -> ShellResult(0, "", "")
                s.startsWith("pm path") -> ShellResult(0, "package:/data/app/x/base.apk\n", "")
                else -> ShellResult(3, "GF_SYSAPP_ERR_REMOUNT\n", "")
            }
        }
        try { SystemApp.convert(exec, "com.x.y", "X", Kind.APP); fail() } catch (e: SystemApp.Failure) { assertTrue(e.message!!, e.message!!.contains("Magisk")) }
    }

    @Test fun refusesAlreadySystem() {
        val exec = SystemApp.Exec { s, _ ->
            when {
                s == SystemApp.PROBE -> ShellResult(0, "uid=0\nmodules=1\n", "")
                s.startsWith("pm path") -> ShellResult(0, "package:/system/app/X/X.apk\n", "")
                else -> ShellResult(0, "", "")
            }
        }
        try { SystemApp.convert(exec, "com.x.y", "X", Kind.APP); fail() } catch (e: SystemApp.Failure) { }
    }
}
