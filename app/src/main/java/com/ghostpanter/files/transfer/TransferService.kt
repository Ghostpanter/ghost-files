package com.ghostpanter.files.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ghostpanter.files.GhostApp
import com.ghostpanter.files.R
import com.ghostpanter.files.core.transfer.JobSnapshot
import com.ghostpanter.files.core.transfer.JobState
import com.ghostpanter.files.t
import com.ghostpanter.files.ui.MainActivity
import com.ghostpanter.files.ui.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process (and Wi-Fi radio) alive while the transfer queue is busy,
 * so copies continue with the screen off or the app in the background.
 * Holds a partial wake lock + high-performance Wi-Fi lock only while jobs run.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startFg(build(emptyList()))
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val engine = GhostApp.graph.transfers
        val nm = getSystemService(NotificationManager::class.java)
        var idleSince = 0L
        while (true) {
            val jobs = engine.jobs.value
            val active = jobs.filter { !it.state.finished }
            nm.notify(NOTIF_ID, build(active))
            if (active.isEmpty()) {
                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - idleSince > 1500) break
            } else idleSince = 0L
            delay(700)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun build(active: List<JobSnapshot>): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_TRANSFERS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (active.isEmpty()) return b.setContentTitle(t("传输完成", "Transfers finished")).build()
        val total = active.sumOf { it.totalBytes }
        val done = active.sumOf { it.doneBytes }
        val speed = active.sumOf { it.bytesPerSec }
        val first = active.first()
        val paused = active.all { it.state == JobState.PAUSED }
        b.setContentTitle(if (active.size == 1) first.title else t("${active.size} 个传输任务", "${active.size} transfers"))
            .setContentText(
                (if (paused) t("已暂停 · ", "Paused · ") else "") +
                    "${formatSize(done)} / ${formatSize(total)}" + (if (speed > 0) " · ${formatSize(speed)}/s" else "") +
                    (first.currentName?.let { " · $it" } ?: "")
            )
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total == 0L)
        return b.build()
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GhostFiles:transfer").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        runCatching {
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GhostFiles:transfer").apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIF_ID = 42

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java)) }
        }
    }
}
