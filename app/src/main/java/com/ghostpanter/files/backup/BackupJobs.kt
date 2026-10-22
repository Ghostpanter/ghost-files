package com.ghostpanter.files.backup

import android.content.Context
import android.os.Build
import android.provider.Telephony
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import com.ghostpanter.files.core.backup.BackupKind
import com.ghostpanter.files.core.backup.BackupManifest
import com.ghostpanter.files.core.backup.SmsXml
import com.ghostpanter.files.core.backup.WifiStore
import com.ghostpanter.files.fs.FsManager
import com.ghostpanter.files.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupProgress(
    val restore: Boolean,
    val kind: BackupKind?,
    /** 1-based item being processed and how many items in total. */
    val step: Int, val steps: Int,
    val done: Int, val total: Int,
    val target: String,
)

data class BackupLine(val kind: BackupKind, val text: String, val ok: Boolean = true)

data class BackupOutcome(val restore: Boolean, val lines: List<BackupLine>, val location: String?, val cancelled: Boolean = false, val error: String? = null, val fsId: String? = null, val path: String? = null)

/** Where a restore reads from: a backup folder, a ghost-backup zip, or one loose file. */
data class RestoreSource(val fsId: String, val path: String)

data class RestoreEntry(val kind: BackupKind, val count: Int?, val name: String)

data class RestoreInventory(val source: RestoreSource, val label: String, val type: Type, val entries: List<RestoreEntry>, val created: Long = 0, val device: String = "") {
    enum class Type { FOLDER, ZIP, FILE }
}

/** Backup / restore jobs (survive leaving the page). */
object BackupJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var progress by mutableStateOf<BackupProgress?>(null); private set
    var outcome by mutableStateOf<BackupOutcome?>(null)
    @Volatile private var cancel = false
    val running get() = progress != null

    fun cancel() { cancel = true }

    fun defaultDir(fsm: FsManager) = fsm.storageRoot + "/双栏文件管理器/备份"

    fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date(ms))

    /** Keeps the underlying stream open when a writer on a zip entry is closed. */
    private class NoClose(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        override fun close() { flush() }
    }

    private class NoCloseIn(i: InputStream) : java.io.FilterInputStream(i) { override fun close() {} }

    fun backup(ctx: Context, fsm: FsManager, kinds: List<BackupKind>, fsId: String, dir: String, zip: Boolean) {
        if (running || kinds.isEmpty()) return
        cancel = false; outcome = null
        val now = System.currentTimeMillis()
        val st = stamp(now); val fstamp = SmsXml.stamp(now)
        val fs = fsm.get(fsId)
        val target = if (zip) VPath.join(dir, "ghost-backup-$st.zip") else VPath.join(dir, st)
        val label = fsm.label(fsId) + "  " + target
        progress = BackupProgress(false, kinds.first(), 1, kinds.size, 0, 0, label)
        scope.launch {
            val p = BackupProviders(ctx)
            val lines = ArrayList<BackupLine>(); val counts = LinkedHashMap<BackupKind, Int>(); val files = LinkedHashMap<BackupKind, String>()
            var cancelled = false; var error: String? = null
            fun manifest(c: Map<BackupKind, Int>) = BackupManifest(now, "${Build.MANUFACTURER} ${Build.MODEL}", com.ghostpanter.files.BuildConfig.VERSION_NAME, c, files, Build.VERSION.SDK_INT)
            try {
                kinds.forEach { files[it] = BackupManifest.fileName(it, fstamp) }
                if (zip) {
                    fs.mkdirs(dir)
                    // Counts up front so the manifest can be the first entry (restore reads it without scanning the zip).
                    val pre = kinds.associateWith { k -> p.count(k) ?: 0 }
                    ZipOutputStream(fs.openWrite(target).buffered(1 shl 16)).use { z ->
                        z.putNextEntry(ZipEntry(BackupManifest.NAME)); z.write(manifest(pre).toJson().toByteArray()); z.closeEntry()
                        kinds.forEachIndexed { i, k ->
                            z.putNextEntry(ZipEntry(files[k]!!))
                            safe(k, lines) { counts[k] = runOne(p, k, NoClose(z), i, kinds.size, label) }
                            z.closeEntry()
                        }
                    }
                } else {
                    fs.mkdirs(target)
                    kinds.forEachIndexed { i, k ->
                        val path = VPath.join(target, files[k]!!)
                        if (k == BackupKind.WIFI) fs.mkdirs(VPath.parent(path))
                        safe(k, lines) { fs.openWrite(path).buffered(1 shl 16).use { counts[k] = runOne(p, k, it, i, kinds.size, label) } }
                        if (k !in counts) runCatching { fs.delete(path, false) }
                    }
                    fs.openWrite(VPath.join(target, BackupManifest.NAME)).use { it.write(manifest(counts).toJson().toByteArray()) }
                }
            } catch (e: CancellationException) { cancelled = true } catch (e: Throwable) { error = e.message ?: e.toString() }
            for ((k, n) in counts) lines.add(BackupLine(k, unit(k, n)))
            lines.sortBy { it.kind.ordinal }
            if (cancelled || error != null) runCatching { if (zip) fs.delete(target, false) else fs.delete(target, true) }
            withContext(Dispatchers.Main) {
                progress = null
                outcome = BackupOutcome(false, lines, if (cancelled || error != null) null else label, cancelled, error, fsId, target)
            }
        }
    }

    /** One item failing (permission revoked, provider error) does not stop the others. */
    private inline fun safe(k: BackupKind, lines: MutableList<BackupLine>, block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            lines.add(BackupLine(k, t("失败：", "Failed: ") + (if (e is SecurityException) t("没有权限", "permission denied") else e.message ?: e.toString()), false))
        }
    }

    fun unit(k: BackupKind, n: Int) = when (k) {
        BackupKind.SMS -> t("$n 条短信/彩信", "$n messages")
        BackupKind.CALLS -> t("$n 条通话记录", "$n calls")
        BackupKind.CONTACTS -> t("$n 位联系人", "$n contacts")
        BackupKind.CALENDAR -> t("$n 个日程", "$n events")
        BackupKind.WIFI -> t("$n 个 WiFi", "$n networks")
    }

    private suspend fun publish(kind: BackupKind, restore: Boolean, step: Int, steps: Int, done: Int, total: Int, label: String) =
        withContext(Dispatchers.Main) { if (progress != null) progress = BackupProgress(restore, kind, step + 1, steps, done, total, label) }

    private fun tick(kind: BackupKind, restore: Boolean, step: Int, steps: Int, label: String) = Tick({ cancel }) { d, tot ->
        scope.launch { publish(kind, restore, step, steps, d, tot, label) }
    }

    private fun runOne(p: BackupProviders, k: BackupKind, out: OutputStream, step: Int, steps: Int, label: String): Int {
        if (cancel) throw CancellationException()
        val t = tick(k, false, step, steps, label)
        t.progress(0, 0)
        val w = out.bufferedWriter(Charsets.UTF_8)
        val n = when (k) {
            BackupKind.SMS -> p.exportSms(w, t)
            BackupKind.CALLS -> p.exportCalls(w, t)
            BackupKind.CONTACTS -> p.exportContacts(w, t)
            BackupKind.CALENDAR -> p.exportCalendar(w, t)
            BackupKind.WIFI -> { val b = p.wifiBytes() ?: throw java.io.IOException(t("读取 WiFi 配置失败（需要 Root）", "Cannot read WiFi config (root)")); w.flush(); out.write(b); WifiStore.countNetworks(String(b)) }
        }
        w.flush()
        return n
    }

    // ------------------------------------------------------------------ restore

    private fun head(fs: VirtualFs, path: String, n: Int = 4096): String = fs.openRead(path).use { i -> val b = ByteArray(n); val r = i.read(b).coerceAtLeast(0); String(b, 0, r) }

    private fun countIn(kind: BackupKind, open: () -> InputStream): Int? = runCatching {
        when (kind) {
            BackupKind.SMS, BackupKind.CALLS -> open().use { i -> val b = ByteArray(4096); val r = i.read(b).coerceAtLeast(0); SmsXml.peekCount(String(b, 0, r))?.second }
            BackupKind.CONTACTS -> open().bufferedReader().useLines { s -> s.count { it.trim().equals("BEGIN:VCARD", true) } }
            BackupKind.CALENDAR -> open().bufferedReader().useLines { s -> s.count { it.trim() == "BEGIN:VEVENT" } }
            BackupKind.WIFI -> open().use { WifiStore.countNetworks(String(it.readBytes())) }
        }
    }.getOrNull()

    /** Lists what a backup holds (reads the manifest when there is one). */
    fun inspect(fsm: FsManager, src: RestoreSource): RestoreInventory {
        val fs = fsm.get(src.fsId)
        val e = fs.stat(src.path) ?: throw java.io.FileNotFoundException(src.path)
        val name = VPath.name(src.path)
        val dir = when { e.isDirectory -> src.path; name.equals(BackupManifest.NAME, true) -> VPath.parent(src.path); else -> null }
        val label = fsm.label(src.fsId) + "  " + (dir ?: src.path)
        if (dir != null) {
            val m = runCatching { BackupManifest.parse(fs.openRead(VPath.join(dir, BackupManifest.NAME)).use { String(it.readBytes()) }) }.getOrNull()
            val list = fs.list(dir)
            val entries = ArrayList<RestoreEntry>()
            if (m != null) {
                for ((k, f) in m.files) if (fs.stat(VPath.join(dir, f)) != null) entries.add(RestoreEntry(k, m.counts[k], f))
            } else {
                for (f in list) {
                    val k = BackupManifest.kindOf(f.name) ?: continue
                    if (f.isDirectory || entries.any { it.kind == k }) continue
                    entries.add(RestoreEntry(k, countIn(k) { fs.openRead(f.path) }, f.name))
                }
                if (fs.stat(VPath.join(dir, "wifi/WifiConfigStore.xml")) != null) entries.add(RestoreEntry(BackupKind.WIFI, null, "wifi/WifiConfigStore.xml"))
            }
            return RestoreInventory(RestoreSource(src.fsId, dir), label, RestoreInventory.Type.FOLDER, entries, m?.created ?: e.lastModified, m?.device ?: "")
        }
        if (name.endsWith(".zip", true)) {
            var m: BackupManifest? = null
            val found = ArrayList<RestoreEntry>()
            ZipInputStream(fs.openRead(src.path).buffered(1 shl 16)).use { z ->
                while (true) {
                    val ze = z.nextEntry ?: break
                    if (ze.name == BackupManifest.NAME) { m = BackupManifest.parse(String(z.readBytes())); if (m != null) break; continue }
                    val k = BackupManifest.kindOf(VPath.name(ze.name)) ?: continue
                    if (found.none { it.kind == k }) found.add(RestoreEntry(k, countIn(k) { NoCloseIn(z) }, ze.name))
                }
            }
            val entries = m?.let { mm -> mm.files.map { (k, f) -> RestoreEntry(k, mm.counts[k], f) } } ?: found
            return RestoreInventory(src, label, RestoreInventory.Type.ZIP, entries, m?.created ?: e.lastModified, m?.device ?: "")
        }
        val k = BackupManifest.kindOf(name) ?: throw java.io.IOException(t("不是可识别的备份文件", "Not a backup file"))
        return RestoreInventory(src, label, RestoreInventory.Type.FILE, listOf(RestoreEntry(k, countIn(k) { fs.openRead(src.path) }, name)), e.lastModified)
    }

    /** Backups in the default folder, newest first: folders with a manifest and ghost-backup zips. */
    fun recent(fsm: FsManager): List<RestoreSource> = runCatching {
        val dir = defaultDir(fsm)
        fsm.local.list(dir).filter { it.isDirectory && java.io.File(it.path, BackupManifest.NAME).exists() || BackupManifest.opensRestore(it.name) }
            .sortedByDescending { it.lastModified }.map { RestoreSource(LocalFs.ID, it.path) }
    }.getOrDefault(emptyList())

    fun isDefaultSms(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    fun restore(ctx: Context, fsm: FsManager, inv: RestoreInventory, kinds: List<BackupKind>) {
        if (running || kinds.isEmpty()) return
        cancel = false; outcome = null
        val order = BackupKind.entries.filter { it in kinds }
        progress = BackupProgress(true, order.first(), 1, order.size, 0, 0, inv.label)
        scope.launch {
            val p = BackupProviders(ctx)
            val fs = fsm.get(inv.source.fsId)
            val lines = ArrayList<BackupLine>()
            var cancelled = false; var error: String? = null
            fun oneUnsafe(k: BackupKind, step: Int, input: InputStream) {
                val t = tick(k, true, step, order.size, inv.label)
                t.progress(0, 0)
                if (k == BackupKind.SMS && !isDefaultSms(ctx)) { lines.add(BackupLine(k, t("未设为默认短信应用，已跳过", "Not the default SMS app: skipped"), false)); return }
                if (k == BackupKind.WIFI) {
                    val target = p.restoreWifi(input.readBytes())
                    lines.add(BackupLine(k, t("已写入 $target，建议重启手机生效", "Written to $target; reboot to apply")))
                    return
                }
                val r: Reader = input.bufferedReader(Charsets.UTF_8)
                val c = when (k) {
                    BackupKind.SMS -> p.restoreSms(r, t)
                    BackupKind.CALLS -> p.restoreCalls(r, t)
                    BackupKind.CONTACTS -> p.restoreContacts(r, t)
                    BackupKind.CALENDAR -> p.restoreCalendar(r, t)
                    else -> return
                }
                lines.add(BackupLine(k, t("新增 ${c.added}，已存在跳过 ${c.skipped}", "added ${c.added}, skipped ${c.skipped} existing") + if (c.failed > 0) t("，失败 ${c.failed}", ", failed ${c.failed}") else "", c.failed == 0))
            }
            fun one(k: BackupKind, step: Int, input: InputStream) = safe(k, lines) { oneUnsafe(k, step, input) }
            try {
                when (inv.type) {
                    RestoreInventory.Type.FOLDER -> order.forEachIndexed { i, k ->
                        val e = inv.entries.firstOrNull { it.kind == k } ?: return@forEachIndexed
                        if (cancel) throw CancellationException()
                        fs.openRead(VPath.join(inv.source.path, e.name)).buffered(1 shl 16).use { one(k, i, it) }
                    }
                    RestoreInventory.Type.FILE -> fs.openRead(inv.source.path).buffered(1 shl 16).use { one(order.first(), 0, it) }
                    RestoreInventory.Type.ZIP -> ZipInputStream(fs.openRead(inv.source.path).buffered(1 shl 16)).use { z ->
                        val want = inv.entries.filter { it.kind in order }.associateBy { it.name }
                        while (true) {
                            if (cancel) throw CancellationException()
                            val ze = z.nextEntry ?: break
                            val e = want[ze.name] ?: continue
                            one(e.kind, order.indexOf(e.kind), NoCloseIn(z))
                        }
                    }
                }
            } catch (e: CancellationException) { cancelled = true } catch (e: Throwable) { error = e.message ?: e.toString() }
            withContext(Dispatchers.Main) { progress = null; outcome = BackupOutcome(true, lines, inv.label, cancelled, error) }
        }
    }
}
