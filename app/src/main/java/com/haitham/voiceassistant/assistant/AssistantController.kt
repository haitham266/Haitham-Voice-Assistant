package com.haitham.voiceassistant.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.haitham.voiceassistant.MainActivity
import com.haitham.voiceassistant.audio.FeedbackPlayer
import com.haitham.voiceassistant.audio.VoiceAudioManager
import com.haitham.voiceassistant.call.CallManager
import com.haitham.voiceassistant.contacts.ContactMatcher
import com.haitham.voiceassistant.contacts.ContactsRepository
import com.haitham.voiceassistant.model.AssistantStatus
import com.haitham.voiceassistant.model.ContactCandidate
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.nlu.ArabicCommandParser
import com.haitham.voiceassistant.service.AssistantNotifications
import com.haitham.voiceassistant.settings.AssistantSettings
import com.haitham.voiceassistant.settings.SettingsRepository
import com.haitham.voiceassistant.speech.GrammarBuilder
import com.haitham.voiceassistant.speech.ModelManager
import com.haitham.voiceassistant.speech.OnDeviceRecognizer
import com.haitham.voiceassistant.speech.WhisperRecognizer
import com.haitham.voiceassistant.speech.SpeechRecognitionEngine
import com.haitham.voiceassistant.speech.SpeechRecognitionEngine.Purpose
import com.haitham.voiceassistant.speech.Vocabulary
import com.haitham.voiceassistant.speech.WakeWordDetector
import kotlinx.coroutines.runBlocking
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.util.concurrent.Executors

/**
 * Orchestrates: wake word -> command -> contact matching -> call -> back to wake word.
 * Runs inside [com.haitham.voiceassistant.service.VoiceForegroundService].
 */
class AssistantController(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val onStopRequested: () -> Unit,
    private val onStatus: (AssistantStatus, String?) -> Unit
) : SpeechRecognitionEngine.Listener {

    private val tag = "Assistant"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val audio = VoiceAudioManager(context)
    private val contactsRepo = ContactsRepository(context)
    private val callManager = CallManager(context)
    private val feedback = FeedbackPlayer(context)
    private val matcher = ContactMatcher(minScore = 0.55)
    private val dedup = CommandDeduplicator()

    private var settings = AssistantSettings()
    private var parser = ArabicCommandParser()
    private var resolver = CommandResolver(parser, matcher)
    private var model: Model? = null
    private var engine: SpeechRecognitionEngine? = null
    private var vocab: Vocabulary? = null
    private var supportsGrammar = true
    @Volatile private var wakeGrammar: String? = null
    @Volatile private var commandGrammar: String? = null
    private var wakeDetector = WakeWordDetector(AssistantSettings.DEFAULT_WAKE_PHRASE, 1)
    @Volatile private var triggerGrammar: String? = null
    private var triggerDetector = WakeWordDetector(AssistantSettings.DEFAULT_WAKE_PHRASE, 1, GrammarBuilder.triggerWordsNormalized)
    @Volatile private var contactNames: List<String> = emptyList()

    /** The phone's on-device AI recogniser (used for the command itself). */
    private val onDevice = OnDeviceRecognizer(context)

    /** Whisper Turbo inside the app: the default engine for understanding commands. */
    private val whisper = WhisperRecognizer(context)

    private fun whisperUsable() = settings.commandEngine == AssistantSettings.ENGINE_WHISPER && whisper.isLoaded

    /** Loads Whisper on the worker thread if it is the selected engine. */
    private fun ensureWhisperLoaded() {
        if (settings.commandEngine != AssistantSettings.ENGINE_WHISPER || whisper.isLoaded) return
        if (!WhisperRecognizer.isBundled(context)) { AssistantRepository.aiInfo("Whisper غير موجود في هذه النسخة"); return }
        onStatus(AssistantStatus.LOADING_MODEL, "تحميل Whisper (الذكاء الاصطناعي)…")
        val ok = whisper.load()
        AssistantRepository.aiInfo(if (ok) "Whisper Turbo ✓ يعمل داخل الهاتف دون إنترنت" else "تعذر تحميل Whisper — سيُستخدم Vosk")
    }

    /** One command round: results of the AI recogniser and of Vosk, merged when both are in. */
    private class Session(val purpose: Purpose) {
        var ai = false          // AI recogniser running for this round
        var micMode = false     // AI recogniser uses the mic itself (Vosk not running)
        var whisper = false     // the AI part is Whisper (slower to finish than the phone's engine)
        var aiHyps: List<Hypothesis>? = null
        var aiFailed = false
        var voskHyps: List<Hypothesis>? = null
        var done = false
    }
    private var session: Session? = null

    private enum class Pending { NONE, CHOICE, CONFIRM_CONTACT, CONFIRM_NUMBER }
    private var pending = Pending.NONE
    private var pendingCandidates: List<ContactCandidate> = emptyList()
    private var pendingNumber: String? = null
    private var retries = 0
    @Volatile private var released = false

    // ------------------------------------------------------------------ lifecycle
    fun start() {
        onStatus(AssistantStatus.LOADING_MODEL, null)
        worker.execute {
            try {
                settings = runBlocking { settingsRepo.current() }
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val mm = ModelManager(context)
                val dir = mm.resolve(settings.modelId) { p -> onStatus(AssistantStatus.LOADING_MODEL, "تجهيز النموذج لأول مرة… $p%") }
                val info = mm.describe(dir)
                supportsGrammar = info.supportsGrammar
                AssistantRepository.modelInfo("${info.name} • ${info.sizeMb} ميجابايت" + if (info.supportsGrammar) "" else " • بدون دعم القواعد")
                vocab = Vocabulary(File(dir, "graph/words.txt"))
                val t0 = SystemClock.elapsedRealtime()
                model = Model(dir.absolutePath)
                Log.i(tag, "Model loaded in ${SystemClock.elapsedRealtime() - t0} ms")
                if (released) return@execute
                rebuildGrammars()
                ensureWhisperLoaded()
                contactsRepo.onChanged = { worker.execute { rebuildCommandGrammar() } }
                main.post { contactsRepo.startObserving() }
                engine = SpeechRecognitionEngine(audio, model!!, supportsGrammar, this).also { it.start() }
                main.post {
                    when (settings.commandEngine) {
                        AssistantSettings.ENGINE_AI -> AssistantRepository.aiInfo("محرك الهاتف: ${settings.aiEngine.substringAfterLast('.')} • ${settings.aiLocale}")
                        AssistantSettings.ENGINE_VOSK -> AssistantRepository.aiInfo("Vosk فقط")
                        else -> Unit
                    }
                    backToWake()
                }
            } catch (t: Throwable) {
                Log.e(tag, "start failed", t)
                AssistantRepository.error("تعذر تشغيل المحرك الصوتي: ${t.message}")
                onStatus(AssistantStatus.ERROR, t.message)
            }
        }
    }

    fun release() {
        released = true
        contactsRepo.stopObserving()
        worker.execute {
            engine?.stop(); engine = null
            model?.close(); model = null
            whisper.release()
        }
        worker.shutdown()
        main.post { onDevice.shutdown() }
        feedback.release()
    }

    /** Called when settings changed while running. */
    fun applySettings(new: AssistantSettings) {
        val old = settings
        settings = new
        if (old.confirmWhenUnsure != new.confirmWhenUnsure) {
            resolver = CommandResolver(parser, matcher, askWhenUnsure = new.confirmWhenUnsure)
        }
        if (old.wakePhrase != new.wakePhrase || old.sensitivity != new.sensitivity) {
            worker.execute { rebuildGrammars(); if (pending == Pending.NONE) main.post { backToWake() } }
        } else if (old.requireWakeWord != new.requireWakeWord || old.commandEngine != new.commandEngine ||
            old.aiEngine != new.aiEngine || old.aiLocale != new.aiLocale) {
            aiBroken = false
            if (new.commandEngine == AssistantSettings.ENGINE_WHISPER && !whisper.isLoaded) {
                worker.execute { ensureWhisperLoaded(); main.post { if (pending == Pending.NONE && session == null) backToWake() } }
                return
            }
            main.post { if (pending == Pending.NONE && session == null) backToWake() }
        }
    }

    private fun rebuildGrammars() {
        val v = vocab ?: return
        parser = ArabicCommandParser(settings.wakePhrase)
        resolver = CommandResolver(parser, matcher, askWhenUnsure = settings.confirmWhenUnsure)
        wakeDetector = WakeWordDetector(settings.wakePhrase, settings.sensitivity)
        triggerDetector = WakeWordDetector(settings.wakePhrase, settings.sensitivity, GrammarBuilder.triggerWordsNormalized)
        triggerGrammar = if (supportsGrammar) GrammarBuilder.triggerGrammar(v) else null
        if (supportsGrammar) {
            val wg = GrammarBuilder.wakeGrammar(settings.wakePhrase, v)
            if (wg.missingWords.isNotEmpty()) {
                AssistantRepository.result("تنبيه: الكلمات غير موجودة في قاموس النموذج: ${wg.missingWords.joinToString("، ")}")
            }
            wakeGrammar = wg.json
        } else wakeGrammar = null
        rebuildCommandGrammar()
    }

    private fun rebuildCommandGrammar() {
        val v = vocab ?: return
        contactsRepo.invalidate()
        val contacts = contactsRepo.contacts()
        matcher.setContacts(contacts)
        contactNames = contacts.map { it.displayName }.filter { it.isNotBlank() }
        commandGrammar = if (supportsGrammar) {
            GrammarBuilder.commandGrammar(contacts.map { it.displayName } + settings.wakePhrase, v)
        } else null
        Log.i(tag, "Command grammar ready for ${contacts.size} contacts")
    }

    private fun backToWake() {
        pending = Pending.NONE; pendingCandidates = emptyList(); pendingNumber = null; retries = 0
        session = null
        if (settings.requireWakeWord) engine?.listenForWake(wakeGrammar, wakeDetector, settings.sensitivity)
        else engine?.listenForWake(
            triggerGrammar, triggerDetector, settings.sensitivity,
            // With the AI recogniser every utterance is checked by it (much more accurate than
            // spotting "اتصل" with Vosk); without it, Vosk spots the call words.
            triggerOnSpeech = whisperUsable() ||
                (aiUsable() && !onDevice.audioSourceUnsupported && android.os.Build.VERSION.SDK_INT >= 33)
        )
        onStatus(waitingStatus(), null)
    }

    private fun waitingStatus() =
        if (settings.requireWakeWord) AssistantStatus.WAITING_WAKE_WORD else AssistantStatus.WAITING_DIRECT_COMMAND

    /** Set when the chosen recogniser really refused Arabic during use (not from its language list). */
    @Volatile private var aiBroken = false

    private fun aiUsable(): Boolean {
        if (settings.commandEngine != AssistantSettings.ENGINE_AI || aiBroken) return false
        onDevice.engineId = settings.aiEngine
        onDevice.locale = settings.aiLocale
        return if (settings.aiEngine == OnDeviceRecognizer.ENGINE_SYSTEM) OnDeviceRecognizer.isAvailable(context) else true
    }

    private fun biasing(): List<String> =
        contactNames.take(380) + listOf("اتصل ب", "دق على", "كلم", "اتصل بالرقم", "أعد الاتصال", "أوقف الاستماع", "افتح التطبيق")

    /**
     * Starts listening to the command: the phone's AI recogniser (fed with our audio,
     * including what was already said) in parallel with Vosk as a fallback.
     */
    private fun startCommand(purpose: Purpose, replay: Boolean, muteMs: Long) {
        val e = engine ?: return
        val s = Session(purpose)
        session = s
        val timeout = if (purpose == Purpose.TEST) 6000L else settings.commandTimeoutSec * 1000L
        if (whisperUsable()) {
            // Whisper gets the whole command (including what was said before the hand-off).
            s.ai = true; s.whisper = true
            val sink = whisper.Session { hyps, _ -> main.post { s.aiHyps = hyps; tryFinish(s) } }
            e.listen(purpose, commandGrammar, useFree = false, timeoutMs = timeout, replay = replay, muteMs = muteMs, sink = sink)
            return
        }
        val useAi = aiUsable()
        if (useAi && !onDevice.audioSourceUnsupported && android.os.Build.VERSION.SDK_INT >= 33) {
            s.ai = true
            val stream = onDevice.start(streamed = true, biasing = biasing(), cb = aiCallback(s))
            e.listen(purpose, commandGrammar, settings.dualRecognition, timeout, replay = replay, muteMs = muteMs, sink = stream)
        } else if (useAi) {
            // This phone does not accept streamed audio: hand the microphone to the AI recogniser.
            s.ai = true; s.micMode = true
            e.releaseMic()
            main.postDelayed({ if (session === s) onDevice.start(streamed = false, biasing = biasing(), cb = aiCallback(s)) }, 150)
        } else {
            e.listen(purpose, commandGrammar, settings.dualRecognition, timeout, replay = replay, muteMs = muteMs)
        }
    }

    private fun aiCallback(s: Session) = object : OnDeviceRecognizer.Callback {
        override fun onPartial(text: String) { if (session === s) AssistantRepository.heard(text) }
        override fun onResults(hyps: List<Hypothesis>) = main.post { s.aiHyps = hyps; tryFinish(s) }.let {}
        override fun onError(code: Int, audioSourceRefused: Boolean) = main.post {
            Log.w(tag, "AI recogniser error $code (refused=$audioSourceRefused)")
            if (code == android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                code == android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                aiBroken = true
                AssistantRepository.aiInfo("المحرك المختار رفض العربية (${settings.aiLocale}) — اضغط «فحص بصوتي» في الإعدادات")
            }
            s.aiFailed = true; tryFinish(s)
        }.let {}
    }

    private fun tryFinish(s: Session) {
        if (s.done || session !== s) return
        val aiDone = !s.ai || s.aiHyps != null || s.aiFailed
        val voskDone = s.micMode || s.voskHyps != null
        if (!aiDone || !voskDone) return
        s.done = true
        session = null
        finishSession(s)
    }

    private fun actionable(d: Decision) = d !is Decision.NotUnderstood && d !is Decision.NothingHeard && d !is Decision.NotFound

    private fun finishSession(s: Session) {
        val ai = s.aiHyps.orEmpty()
        val vosk = s.voskHyps.orEmpty()
        val shown = (ai.firstOrNull() ?: vosk.firstOrNull())?.text.orEmpty()
        AssistantRepository.heard(if (shown.isBlank()) "(لم يُسمع كلام واضح)" else shown)
        onStatus(AssistantStatus.PROCESSING, null)
        if (s.purpose == Purpose.TEST) {
            val lines = (ai + vosk).map {
                val src = when (it.source) {
                    Hypothesis.Source.ON_DEVICE -> if (s.whisper) "Whisper" else "محرك الهاتف"
                    Hypothesis.Source.GRAMMAR -> "Vosk مقيد"
                    else -> "Vosk حر"
                }
                "[$src] ${it.text} → ${resolver.resolveCommand(listOf(it))}"
            }
            AssistantRepository.test(
                if (lines.isEmpty()) "لم يُسمع كلام" + if (s.aiFailed) " (محرك الهاتف لم يرجع نتيجة)" else "" else lines.joinToString("\n")
            )
            backToWake(); return
        }
        // The AI recogniser decides first; Vosk is only a fallback.
        val dAi = if (ai.isNotEmpty()) resolver.resolveCommand(ai) else null
        val dVosk = if (vosk.isNotEmpty()) resolver.resolveCommand(vosk) else null
        val d = when {
            dAi != null && actionable(dAi) -> dAi
            dVosk != null && actionable(dVosk) -> dVosk
            else -> dAi ?: dVosk ?: Decision.NothingHeard
        }
        if (!settings.requireWakeWord && !actionable(d)) {
            // Direct mode: ordinary conversation is ignored silently.
            Log.i(tag, "direct mode: ignored '$shown' ($d)")
            backToWake(); return
        }
        execute(d)
    }

    fun runRecognitionTest() {
        val e = engine ?: return
        pending = Pending.NONE
        AssistantRepository.test("تكلم الآن…")
        e.idle()
        val beep = feedback.readyBeep()
        startCommand(Purpose.TEST, replay = false, muteMs = beep)
        onStatus(AssistantStatus.LISTENING_COMMAND, "اختبار التعرف")
    }

    // ------------------------------------------------------------- engine events
    override fun onLevel(level: Float) = AssistantRepository.level(level)

    override fun onPartial(text: String) = AssistantRepository.heard(text)

    override fun onWake(confidence: Float, trailingSpeech: Boolean) = main.post {
        Log.i(tag, "wake (conf=$confidence, trailing=$trailingSpeech)")
        AssistantRepository.heard("…")
        // Direct mode: the user is already saying the command -> no beep. Wake mode: beep,
        // and never drop audio for the beep if the command is said in the same breath.
        val beep = if (settings.requireWakeWord) feedback.readyBeep() else 0L
        startCommand(Purpose.COMMAND, replay = trailingSpeech, muteMs = if (trailingSpeech) 0 else beep)
        onStatus(AssistantStatus.LISTENING_COMMAND, null)
    }.let {}

    override fun onCommand(purpose: Purpose, hypotheses: List<Hypothesis>) = main.post {
        val s = session
        if (s != null && s.purpose == purpose && purpose != Purpose.CHOICE) {
            s.voskHyps = hypotheses
            if (s.ai && s.aiHyps == null && !s.aiFailed) {
                onStatus(AssistantStatus.PROCESSING, null)
                // Speech ended (stream closed): give the AI recogniser a moment to finalise.
                main.postDelayed({ if (!s.done && s.aiHyps == null) { onDevice.cancel(); s.aiFailed = true; tryFinish(s) } }, if (s.whisper) 12000 else 2500)
            }
            tryFinish(s)
            return@post
        }
        val text = hypotheses.firstOrNull()?.text.orEmpty()
        AssistantRepository.heard(if (text.isBlank()) "(لم يُسمع كلام واضح)" else text)
        onStatus(AssistantStatus.PROCESSING, null)
        when (purpose) {
            Purpose.TEST -> {
                AssistantRepository.test(
                    if (hypotheses.isEmpty()) "لم يُسمع كلام" else hypotheses.joinToString("\n") {
                        val src = if (it.source == Hypothesis.Source.GRAMMAR) "مقيد بالقواعد" else "حر"
                        "[$src] ${it.text}  (ثقة ${"%.2f".format(it.meanConfidence)}) → ${parser.parse(it.text)}"
                    }
                )
                backToWake()
            }
            Purpose.COMMAND -> execute(resolver.resolveCommand(hypotheses))
            Purpose.CHOICE -> handleAnswer(hypotheses)
        }
    }.let {}

    override fun onCallStateChanged(inCall: Boolean) = main.post {
        if (inCall) onStatus(AssistantStatus.PAUSED_IN_CALL, null)
        else if (AssistantRepository.state.value.status == AssistantStatus.PAUSED_IN_CALL) backToWake()
    }.let {}

    override fun onMicBlocked(blocked: Boolean) = main.post {
        if (blocked) onStatus(AssistantStatus.MIC_BLOCKED, null)
        else if (AssistantRepository.state.value.status == AssistantStatus.MIC_BLOCKED) onStatus(waitingStatus(), null)
    }.let {}

    override fun onFatal(message: String) = main.post {
        AssistantRepository.error("توقف المحرك: $message")
        onStatus(AssistantStatus.ERROR, message)
    }.let {}

    // ----------------------------------------------------------------- decisions
    private fun execute(d: Decision) {
        Log.i(tag, "decision: $d")
        when (d) {
            is Decision.CallCandidate -> placeCall(d.candidate.phone.number, d.candidate.contact.displayName)
            is Decision.CallDigits -> placeCall(d.digits, null)
            is Decision.ConfirmDigits -> {
                pending = Pending.CONFIRM_NUMBER; pendingNumber = d.digits
                ask("هل تريد الاتصال بالرقم ${spellDigits(d.digits)}؟ قل نعم أو لا", "تأكيد الرقم ${d.digits}")
            }
            is Decision.AskChoice -> {
                pending = Pending.CHOICE; pendingCandidates = d.candidates
                val ordinals = listOf("الأول", "الثاني", "الثالث", "الرابع")
                val list = d.candidates.mapIndexed { i, c -> "${ordinals[i]}: ${c.contact.displayName}" }.joinToString("، ")
                ask("وجدت أكثر من شخص. $list. من تقصد؟", "أكثر من تطابق: $list")
            }
            is Decision.AskConfirm -> {
                pending = Pending.CONFIRM_CONTACT; pendingCandidates = listOf(d.candidate)
                ask("هل تقصد ${d.candidate.contact.displayName}؟ قل نعم أو لا", "هل تقصد ${d.candidate.contact.displayName}؟")
            }
            Decision.Redial -> {
                val n = callManager.lastOutgoingFromCallLog() ?: settings.lastDialedNumber.takeIf { it.isNotBlank() }
                if (n == null) fail("لا يوجد رقم سابق لإعادة الاتصال") else placeCall(n, null)
            }
            Decision.Stop -> {
                AssistantRepository.result("تم إيقاف الاستماع")
                feedback.say("تم إيقاف الاستماع", settings.voiceFeedback) { onStopRequested() }
            }
            Decision.OpenApp -> { openApp(); backToWake() }
            Decision.Cancelled -> { AssistantRepository.result("تم الإلغاء"); feedback.say("تم الإلغاء", settings.voiceFeedback) { backToWake() } }
            is Decision.NotFound -> fail("لم أجد جهة اتصال باسم «${d.name}»")
            is Decision.NotUnderstood -> fail("لم أفهم الأمر")
            Decision.NothingHeard -> { feedback.errorBeep(); AssistantRepository.result("لم أسمع أمراً"); backToWake() }
        }
    }

    private fun handleAnswer(hyps: List<Hypothesis>) {
        when (pending) {
            Pending.CHOICE -> when (val d = resolver.resolveChoice(hyps, pendingCandidates)) {
                is Decision.CallCandidate, Decision.Cancelled -> execute(d)
                else -> retryOrCancel("لم أفهم اختيارك. قل الأول أو الثاني أو الاسم كاملاً")
            }
            Pending.CONFIRM_CONTACT, Pending.CONFIRM_NUMBER -> when (resolver.resolveConfirm(hyps)) {
                true -> if (pending == Pending.CONFIRM_NUMBER) placeCall(pendingNumber!!, null)
                        else pendingCandidates.first().let { placeCall(it.phone.number, it.contact.displayName) }
                false -> execute(Decision.Cancelled)
                null -> retryOrCancel("قل نعم أو لا")
            }
            Pending.NONE -> backToWake()
        }
    }

    private fun retryOrCancel(prompt: String) {
        if (retries++ >= 1) { execute(Decision.Cancelled); return }
        ask(prompt, prompt)
    }

    private fun ask(spoken: String, shown: String) {
        AssistantRepository.result(shown)
        onStatus(AssistantStatus.AWAITING_CHOICE, shown)
        engine?.idle()
        feedback.say(spoken, settings.voiceFeedback) {
            val v = vocab
            val grammar = if (supportsGrammar && v != null) {
                GrammarBuilder.choiceGrammar(pendingCandidates.map { it.contact.displayName }, v)
            } else null
            val beep = feedback.readyBeep()
            engine?.listen(Purpose.CHOICE, grammar, settings.dualRecognition, 6000, muteMs = beep)
            onStatus(AssistantStatus.AWAITING_CHOICE, shown)
        }
    }

    private fun fail(message: String) {
        feedback.errorBeep()
        AssistantRepository.result(message)
        engine?.idle()
        feedback.say(message, settings.voiceFeedback) { backToWake() }
    }

    private fun placeCall(number: String, name: String?) {
        if (!dedup.shouldExecute("call:${number.filter { it.isDigit() }}", SystemClock.elapsedRealtime())) {
            Log.i(tag, "duplicate call suppressed"); backToWake(); return
        }
        when (val r = callManager.call(number)) {
            is CallManager.Outcome.Placed -> {
                AssistantRepository.result("جارٍ الاتصال ${name?.let { "بـ $it " } ?: ""}(${r.number})")
                runBlocking { settingsRepo.setLastDialed(r.number) }
                backToWake() // the engine pauses itself while the call is active
            }
            is CallManager.Outcome.Failed -> fail("تعذر الاتصال: ${r.reason}")
        }
    }

    private fun openApp() {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val canStart = Settings.canDrawOverlays(context)
        try {
            if (canStart) { context.startActivity(i); AssistantRepository.result("تم فتح التطبيق"); return }
        } catch (e: Exception) { Log.w(tag, "open app failed", e) }
        // Android blocks background activity starts: offer a tap-to-open notification instead.
        AssistantNotifications.tapToOpen(context, "اضغط لفتح المساعد")
        AssistantRepository.result("اضغط على الإشعار لفتح التطبيق (أو فعّل «الظهور فوق التطبيقات» من الإعدادات)")
    }

    private fun spellDigits(d: String) = d.toCharArray().joinToString(" ")
}
