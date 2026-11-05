package com.ghostpanter.files.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.ShellResult
import com.ghostpanter.files.core.apk.SystemApp
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Root shell on this device for [SystemApp], or null when su is not granted (may show the Magisk prompt). */
suspend fun localRootExec(): SystemApp.Exec? = withContext(Dispatchers.IO) {
    if (!RootRunner.check()) null
    else SystemApp.Exec { s, ms ->
        val r = com.ghostpanter.files.core.Proc.collect(RootRunner.start(s), ms)
        ShellResult(r.code, r.stdout, String(r.err, Charsets.UTF_8))
    }
}

/** Status of a Ghost Files system-app conversion, only when Root is already granted (no prompt). */
suspend fun localSysAppStatus(pkg: String): SystemApp.Status? = withContext(Dispatchers.IO) {
    if (!RootRunner.granted()) null
    else runCatching { localRootExec()?.let { SystemApp.status(it, pkg) } }.getOrNull()
}

/** 「转为系统应用」/「还原为用户应用」: confirm → run → result with reboot prompt. Shared by local and ADB app lists. */
@Stable
class SysAppFlow(
    private val scope: CoroutineScope,
    private val execProvider: suspend () -> SystemApp.Exec?,
    private val onChanged: () -> Unit,
) {
    sealed interface Step
    data class Confirm(val pkg: String, val label: String) : Step
    data class RevertConfirm(val pkg: String, val label: String) : Step
    data class Running(val text: String) : Step
    data class Done(val title: String, val text: String, val ok: Boolean) : Step

    var step by mutableStateOf<Step?>(null)
    private var exec: SystemApp.Exec? = null

    fun askConvert(pkg: String, label: String) { step = Confirm(pkg, label) }
    fun askRevert(pkg: String, label: String) { step = RevertConfirm(pkg, label) }

    private suspend fun execOrFail(): SystemApp.Exec =
        exec ?: (execProvider() ?: throw SystemApp.Failure(SystemApp.NO_ROOT)).also { exec = it }

    fun convert(pkg: String, label: String, kind: SystemApp.Kind) {
        step = Running(t("正在把「$label」转为系统应用…（需 Root）", "Converting $label to a system app… (Root)"))
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SystemApp.convert(execOrFail(), pkg, label, kind) } }
            step = r.fold(
                { res ->
                    val how = when (res.method) {
                        SystemApp.Method.MODULE -> t(
                            "方式：${res.manager.ifEmpty { "Root" }} 模块（systemless，不改动系统分区）\n模块目录：${SystemApp.moduleDir(pkg)}\n挂载为：/system/${res.kind.dir}/${SystemApp.dirName(pkg)}/",
                            "Method: ${res.manager.ifEmpty { "Root" }} module (systemless)\nModule: ${SystemApp.moduleDir(pkg)}\nMounted as /system/${res.kind.dir}/${SystemApp.dirName(pkg)}/",
                        )
                        SystemApp.Method.DIRECT -> t("方式：直接写入系统分区\n路径：${res.path}", "Method: direct write to /system\nPath: ${res.path}")
                    }
                    val ksu = if (res.manager == "KernelSU") t("\n\nKernelSU：请确认模块挂载可用（新版 KernelSU 需安装挂载用的元模块），否则重启后不生效。", "\n\nKernelSU: make sure module mounting is available (newer KernelSU needs a mount metamodule).") else ""
                    Done(t("已转为系统应用（重启后生效）", "Converted (reboot to apply)"),
                        how + t("\n\n应用数据保留；用户区的安装副本会作为该系统应用的「更新」保留。重启后在应用列表中显示为系统应用。", "\n\nApp data is kept; the /data copy stays as an update of the system app.") + ksu, true)
                },
                { e -> Done(t("转换失败", "Conversion failed"), e.message ?: e.toString(), false) },
            )
            onChanged()
        }
    }

    fun revert(pkg: String, label: String) {
        step = Running(t("正在把「$label」还原为用户应用…", "Reverting $label to a user app…"))
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SystemApp.revert(execOrFail(), pkg) } }
            step = r.fold(
                { res ->
                    Done(t("已还原为用户应用（重启后生效）", "Reverted (reboot to apply)"),
                        when (res.method) {
                            SystemApp.Method.MODULE -> t("已标记删除模块 ${SystemApp.moduleId(pkg)}，重启后由 Root 管理器移除。", "Module ${SystemApp.moduleId(pkg)} marked for removal on reboot.")
                            SystemApp.Method.DIRECT -> t("已从 ${res.path} 删除系统副本。", "Removed the system copy at ${res.path}.")
                        } + t("\n用户区副本与数据保留。", "\nThe /data copy and app data are kept."), true)
                },
                { e -> Done(t("还原失败", "Revert failed"), e.message ?: e.toString(), false) },
            )
            onChanged()
        }
    }

    fun reboot() {
        val e = exec ?: return
        step = null
        scope.launch(Dispatchers.IO) { runCatching { e.run(SystemApp.REBOOT, 15_000) } }
    }
}

@Composable
fun rememberSysAppFlow(execProvider: suspend () -> SystemApp.Exec?, onChanged: () -> Unit = {}): SysAppFlow {
    val scope = rememberCoroutineScope()
    val p by rememberUpdatedState(execProvider)
    val c by rememberUpdatedState(onChanged)
    return remember { SysAppFlow(scope, { p() }, { c() }) }
}

@Composable
fun SysAppDialogs(flow: SysAppFlow) {
    when (val s = flow.step) {
        null -> {}
        is SysAppFlow.Confirm -> {
            var kind by remember(s) { mutableStateOf(SystemApp.Kind.APP) }
            AlertDialog(
                onDismissRequest = { flow.step = null },
                title = { Text(t("转为系统应用", "Convert to system app")) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("「${s.label}」 ${s.pkg}", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        for (k in SystemApp.Kind.entries) {
                            Row(Modifier.fillMaxWidth().clickable { kind = k }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(kind == k, { kind = k })
                                Column {
                                    Text(if (k == SystemApp.Kind.APP) "/system/app" else "/system/priv-app", fontSize = 14.sp)
                                    Text(
                                        if (k == SystemApp.Kind.APP) t("普通系统应用（推荐）", "Plain system app (recommended)")
                                        else t("特权应用：需要签名级/特权权限时选，会同时写入特权权限白名单", "Privileged: for privileged permissions; writes a privapp whitelist"),
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t(
                            "需要 Root。装有 Magisk / KernelSU / APatch 时以模块方式写入（不改动系统分区，A/B 动态分区可用）；否则尝试直接挂载 /system 读写。",
                            "Root required. With Magisk / KernelSU / APatch a systemless module is used (works on A/B dynamic partitions); otherwise /system is remounted rw.",
                        ), fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(t(
                            "风险：\n• 可能导致卡开机（bootloop），特权应用风险更高；卡开机时进 Recovery 或安全模式删除 /data/adb/modules/${SystemApp.moduleId(s.pkg)}\n• OTA 系统更新前请先「还原为用户应用」；直接改 /system 的方式会被 OTA 覆盖或导致增量 OTA 失败\n• 部分应用作为系统应用运行可能异常；不建议对银行/支付类应用操作",
                            "Risks:\n• May bootloop (higher for priv-app); remove /data/adb/modules/${SystemApp.moduleId(s.pkg)} from recovery/safe mode\n• Revert before OTA; direct /system edits break incremental OTA\n• Some apps misbehave as system apps",
                        ), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = { TextButton({ flow.convert(s.pkg, s.label, kind) }) { Text(t("转为系统应用", "Convert"), color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton({ flow.step = null }) { Text(t("取消", "Cancel")) } },
            )
        }
        is SysAppFlow.RevertConfirm -> AlertDialog(
            onDismissRequest = { flow.step = null },
            title = { Text(t("还原为用户应用", "Revert to user app")) },
            text = {
                Text(t(
                    "将移除「${s.label}」的系统副本（模块或 /system 中的文件），重启后恢复为普通用户应用，数据保留。若用户区已无副本，会先从系统副本重新安装一份。",
                    "Removes the system copy of ${s.label}; after reboot it is a normal user app with data kept. If no /data copy exists it is reinstalled first.",
                ), fontSize = 13.sp)
            },
            confirmButton = { TextButton({ flow.revert(s.pkg, s.label) }) { Text(t("还原", "Revert"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ flow.step = null }) { Text(t("取消", "Cancel")) } },
        )
        is SysAppFlow.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(t("请稍候", "Please wait")) },
            text = { Column { Text(s.text, fontSize = 13.sp); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } },
            confirmButton = {},
        )
        is SysAppFlow.Done -> AlertDialog(
            onDismissRequest = { flow.step = null },
            title = { Text(s.title) },
            text = { SelectionContainer { Text(s.text, fontSize = 13.sp, color = if (s.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) } },
            confirmButton = {
                if (s.ok) TextButton({ flow.reboot() }) { Text(t("立即重启", "Reboot now")) }
                else TextButton({ flow.step = null }) { Text(t("关闭", "Close")) }
            },
            dismissButton = { if (s.ok) TextButton({ flow.step = null }) { Text(t("稍后重启", "Later")) } },
        )
    }
}
