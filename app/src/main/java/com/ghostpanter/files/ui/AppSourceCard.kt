package com.ghostpanter.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.t

/** Install source of an installed app, as shown in its detail dialog. Stateless for Paparazzi. */
data class AppSourceUi(
    val installer: String,
    val installerPkg: String?,
    val initiating: String? = null,
    val updateOwner: String? = null,
    val isPlay: Boolean = false,
    /** Root / Shizuku and Play installed: offer `pm set-installer … com.android.vending`. */
    val canSetPlay: Boolean = false,
    val canOpenPlay: Boolean = false,
    val busy: Boolean = false,
    val note: Pair<Boolean, String>? = null,
    /** After set-installer was refused: offer the reinstall-in-place fallback. */
    val offerReinstall: Boolean = false,
    val loading: Boolean = false,
)

@Composable
fun AppSourceCard(s: AppSourceUi, onSetPlay: () -> Unit = {}, onOpenPlay: () -> Unit = {}, onReinstall: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant.copy(alpha = 0.55f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shop, null, tint = if (s.isPlay) cs.primary else cs.outline, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("安装来源", "Install source"), fontSize = 13.sp, color = cs.outline)
            Spacer(Modifier.width(8.dp))
            if (s.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else Text(s.installer, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        s.installerPkg?.takeIf { it != s.installer }?.let { Text(it, fontSize = 11.sp, color = cs.outline, modifier = Modifier.padding(start = 24.dp)) }
        s.initiating?.let { Text(t("发起安装：", "Initiated by: ") + it, fontSize = 12.sp, color = cs.outline, modifier = Modifier.padding(start = 24.dp, top = 2.dp)) }
        s.updateOwner?.let { Text(t("更新所有者（Android 14+）：", "Update owner (Android 14+): ") + it, fontSize = 12.sp, color = cs.outline, modifier = Modifier.padding(start = 24.dp, top = 2.dp)) }
        s.note?.let { (ok, msg) ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Icon(if (ok) Icons.Rounded.Verified else Icons.Rounded.WarningAmber, null, tint = if (ok) cs.primary else cs.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(msg, fontSize = 12.sp, color = if (ok) cs.onSurface else cs.error)
            }
        }
        if (s.canSetPlay || s.canOpenPlay || s.offerReinstall) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (s.canOpenPlay) TextButton(onOpenPlay) { Text(t("在 Play 中打开", "Open in Play")) }
                if (s.offerReinstall) FilledTonalButton(onReinstall, enabled = !s.busy) { Text(t("覆盖安装为 Play 来源", "Reinstall as Play")) }
                else if (s.canSetPlay) FilledTonalButton(onSetPlay, enabled = !s.busy) { Text(t("改为 Google Play 来源", "Set source to Google Play")) }
            }
        }
        if (s.canSetPlay || s.offerReinstall) Text(
            (if (s.offerReinstall) t("覆盖安装用本机已装的同一份 APK（签名相同），数据保留。", "Reinstalls the same installed APKs (same signature); data is kept. ")
            else t("只改记录，不重装、不丢数据。", "Only changes the record; no reinstall, data stays. ")) +
                t("部分应用还会在服务器核对账号是否拥有该应用，改来源不保证一定通过。", "Some apps also check account ownership on their server; not a guarantee."),
            fontSize = 11.sp, color = cs.outline, modifier = Modifier.padding(top = 2.dp))
    }
}
