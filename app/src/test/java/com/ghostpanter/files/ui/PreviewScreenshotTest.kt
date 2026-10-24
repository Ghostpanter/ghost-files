package com.ghostpanter.files.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.ghostpanter.files.core.preview.ExifInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 0.7.2-test2 preview chrome snapshots: image viewer, PDF toolbar, docx/xlsx offline preview.
 * JVM/Paparazzi only — not a device verification of Coil/Pdfium/WebView rendering.
 */
@RunWith(Parameterized::class)
class PreviewScreenshotTest(private val name: String, private val device: DeviceConfig) {
    companion object {
        init { java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> = listOf(
            arrayOf("phone", DeviceConfig.PIXEL_5.copy(locale = "zh-rCN")),
            arrayOf("phone_dark", DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, locale = "zh-rCN")),
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 1.0)

    private val dark get() = device.nightMode == NightMode.NIGHT

    @Test fun imageViewer() = paparazzi.snapshot {
        GhostTheme(dark = dark) { ImageViewerChrome() }
    }

    @Test fun pdfToolbar() = paparazzi.snapshot {
        GhostTheme(dark = dark) { PdfChrome() }
    }

    @Test fun docxPreview() = paparazzi.snapshot {
        GhostTheme(dark = dark) { OfficeChrome(kind = "docx", title = "合同草案.docx") }
    }

    @Test fun xlsxPreview() = paparazzi.snapshot {
        GhostTheme(dark = dark) { OfficeChrome(kind = "xlsx", title = "销量汇总.xlsx") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewerChrome() {
    val info = ExifInfo(
        make = "Xiaomi", model = "23127PN0CC", aperture = "f/1.6",
        shutter = "1/120s", iso = "100", focalLength = "6.8mm",
        dateTime = "2026:10:24 14:32:08", width = 4096, height = 3072,
        latitude = 31.2304, longitude = 121.4737,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                colors = ghostTopBarColors(),
                title = { Text("IMG_20261024.jpg  (2/12)", maxLines = 1) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Filled.Info, null) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("预览区域", color = Color.White.copy(alpha = 0.5f))
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text("拍摄信息", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                @Composable
                fun row(k: String, v: String) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, modifier = Modifier.width(100.dp))
                        Text(v, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
                row("机型", listOfNotNull(info.make, info.model).joinToString(" "))
                row("光圈", info.aperture!!)
                row("快门", info.shutter!!)
                row("ISO", info.iso!!)
                row("焦距", info.focalLength!!)
                row("时间", info.dateTime!!)
                row("尺寸", info.sizeLabel()!!)
                row("GPS", info.gpsLabel()!!)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfChrome() {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = ghostTopBarColors(),
                title = { Text("说明书.pdf", maxLines = 1) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {}) { Icon(Icons.Filled.NavigateBefore, null) }
                Text("3 / 28", fontSize = 13.sp)
                IconButton(onClick = {}) { Icon(Icons.Filled.NavigateNext, null) }
                Spacer(Modifier.weight(1f))
                Text("−", fontSize = 18.sp, modifier = Modifier.padding(8.dp))
                Text("+", fontSize = 18.sp, modifier = Modifier.padding(8.dp))
                IconButton(onClick = {}) { Icon(Icons.Filled.Search, null) }
                IconButton(onClick = {}) { Icon(Icons.Filled.List, null) }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE8E8E8)),
                contentAlignment = Alignment.Center,
            ) {
                Text("PDF 页面（Pdfium）", color = Color.DarkGray)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficeChrome(kind: String, title: String) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = ghostTopBarColors(),
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (kind == "xlsx") {
                ScrollableTabRow(selectedTabIndex = 0) {
                    listOf("总表", "分区", "备注").forEachIndexed { i, n ->
                        Tab(selected = i == 0, onClick = {}, text = { Text(n) })
                    }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    if (kind == "docx") "离线 mammoth.js 预览（只读）\n\n第一条　合同双方……"
                    else "离线 SheetJS 预览（只读）\nA1  产品  B1  数量",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = {}, modifier = Modifier.padding(8.dp)) {
                Text("用其他应用打开")
            }
        }
    }
}
