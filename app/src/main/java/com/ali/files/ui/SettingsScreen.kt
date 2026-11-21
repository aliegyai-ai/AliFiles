package com.ali.files.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
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
        title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
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
fun SettingsScreen(browser: BrowserController) {
    val ctx = LocalContext.current
    var changePin by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("الإعدادات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))

        Section("التحميل")
        Text("عدد الخيوط الافتراضي: ${AppSettings.threads.intValue}", Modifier.padding(horizontal = 16.dp))
        Slider(
            value = AppSettings.threads.intValue.toFloat(),
            onValueChange = { AppSettings.setThreads(it.toInt()) },
            valueRange = 1f..16f, steps = 14,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SwitchRow("احفظ التحميلات في الخزنة تلقائياً", "تقدر تغيّرها لكل تحميل", AppSettings.saveToVault.value) { AppSettings.setSaveToVault(it) }

        HorizontalDivider()
        Section("المتصفح")
        SwitchRow("مانع الإعلانات والنوافذ المنبثقة", "بيقفل شبكات الإعلانات والتتبع", AppSettings.adBlock.value) { AppSettings.setAdBlock(it) }
        SwitchRow("التقاط الروابط من الحافظة", "لما تنسخ لينك يعرض عليك تحمّله", AppSettings.clipboardDetect.value) { AppSettings.setClipboardDetect(it) }
        Text("محرك البحث", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        listOf("google" to "Google", "ddg" to "DuckDuckGo", "bing" to "Bing").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { AppSettings.setSearchEngine(key) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = AppSettings.searchEngine.value == key, onClick = { AppSettings.setSearchEngine(key) })
                Text(label)
            }
        }
        ListItem(
            headlineContent = { Text("امسح بيانات التصفح", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("السجل، الكوكيز، الكاش، وتسجيلات الدخول") },
            modifier = Modifier.clickable { confirmClear = true },
        )

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
        AlertDialog(
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
    AlertDialog(
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
