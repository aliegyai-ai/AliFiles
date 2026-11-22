@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ali.files.library

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.download.DownloadRequest
import com.ali.files.download.DownloadsScreen
import com.ali.files.player.PlayerHub
import com.ali.files.player.Track
import com.ali.files.player.formatTime
import com.ali.files.util.FileKind
import com.ali.files.util.FileStore
import com.ali.files.util.formatBytes
import com.ali.files.util.kindIcon
import com.ali.files.util.kindOf
import kotlinx.coroutines.launch

private enum class LFilter(val label: String) {
    ALL("الكل"), VIDEO("فيديو"), AUDIO("صوت"), FAV("المفضلة"), LISTS("قوايم التشغيل"), IMAGE("صور"), OTHER("ملفات")
}

fun LibFile.toTrack(): Track = Track(
    id = path, uri = Uri.fromFile(file), title = title,
    subtitle = formatBytes(size), isVideo = kind == FileKind.VIDEO, libraryPath = path,
)

/** The «المكتبة» tab: the library (what you downloaded) + the downloads list. */
@Composable
fun LibraryTab(onRequest: (DownloadRequest) -> Unit, onOpenVault: () -> Unit) {
    var page by LibraryTabState.page
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = page, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("المكتبة", fontWeight = FontWeight.Bold) })
            Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("التحميلات", fontWeight = FontWeight.Bold) })
        }
        if (page == 0) LibraryScreen() else DownloadsScreen(onRequest, onOpenVault)
    }
}

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    val files by Library.files.collectAsState()
    val favs by Library.favorites.collectAsState()
    val playlists by Library.playlists.collectAsState()
    var filter by remember { mutableStateOf(LFilter.ALL) }
    var menuFor by remember { mutableStateOf<LibFile?>(null) }
    var openList by remember { mutableStateOf<Playlist?>(null) }

    // play files straight from the phone (not copied into the app)
    val pickFromPhone = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val tracks = uris.map { u ->
                val name = FileStore.displayName(ctx, u)
                val kind = kindOf(ctx.contentResolver.getType(u), name)
                Track(id = u.toString(), uri = u, title = name.substringBeforeLast('.'), subtitle = "من الموبايل", isVideo = kind == FileKind.VIDEO)
            }
            PlayerHub.play(tracks)
        }
    }

    val shown = when (filter) {
        LFilter.ALL -> files
        LFilter.VIDEO -> files.filter { it.kind == FileKind.VIDEO }
        LFilter.AUDIO -> files.filter { it.kind == FileKind.AUDIO }
        LFilter.FAV -> files.filter { it.path in favs }
        LFilter.IMAGE -> files.filter { it.kind == FileKind.IMAGE }
        LFilter.OTHER -> files.filter { it.kind != FileKind.VIDEO && it.kind != FileKind.AUDIO && it.kind != FileKind.IMAGE }
        LFilter.LISTS -> emptyList()
    }

    fun open(f: LibFile, list: List<LibFile>) {
        when (f.kind) {
            FileKind.VIDEO, FileKind.AUDIO -> {
                val playable = list.filter { it.kind == f.kind }
                PlayerHub.play(playable.map { it.toTrack() }, playable.indexOf(f).coerceAtLeast(0))
            }
            else -> FileStore.open(ctx, Library.uriFor(f.file), f.mime)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${files.size} ملف • ${formatBytes(Library.totalSize())}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("محفوظين جوه التطبيق بس", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { pickFromPhone.launch(arrayOf("video/*", "audio/*")) }) {
                Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("شغّل من الموبايل")
            }
        }
        SeparationJobs()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f; openList = null }, label = { Text(f.label) }) }
        }

        when {
            filter == LFilter.LISTS -> PlaylistsView(playlists, openList, onOpen = { openList = it }, onFile = { f, l -> open(f, l) }, onMenu = { menuFor = it })
            shown.isEmpty() -> EmptyLibrary(filter)
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (filter == LFilter.VIDEO) 2 else 1),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 90.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(if (filter == LFilter.VIDEO) 12.dp else 2.dp),
            ) {
                if (filter == LFilter.AUDIO || filter == LFilter.FAV) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val songs = shown.filter { it.kind == FileKind.AUDIO }
                        if (songs.isNotEmpty()) Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { PlayerHub.shuffle.value = false; PlayerHub.play(songs.map { it.toTrack() }) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("شغّل الكل")
                            }
                            OutlinedButton(onClick = { PlayerHub.shuffle.value = true; PlayerHub.play(songs.shuffled().map { it.toTrack() }) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("عشوائي")
                            }
                        }
                    }
                }
                items(shown, key = { it.path }) { f ->
                    if (filter == LFilter.VIDEO) VideoCard(f, onClick = { open(f, shown) }, onMenu = { menuFor = f })
                    else FileRow(f, favorite = f.path in favs, onClick = { open(f, shown) }, onMenu = { menuFor = f })
                }
            }
        }
    }

    menuFor?.let { f -> FileActions(f) { menuFor = null } }
}

/** Cards for «remove the music» jobs: progress, time left, cancel, play when done. */
@Composable
private fun SeparationJobs() {
    val ctx = LocalContext.current
    val jobs by com.ali.files.separate.Separator.jobs.collectAsState()
    if (jobs.isEmpty()) return
    val gold = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        jobs.forEach { j ->
            Surface(shape = RoundedCornerShape(14.dp), color = gold.copy(alpha = 0.10f)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (j.mode == com.ali.files.separate.SepMode.VOICE) Icons.Filled.MusicOff else Icons.Filled.LibraryMusic, null, tint = gold)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${j.title} • ${j.mode.label}", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                j.error ?: (j.state + if (j.etaSec > 0 && !j.done) " • باقي ${formatTime(j.etaSec * 1000)}" else ""),
                                fontSize = 12.sp, color = if (j.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val out = j.output
                        if (j.done && out != null) {
                            IconButton(onClick = {
                                val f = java.io.File(out)
                                PlayerHub.play(listOf(Track(id = f.absolutePath, uri = Uri.fromFile(f), title = f.nameWithoutExtension, subtitle = j.mode.label, isVideo = j.isVideo, libraryPath = f.absolutePath)))
                            }) { Icon(Icons.Filled.PlayArrow, "شغّل", tint = gold) }
                        }
                        IconButton(onClick = { if (j.done) com.ali.files.separate.Separator.dismiss(j.id) else com.ali.files.separate.Separator.cancel(ctx, j.id) }) {
                            Icon(Icons.Filled.Close, if (j.done) "إخفاء" else "إلغاء", Modifier.size(18.dp))
                        }
                    }
                    if (!j.done) {
                        Spacer(Modifier.height(8.dp))
                        if (j.progress >= 0f) androidx.compose.material3.LinearProgressIndicator(progress = { j.progress }, color = gold, modifier = Modifier.fillMaxWidth())
                        else androidx.compose.material3.LinearProgressIndicator(color = gold, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(filter: LFilter) {
    Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.VideoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Spacer(Modifier.height(12.dp))
        Text(if (filter == LFilter.FAV) "مفيش حاجة في المفضلة" else "المكتبة فاضية", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            if (filter == LFilter.FAV) "دوس ♡ على أي ملف عشان يظهر هنا." else "أي حاجة تحمّلها هتتحفظ هنا جوه التطبيق، وانت تختار تحفظها على الموبايل وقت ما تحب.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
    }
}

@Composable
private fun rememberThumb(f: LibFile): Bitmap? {
    val b by produceState<Bitmap?>(null, f.path, f.modified) { value = Library.thumbnail(f) }
    return b
}

@Composable
private fun rememberDuration(f: LibFile): Long {
    val d by produceState(0L, f.path) { if (f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) value = Library.duration(f) }
    return d
}

@Composable
private fun VideoCard(f: LibFile, onClick: () -> Unit, onMenu: () -> Unit) {
    val thumb = rememberThumb(f)
    val dur = rememberDuration(f)
    Column(Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onMenu)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
            }
            if (dur > 0) Text(
                formatTime(dur), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
            Text(f.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.MoreVert, "خيارات", Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onMenu).padding(4.dp))
        }
    }
}

@Composable
private fun FileRow(f: LibFile, favorite: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val thumb = rememberThumb(f)
    val dur = rememberDuration(f)
    val gold = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onMenu).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(gold.copy(alpha = 0.22f), gold.copy(alpha = 0.08f)))),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(kindIcon(f.kind), null, tint = gold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(dur.takeIf { it > 0 }?.let { formatTime(it) }, formatBytes(f.size), f.file.extension.uppercase().takeIf { it.isNotEmpty() }).joinToString(" • "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (favorite) Icon(Icons.Filled.Favorite, null, tint = gold, modifier = Modifier.size(16.dp))
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "خيارات") }
    }
}

// ============================================================ playlists

@Composable
private fun PlaylistsView(lists: List<Playlist>, open: Playlist?, onOpen: (Playlist?) -> Unit, onFile: (LibFile, List<LibFile>) -> Unit, onMenu: (LibFile) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val current = open?.let { o -> lists.firstOrNull { it.name == o.name } }
    if (current != null) {
        val items = Library.filesOf(current)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpen(null) }) { Text("← القوايم") }
                Text(current.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) IconButton(onClick = { PlayerHub.play(items.map { it.toTrack() }) }) { Icon(Icons.Filled.PlayArrow, "شغّل", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { Library.deletePlaylist(current.name); onOpen(null) }) { Icon(Icons.Filled.Delete, "امسح القايمة") }
            }
            if (items.isEmpty()) Text("القايمة فاضية. من ⋮ على أي ملف اختار «ضيف لقايمة تشغيل».", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            items.forEach { f -> FileRow(f, favorite = false, onClick = { onFile(f, items) }, onMenu = { onMenu(f) }) }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("قايمة تشغيل جديدة")
        }
        lists.forEach { p ->
            Surface(onClick = { onOpen(p) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${p.paths.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (creating) NameDialog("قايمة تشغيل جديدة", "") { name -> creating = false; if (name != null) Library.createPlaylist(name) }
}

// ============================================================ actions on one file

@Composable
private fun FileActions(f: LibFile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val favs by Library.favorites.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var pickList by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) scope.launch {
            runCatching { Library.saveTo(f, uri) }.onSuccess { toast("اتحفظ على الموبايل") }.onFailure { toast(it.message ?: "تعذر الحفظ") }
        }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(f.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Text(formatBytes(f.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) {
                Action(Icons.Filled.PlayArrow, "تشغيل") { onDismiss(); PlayerHub.play(listOf(f.toTrack())) }
            }
            Action(Icons.Filled.Download, "احفظ على الموبايل (التنزيلات)") {
                scope.launch {
                    runCatching { Library.saveToDownloads(f) }.onSuccess { toast("اتحفظ في Download/AliFiles") }.onFailure { toast(it.message ?: "تعذر الحفظ") }
                }
                onDismiss()
            }
            Action(Icons.AutoMirrored.Filled.DriveFileMove, "احفظ في مكان تختاره…") { saveAs.launch(f.name) }
            Action(Icons.Filled.Share, "مشاركة") { onDismiss(); FileStore.share(ctx, Library.uriFor(f.file), f.mime) }
            val fav = f.path in favs
            Action(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (fav) "شيل من المفضلة" else "ضيف للمفضلة") { Library.toggleFavorite(f.path); onDismiss() }
            if (f.kind == FileKind.AUDIO || f.kind == FileKind.VIDEO) {
                Action(Icons.Filled.MusicOff, "شيل الموسيقى (الصوت بس)") {
                    com.ali.files.separate.Separator.start(ctx, f, com.ali.files.separate.SepMode.VOICE)
                    toast("بدأ فصل الموسيقى — هتلاقي التقدّم فوق في المكتبة"); onDismiss()
                }
                Action(Icons.Filled.LibraryMusic, "الموسيقى بس (من غير صوت)") {
                    com.ali.files.separate.Separator.start(ctx, f, com.ali.files.separate.SepMode.MUSIC)
                    toast("بدأ فصل الموسيقى — هتلاقي التقدّم فوق في المكتبة"); onDismiss()
                }
                Action(Icons.AutoMirrored.Filled.PlaylistAdd, "ضيف لقايمة تشغيل") { pickList = true }
            }
            Action(Icons.Filled.DriveFileRenameOutline, "إعادة تسمية") { renaming = true }
            Action(Icons.Filled.Lock, "انقله للخزنة 🔒") {
                scope.launch { runCatching { Library.moveToVault(f) }.onSuccess { toast("اتنقل للخزنة") }.onFailure { toast(it.message ?: "تعذر النقل") } }
                onDismiss()
            }
            Action(Icons.Filled.Delete, "امسح", danger = true) { confirmDelete = true }
        }
    }

    if (renaming) NameDialog("إعادة تسمية", f.title) { name ->
        renaming = false
        if (name != null && name.isNotBlank()) { if (Library.rename(f, name.trim()) == null) toast("تعذر تغيير الاسم") }
        onDismiss()
    }
    if (pickList) PlaylistPicker(f) { pickList = false; onDismiss() }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("تمسح «${f.title}»؟") },
        text = { Text("هيتمسح من المكتبة نهائيًا.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Library.delete(f); onDismiss() }) { Text("امسح", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("لأ") } },
    )
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = c) },
        leadingContent = { Icon(icon, null, tint = if (danger) c else MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PlaylistPicker(f: LibFile, onDone: () -> Unit) {
    val lists by Library.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("ضيف لقايمة تشغيل") },
        text = {
            Column {
                lists.forEach { p ->
                    Text(
                        p.name + if (f.path in p.paths) " ✓" else "",
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { Library.addToPlaylist(p.name, f.path); onDone() }.padding(12.dp),
                    )
                }
                TextButton(onClick = { creating = true }) { Text("+ قايمة جديدة") }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("إلغاء") } },
    )
    if (creating) NameDialog("قايمة تشغيل جديدة", "") { name -> creating = false; if (!name.isNullOrBlank()) { Library.createPlaylist(name, f.path); onDone() } }
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text) }) { Text("تمام") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("إلغاء") } },
    )
}


/** which page of the «المكتبة» tab is open (0 library, 1 downloads) — kept outside so other screens can switch it */
object LibraryTabState { val page = androidx.compose.runtime.mutableIntStateOf(0) }
