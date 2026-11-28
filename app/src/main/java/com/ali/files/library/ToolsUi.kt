package com.ali.files.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ali.files.util.formatBytes
import kotlinx.coroutines.launch

/** the settings rows for: duplicates, storage cleanup, backup / restore */
@Composable
fun LibraryToolsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dup by remember { mutableStateOf(false) }
    var clean by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
        if (u != null) scope.launch {
            val ok = LibraryTools.export(ctx, u)
            Toast.makeText(ctx, if (ok) "اتحفظت النسخة الاحتياطية" else "معرفتش أحفظ النسخة", Toast.LENGTH_LONG).show()
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val ok = LibraryTools.import(ctx, u)
            Toast.makeText(ctx, if (ok) "رجعت القوايم والتصنيفات والمفضلة ومكان الوقوف" else "الملف ده مش نسخة احتياطية صحيحة", Toast.LENGTH_LONG).show()
        }
    }
    ListItem(
        headlineContent = { Text("دوّر على الملفات المكررة") },
        supportingContent = { Text("نفس الفيديو أو الأغنية محمّلة مرتين — امسح النسخة الزيادة ووفّر مساحة") },
        leadingContent = { Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { dup = true },
    )
    ListItem(
        headlineContent = { Text("تنضيف المساحة") },
        supportingContent = { Text("فيديوهات وأغاني ما فتحتهاش من فترة — امسحها بضغطة") },
        leadingContent = { Icon(Icons.Filled.CleaningServices, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { clean = true },
    )
    ListItem(
        headlineContent = { Text("احفظ نسخة احتياطية من المكتبة") },
        supportingContent = { Text("القوايم والتصنيفات والمفضلة ومكان الوقوف في كل ملف (من غير الملفات نفسها)") },
        leadingContent = { Icon(Icons.Filled.Save, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { save.launch("alifiles-backup.zip") },
    )
    ListItem(
        headlineContent = { Text("رجّع نسخة احتياطية") },
        supportingContent = { Text("على موبايل جديد: انقل الملفات الأول وبعدين رجّع النسخة") },
        leadingContent = { Icon(Icons.Filled.Restore, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { load.launch(arrayOf("*/*")) },
    )
    if (dup) DuplicatesDialog { dup = false }
    if (clean) CleanupDialog { clean = false }
}

@Composable
private fun ToolDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("قفل") }
                }
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
private fun DuplicatesDialog(onClose: () -> Unit) {
    var groups by remember { mutableStateOf<List<List<LibFile>>?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        val g = LibraryTools.duplicates()
        groups = g
        // keep the oldest of each group, tick the extra copies
        g.forEach { grp -> grp.drop(1).forEach { picked += it.path } }
    }
    ToolDialog("الملفات المكررة", onClose) {
        val g = groups
        when {
            g == null -> Text("بدوّر…", Modifier.padding(20.dp))
            g.isEmpty() -> Text("مفيش ملفات مكررة. المكتبة نضيفة 👌", Modifier.padding(20.dp))
            else -> {
                val all = g.flatten().filter { it.path in picked }
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.weight(1f)) {
                        items(g) { grp ->
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("${formatBytes(grp.first().size)} × ${grp.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                grp.forEachIndexed { i, f ->
                                    Row(Modifier.fillMaxWidth().clickable { if (f.path in picked) picked -= f.path else picked += f.path }, verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = f.path in picked, onCheckedChange = { if (it) picked += f.path else picked -= f.path })
                                        Column {
                                            Text(f.name, maxLines = 1, fontSize = 14.sp)
                                            Text((if (i == 0) "الأقدم • " else "") + (f.folder.ifBlank { "المكتبة" }), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Button(
                        enabled = all.isNotEmpty(),
                        onClick = {
                            Library.deleteMany(all)
                            picked.clear(); groups = emptyList()
                        },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    ) { Text("امسح ${all.size} نسخة (يوفّر ${formatBytes(all.sumOf { it.size })})") }
                }
            }
        }
    }
}

@Composable
private fun CleanupDialog(onClose: () -> Unit) {
    var days by remember { mutableStateOf(90) }
    val files = remember(days) { LibraryTools.stale(days) }
    val picked = remember(days) { mutableStateListOf<String>().also { l -> files.forEach { l += it.path } } }
    ToolDialog("تنضيف المساحة", onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30 to "شهر", 90 to "3 شهور", 180 to "6 شهور").forEach { (d, l) ->
                    FilterChip(selected = days == d, onClick = { days = d }, label = { Text("من $l") })
                }
            }
            if (files.isEmpty()) Text("مفيش حاجة كبيرة مهملة المدة دي.", Modifier.padding(20.dp))
            else {
                val sel = files.filter { it.path in picked }
                Text("${files.size} ملف ما اتفتحوش من $days يوم — ${formatBytes(files.sumOf { it.size })}", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp)
                LazyColumn(Modifier.weight(1f)) {
                    items(files) { f ->
                        Row(Modifier.fillMaxWidth().clickable { if (f.path in picked) picked -= f.path else picked += f.path }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = f.path in picked, onCheckedChange = { if (it) picked += f.path else picked -= f.path })
                            Column(Modifier.weight(1f)) {
                                Text(f.name, maxLines = 1, fontSize = 14.sp)
                                Text(f.folder.ifBlank { "المكتبة" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatBytes(f.size), fontSize = 12.sp)
                        }
                    }
                }
                Button(
                    enabled = sel.isNotEmpty(),
                    onClick = { Library.deleteMany(sel); onClose() },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text("امسح ${sel.size} ملف (يوفّر ${formatBytes(sel.sumOf { it.size })})") }
            }
        }
    }
}
