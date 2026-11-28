package com.ali.files.transcribe

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.library.LibFile
import com.ali.files.player.formatTime
import com.ali.files.util.AppSettings

/** Cards for «write what's said» jobs (progress, cancel). */
@Composable
fun TranscribeJobs() {
    val all by Transcriber.jobs.collectAsState()
    LaunchedEffect(all) { all.filter { it.done && it.error == null }.forEach { Transcriber.dismiss(it.id) } }
    val jobs = all.filter { !it.done || it.error != null }
    if (jobs.isEmpty()) return
    val gold = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        jobs.forEach { j ->
            Surface(shape = RoundedCornerShape(14.dp), color = gold.copy(alpha = 0.10f)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Subtitles, null, tint = gold)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${j.title} • كتابة الكلام", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(j.error ?: j.state, fontSize = 12.sp, color = if (j.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { if (j.done) Transcriber.dismiss(j.id) else Transcriber.cancel(j.id) }) {
                            Icon(Icons.Filled.Close, if (j.done) "إخفاء" else "إلغاء", Modifier.size(18.dp))
                        }
                    }
                    if (!j.done) {
                        Spacer(Modifier.size(8.dp))
                        if (j.progress >= 0f) LinearProgressIndicator(progress = { j.progress }, color = gold, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(color = gold, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** From a file's menu: pick the model (first time) and start, or read / delete what was written. */
@Composable
fun SpeechDialog(f: LibFile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val have = remember { Transcripts.has(f.path) }
    if (have) {
        val t = remember { Transcripts.load(f.path) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("الكلام المكتوب") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    t?.segs?.forEach { s ->
                        Row {
                            Text(formatTime(s.s), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(54.dp))
                            Text(s.t, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    Text("الترجمة بتظهر لوحدها على الفيديو (قايمة الترجمة في المشغّل).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("تمام") } },
            dismissButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("text", t?.segs?.joinToString("\n") { it.t } ?: ""))
                    Toast.makeText(ctx, "اتنسخ النص", Toast.LENGTH_SHORT).show()
                }) { Text("انسخ النص") }
            },
        )
        return
    }
    var pick by remember { mutableStateOf(AppSettings.whisperModel.value.ifEmpty { "small" }) }
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onClose,
        title = { Text("اكتب الكلام (نص عربي)") },
        text = {
            Column {
                Text("بيتكتب كل اللي اتقال في الفيديو أو الصوت، ويتحط ترجمة على الفيديو، وتقدر تدوّر بيه في «اسأل مكتبتك». الشغل كله على موبايلك من غير نت (النموذج بيتنزّل مرة واحدة).", fontSize = 13.sp)
                Spacer(Modifier.size(10.dp))
                WhisperSize.entries.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { pick = s.key }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = pick == s.key, onClick = { pick = s.key })
                        Column {
                            Text(s.label + if (Transcriber.modelReady(ctx, s)) " ✓ منزّل" else "", fontWeight = FontWeight.SemiBold)
                            Text(s.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("الكتابة بتاخد وقت (ساعة صوت ممكن تاخد أكتر من نص ساعة)، وبتكمّل في الخلفية وإنت بره التطبيق.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppSettings.setWhisperModel(pick)
                Transcriber.start(ctx, f)
                Toast.makeText(ctx, "بدأ — التقدّم فوق في المكتبة", Toast.LENGTH_SHORT).show()
                onClose()
            }) { Text("ابدأ") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } },
    )
}
