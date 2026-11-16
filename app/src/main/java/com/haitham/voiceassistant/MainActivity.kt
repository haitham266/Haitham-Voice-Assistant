package com.haitham.voiceassistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.haitham.voiceassistant.assistant.AssistantRepository
import com.haitham.voiceassistant.assistant.CommandResolver
import com.haitham.voiceassistant.contacts.ContactMatcher
import com.haitham.voiceassistant.contacts.ContactsRepository
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.nlu.ArabicCommandParser
import com.haitham.voiceassistant.service.AssistantNotifications
import com.haitham.voiceassistant.service.VoiceForegroundService
import com.haitham.voiceassistant.settings.AssistantSettings
import com.haitham.voiceassistant.settings.SettingsRepository
import com.haitham.voiceassistant.speech.GrammarBuilder
import com.haitham.voiceassistant.speech.ModelManager
import com.haitham.voiceassistant.speech.Vocabulary
import com.haitham.voiceassistant.ui.MainScreen
import com.haitham.voiceassistant.ui.Permissions
import com.haitham.voiceassistant.ui.SettingsActions
import com.haitham.voiceassistant.ui.SettingsScreen
import com.haitham.voiceassistant.ui.theme.HaithamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_START_ASSISTANT = "start_assistant"
    }

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var modelManager: ModelManager
    private var permissionsVersion by mutableIntStateOf(0)
    private var showSettings by mutableStateOf(false)
    private var importStatus by mutableStateOf("")
    private var hasCustomModel by mutableStateOf(false)
    private var startAfterPermissions = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsVersion++
        if (startAfterPermissions) {
            startAfterPermissions = false
            if (Permissions.granted(this, Manifest.permission.RECORD_AUDIO)) VoiceForegroundService.start(this)
            else Toast.makeText(this, "لا يمكن تشغيل المساعد دون صلاحية الميكروفون", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsRepo = SettingsRepository(this)
        modelManager = ModelManager(this)
        hasCustomModel = modelManager.hasCustomModel()
        AssistantNotifications.createChannels(this)
        handleIntent(intent)

        setContent {
            HaithamTheme {
                val state by AssistantRepository.state.collectAsStateWithLifecycle()
                val settings by settingsRepo.settings.collectAsStateWithLifecycle(AssistantSettings())
                val pv = permissionsVersion
                val missing = remember(pv) { Permissions.missingRequired(this) }
                BackHandler(enabled = showSettings) { showSettings = false }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    if (!showSettings) {
                        MainScreen(
                            state = state, wakePhrase = settings.wakePhrase, missingPermissions = missing,
                            onToggle = { toggleAssistant(state.serviceRunning) },
                            onGrantPermissions = { requestPermissions(false) },
                            onOpenSettings = { showSettings = true }
                        )
                    } else {
                        SettingsScreen(
                            settings = settings, state = state, hasCustomModel = hasCustomModel,
                            importStatus = importStatus, refreshKey = pv, actions = settingsActions(),
                            onBack = { showSettings = false }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsVersion++ // re-check permissions / battery / overlay when coming back
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_ASSISTANT, false) == true) {
            intent.removeExtra(EXTRA_START_ASSISTANT)
            startAssistant()
        }
    }

    private fun toggleAssistant(running: Boolean) {
        if (running) VoiceForegroundService.stop(this) else startAssistant()
    }

    private fun startAssistant() {
        if (Permissions.missingRequired(this).isNotEmpty()) requestPermissions(true)
        else VoiceForegroundService.start(this)
    }

    private fun requestPermissions(thenStart: Boolean) {
        startAfterPermissions = thenStart
        permissionLauncher.launch(Permissions.requiredPermissions)
    }

    private fun restartIfRunning() {
        if (!AssistantRepository.state.value.serviceRunning) return
        VoiceForegroundService.stop(this)
        window.decorView.postDelayed({ VoiceForegroundService.start(this) }, 1200)
    }

    private fun settingsActions() = SettingsActions(
        setWakePhrase = { v -> lifecycleScope.launch { settingsRepo.setWakePhrase(v.ifBlank { AssistantSettings.DEFAULT_WAKE_PHRASE }) } },
        setSensitivity = { v -> lifecycleScope.launch { settingsRepo.setSensitivity(v) } },
        setAutoStart = { v -> lifecycleScope.launch { settingsRepo.setAutoStart(v) } },
        setVoiceFeedback = { v -> lifecycleScope.launch { settingsRepo.setVoiceFeedback(v) } },
        setKeepCpu = { v -> lifecycleScope.launch { settingsRepo.setKeepCpuAwake(v) } },
        setDual = { v -> lifecycleScope.launch { settingsRepo.setDualRecognition(v) } },
        setTimeout = { v -> lifecycleScope.launch { settingsRepo.setCommandTimeout(v) } },
        setModel = { v -> lifecycleScope.launch { settingsRepo.setModel(v); restartIfRunning() } },
        importModel = { uri ->
            importStatus = "جارٍ الاستيراد…"
            lifecycleScope.launch {
                importStatus = try {
                    val info = withContext(Dispatchers.IO) {
                        modelManager.importZip(uri) { b -> importStatus = "جارٍ الاستيراد… ${b / (1024 * 1024)} ميجابايت" }
                    }
                    hasCustomModel = true
                    settingsRepo.setModel(AssistantSettings.MODEL_CUSTOM)
                    restartIfRunning()
                    "تم استيراد ${info.name} (${info.sizeMb} ميجابايت)" + if (!info.supportsGrammar) "\nتنبيه: هذا النموذج لا يدعم القواعد؛ سيعمل بالتعرف الحر فقط وسيستهلك معالجاً أكثر." else ""
                } catch (e: Exception) { "فشل الاستيراد: ${e.message}" }
            }
        },
        deleteCustomModel = {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { modelManager.deleteCustom() }
                hasCustomModel = false
                settingsRepo.setModel(AssistantSettings.MODEL_BUNDLED)
                restartIfRunning()
            }
        },
        checkWakePhrase = { phrase -> checkWakePhrase(phrase) },
        requestPermissions = { requestPermissions(false) },
        requestCallLog = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALL_LOG)) },
        runRecognitionTest = {
            if (!Permissions.granted(this, Manifest.permission.RECORD_AUDIO)) requestPermissions(false)
            else if (AssistantRepository.state.value.serviceRunning) VoiceForegroundService.start(this, VoiceForegroundService.ACTION_TEST)
            else {
                AssistantRepository.test("جارٍ تشغيل المساعد… أعد الضغط على زر الاختبار بعد أن تصبح الحالة «نشط».")
                VoiceForegroundService.start(this)
            }
        },
        analyzeText = { text -> analyze(text) }
    )

    private fun currentModelDir(): File {
        val id = if (hasCustomModel && kotlinx.coroutines.runBlocking { settingsRepo.current().modelId } == AssistantSettings.MODEL_CUSTOM) "custom" else "bundled"
        return File(filesDir, "models/$id")
    }

    private fun checkWakePhrase(phrase: String): String {
        val words = File(currentModelDir(), "graph/words.txt")
        if (!words.exists()) return "سيتم التحقق من الكلمات بعد أول تشغيل للمساعد (تجهيز النموذج)."
        val g = GrammarBuilder.wakeGrammar(phrase, Vocabulary(words))
        return if (g.missingWords.isEmpty()) "✓ كل الكلمات موجودة في قاموس النموذج: «${g.phrase}»"
        else "✗ كلمات غير موجودة في القاموس: ${g.missingWords.joinToString("، ")} — جرّب كتابة أخرى أو كلمة مختلفة."
    }

    private fun analyze(text: String): String {
        val parser = ArabicCommandParser(kotlinx.coroutines.runBlocking { settingsRepo.current().wakePhrase })
        val repo = ContactsRepository(this)
        val matcher = ContactMatcher().apply { setContacts(repo.contacts()) }
        val intent = parser.parse(text)
        val decision = CommandResolver(parser, matcher).resolveCommand(
            listOf(Hypothesis(text, text.split(' ').map { com.haitham.voiceassistant.model.RecognizedWord(it, 1f, 0f, 0f) }, Hypothesis.Source.FREE))
        )
        val contactsNote = if (!repo.hasPermission()) "\n(صلاحية جهات الاتصال غير ممنوحة)" else "\nعدد جهات الاتصال: ${repo.contacts().size}"
        return "النية: $intent\nالقرار: $decision$contactsNote"
    }
}
