package com.ghostpanter.files.space

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghostpanter.files.core.AppFolders
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.space.AppStat
import com.ghostpanter.files.core.space.CategoryTotals
import com.ghostpanter.files.core.space.DupGroups
import com.ghostpanter.files.core.space.DuplicateFinder
import com.ghostpanter.files.core.space.LocalWalker
import com.ghostpanter.files.core.space.NodeFlags
import com.ghostpanter.files.core.space.NodeKind
import com.ghostpanter.files.core.space.PathCanon
import com.ghostpanter.files.core.space.Residue
import com.ghostpanter.files.core.space.ResidueGroup
import com.ghostpanter.files.core.space.ResidueKind
import com.ghostpanter.files.core.space.Residues
import com.ghostpanter.files.core.space.ScanCancelled
import com.ghostpanter.files.core.space.ScanControl
import com.ghostpanter.files.core.space.ShellStreamScan
import com.ghostpanter.files.core.space.SpaceCache
import com.ghostpanter.files.core.space.SpaceEdits
import com.ghostpanter.files.core.space.SpaceNode
import com.ghostpanter.files.core.space.SpacePolicy
import com.ghostpanter.files.core.space.SpaceQueries
import com.ghostpanter.files.core.space.SpaceTree
import com.ghostpanter.files.core.space.SpaceTreeBuilder
import com.ghostpanter.files.core.space.StorageAggregate
import com.ghostpanter.files.core.space.VfsWalker
import com.ghostpanter.files.fs.FsManager
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.fs.ShizukuRunner
import com.ghostpanter.files.t
import com.ghostpanter.files.ui.AppIconCache
import com.ghostpanter.files.ui.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Which access the analysis runs with (decides what can be read and cleaned). */
enum class SpaceTier { BASIC, SHIZUKU, ROOT, ADB, ADB_ROOT, OTHER;
    fun label(): String = when (this) {
        BASIC -> t("非 Root", "No root")
        SHIZUKU -> "Shizuku"
        ROOT -> "Root"
        ADB -> "ADB"
        ADB_ROOT -> "ADB · Root"
        OTHER -> t("远程", "Remote")
    }
}

/** What to analyze: a backend + folder, or the whole device (Root). */
data class SpaceTarget(val fsId: String, val path: String, val wholeDevice: Boolean = false)

data class SpaceNotice(val text: String, val action: String? = null, val actionId: String? = null)

/** Storage totals for the summary card. */
data class SpaceSummary(val total: Long, val free: Long, val categories: CategoryTotals?)

/** Progress of a 「保留最优，删除其他」 batch. */
data class DupBatch(val done: Int, val total: Int, val bytes: Long, val failed: Int = 0, val current: String = "", val title: String = "")

/** Where the 卸载残留 scan looked and with what access. */
data class ResidueScan(val groups: List<ResidueGroup>, val via: String?, val limited: Boolean)

/**
 * One analysis (kept per target for the process lifetime): loads the cached tree instantly,
 * rescans in the background (incremental for local walks), exposes Compose state.
 */
class SpaceSession(val ctx: Context, val fsm: FsManager, val target: SpaceTarget) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var tier by mutableStateOf(SpaceTier.BASIC); private set
    /** Tree shown (scan or aggregated root). */
    var tree by mutableStateOf<SpaceTree?>(null); private set
    var scanning by mutableStateOf(false); private set
    /** True while a refresh runs behind a cached tree. */
    var background by mutableStateOf(false); private set
    var files by mutableLongStateOf(0); private set
    var bytes by mutableLongStateOf(0); private set
    var current by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var summary by mutableStateOf<SpaceSummary?>(null); private set
    var usageAccess by mutableStateOf(false); private set
    var version by mutableLongStateOf(0); private set
    var notices by mutableStateOf<List<SpaceNotice>>(emptyList()); private set
    var dups by mutableStateOf<List<DuplicateFinder.Group>?>(null); private set
    var dupProgress by mutableStateOf<Pair<Int, Int>?>(null); private set
    /** Keep choices the user tapped (one path per group; others use [com.ghostpanter.files.core.space.DupRanker]). */
    var keepChosen by mutableStateOf<Set<String>>(emptySet()); private set
    var batch by mutableStateOf<DupBatch?>(null); private set
    /** 卸载残留 (null = not scanned yet in this process). */
    var residues by mutableStateOf<ResidueScan?>(null); private set
    var residueScanning by mutableStateOf(false); private set
    var residueError by mutableStateOf<String?>(null); private set
    @Volatile private var batchCancel = false
    private val pendingRemoved = HashSet<String>()
    /** Root of the raw scan (inside an aggregated [tree]). */
    var scanRoot: SpaceNode? = null; private set
    var adbSerial: String? = null; private set

    private var job: Job? = null
    private var ctl: ScanControl? = null
    private var dupCtl: ScanControl? = null

    val running get() = job?.isActive == true

    init { tier = resolveTier() }

    private fun resolveTier(): SpaceTier = when {
        com.ghostpanter.adb.AdbFs.isAdbId(target.fsId) -> {
            adbSerial = com.ghostpanter.adb.AdbFs.serialOf(target.fsId)
            if (target.fsId.startsWith(com.ghostpanter.adb.AdbFs.PREFIX_SU)) SpaceTier.ADB_ROOT else SpaceTier.ADB
        }
        target.fsId == FsManager.ROOT -> SpaceTier.ROOT
        target.fsId == FsManager.SHIZUKU -> SpaceTier.SHIZUKU
        target.fsId == LocalFs.ID -> when {
            target.wholeDevice || RootRunner.granted() -> SpaceTier.ROOT
            ShizukuRunner.granted() -> SpaceTier.SHIZUKU
            else -> SpaceTier.BASIC
        }
        else -> SpaceTier.OTHER
    }

    private val storageRoot get() = fsm.storageRoot
    /** Local storage root analysis: the one that gets system aggregates. */
    val isStorageRoot get() = target.fsId == LocalFs.ID && !target.wholeDevice && VPath.normalize(target.path) == storageRoot
    val rooted get() = tier == SpaceTier.ROOT || tier == SpaceTier.ADB_ROOT

    private fun cacheFile(): File {
        val key = "${target.fsId}|${target.path}|${target.wholeDevice}|$tier"
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "space").apply { mkdirs() }, "$h.bin")
    }

    /** Opens the session: cached tree first (instant), then a background refresh. */
    fun open() {
        if (tree != null) { if (!running) revalidateDups(); return }
        if (running) return
        scope.launch {
            val cached = runCatching {
                DataInputStream(BufferedInputStream(cacheFile().inputStream(), 1 shl 16)).use { SpaceCache.read(it) }
            }.getOrNull()
            if (cached != null) {
                scanRoot = cached.tree.root.takeIf { it.kind == NodeKind.DIR } ?: cached.tree.root.children?.firstOrNull { it.kind == NodeKind.DIR }
                val cachedDups = DupGroups.decode(readDups(cached.meta))
                withContext(Dispatchers.Main) { tree = cached.tree; if (cachedDups != null && dups == null) dups = cachedDups; version++ }
                if (cachedDups != null) revalidateDups()
                if (isStorageRoot) loadStats()
            }
            withContext(Dispatchers.Main) { refresh(full = false) }
        }
    }

    /** Starts a scan. [full] = ignore the cached tree (no incremental reuse). */
    fun refresh(full: Boolean) {
        if (running) return
        tier = resolveTier()
        val c = ScanControl(); ctl = c
        val previous = if (full) null else scanRoot
        background = tree != null
        scanning = true; error = null; files = 0; bytes = 0
        job = scope.launch {
            val n = ArrayList<SpaceNotice>()
            val b = try { scan(c, previous, n) } catch (e: ScanCancelled) { null } catch (e: Throwable) {
                withContext(Dispatchers.Main) { error = e.message ?: e.toString() }; null
            }
            if (b != null && !c.isCancelled) {
                b.tree.scannedAt = System.currentTimeMillis()
                markPackages(b.root)
                scanRoot = b.root
                val shown = if (isStorageRoot) composeAggregates(b.root) else b.tree
                shown.scannedAt = b.tree.scannedAt
                if (tier == SpaceTier.ROOT && target.wholeDevice) markReadOnly(b.root)
                // Deleted while this scan was running (the walk may have listed them before).
                val late = synchronized(pendingRemoved) { pendingRemoved.toList().also { pendingRemoved.clear() } }
                if (late.isNotEmpty()) synchronized(shown.lock) { SpaceEdits.remove(shown, late) }
                withContext(Dispatchers.Main) {
                    tree = shown
                    // Files gone since the duplicate search drop out of their groups.
                    dups = dups?.let { d -> synchronized(shown.lock) { DupGroups.retain(d) { p -> SpaceTree.findIn(shown.root, p) != null } } }
                    version++
                }
                runCatching { save(shown) }
            }
            withContext(Dispatchers.Main) {
                scanning = false; background = false
                notices = n + baseNotices()
            }
        }
        // Progress ticker (incremental rendering while scanning).
        scope.launch {
            while (job?.isActive == true) {
                delay(250)
                withContext(Dispatchers.Main) { if (scanning) { current = c.current; version++ } }
            }
        }
    }

    fun cancel() { ctl?.cancel(); dupCtl?.cancel() }

    private suspend fun publish(b: SpaceTreeBuilder) {
        withContext(Dispatchers.Main) {
            // First scan with no cache: show the live tree (blocks appear as the walk goes).
            if (!background) { tree = b.tree; scanRoot = b.root }
        }
    }

    private suspend fun scan(c: ScanControl, previous: SpaceNode?, n: MutableList<SpaceNotice>): SpaceTreeBuilder {
        val ticker = scope.launch {
            while (isActive) { delay(250); val b = liveBuilder ?: continue; withContext(Dispatchers.Main) { files = b.files; bytes = b.bytes } }
        }
        try { return doScan(c, previous, n) } finally { ticker.cancel(); liveBuilder?.let { b -> withContext(Dispatchers.Main) { files = b.files; bytes = b.bytes } } }
    }

    @Volatile private var liveBuilder: SpaceTreeBuilder? = null

    private suspend fun doScan(c: ScanControl, previous: SpaceNode?, n: MutableList<SpaceNotice>): SpaceTreeBuilder {
        val path = VPath.normalize(target.path)
        when (tier) {
            SpaceTier.ROOT -> {
                if (!RootRunner.granted() && !RootRunner.check(timeoutSec = 20)) throw java.io.IOException(t("未获得 Root 授权", "Root not granted"))
                if (target.wholeDevice) {
                    val extra = runCatching {
                        com.ghostpanter.files.core.Proc.run(RootRunner, "ls -d /my_* /cache /data/log 2>/dev/null", 15_000).stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    }.getOrDefault(emptyList()).filter { it.startsWith("/") && it != "/data/log" }
                    val roots = PathCanon.dedupeRoots(listOf("/data", "/system", "/vendor", "/product", "/system_ext", "/odm") + extra)
                    val b = SpaceTreeBuilder("/").also { liveBuilder = it }
                    publish(b)
                    ShellStreamScan.run(RootRunner, roots, b, c)
                    return b
                }
                // Inside user storage: read /data/media directly (no FUSE), keep the user's spelling.
                val canon = PathCanon.canonical(path)
                val b = SpaceTreeBuilder(path).also { liveBuilder = it }
                publish(b)
                val viaMedia = canon != path && canon.startsWith("/data/media/")
                ShellStreamScan.run(RootRunner, listOf(canon), b, c) { p -> if (viaMedia) path + p.removePrefix(canon) else p }
                return b
            }
            SpaceTier.SHIZUKU -> {
                if (target.fsId == FsManager.SHIZUKU) return streamScan(ShizukuRunner, path, c)
                val b = SpaceTreeBuilder(path).also { liveBuilder = it }
                publish(b)
                val appDirs = listOf("$storageRoot/Android/data", "$storageRoot/Android/obb").filter { VPath.isSameOrDescendant(path, it) || VPath.isSameOrDescendant(it, path) }
                val inside = appDirs.firstOrNull { VPath.isSameOrDescendant(it, path) }
                if (inside != null) { ShellStreamScan.run(ShizukuRunner, listOf(path), b, c); return b }
                val walker = LocalWalker()
                // Android/data|obb through Shizuku in parallel with the local walk.
                val shz = scope.launch { runCatching { ShellStreamScan.run(ShizukuRunner, appDirs, b, c) } }
                walker.walk(path, b, c, previous?.takeIf { it.kind == NodeKind.DIR }, skip = { it in appDirs })
                shz.join()
                return b
            }
            SpaceTier.BASIC -> {
                val b = SpaceTreeBuilder(path).also { liveBuilder = it }
                publish(b)
                val restricted = Build.VERSION.SDK_INT >= 30
                LocalWalker(isAppParentUnreadable = { p -> restricted && (p.endsWith("/Android/data") || p.endsWith("/Android/obb")) })
                    .walk(path, b, c, previous?.takeIf { it.kind == NodeKind.DIR })
                if (restricted && VPath.isSameOrDescendant(path, "$storageRoot/Android/data")) {
                    n.add(SpaceNotice(
                        t("Android 11 起，Android/data 和 Android/obb 只有 Shizuku 或 Root 能读取，方块图里不含这两处（授予「使用情况访问」后计入「应用」）。",
                            "Since Android 11, Android/data and Android/obb are readable only with Shizuku or Root; they are not in the map (with usage access they count under Apps)."),
                        t("用 Shizuku", "Use Shizuku"), "shizuku",
                    ))
                }
                return b
            }
            SpaceTier.ADB, SpaceTier.ADB_ROOT -> {
                val dev = com.ghostpanter.adb.AdbPool.device(adbSerial!!)
                return streamScan(dev.runner(tier == SpaceTier.ADB_ROOT), path, c)
            }
            SpaceTier.OTHER -> {
                val b = SpaceTreeBuilder(path).also { liveBuilder = it }
                publish(b)
                VfsWalker.walk(fsm.get(target.fsId), path, b, c)
                return b
            }
        }
    }

    private suspend fun streamScan(runner: ShellRunner, path: String, c: ScanControl): SpaceTreeBuilder {
        val b = SpaceTreeBuilder(path).also { liveBuilder = it }
        publish(b)
        ShellStreamScan.run(runner, listOf(path), b, c)
        return b
    }

    // ------------------------------------------------------------------ system aggregates

    fun hasUsageAccess(): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private var appStats: List<AppStat>? = null

    private suspend fun loadStats() {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val uuid = StorageManager.UUID_DEFAULT
        val total = runCatching { ssm.getTotalBytes(uuid) }.getOrElse { StatFs(storageRoot).totalBytes }
        val free = runCatching { ssm.getFreeBytes(uuid) }.getOrElse { StatFs(storageRoot).availableBytes }
        val access = hasUsageAccess()
        var cats: CategoryTotals? = null
        if (access) {
            val user = Process.myUserHandle()
            cats = runCatching {
                val e = ssm.queryExternalStatsForUser(uuid, user)
                CategoryTotals(e.imageBytes, e.videoBytes, e.audioBytes, if (Build.VERSION.SDK_INT >= 30) e.appBytes else 0, e.totalBytes)
            }.getOrNull()
            val pm = ctx.packageManager
            appStats = runCatching {
                pm.getInstalledApplications(0).mapNotNull { ai: ApplicationInfo ->
                    runCatching {
                        val s = ssm.queryStatsForPackage(uuid, ai.packageName, user)
                        AppStat(ai.packageName, runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName), s.appBytes, s.dataBytes, s.cacheBytes)
                    }.getOrNull()
                }
            }.getOrNull()
        } else appStats = null
        withContext(Dispatchers.Main) { summary = SpaceSummary(total, free, cats); usageAccess = access }
    }

    private suspend fun composeAggregates(scan: SpaceNode): SpaceTree {
        loadStats()
        val s = summary ?: return SpaceTree(scan)
        val r = StorageAggregate.compose(
            label = t("内部存储", "Internal storage"), total = s.total, free = s.free, scan = scan,
            scannedAppSpecific = StorageAggregate.appSpecificBytes(scan), apps = appStats,
            appsLabel = t("应用", "Apps"), systemLabel = t("系统", "System"),
            detail = { a -> t("安装包 ", "App ") + formatSize(a.appBytes) + t(" · 数据 ", " · data ") + formatSize(a.dataBytes) + t(" · 缓存 ", " · cache ") + formatSize(a.cacheBytes) },
        )
        scan.name = storageRoot
        return SpaceTree(r.root)
    }

    /** Uninstall leftovers: package folders in Android/data|obb|media whose app is gone. */
    private suspend fun markPackages(root: SpaceNode) {
        val folders = synchronized(Any()) { SpaceQueries.packageFolders(root, AppFolders::isAppParent, AppFolders::packageOf) }
        val scopeId = if (adbSerial != null) "adb:$adbSerial" else AppIconCache.LOCAL
        for (f in folders) {
            val pkg = f.pkg ?: continue
            val installed = AppIconCache.isInstalled(ctx, scopeId, pkg) ?: continue
            val shared = f.parent?.name in setOf("data", "obb", "media") && f.parent?.parent?.name == "Android"
            if (!installed && shared) f.flags = f.flags or NodeFlags.RESIDUE
        }
    }

    private fun markReadOnly(root: SpaceNode) {
        root.children?.forEach { c -> if (SpacePolicy.readOnlyPartition(c.path())) c.flags = c.flags or NodeFlags.READ_ONLY }
    }

    private fun baseNotices(): List<SpaceNotice> {
        val out = ArrayList<SpaceNotice>()
        if (isStorageRoot && !usageAccess) out.add(SpaceNotice(
            t("授予「使用情况访问」后，方块图会加上「应用」和「系统」两块，总量和系统设置一致。", "Grant usage access to add Apps and System blocks so the total matches Settings."),
            t("去授权", "Grant"), "usage",
        ))
        if (tier == SpaceTier.BASIC && isStorageRoot) out.add(SpaceNotice(
            t("非 Root：其他应用的缓存只能在应用详情页里清理。", "Without root, other apps' caches can only be cleared from their App info page."),
        ))
        return out
    }

    /** writeUTF caps a value at 64 KB: duplicate groups go into numbered chunks. */
    private fun writeDups(meta: MutableMap<String, String>, groups: List<DuplicateFinder.Group>) {
        var i = 0; val sb = StringBuilder(); var bytes = 0
        fun flush() { meta["dups.$i"] = sb.toString(); i++; sb.setLength(0); bytes = 0 }
        for (line in DupGroups.encode(groups).split('\n').filter { it.isNotEmpty() }) {
            val b = line.toByteArray().size + 1
            if (b > 60_000) continue
            if (bytes + b > 60_000) flush()
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line); bytes += b
        }
        flush()
        meta["dups"] = i.toString()
    }

    private fun readDups(meta: Map<String, String>): String? {
        val n = meta["dups"]?.toIntOrNull() ?: return null
        return (0 until n).mapNotNull { meta["dups.$it"]?.takeIf { s -> s.isNotEmpty() } }.joinToString("\n")
    }

    /** Checks every file of every group still exists (cache reload / screen reopened). */
    fun revalidateDups() {
        val d = dups ?: return
        if (d.isEmpty()) return
        scope.launch {
            val all = d.flatMap { it.paths }.toSet()
            val missing = all.filter { p -> runCatching { val (fsId, lp) = locate(SpaceNode(p, NodeKind.FILE)); !fsm.get(fsId).exists(lp) }.getOrDefault(false) }
            if (missing.isNotEmpty()) applyRemoved(missing)
        }
    }

    private fun save(t: SpaceTree) {
        val f = cacheFile(); val tmp = File(f.path + ".tmp")
        val meta = HashMap<String, String>(); meta["tier"] = tier.name
        dups?.let { writeDups(meta, it) }
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { SpaceCache.write(it, t, meta) }
        tmp.renameTo(f)
    }

    // ------------------------------------------------------------------ actions

    /** Backend id + path to open [n] in a pane. */
    fun locate(n: SpaceNode): Pair<String, String> {
        val p = n.path()
        return when (tier) {
            SpaceTier.ROOT -> if (PathCanon.isUserStorage(p)) LocalFs.ID to PathCanon.userVisible(p) else FsManager.ROOT to p
            SpaceTier.SHIZUKU -> if (Build.VERSION.SDK_INT >= 30 && (p.contains("/Android/data") || p.contains("/Android/obb"))) FsManager.SHIZUKU to p
                else if (target.fsId == FsManager.SHIZUKU) FsManager.SHIZUKU to p else LocalFs.ID to p
            else -> target.fsId to p
        }
    }

    fun deleteNode(n: SpaceNode, useTrash: Boolean, trash: com.ghostpanter.files.core.ops.RecycleBin, done: (String?) -> Unit) {
        scope.launch {
            val err = runCatching {
                val (fsId, p) = locate(n)
                if (useTrash && fsId == LocalFs.ID) trash.moveToTrash(p) else fsm.get(fsId).delete(p, true)
            }.exceptionOrNull()
            if (err == null) {
                val (fsId, p) = locate(n)
                // Every other open analysis (whole device / storage root…) follows too.
                SpaceSessions.notifyRemoved(fsId, listOf(p), except = this@SpaceSession)
                applyRemoved(listOf(n.path()))
            }
            withContext(Dispatchers.Main) { done(err?.message) }
        }
    }

    /**
     * Files at [paths] (this session's namespace) are gone: detach them from the tree (sizes, largest
     * files, empty folders, treemap follow), drop them from duplicate groups, persist the cache.
     */
    suspend fun applyRemoved(paths: Collection<String>) {
        if (paths.isEmpty()) return
        if (running) synchronized(pendingRemoved) { pendingRemoved.addAll(paths) }
        val t = tree
        if (t != null) synchronized(t.lock) { SpaceEdits.remove(t, paths) }
        withContext(Dispatchers.Main) {
            dups = dups?.let { DupGroups.remove(it, paths) }
            keepChosen = keepChosen.filterTo(HashSet()) { k -> paths.none { VPath.isSameOrDescendant(it, k) } }
            version++
        }
        runCatching { tree?.let { save(it) } }
    }

    suspend fun applyMoved(from: String, to: String?) {
        if (to == null) return applyRemoved(listOf(from))
        val t = tree ?: return
        synchronized(t.lock) { SpaceEdits.move(t, from, to) }
        val inScope = synchronized(t.lock) { SpaceTree.findIn(t.root, to) != null }
        withContext(Dispatchers.Main) {
            // Moved out of the analyzed folder: like a delete for this session.
            dups = dups?.let { if (inScope) DupGroups.rename(it, from, to) else DupGroups.remove(it, listOf(from)) }
            keepChosen = keepChosen.mapTo(HashSet()) { k -> if (inScope && VPath.isSameOrDescendant(from, k)) to + k.removePrefix(from) else k }
            version++
        }
        runCatching { save(t) }
    }

    /** Maps a path of backend [fsId] into this session's tree namespace (null = unrelated backend). */
    fun toTreePath(fsId: String, path: String): String? {
        val p = VPath.normalize(path)
        val device = fsId == LocalFs.ID || fsId == FsManager.ROOT || fsId == FsManager.SHIZUKU
        return when (tier) {
            SpaceTier.ADB, SpaceTier.ADB_ROOT -> if (com.ghostpanter.adb.AdbFs.isAdbId(fsId) && com.ghostpanter.adb.AdbFs.serialOf(fsId) == adbSerial) p else null
            SpaceTier.ROOT -> if (device) PathCanon.canonical(p) else null
            SpaceTier.BASIC, SpaceTier.SHIZUKU -> if (device) PathCanon.userVisible(PathCanon.canonical(p)) else null
            SpaceTier.OTHER -> if (fsId == target.fsId) p else null
        }
    }

    fun chooseKeep(g: DuplicateFinder.Group, path: String) { keepChosen = keepChosen - g.paths.toSet() + path }

    fun mtimeOf(p: String): Long = tree?.find(p)?.mtime ?: 0

    fun cancelBatch() { batchCancel = true }

    /**
     * Deletes [paths] (the non-kept copies) one by one, to the recycle bin when [useTrash] and the file
     * is on local storage; progress in [batch]; finished files update the page right away.
     */
    fun deleteDuplicates(
        paths: List<String>, bytesEach: Map<String, Long>, useTrash: Boolean, trash: com.ghostpanter.files.core.ops.RecycleBin,
        title: String = t("正在删除重复文件…", "Deleting duplicates…"), done: (String) -> Unit,
    ) {
        if (batch != null || paths.isEmpty()) return
        batchCancel = false
        batch = DupBatch(0, paths.size, 0, title = title)
        scope.launch {
            var ok = 0; var failed = 0; var freed = 0L; var firstErr: String? = null
            val removed = ArrayList<String>()
            for ((i, p) in paths.withIndex()) {
                if (batchCancel) break
                val cur = freed; val f = failed
                withContext(Dispatchers.Main) { batch = DupBatch(i, paths.size, cur, f, VPath.name(p), title) }
                val (fsId, lp) = locate(SpaceNode(p, NodeKind.FILE))
                val err = runCatching { if (useTrash && fsId == LocalFs.ID) trash.moveToTrash(lp) else fsm.get(fsId).delete(lp, false) }.exceptionOrNull()
                if (err == null) {
                    ok++; freed += bytesEach[p] ?: 0; removed.add(p)
                    SpaceSessions.notifyRemoved(fsId, listOf(lp), except = this@SpaceSession)
                    if (removed.size >= 8) { applyRemoved(removed.toList()); removed.clear() }
                } else { failed++; if (firstErr == null) firstErr = err.message ?: err.toString() }
            }
            applyRemoved(removed)
            withContext(Dispatchers.Main) {
                batch = null
                done(buildString {
                    append(t("已删除 ", "Deleted ")); append(ok); append(t(" 个文件，释放 ", " files, freed ")); append(formatSize(freed))
                    if (failed > 0) { append(t("；失败 ", "; failed ")); append(failed); firstErr?.let { append(" (" + it + ")") } }
                })
            }
        }
    }

    // ------------------------------------------------------------------ 卸载残留

    /** Shell used for leftovers: Root > Shizuku (built-in / external) > none; ADB targets use the device. */
    private fun residueShell(): Triple<ShellRunner?, Boolean, String?> = when {
        tier == SpaceTier.ADB_ROOT -> Triple(com.ghostpanter.adb.AdbPool.device(adbSerial!!).runner(true), true, "ADB · Root")
        tier == SpaceTier.ADB -> Triple(com.ghostpanter.adb.AdbPool.device(adbSerial!!).runner(false), false, "ADB")
        RootRunner.granted() -> Triple(RootRunner, true, "Root")
        ShizukuRunner.granted() -> Triple(ShizukuRunner, false, "Shizuku")
        else -> Triple(null, false, null)
    }

    private fun residueParents(root: Boolean, shell: Boolean): List<Pair<String, ResidueKind>> {
        val storage = if (root) "/data/media/0" else "/storage/emulated/0"
        val all = Residues.parents(storage, root)
        // Without a shell only what this app can list itself: Android/data|obb are hidden on Android 11+.
        return if (shell) all else all.filter { (_, k) -> Build.VERSION.SDK_INT < 30 || (k != ResidueKind.DATA && k != ResidueKind.OBB) }
    }

    val residueSupported get() = tier != SpaceTier.OTHER

    fun scanResidues() {
        if (residueScanning || !residueSupported) return
        residueScanning = true; residueError = null
        scope.launch {
            val r = runCatching { findResidues() }
            withContext(Dispatchers.Main) {
                r.onSuccess { residues = it }.onFailure { residueError = it.message ?: it.toString() }
                residueScanning = false
            }
        }
    }

    private fun findResidues(): ResidueScan {
        val (runner, root, via) = residueShell()
        val parents = residueParents(root, runner != null)
        val adb = adbSerial != null
        // Installed = every user's packages (dual apps / work profile) + apps uninstalled with 「保留数据」.
        val installed = HashSet<String>()
        if (!adb) runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstalledPackages(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES).forEach { installed.add(it.packageName) }
        }
        val candidates: List<Residue>
        val sizes: Map<String, Long>
        if (runner != null) {
            val pm = Residues.PACKAGES_SCRIPT + "pm list packages -u 2>/dev/null; exit 0\n"
            installed += Residues.parsePackages(com.ghostpanter.files.core.Proc.run(runner, pm, 60_000, what = "pm list packages").stdout)
            val out = com.ghostpanter.files.core.Proc.run(runner, Residues.listScript(parents.map { it.first }), 60_000, what = t("扫描残留", "scan leftovers")).stdout
            candidates = Residues.leftovers(Residues.parseListing(out, parents), installed, ctx.packageName)
            sizes = if (candidates.isEmpty()) emptyMap() else Residues.parseSizes(
                com.ghostpanter.files.core.Proc.run(runner, Residues.sizeScript(candidates.map { it.path }), 300_000, what = t("计算残留大小", "size leftovers")).stdout
            )
        } else {
            val listing = parents.flatMap { (p, _) -> File(p).listFiles()?.filter { it.isDirectory }?.map { it.path } ?: emptyList() }
            candidates = Residues.leftovers(Residues.parseListing(listing.joinToString("\n"), parents), installed, ctx.packageName)
            sizes = candidates.associate { it.path to runCatching { File(it.path).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }.getOrDefault(0) }
        }
        if (installed.size < 5) error(t("读不到已安装应用列表，为安全起见未标记残留", "Couldn't read the installed app list; nothing flagged to stay safe"))
        val items = candidates.map { r -> r.copy(bytes = sizes[r.path] ?: 0, shown = if (root && !adb) PathCanon.userVisible(r.path) else r.path) }
        return ResidueScan(Residues.group(items), via, limited = runner == null)
    }

    /** Deletes the chosen leftovers (direct, no recycle bin), keeps every analysis in sync, then rescans. */
    fun deleteResidues(items: List<Residue>, done: (String) -> Unit) {
        if (batch != null || items.isEmpty()) return
        batchCancel = false
        val title = t("正在清理卸载残留…", "Removing leftovers…")
        batch = DupBatch(0, items.size, 0, title = title)
        scope.launch {
            val (runner, root, _) = residueShell()
            val parents = residueParents(root, runner != null)
            var ok = 0; var failed = 0; var freed = 0L; var firstErr: String? = null
            val gone = HashSet<String>()
            for ((i, r) in items.withIndex()) {
                if (batchCancel) break
                val cur = freed; val f = failed
                withContext(Dispatchers.Main) { batch = DupBatch(i, items.size, cur, f, r.pkg, title) }
                val err = runCatching {
                    require(Residues.deletable(r.path, parents)) { t("路径不在残留目录内: ", "Not a leftover path: ") + r.shown }
                    if (runner != null) {
                        val out = com.ghostpanter.files.core.Proc.run(runner, Residues.deleteScript(listOf(r.path)), 300_000, what = t("清理残留", "remove leftovers")).stdout
                        if (Residues.parseLeft(out).isNotEmpty()) error(t("部分文件没能删除", "Some files could not be removed"))
                    } else fsm.get(LocalFs.ID).delete(r.path, true)
                }.exceptionOrNull()
                if (err == null) {
                    ok++; freed += r.bytes; gone.add(r.path)
                    val (fsId, p) = when {
                        adbSerial != null -> target.fsId to r.path
                        PathCanon.isUserStorage(r.path) || r.path.startsWith("/storage/") -> LocalFs.ID to PathCanon.userVisible(PathCanon.canonical(r.path))
                        else -> FsManager.ROOT to r.path
                    }
                    SpaceSessions.notifyRemoved(fsId, listOf(p))
                } else { failed++; if (firstErr == null) firstErr = err.message ?: err.toString() }
            }
            withContext(Dispatchers.Main) {
                residues = residues?.let { s -> s.copy(groups = s.groups.mapNotNull { g -> g.items.filter { it.path !in gone }.takeIf { it.isNotEmpty() }?.let { ResidueGroup(g.pkg, it) } }) }
                batch = null
                done(buildString {
                    append(t("已清理 ", "Removed ")); append(ok); append(t(" 处残留，释放 ", " leftovers, freed ")); append(formatSize(freed))
                    if (failed > 0) { append(t("；失败 ", "; failed ")); append(failed); firstErr?.let { append(" (" + it + ")") } }
                })
            }
            withContext(Dispatchers.Main) { scanResidues() }
        }
    }

    /** Clears caches: one app ([pkg]) or every app (null). Returns bytes freed (approx.). */
    fun clearCaches(pkg: String?, done: (String) -> Unit) {
        scope.launch {
            val before = runCatching { StatFs("/data").availableBytes }.getOrDefault(0)
            val runner: ShellRunner? = when {
                tier == SpaceTier.ROOT || RootRunner.granted() -> RootRunner
                ShizukuRunner.granted() -> ShizukuRunner
                tier == SpaceTier.ADB_ROOT || tier == SpaceTier.ADB -> com.ghostpanter.adb.AdbPool.device(adbSerial!!).runner(tier == SpaceTier.ADB_ROOT)
                else -> null
            }
            val msg = if (runner == null) t("需要 Root 或 Shizuku", "Needs root or Shizuku") else runCatching {
                val root = runner === RootRunner || tier == SpaceTier.ADB_ROOT
                val script = CacheScripts.script(pkg, root, ctx.packageName)
                com.ghostpanter.files.core.Proc.run(runner, script, 300_000, what = t("清理缓存", "clear caches"))
                val after = runCatching { StatFs("/data").availableBytes }.getOrDefault(0)
                val freed = (after - before).coerceAtLeast(0)
                if (adbSerial != null) t("已清理缓存", "Caches cleared")
                else t("已清理缓存，释放约 ", "Caches cleared, about ") + formatSize(freed) + t("", " freed")
            }.getOrElse { t("清理失败: ", "Clear failed: ") + it.message }
            withContext(Dispatchers.Main) { done(msg) }
        }
    }

    fun findDuplicates(minBytes: Long = 1L shl 20) {
        val root = scanRoot ?: return
        val c = ScanControl(); dupCtl = c
        dups = null; dupProgress = 0 to 0
        scope.launch {
            val files = synchronized(tree?.lock ?: Any()) { SpaceQueries.filesAtLeast(root, minBytes).map { it.path() to it.size } }
            val f = DuplicateFinder({ p -> val (fsId, lp) = locateNodePath(p); fsm.get(fsId).openRead(lp) }, c)
            f.progress = { d, tot -> scope.launch(Dispatchers.Main) { dupProgress = d to tot } }
            val r = runCatching { f.find(files, minBytes) }.getOrNull()
            withContext(Dispatchers.Main) { dups = r ?: emptyList(); dupProgress = null; keepChosen = emptySet() }
            runCatching { tree?.let { save(it) } }
        }
    }

    private fun locateNodePath(p: String): Pair<String, String> = when (tier) {
        SpaceTier.ROOT -> FsManager.ROOT to PathCanon.canonical(p)
        else -> locate(SpaceNode(p, NodeKind.FILE))
    }

    fun close() { cancel(); scope.coroutineContext[Job]?.cancel() }
}

object CacheScripts {
    private fun q(s: String) = VPath.shellQuote(s)

    /** Root / shell script that empties cache dirs (never this app's own: the analysis cache lives there). */
    fun script(pkg: String?, root: Boolean, self: String): String {
        val p = pkg ?: "*"
        val guard = "case \"\$d\" in */$self/*) continue;; esac; "
        val clear = "[ -d \"\$d\" ] && find \"\$d\" -mindepth 1 -delete 2>/dev/null; "
        val shared = if (pkg != null) "/storage/emulated/0/Android/data/${q(pkg)}/cache" else "/storage/emulated/0/Android/data/*/cache"
        val sb = StringBuilder()
        if (pkg == null) sb.append("pm trim-caches 999999999999999 >/dev/null 2>&1\n")
        if (root) {
            val pq = if (pkg != null) q(pkg) else p
            val media = if (pkg != null) "/data/media/*/Android/data/$pq/cache" else "/data/media/*/Android/data/*/cache"
            sb.append("for d in /data/user/*/$pq/cache /data/user/*/$pq/code_cache /data/user_de/*/$pq/cache /data/user_de/*/$pq/code_cache $media; do $guard$clear done\n")
        } else {
            sb.append("for d in $shared; do $guard$clear done\n")
        }
        sb.append("exit 0\n")
        return sb.toString()
    }
}

/** Sessions survive leaving the screen (back from 「在面板中定位」 is instant). */
object SpaceSessions {
    private val map = HashMap<SpaceTarget, SpaceSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun get(ctx: Context, fsm: FsManager, t: SpaceTarget): SpaceSession = synchronized(map) {
        map.getOrPut(t) { SpaceSession(ctx.applicationContext, fsm, t) }
    }

    private fun all(except: SpaceSession?) = synchronized(map) { map.values.filter { it !== except } }

    /** Files deleted / trashed / moved away anywhere in the app (main panes, other analyses). */
    fun notifyRemoved(fsId: String, paths: List<String>, except: SpaceSession? = null) {
        val sessions = all(except).ifEmpty { return }
        scope.launch {
            for (s in sessions) runCatching { s.applyRemoved(paths.mapNotNull { s.toTreePath(fsId, it) }) }
        }
    }

    /** A move that stopped half-way: re-check which duplicate files still exist. */
    fun revalidateAll() { all(null).forEach { it.revalidateDups() } }

    /** A rename or a move ([toFsId] null or another backend = it left this device). */
    fun notifyMoved(fsId: String, from: String, toFsId: String?, to: String?) {
        val sessions = all(null).ifEmpty { return }
        scope.launch {
            for (s in sessions) runCatching {
                val f = s.toTreePath(fsId, from) ?: return@runCatching
                val t = if (toFsId != null && to != null) s.toTreePath(toFsId, to) else null
                s.applyMoved(f, t)
            }
        }
    }
}
