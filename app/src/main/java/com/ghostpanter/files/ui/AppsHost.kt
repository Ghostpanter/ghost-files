package com.ghostpanter.files.ui

import com.ghostpanter.files.t
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ghostpanter.files.ui.adb.RemoteAppsPane
import com.ghostpanter.files.ui.adb.TargetSwitch
import com.ghostpanter.files.ui.adb.adbLabel

/** Apps page with the 本机 / 目标设备 switch. */
@Composable
fun AppsScreen(vm: MainViewModel) {
    val target = vm.appsTarget
    val header: @Composable () -> Unit = { TargetSwitch(target, { vm.appsTarget = it }, Modifier.fillMaxWidth()) }
    if (target == null) LocalAppsScreen(vm, header) else RemoteAppsScreen(vm, target, header)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAppsScreen(vm: MainViewModel, serial: String, header: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("应用管理 · ", "Apps · ") + adbLabel(serial)) },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { IconButton(onClick = { vm.screen = Screen.AdbTools(serial) }) { Icon(Icons.Filled.Build, t("设备工具", "Device tools")) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            header()
            RemoteAppsPane(vm::toast, serial)
        }
    }
}
