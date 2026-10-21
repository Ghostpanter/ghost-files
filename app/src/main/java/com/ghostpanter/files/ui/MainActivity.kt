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

    override fun attachBaseContext(newBase: android.content.Context) {
        // Apply the in-app language to Android resources too (app_name, system dialogs we inflate).
        super.attachBaseContext(LocaleCtx.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (com.ghostpanter.scrcpy.ZhuoyitongGuard.blockIfLocal(this)) return
        when {
            intent?.getBooleanExtra(EXTRA_SHOW_SERVERS, false) == true -> vm.screen = Screen.Servers
            intent?.getBooleanExtra(EXTRA_SHOW_TRANSFERS, false) == true -> vm.screen = Screen.Transfers
            intent?.action == "com.ghostpanter.files.action.SHOW_SERVERS" -> vm.screen = Screen.Servers
            intent?.action == "com.ghostpanter.files.action.SHOW_TRANSFERS" -> vm.screen = Screen.Transfers
            intent?.action == "com.ghostpanter.files.action.SCAN_LAN" -> vm.screen = Screen.LanScan
        }
        intent?.let { handleAdbIntent(it) }
        if (com.ghostpanter.files.BuildConfig.DEBUG) intent?.getStringExtra("debug_fs")?.let { fs ->
            vm.openFs(fs, intent.getStringExtra("debug_path") ?: "/")
        }
        if (com.ghostpanter.files.BuildConfig.DEBUG) intent?.getStringExtra("debug_bench")?.let { list ->
            val fsId = intent.getStringExtra("debug_fs") ?: "local"
            intent.getLongExtra("debug_timeout_ms", 0L).takeIf { it > 0 }?.let { Thumbnails.VIDEO_TIMEOUT_MS = it }
            Thread {
                for (path in list.split(',')) {
                    val e = runCatching { vm.fsm.get(fsId).stat(path) }.getOrNull()
                    if (e == null) { android.util.Log.i("GFBench", "missing $path"); continue }
                    Thumbnails.clearAll()
                    val t0 = System.nanoTime()
                    val b = runCatching { vm.thumbnail(fsId, e, 96) }.getOrNull()
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    android.util.Log.i("GFBench", "fs=$fsId size=${e.size} ok=${b != null} ms=$ms $path")
                }
                android.util.Log.i("GFBench", "BENCH_DONE")
            }.start()
        }
        setContent {
            GhostThemeFromSettings(vm.g.settings.themeMode, vm.g.settings.oledBlack) { MainScreen(vm, this) }
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
        handleAdbIntent(intent)
    }

    /** From the embedded scrcpy panel: open the ADB tools page or browse the device in the active pane. */
    private fun handleAdbIntent(i: Intent) {
        // OTG: launched by plugging in a device with USB debugging (permission comes with the intent).
        if (i.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) { vm.screen = Screen.Devices; i.action = null }
        i.getStringExtra(EXTRA_ADB_TOOLS)?.let { vm.screen = Screen.AdbTools(it); i.removeExtra(EXTRA_ADB_TOOLS) }
        i.getStringExtra(EXTRA_ADB_BROWSE)?.let { vm.openFs(com.ghostpanter.adb.AdbFs.idFor(it, false)); i.removeExtra(EXTRA_ADB_BROWSE) }
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
        const val EXTRA_ADB_TOOLS = "adb_tools"
        const val EXTRA_ADB_BROWSE = "adb_browse"
        const val EXTRA_SHOW_TRANSFERS = "show_transfers"
    }
}

/** Applies the in-app language (Settings → 语言) to a Context's Android resources. */
object LocaleCtx {
    fun wrap(newBase: android.content.Context): android.content.Context {
        val pref = newBase.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("language", "system")
        return if (pref == "zh" || pref == "en") {
            val loc = if (pref == "en") java.util.Locale.ENGLISH else java.util.Locale.SIMPLIFIED_CHINESE
            val cfg = android.content.res.Configuration(newBase.resources.configuration).apply { setLocale(loc) }
            newBase.createConfigurationContext(cfg)
        } else newBase
    }
}
