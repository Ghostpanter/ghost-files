package com.ghostpanter.files.core

import com.ghostpanter.files.core.ops.AesCrypt
import com.ghostpanter.files.core.ops.ApksOps
import com.ghostpanter.files.core.ops.Markdown
import com.ghostpanter.files.core.ops.MusicTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpsV060Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun gfencRoundTrip() {
        val src = tmp.newFile("hello.txt").also { it.writeText("secret payload 你好") }
        val enc = File(tmp.root, "hello.txt.gfenc")
        val dec = File(tmp.root, "hello.out")
        AesCrypt.encrypt(src, enc, "passw0rd!")
        assertTrue(AesCrypt.isContainer(enc))
        AesCrypt.decrypt(enc, dec, "passw0rd!")
        assertEquals(src.readText(), dec.readText())
    }

    @Test(expected = Exception::class)
    fun gfencBadPassword() {
        val src = tmp.newFile("a.bin").also { it.writeBytes(ByteArray(100) { it.toByte() }) }
        val enc = File(tmp.root, "a.gfenc")
        AesCrypt.encrypt(src, enc, "right")
        AesCrypt.decrypt(enc, File(tmp.root, "out"), "wrong")
    }

    @Test fun markdownBasic() {
        val html = Markdown.toHtml("# Title\n\nHello **bold** and `code`\n\n- a\n- b")
        assertTrue(html.contains("<h1>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<li>"))
    }

    @Test fun musicTagsId3v1() {
        // Minimal MP3-ish bytes + no existing tag
        val f = tmp.newFile("song.mp3").also { it.writeBytes(ByteArray(200) { 0 }) }
        MusicTags.write(f, MusicTags.Tags(title = "T1", artist = "A1", album = "Al", year = "2026", comment = "c"))
        val r = MusicTags.read(f)
        assertEquals("T1", r.title)
        assertEquals("A1", r.artist)
        assertEquals("Al", r.album)
        assertEquals("2026", r.year)
    }

    @Test fun apksExtract() {
        val apks = tmp.newFile("bundle.apks")
        ZipOutputStream(apks.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("base.apk")); z.write(ByteArray(16) { 1 }); z.closeEntry()
            z.putNextEntry(ZipEntry("split_config.apk")); z.write(ByteArray(8) { 2 }); z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json")); z.write("""{"name":"Demo"}""".toByteArray()); z.closeEntry()
        }
        val dir = tmp.newFolder("out")
        val r = ApksOps.extract(apks, dir)
        assertEquals(2, r.apks.size)
        assertEquals("Demo", r.label)
        assertTrue(ApksOps.isBundle("x.xapk"))
    }
}
