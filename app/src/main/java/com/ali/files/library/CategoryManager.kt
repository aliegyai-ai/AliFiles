package com.ali.files.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** one category in the bar: a built-in one (key = its name) or one you made (key = "c:<id>") */
data class CatEntry(val key: String, val label: String, val color: Color, val icon: ImageVector, val builtin: Boolean, val hidden: Boolean, val custom: CustomCat? = null)

/** Edit the category bar: drag ☰ to re-order, 👁 hide / show, ✏️ edit, 🗑 delete (the ones you made). */
@Composable
internal fun CategoryManager(
    entries: List<CatEntry>, prefs: Library.CatPrefs, onDismiss: () -> Unit,
    onEdit: (CatEntry) -> Unit, onNew: () -> Unit, onDelete: (CatEntry) -> Unit,
) {
    val order = remember { mutableStateListOf<CatEntry>().also { it.addAll(entries) } }
    // names / colours changed by the edit dialog show up here too
    LaunchedEffect(entries) {
        val byKey = entries.associateBy { it.key }
        order.replaceAll { byKey[it.key] ?: it }
        order.removeAll { it.key !in byKey }
        entries.filter { e -> order.none { it.key == e.key } }.forEach { order.add(it) }
    }
    val lazy = rememberLazyListState()
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var askDelete by remember { mutableStateOf<CatEntry?>(null) }
    val gold = MaterialTheme.colorScheme.primary

    fun commit() {
        val p = Library.catPrefs.value
        Library.setCatPrefs(p.copy(order = order.map { it.key }))
    }
    fun toggleHide(e: CatEntry) {
        val p = Library.catPrefs.value
        Library.setCatPrefs(p.copy(hidden = if (e.key in p.hidden) p.hidden - e.key else p.hidden + e.key))
        order.replaceAll { if (it.key == e.key) it.copy(hidden = !it.hidden) else it }
    }

    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text("رتّب التصنيفات") },
        text = {
            Column {
                Text("اسحب ☰ للترتيب • 👁 للإخفاء • ✏️ للتعديل", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp), state = lazy) {
                    itemsIndexed(order, key = { _, e -> e.key }) { i, e ->
                        val dragging = dragKey == e.key
                        val fixed = e.key == "ALL"
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragY; shadowElevation = 12f } else Modifier.animateItem())
                                .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(12.dp))
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (fixed) Spacer(Modifier.size(40.dp)) else Icon(
                                Icons.Filled.DragHandle, "رتّب", tint = if (dragging) gold else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(e.key) {
                                    detectDragGestures(
                                        onDragStart = { dragKey = e.key; dragY = 0f },
                                        onDrag = { change, amt ->
                                            change.consume(); dragY += amt.y
                                            val info = lazy.layoutInfo.visibleItemsInfo
                                            val cur = info.firstOrNull { it.key == e.key } ?: return@detectDragGestures
                                            val mid = cur.offset + cur.size / 2 + dragY
                                            val tgt = info.firstOrNull { it.key != e.key && it.key != "ALL" && mid >= it.offset && mid <= it.offset + it.size }
                                            if (tgt != null) {
                                                val from = order.indexOfFirst { it.key == e.key }
                                                val to = order.indexOfFirst { it.key == tgt.key }
                                                if (from >= 0 && to >= 0) { order.add(to, order.removeAt(from)); dragY -= (tgt.offset - cur.offset) }
                                            }
                                        },
                                        onDragEnd = { dragKey = null; dragY = 0f; commit() },
                                        onDragCancel = { dragKey = null; dragY = 0f; commit() },
                                    )
                                },
                            )
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(e.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Icon(e.icon, null, tint = e.color, modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                e.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                                color = if (e.hidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                            )
                            if (!fixed) IconButton(onClick = { toggleHide(e) }, Modifier.size(38.dp)) {
                                Icon(if (e.hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (e.hidden) "اظهره" else "اخفيه", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onEdit(e) }, Modifier.size(38.dp)) { Icon(Icons.Filled.Edit, "عدّل", Modifier.size(19.dp)) }
                            if (!e.builtin) IconButton(onClick = { askDelete = e }, Modifier.size(38.dp)) { Icon(Icons.Filled.Delete, "امسح", Modifier.size(19.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تمام") } },
        dismissButton = {
            Row {
                TextButton(onClick = onNew) { Text("+ تصنيف جديد") }
                TextButton(onClick = { Library.setCatPrefs(Library.CatPrefs()); onDismiss() }) { Text("رجّع الأصل") }
            }
        },
    )
    askDelete?.let { e ->
        AlertDialog(
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            onDismissRequest = { askDelete = null },
            title = { Text("تمسح «${e.label}»؟") },
            text = { Text("الملفات اللي فيه ما بتتمسحش، بترجع لتصنيفها التلقائي.") },
            confirmButton = { TextButton(onClick = { onDelete(e); order.removeAll { it.key == e.key }; askDelete = null }) { Text("امسح") } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("إلغاء") } },
        )
    }
}
