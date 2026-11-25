package com.ali.files.library

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.ali.files.library.AskParser.Test
import com.ali.files.player.PlayerHub
import com.ali.files.util.FileKind
import com.ali.files.util.FileStore
import com.ali.files.util.formatBytes
import com.ali.files.util.kindIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** what you searched before, and what you open from the search (opened more → shown higher) */
private object AskMemory {
    private fun p(c: Context) = c.getSharedPreferences("ask_library", Context.MODE_PRIVATE)
    fun recent(c: Context): List<String> = p(c).getString("recent", "")!!.split('\n').filter { it.isNotBlank() }
    fun addRecent(c: Context, q: String) {
        val t = q.trim(); if (t.length < 2) return
        p(c).edit().putString("recent", (listOf(t) + recent(c).filter { it != t }).take(6).joinToString("\n")).apply()
    }
    fun clearRecent(c: Context) = p(c).edit().remove("recent").apply()
    fun hits(c: Context, path: String) = p(c).getInt("h:$path", 0)
    fun hit(c: Context, path: String) = p(c).edit().putInt("h:$path", hits(c, path) + 1).apply()
}

private val EXAMPLES = listOf(
    "فيديوهات النهارده", "أكبر حاجة عندي", "اللي ما كملتهوش",
    "أغاني من غير موسيقى", "فيديوهات طويلة من يوتيوب", "اللي شغلته",
)

/**
 * «اسأل مكتبتك» — full search over the library. Opens from the 🔍 in the library, by pulling the
 * library down from the top, or a long press on «المكتبة» in the bottom bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AskLibrary(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val files by Library.files.collectAsState()
    val folders by Library.folders.collectAsState()
    val lists by Library.playlists.collectAsState()
    val favs by Library.favorites.collectAsState()
    val gold = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var removed by remember { mutableStateOf(setOf<String>()) }
    var menuFor by remember { mutableStateOf<LibFile?>(null) }
    var preview by remember { mutableStateOf<LibFile?>(null) }
    var recent by remember { mutableStateOf(AskMemory.recent(ctx)) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val durations = remember { mutableStateMapOf<String, Long>() }
    var measuring by remember { mutableStateOf(false) }

    BackHandler { if (preview != null) preview = null else onClose() }
    LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() } }
    // what you change in the text brings back the chips you'd removed for the old text
    LaunchedEffect(text) { if (text.isBlank()) removed = emptySet() }

    val q = remember(text, removed) { AskParser.parse(text, removed) }
    val needLength = q.chips.any { it.test is Test.Length || (it.test as? Test.Sort)?.by == "long" }

    // files you may see (a locked folder stays hidden until it's been opened)
    val visible = remember(files) {
        files.filter { f -> !Library.isLocked(f.folder) || LibraryTabState.unlocked.any { u -> f.folder == u || f.folder.startsWith("$u/") } }
    }
    val normNames = remember(visible) { visible.associate { it.path to AskParser.norm(it.title + " " + it.folder) } }

    // a length was asked: measure the videos / songs once (kept in memory)
    LaunchedEffect(needLength, visible) {
        if (!needLength) return@LaunchedEffect
        val todo = visible.filter { (it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO) && it.path !in durations }
        if (todo.isEmpty()) return@LaunchedEffect
        measuring = true
        withContext(Dispatchers.IO) { todo.forEach { f -> durations[f.path] = Library.duration(f) } }
        measuring = false
    }

    val played = PlayerHub.recent.value
    LaunchedEffect(Unit) { PlayerHub.loadRecent() }

    val results: List<LibFile> = remember(q, visible, favs, durations.size, played) {
        if (q.words.isEmpty() && q.chips.isEmpty()) return@remember emptyList()
        var l = visible.filter { f ->
            val hay = normNames[f.path] ?: ""
            q.words.all { AskParser.has(hay, it) } && q.chips.all { c ->
                when (val t = c.test) {
                    is Test.Kind -> f.kind in t.kinds
                    is Test.Since -> f.modified >= t.ms && f.modified < t.until
                    is Test.Size -> f.size >= t.min && f.size <= t.max
                    is Test.Length -> durations[f.path]?.let { d -> d > 0 && d >= t.min && d <= t.max } ?: false
                    is Test.Site -> t.names.any { s -> hay.contains(AskParser.norm(s)) }
                    Test.Separated -> f.folder.startsWith("بدون موسيقى") || f.title.endsWith("(بدون موسيقى)")
                    Test.MusicOnly -> f.folder.startsWith("موسيقى بس") || f.title.endsWith("(موسيقى بس)")
                    Test.Unfinished -> PlayerHub.progressOf(f.path).let { it > 0f && it < 0.97f }
                    Test.Favorite -> f.path in favs
                    Test.Played -> f.path in played
                    is Test.Sort -> true
                }
            }
        }
        val sort = q.chips.firstNotNullOfOrNull { (it.test as? Test.Sort)?.by }
        l = when {
            sort == "size" -> l.sortedByDescending { it.size }
            sort == "small" -> l.sortedBy { it.size }
            sort == "long" -> l.sortedByDescending { durations[it.path] ?: 0L }
            sort == "new" -> l.sortedByDescending { it.modified }
            sort == "old" -> l.sortedBy { it.modified }
            q.chips.any { it.test == Test.Played } -> l.sortedBy { played.indexOf(it.path) }
            else -> {
                // the name starting with what you typed first, then what you open the most, then the newest
                val first = q.words.firstOrNull()
                l.sortedWith(
                    compareByDescending<LibFile> { first != null && (normNames[it.path] ?: "").startsWith(first) }
                        .thenByDescending { AskMemory.hits(ctx, it.path) }
                        .thenByDescending { it.modified },
                )
            }
        }
        if (sort == "size" || sort == "small" || sort == "long") l.take(50) else l
    }
    val words = q.words
    val folderHits = remember(words, folders) {
        if (words.isEmpty()) emptyList() else folders.filter { d -> AskParser.norm(d.name).let { n -> words.all { AskParser.has(n, it) } } }.take(6)
    }
    val listHits = remember(words, lists) {
        if (words.isEmpty()) emptyList() else lists.filter { p -> AskParser.norm(p.name).let { n -> words.all { AskParser.has(n, it) } } }.take(6)
    }

    fun open(f: LibFile, among: List<LibFile>) {
        AskMemory.hit(ctx, f.path)
        AskMemory.addRecent(ctx, text); recent = AskMemory.recent(ctx)
        when (f.kind) {
            FileKind.VIDEO, FileKind.AUDIO -> {
                val same = among.filter { it.kind == f.kind }
                PlayerHub.play(same.map { it.toTrack() }, same.indexOf(f).coerceAtLeast(0))
            }
            else -> FileStore.open(ctx, Library.uriFor(f.file), f.mime)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { } }) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(shown, enter = slideInVertically(tween(260)) { -it / 3 } + fadeIn(tween(200))) {
            Surface(color = scheme.background.copy(alpha = 0.97f), modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    // ---------------------------------------------------------------- the field
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                        Row(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                                .background(scheme.surfaceContainerHigh)
                                .border(1.dp, gold.copy(alpha = 0.55f), RoundedCornerShape(26.dp))
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (text.isEmpty()) Text("اسأل مكتبتك… «أغاني الأسبوع ده»", color = scheme.onSurfaceVariant, maxLines = 1)
                                BasicTextField(
                                    value = text, onValueChange = { text = it }, singleLine = true,
                                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 16.sp),
                                    cursorBrush = SolidColor(gold),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); AskMemory.addRecent(ctx, text); recent = AskMemory.recent(ctx) }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                )
                            }
                            if (text.isNotEmpty()) Icon(Icons.Filled.Close, "امسح", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { text = "" })
                        }
                    }
                    // ---------------------------------------------------------------- what it understood
                    if (q.chips.isNotEmpty()) Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        q.chips.forEach { c ->
                            Row(
                                Modifier.clip(RoundedCornerShape(14.dp)).background(com.ali.files.ui.GoldBrush)
                                    .clickable { removed = removed + c.id }.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.label, color = Color(0xFF14100A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.Close, "شيلها", tint = Color(0xFF14100A), modifier = Modifier.size(14.dp))
                            }
                        }
                        if (words.isNotEmpty()) Text(
                            "🔤 " + words.joinToString(" "), fontSize = 13.sp, color = gold,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, gold.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    if (measuring) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

                    // ---------------------------------------------------------------- results / the start
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 40.dp)) {
                        if (text.isBlank()) {
                            val goOn = played.mapNotNull { p -> visible.firstOrNull { it.path == p } }.filter { PlayerHub.progressOf(it.path).let { v -> v > 0f && v < 0.97f } }.take(4)
                            if (goOn.isNotEmpty()) {
                                item { Head("كمّل اللي كنت بتسمعه") }
                                items(goOn, key = { "go" + it.path }) { f ->
                                    ResultRow(f, words, gold, PlayerHub.progressOf(f.path), onTap = { open(f, goOn) }, onHold = { preview = f }, onRelease = { preview = null }, onMenu = { menuFor = f })
                                }
                            }
                            if (recent.isNotEmpty()) {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) { Head("آخر حاجة دوّرت عليها") }
                                        Text("امسحهم", fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.clickable { AskMemory.clearRecent(ctx); recent = emptyList() }.padding(8.dp))
                                    }
                                }
                                items(recent, key = { "r$it" }) { r ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { text = r }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.History, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(12.dp)); Text(r)
                                    }
                                }
                            }
                            item { Head("جرّب تسأل") }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EXAMPLES.chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            pair.forEach { e ->
                                                Text(
                                                    "✨ $e", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainerHigh)
                                                        .clickable { text = e }.padding(horizontal = 12.dp, vertical = 12.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            item { Text("دوس مطوّلاً على أي نتيجة عشان تسمع / تشوف حتة منها", fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, start = 4.dp)) }
                        } else {
                            if (folderHits.isNotEmpty()) {
                                item { Head("مجلدات") }
                                items(folderHits, key = { "d" + it.rel }) { d ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                            AskMemory.addRecent(ctx, text); LibraryTabState.filter.intValue = 0; LibraryTabState.filterAsk.intValue++
                                            LibraryTabState.folder.value = d.rel; onClose()
                                        }.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Folder, null, tint = gold, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(highlight(d.name, words, gold), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                            Text(if (d.parent.isEmpty()) "${d.count} ملف" else "في ${d.parent} • ${d.count} ملف", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (listHits.isNotEmpty()) {
                                item { Head("قوايم التشغيل") }
                                items(listHits, key = { "l" + it.name }) { p ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                            AskMemory.addRecent(ctx, text); LibraryTabState.openPlaylist.value = p.name; onClose()
                                        }.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color(0xFFBA68C8), modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text(highlight(p.name, words, gold), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        Text("${p.paths.size}", color = scheme.onSurfaceVariant)
                                    }
                                }
                            }
                            val groups = listOf(
                                "فيديوهات" to results.filter { it.kind == FileKind.VIDEO },
                                "صوتيات" to results.filter { it.kind == FileKind.AUDIO },
                                "صور" to results.filter { it.kind == FileKind.IMAGE },
                                "ملفات تانية" to results.filter { it.kind != FileKind.VIDEO && it.kind != FileKind.AUDIO && it.kind != FileKind.IMAGE },
                            ).filter { it.second.isNotEmpty() }
                            groups.forEach { (title, l) ->
                                item(key = "h$title") { Head("$title (${l.size})") }
                                items(l.take(200), key = { title + it.path }) { f ->
                                    ResultRow(
                                        f, words, gold, PlayerHub.progressOf(f.path),
                                        length = durations[f.path],
                                        onTap = { open(f, l) }, onHold = { preview = f }, onRelease = { preview = null }, onMenu = { menuFor = f },
                                    )
                                }
                            }
                            if (groups.isEmpty() && folderHits.isEmpty() && listHits.isEmpty()) item {
                                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🔍", fontSize = 40.sp)
                                    Text(if (measuring) "بيقيس مدة الملفات…" else "مفيش حاجة بالوصف ده", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                                    if (q.chips.isNotEmpty() && !measuring) Text("جرّب تشيل حاجة من اللي فوق ✕", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        preview?.takeIf { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO }?.let { f -> PreviewBubble(f) { preview = null } }
    }
    menuFor?.let { f -> FileActions(f) { menuFor = null } }
}

@Composable
private fun Head(t: String) =
    Text(t, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp))

/** the words you typed, in gold inside the name */
private fun highlight(name: String, words: List<String>, gold: Color): AnnotatedString {
    if (words.isEmpty()) return AnnotatedString(name)
    // map each char of the normalised name back to the original (norm keeps 1 char → ≤1 char)
    val idx = ArrayList<Int>(); val sb = StringBuilder()
    name.forEachIndexed { i, c -> val n = AskParser.norm(c.toString()).ifEmpty { if (c == ' ') " " else "" }; n.forEach { sb.append(it); idx.add(i) } }
    val hay = sb.toString()
    val marks = BooleanArray(name.length)
    words.forEach { w ->
        listOf(w, w.removePrefix("و").removePrefix("ال")).filter { it.length >= 2 || it == w }.forEach { ww ->
            var at = hay.indexOf(ww)
            while (at >= 0 && ww.isNotEmpty()) { for (k in at until at + ww.length) idx.getOrNull(k)?.let { marks[it] = true }; at = hay.indexOf(ww, at + ww.length) }
        }
    }
    return buildAnnotatedString {
        name.forEachIndexed { i, c ->
            if (marks[i]) pushStyle(SpanStyle(color = gold, fontWeight = FontWeight.Bold))
            append(c)
            if (marks[i]) pop()
        }
    }
}

@Composable
private fun ResultRow(
    f: LibFile, words: List<String>, gold: Color, progress: Float, length: Long? = null,
    onTap: () -> Unit, onHold: () -> Unit, onRelease: () -> Unit, onMenu: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val thumb by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, f.path) { value = Library.thumbnail(f) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .pointerInput(f.path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onHold() },
                    onPress = { tryAwaitRelease(); onRelease() },
                )
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            val b = thumb
            if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(kindIcon(f.kind), null, tint = gold)
            if (progress > 0f) LinearProgressIndicator(progress = { progress }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = gold, trackColor = Color.Transparent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(highlight(f.title, words, gold), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            val where = if (f.folder.isEmpty()) "المكتبة" else f.folder.substringAfterLast('/')
            val len = length?.takeIf { it > 0 }?.let { " • " + com.ali.files.player.formatTime(it) } ?: ""
            Text("📁 $where • ${formatBytes(f.size)}$len", fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "خيارات") }
    }
}


/** hold a result: 5 seconds from the middle play here (the music you were playing waits) */
@Composable
private fun PreviewBubble(f: LibFile, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val gold = MaterialTheme.colorScheme.primary
    val p = remember { ExoPlayer.Builder(ctx).build() }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(f.path) {
        val wasPlaying = PlayerHub.isPlaying.value
        if (wasPlaying) PlayerHub.player.pause()
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f.file)))
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == androidx.media3.common.Player.STATE_READY && !ready) {
                    ready = true
                    val d = p.duration
                    if (d > 15_000) p.seekTo(d / 3)
                    p.play()
                }
            }
        })
        p.prepare()
        onDispose { p.release(); if (wasPlaying) PlayerHub.player.play() }
    }
    LaunchedEffect(ready) { if (ready) { delay(5_000); onDone() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 16.dp, modifier = Modifier.padding(28.dp)) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (f.kind == FileKind.VIDEO) {
                    AndroidView(
                        factory = { c -> android.view.TextureView(c).also { p.setVideoTextureView(it) } },
                        modifier = Modifier.width(280.dp).height(158.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                    )
                } else {
                    Box(Modifier.size(120.dp).clip(CircleShape).background(com.ali.files.ui.GoldBrush), contentAlignment = Alignment.Center) {
                        if (ready) Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF14100A), modifier = Modifier.size(56.dp))
                        else CircularProgressIndicator(color = Color(0xFF14100A))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(260.dp))
                Text("سيب صباعك عشان توقف", fontSize = 11.sp, color = gold)
            }
        }
    }
}
