package com.ali.files.ui

import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Person
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.BuildConfigInfo
import com.ali.files.browser.BrowserController
import com.ali.files.util.AppSettings
import com.ali.files.vault.Vault
import com.ali.files.vault.VaultLock
import com.ali.files.vault.biometricAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Section(title: String) {
    Text(
        title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (sub != null) { { Text(sub) } } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
fun SettingsScreen(browser: BrowserController, ui: UiState) {
    val ctx = LocalContext.current
    var changePin by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp))

        // your name + share the app
        var editName by remember { mutableStateOf(false) }
        val shareCtx = LocalContext.current
        ListItem(
            headlineContent = { Text("اسمك") },
            supportingContent = { Text(AppSettings.userName.value.ifBlank { "مش متحدد" }) },
            leadingContent = { Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable { editName = true },
        )
        if (editName) NameAsk("اسمك", "التطبيق هيناديك بيه في الرئيسية", AppSettings.userName.value, onSkip = { editName = false }) {
            AppSettings.setUserName(it); editName = false
        }
        ListItem(
            headlineContent = { Text("شارك التطبيق") },
            supportingContent = { Text("ابعت لينك التحميل لحد يجربه") },
            leadingContent = { Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable {
                val text = "جرّب تطبيق «ملفات علي»: متصفح بيحمّل أي فيديو، ومكتبة ومشغل وخزنة.\n" +
                    "حمّله من هنا:\nhttps://github.com/aliegyai-ai/AliFiles/releases/download/latest/AliFiles.apk\n\n" +
                    "لو الموبايل قال «مش مسموح»: الإعدادات ← السماح من المصدر ده ← تثبيت."
                shareCtx.startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
                        "شارك التطبيق",
                    ),
                )
            },
        )
        HorizontalDivider()

        val updScope = rememberCoroutineScope()
        ListItem(
            headlineContent = { Text("دوّر على تحديث للتطبيق") },
            supportingContent = { Text("النسخة اللي عندك: ${com.ali.files.update.Updater.installedVersion(ctx).second}") },
            leadingContent = { androidx.compose.material3.Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable {
                ui.updateManual.value = true
                updScope.launch { com.ali.files.update.Updater.check(ctx.applicationContext, manual = true) }
            },
        )
        HorizontalDivider()

        Text("التحديثات", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        listOf(
            "auto" to "تلقائي بالكامل (زي جوجل بلاي)",
            "download" to "نزّله لوحده واسألني قبل التثبيت",
            "ask" to "اسألني الأول",
        ).forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { AppSettings.setUpdateMode(key) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = AppSettings.updateMode.value == key, onClick = { AppSettings.setUpdateMode(key) })
                Text(label)
            }
        }
        val ctxU = LocalContext.current
        SwitchRow("نزّل التحديثات على الواي فاي بس", "عشان الباقة", AppSettings.updateWifiOnly.value) {
            AppSettings.setUpdateWifiOnly(it); com.ali.files.update.AutoUpdate.schedule(ctxU)
        }
        val upLog = remember { com.ali.files.update.AutoUpdate.lastLog(ctxU) }
        if (upLog.isNotBlank()) Text("آخر حاجة حصلت: $upLog", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))

        HorizontalDivider()
        Section("التحميل")
        Text("عدد الخيوط الافتراضي: ${AppSettings.threads.intValue}", Modifier.padding(horizontal = 16.dp))
        Slider(
            value = AppSettings.threads.intValue.toFloat(),
            onValueChange = { AppSettings.setThreads(it.toInt()) },
            valueRange = 1f..16f, steps = 14,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SwitchRow("احفظ التحميلات في الخزنة تلقائياً", "تقدر تغيّرها لكل تحميل", AppSettings.saveToVault.value) { AppSettings.setSaveToVault(it) }

        var updating by remember { mutableStateOf(false) }
        val scope2 = rememberCoroutineScope()
        ListItem(
            headlineContent = { Text(if (updating) "جاري التحديث…" else "حدّث محرك تحميل الفيديو") },
            supportingContent = { Text("لو يوتيوب أو فيسبوك بطّل يحمّل، حدّثه من هنا (بيتحدث لوحده كل يومين)") },
            modifier = Modifier.clickable(enabled = !updating) {
                updating = true
                scope2.launch {
                    val msg = runCatching { com.ali.files.download.VideoExtractor.update() }.getOrElse { "تعذر التحديث: ${it.message}" }
                    updating = false
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            },
        )

        HorizontalDivider()
        Section("المتصفح")
        SwitchRow(
            "وضع النسيان",
            "المواقع تنسى اللي شفته ودوّرت عليه كل ما ترجع للتطبيق. المواقع اللي عاوزها تفضل فاكراك: من قايمة المتصفح ← «خلّيه فاكرني»",
            AppSettings.forgetMode.value,
        ) { AppSettings.setForgetMode(it) }
        if (AppSettings.forgetMode.value && AppSettings.keepSites.value.isNotEmpty()) {
            Text("فاكرينك: " + AppSettings.keepSites.value.sorted().joinToString("، "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            TextButton(onClick = { AppSettings.setKeepSites(emptySet()) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("انسى القايمة دي") }
        }
        SwitchRow("مانع الإعلانات والنوافذ المنبثقة", "بيقفل شبكات الإعلانات والتتبع", AppSettings.adBlock.value) { AppSettings.setAdBlock(it) }
        SwitchRow("وضع ليلي للمواقع", "يخلي المواقع داكنة لما الموبايل يكون على الوضع الداكن", AppSettings.darkSites.value) { AppSettings.setDarkSites(it); browser.applyDarkSites() }
        SwitchRow("أزرار ⬇ جوه الصفحات", "زرار تحميل صغير فوق كل فيديو جوه الصفحة", AppSettings.inPageButtons.value) { AppSettings.setInPageButtons(it) }
        SwitchRow("زرار التحميل العايم فوق الصفحة", "مقفول: استخدم أيقونة التحميل في الشريط اللي تحت", AppSettings.floatingDownload.value) { AppSettings.setFloatingDownload(it) }
        if (biometricAvailable(ctx)) {
            SwitchRow("قفل التصفح الخفي بالبصمة", "يطلب البصمة كل ما تفتح التصفح الخفي", AppSettings.privateLock.value) { AppSettings.setPrivateLock(it) }
        }
        SwitchRow("التقاط الروابط من الحافظة", "لما تنسخ لينك يعرض عليك تحمّله", AppSettings.clipboardDetect.value) { AppSettings.setClipboardDetect(it) }
        Text("محرك البحث", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        listOf("google" to "Google", "ddg" to "DuckDuckGo", "bing" to "Bing").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { AppSettings.setSearchEngine(key) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = AppSettings.searchEngine.value == key, onClick = { AppSettings.setSearchEngine(key) })
                Text(label)
            }
        }
        Text("بحث التصفح الخفي", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        listOf("ddg" to "DuckDuckGo (مش بيتتبعك)", "brave" to "Brave Search", "same" to "زي البحث العادي").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { AppSettings.setPrivateSearch(key) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = AppSettings.privateSearch.value == key, onClick = { AppSettings.setPrivateSearch(key) })
                Text(label)
            }
        }
        Text("امسح التصفح الخفي لوحده", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        listOf(-1 to "لأ، أنا بمسحه بإيدي", 0 to "أول ما أخرج من التطبيق", 5 to "بعد 5 دقايق برّه التطبيق", 30 to "بعد نص ساعة برّه التطبيق").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { AppSettings.setPrivateAutoWipe(key) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = AppSettings.privateAutoWipe.value == key, onClick = { AppSettings.setPrivateAutoWipe(key) })
                Text(label)
            }
        }
        ListItem(
            headlineContent = { Text("امسح بيانات التصفح", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("السجل، الكوكيز، الكاش، وتسجيلات الدخول") },
            modifier = Modifier.clickable { confirmClear = true },
        )

        HorizontalDivider()
        Section("المكتبة")
        Text("حط التحميلات الجديدة في مجلدات", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        listOf("none" to "لأ، كله في مكان واحد", "type" to "حسب النوع (فيديوهات، أغاني، صور…)", "source" to "حسب الموقع (YouTube، TikTok…)").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { AppSettings.setAutoFolder(key) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = AppSettings.autoFolder.value == key, onClick = { AppSettings.setAutoFolder(key) })
                Text(label)
            }
        }
        SwitchRow("احفظ نسخة في الاستديو", "كل فيديو أو صورة أو أغنية تتحمّل تتحفظ كمان في الاستديو/الموسيقى", AppSettings.autoGallery.value) { AppSettings.setAutoGallery(it) }

        HorizontalDivider()
        Section("التورنت")
        SwitchRow("حمّل على الواي فاي بس", "يوقف التورنت لما تكون على باقة الموبايل", AppSettings.torrentWifiOnly.value) { AppSettings.setTorrentWifiOnly(it) }
        SwitchRow("شارك بعد ما يخلص", "يرفع لغيرك لحد ما ترفع قد اللي نزّلته أو ساعة — مقفول يعني يوقف على طول ويوفّر النت", AppSettings.torrentSeed.value) {
            AppSettings.setTorrentSeed(it); com.ali.files.torrent.TorrentEngine.applyLimits()
        }
        Text("أقصى سرعة تحميل", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        listOf(0 to "من غير حد", 512 to "512 كيلو/ث", 1024 to "1 ميجا/ث", 3072 to "3 ميجا/ث").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { AppSettings.setTorrentLimitKb(key); com.ali.files.torrent.TorrentEngine.applyLimits() }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = AppSettings.torrentLimitKb.value == key, onClick = { AppSettings.setTorrentLimitKb(key); com.ali.files.torrent.TorrentEngine.applyLimits() })
                Text(label)
            }
        }

        HorizontalDivider()
        Section("مساحة التطبيق")
        StorageSection(browser)

        HorizontalDivider()
        Section("الخزنة")
        if (biometricAvailable(ctx)) {
            SwitchRow("الفتح بالبصمة", null, AppSettings.biometric.value) { AppSettings.setBiometric(it) }
        }
        if (VaultLock.hasPin()) {
            ListItem(headlineContent = { Text("غيّر الرمز السري") }, modifier = Modifier.clickable { changePin = true })
        }
        ListItem(
            headlineContent = { Text("الحماية") },
            supportingContent = {
                Text("كل ملف بيتشفر AES-256 بمفتاح خاص بيه، والمفاتيح محمية بشريحة الأمان في الموبايل. الخزنة بتتقفل أول ما تخرج من التطبيق، وممنوع تصوير الشاشة جواها.")
            },
        )

        HorizontalDivider()
        Section("عن التطبيق")
        ListItem(headlineContent = { Text("ملفات علي") }, supportingContent = { Text("الإصدار ${BuildConfigInfo.version(ctx)} • المرحلة الأولى: المتصفح، المحمّل، الخزنة") })
    }

    if (confirmClear) {
        AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح بيانات التصفح؟") },
            text = { Text("هتخرج من كل المواقع اللي مسجل فيها.") },
            confirmButton = {
                TextButton(onClick = {
                    browser.clearBrowsingData(); confirmClear = false
                    Toast.makeText(ctx, "اتمسحت", Toast.LENGTH_SHORT).show()
                }) { Text("امسح", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
    if (changePin) ChangePinDialog { changePin = false }
}

@Composable
private fun ChangePinDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val kb = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
        onDismissRequest = onDismiss,
        title = { Text("تغيير الرمز") },
        text = {
            Column {
                OutlinedTextField(old, { old = it.filter(Char::isDigit).take(8) }, label = { Text("الرمز الحالي") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb)
                OutlinedTextField(new1, { new1 = it.filter(Char::isDigit).take(8) }, label = { Text("الرمز الجديد (4-8 أرقام)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && old.isNotEmpty() && new1.length >= 4, onClick = {
                working = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { VaultLock.check(old) }
                    if (ok) {
                        withContext(Dispatchers.Default) { VaultLock.setPin(new1) }
                        Toast.makeText(ctx, "اتغيّر الرمز", Toast.LENGTH_SHORT).show()
                        Vault.lock()
                        onDismiss()
                    } else {
                        error = "الرمز الحالي غلط"
                        working = false
                    }
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}


@Composable
private fun StorageSection(browser: BrowserController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<com.ali.files.util.StorageInfo.Item>?>(null) }
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        items = withContext(Dispatchers.IO) { com.ali.files.util.StorageInfo.items(ctx) }
    }
    val list = items
    if (list == null) {
        Text("بيحسب…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val total = list.sumOf { it.bytes }
    Text("المجموع: ${humanSize(total)}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    list.sortedByDescending { it.bytes }.forEach { it ->
        ListItem(
            headlineContent = { Text(it.title) },
            supportingContent = { Text(it.sub) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(humanSize(it.bytes), fontWeight = FontWeight.Bold)
                    if (it.clearable && it.bytes > 0) TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                com.ali.files.util.StorageInfo.clear(ctx, it.kind) {}
                            }
                            if (it.kind == com.ali.files.util.StorageInfo.Kind.WEB_CACHE) browser.clearWebCache()
                            Toast.makeText(ctx, "اتمسح ${it.title}", Toast.LENGTH_SHORT).show()
                            tick++
                        }
                    }) { Text("امسح") }
                }
            },
        )
    }
    Text(
        "الكاش بيتمسح لوحده لما يعدّي 200 ميجا.",
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun humanSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f جيجا", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f ميجا", b / (1L shl 20).toDouble())
    b >= 1024 -> "${b / 1024} كيلو"
    b > 0 -> "$b بايت"
    else -> "فاضي"
}
