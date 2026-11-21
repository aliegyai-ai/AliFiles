package com.ali.files.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Shows itself when a newer version is on GitHub: what's new → download with progress → install.
 * [manual] = opened from settings, so «up to date» and errors are shown too.
 */
@Composable
fun UpdateDialog(manual: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    val current = Updater.installedVersion(ctx).second

    val close = {
        (state as? UpdateState.Available)?.let { Updater.dismissedCode = it.info.versionCode }
        if (state is UpdateState.UpToDate || state is UpdateState.Failed) Updater.reset()
        onClose()
    }

    when (val s = state) {
        is UpdateState.Available -> {
            if (!manual && Updater.dismissedCode == s.info.versionCode) return
            AlertDialog(
                onDismissRequest = close,
                icon = { Icon(Icons.Filled.SystemUpdate, null) },
                title = { Text("نسخة جديدة ${s.info.versionName} متاحة") },
                text = {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("النسخة اللي عندك: $current", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.info.notes.isNotBlank()) {
                            Text("الجديد:", fontWeight = FontWeight.Bold)
                            Text(s.info.notes)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { scope.launch { Updater.download(ctx.applicationContext, s.info) } }) { Text("حدّث دلوقتي", fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = close) { Text("بعدين") } },
            )
        }

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("بينزّل نسخة ${s.info.versionName}…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
                    Text("${(s.fraction * 100).toInt()}%", fontSize = 13.sp)
                }
            },
            confirmButton = {},
        )

        is UpdateState.Ready -> {
            val allowed = Updater.canInstall(ctx)
            AlertDialog(
                onDismissRequest = onClose,
                icon = { Icon(Icons.Filled.SystemUpdate, null) },
                title = { Text("نسخة ${s.info.versionName} جاهزة") },
                text = {
                    Text(
                        if (allowed) "دوس «ثبّت»، وبعدين «تحديث» في الشاشة اللي هتظهر. التطبيق هيقفل ويفتح بالنسخة الجديدة، وملفاتك والخزنة زي ما هي."
                        else "أول مرة بس: أندرويد محتاج تسمح للتطبيق يثبّت تحديثات. دوس «اسمح»، فعّل الاختيار، وارجع للتطبيق ودوس «ثبّت».",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { if (Updater.canInstall(ctx)) Updater.install(ctx, s.apk) else Updater.openInstallPermission(ctx) }) {
                        Text(if (allowed) "ثبّت" else "اسمح", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("بعدين") } },
            )
        }

        is UpdateState.Checking -> if (manual) AlertDialog(
            onDismissRequest = close, title = { Text("بيدوّر على تحديث…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {},
        )

        is UpdateState.UpToDate -> if (manual) AlertDialog(
            onDismissRequest = close, title = { Text("عندك آخر نسخة ✅") }, text = { Text("النسخة $current") },
            confirmButton = { TextButton(onClick = close) { Text("تمام") } },
        )

        is UpdateState.Failed -> if (manual) AlertDialog(
            onDismissRequest = close, title = { Text("التحديث معرفش يكمّل") }, text = { Text(s.message) },
            confirmButton = { TextButton(onClick = close) { Text("تمام") } },
        )

        UpdateState.Idle -> {}
    }
}
