package com.ghostpanter.files.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalAndShellFsTest {
    @Test fun localContract() = FsContract.run(LocalFs(), tempDir().path)

    private val sh = ShellRunner { script -> ProcessBuilder("sh", "-c", script).start() }

    @Test fun shellContract() = FsContract.run(ShellFs("shell", "sh", sh), tempDir().path)

    @Test fun shellListsHiddenFilesLinksAndPerms() {
        val d = tempDir()
        File(d, ".hidden").writeText("x")
        File(d, "tab\tname.txt").writeText("yy")
        File(d, "real").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(d, "link").toPath(), File(d, "real").toPath())
        val fs = ShellFs("shell", "sh", sh)
        val list = fs.list(d.path).associateBy { it.name }
        assertTrue(list[".hidden"]!!.isHidden)
        assertEquals(1L, list[".hidden"]!!.size)
        assertTrue(list["link"]!!.isLink)
        assertTrue(list["link"]!!.isDirectory)
        assertTrue(list["real"]!!.permissions!!.startsWith("d"))
        fs.chmod(VPath.join(d.path, ".hidden"), "600")
        assertEquals("-rw-------", fs.stat(VPath.join(d.path, ".hidden"))!!.permissions)
    }

    @Test fun parseToyboxStyleOutput() {
        val out = "regular file\t-rw-rw----\t12\t1700000000\troot\tsdcard_rw\t./a b.txt\n" +
            "directory\tdrwxrwx--x\t3452\t1700000001\tsystem\tsystem\t./data\n" +
            "symbolic link\tlrwxrwxrwx\t9\t1700000002\troot\troot\t./sdcard\n" +
            "${ShellFs.SEP}\n./data\n./sdcard\n"
        val l = ShellFs.parseListing("/", out).associateBy { it.name }
        assertEquals(3, l.size)
        assertEquals("/a b.txt", l["a b.txt"]!!.path)
        assertEquals(1_700_000_000_000, l["a b.txt"]!!.lastModified)
        assertTrue(l["sdcard"]!!.isDirectory && l["sdcard"]!!.isLink)
        assertEquals("system", l["data"]!!.owner)
    }
}
