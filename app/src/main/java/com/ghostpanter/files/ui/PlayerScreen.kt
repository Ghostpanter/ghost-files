package com.ghostpanter.files.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, s: Screen.Player) {
    val ctx = LocalContext.current
    var index by remember { mutableIntStateOf(s.siblings.indexOf(s.path).coerceAtLeast(0)) }
    val path = s.siblings.getOrNull(index) ?: s.path
    var local by remember(path) { mutableStateOf<File?>(null) }
    var err by remember(path) { mutableStateOf<String?>(null) }
    var audioOnly by remember { mutableStateOf(s.audioOnly) }

    LaunchedEffect(path) {
        local = null; err = null
        withContext(Dispatchers.IO) {
            runCatching {
                val e = vm.fsm.get(s.fsId).stat(path) ?: error("missing")
                // Prefer local path; otherwise cache to open/
                vm.fsm.localFile(s.fsId, path)?.takeIf { it.canRead() }
                    ?: run {
                        val dir = File(ctx.cacheDir, "play/" + Integer.toHexString((s.fsId + path).hashCode()))
                        dir.mkdirs()
                        val out = File(dir, VPath.name(path))
                        if (!(out.exists() && out.length() == e.size)) {
                            vm.fsm.get(s.fsId).openRead(path).use { input ->
                                out.outputStream().use { input.copyTo(it) }
                            }
                        }
                        out
                    }
            }.onSuccess { local = it }.onFailure { err = it.message }
        }
        val ext = VPath.extension(path)
        audioOnly = ext in AUDIO_EXT
    }

    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx)))
            .build()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(local) {
        val f = local ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        player.prepare()
        player.playWhenReady = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
                title = { Text(VPath.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { player.stop(); vm.screen = Screen.Main }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(enabled = index > 0, onClick = { index-- }) { Icon(Icons.Filled.SkipPrevious, t("上一个", "Prev")) }
                    Text("${index + 1}/${s.siblings.size.coerceAtLeast(1)}", modifier = Modifier.align(Alignment.CenterVertically))
                    IconButton(enabled = index < s.siblings.size - 1, onClick = { index++ }) { Icon(Icons.Filled.SkipNext, t("下一个", "Next")) }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
            when {
                err != null -> Text(err!!, color = Color.White, modifier = Modifier.align(Alignment.Center))
                local == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            this.player = player
                            useController = true
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (audioOnly && local != null) {
                Text(
                    t("后台也可继续播放；按返回停止", "Plays in background; back stops"),
                    color = Color.LightGray,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                )
            }
        }
    }
}
