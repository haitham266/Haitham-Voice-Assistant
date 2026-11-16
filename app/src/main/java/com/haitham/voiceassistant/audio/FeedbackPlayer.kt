package com.haitham.voiceassistant.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Audible feedback: a short "ready" beep after the wake phrase, an error tone,
 * and optional spoken Arabic prompts through the phone's own TTS engine.
 * Only voices that do NOT need a network connection are used; if no offline
 * Arabic voice is installed, prompts fall back to tones + on-screen text.
 */
class FeedbackPlayer(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_MUSIC, 85) } catch (e: Exception) { null }
    private var tts: TextToSpeech? = null
    @Volatile var ttsReady = false
        private set
    private val pending = HashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            val arabic = listOf(Locale("ar", "YE"), Locale("ar", "SA"), Locale("ar"))
                .firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
            if (arabic == null) return@TextToSpeech
            t.language = arabic
            try {
                val offline = t.voices?.filter { it.locale.language == "ar" && !it.isNetworkConnectionRequired }
                if (offline.isNullOrEmpty()) return@TextToSpeech
                t.voice = offline.minByOrNull { it.latency }
            } catch (e: Exception) { Log.w("Feedback", "voice query failed", e) }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { finish(id) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { finish(id) }
                override fun onError(id: String?, errorCode: Int) { finish(id) }
            })
            ttsReady = true
        }
    }

    private fun finish(id: String?) {
        val cb = synchronized(pending) { pending.remove(id) } ?: return
        main.post(cb)
    }

    /** Beep meaning "I'm listening". Returns its duration in ms. */
    fun readyBeep(): Long { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150); return 220 }

    fun successBeep() { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 200) }

    fun errorBeep() { tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300) }

    /**
     * Speaks [text] (if enabled and available) and invokes [then] on the main
     * thread when finished. The microphone recogniser is idle meanwhile so the
     * assistant never hears itself.
     */
    fun say(text: String, enabled: Boolean, then: () -> Unit = {}) {
        val t = tts
        if (!enabled || !ttsReady || t == null) { main.postDelayed(then, 350); return }
        val id = UUID.randomUUID().toString()
        synchronized(pending) { pending[id] = then }
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString()) }
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finish(id)
        // Safety net: never stay mute forever if the engine never calls back.
        main.postDelayed({ finish(id) }, 8000)
    }

    fun release() {
        tts?.shutdown(); tts = null
        tone?.release()
    }
}
