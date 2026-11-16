package com.haitham.voiceassistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.haitham.voiceassistant.model.AssistantUiState
import com.haitham.voiceassistant.settings.AssistantSettings
import com.haitham.voiceassistant.ui.theme.Danger
import com.haitham.voiceassistant.ui.theme.Listening

data class SettingsActions(
    val setWakePhrase: (String) -> Unit,
    val setSensitivity: (Int) -> Unit,
    val setAutoStart: (Boolean) -> Unit,
    val setVoiceFeedback: (Boolean) -> Unit,
    val setKeepCpu: (Boolean) -> Unit,
    val setDual: (Boolean) -> Unit,
    val setTimeout: (Int) -> Unit,
    val setModel: (String) -> Unit,
    val importModel: (Uri) -> Unit,
    val deleteCustomModel: () -> Unit,
    val checkWakePhrase: (String) -> String,
    val requestPermissions: () -> Unit,
    val requestCallLog: () -> Unit,
    val runRecognitionTest: () -> Unit,
    val analyzeText: (String) -> String
)

@Composable
fun SettingsScreen(
    settings: AssistantSettings,
    state: AssistantUiState,
    hasCustomModel: Boolean,
    importStatus: String,
    refreshKey: Int,
    actions: SettingsActions,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var wake by remember(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
    var wakeCheck by remember { mutableStateOf("") }
    var analyzeInput by remember { mutableStateOf("اتصل بهيثم العريفي") }
    var analyzeOut by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(actions.importModel) }

    // Re-read system states when returning from system screens.
    var battery by remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(ctx)) }
    var overlay by remember { mutableStateOf(Permissions.canDrawOverlays(ctx)) }
    LaunchedEffect(refreshKey) {
        battery = Permissions.isIgnoringBatteryOptimizations(ctx)
        overlay = Permissions.canDrawOverlays(ctx)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().widthIn(max = 680.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
            Text("الإعدادات", style = MaterialTheme.typography.headlineSmall)
        }

        Section("التشغيل في الخلفية") {
            SwitchRow("التشغيل التلقائي بعد إعادة تشغيل الهاتف", settings.autoStartOnBoot, actions.setAutoStart)
            Hint(
                "أندرويد 8–10: يبدأ المساعد تلقائياً.\nأندرويد 11 فما فوق: يمنع النظام تشغيل الميكروفون من الخلفية بعد الإقلاع، " +
                    "لذلك سيظهر إشعار «فعّل المساعد الصوتي» وتكفي ضغطة واحدة عليه."
            )
            SwitchRow("إبقاء المعالج نشطاً والشاشة مغلقة", settings.keepCpuAwake, actions.setKeepCpu)
            StatusRow("استثناء من تحسين البطارية", battery) { Permissions.requestIgnoreBatteryOptimizations(ctx) }
            StatusRow("الظهور فوق التطبيقات (لأمر «افتح التطبيق»)", overlay) { Permissions.requestOverlay(ctx) }
            Hint("بعض الشركات (شاومي، هواوي، أوبو، سامسونج) لديها إعدادات إضافية: اسمح بـ«التشغيل التلقائي» و«بدون قيود» للبطارية من إعدادات التطبيق.")
            OutlinedButton(onClick = { Permissions.openAppSettings(ctx) }) { Text("فتح إعدادات التطبيق في النظام") }
        }

        Section("كلمة التنبيه") {
            OutlinedTextField(wake, { wake = it }, label = { Text("كلمة التنبيه") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { wakeCheck = actions.checkWakePhrase(wake); actions.setWakePhrase(wake) }) { Text("حفظ") }
                OutlinedButton(onClick = { wake = AssistantSettings.DEFAULT_WAKE_PHRASE; actions.setWakePhrase(wake); wakeCheck = "" }) { Text("الافتراضي «يا مساعد»") }
            }
            if (wakeCheck.isNotBlank()) Hint(wakeCheck)
            Hint("استخدم كلمتين مميزتين (مثل «يا مساعد» أو «يا هيثم»). يجب أن تكون الكلمات موجودة في قاموس النموذج الصوتي.")
            Text("حساسية الميكروفون وكلمة التنبيه", style = MaterialTheme.typography.bodyLarge)
            val labels = listOf("منخفضة", "متوسطة", "عالية")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { i, l ->
                    SegmentedButton(selected = settings.sensitivity == i, onClick = { actions.setSensitivity(i) }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
                }
            }
            Hint("منخفضة: تنبيهات خاطئة أقل في الضوضاء. عالية: يستيقظ بسهولة أكبر (وقد يكفي قول «مساعد» وحدها).")
        }

        Section("التعرف على الأوامر") {
            SwitchRow("الرد الصوتي (محرك النطق في الهاتف دون إنترنت)", settings.voiceFeedback, actions.setVoiceFeedback)
            SwitchRow("تعرف مزدوج (قواعد + تعرف حر) – أدق وأكثر استهلاكاً", settings.dualRecognition, actions.setDual)
            Text("أقصى مدة لاستقبال الأمر: ${settings.commandTimeoutSec} ثوانٍ", style = MaterialTheme.typography.bodyLarge)
            Slider(settings.commandTimeoutSec.toFloat(), { actions.setTimeout(it.toInt()) }, valueRange = 4f..15f, steps = 10)
        }

        Section("النموذج الصوتي") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.modelId == AssistantSettings.MODEL_BUNDLED, { actions.setModel(AssistantSettings.MODEL_BUNDLED) })
                Text("النموذج العربي المضمّن (Vosk، يدعم القواعد، ~290 ميجابايت)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.modelId == AssistantSettings.MODEL_CUSTOM, { if (hasCustomModel) actions.setModel(AssistantSettings.MODEL_CUSTOM) }, enabled = hasCustomModel)
                Text(if (hasCustomModel) "نموذج مستورد" else "نموذج مستورد (لم يُستورد بعد)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("استيراد نموذج Vosk (zip)") }
                if (hasCustomModel) OutlinedButton(onClick = actions.deleteCustomModel) { Text("حذف المستورد") }
            }
            if (importStatus.isNotBlank()) Hint(importStatus)
            if (state.modelInfo.isNotBlank()) Hint("النموذج الحالي: ${state.modelInfo}")
            Hint("يعاد تشغيل المساعد تلقائياً عند تغيير النموذج.")
        }

        Section("الأذونات") {
            Permissions.items.forEach { p ->
                val g = Permissions.granted(ctx, p.permission)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (g) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (g) Listening else Danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyLarge)
                        Text(p.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.requestPermissions) { Text("منح الأذونات") }
                OutlinedButton(onClick = actions.requestCallLog) { Text("سجل المكالمات") }
            }
        }

        Section("اختبار التعرف على الكلام") {
            Hint("يشغّل المساعد إن لم يكن يعمل، ثم يستمع 6 ثوانٍ ويعرض النص الذي فهمه دون تنفيذ أي اتصال.")
            Button(onClick = actions.runRecognitionTest) { Text("ابدأ الاختبار وتكلم") }
            if (state.testTranscript.isNotBlank()) Hint(state.testTranscript)
            Spacer(Modifier.height(6.dp))
            Text("اختبار محلل الأوامر (بالكتابة)", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(analyzeInput, { analyzeInput = it }, modifier = Modifier.fillMaxWidth(), label = { Text("اكتب أمراً") })
            Button(onClick = { analyzeOut = actions.analyzeText(analyzeInput) }) { Text("حلّل (بدون اتصال)") }
            if (analyzeOut.isNotBlank()) Hint(analyzeOut)
        }

        Section("الخصوصية") {
            Hint(
                "• التطبيق لا يملك صلاحية الإنترنت إطلاقاً، فلا يمكنه إرسال أي شيء.\n" +
                    "• الصوت يُعالج في الذاكرة فقط ولا يُحفظ أي تسجيل.\n" +
                    "• يظهر إشعار دائم ومؤشر الميكروفون من النظام طوال مدة الاستماع."
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth().widthIn(max = 680.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (ok) Listening else Danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (!ok) OutlinedButton(onClick = onFix) { Text("تفعيل") }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
