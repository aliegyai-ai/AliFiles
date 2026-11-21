@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ali.files.vault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.ali.files.util.AppSettings
import com.ali.files.util.FileKind
import com.ali.files.util.FileStore
import com.ali.files.util.formatBytes
import com.ali.files.util.kindIcon
import com.ali.files.util.kindLabel
import com.ali.files.util.kindOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ biometric

fun biometricAvailable(c: Context): Boolean =
    BiometricManager.from(c).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS

fun showBiometric(act: FragmentActivity, title: String = "فتح الخزنة", negative: String = "استخدم الرمز", onOk: () -> Unit) {
    val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("حط صباعك على الحساس")
        .setNegativeButtonText(negative)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    runCatching { prompt.authenticate(info) }
}

// ------------------------------------------------------------------ entry point

@Composable
fun VaultScreen() {
    val unlocked by Vault.unlocked
    var hasPin by remember { mutableStateOf(VaultLock.hasPin()) }
    when {
        !hasPin -> PinSetup(onDone = { hasPin = true; Vault.unlock() })
        !unlocked -> LockScreen()
        else -> VaultContent()
    }
}

// ------------------------------------------------------------------ PIN pad

@Composable
private fun PinDots(length: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(maxOf(4, length)) { i ->
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .background(if (i < length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@Composable
private fun PinPad(onDigit: (Char) -> Unit, onBack: () -> Unit, onOk: () -> Unit, enabled: Boolean = true) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val rows = listOf("123", "456", "789", "<0>")
            rows.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    r.forEach { ch ->
                        Surface(
                            onClick = {
                                when (ch) {
                                    '<' -> onBack()
                                    '>' -> onOk()
                                    else -> onDigit(ch)
                                }
                            },
                            enabled = enabled,
                            shape = CircleShape,
                            color = if (ch == '>') MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(70.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                when (ch) {
                                    '<' -> Icon(Icons.AutoMirrored.Filled.Backspace, "مسح")
                                    '>' -> Icon(Icons.Filled.Done, "تأكيد", tint = MaterialTheme.colorScheme.onPrimary)
                                    else -> Text(ch.toString(), fontSize = 26.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinSetup(onDone: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(if (first == null) "اعمل رمز سري للخزنة" else "اكتب الرمز تاني للتأكيد", style = MaterialTheme.typography.titleLarge)
        Text("من 4 لـ 8 أرقام", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        PinDots(pin.length)
        Spacer(Modifier.height(8.dp))
        Text(error ?: " ", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        PinPad(
            enabled = !saving,
            onDigit = { if (pin.length < 8) { pin += it; error = null } },
            onBack = { pin = pin.dropLast(1) },
            onOk = {
                when {
                    pin.length < 4 -> { error = "الرمز لازم يكون 4 أرقام على الأقل" }
                    first == null -> { first = pin; pin = "" }
                    first != pin -> { error = "الرمزين مش زي بعض، ابدأ تاني"; first = null; pin = "" }
                    else -> {
                        saving = true
                        val p = pin
                        scope.launch {
                            withContext(Dispatchers.Default) { VaultLock.setPin(p) }
                            onDone()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LockScreen() {
    val ctx = LocalContext.current
    val act = ctx as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bio = AppSettings.biometric.value && act != null && biometricAvailable(ctx)

    LaunchedEffect(Unit) { if (bio && act != null) showBiometric(act) { Vault.unlock() } }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("الخزنة مقفولة", style = MaterialTheme.typography.titleLarge)
        Text("${Vault.entries.collectAsState().value.size} ملف مشفر", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        PinDots(pin.length)
        Spacer(Modifier.height(8.dp))
        Text(error ?: " ", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        PinPad(
            enabled = !checking,
            onDigit = { if (pin.length < 8) { pin += it; error = null } },
            onBack = { pin = pin.dropLast(1) },
            onOk = {
                val wait = VaultLock.lockedFor()
                if (wait > 0) { error = "محاولات كتير غلط، استنى ${wait / 1000 + 1} ثانية"; return@PinPad }
                if (pin.isEmpty()) return@PinPad
                checking = true
                val p = pin
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { VaultLock.check(p) }
                    checking = false
                    pin = ""
                    if (ok) { Vault.unlock() } else { error = "رمز غلط (${VaultLock.fails()} محاولة)" }
                }
            },
        )
        if (bio && act != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = { showBiometric(act) { Vault.unlock() } }) {
                Icon(Icons.Filled.Fingerprint, null)
                Spacer(Modifier.width(8.dp))
                Text("افتح بالبصمة")
            }
        }
    }
}

// ------------------------------------------------------------------ content

@Composable
private fun SecureWindow() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val w = (ctx as? android.app.Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun openExternally(ctx: Context, e: VaultEntry, file: java.io.File, share: Boolean) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    Vault.allowLeave = true
    if (share) FileStore.share(ctx, uri, e.mime) else FileStore.open(ctx, uri, e.mime)
}

@Composable
private fun VaultContent() {
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by Vault.entries.collectAsState()
    val busy by Vault.busy
    var filter by remember { mutableStateOf<FileKind?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var viewing by remember { mutableStateOf<VaultEntry?>(null) }
    var askDeleteOriginals by remember { mutableStateOf<List<Uri>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VaultEntry?>(null) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            var ok = 0
            uris.forEach { u -> runCatching { Vault.importUri(u) }.onSuccess { ok++ } }
            toast("اتضاف $ok ملف للخزنة")
            if (ok > 0) askDeleteOriginals = uris
        }
    }

    val shown = entries.filter { e ->
        (filter == null || kindOf(e.mime, e.name) == filter) &&
            (query.isBlank() || e.name.contains(query, ignoreCase = true))
    }
    val kinds = entries.map { kindOf(it.mime, it.name) }.distinct().sortedBy { it.ordinal }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // header
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isEmpty()) {
                    Column(Modifier.weight(1f)) {
                        Text("الخزنة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${entries.size} ملف • ${formatBytes(Vault.totalSize())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "بحث") }
                    IconButton(onClick = { Vault.lock() }) { Icon(Icons.Filled.Lock, "اقفل") }
                } else {
                    IconButton(onClick = { selected.clear() }) { Icon(Icons.Filled.Close, "إلغاء") }
                    Text("${selected.size} محدد", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { selected.clear(); selected.addAll(shown.map { it.id }) }) { Icon(Icons.Filled.SelectAll, "تحديد الكل") }
                    if (selected.size == 1) {
                        IconButton(onClick = { renaming = entries.firstOrNull { it.id == selected.first() } }) { Icon(Icons.Filled.Edit, "إعادة تسمية") }
                        IconButton(onClick = {
                            val e = entries.firstOrNull { it.id == selected.first() } ?: return@IconButton
                            scope.launch { runCatching { openExternally(ctx, e, Vault.decryptToCache(e), share = true) }.onFailure { toast(it.message ?: "خطأ") } }
                        }) { Icon(Icons.Filled.Share, "مشاركة") }
                    }
                    IconButton(onClick = {
                        val list = entries.filter { it.id in selected }
                        selected.clear()
                        scope.launch {
                            var ok = 0
                            list.forEach { e -> runCatching { Vault.export(e) }.onSuccess { ok++ } }
                            toast("اتطلع $ok ملف لـ Download/AliFiles")
                        }
                    }) { Icon(Icons.Filled.FileDownload, "إخراج من الخزنة") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "حذف") }
                }
            }
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("دور باسم الملف") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (kinds.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("الكل") })
                    kinds.forEach { k -> FilterChip(selected = filter == k, onClick = { filter = if (filter == k) null else k }, label = { Text(kindLabel(k)) }) }
                }
            }
            if (entries.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("الخزنة فاضية", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ضيف ملفات من زرار (+)، أو شارك أي ملف من أي تطبيق لـ «ملفات علي»، أو اختار «الخزنة» وانت بتحمّل.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.id }) { e ->
                        VaultTile(
                            e = e,
                            isSelected = e.id in selected,
                            onClick = {
                                if (selected.isNotEmpty()) {
                                    if (e.id in selected) selected.remove(e.id) else selected.add(e.id)
                                } else if (kindOf(e.mime, e.name) == FileKind.IMAGE) {
                                    viewing = e
                                } else {
                                    scope.launch {
                                        runCatching { openExternally(ctx, e, Vault.decryptToCache(e), share = false) }
                                            .onFailure { toast(it.message ?: "تعذر فتح الملف") }
                                    }
                                }
                            },
                            onLongClick = { if (e.id !in selected) selected.add(e.id) },
                        )
                    }
                }
            }
        }
        if (selected.isEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { Vault.allowLeave = true; picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("إضافة ملفات") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    viewing?.let { e ->
        ImageViewer(
            e = e,
            onClose = { viewing = null },
            onExport = { scope.launch { runCatching { Vault.export(e) }.onSuccess { toast("اتطلعت لـ Download/AliFiles") } } },
            onShare = { scope.launch { runCatching { openExternally(ctx, e, Vault.decryptToCache(e), share = true) } } },
        )
    }

    askDeleteOriginals?.let { uris ->
        AlertDialog(
            onDismissRequest = { askDeleteOriginals = null },
            title = { Text("تمسح النسخ الأصلية؟") },
            text = { Text("الملفات اتشفرت جوه الخزنة. لو عاوز محدش يلاقيها برة، امسح النسخة الأصلية (${uris.size} ملف).") },
            confirmButton = {
                TextButton(onClick = {
                    val n = uris.count { Vault.deleteOriginal(it) }
                    toast(if (n == uris.size) "اتمسحت الأصول" else "اتمسح $n من ${uris.size} — الباقي امسحه يدوي")
                    askDeleteOriginals = null
                }) { Text("امسح الأصل") }
            },
            dismissButton = { TextButton(onClick = { askDeleteOriginals = null }) { Text("سيبها") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف ${selected.size} ملف نهائياً؟") },
            text = { Text("الحذف من الخزنة مالوش رجوع.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toSet()
                    selected.clear(); confirmDelete = false
                    scope.launch { Vault.delete(ids) }
                }) { Text("احذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
        )
    }

    renaming?.let { e ->
        var name by remember(e.id) { mutableStateOf(e.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("إعادة تسمية") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val n = name
                    renaming = null; selected.clear()
                    if (n.isNotBlank()) scope.launch { Vault.rename(e.id, n) }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun VaultTile(e: VaultEntry, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val kind = kindOf(e.mime, e.name)
    val thumb by produceState<Bitmap?>(null, e.id) { value = Vault.thumbnail(e) }
    Card(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) {
                Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(kindIcon(kind), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == FileKind.VIDEO && t != null) {
                Icon(Icons.Filled.PlayCircle, null, Modifier.size(32.dp), tint = Color.White)
            }
            if (isSelected) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            formatBytes(e.size), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun ImageViewer(e: VaultEntry, onClose: () -> Unit, onExport: () -> Unit, onShare: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { }
        }
        val bmp by produceState<Bitmap?>(null, e.id) { value = runCatching { Vault.decodeImage(e) }.getOrNull() }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f }
                    })
                },
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    b.asImageBitmap(), e.name,
                    Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إغلاق", tint = Color.White) }
                Text(e.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "مشاركة", tint = Color.White) }
                IconButton(onClick = onExport) { Icon(Icons.Filled.FileDownload, "إخراج", tint = Color.White) }
            }
        }
    }
}

/** Dialog shown by the app when files are shared into it. */
@Composable
fun ImportSharedDialog(uris: List<Uri>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("حط ${uris.size} ملف في الخزنة؟") },
        text = {
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Text("الملفات هتتشفر جوه الخزنة. النسخة الأصلية هتفضل مكانها.")
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                working = true
                scope.launch {
                    var ok = 0
                    uris.forEach { u -> runCatching { Vault.importUri(u) }.onSuccess { ok++ } }
                    Toast.makeText(ctx, "اتضاف $ok ملف للخزنة 🔒", Toast.LENGTH_SHORT).show()
                    delay(150)
                    onDismiss()
                }
            }) { Text("ضيفهم") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("إلغاء") } },
    )
}
