package com.ghostpanter.files.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ghostpanter.files.GhostApp
import com.ghostpanter.files.R
import com.ghostpanter.files.core.server.HostedKind
import com.ghostpanter.files.core.server.HostedServerHub
import com.ghostpanter.files.t
import com.ghostpanter.files.ui.MainActivity
import java.io.File

/**
 * Foreground service that keeps hosted FTP / WebDAV / SFTP listeners alive with
 * a partial wake lock and high-performance Wi-Fi lock.
 */
class HostedServerService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var netReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startFg(buildNotification())
        acquireLocks()
        registerWifiWatcher()
        Runtime.syncFromStore(this)
        Runtime.refreshListenHint(this)
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> {
                Runtime.refreshListenHint(this)
            }
            ACTION_STOP_ALL -> {
                Runtime.hub(this).stopAll()
                for (k in listOf(HostedKind.FTP, HostedKind.WEBDAV, HostedKind.SFTP, HostedKind.SMB)) {
                    val c = GhostApp.graph.hosted.get(k)
                    GhostApp.graph.hosted.upsert(c.copy(enabled = false))
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET -> {
                val baseName = intent.getStringExtra(EXTRA_KIND) ?: return START_STICKY
                val enable = intent.getBooleanExtra(EXTRA_ENABLE, false)
                val base = HostedKind.valueOf(baseName)
                Runtime.set(this, base, enable)
                if (!Runtime.hub(this).anyRunning()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_BOOT -> {
                Runtime.startEnabled(this)
                if (!Runtime.hub(this).anyRunning()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        updateNotification()
        return START_STICKY
    }

    private fun registerWifiWatcher() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Runtime.refreshListenHint(this@HostedServerService)
                if (!LanAddresses.wifiConnected(this@HostedServerService)) {
                    val hub = Runtime.hub(this@HostedServerService)
                    for (k in listOf(HostedKind.FTP, HostedKind.WEBDAV, HostedKind.SFTP)) {
                        val cfg = GhostApp.graph.hosted.get(k)
                        if (cfg.stopOnWifiLost && (hub.status(k).running ||
                                (k == HostedKind.FTP && hub.status(HostedKind.FTPS).running) ||
                                (k == HostedKind.WEBDAV && hub.status(HostedKind.WEBDAVS).running))
                        ) {
                            Runtime.set(this@HostedServerService, k, false)
                        }
                    }
                    if (!hub.anyRunning()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return
                    }
                }
                updateNotification()
            }
        }
        netReceiver = r
        @Suppress("DEPRECATION")
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_SERVERS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, HostedServerService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hub = Runtime.hub(this)
        val running = listOf(
            HostedKind.FTP, HostedKind.FTPS, HostedKind.WEBDAV, HostedKind.WEBDAVS, HostedKind.SFTP,
        ).map { hub.status(it) }.filter { it.running }
        val ip = LanAddresses.primaryIpv4(this) ?: "…"
        val title = if (running.isEmpty()) t("本机服务已停止", "Hosted services stopped")
        else t("本机服务运行中（${running.size}）", "Hosting ${running.size} service(s)")
        val lines = running.joinToString("\n") {
            "${it.kind.label}  $ip:${it.port}" +
                if (it.clientCount > 0) t(" · ${it.clientCount} 客户端", " · ${it.clientCount} client(s)") else ""
        }.ifEmpty { t("点击管理", "Tap to manage") }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(lines.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, t("全部停止", "Stop all"), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GhostFiles:hosted").apply {
                    setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
                }
        }
        runCatching {
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GhostFiles:hosted").apply {
                    setReferenceCounted(false); acquire()
                }
        }
    }

    override fun onDestroy() {
        runCatching { netReceiver?.let { unregisterReceiver(it) } }
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    object Runtime {
        @Volatile private var hubInstance: HostedServerHub? = null

        fun hub(ctx: Context): HostedServerHub {
            hubInstance?.let { return it }
            synchronized(this) {
                hubInstance?.let { return it }
                val app = ctx.applicationContext
                val created = HostedServerHub(File(app.filesDir, "hosted")) {
                    GhostApp.graph.hosted.defaultRoot()
                }
                hubInstance = created
                return created
            }
        }

        fun syncFromStore(ctx: Context) {
            val h = hub(ctx)
            for (c in GhostApp.graph.hosted.configs.values) h.putConfig(c)
        }

        fun refreshListenHint(ctx: Context) {
            hub(ctx).updateListenHint(LanAddresses.primaryIpv4(ctx))
        }

        fun startEnabled(ctx: Context) {
            syncFromStore(ctx)
            refreshListenHint(ctx)
            for (c in GhostApp.graph.hosted.configs.values) {
                if (!c.enabled) continue
                if (c.kind == HostedKind.FTPS || c.kind == HostedKind.WEBDAVS) continue
                set(ctx, c.kind, true)
            }
        }

        /**
         * [base] is FTP / WEBDAV / SFTP / SMB. TLS variants are derived from [HostedServiceConfig.useTls].
         */
        @Synchronized
        fun set(ctx: Context, base: HostedKind, enable: Boolean) {
            syncFromStore(ctx)
            refreshListenHint(ctx)
            val store = GhostApp.graph.hosted
            val cfg = store.get(base)
            store.upsert(cfg.copy(enabled = enable))
            val h = hub(ctx)
            when (base) {
                HostedKind.FTP -> {
                    h.stop(HostedKind.FTP); h.stop(HostedKind.FTPS)
                    if (enable) {
                        val runKind = if (cfg.useTls) HostedKind.FTPS else HostedKind.FTP
                        h.putConfig(cfg.copy(kind = runKind, enabled = true, useTls = cfg.useTls))
                        h.start(runKind)
                    }
                }
                HostedKind.WEBDAV -> {
                    h.stop(HostedKind.WEBDAV); h.stop(HostedKind.WEBDAVS)
                    if (enable) {
                        val runKind = if (cfg.useTls) HostedKind.WEBDAVS else HostedKind.WEBDAV
                        h.putConfig(cfg.copy(kind = runKind, enabled = true, useTls = cfg.useTls))
                        h.start(runKind)
                    }
                }
                HostedKind.SFTP -> {
                    h.stop(HostedKind.SFTP)
                    if (enable) {
                        h.putConfig(cfg.copy(enabled = true))
                        h.start(HostedKind.SFTP)
                    }
                }
                HostedKind.SMB -> {
                    h.stop(HostedKind.SMB)
                    if (enable) {
                        h.putConfig(cfg.copy(enabled = true))
                        h.start(HostedKind.SMB)
                    }
                }
                HostedKind.HTTP -> {
                    h.stop(HostedKind.HTTP)
                    if (enable) {
                        h.putConfig(cfg.copy(enabled = true))
                        h.start(HostedKind.HTTP)
                    }
                }
                else -> {}
            }
        }
    }

    companion object {
        const val CHANNEL = "hosted_servers"
        const val NOTIF_ID = 77
        const val ACTION_SET = "com.ghostpanter.files.hosted.SET"
        const val ACTION_REFRESH = "com.ghostpanter.files.hosted.REFRESH"
        const val ACTION_STOP_ALL = "com.ghostpanter.files.hosted.STOP_ALL"
        const val ACTION_BOOT = "com.ghostpanter.files.hosted.BOOT"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ENABLE = "enable"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.hosted_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        fun set(ctx: Context, base: HostedKind, enable: Boolean) {
            ensureChannel(ctx)
            val i = Intent(ctx, HostedServerService::class.java)
                .setAction(ACTION_SET)
                .putExtra(EXTRA_KIND, base.name)
                .putExtra(EXTRA_ENABLE, enable)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun refresh(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, HostedServerService::class.java).setAction(ACTION_REFRESH)
            )
        }
    }
}
