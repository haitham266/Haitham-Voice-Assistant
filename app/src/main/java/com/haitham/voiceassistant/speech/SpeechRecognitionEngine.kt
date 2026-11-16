package com.haitham.voiceassistant.speech

import android.os.SystemClock
import android.util.Log
import com.haitham.voiceassistant.audio.EnergyVad
import com.haitham.voiceassistant.audio.VoiceAudioManager
import com.haitham.voiceassistant.audio.rms
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.model.RecognizedWord
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.log10

/**
 * Owns the microphone thread and the Vosk recognisers.
 *
 * Two clearly separated stages:
 *  1. WAKE  – a tiny grammar recogniser ([WakeWordEngine]) that only knows the
 *     wake phrase + filler words. It is fed ONLY while the energy VAD hears
 *     speech, so in silence the CPU does almost nothing.
 *  2. COMMAND – started after the wake phrase: a grammar recogniser built from
 *     command words + the user's contact names, optionally in parallel with the
 *     free (open vocabulary) recogniser. It ends on silence or timeout and the
 *     hypotheses are handed to the controller.
 *
 * All recogniser calls happen on the single audio thread (Vosk objects are not thread safe).
 */
class SpeechRecognitionEngine(
    private val audio: VoiceAudioManager,
    private val model: Model,
    private val supportsGrammar: Boolean,
    private val listener: Listener
) {

    interface Listener {
        fun onLevel(level: Float)
        fun onWake(confidence: Float, trailingSpeech: Boolean)
        fun onPartial(text: String)
        fun onCommand(purpose: Purpose, hypotheses: List<Hypothesis>)
        fun onCallStateChanged(inCall: Boolean)
        fun onMicBlocked(blocked: Boolean)
        fun onFatal(message: String)
    }

    enum class Purpose { COMMAND, CHOICE, TEST }

    private enum class Mode { WAKE, LISTEN, IDLE }

    private sealed class Req {
        data class Wake(val grammarJson: String?, val detector: WakeWordDetector, val sensitivity: Int) : Req()
        data class Listen(
            val purpose: Purpose, val grammarJson: String?, val useFree: Boolean,
            val timeoutMs: Long, val replay: Boolean, val muteMs: Long
        ) : Req()
        data object Idle : Req()
    }

    private val queue = ConcurrentLinkedQueue<Req>()
    @Volatile private var running = false
    private var thread: Thread? = null

    // --- state owned by the audio thread ---
    private var mode = Mode.IDLE
    private var wakeEngine: WakeWordEngine? = null
    private var vad = EnergyVad(1)
    private var listenGrammarRec: Recognizer? = null
    private var listenFreeRec: Recognizer? = null
    private var listenPurpose = Purpose.COMMAND
    private var listenStart = 0L
    private var listenTimeout = 7000L
    private var listenSpeechSeen = false
    private var listenSilenceFrames = 0
    private var muteUntil = 0L
    private val grammarWords = ArrayList<RecognizedWord>()
    private val freeWords = ArrayList<RecognizedWord>()
    private val grammarText = StringBuilder()
    private val freeText = StringBuilder()
    private var lastPartialAt = 0L
    private var replayRequested = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "voice-engine").apply { priority = Thread.MAX_PRIORITY - 1; start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    fun listenForWake(wakeGrammarJson: String?, detector: WakeWordDetector, sensitivity: Int) {
        queue += Req.Wake(wakeGrammarJson, detector, sensitivity)
    }

    fun listen(purpose: Purpose, grammarJson: String?, useFree: Boolean, timeoutMs: Long, replay: Boolean = false, muteMs: Long = 0) {
        queue += Req.Listen(purpose, grammarJson, useFree, timeoutMs, replay, muteMs)
    }

    fun idle() { queue += Req.Idle }

    private fun loop() {
        val buf = ShortArray(VoiceAudioManager.FRAME_SAMPLES)
        var inCall = false
        var lastCallCheck = 0L
        var micBlocked = false
        try {
            while (running) {
                // Process control requests.
                while (true) handle(queue.poll() ?: break)

                val now = SystemClock.elapsedRealtime()
                if (now - lastCallCheck > 1000) {
                    lastCallCheck = now
                    val c = audio.isInCall()
                    if (c != inCall) {
                        inCall = c
                        if (c) audio.close() // release the mic for the dialer / VoIP app
                        listener.onCallStateChanged(c)
                    }
                    val blocked = audio.silencedByOtherApp
                    if (blocked != micBlocked) { micBlocked = blocked; listener.onMicBlocked(blocked) }
                }
                if (inCall || mode == Mode.IDLE && !audio.isOpen) {
                    Thread.sleep(300); continue
                }
                if (!audio.isOpen) {
                    if (!audio.open()) {
                        if (!micBlocked) { micBlocked = true; listener.onMicBlocked(true) }
                        Thread.sleep(2000); continue
                    }
                    if (micBlocked && !audio.silencedByOtherApp) { micBlocked = false; listener.onMicBlocked(false) }
                }
                val n = audio.read(buf)
                if (n <= 0) {
                    audio.close(); Thread.sleep(300); continue
                }
                val energy = rms(buf, n)
                listener.onLevel(levelOf(energy))
                when (mode) {
                    Mode.WAKE -> wakeFrame(buf, n, energy)
                    Mode.LISTEN -> listenFrame(buf, n, energy, now)
                    Mode.IDLE -> Unit // keep the mic open but do no recognition work
                }
            }
        } catch (t: Throwable) {
            Log.e("VoiceEngine", "engine crashed", t)
            listener.onFatal(t.message ?: t.javaClass.simpleName)
        } finally {
            closeListen()
            wakeEngine?.close(); wakeEngine = null
            audio.close()
        }
    }

    private fun levelOf(energy: Double): Float {
        if (energy <= 1) return 0f
        val db = 20 * log10(energy / 32768.0) // -90..0
        return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
    }

    private fun handle(r: Req) {
        when (r) {
            is Req.Wake -> {
                closeListen()
                wakeEngine?.close()
                wakeEngine = WakeWordEngine(model, r.grammarJson, r.detector)
                vad = EnergyVad(r.sensitivity)
                mode = Mode.WAKE
            }
            is Req.Listen -> {
                closeListen()
                listenPurpose = r.purpose
                listenGrammarRec = if (supportsGrammar && r.grammarJson != null) Recognizer(model, 16000f, r.grammarJson).apply { setWords(true) } else null
                listenFreeRec = if (r.useFree || listenGrammarRec == null) Recognizer(model, 16000f).apply { setWords(true) } else null
                listenStart = SystemClock.elapsedRealtime()
                listenTimeout = r.timeoutMs
                listenSpeechSeen = false
                listenSilenceFrames = 0
                muteUntil = listenStart + r.muteMs
                grammarWords.clear(); freeWords.clear(); grammarText.clear(); freeText.clear()
                replayRequested = r.replay
                if (r.replay) {
                    // Command spoken in the same breath as the wake phrase: decode it now.
                    val audioSoFar = wakeEngine?.takeUtterance()
                    if (audioSoFar != null && audioSoFar.isNotEmpty()) {
                        feedListen(audioSoFar, audioSoFar.size)
                        listenSpeechSeen = true
                    }
                }
                wakeEngine?.resetUtterance()
                mode = Mode.LISTEN
            }
            Req.Idle -> { closeListen(); mode = Mode.IDLE }
        }
    }

    // ------------------------------------------------------------ WAKE stage
    private fun wakeFrame(buf: ShortArray, n: Int, energy: Double) {
        val engine = wakeEngine ?: return
        val pass = vad.update(energy)
        val hit = engine.process(buf, n, pass)
        if (hit != null) {
            mode = Mode.IDLE // controller decides what's next
            listener.onWake(hit.confidence, hit.trailingSpeech)
        }
    }

    // --------------------------------------------------------- LISTEN stage
    private fun feedListen(buf: ShortArray, n: Int) {
        listenGrammarRec?.let { rec ->
            if (rec.acceptWaveForm(buf, n)) collect(rec.result, grammarWords, grammarText)
        }
        listenFreeRec?.let { rec ->
            if (rec.acceptWaveForm(buf, n)) collect(rec.result, freeWords, freeText)
        }
    }

    private fun listenFrame(buf: ShortArray, n: Int, energy: Double, now: Long) {
        if (now < muteUntil) return // skip the beep we just played
        vad.update(energy)
        feedListen(buf, n)
        if (vad.lastFrameLoud) { listenSpeechSeen = true; listenSilenceFrames = 0 } else listenSilenceFrames++

        if (now - lastPartialAt > 400) {
            lastPartialAt = now
            val rec = listenGrammarRec ?: listenFreeRec
            rec?.partialResult?.let { p ->
                val t = JSONObject(p).optString("partial")
                val shown = (if (listenGrammarRec != null) grammarText else freeText).toString() + " " + t
                if (shown.isNotBlank()) listener.onPartial(shown.trim())
            }
        }
        val elapsed = now - listenStart
        val endOfSpeech = listenSpeechSeen && listenSilenceFrames >= 7 // 0.7 s of real silence after speech
        val nothingSaid = !listenSpeechSeen && elapsed > 4500
        if (endOfSpeech || nothingSaid || elapsed > listenTimeout) finishListen()
    }

    private fun finishListen() {
        listenGrammarRec?.let { collect(it.finalResult, grammarWords, grammarText) }
        listenFreeRec?.let { collect(it.finalResult, freeWords, freeText) }
        val hyps = ArrayList<Hypothesis>()
        if (grammarText.isNotBlank()) hyps += Hypothesis(grammarText.toString().trim(), grammarWords.toList(), Hypothesis.Source.GRAMMAR)
        if (freeText.isNotBlank()) hyps += Hypothesis(freeText.toString().trim(), freeWords.toList(), Hypothesis.Source.FREE)
        val purpose = listenPurpose
        closeListen()
        mode = Mode.IDLE
        listener.onCommand(purpose, hyps)
    }

    private fun closeListen() {
        listenGrammarRec?.close(); listenGrammarRec = null
        listenFreeRec?.close(); listenFreeRec = null
    }

    private fun collect(json: String, words: MutableList<RecognizedWord>, text: StringBuilder) {
        val parsed = parseResult(json)
        if (parsed.first.isBlank()) return
        if (text.isNotEmpty()) text.append(' ')
        text.append(parsed.first)
        words += parsed.second
    }

    companion object {
        fun parseResult(json: String): Pair<String, List<RecognizedWord>> {
            val o = JSONObject(json)
            val text = o.optString("text", "")
            val arr = o.optJSONArray("result") ?: return text to emptyList()
            val words = (0 until arr.length()).map {
                val w = arr.getJSONObject(it)
                RecognizedWord(w.optString("word"), w.optDouble("conf", 1.0).toFloat(), w.optDouble("start").toFloat(), w.optDouble("end").toFloat())
            }
            return text to words
        }
    }
}
