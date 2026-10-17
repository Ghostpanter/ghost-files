package com.ghostpanter.files.core

import com.ghostpanter.files.core.server.FsStreamProxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class FsStreamProxyTest {
    @Test fun servesFullAndRanges() {
        val d = kotlin.io.path.createTempDirectory().toFile()
        val data = ByteArray(300_000) { (it * 31).toByte() }
        File(d, "v.mp4").writeBytes(data)
        val sh = ShellRunner { s -> ProcessBuilder("sh", "-c", s).start() }
        val fs = ShellFs("shell", "sh", sh)
        FsStreamProxy().use { p ->
            val url = p.register(fs, File(d, "v.mp4").path, data.size.toLong(), "video/mp4")
            val c = URL(url).openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertArrayEquals(data, c.inputStream.readBytes())
            val r = URL(url).openConnection() as HttpURLConnection
            r.setRequestProperty("Range", "bytes=1000-1999")
            assertEquals(206, r.responseCode)
            assertEquals("bytes 1000-1999/300000", r.getHeaderField("Content-Range"))
            assertArrayEquals(data.copyOfRange(1000, 2000), r.inputStream.readBytes())
            val t = URL(url).openConnection() as HttpURLConnection
            t.setRequestProperty("Range", "bytes=299990-")
            assertArrayEquals(data.copyOfRange(299990, 300000), t.inputStream.readBytes())
        }
    }
}
