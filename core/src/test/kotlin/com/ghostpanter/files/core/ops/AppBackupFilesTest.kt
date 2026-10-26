package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.ops.AppBackupFiles.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class AppBackupFilesTest {
    @Test fun names() {
        assertEquals("com.tencent.mm-data.tar", AppBackupFiles.dataTarName("com.tencent.mm"))
        assertEquals("com.tencent.mm", AppBackupFiles.dataTarPackage("com.tencent.mm-data.tar"))
        assertNull(AppBackupFiles.dataTarPackage("backup.tar"))
        assertNull(AppBackupFiles.dataTarPackage("foo bar-data.tar"))
        assertEquals("com.a.b_微信_8.0.1.apks", AppBackupFiles.apkName("com.a.b", "微信", "8.0.1", true))
        assertEquals("com.a.b_My_App_1.0.apk", AppBackupFiles.apkName("com.a.b", "My App", "1.0", false))
        assertTrue(AppBackupFiles.apkNameMatches("com.a.b_x_1.apk", "com.a.b"))
        assertTrue(AppBackupFiles.apkNameMatches("com.a.b.apks", "com.a.b"))
        assertFalse(AppBackupFiles.apkNameMatches("com.a.bc_x.apk", "com.a.b"))
        assertFalse(AppBackupFiles.apkNameMatches("com.a.b_x.tar", "com.a.b"))
    }

    @Test fun partFlags() {
        assertTrue(AppBackupFiles.Part.BOTH.apk && AppBackupFiles.Part.BOTH.data)
        assertFalse(AppBackupFiles.Part.APK.data)
        assertFalse(AppBackupFiles.Part.DATA.apk)
    }

    @Test fun pickNewestMatchingApk() {
        val files = listOf(
            Candidate("com.a.b_A_1.apk", 100), Candidate("com.a.b_A_2.apks", 200),
            Candidate("微信_8.0.apk", 300), Candidate("com.a.b-data.tar", 400),
        )
        // legacy name resolved through content: newest wins
        val legacy = mapOf("微信_8.0.apk" to "com.a.b")
        assertEquals("微信_8.0.apk", AppBackupFiles.pickApk(files, "com.a.b") { legacy[it] }?.name)
        assertEquals("com.a.b_A_2.apks", AppBackupFiles.pickApk(files, "com.a.b") { null }?.name)
        // name says com.a.b_… but content says another package (com.a.b_c): skip it
        val clash = listOf(Candidate("com.a.b_c_1.apk", 500), Candidate("com.a.b_A_1.apk", 100))
        assertEquals("com.a.b_A_1.apk", AppBackupFiles.pickApk(clash, "com.a.b") { if (it.startsWith("com.a.b_c")) "com.a.b_c" else "com.a.b" }?.name)
        assertNull(AppBackupFiles.pickApk(files, "com.x.y") { null })
        assertEquals("com.a.b-data.tar", AppBackupFiles.pickData(files, "com.a.b")?.name)
        assertNull(AppBackupFiles.pickData(files, "com.x.y"))
    }

    @Test fun cacheGid() {
        assertEquals(20_123, AppBackupFiles.cacheGid(10_123))
        assertEquals(1_020_123, AppBackupFiles.cacheGid(1_010_123))
    }

    @Test(expected = IllegalArgumentException::class) fun scriptRejectsBadPackage() {
        AppBackupFiles.restoreScript("a;rm -rf /", 10_100, "/data/local/tmp/x.tar")
    }

    /** Runs the restore script against a fake /data with stub am/chown/restorecon. */
    @Test fun restoreScriptRuns() {
        val sh = File("/bin/sh")
        assumeTrue(sh.canExecute() && File("/bin/tar").exists() || File("/usr/bin/tar").exists())
        val root = Files.createTempDirectory("gfrestore").toFile()
        try {
            val dataData = File(root, "data/data").apply { mkdirs() }
            val tmpDir = File(root, "data/local/tmp").apply { mkdirs() }
            val bin = File(root, "bin").apply { mkdirs() }
            val log = File(root, "log")
            for (n in listOf("am", "chown", "restorecon")) {
                File(bin, n).apply { writeText("#!/bin/sh\necho $n \"$@\" >> ${log.path}\n"); setExecutable(true) }
            }
            val pkg = "com.example.app"
            // current install: old file + lib link that must survive
            val app = File(dataData, pkg).apply { mkdirs() }
            File(app, "shared_prefs").mkdirs(); File(app, "shared_prefs/old.xml").writeText("old")
            File(app, "lib").mkdirs(); File(app, "lib/keep").writeText("lib")
            // backup tar: pkg/{databases/db, cache/c, lib/stale}
            val src = File(root, "src/$pkg").apply { mkdirs() }
            File(src, "databases").mkdirs(); File(src, "databases/db").writeText("db")
            File(src, "cache").mkdirs(); File(src, "cache/c").writeText("c")
            File(src, "lib").mkdirs(); File(src, "lib/stale").writeText("stale")
            val tar = File(tmpDir, "gf-restore-$pkg.tar")
            assertEquals(0, ProcessBuilder("tar", "-C", File(root, "src").path, "-cf", tar.path, pkg).start().waitFor())

            val script = AppBackupFiles.restoreScript(pkg, 10_123, "/data/local/tmp/gf-restore-$pkg.tar")
                .replace("/data/", root.path + "/data/")
            val p = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true)
            p.environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
            val proc = p.start()
            val out = proc.inputStream.bufferedReader().readText()
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
            assertEquals(out, 0, proc.exitValue())
            assertTrue(out, out.contains("OK"))
            assertEquals("db", File(app, "databases/db").readText())
            assertFalse(File(app, "shared_prefs/old.xml").exists())
            assertEquals("lib", File(app, "lib/keep").readText())
            assertFalse(File(app, "lib/stale").exists())
            assertFalse(tar.exists())
            val l = log.readText()
            assertTrue(l, l.contains("am force-stop $pkg"))
            assertTrue(l, l.contains("chown -R 10123:10123 ") && l.contains("/databases"))
            assertTrue(l, l.contains("chown -R 10123:20123 ") && l.contains("/cache"))
            assertFalse(l, Regex("chown -R \\S+ \\S*/lib\\b").containsMatchIn(l))
            assertTrue(l, l.contains("restorecon -RF"))
        } finally { root.deleteRecursively() }
    }

    @Test fun restoreScriptRejectsWrongTar() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = Files.createTempDirectory("gfrestore2").toFile()
        try {
            val pkg = "com.example.app"
            File(root, "data/data/$pkg").mkdirs()
            val tmpDir = File(root, "data/local/tmp").apply { mkdirs() }
            File(root, "src/other.pkg").mkdirs(); File(root, "src/other.pkg/x").writeText("x")
            val tar = File(tmpDir, "t.tar")
            ProcessBuilder("tar", "-C", File(root, "src").path, "-cf", tar.path, "other.pkg").start().waitFor()
            val bin = File(root, "bin").apply { mkdirs() }
            for (n in listOf("am", "chown", "restorecon")) File(bin, n).apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            val script = AppBackupFiles.restoreScript(pkg, 10_123, "/data/local/tmp/t.tar").replace("/data/", root.path + "/data/")
            val pb = ProcessBuilder("/bin/sh", "-c", script)
            pb.environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
            val proc = pb.start()
            val err = proc.errorStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            assertEquals(3, proc.exitValue())
            assertEquals("BAD_TAR", AppBackupFiles.explainRestore(err))
        } finally { root.deleteRecursively() }
    }
}
