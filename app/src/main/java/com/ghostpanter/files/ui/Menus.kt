package com.ghostpanter.files.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.ops.Archives
import com.ghostpanter.files.core.transfer.TransferOp
import com.ghostpanter.files.t

/** Short description of the other pane's directory, used as the target hint ("→ 内部存储 /Download"). */
fun MainViewModel.otherTarget(p: PaneState): String {
    val o = other(p).tab
    return fsm.label(o.fsId) + " " + o.path
}

/** MT-style long-press menu. Copy/move targets default to the opposite pane. */
fun MainViewModel.buildEntryMenu(pane: PaneState, targets: List<FileEntry>): ActionMenu {
    val tab = pane.tab
    val single = targets.singleOrNull()
    val dest = "→ " + otherTarget(pane)
    fun done() { menu = null }
    val a = ArrayList<MenuAction>()
    fun add(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, hint: String? = null, run: () -> Unit) =
        a.add(MenuAction(label, icon, hint) { done(); run() })

    if (single != null && single.isDirectory) add(t("打开", "Open"), Icons.Filled.FolderOpen) { navigate(tab, tab.fsId, single.path) }
    if (single != null && single.isDirectory) add(t("在另一窗口打开", "Open in other pane"), Icons.Filled.Splitscreen) {
        navigate(other(pane).tab, tab.fsId, single.path)
    }
    add(t("复制到对面", "Copy to other pane"), Icons.Filled.FileCopy, dest) { transferToOther(pane, TransferOp.COPY, targets) }
    add(t("移动到对面", "Move to other pane"), Icons.AutoMirrored.Filled.DriveFileMove, dest) { transferToOther(pane, TransferOp.MOVE, targets) }
    if (single != null) add(t("重命名", "Rename"), Icons.Filled.DriveFileRenameOutline) { dialog = DialogState.Rename(pane.index, single) }
    add(t("删除", "Delete"), Icons.Filled.Delete) { dialog = DialogState.Delete(pane.index, targets) }
    if (single != null && !single.isDirectory) {
        add(t("打开方式…", "Open with…"), Icons.AutoMirrored.Filled.OpenInNew) { openEntry(tab, single, chooser = true) }
        add(t("文本编辑器打开", "Open as text"), Icons.Filled.EditNote) { openEntry(tab, single, asText = true) }
    }
    add(t("压缩为 ZIP", "Compress (zip)"), Icons.Filled.FolderZip) { dialog = DialogState.Compress(pane.index, targets) }
    if (single != null && Archives.isArchive(single.name)) add(t("解压…", "Extract…"), Icons.Filled.Unarchive) { pendingExtract = tab to single }
    if (targets.any { !it.isDirectory }) add(t("分享", "Share"), Icons.Filled.Share) { share(tab, targets) }
    add(t("复制（剪贴板）", "Copy (clipboard)"), Icons.Filled.ContentCopy) { tab.selected = targets.map { it.path }.toSet(); setClip(tab, TransferOp.COPY) }
    add(t("剪切（剪贴板）", "Cut (clipboard)"), Icons.Filled.ContentCut) { tab.selected = targets.map { it.path }.toSet(); setClip(tab, TransferOp.MOVE) }
    if (single != null) {
        if (single.isDirectory) add(t("添加书签", "Bookmark"), Icons.Filled.Bookmark) { addBookmark(tab, single) }
        if (hasCap(tab, Capability.CHMOD)) add(t("权限 / 所有者", "Permissions"), Icons.Filled.Lock) { dialog = DialogState.Chmod(pane.index, single) }
        add(t("复制路径", "Copy path"), Icons.Filled.Link) { copyText(single.path) }
        add(t("属性", "Properties"), Icons.Filled.Info) { dialog = DialogState.Properties(pane.index, single) }
    }
    if (!tab.selecting) add(t("多选", "Multi-select"), Icons.Filled.Checklist) { tab.multiSelect = true; tab.selected = targets.map { it.path }.toSet() }
    val title = single?.name ?: t("已选 ${targets.size} 项", "${targets.size} items")
    val sub = single?.let { if (it.isDirectory) t("文件夹", "Folder") else formatSize(it.size) + "  " + formatTime(it.lastModified) }
    return ActionMenu(title, sub, a)
}

/** The "更多" menu of the bottom toolbar (no selection). */
fun MainViewModel.buildMoreMenu(pane: PaneState): ActionMenu {
    val tab = pane.tab
    fun done() { menu = null }
    val a = ArrayList<MenuAction>()
    fun add(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, hint: String? = null, run: () -> Unit) =
        a.add(MenuAction(label, icon, hint) { done(); run() })
    clip?.let { c ->
        add(t("粘贴 ${c.entries.size} 项", "Paste ${c.entries.size}"), Icons.Filled.ContentPaste, if (c.op == TransferOp.MOVE) t("剪切", "cut") else t("复制", "copy")) { paste(tab) }
        add(t("清空剪贴板", "Clear clipboard"), Icons.Filled.Clear) { clip = null }
    }
    add(t("交换窗口", "Swap panes"), Icons.Filled.SwapHoriz) { swapPanes() }
    add(t("跳转路径…", "Go to path…"), Icons.Filled.Directions) { pathJump = pane }
    add(t("搜索", "Search"), Icons.Filled.Search) { screen = Screen.Search(tab.fsId, tab.path) }
    add(t("排序方式", "Sort"), Icons.AutoMirrored.Filled.Sort) { dialog = DialogState.Sort }
    add(if (g.settings.showHidden) t("不显示隐藏文件", "Hide hidden files") else t("显示隐藏文件", "Show hidden files"),
        if (g.settings.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility) { g.settings.showHidden = !g.settings.showHidden }
    add(t("新建标签页", "New tab"), Icons.Filled.Tab) { newTab(pane) }
    add(t("添加书签", "Bookmark folder"), Icons.Filled.Bookmark) { addBookmark(tab) }
    add(t("复制当前路径", "Copy current path"), Icons.Filled.Link) { copyText(tab.path) }
    add(t("扫描局域网", "Scan LAN"), Icons.Filled.Radar) { screen = Screen.LanScan }
    add(t("传输队列", "Transfers"), Icons.Filled.SwapVert) { screen = Screen.Transfers }
    add(t("目录属性", "Folder properties"), Icons.Filled.Info) {
        dialog = DialogState.Properties(pane.index, FileEntry(tab.path, tab.title, true, 0, 0))
    }
    return ActionMenu(t("更多", "More"), fsm.label(tab.fsId) + "  " + tab.path, a)
}

/** The "更多" menu while multi-selecting. */
fun MainViewModel.buildSelectionMenu(pane: PaneState): ActionMenu {
    val tab = pane.tab
    val sel = tab.selectedEntries()
    return buildEntryMenu(pane, sel).copy(title = t("已选 ${sel.size} 项", "${sel.size} selected"))
}

fun MainViewModel.copyText(text: String) {
    val cm = getApplication<android.app.Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("path", text))
    toast(t("已复制: $text", "Copied: $text"))
}
