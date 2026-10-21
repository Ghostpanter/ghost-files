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
import com.ghostpanter.files.core.space.DuplicateFinder
import com.ghostpanter.files.core.space.LocalWalker
import com.ghostpanter.files.core.space.NodeFlags
import com.ghostpanter.files.core.space.NodeKind
import com.ghostpanter.files.core.space.PathCanon
import com.ghostpanter.files.core.space.ScanCancelled
import com.ghostpanter.files.core.space.ScanControl
import com.ghostpanter.files.core.space.ShellStreamScan
import com.ghostpanter.files.core.space.SpaceCache
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
        if (tree != null || running) return
        scope.launch {
            val cached = runCatching {
                DataInputStream(BufferedInputStream(cacheFile().inputStream(), 1 shl 16)).use { SpaceCache.read(it) }
            }.getOrNull()
            if (cached != null) {
                scanRoot = cached.tree.root.takeIf { it.kind == NodeKind.DIR } ?: cached.tree.root.children?.firstOrNull { it.kind == NodeKind.DIR }
                withContext(Dispatchers.Main) { tree = cached.tree; version++ }
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
                withContext(Dispatchers.Main) { tree = shown; version++ }
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
                        RootRunner.start("ls -d /my_* /cache /data/log 2>/dev/null").let { p -> p.outputStream.close(); p.inputStream.bufferedReader().readLines().also { p.waitFor() } }
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

    private fun save(t: SpaceTree) {
        val f = cacheFile(); val tmp = File(f.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { SpaceCache.write(it, t, mapOf("tier" to tier.name)) }
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
                val t = tree
                if (t != null) synchronized(t.lock) { detachEverywhere(n) }
                runCatching { tree?.let { save(it) } }
            }
            withContext(Dispatchers.Main) { version++; done(err?.message) }
        }
    }

    private fun detachEverywhere(n: SpaceNode) {
        // Aggregated root: the scan node is a child; detaching from the scan keeps totals right,
        // and the 「系统」 block stays (it is total − rest, recomputed on the next scan).
        SpaceQueries.detach(n)
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
                val p = runner.start(script); p.outputStream.close(); p.inputStream.readBytes(); p.waitFor()
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
            withContext(Dispatchers.Main) { dups = r ?: emptyList(); dupProgress = null }
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
    fun get(ctx: Context, fsm: FsManager, t: SpaceTarget): SpaceSession = synchronized(map) {
        map.getOrPut(t) { SpaceSession(ctx.applicationContext, fsm, t) }
    }
}
