package com.ghostpanter.files.privilege

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.ghostpanter.files.t

/** Notification with RemoteInput for the six-digit wireless-debug pairing code (Android 11+). */
object PairingNotifier {
    const val CHANNEL = "embedded_privilege"
    const val NOTIF_ID = 41271
    const val KEY_CODE = "pair_code"
    const val ACTION_REPLY = "com.ghostpanter.files.privilege.PAIR_CODE"
    const val ACTION_CANCEL = "com.ghostpanter.files.privilege.PAIR_CANCEL"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                t("内置提权", "Built-in privilege"),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = t("输入无线调试配对码", "Enter wireless debugging pairing code") },
        )
    }

    fun show(ctx: Context) {
        ensureChannel(ctx)
        val reply = RemoteInput.Builder(KEY_CODE)
            .setLabel(t("6 位配对码", "6-digit code"))
            .build()
        val replyPi = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, PairingCodeReceiver::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val cancelPi = PendingIntent.getBroadcast(
            ctx, 2,
            Intent(ctx, PairingCodeReceiver::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(t("内置提权 · 输入配对码", "Built-in privilege · pairing code"))
            .setContentText(t("打开「开发者选项 → 无线调试 → 使用配对码配对设备」，把 6 位码填到这里",
                "Open Developer options → Wireless debugging → Pair with pairing code, then enter the 6 digits here"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                t("1. 开启无线调试\n2. 点「使用配对码配对设备」\n3. 在本通知中输入 6 位配对码",
                    "1. Enable Wireless debugging\n2. Tap Pair with pairing code\n3. Enter the 6-digit code in this notification"),
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(
                NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, t("输入配对码", "Enter code"), replyPi)
                    .addRemoteInput(reply).build(),
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, t("取消", "Cancel"), cancelPi)
            .setOngoing(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
    }

    fun cancel(ctx: Context?) {
        ctx ?: return
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            PairingNotifier.ACTION_CANCEL -> {
                PairingNotifier.cancel(context)
                EmbeddedPrivilege.stop()
            }
            PairingNotifier.ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(PairingNotifier.KEY_CODE)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (code.matches(Regex("\\d{6}"))) {
                    PairingNotifier.cancel(context)
                    EmbeddedPrivilege.submitPairingCode(code)
                } else {
                    // Re-show so the user can try again.
                    PairingNotifier.show(context)
                }
            }
        }
    }
}
