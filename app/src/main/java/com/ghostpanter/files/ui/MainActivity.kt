package com.ghostpanter.files.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ghostpanter.files.GhostApp
import com.ghostpanter.files.t
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val legacyPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.panes.forEach { p -> p.tabs.forEach { vm.refresh(it) } }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val safPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            GhostApp.graph.settings.addSafTree(uri.toString())
            vm.openFs("saf:$uri", "/")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        when {
            intent?.getBooleanExtra(EXTRA_SHOW_SERVERS, false) == true -> vm.screen = Screen.Servers
            intent?.getBooleanExtra(EXTRA_SHOW_TRANSFERS, false) == true -> vm.screen = Screen.Transfers
            intent?.action == "com.ghostpanter.files.action.SHOW_SERVERS" -> vm.screen = Screen.Servers
            intent?.action == "com.ghostpanter.files.action.SHOW_TRANSFERS" -> vm.screen = Screen.Transfers
            intent?.action == "com.ghostpanter.files.action.SCAN_LAN" -> vm.screen = Screen.LanScan
        }
        setContent {
            GhostThemeFromSettings(vm.g.settings.themeMode) { MainScreen(vm, this) }
        }
        lifecycleScope.launch {
            vm.events.collect { handle(it) }
        }
        askPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            intent.getBooleanExtra(EXTRA_SHOW_SERVERS, false) -> vm.screen = Screen.Servers
            intent.getBooleanExtra(EXTRA_SHOW_TRANSFERS, false) -> vm.screen = Screen.Transfers
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the "all files access" settings page.
        if (hasStorageAccess()) vm.panes.forEach { p -> p.tabs.filter { it.error != null }.forEach { vm.refresh(it) } }
    }

    override fun onPause() {
        super.onPause()
        vm.saveTabs()
    }

    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPerms.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun askPermissions() {
        if (!hasStorageAccess() && Build.VERSION.SDK_INT < 30) requestStorageAccess()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun handle(e: UiEvent) {
        when (e) {
            is UiEvent.Toast -> Toast.makeText(this, e.text, Toast.LENGTH_SHORT).show()
            is UiEvent.OpenFile -> {
                val uri = runCatching { uriFor(e.file) }.getOrElse {
                    Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show(); return
                }
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (e.chooser) "*/*".takeIf { e.mime == "*/*" } ?: e.mime else e.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try {
                    startActivity(if (e.chooser || e.mime == "*/*") Intent.createChooser(i, t("打开方式", "Open with")) else i)
                } catch (ex: ActivityNotFoundException) {
                    startActivity(Intent.createChooser(i.setDataAndType(uri, "*/*"), t("打开方式", "Open with")))
                }
            }
            is UiEvent.Share -> {
                val uris = ArrayList(e.files.map { uriFor(it) })
                val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(e.files[0].name))
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, t("分享", "Share")))
            }
            is UiEvent.ShareBluetooth -> {
                val uris = ArrayList(e.files.map { uriFor(it) })
                val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(e.files[0].name))
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Prefer system Bluetooth share activity when present
                i.setPackage("com.android.bluetooth")
                try {
                    startActivity(i)
                } catch (_: ActivityNotFoundException) {
                    i.setPackage(null)
                    startActivity(Intent.createChooser(i, t("蓝牙发送", "Bluetooth")))
                }
            }
            UiEvent.RequestAllFiles -> requestStorageAccess()
        }
    }

    fun installApk(f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Toast.makeText(this, t("请允许安装未知应用后重试", "Allow installing unknown apps, then retry"), Toast.LENGTH_LONG).show()
            return
        }
        startActivity(i)
    }

    fun pickSafTree(androidData: Boolean) {
        val initial = if (androidData) Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata") else null
        safPicker.launch(initial)
    }

    companion object {
        const val EXTRA_SHOW_SERVERS = "show_servers"
        const val EXTRA_SHOW_TRANSFERS = "show_transfers"
    }
}
