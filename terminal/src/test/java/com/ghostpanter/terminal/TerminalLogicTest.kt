package com.ghostpanter.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TerminalLogicTest {
    private val rs = "\u001e"

    @Test fun promptRecordsSplitAcrossChunks() {
        val p = TermParser()
        val all = ArrayList<TermParser.Event>()
        val wire = "hello\n${rs}GFPID42${rs}out${rs}GFP1;/sdcard/Download${rs}tail"
        for (c in wire.chunked(3)) all += p.feed(c)
        val text = all.filterIsInstance<TermParser.Event.Text>().joinToString("") { it.text }
        assertEquals("hello\nouttail", text)
        assertEquals(TermParser.Event.Pid(42), all.filterIsInstance<TermParser.Event.Pid>().single())
        assertEquals(TermParser.Event.Prompt(1, "/sdcard/Download"), all.filterIsInstance<TermParser.Event.Prompt>().single())
        // a stray RS that is not a record passes through
        assertEquals(listOf(TermParser.Event.Text("a${rs}xyz b")), TermParser().feed("a${rs}xyz b"))
    }

    @Test fun ansiColoursCrAndClear() {
        val d = AnsiDecoder()
        val ops = d.decode("\u001b[1;31mred\u001b[0m plain\r\n50%\r100%\u001b]0;title\u0007\u001b[2J")
        val writes = ops.filterIsInstance<AnsiOp.Write>().map { it.run }
        assertEquals(Styled("red", 1, true), writes[0])
        assertEquals(Styled(" plain\n50%", -1, false), writes[1])
        assertTrue(ops.contains(AnsiOp.CarriageReturn))
        assertEquals("100%", writes[2].text)
        assertEquals(AnsiOp.ClearScreen, ops.last())
        // escape split across chunks
        val d2 = AnsiDecoder()
        assertEquals(emptyList<AnsiOp>(), d2.decode("\u001b[3"))
        assertEquals(Styled("g", 2, false), (d2.decode("2mg").single() as AnsiOp.Write).run)
    }

    @Test fun favoritesRoundTripAndImportFormats() {
        val list = listOf(Favorite("a", "ls -l"), Favorite("b", "getprop | grep model"))
        assertEquals(list, FavoritesCodec.decode(FavoritesCodec.encode(list)))
        assertEquals(listOf(Favorite("x", "id")), FavoritesCodec.decode("""[{"name":"x","cmd":"id"}]"""))
        assertEquals(listOf("df -h", "uptime"), FavoritesCodec.decode("# comment\ndf -h\n\nuptime").map { it.command })
        assertEquals(3, FavoritesCodec.merge(list, listOf(Favorite("dup", "ls -l"), Favorite("c", "id"))).size)
    }

    @Test fun nonPtyShellReportsPromptsAndCwd() {
        var proc: Process? = null
        val backend = StreamShellBackend("local", "u", "h", pty = false, open = {
            val p = ProcessBuilder("sh").redirectErrorStream(true).start(); proc = p
            Triple(p.inputStream, p.outputStream, Closeable { p.destroy() })
        }, execOnce = { cmd -> ProcessBuilder("sh", "-c", cmd).start().inputStream.bufferedReader().readText() })
        val q = LinkedBlockingQueue<String>()
        backend.start(object : TermSink {
            override fun onOutput(text: String) { q.put(text) }
            override fun onExit(reason: String?) { q.put("<exit>") }
        }, 80, 24)
        val parser = TermParser()
        fun until(pred: (TermParser.Event) -> Boolean): List<TermParser.Event> {
            val got = ArrayList<TermParser.Event>()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val s = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val evs = parser.feed(s); got += evs
                if (evs.any(pred)) return got
            }
            throw AssertionError("timeout, got $got")
        }
        until { it is TermParser.Event.Prompt }
        backend.sendLine("cd /tmp && echo hi; false")
        val evs = until { it is TermParser.Event.Prompt }
        assertEquals("hi\n", evs.filterIsInstance<TermParser.Event.Text>().joinToString("") { it.text })
        val pr = evs.filterIsInstance<TermParser.Event.Prompt>().single()
        assertEquals(1, pr.rc); assertEquals("/tmp", pr.cwd)
        // completion runs a separate one-shot command in the cwd
        val c = backend.complete("/", "/tm", command = false)
        assertTrue(c.toString(), c.contains("/tmp/"))
        backend.eof()
        assertTrue(proc!!.waitFor(5, TimeUnit.SECONDS))
        backend.close()
    }
}
