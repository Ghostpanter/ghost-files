package com.ghostpanter.files.privilege

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/** After boot (and when Wi-Fi comes up), try to reopen wireless debugging and the privilege server. */
class PrivilegeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != ConnectivityManager.CONNECTIVITY_ACTION
        ) return
        val app = context.applicationContext
        EmbeddedPrivilege.init(app)
        ElevatedAccess.init(app)
        // Defer so Wi-Fi / Settings settle.
        Thread({
            Thread.sleep(3_000)
            if (!EmbeddedPrivilege.wifiConnected(app)) return@Thread
            EmbeddedPrivilege.tryAutoStart(app)
        }, "priv-boot").start()
    }
}
