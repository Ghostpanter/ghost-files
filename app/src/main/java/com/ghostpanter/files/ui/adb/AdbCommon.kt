package com.ghostpanter.files.ui.adb

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.files.t
import com.ghostpanter.scrcpy.Devices
import com.ghostpanter.scrcpy.Mirror

/** "Pixel 8 (192.168.1.5:41234)" style label, or the bare serial before the model is known. */
fun adbLabel(serial: String): String = AdbPool.name(serial) ?: serial

/** Saved devices, re-read whenever the screen resumes (e.g. after pairing in the scrcpy panel). */
@Composable
fun rememberSavedDevices(): List<String> {
    var rev by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rev++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return remember(rev) { AdbPool.saved().map { AdbPool.serialOf(it) } }
}

/** 本机 / 目标设备 switch shown on the Apps and Terminal pages. `null` = this device. */
@Composable
fun TargetSwitch(current: String?, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    val devices = rememberSavedDevices()
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(current == null, { onPick(null) }, { Text(t("本机", "This device")) },
            leadingIcon = { Icon(Icons.Filled.Smartphone, null) })
        devices.forEach { s ->
            FilterChip(current == s, { onPick(s) }, { Text(t("目标：", "Target: ") + adbLabel(s)) },
                leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null) })
        }
        if (devices.isEmpty()) Text(t("（在「设备/投屏」中配对目标设备）", "(pair a target in Devices)"), modifier = Modifier.padding(start = 4.dp))
    }
}

object AdbNav {
    fun mirror(ctx: Context, serial: String) {
        val d = Devices.parseAddress(serial) ?: return
        ctx.startActivity(Intent(ctx, Mirror::class.java).putExtra(Mirror.EXTRA_HOST, d.host).putExtra(Mirror.EXTRA_PORT, d.port))
    }
    fun pairPanel(ctx: Context) {
        ctx.startActivity(Intent(ctx, com.ghostpanter.scrcpy.Main::class.java))
    }
}
