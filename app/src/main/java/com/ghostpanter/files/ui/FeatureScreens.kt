package com.ghostpanter.files.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.ops.Compare
import com.ghostpanter.files.core.ops.ContentSearch
import com.ghostpanter.files.core.ops.ConfigBackup
import com.ghostpanter.files.t
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

// ------------------------------------------------------------------ content search

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentSearchScreen(vm: MainViewModel, s: Screen.ContentSearch) {
    var query by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }
    var ignoreCase by remember { mutableStateOf(true) }
    var regex by remember { mutableStateOf(false) }
    var nameFilter by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val hits = remember { mutableStateListOf<ContentSearch.Hit>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var progress by remember { mutableStateOf("") }
    fun start() {
        job?.cancel(); hits.clear()
        if (query.isBlank()) return
        running = true
        job = scope.launch {
            withContext(Dispatchers.IO) {
                val ctx = coroutineContext
                ContentSearch.search(
                    vm.fsm.get(s.fsId), s.root,
                    ContentSearch.Options(query, ignoreCase, regex, recursive = recursive, nameFilter = nameFilter),
                    isCancelled = { !ctx.isActive },
                    onHit = { h -> scope.launch { hits.add(h) } },
                    onProgress = { p -> progress = p },
                )
            }
            running = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(t("内容搜索", "Content search")) },
            navigationIcon = { IconButton(onClick = { job?.cancel(); vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(t("关键词 / 正则", "Keyword / regex")) }, modifier = Modifier.fillMaxWidth().padding(8.dp))
            OutlinedTextField(nameFilter, { nameFilter = it }, singleLine = true, label = { Text(t("文件名过滤（可选 *?）", "Name filter")) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(recursive, { recursive = it }); Text(t("子目录", "Recursive"))
                Checkbox(ignoreCase, { ignoreCase = it }); Text(t("忽略大小写", "Ignore case"))
                Checkbox(regex, { regex = it }); Text(t("正则", "Regex"))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { start() }) { Icon(Icons.Filled.Search, null) }
            }
            if (running) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                Text(progress, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { job?.cancel(); running = false }) { Text(t("停止", "Stop")) }
            }
            Text(t("${hits.size} 处命中", "${hits.size} hits"), Modifier.padding(horizontal = 12.dp), fontSize = 12.sp)
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits.toList(), key = { "${it.entry.path}:${it.line}:${it.column}:${it.preview.hashCode()}" }) { h ->
                    ListItem(
                        headlineContent = { Text("${h.entry.name}:${h.line}") },
                        supportingContent = { Text(h.preview, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2) },
                        modifier = Modifier.clickable {
                            job?.cancel(); vm.screen = Screen.Editor(s.fsId, h.entry.path)
                        },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ compare

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: MainViewModel, s: Screen.Compare) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Compare.Row>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var textDiff by remember { mutableStateOf<Compare.TextDiff?>(null) }
    var hashFiles by remember { mutableStateOf(true) }
    LaunchedEffect(s, hashFiles) {
        rows = null; err = null; textDiff = null
        withContext(Dispatchers.IO) {
            runCatching {
                val lfs = vm.fsm.get(s.leftFs); val rfs = vm.fsm.get(s.rightFs)
                val le = lfs.stat(s.leftPath); val re = rfs.stat(s.rightPath)
                when {
                    le == null || re == null -> listOf(Compare.compareFiles(lfs, s.leftPath, rfs, s.rightPath))
                    le.isDirectory && re.isDirectory -> Compare.compareTrees(lfs, s.leftPath, rfs, s.rightPath, hashFiles, { false }) { progress = it }
                    !le.isDirectory && !re.isDirectory -> {
                        val row = Compare.compareFiles(lfs, s.leftPath, rfs, s.rightPath)
                        if (row.kind == Compare.Kind.DIFFERENT && VPath.extension(le.name) in TEXT_EXT && le.size < (2L shl 20) && re.size < (2L shl 20)) {
                            val lt = lfs.openRead(s.leftPath).use { it.readBytes().toString(Charsets.UTF_8) }
                            val rt = rfs.openRead(s.rightPath).use { it.readBytes().toString(Charsets.UTF_8) }
                            textDiff = Compare.textDiff(lt, rt)
                        }
                        listOf(row)
                    }
                    else -> listOf(Compare.Row("", Compare.Kind.TYPE_MISMATCH, le, re, detail = "一边是文件一边是目录"))
                }
            }.onSuccess { rows = it }.onFailure { err = it.message }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(t("对比", "Compare")) },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                TextButton(onClick = { hashFiles = !hashFiles }) { Text(if (hashFiles) t("哈希", "Hash") else t("大小", "Size")) }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("${vm.fsm.label(s.leftFs)} ${s.leftPath}\n↔ ${vm.fsm.label(s.rightFs)} ${s.rightPath}", Modifier.padding(8.dp), fontSize = 12.sp)
            when {
                err != null -> Text(err!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                rows == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(); Text(progress, fontSize = 11.sp)
                }
                else -> {
                    val summary = rows!!.groupingBy { it.kind }.eachCount()
                    Text(summary.entries.joinToString(" · ") { "${it.key}: ${it.value}" }, Modifier.padding(horizontal = 8.dp), fontSize = 12.sp)
                    LazyColumn(Modifier.weight(1f)) {
                        items(rows!!, key = { it.rel + it.kind }) { r ->
                            val color = when (r.kind) {
                                Compare.Kind.SAME -> MaterialTheme.colorScheme.outline
                                Compare.Kind.LEFT_ONLY -> MaterialTheme.colorScheme.primary
                                Compare.Kind.RIGHT_ONLY -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                            ListItem(
                                headlineContent = { Text(r.rel.ifEmpty { VPath.name(s.leftPath) }, color = color) },
                                supportingContent = { Text("${r.kind} ${r.detail.orEmpty()}", fontSize = 11.sp) },
                            )
                        }
                    }
                    textDiff?.let { td ->
                        HorizontalDivider()
                        Text(t("文本差异", "Text diff"), Modifier.padding(8.dp), style = MaterialTheme.typography.titleSmall)
                        LazyColumn(Modifier.heightIn(max = 280.dp).padding(horizontal = 8.dp)) {
                            items(td.unified) { line ->
                                val c = when {
                                    line.startsWith("+") -> Color(0xFF2E7D32)
                                    line.startsWith("-") -> Color(0xFFC62828)
                                    line.startsWith("@") -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ QR helper

@Composable
fun QrImage(text: String, size: Int = 512, modifier: Modifier = Modifier) {
    val bmp = remember(text, size) {
        runCatching {
            val bit = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size) {
                b.setPixel(x, y, if (bit[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            b
        }.getOrNull()
    }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = text, modifier = modifier.size((size / 3).dp))
}

// ------------------------------------------------------------------ thumbnails

object Thumbnails {
    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 120
    }
    /** Keys known to have no thumbnail (unsupported/too big/failed) so rows don't retry on every scroll. */
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MISS_TTL_MS = 90_000L

    /** Forget failed thumbnails (manual refresh, Root/Shizuku reconnect). */
    fun clearMisses() = misses.clear()
    /** Bound concurrent decodes/streams so a fast scroll over a shell/remote dir doesn't spawn dozens of readers. */
    private val gate = java.util.concurrent.Semaphore(3)

    private const val MAX_IMAGE_BYTES = 32L shl 20
    private const val MAX_VIDEO_BYTES_NEAR = 512L shl 20   // shell / SAF: copy of the file stays on-device
    private const val MAX_IMAGE_BYTES_REMOTE = 12L shl 20

    fun cached(fsId: String, entry: FileEntry, size: Int = 96): Bitmap? =
        synchronized(cache) { cache[key(fsId, entry, size)] }

    private fun key(fsId: String, e: FileEntry, size: Int) = "$fsId:${e.path}:${e.lastModified}:${e.size}:$size"

    fun load(fsId: String, entry: FileEntry, localFile: File?, apkIcon: (() -> Bitmap?)? = null, size: Int = 96): Bitmap? =
        load(fsId, entry, null, localFile, apkIcon, size)

    /**
     * Thumbnail for [entry] on any backend. LocalFs files decode in place; other backends (Shizuku/Root shell,
     * SAF, network) stream through [fs] into a temp file under the app cache, then decode. Results are kept in
     * memory and as small JPEGs in cache/thumbs keyed by fs+path+mtime+size.
     */
    fun load(fsId: String, entry: FileEntry, fs: com.ghostpanter.files.core.VirtualFs?, localFile: File?, apkIcon: (() -> Bitmap?)? = null, size: Int = 96): Bitmap? {
        if (entry.isDirectory) return null
        val key = key(fsId, entry, size)
        synchronized(cache) { cache[key] }?.let { return it }
        misses[key]?.let { t0 -> if (System.currentTimeMillis() - t0 < MISS_TTL_MS) return null else misses.remove(key) }
        val ext = entry.extension.lowercase()
        val isImg = ext in IMAGE_EXT
        val isVid = ext in VIDEO_EXT
        if (!isImg && !isVid && ext != "apk") return null
        val diskDir = File(com.ghostpanter.files.GhostApp.instance.cacheDir, "thumbs").apply { mkdirs() }
        val diskFile = File(diskDir, sha1(key) + ".jpg")
        if (diskFile.length() > 0) {
            BitmapFactory.decodeFile(diskFile.path)?.let { b -> synchronized(cache) { cache[key] = b }; return b }
        }
        gate.acquire()
        val bmp = try {
            when {
                localFile != null && localFile.isFile && localFile.canRead() -> when {
                    isImg -> decodeImage(localFile, size)
                    isVid -> decodeVideo(localFile, size)
                    else -> apkIcon?.invoke()
                }
                ext == "apk" -> apkIcon?.invoke()
                fs != null -> viaTemp(fsId, fs, entry, isImg, size)
                else -> null
            }
        } catch (t: Throwable) { android.util.Log.w("GFThumb", "thumb failed $fsId ${entry.path}", t); null } finally { gate.release() }
        if (bmp == null) { misses[key] = System.currentTimeMillis(); return null }
        synchronized(cache) { cache[key] = bmp }
        if (fsId != "local") runCatching {
            diskFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return bmp
    }

    private fun viaTemp(fsId: String, fs: com.ghostpanter.files.core.VirtualFs, e: FileEntry, isImg: Boolean, size: Int): Bitmap? {
        val remote = fsId.startsWith("net:")
        val limit = when {
            isImg && remote -> MAX_IMAGE_BYTES_REMOTE
            isImg -> MAX_IMAGE_BYTES
            remote -> return null // a whole remote video per row is too expensive
            else -> MAX_VIDEO_BYTES_NEAR
        }
        if (e.size > limit) return null
        val tmpDir = File(com.ghostpanter.files.GhostApp.instance.cacheDir, "thumb-tmp").apply { mkdirs() }
        val tmp = File.createTempFile("thumb", "." + e.extension.lowercase().ifEmpty { "bin" }, tmpDir)
        return try {
            fs.openRead(e.path).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        if (total > limit) return null
                        out.write(buf, 0, n)
                    }
                }
            }
            if (isImg) decodeImage(tmp, size) else decodeVideo(tmp, size)
        } finally { tmp.delete() }
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Trim the disk thumb cache (called at startup). */
    fun trimDisk(maxBytes: Long = 64L shl 20) {
        try {
            File(com.ghostpanter.files.GhostApp.instance.cacheDir, "thumb-tmp").deleteRecursively()
            val files: List<File> = File(com.ghostpanter.files.GhostApp.instance.cacheDir, "thumbs").listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = 0L
            for (f in files) total += f.length()
            for (f in files) { if (total <= maxBytes) break; total -= f.length(); f.delete() }
        } catch (_: Throwable) {}
    }

    private fun decodeImage(f: File, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (o.outWidth / sample > size * 2 || o.outHeight / sample > size * 2) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return centerCrop(b, size)
    }

    private fun decodeVideo(f: File, size: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            java.io.FileInputStream(f).use { r.setDataSource(it.fd) }
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size * 2, size * 2) else r.frameAtTime
            frame?.let { centerCrop(it, size) }
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    /** Square center crop (the old code stretched non-square frames). */
    private fun centerCrop(b: Bitmap, size: Int): Bitmap {
        val side = minOf(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side <= size) sq else Bitmap.createScaledBitmap(sq, size, size, true)
    }
}

fun highlightCode(text: String, ext: String): androidx.compose.ui.text.AnnotatedString {
    val keywords = when (ext) {
        "kt", "kts", "java" -> setOf("fun", "val", "var", "class", "object", "if", "else", "when", "return", "import", "package", "public", "private", "override", "suspend", "data")
        "py" -> setOf("def", "class", "import", "from", "return", "if", "elif", "else", "for", "while", "with", "as", "None", "True", "False")
        "js", "ts" -> setOf("function", "const", "let", "var", "return", "if", "else", "class", "import", "export", "async", "await")
        "json" -> emptySet()
        "xml", "html", "htm" -> emptySet()
        "md" -> emptySet()
        else -> setOf("if", "else", "for", "while", "return")
    }
    return buildAnnotatedString {
        if (keywords.isEmpty() && ext !in setOf("json", "xml", "html", "htm", "md")) {
            append(text); return@buildAnnotatedString
        }
        val kwColor = SpanStyle(color = Color(0xFFCC7832))
        val strColor = SpanStyle(color = Color(0xFF6A8759))
        val cmtColor = SpanStyle(color = Color(0xFF808080))
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) || text.startsWith("#", i) && ext == "py" -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    withStyle(cmtColor) { append(text.substring(i, end)) }; i = end
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val q = text[i]; var j = i + 1
                    while (j < text.length && text[j] != q) { if (text[j] == '\\') j++; j++ }
                    if (j < text.length) j++
                    withStyle(strColor) { append(text.substring(i, j)) }; i = j
                }
                text[i].isLetter() || text[i] == '_' -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val w = text.substring(i, j)
                    if (w in keywords) withStyle(kwColor) { append(w) } else append(w)
                    i = j
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}
