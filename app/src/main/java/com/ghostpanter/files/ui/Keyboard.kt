package com.ghostpanter.files.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.transfer.TransferOp
import com.ghostpanter.files.t

/**
 * Hardware keyboard shortcuts of the dual-pane frame (tablet + keyboard / mouse, also phones with a
 * keyboard). Returns true when the key was handled.
 *
 * Tab switch pane · ↑↓ PgUp PgDn Home End move cursor (Shift extends selection) · Enter open ·
 * Backspace / Alt+↑ parent · Alt+←/→ back/forward · Space toggle selection · Ctrl+A/C/X/V ·
 * Delete · F2 rename · Ctrl+F filter · F5 / Ctrl+R refresh · Esc clear · Ctrl+N new folder ·
 * Ctrl+T / Ctrl+W tab · Ctrl+Tab next tab · Ctrl+L go to path · Alt+Enter properties · Menu / Shift+F10 context menu.
 */
fun handleShortcut(vm: MainViewModel, ev: KeyEvent): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    if (vm.screen != Screen.Main || vm.dialog != null || vm.menu != null || vm.pendingTransfer != null || vm.busy != null || vm.pathJump != null) return false
    val pane = vm.pane(vm.activePane)
    val tab = pane.tab
    val ctrl = ev.isCtrlPressed || ev.isMetaPressed
    val shift = ev.isShiftPressed
    val alt = ev.isAltPressed

    if (PaneUi.textFocused) {
        if (ev.key == Key.Escape) {
            vm.panes.forEach { it.tab.filter = "" }
            PaneUi.filterOpen = false
            PaneUi.textFocused = false
            PaneUi.rootFocusRequest++
            return true
        }
        if (ev.key == Key.Enter || ev.key == Key.DirectionDown) { PaneUi.textFocused = false; PaneUi.rootFocusRequest++; return true }
        return false
    }

    val visible = computeVisible(vm.prefs, tab)
    fun cursor(): FileEntry? = tab.highlight?.let { h -> visible.firstOrNull { it.path == h } }
    fun targets(): List<FileEntry> = tab.selectedEntries().ifEmpty { listOfNotNull(cursor()) }

    when {
        ev.key == Key.Tab && ctrl -> {
            if (pane.tabs.size > 1) vm.selectTab(pane, (pane.activeTab + if (shift) pane.tabs.size - 1 else 1) % pane.tabs.size)
        }
        ev.key == Key.Tab -> vm.activePane = 1 - vm.activePane
        ev.key == Key.DirectionLeft && alt -> vm.back(tab)
        ev.key == Key.DirectionRight && alt -> { if (!vm.forward(tab)) vm.toast(t("没有可前进的记录", "Nothing to go forward to")) }
        ev.key == Key.DirectionUp && alt -> vm.up(tab)
        ev.key == Key.Backspace -> vm.up(tab)
        ev.key == Key.DirectionUp -> moveCursor(vm, tab, visible, -gridStep(vm, tab), shift)
        ev.key == Key.DirectionDown -> moveCursor(vm, tab, visible, gridStep(vm, tab), shift)
        ev.key == Key.DirectionLeft && vm.prefs.gridView -> moveCursor(vm, tab, visible, -1, shift)
        ev.key == Key.DirectionRight && vm.prefs.gridView -> moveCursor(vm, tab, visible, 1, shift)
        ev.key == Key.PageUp -> moveCursor(vm, tab, visible, -10, shift)
        ev.key == Key.PageDown -> moveCursor(vm, tab, visible, 10, shift)
        ev.key == Key.MoveHome -> moveCursor(vm, tab, visible, -visible.size, shift)
        ev.key == Key.MoveEnd -> moveCursor(vm, tab, visible, visible.size, shift)
        (ev.key == Key.Enter || ev.key == Key.NumPadEnter) && alt -> cursor()?.let { vm.dialog = DialogState.Properties(pane.index, it) }
        ev.key == Key.Enter || ev.key == Key.NumPadEnter -> cursor()?.let { vm.openEntry(tab, it) }
        ev.key == Key.Spacebar -> cursor()?.let { tab.multiSelect = true; vm.toggle(tab, it) }
        ev.key == Key.Escape -> when {
            tab.selecting -> vm.clearSelection(tab)
            PaneUi.filterOpen || tab.filter.isNotEmpty() -> { vm.panes.forEach { it.tab.filter = "" }; PaneUi.filterOpen = false }
            else -> return false
        }
        ctrl && ev.key == Key.A -> vm.selectAll(tab, visible)
        ctrl && (ev.key == Key.C || ev.key == Key.X) -> {
            val items = targets()
            if (items.isEmpty()) vm.toast(t("未选择文件", "Nothing selected"))
            else { tab.selected = items.map { it.path }.toSet(); vm.setClip(tab, if (ev.key == Key.C) TransferOp.COPY else TransferOp.MOVE) }
        }
        ctrl && ev.key == Key.V -> if (vm.clip == null) vm.toast(t("剪贴板为空", "Clipboard is empty")) else vm.paste(tab)
        ev.key == Key.Delete -> targets().takeIf { it.isNotEmpty() }?.let { vm.dialog = DialogState.Delete(pane.index, it) }
        ev.key == Key.F2 -> targets().singleOrNull()?.let { vm.dialog = DialogState.Rename(pane.index, it) }
        ctrl && ev.key == Key.F -> { PaneUi.filterOpen = true; PaneUi.filterFocusPending = true }
        ev.key == Key.F5 || (ctrl && ev.key == Key.R) -> vm.refresh(tab)
        ctrl && ev.key == Key.N -> vm.dialog = DialogState.Create(pane.index, !shift)
        ctrl && ev.key == Key.T -> vm.newTab(pane)
        ctrl && ev.key == Key.W -> if (pane.tabs.size > 1) vm.closeTab(pane, pane.activeTab)
        ctrl && ev.key == Key.L -> vm.pathJump = pane
        ev.key == Key.Menu || (shift && ev.key == Key.F10) -> {
            val items = targets()
            if (items.isEmpty()) vm.onPaneContextMenu(pane) else vm.menu = vm.buildEntryMenu(pane, items)
        }
        else -> return false
    }
    return true
}

/** Grid: ↑/↓ jump a full row (column count read from the visible layout). */
private fun gridStep(vm: MainViewModel, tab: TabState): Int {
    if (!vm.prefs.gridView) return 1
    val vis = tab.gridState.layoutInfo.visibleItemsInfo
    if (vis.isEmpty()) return 1
    val row = vis.last().row
    return vis.count { it.row == row && it.key != ".." }.coerceAtLeast(vis.count { it.row == vis.first().row }).coerceAtLeast(1)
}

/** Moves the keyboard cursor ([TabState.highlight]); with [extend] (Shift) also selects the passed rows. */
fun moveCursor(vm: MainViewModel, tab: TabState, visible: List<FileEntry>, delta: Int, extend: Boolean) {
    if (visible.isEmpty()) return
    val cur = visible.indexOfFirst { it.path == tab.highlight }
    val next = if (cur < 0) (if (delta < 0) visible.size - 1 else 0) else (cur + delta).coerceIn(0, visible.size - 1)
    if (extend) {
        val from = if (cur < 0) next else cur
        val range = if (from <= next) from..next else next..from
        tab.multiSelect = true
        tab.selected = tab.selected + range.map { visible[it].path }
    }
    tab.highlight = visible[next].path
    tab.scrollRequest++
}
