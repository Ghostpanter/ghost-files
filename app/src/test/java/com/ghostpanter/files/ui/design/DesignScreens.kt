package com.ghostpanter.files.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Chrome(val radius: Dp, val rowPadV: Dp, val titleSp: Float, val dense: Boolean, val floatBar: Boolean, val frosted: Boolean)

private fun chrome(dir: DesignDir) = when (dir) {
    DesignDir.A -> Chrome(4.dp, 4.dp, 15f, dense = true, floatBar = false, frosted = false)
    DesignDir.B -> Chrome(20.dp, 10.dp, 18f, dense = false, floatBar = true, frosted = false)
    DesignDir.C -> Chrome(10.dp, 8.dp, 17f, dense = false, floatBar = false, frosted = true)
}

@Composable
fun DesignThemeWrap(dir: DesignDir, dark: Boolean, oled: Boolean = dark, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DesignThemes.of(dir, dark, oled), content = content)
}

// ---- Main dual-pane -------------------------------------------------------

@Composable
fun ConceptMain(dir: DesignDir, tablet: Boolean, sideOpen: Boolean = tablet) {
    val c = chrome(dir)
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Row(Modifier.fillMaxSize()) {
            if (tablet) {
                Rail(dir)
                if (sideOpen) {
                    SidePanel(dir, Modifier.width(220.dp).fillMaxHeight())
                    if (dir == DesignDir.A) VerticalDivider(color = scheme.outline.copy(alpha = 0.45f))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(dir, tablet)
                PathStrip(dir, DesignFake.leftPath)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tablet || dir != DesignDir.B) {
                        Row(Modifier.fillMaxSize().padding(if (dir == DesignDir.B) 10.dp else 0.dp)) {
                            PaneCard(dir, "本机", DesignFake.leftPath, DesignFake.left, focused = true, Modifier.weight(1f))
                            if (dir == DesignDir.A) VerticalDivider(color = scheme.outline.copy(alpha = 0.4f))
                            else Spacer(Modifier.width(if (dir == DesignDir.B) 10.dp else 1.dp))
                            PaneCard(dir, "NAS (SMB)", DesignFake.rightPath, DesignFake.right, focused = false, Modifier.weight(1f))
                        }
                    } else {
                        // B phone: stacked rounded panes + FAB feel
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PaneCard(dir, "本机", DesignFake.leftPath, DesignFake.left.take(5), focused = true, Modifier.weight(1f))
                            PaneCard(dir, "NAS (SMB)", DesignFake.rightPath, DesignFake.right.take(4), focused = false, Modifier.weight(1f))
                        }
                    }
                    if (c.floatBar) {
                        FloatingActionButton(
                            onClick = {},
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                        ) { Icon(Icons.Filled.SwapVert, null) }
                    }
                }
                if (!tablet && dir == DesignDir.B) BottomNav(dir)
                else if (!tablet) ToolStrip(dir)
            }
        }
        if (dir == DesignDir.C && !tablet) {
            // Finder-like subtle status
            Text("2 项已选 · 700 MB", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                fontSize = 11.sp, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TopBar(dir: DesignDir, tablet: Boolean) {
    val c = chrome(dir)
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        c.frosted -> scheme.surfaceContainer.copy(alpha = 0.92f)
        dir == DesignDir.B -> scheme.surface
        else -> scheme.surfaceContainer
    }
    Row(
        Modifier.fillMaxWidth().background(bg).then(
            if (dir == DesignDir.A) Modifier.border(0.5.dp, scheme.outline.copy(alpha = 0.5f)) else Modifier
        ).padding(horizontal = 10.dp, vertical = if (c.dense) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!tablet) Icon(Icons.Filled.Menu, null, Modifier.size(22.dp), tint = scheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (dir == DesignDir.C) "双栏文件管理器" else "文件",
                fontWeight = if (dir == DesignDir.A) FontWeight.SemiBold else FontWeight.Bold,
                fontSize = c.titleSp.sp,
                color = scheme.onSurface,
            )
            if (dir == DesignDir.C) Text("本机  ↔  NAS", fontSize = 11.sp, color = scheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Filled.MoreVert, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
    }
}

/** Constraint: only the focused pane's path under the title — never per-pane bars. */
@Composable
private fun PathStrip(dir: DesignDir, path: String) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (dir == DesignDir.B) 12.dp else if (dir == DesignDir.C) 8.dp else 0.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (dir == DesignDir.A) 0.dp else 10.dp, vertical = if (dir == DesignDir.A) 0.dp else 6.dp)
            .then(if (dir != DesignDir.A) Modifier.clip(shape).background(scheme.surfaceContainerHigh) else Modifier.background(scheme.surfaceContainerLowest))
            .padding(horizontal = 12.dp, vertical = if (dir == DesignDir.A) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("本机", fontSize = 11.sp, color = scheme.primary, fontWeight = FontWeight.Medium)
        Text("  ›  ", fontSize = 11.sp, color = scheme.onSurfaceVariant)
        Text(path, fontSize = 12.sp, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    if (dir == DesignDir.A) HorizontalDivider(thickness = 0.5.dp, color = scheme.outline.copy(alpha = 0.5f))
}

@Composable
private fun PaneCard(dir: DesignDir, label: String, path: String, rows: List<DesignFake.Row>, focused: Boolean, modifier: Modifier) {
    val c = chrome(dir)
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(c.radius)
    val surfaceMod = when (dir) {
        DesignDir.A -> modifier.fillMaxHeight().background(if (focused) scheme.surface else scheme.surfaceContainerLowest)
        DesignDir.B -> modifier.fillMaxHeight().shadow(2.dp, shape).clip(shape).background(scheme.surfaceContainerLowest)
        DesignDir.C -> modifier.fillMaxHeight().shadow(1.dp, shape).clip(shape).background(scheme.surfaceContainer).border(0.5.dp, scheme.outline.copy(alpha = 0.35f), shape)
    }
    Column(surfaceMod) {
        if (dir != DesignDir.A) {
            // Label only — path lives in the global PathStrip (focused pane).
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (focused) scheme.primary else scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (focused) Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.primary))
            }
            if (dir == DesignDir.C) HorizontalDivider(thickness = 0.5.dp, color = scheme.outline.copy(alpha = 0.3f))
        } else {
            Row(Modifier.fillMaxWidth().background(if (focused) scheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (focused) scheme.primary else scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${rows.size}", fontSize = 10.sp, color = scheme.onSurfaceVariant)
            }
            HorizontalDivider(thickness = 0.5.dp, color = scheme.outline.copy(alpha = 0.35f))
        }
        rows.forEach { r -> FileRow(dir, r) }
    }
}

@Composable
private fun FileRow(dir: DesignDir, r: DesignFake.Row) {
    val c = chrome(dir)
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        r.selected && dir == DesignDir.A -> scheme.primary.copy(alpha = 0.12f)
        r.selected && dir == DesignDir.B -> scheme.secondaryContainer
        r.selected && dir == DesignDir.C -> scheme.primary.copy(alpha = 0.15f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = c.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (r.folder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            null, Modifier.size(if (c.dense) 18.dp else 26.dp),
            tint = if (r.folder) (if (dir == DesignDir.C) Color(0xFF5AC8FA) else scheme.primary) else scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(if (c.dense) 8.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = if (c.dense) 13.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
            if (!c.dense || dir == DesignDir.A) Text(r.meta, fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
    if (dir == DesignDir.A) HorizontalDivider(thickness = 0.5.dp, color = scheme.outline.copy(alpha = 0.25f), modifier = Modifier.padding(start = 36.dp))
}

@Composable
private fun ToolStrip(dir: DesignDir) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainer).padding(vertical = if (dir == DesignDir.A) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf("复制", "移动", "删除", "更多").forEach {
            Text(it, fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BottomNav(dir: DesignDir) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().shadow(6.dp).background(scheme.surfaceContainer).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(Icons.Filled.Folder to "文件", Icons.Filled.SwapVert to "传输", Icons.Filled.Apps to "应用", Icons.Filled.Cast to "设备").forEach { (ic, l) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(ic, null, Modifier.size(22.dp), tint = if (l == "文件") scheme.primary else scheme.onSurfaceVariant)
                Text(l, fontSize = 10.sp, color = if (l == "文件") scheme.primary else scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Rail(dir: DesignDir) {
    val scheme = MaterialTheme.colorScheme
    val items = listOf(
        Icons.Filled.Folder to "文件", Icons.Filled.SwapVert to "传输", Icons.Filled.CloudUpload to "服务",
        Icons.Filled.Radar to "局域网", Icons.Filled.Apps to "应用", Icons.Filled.DeleteSweep to "回收站",
        Icons.Filled.Terminal to "终端", Icons.Filled.Cast to "设备",
    )
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(if (dir == DesignDir.C) scheme.surfaceContainer else scheme.surfaceContainerHigh)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (dir == DesignDir.A) 4.dp else 10.dp),
    ) {
        items.forEach { (ic, l) ->
            val on = l == "文件"
            Column(
                Modifier.width(64.dp).clip(RoundedCornerShape(if (dir == DesignDir.B) 16.dp else 8.dp))
                    .background(if (on) scheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(ic, null, Modifier.size(22.dp), tint = if (on) scheme.primary else scheme.onSurfaceVariant)
                Text(l, fontSize = 9.sp, color = if (on) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SidePanel(dir: DesignDir, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.background(scheme.surfaceContainer).padding(12.dp)) {
        Text(if (dir == DesignDir.C) "个人收藏" else "位置", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = scheme.onSurface)
        Spacer(Modifier.height(8.dp))
        DesignFake.drawer.take(8).forEach {
            Text(it, Modifier.padding(vertical = if (dir == DesignDir.A) 4.dp else 8.dp), fontSize = 13.sp, color = scheme.onSurface)
        }
    }
}

// ---- Drawer ---------------------------------------------------------------

@Composable
fun ConceptDrawer(dir: DesignDir) {
    val scheme = MaterialTheme.colorScheme
    val c = chrome(dir)
    Row(Modifier.fillMaxSize().background(scheme.background.copy(alpha = 0.35f))) {
        Column(
            Modifier.widthIn(max = 300.dp).fillMaxHeight().fillMaxWidth(0.82f)
                .then(if (dir == DesignDir.B) Modifier.clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) else Modifier)
                .background(scheme.surface)
                .padding(vertical = 12.dp),
        ) {
            Text("双栏文件管理器", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text("v0.7.0-test · ${dir.titleZh}", fontSize = 11.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            if (dir == DesignDir.A) HorizontalDivider(color = scheme.outline.copy(alpha = 0.4f))
            DesignFake.drawer.forEachIndexed { i, label ->
                val icons = listOf(Icons.Filled.Folder, Icons.Filled.Settings, Icons.Filled.Apps, Icons.Filled.PhoneAndroid,
                    Icons.Filled.Folder, Icons.Filled.Radar, Icons.Filled.CloudUpload, Icons.Filled.DeleteSweep,
                    Icons.Filled.Apps, Icons.Filled.Cast, Icons.Filled.Terminal, Icons.Filled.Settings)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = c.rowPadV + 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icons[i % icons.size], null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Text(label, fontSize = 14.sp, color = scheme.onSurface)
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

// ---- Devices / ADB tools --------------------------------------------------

@Composable
fun ConceptDevices(dir: DesignDir, tablet: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val c = chrome(dir)
    Row(Modifier.fillMaxSize().background(scheme.background)) {
        if (tablet) Rail(dir)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = scheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text("设备 / 投屏", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text("配对", color = scheme.primary, fontWeight = FontWeight.Medium)
            }
            if (dir == DesignDir.B) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DesignFake.devices.forEach { (n, m) -> DeviceCardB(n, m) }
                }
            } else {
                DesignFake.devices.forEach { (n, m) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = c.rowPadV + 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(28.dp), tint = scheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n, fontWeight = FontWeight.SemiBold)
                            Text(m, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        }
                        Text("投屏", color = scheme.primary, fontSize = 13.sp)
                    }
                    if (dir == DesignDir.A) HorizontalDivider(Modifier.padding(start = 54.dp), thickness = 0.5.dp, color = scheme.outline.copy(alpha = 0.35f))
                }
                Spacer(Modifier.height(12.dp))
                Text("工具预览", Modifier.padding(horizontal = 14.dp), fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("截图", "录屏", "重启", "应用").forEach {
                        Surface(shape = RoundedCornerShape(c.radius), color = scheme.surfaceContainerHigh) {
                            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCardB(name: String, meta: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLowest, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(meta, fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("投屏控制", "浏览文件", "工具").forEach {
                    Surface(shape = RoundedCornerShape(20.dp), color = scheme.secondaryContainer) {
                        Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, color = scheme.onSurface)
                    }
                }
            }
        }
    }
}

// ---- Terminal -------------------------------------------------------------

@Composable
fun ConceptTerminal(dir: DesignDir, dark: Boolean) {
    // Always dark-ish terminal chrome; page chrome follows direction.
    val termBg = if (dark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val termFg = if (dark) Color(0xFFD4D4D4) else Color(0xFF1E1E1E)
    val green = Color(0xFF34C759)
    val blue = Color(0xFF0A84FF)
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.background)) {
        Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = scheme.onSurface)
            Spacer(Modifier.width(10.dp))
            Text("终端", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f), color = scheme.onSurface)
            Surface(shape = RoundedCornerShape(8.dp), color = scheme.primary.copy(alpha = 0.12f)) {
                Text("目标：Xiaomi", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, color = scheme.primary)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(if (dir == DesignDir.B) 10.dp else 0.dp)
            .then(if (dir != DesignDir.A) Modifier.clip(RoundedCornerShape(if (dir == DesignDir.B) 20.dp else 10.dp)) else Modifier)
            .background(termBg).padding(12.dp)) {
            Column {
                Row {
                    Text("shell", color = green, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("@Xiaomi-23049RAD8C ", color = green, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("sdcard", color = blue, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(" % ", color = termFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("ls", color = termFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Text("DCIM  Download  Movies  Music  Pictures", color = termFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Row {
                    Text("shell", color = green, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("@Xiaomi-23049RAD8C ", color = green, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("sdcard", color = blue, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(" % ", color = termFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("█", color = termFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Esc", "Tab", "Ctrl", "Alt", "↑", "↓", "←", "→").forEach {
                Surface(shape = RoundedCornerShape(6.dp), color = scheme.surfaceContainer) {
                    Text(it, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
