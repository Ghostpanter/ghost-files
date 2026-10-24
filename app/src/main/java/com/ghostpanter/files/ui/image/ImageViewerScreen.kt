package com.ghostpanter.files.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.preview.ExifInfo
import com.ghostpanter.files.t
import com.ghostpanter.files.ui.MainViewModel
import com.ghostpanter.files.ui.Screen
import com.ghostpanter.files.ui.SubScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(vm: MainViewModel, s: Screen.Image) {
    val siblings = remember(s.path, s.siblings) {
        if (s.siblings.isEmpty()) listOf(s.path) else s.siblings
    }
    val start = siblings.indexOf(s.path).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = start, pageCount = { siblings.size })
    var showInfo by remember { mutableStateOf(false) }
    var exif by remember { mutableStateOf(ExifInfo()) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val loader = remember { GhostImageLoader.get(ctx) }

    LaunchedEffect(pagerState.currentPage, siblings) {
        val path = siblings.getOrNull(pagerState.currentPage) ?: s.path
        loadErr = null
        currentFile = null
        exif = ExifInfo()
        runCatching {
            withContext(Dispatchers.IO) {
                val fs = vm.fsm.get(s.fsId)
                val entry = fs.stat(path) ?: error(t("文件不存在", "Missing file"))
                if (entry.size > 512L shl 20) error(t("图片过大（>512MB）", "Image too large (>512 MB)"))
                val file = vm.materializeForPreview(s.fsId, entry)
                file to ExifReader.read(file)
            }
        }.onSuccess { (f, info) ->
            currentFile = f
            exif = info
        }.onFailure { loadErr = it.message }
    }

    val title = "${VPath.name(siblings.getOrNull(pagerState.currentPage) ?: s.path)}  (${pagerState.currentPage + 1}/${siblings.size})"
    SubScaffold(title, { vm.screen = Screen.Main }, actions = {
        IconButton(onClick = { showInfo = true }) {
            Icon(Icons.Filled.Info, contentDescription = t("信息", "Info"))
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                val path = siblings[page]
                val zoomableState = rememberZoomableState()
                val imageState = rememberZoomableImageState(zoomableState)
                if (page == pagerState.currentPage) {
                    val file = currentFile
                    when {
                        loadErr != null -> Text(
                            loadErr!!,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        file == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> ZoomableAsyncImage(
                            model = file,
                            contentDescription = VPath.name(path),
                            modifier = Modifier.fillMaxSize(),
                            state = imageState,
                            imageLoader = loader,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        CircularProgressIndicator(
                            Modifier.align(Alignment.Center),
                            color = Color.White.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ExifInfoPanel(exif, Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun ExifInfoPanel(info: ExifInfo, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(t("拍摄信息", "Image info"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        if (!info.hasAny) {
            Text(t("无 EXIF 信息", "No EXIF metadata"), color = MaterialTheme.colorScheme.outline)
            return
        }
        @Composable
        fun row(k: String, v: String?) {
            if (v.isNullOrBlank()) return
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(k, color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, modifier = Modifier.width(100.dp))
                Text(v, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        val camera = listOfNotNull(info.make, info.model).joinToString(" ").ifBlank { null }
        row(t("机型", "Camera"), camera)
        row(t("光圈", "Aperture"), info.aperture)
        row(t("快门", "Shutter"), info.shutter)
        row("ISO", info.iso)
        row(t("焦距", "Focal length"), info.focalLength)
        row(t("时间", "Time"), info.dateTime)
        row(t("尺寸", "Size"), info.sizeLabel())
        row("GPS", info.gpsLabel())
    }
}
