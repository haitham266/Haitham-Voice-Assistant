package com.haitham.voiceassistant.speech

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.model.RecognizedWord
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.concurrent.Executors

/**
 * Whisper Turbo (int8) running inside the app with sherpa-onnx — a modern AI speech
 * model that understands Arabic dialects (tested on the user's own Yemeni recordings).
 * Fully offline: the model is read directly from the APK (no copy, no internet).
 *
 * It is used for the command only (after the wake word / in direct mode): the audio
 * thread streams the command into a [Session]; when speech ends the whole command is
 * transcribed in one pass (about 1–2 s on a recent phone).
 */
class WhisperRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "Whisper"
        private const val DIR = "whisper"
        /** Whisper works on windows of up to 30 s; commands are much shorter. */
        private const val MAX_SAMPLES = 16000 * 25

        fun isBundled(context: Context): Boolean =
            try { context.assets.list(DIR)?.contains("turbo-encoder.int8.onnx") == true } catch (_: Exception) { false }
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val decoder = Executors.newSingleThreadExecutor()

    val isLoaded: Boolean get() = recognizer != null

    /** Loads the model (blocking, ~2–5 s). Call from a worker thread. */
    fun load(): Boolean {
        if (recognizer != null) return true
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val cores = Runtime.getRuntime().availableProcessors()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "$DIR/turbo-encoder.int8.onnx",
                        decoder = "$DIR/turbo-decoder.int8.onnx",
                        language = "ar",
                        task = "transcribe",
                        tailPaddings = 1000
                    ),
                    tokens = "$DIR/turbo-tokens.txt",
                    numThreads = cores.coerceIn(2, 4),
                    provider = "cpu",
                    modelType = "whisper"
                )
            )
            recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
            Log.i(TAG, "loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            false
        }
    }

    /** Transcribes 16 kHz mono PCM. Blocking. */
    fun transcribe(pcm: ShortArray, n: Int = pcm.size): String {
        val r = recognizer ?: return ""
        val len = minOf(n, MAX_SAMPLES)
        val start = n - len // keep the end if it is very long
        val f = FloatArray(len) { pcm[start + it] / 32768f }
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(f, 16000)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /**
     * Collects the command audio from the microphone thread; on [close] the audio is
     * transcribed on a background thread and [onText] is called with the hypotheses.
     */
    inner class Session(private val onText: (List<Hypothesis>, Long) -> Unit) : SpeechRecognitionEngine.AudioSink {
        private var buf = ShortArray(16000 * 6)
        private var len = 0
        @Volatile private var closed = false

        @Synchronized
        override fun write(buf: ShortArray, n: Int) {
            if (closed) return
            if (len + n > this.buf.size) {
                if (this.buf.size >= MAX_SAMPLES) return
                this.buf = this.buf.copyOf(minOf(MAX_SAMPLES, maxOf(this.buf.size * 2, len + n)))
                if (len + n > this.buf.size) return
            }
            System.arraycopy(buf, 0, this.buf, len, n)
            len += n
        }

        override fun close() {
            val audio: ShortArray
            synchronized(this) {
                if (closed) return
                closed = true
                audio = buf.copyOf(len)
            }
            decoder.execute {
                val t0 = SystemClock.elapsedRealtime()
                val text = try { if (audio.size < 16000 / 4) "" else transcribe(audio) } catch (t: Throwable) {
                    Log.e(TAG, "decode failed", t); ""
                }
                val ms = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "\"$text\" (${audio.size / 16} ms audio, decoded in $ms ms)")
                val hyps = if (text.isBlank()) emptyList() else listOf(
                    Hypothesis(text, text.split(' ').map { RecognizedWord(it, 0.95f, 0f, 0f) }, Hypothesis.Source.ON_DEVICE)
                )
                onText(hyps, ms)
            }
        }
    }

    fun release() {
        decoder.shutdownNow()
        recognizer?.release()
        recognizer = null
    }
}
