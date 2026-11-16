package com.haitham.voiceassistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.haitham.voiceassistant.speech.OnDeviceRecognizer.Companion.Engine

/**
 * Real test of every speech recogniser on the phone: the user speaks once per engine,
 * and Arabic locales are tried automatically until one is accepted. Nothing is trusted
 * from "supported languages" lists (Samsung's engine reports none, yet works).
 * Runs on the main thread while the assistant service is stopped (it uses the mic).
 */
class EngineProbe(private val context: Context) {

    data class Outcome(val engine: Engine, val locale: String?, val text: String?, val error: String?) {
        val ok get() = !text.isNullOrBlank()
    }

    private val main = Handler(Looper.getMainLooper())
    private var current: SpeechRecognizer? = null
    private var cancelled = false

    fun run(engines: List<Engine>, onStep: (String) -> Unit, onDone: (List<Outcome>) -> Unit) {
        cancelled = false
        val results = ArrayList<Outcome>()
        fun next(i: Int) {
            if (cancelled) return
            if (i >= engines.size) { onDone(results); return }
            tryEngine(engines[i], i, engines.size, onStep) { results += it; next(i + 1) }
        }
        next(0)
    }

    fun cancel() {
        cancelled = true
        current?.let { try { it.cancel(); it.destroy() } catch (_: Exception) {} }
        current = null
    }

    private fun tryEngine(e: Engine, idx: Int, total: Int, onStep: (String) -> Unit, done: (Outcome) -> Unit) {
        val locales = OnDeviceRecognizer.localeCandidates
        fun attempt(li: Int) {
            if (cancelled) return
            if (li >= locales.size) { done(Outcome(e, null, null, "لم يقبل أي لهجة عربية")); return }
            val locale = locales[li]
            val r = try {
                OnDeviceRecognizer(context).apply { engineId = e.id }.create()
            } catch (ex: Exception) { done(Outcome(e, null, null, "تعذر تشغيله: ${ex.message}")); return }
            current = r
            var finished = false
            val timeout = Runnable { if (!finished) { finished = true; r.cancel(); r.destroy(); done(Outcome(e, locale, null, "انتهت المهلة")) } }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) =
                    onStep("المحرك ${idx + 1} من $total: ${e.label}\nتكلم الآن، مثلاً: «اتصل بعمنا مختار»")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { onStep("جارٍ الفهم…") }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    if (finished) return
                    finished = true; main.removeCallbacks(timeout); r.destroy()
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    done(Outcome(e, locale, t, if (t.isNullOrBlank()) "لم يرجع نصاً" else null))
                }
                override fun onError(error: Int) {
                    if (finished) return
                    finished = true; main.removeCallbacks(timeout); r.destroy()
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        attempt(li + 1) // try the next Arabic locale immediately
                    } else done(Outcome(e, locale, null, describe(error)))
                }
            })
            onStep("المحرك ${idx + 1} من $total: ${e.label}\nتجهيز ($locale)…")
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            try { r.startListening(intent) } catch (ex: Exception) {
                finished = true; r.destroy(); done(Outcome(e, locale, null, "تعذر البدء: ${ex.message}")); return
            }
            main.postDelayed(timeout, 12000)
        }
        attempt(0)
    }

    companion object {
        fun describe(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "يحتاج إنترنت (رمز $error)"
            SpeechRecognizer.ERROR_NO_MATCH -> "لم يفهم الكلام"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "لم يسمع كلاماً"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "صلاحيات غير كافية"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "المحرك مشغول"
            SpeechRecognizer.ERROR_AUDIO -> "خطأ في الصوت"
            else -> "خطأ رمز $error"
        }
    }
}
