package com.ghostpanter.files.backup

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.widget.Toast
import com.ghostpanter.files.t

/*
 * Components Android requires before it lets an app hold the SMS role. This app only takes the role for
 * a moment to write restored messages; while it holds it, incoming SMS are still saved to the inbox and
 * compose requests are handed to the previous default app.
 */

/** Remembers the previous default SMS app so the restore page can send the user back to it. */
object SmsRoleMemory {
    private const val PREFS = "backup"
    fun previous(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString("prev_sms", null)
    fun remember(ctx: Context) {
        val cur = Telephony.Sms.getDefaultSmsPackage(ctx)
        if (cur != null && cur != ctx.packageName) ctx.getSharedPreferences(PREFS, 0).edit().putString("prev_sms", cur).apply()
    }
}

/** SMS_DELIVER: while we are (temporarily) default, store the message like the system app would. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (msgs.isEmpty()) return
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
        val first = msgs[0]
        runCatching {
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, first.displayOriginatingAddress)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, first.timestampMillis)
                put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                first.serviceCenterAddress?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
                put(Telephony.Sms.SUBSCRIPTION_ID, intent.getIntExtra("subscription", -1))
            })
        }
    }
}

/** WAP_PUSH_DELIVER (MMS notifications): required for the role; MMS download is left to the real SMS app. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {}
}

/** RESPOND_VIA_MESSAGE ("reply with a message" on incoming calls): required for the role; not used. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { stopSelf(startId); return START_NOT_STICKY }
}

/** SENDTO sms:/smsto:/mms: — forwards to the previous default SMS app. */
class SmsComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prev = SmsRoleMemory.previous(this)
        val fwd = Intent(intent.action ?: Intent.ACTION_SENDTO, intent.data).apply {
            intent.extras?.let { putExtras(it) }
            if (prev != null) setPackage(prev)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = prev != null && runCatching { startActivity(fwd) }.isSuccess
        if (!ok) Toast.makeText(this, t("双栏文件管理器只用于恢复短信，请把默认短信应用切回系统「信息」", "This app only restores messages; switch the default SMS app back"), Toast.LENGTH_LONG).show()
        finish()
    }
}
