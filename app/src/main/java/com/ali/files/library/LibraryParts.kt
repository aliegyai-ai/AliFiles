@file:OptIn(ExperimentalMaterial3Api::class)

package com.ali.files.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ali.files.util.formatBytes
import kotlinx.coroutines.launch

/** Folder colors (0 = gold, the default). */
val FOLDER_COLORS = listOf(0xFFD4A94F, 0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFBA68C8, 0xFFFFB74D, 0xFF4DD0E1, 0xFFF06292, 0xFF90A4AE).map { it.toInt() }

/** Opens with the fingerprint, or the phone's own PIN/pattern if there's no fingerprint. */
fun unlockWithPhoneLock(c: Context, title: String, onOk: () -> Unit) {
    val act = c as? FragmentActivity ?: return
    val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(c).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
        Toast.makeText(c, "فعّل قفل الشاشة على موبايلك الأول عشان تقفل المجلدات", Toast.LENGTH_LONG).show(); return
    }
    val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
    })
    runCatching {
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth).build())
    }
}

@Composable
internal fun SelectionBar(
    count: Int, onClose: () -> Unit, onAll: () -> Unit, onMove: () -> Unit, onZip: () -> Unit,
    onCopy: () -> Unit, onGallery: () -> Unit, onVault: () -> Unit, onDelete: () -> Unit,
) {
    val gold = MaterialTheme.colorScheme.primary
    Surface(color = gold.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إلغاء") }
                Text("$count متحدد", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onAll) { Icon(Icons.Filled.SelectAll, "حدد الكل") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BarAction(Icons.AutoMirrored.Filled.DriveFileMove, "انقل", onMove)
                BarAction(Icons.Filled.ContentCopy, "نسخة", onCopy)
                BarAction(Icons.Filled.PhotoLibrary, "الاستديو", onGallery)
                BarAction(Icons.Filled.FolderZip, "اضغط", onZip)
                BarAction(Icons.Filled.Lock, "الخزنة", onVault)
                BarAction(Icons.Filled.Delete, "امسح", onDelete, danger = true)
            }
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val c = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c)
        Text(label, fontSize = 11.sp)
    }
}

@Composable
internal fun Breadcrumbs(current: String, onGo: (String) -> Unit) {
    val parts = current.split('/')
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onGo("") }) { Text("المكتبة") }
        parts.forEachIndexed { i, p ->
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val target = parts.take(i + 1).joinToString("/")
            TextButton(onClick = { onGo(target) }, enabled = i < parts.lastIndex) {
                Text(p, fontWeight = if (i == parts.lastIndex) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(d: LibFolder, meta: FolderMeta, pinned: Boolean, locked: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val color = Color(FOLDER_COLORS.getOrElse(meta.color) { FOLDER_COLORS[0] })
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onMenu).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (locked) Icons.Filled.Lock else Icons.Filled.Folder, null, tint = color, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(if (locked) "مقفول 🔒" else "${d.count} ملف • ${formatBytes(d.size)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pinned) Icon(Icons.Filled.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "خيارات") }
    }
}

@Composable
internal fun FolderActions(d: LibFolder, meta: FolderMeta, pinned: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(d.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Text("${d.count} ملف • ${formatBytes(d.size)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Action(Icons.Filled.DriveFileRenameOutline, "إعادة تسمية") { renaming = true }
            Action(Icons.Filled.Palette, "لون المجلد") { coloring = true }
            Action(Icons.Filled.PushPin, if (pinned) "شيل التثبيت" else "ثبّته فوق") { Library.togglePin("dir:" + d.rel); onDismiss() }
            if (meta.locked) Action(Icons.Filled.LockOpen, "شيل القفل") {
                unlockWithPhoneLock(ctx, "شيل قفل المجلد") { Library.setLocked(d.rel, false); toast("القفل اتشال") }
                onDismiss()
            } else Action(Icons.Filled.Lock, "اقفله بالبصمة") {
                unlockWithPhoneLock(ctx, "اقفل المجلد") { Library.setLocked(d.rel, true); LibraryTabState.unlocked.remove(d.rel); toast("المجلد اتقفل 🔒") }
                onDismiss()
            }
            Action(Icons.Filled.Delete, "امسح المجلد", danger = true) { confirm = true }
        }
    }
    if (renaming) NameDialog("إعادة تسمية", d.name) { n ->
        renaming = false
        if (!n.isNullOrBlank() && !Library.renameFolder(d, n)) toast("تعذر تغيير الاسم")
        onDismiss()
    }
    if (coloring) AlertDialog(
        onDismissRequest = { coloring = false },
        title = { Text("لون المجلد") },
        text = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FOLDER_COLORS.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                            .clickable { Library.setColor(d.rel, i); coloring = false; onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { if (meta.color == i) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { coloring = false }) { Text("إلغاء") } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("تمسح «${d.name}»؟") },
        text = { Text("المجلد وكل اللي فيه (${d.count} ملف) هيروحوا سلة المهملات 30 يوم.") },
        confirmButton = { TextButton(onClick = { confirm = false; Library.deleteFolder(d); onDismiss() }) { Text("امسح", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("لأ") } },
    )
}

/** Pick where to move files (any folder, or the top of the library). */
@Composable
internal fun FolderPicker(title: String, exclude: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val folders by Library.folders.collectAsState()
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Text("📚  المكتبة (برّه المجلدات)", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick("") }.padding(12.dp))
                }
                items(folders.filter { it.rel != exclude }.sortedBy { it.rel.lowercase() }) { d ->
                    Text(
                        "📁  " + d.rel.replace("/", " › "),
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(d.rel) }.padding(12.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                item { TextButton(onClick = { creating = true }) { Text("+ مجلد جديد") } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
    if (creating) NameDialog("مجلد جديد", "") { n ->
        creating = false
        if (!n.isNullOrBlank()) { Library.createFolder("", n); onPick(com.ali.files.util.sanitizeFileName(n.trim())) }
    }
}

@Composable
internal fun TrashSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val trash by Library.trash.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("سلة المهملات", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("الحاجات بتتمسح نهائي بعد 30 يوم", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (trash.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("فضّيها", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.size(8.dp))
            if (trash.isEmpty()) Text("السلة فاضية 👌", Modifier.padding(vertical = 30.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(trash.sortedByDescending { it.deletedAt }, key = { it.id }) { e ->
                    val days = 30 - ((System.currentTimeMillis() - e.deletedAt) / 86_400_000).toInt()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.isDir) Icons.Filled.Folder else Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatBytes(e.size)} • فاضل $days يوم", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            if (Library.restore(e)) Toast.makeText(ctx, "رجع مكانه", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Filled.Restore, "رجّعه", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = { Library.purge(e) }) { Icon(Icons.Filled.Close, "امسحه نهائي") }
                    }
                }
            }
        }
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("تفضّي السلة؟") },
        text = { Text("كل اللي فيها هيتمسح نهائي ومش هيرجع.") },
        confirmButton = { TextButton(onClick = { Library.emptyTrash(); confirmEmpty = false }) { Text("فضّيها", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("لأ") } },
    )
}

/** «اعمله نغمة»: call / notifications / alarm. Asks for the "change system settings" permission once. */
@Composable
internal fun ToneDialog(f: LibFile, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("اعمله نغمة") },
        text = {
            Column {
                Library.ToneType.entries.forEach { t ->
                    Text(
                        t.label,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            if (!Settings.System.canWrite(ctx)) {
                                Toast.makeText(ctx, "فعّل «تعديل إعدادات النظام» للتطبيق وارجع جرّب تاني", Toast.LENGTH_LONG).show()
                                runCatching {
                                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                                }
                                onDone(); return@clickable
                            }
                            scope.launch {
                                runCatching { Library.setAsTone(f, t) }
                                    .onSuccess { Toast.makeText(ctx, "بقت ${t.label} ✓", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, it.message ?: "تعذر", Toast.LENGTH_SHORT).show() }
                                onDone()
                            }
                        }.padding(12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("إلغاء") } },
    )
}
