package com.ghostpanter.files.core

import com.ghostpanter.files.core.ops.Archives
import com.ghostpanter.files.core.ops.Compare
import com.ghostpanter.files.core.ops.ConfigBackup
import com.ghostpanter.files.core.ops.ContentSearch
import com.ghostpanter.files.core.ops.SymlinkOps
import com.ghostpanter.files.core.ops.ZipEdit
import com.ghostpanter.files.core.server.HostedKind
import com.ghostpanter.files.core.server.HostedServiceConfig
import com.ghostpanter.files.core.server.HttpRemoteServer
import com.ghostpanter.files.core.server.randomPassword
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class OpsV050Test {
    private val fs = LocalFs()

    private fun tempDir(): File = File.createTempFile("gf50", "d").also { it.delete(); it.mkdirs() }

    private fun File.writeRandom(n: Int): ByteArray {
        parentFile?.mkdirs()
        val b = ByteArray(n) { (it % 251).toByte() }
        writeBytes(b); return b
    }

    @Test fun encryptedZipRoundTrip() {
        val src = tempDir(); val out = tempDir()
        val a = File(src, "secret.txt").also { it.writeText("hello-aes-zip") }
        val zip = File(out, "enc.zip")
        ZipEdit.compressEncrypted(fs, listOf(fs.stat(a.path)!!), zip, "p@ss-123")
        assertTrue(zip.length() > 20)
        val dest = File(out, "x")
        Archives.extract(fs, zip.path, fs, dest.path, localFile = zip, password = "p@ss-123")
        assertEquals("hello-aes-zip", File(dest, "secret.txt").readText())
    }

    @Test fun zipInArchiveAddDelete() {
        val d = tempDir()
        val a = File(d, "a.txt").also { it.writeText("A") }
        val zip2 = File(d, "u.zip")
        Archives.compressZip(fs, listOf(fs.stat(a.path)!!), fs, zip2.path)
        val b = File(d, "b.txt").also { it.writeText("B") }
        ZipEdit.addOrUpdate(zip2, b, "b.txt")
        assertTrue(ZipEdit.listEntries(zip2).any { it == "b.txt" })
        ZipEdit.removeEntries(zip2, listOf("a.txt"))
        val names = ZipEdit.listEntries(zip2)
        assertFalse(names.any { it == "a.txt" || it.startsWith("a.txt") })
        assertTrue(names.any { it == "b.txt" })
    }

    @Test fun encryptedZipRequiresPassword() {
        try {
            ZipEdit.compressEncrypted(fs, emptyList(), File(tempDir(), "x.zip"), "")
            fail("expected")
        } catch (e: Exception) {
            assertTrue(e.message ?: "", (e.message ?: "").contains("密码") || (e.message ?: "").contains("password") || (e.message ?: "").isNotEmpty())
        }
    }

    @Test fun contentSearchFindsLine() {
        val d = tempDir()
        File(d, "a.txt").writeText("alpha\nhello world\nomega")
        File(d, "b.bin").writeBytes(ByteArray(100) { 0 })
        val hits = ArrayList<ContentSearch.Hit>()
        ContentSearch.search(fs, d.path, ContentSearch.Options("hello", ignoreCase = true), onHit = { hits.add(it) })
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].line)
        assertTrue(hits[0].preview.contains("hello"))
    }

    @Test fun compareTreesAndTextDiff() {
        val a = tempDir(); val b = tempDir()
        File(a, "same.txt").writeText("x"); File(b, "same.txt").writeText("x")
        File(a, "onlyL.txt").writeText("L"); File(b, "onlyR.txt").writeText("R")
        File(a, "diff.txt").writeText("one\ntwo\n"); File(b, "diff.txt").writeText("one\nTWO\n")
        val rows = Compare.compareTrees(fs, a.path, fs, b.path, hashFiles = true)
        assertTrue(rows.any { it.rel == "onlyL.txt" && it.kind == Compare.Kind.LEFT_ONLY })
        assertTrue(rows.any { it.rel == "onlyR.txt" && it.kind == Compare.Kind.RIGHT_ONLY })
        assertTrue(rows.any { it.rel == "diff.txt" && it.kind == Compare.Kind.DIFFERENT })
        assertTrue(rows.any { it.rel == "same.txt" && it.kind == Compare.Kind.SAME })
        val td = Compare.textDiff("one\ntwo\n", "one\nTWO\n")
        assertTrue(td.unified.any { it.startsWith("-two") })
        assertTrue(td.unified.any { it.startsWith("+TWO") })
    }

    @Test fun configBackupRoundTrip() {
        val f = File(tempDir(), "b.gfbackup")
        val snap = ConfigBackup.Snapshot(
            settingsJson = ConfigBackup.settingsToJson(true, true, true, false, "NAME", 0.5f, 2, 1),
            bookmarksJson = ConfigBackup.bookmarksToJson(listOf(Triple("DL", "local", "/sdcard/Download"))),
            serversJson = "[]",
        )
        ConfigBackup.exportZip(f, snap)
        val back = ConfigBackup.importZip(f)
        assertTrue(back.settingsJson.contains("show_hidden"))
        assertEquals(1, ConfigBackup.parseBookmarks(back.bookmarksJson).size)
    }

    @Test fun symlinkLocal() {
        val d = tempDir()
        val target = File(d, "t.txt").also { it.writeText("T") }
        val link = File(d, "l.txt").path
        SymlinkOps.createLink(link, target.path)
        assertTrue(File(link).exists() || java.nio.file.Files.isSymbolicLink(File(link).toPath()))
    }

    @Test fun httpRemoteListsAndAuth() {
        val root = tempDir()
        File(root, "hi.txt").writeText("hi")
        val srv = HttpRemoteServer()
        val cfg = HostedServiceConfig(
            kind = HostedKind.HTTP, port = 0, username = "u", password = "p",
            rootPath = root.path, anonymous = false, lanOnly = false,
        )
        // bind ephemeral: start with port 0 via mutating — HostedServiceConfig effectivePort uses kind default if 0.
        // Pick a free port
        val free = java.net.ServerSocket(0).use { it.localPort }
        val st = srv.start(cfg.copy(port = free), InetAddress.getLoopbackAddress().hostAddress)
        assertTrue(st.error == null || st.running)
        try {
            val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
            val noAuth = client.newCall(Request.Builder().url("http://127.0.0.1:$free/").build()).execute()
            assertEquals(401, noAuth.code)
            val ok = client.newCall(
                Request.Builder().url("http://127.0.0.1:$free/")
                    .header("Authorization", Credentials.basic("u", "p")).build(),
            ).execute()
            assertEquals(200, ok.code)
            val body = ok.body!!.string()
            assertTrue(body.contains("hi.txt"))
            assertTrue(body.contains("双栏文件管理器"))
        } finally {
            srv.stop()
        }
    }

    @Test fun archiveKindsDetectExtras() {
        assertEquals(Archives.Kind.ISO, Archives.kindOf("a.iso"))
        assertEquals(Archives.Kind.ZST, Archives.kindOf("a.zst"))
        assertEquals(Archives.Kind.CAB, Archives.kindOf("a.cab"))
        assertEquals(Archives.Kind.DMG, Archives.kindOf("a.dmg"))
        assertEquals(Archives.Kind.TAR_ZST, Archives.kindOf("a.tar.zst"))
    }
}
