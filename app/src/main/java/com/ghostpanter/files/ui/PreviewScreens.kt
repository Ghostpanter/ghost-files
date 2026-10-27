package com.ghostpanter.files.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ghostpanter.files.core.ops.Markdown
import com.ghostpanter.files.core.ops.MusicTags
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import android.graphics.Typeface as AndroidTypeface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: MainViewModel, s: Screen.Preview) {
    var local by remember { mutableStateOf<File?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.path) {
        err = null
        local = null
        runCatching {
            withContext(Dispatchers.IO) {
                val e = vm.fsm.get(s.fsId).stat(s.path) ?: error("missing")
                vm.materializeForPreview(s.fsId, e)
            }
        }.onSuccess { local = it }.onFailure { err = it.message }
    }
    Scaffold(topBar = {
        TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
            title = { Text(com.ghostpanter.files.core.VPath.name(s.path), maxLines = 1) },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                err != null -> Text(err!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                local == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                s.kind == "pdf" -> PdfPreview(local!!, onOpenExternal = { vm.openWithOtherApp(s.fsId, s.path) })
                s.kind == "docx" || s.kind == "xlsx" -> com.ghostpanter.files.ui.office.OfficeWebPreview(
                    local!!, s.kind, onOpenExternal = { vm.openWithOtherApp(s.fsId, s.path) },
                )
                s.kind == "md" -> MarkdownView(local!!)
                s.kind == "epub" -> EpubView(local!!)
                s.kind == "font" -> FontPreview(local!!)
                else -> Text(t("不支持的预览类型", "Unsupported preview"), Modifier.padding(16.dp))
            }
        }
    }
}


@Composable
private fun PdfPreview(file: File, onOpenExternal: () -> Unit) {
    var useSystem by remember { mutableStateOf(false) }
    if (useSystem) SystemPdfPages(file)
    else com.ghostpanter.files.ui.pdf.PdfiumViewer(file, onFallback = { useSystem = true })
}

/** System PdfRenderer fallback when Pdfium fails to load/open. */
@Composable
private fun SystemPdfPages(file: File) {
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file, page) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                        val p = page.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                        renderer.openPage(p).use { pg ->
                            val w = (pg.width * 2).coerceAtMost(2048)
                            val h = (pg.height * 2 * w / pg.width.coerceAtLeast(1)).coerceAtMost(4096)
                            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            b.eraseColor(android.graphics.Color.WHITE)
                            pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp = b
                        }
                    }
                }
            }.onFailure { error = it.message }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(enabled = page > 0, onClick = { page-- }) { Icon(Icons.Filled.NavigateBefore, null) }
            Text("${page + 1} / ${pageCount.coerceAtLeast(1)}")
            IconButton(enabled = page + 1 < pageCount, onClick = { page++ }) { Icon(Icons.Filled.NavigateNext, null) }
        }
        when {
            error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            bmp != null -> Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
            else -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun MarkdownView(file: File) {
    var html by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        html = withContext(Dispatchers.IO) {
            val text = file.readText(Charsets.UTF_8)
            Markdown.toHtml(text, file.name)
        }
    }
    val h = html
    if (h == null) Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
    else AndroidView(
        factory = { ctx -> WebView(ctx).apply {
            settings.javaScriptEnabled = false
            webViewClient = WebViewClient()
            setBackgroundColor(0xFF111111.toInt())
        } },
        update = { it.loadDataWithBaseURL(null, h, "text/html", "utf-8", null) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun EpubView(file: File) {
    var html by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(file).use { z ->
                    // Find first HTML/XHTML in spine-ish order
                    val candidates = z.entries().asSequence()
                        .filter { !it.isDirectory }
                        .map { it.name }
                        .filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }
                        .sorted()
                        .toList()
                    val entry = candidates.firstOrNull { it.contains("chapter", true) || it.contains("text", true) }
                        ?: candidates.firstOrNull()
                        ?: error(t("EPUB 内无 HTML", "No HTML in EPUB"))
                    val body = z.getInputStream(z.getEntry(entry)).bufferedReader().readText()
                    html = """<!DOCTYPE html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>body{font-family:serif;padding:16px;line-height:1.6;background:#111;color:#eee}a{color:#7AD9FB}</style></head>
<body><p style="color:#888;font-size:12px">${file.name} · $entry</p>$body</body></html>"""
                }
            }.onFailure { err = it.message }
        }
    }
    when {
        err != null -> Text(t("EPUB 预览失败：", "EPUB preview failed: ") + err, Modifier.padding(16.dp))
        html == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        else -> {
            val h = html!!
            AndroidView(
                factory = { ctx -> WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    webViewClient = WebViewClient()
                    setBackgroundColor(0xFF111111.toInt())
                } },
                update = { it.loadDataWithBaseURL(null, h, "text/html", "utf-8", null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FontPreview(file: File) {
    var err by remember { mutableStateOf<String?>(null) }
    val tf = remember(file) {
        runCatching { AndroidTypeface.createFromFile(file) }.onFailure { err = it.message }.getOrNull()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(file.name, fontWeight = FontWeight.Bold)
        err?.let { Text(it, color = MaterialTheme.colorScheme.error); return@Column }
        if (tf == null) return@Column
        AndroidView(
            factory = { ctx ->
                android.widget.TextView(ctx).apply {
                    typeface = tf
                    textSize = 22f
                    setTextColor(0xFFEEEEEE.toInt())
                    setLineSpacing(0f, 1.3f)
                    text = "双栏文件管理器 Dual-Pane File Manager\nABCDEFG abcdefg 0123456789\n天地玄黄 宇宙洪荒\nThe quick brown fox jumps over the lazy dog."
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTagScreen(vm: MainViewModel, s: Screen.MusicTag) {
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf(MusicTags.Tags()) }
    var ready by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(s.path) {
        withContext(Dispatchers.IO) {
            val e = vm.fsm.get(s.fsId).stat(s.path) ?: return@withContext
            val f = vm.materializeForPreview(s.fsId, e)
            local = f
            tags = MusicTags.read(f)
            ready = true
        }
    }
    Scaffold(topBar = {
        TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
            title = { Text(t("音乐标签", "Music tags")) },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                TextButton(enabled = ready && local != null, onClick = {
                    scope.launch {
                        val f = local ?: return@launch
                        withContext(Dispatchers.IO) {
                            MusicTags.write(f, tags)
                            // write back if remote
                            if (s.fsId != com.ghostpanter.files.core.LocalFs.ID) {
                                vm.fsm.get(s.fsId).openWrite(s.path).use { out -> f.inputStream().use { it.copyTo(out) } }
                            }
                        }
                        vm.toast(t("已保存（ID3v1）", "Saved (ID3v1)"))
                        vm.screen = Screen.Main
                    }
                }) { Text(t("保存", "Save")) }
            },
        )
    }) { pad ->
        if (!ready) Box(Modifier.padding(pad).fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        else Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(t("仅 MP3 ID3；FLAC/M4A 后续支持。保存会写入 ID3v1。", "MP3 ID3 only; FLAC/M4A later. Save writes ID3v1."), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            GhostTextField(tags.title, { tags = tags.copy(title = it) }, label = { Text(t("标题", "Title")) }, modifier = Modifier.fillMaxWidth())
            GhostTextField(tags.artist, { tags = tags.copy(artist = it) }, label = { Text(t("艺术家", "Artist")) }, modifier = Modifier.fillMaxWidth())
            GhostTextField(tags.album, { tags = tags.copy(album = it) }, label = { Text(t("专辑", "Album")) }, modifier = Modifier.fillMaxWidth())
            GhostTextField(tags.year, { tags = tags.copy(year = it) }, label = { Text(t("年份", "Year")) }, modifier = Modifier.fillMaxWidth())
            GhostTextField(tags.comment, { tags = tags.copy(comment = it) }, label = { Text(t("备注", "Comment")) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
