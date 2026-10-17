package com.ghostpanter.files.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ghostpanter.files.GhostApp
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Process-wide libVLC instance (creating one is expensive). */
object Vlc {
    @Volatile private var lib: LibVLC? = null
    fun get(ctx: android.content.Context): LibVLC = lib ?: synchronized(this) {
        lib ?: LibVLC(ctx.applicationContext, arrayListOf("--no-drop-late-frames", "--no-skip-frames", "--network-caching=1500", "--file-caching=800")).also { lib = it }
    }
}

private fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Built-in libVLC video player. Local files play directly; every other backend streams via the loopback proxy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlcPlayerScreen(vm: MainViewModel, s: Screen.Player) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val settings = GhostApp.graph.settings
    var index by remember { mutableIntStateOf(s.siblings.indexOf(s.path).coerceAtLeast(0)) }
    val path = s.siblings.getOrNull(index) ?: s.path
    var err by remember(path) { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var controls by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    var rate by remember { mutableFloatStateOf(1f) }
    var hw by remember { mutableStateOf(settings.vlcHwDecode) }
    var menu by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }

    val lib = remember { Vlc.get(ctx) }
    val player = remember { MediaPlayer(lib) }
    val layout = remember { VLCVideoLayout(ctx) }

    DisposableEffect(Unit) {
        player.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> { playing = true; buffering = false }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> { timeMs = ev.timeChanged; buffering = false }
                MediaPlayer.Event.LengthChanged -> lengthMs = ev.lengthChanged
                MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
                MediaPlayer.Event.EndReached -> {
                    playing = false; ended = true; controls = true
                    if (index < s.siblings.size - 1) index++
                }
                MediaPlayer.Event.EncounteredError -> err = t("VLC 无法播放该文件", "VLC cannot play this file")
            }
        }
        onDispose {
            player.setEventListener(null)
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            player.release()
            vm.streamProxy.unregisterAll()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w -> WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    LaunchedEffect(path, hw) {
        err = null; buffering = true; timeMs = 0; lengthMs = 0; ended = false
        val resume = player.time.takeIf { player.isPlaying && it > 0 }
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val fs = vm.fsm.get(s.fsId)
                if (s.fsId == LocalFs.ID && java.io.File(path).canRead()) Uri.fromFile(java.io.File(path))
                else {
                    val st = fs.stat(path) ?: error(t("文件不存在", "File not found"))
                    Uri.parse(vm.streamProxy.register(fs, path, st.size, mimeOf(VPath.name(path))))
                }
            }.onFailure { err = it.message ?: it.javaClass.simpleName }.getOrNull()
        } ?: return@LaunchedEffect
        val m = Media(lib, uri)
        m.setHWDecoderEnabled(hw, false)
        player.media = m
        m.release()
        player.rate = rate
        player.play()
        resume?.let { player.time = it }
    }

    LaunchedEffect(controls, playing, menu, seeking) {
        if (controls && playing && menu == null && seeking == null) { delay(3500); controls = false }
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        activity?.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { w ->
            val c = WindowInsetsControllerCompat(w, w.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    androidx.activity.compose.BackHandler { if (fullscreen) toggleFullscreen() else vm.screen = Screen.Main }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                layout.also {
                    player.attachViews(it, null, true, false)
                    player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                    // Rotation / fullscreen changes the surface size: re-apply best-fit so the picture isn't cropped.
                    it.addOnLayoutChangeListener { _, l, t0, r, b, ol, ot, or, ob ->
                        if (r - l != or - ol || b - t0 != ob - ot) {
                            player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
                            player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { controls = !controls },
        )
        if (buffering && err == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        err?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }

        if (controls) {
            // top bar
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0x99000000)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (fullscreen) toggleFullscreen(); vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("返回", "Back"), tint = Color.White) }
                Text(VPath.name(path), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 14.sp)
                Box {
                    IconButton(onClick = { menu = "more" }) { Icon(Icons.Filled.MoreVert, t("更多", "More"), tint = Color.White) }
                    DropdownMenu(expanded = menu != null, onDismissRequest = { menu = null }) {
                        when (menu) {
                            "more" -> {
                                DropdownMenuItem(text = { Text(t("音轨", "Audio track")) }, leadingIcon = { Icon(Icons.Filled.Audiotrack, null) }, onClick = { menu = "audio" })
                                DropdownMenuItem(text = { Text(t("字幕", "Subtitles")) }, leadingIcon = { Icon(Icons.Filled.Subtitles, null) }, onClick = { menu = "spu" })
                                DropdownMenuItem(text = { Text(t("倍速", "Speed") + "  ${rate}x") }, leadingIcon = { Icon(Icons.Filled.Speed, null) }, onClick = { menu = "rate" })
                                DropdownMenuItem(
                                    text = { Text(t("硬件解码", "Hardware decoding") + if (hw) " ✓" else "") },
                                    leadingIcon = { Icon(Icons.Filled.Memory, null) },
                                    onClick = { hw = !hw; settings.vlcHwDecode = hw; menu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text(t("其他应用打开", "Open in another app")) },
                                    leadingIcon = { Icon(Icons.Filled.OpenInNew, null) },
                                    onClick = { menu = null; vm.openWithOtherApp(s.fsId, path) },
                                )
                            }
                            "audio" -> (player.audioTracks ?: emptyArray()).forEach { tr ->
                                DropdownMenuItem(text = { Text(tr.name + if (tr.id == player.audioTrack) " ✓" else "") }, onClick = { player.setAudioTrack(tr.id); menu = null })
                            }
                            "spu" -> {
                                val tracks = player.spuTracks ?: emptyArray()
                                if (tracks.isEmpty()) DropdownMenuItem(text = { Text(t("无字幕", "No subtitles")) }, onClick = { menu = null })
                                tracks.forEach { tr ->
                                    DropdownMenuItem(text = { Text(tr.name + if (tr.id == player.spuTrack) " ✓" else "") }, onClick = { player.setSpuTrack(tr.id); menu = null })
                                }
                            }
                            "rate" -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { r ->
                                DropdownMenuItem(text = { Text("${r}x" + if (r == rate) " ✓" else "") }, onClick = { rate = r; player.rate = r; menu = null })
                            }
                        }
                    }
                }
            }
            // bottom controls
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0x99000000)).navigationBarsPadding().padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtMs(seeking?.let { (it * lengthMs).toLong() } ?: timeMs), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = seeking ?: if (lengthMs > 0) (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { seeking = it; controls = true },
                        onValueChangeFinished = {
                            seeking?.let { pos -> if (ended) { ended = false; player.stop(); player.play() }; player.position = pos }
                            seeking = null
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(fmtMs(lengthMs), color = Color.White, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = index > 0, onClick = { index-- }) { Icon(Icons.Filled.SkipPrevious, t("上一个", "Prev"), tint = Color.White) }
                    IconButton(onClick = { player.time = (player.time - 10_000).coerceAtLeast(0) }) { Icon(Icons.Filled.Replay10, t("后退 10 秒", "Back 10s"), tint = Color.White) }
                    IconButton(onClick = {
                        when {
                            ended -> { ended = false; player.stop(); player.play() }
                            player.isPlaying -> player.pause()
                            else -> player.play()
                        }
                    }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, t("播放/暂停", "Play/Pause"), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { player.time = player.time + 10_000 }) { Icon(Icons.Filled.Forward10, t("前进 10 秒", "Forward 10s"), tint = Color.White) }
                    IconButton(enabled = index < s.siblings.size - 1, onClick = { index++ }) { Icon(Icons.Filled.SkipNext, t("下一个", "Next"), tint = Color.White) }
                    IconButton(onClick = { toggleFullscreen() }) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, t("全屏", "Fullscreen"), tint = Color.White)
                    }
                }
            }
        }
    }
}
