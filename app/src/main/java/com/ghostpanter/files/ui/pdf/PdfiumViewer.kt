package com.ghostpanter.files.ui.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.preview.PdfSearch
import com.ghostpanter.files.core.preview.PdfSearchHit
import com.ghostpanter.files.t
import com.ghostpanter.files.ui.GhostTextField
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pdfium-backed viewer with zoom, TOC, search+highlight, long-press copy, password.
 * On any Pdfium failure, [onFallback] is invoked so the caller can use system PdfRenderer.
 */
@Composable
fun PdfiumViewer(file: File, onFallback: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf<String?>(null) }
    var needPassword by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var docHolder by remember { mutableStateOf<DocSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1.5f) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var highlights by remember { mutableStateOf<List<RectF>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PdfSearchHit>>(emptyList()) }
    var hitIndex by remember { mutableIntStateOf(0) }
    var showToc by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    val clipboard = LocalClipboardManager.current

    fun closeSession() {
        docHolder?.close()
        docHolder = null
    }

    DisposableEffect(file, password) {
        var session: DocSession? = null
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val core = PdfiumCore(ctx)
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val doc = try {
                        if (password == null) core.newDocument(pfd)
                        else core.newDocument(pfd, password)
                    } catch (e: PdfPasswordException) {
                        needPassword = true
                        pfd.close()
                        return@runCatching null
                    }
                    DocSession(core, doc, pfd)
                }.onSuccess {
                    if (it == null) return@onSuccess
                    session = it
                    docHolder = it
                    needPassword = false
                    toc = it.tableOfContents()
                    error = null
                }.onFailure {
                    error = it.message
                    withContext(Dispatchers.Main) { onFallback() }
                }
            }
        }
        onDispose { session?.close(); if (docHolder === session) docHolder = null }
    }

    LaunchedEffect(docHolder, page, scale, hits, hitIndex) {
        val session = docHolder ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val count = session.pageCount
                val p = page.coerceIn(0, (count - 1).coerceAtLeast(0))
                val rendered = session.renderPage(p, scale)
                val hl = if (hits.isNotEmpty() && hits.getOrNull(hitIndex)?.page == p) {
                    session.highlightRects(p, hits[hitIndex].charIndex, hits[hitIndex].charLength, rendered.width, rendered.height)
                } else emptyList()
                Triple(rendered, count, hl)
            }.onSuccess { (b, count, hl) ->
                bmp = b
                highlights = hl
                if (page >= count && count > 0) page = count - 1
            }.onFailure {
                error = it.message
                withContext(Dispatchers.Main) { onFallback() }
            }
        }
    }

    if (needPassword) {
        AlertDialog(
            onDismissRequest = { onFallback() },
            title = { Text(t("加密 PDF", "Encrypted PDF")) },
            text = {
                OutlinedTextField(
                    passwordInput,
                    { passwordInput = it },
                    label = { Text(t("密码", "Password")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    password = passwordInput
                    needPassword = false
                }) { Text(t("打开", "Open")) }
            },
            dismissButton = { TextButton(onClick = onFallback) { Text(t("取消", "Cancel")) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(enabled = page > 0, onClick = { page-- }) { Icon(Icons.Filled.NavigateBefore, null) }
            Text("${page + 1} / ${(docHolder?.pageCount ?: 1).coerceAtLeast(1)}", fontSize = 13.sp)
            IconButton(
                enabled = docHolder != null && page + 1 < (docHolder?.pageCount ?: 0),
                onClick = { page++ },
            ) { Icon(Icons.Filled.NavigateNext, null) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scale = (scale / 1.25f).coerceAtLeast(0.5f) }) { Text("−", fontSize = 18.sp) }
            IconButton(onClick = { scale = (scale * 1.25f).coerceAtMost(4f) }) { Text("+", fontSize = 18.sp) }
            IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Filled.Search, null) }
            IconButton(onClick = { showToc = true }) { Icon(Icons.Filled.List, t("目录", "TOC")) }
            IconButton(onClick = {
                scope.launch {
                    val text = withContext(Dispatchers.IO) { docHolder?.pageText(page).orEmpty() }
                    if (text.isNotBlank()) clipboard.setText(AnnotatedString(text))
                }
            }) { Icon(Icons.Filled.ContentCopy, t("复制本页", "Copy page")) }
        }
        if (showSearch) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(t("搜索全文", "Search")) },
                )
                TextButton(onClick = {
                    scope.launch {
                        val session = docHolder ?: return@launch
                        val found = withContext(Dispatchers.IO) { session.search(query) }
                        hits = found
                        hitIndex = 0
                        found.firstOrNull()?.let { page = it.page }
                    }
                }) { Text(t("查找", "Find")) }
                if (hits.isNotEmpty()) {
                    Text("${hitIndex + 1}/${hits.size}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = {
                        hitIndex = (hitIndex - 1 + hits.size) % hits.size
                        page = hits[hitIndex].page
                    }) { Icon(Icons.Filled.NavigateBefore, null) }
                    IconButton(onClick = {
                        hitIndex = (hitIndex + 1) % hits.size
                        page = hits[hitIndex].page
                    }) { Icon(Icons.Filled.NavigateNext, null) }
                }
                IconButton(onClick = { showSearch = false; hits = emptyList() }) { Icon(Icons.Filled.Close, null) }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFE8E8E8))
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                    }
                }
                .pointerInput(docHolder, page) {
                    detectTapGestures(onLongPress = {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { docHolder?.pageText(page).orEmpty() }
                            if (text.isNotBlank()) clipboard.setText(AnnotatedString(text))
                        }
                    })
                },
        ) {
            when {
                error != null && docHolder == null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                bmp != null -> {
                    val b = bmp!!
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Box {
                            Image(b.asImageBitmap(), null)
                            if (highlights.isNotEmpty()) {
                                Canvas(Modifier.matchParentSize()) {
                                    val paint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.argb(80, 255, 230, 0)
                                        style = android.graphics.Paint.Style.FILL
                                    }
                                    highlights.forEach { r ->
                                        drawContext.canvas.nativeCanvas.drawRect(r, paint)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text(t("目录 / 书签", "Contents")) },
            text = {
                if (toc.isEmpty()) Text(t("无目录", "No table of contents"))
                else LazyColumn(Modifier.height(320.dp)) {
                    items(toc) { (title, pageIndex) ->
                        TextButton(onClick = {
                            page = pageIndex.coerceAtLeast(0)
                            showToc = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showToc = false }) { Text(t("关闭", "Close")) } },
        )
    }
}

private class DocSession(
    private val core: PdfiumCore,
    private val doc: PdfDocument,
    private val pfd: ParcelFileDescriptor,
) {
    val pageCount: Int get() = runCatching { doc.getPageCount() }.getOrDefault(0)

    fun close() {
        runCatching { doc.close() }
        runCatching { pfd.close() }
    }

    fun tableOfContents(): List<Pair<String, Int>> = buildList {
        fun walk(nodes: List<PdfDocument.Bookmark>, depth: Int) {
            for (n in nodes) {
                val title = n.title?.takeIf { it.isNotBlank() } ?: continue
                val pageIdx = n.pageIdx.toInt().coerceAtLeast(0)
                add(" ".repeat(depth) + title to pageIdx)
                walk(n.children, depth + 1)
            }
        }
        runCatching { walk(doc.getTableOfContents(), 0) }
    }

    fun renderPage(index: Int, scale: Float): Bitmap {
        doc.openPage(index).use { page ->
            val w = (page.getPageWidthPoint() * scale).toInt().coerceIn(64, 4096)
            val h = (page.getPageHeightPoint() * scale).toInt().coerceIn(64, 8192)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.renderPageBitmap(bmp, 0, 0, w, h, renderAnnot = true, textMask = false)
            return bmp
        }
    }

    fun pageText(index: Int): String = runCatching {
        doc.openPage(index).use { page ->
            page.openTextPage().use { tp ->
                val n = tp.textPageCountChars()
                if (n <= 0) "" else tp.textPageGetText(0, n) ?: ""
            }
        }
    }.getOrDefault("")

    fun search(query: String): List<PdfSearchHit> {
        if (query.isBlank()) return emptyList()
        val pages = (0 until pageCount).map { pageText(it) }
        return PdfSearch.findInPages(pages, query)
    }

    fun highlightRects(pageIndex: Int, charIndex: Int, len: Int, viewW: Int, viewH: Int): List<RectF> =
        runCatching {
            doc.openPage(pageIndex).use { page ->
                page.openTextPage().use { tp ->
                    val count = tp.textPageCountRects(charIndex, len).coerceAtLeast(0)
                    val pageW = page.getPageWidthPoint().toFloat().coerceAtLeast(1f)
                    val pageH = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
                    val sx = viewW / pageW
                    val sy = viewH / pageH
                    (0 until count).mapNotNull { i ->
                        val r = tp.textPageGetRect(i) ?: return@mapNotNull null
                        // Pdfium page coords: origin bottom-left → bitmap top-left
                        RectF(
                            r.left * sx,
                            (pageH - r.top) * sy,
                            r.right * sx,
                            (pageH - r.bottom) * sy,
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
}
