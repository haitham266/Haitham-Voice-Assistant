package com.haitham.voiceassistant.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.model.RecognizedWord
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * The phone's own modern on-device speech recognition (a neural model that runs
 * inside the phone, e.g. Google's offline Arabic pack). Used ONLY for the command,
 * after the wake word / trigger word. It is created with
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer], so audio never leaves the phone.
 *
 * Audio path (Android 13+): our own microphone thread streams PCM into a pipe
 * ([RecognizerIntent.EXTRA_AUDIO_SOURCE]) including the audio already spoken before
 * the hand-off, so no word is lost and the system recogniser does not touch the mic.
 * If the phone's recogniser refuses that, we fall back to letting it use the mic itself.
 *
 * All SpeechRecognizer calls must happen on the main thread.
 */
class OnDeviceRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "OnDeviceASR"

        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Id of the phone's default on-device recogniser (on Samsung: Samsung's own, using its language packs). */
        const val ENGINE_SYSTEM = "system"

        /** Arabic locales tried in order when an engine says "language not supported". */
        val localeCandidates = listOf("ar-SA", "ar-AE", "ar-YE", "ar", "ar-EG", "ar-JO")

        data class Engine(val id: String, val label: String)

        /** All speech recognisers installed on the phone (system on-device + every recognition service). */
        fun listEngines(context: Context): List<Engine> {
            val out = ArrayList<Engine>()
            if (isAvailable(context)) out += Engine(ENGINE_SYSTEM, "محرك الهاتف الداخلي (دون إنترنت)")
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            for (ri in services) {
                val si = ri.serviceInfo ?: continue
                val app = try { pm.getApplicationLabel(si.applicationInfo).toString() } catch (_: Exception) { si.packageName }
                val label = when {
                    si.packageName.contains("google") -> "Google – $app"
                    si.packageName.contains("samsung") -> "Samsung – $app"
                    else -> app
                }
                out += Engine(ComponentName(si.packageName, si.name).flattenToString(), label)
            }
            return out
        }

        /** Preferred Arabic locales, Yemeni first. */
        val arabicPreference = listOf("ar-YE", "ar-SA", "ar-AE", "ar-OM", "ar-KW", "ar-QA", "ar-BH", "ar-JO", "ar-EG", "ar-XA", "ar")

        fun pickArabic(langs: List<String>): String? {
            val norm = langs.associateBy { it.replace('_', '-').lowercase() }
            for (p in arabicPreference) norm[p.lowercase()]?.let { return it }
            return langs.firstOrNull { it.lowercase().startsWith("ar") }
        }
    }

    sealed class Support {
        data class Ready(val locale: String) : Support()
        data class Downloadable(val locale: String) : Support()
        data class Downloading(val locale: String) : Support()
        data object NoArabic : Support()
        data object NotAvailable : Support()
        data class Unknown(val reason: String) : Support()

        val label: String
            get() = when (this) {
                is Ready -> "جاهز ✓ (العربية $locale مثبتة في الهاتف)"
                is Downloadable -> "العربية متاحة لكن غير منزّلة — اضغط «تنزيل العربية»"
                is Downloading -> "جارٍ تنزيل العربية ($locale)…"
                NoArabic -> "محرك الهاتف لا يدعم العربية دون إنترنت"
                NotAvailable -> "الهاتف لا يوفر تعرفاً داخلياً (يُستخدم Vosk)"
                is Unknown -> "تعذر الفحص: $reason"
            }
    }

    @Volatile var locale: String = "ar-SA"
    /** [ENGINE_SYSTEM] or a flattened ComponentName of a RecognitionService. */
    @Volatile var engineId: String = ENGINE_SYSTEM

    /** Creates the recogniser for [engine] (main thread). */
    fun create(engine: String = engineId): SpeechRecognizer =
        if (engine == ENGINE_SYSTEM) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context, ComponentName.unflattenFromString(engine))
    @Volatile var support: Support = Support.Unknown("لم يُفحص بعد")
        private set
    /** Set to true once the phone refuses EXTRA_AUDIO_SOURCE; then the recogniser uses the mic. */
    @Volatile var audioSourceUnsupported = false

    private var recognizer: SpeechRecognizer? = null
    private val writerExec = Executors.newCachedThreadPool()
    private var audioSourceFailures = 0

    private fun baseIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** Checks whether Arabic is installed on the device recogniser (main thread). */
    fun checkSupport(onResult: (Support) -> Unit) {
        if (!isAvailable(context)) { support = Support.NotAvailable; onResult(support); return }
        if (Build.VERSION.SDK_INT < 33) {
            // No query API before Android 13: assume the default Arabic locale and try it.
            support = Support.Ready(locale); onResult(support); return
        }
        checkSupport33(onResult)
    }

    @RequiresApi(33)
    private fun checkSupport33(onResult: (Support) -> Unit) {
        val r = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) } catch (e: Exception) {
            support = Support.Unknown(e.message ?: "?"); onResult(support); return
        }
        val i = baseIntent().apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar") }
        r.checkRecognitionSupport(i, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                val installed = pickArabic(s.installedOnDeviceLanguages)
                val pending = pickArabic(s.pendingOnDeviceLanguages)
                val supported = pickArabic(s.supportedOnDeviceLanguages)
                support = when {
                    installed != null -> { locale = installed; Support.Ready(installed) }
                    pending != null -> Support.Downloading(pending)
                    supported != null -> { locale = supported; Support.Downloadable(supported) }
                    else -> Support.NoArabic
                }
                Log.i(TAG, "support: installed=${s.installedOnDeviceLanguages} supported=${s.supportedOnDeviceLanguages} -> $support")
                r.destroy(); onResult(support)
            }

            override fun onError(error: Int) {
                support = Support.Unknown("رمز $error"); r.destroy(); onResult(support)
            }
        })
    }

    /** Asks the phone to download its offline Arabic model (Android 13+). */
    fun downloadArabic(onProgress: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !isAvailable(context)) { onProgress("التنزيل التلقائي يحتاج أندرويد 13+"); return }
        val target = (support as? Support.Downloadable)?.locale ?: locale
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val i = baseIntent().apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE, target) }
        if (Build.VERSION.SDK_INT >= 34) {
            r.triggerModelDownload(i, context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = onProgress("جارٍ تنزيل العربية… $completedPercent%")
                override fun onSuccess() { onProgress("تم تنزيل العربية ✓"); r.destroy() }
                override fun onScheduled() { onProgress("تمت جدولة التنزيل (يكتمل عند توفر الشبكة)"); r.destroy() }
                override fun onError(error: Int) { onProgress("فشل طلب التنزيل (رمز $error). نزّلها يدوياً من إعدادات Google."); r.destroy() }
            })
        } else {
            r.triggerModelDownload(i); onProgress("طُلب التنزيل من النظام"); r.destroy()
        }
    }

    // ------------------------------------------------------------------ session

    interface Callback {
        fun onPartial(text: String)
        fun onResults(hyps: List<Hypothesis>)
        /** [audioSourceRefused] = the phone does not accept our audio stream. */
        fun onError(code: Int, audioSourceRefused: Boolean)
    }

    /** Receives PCM from the microphone thread while a streamed session is active. */
    class AudioStream internal constructor(private val out: OutputStream, private val exec: java.util.concurrent.ExecutorService) :
        SpeechRecognitionEngine.AudioSink {
        private val queue = LinkedBlockingQueue<ShortArray>()
        @Volatile private var closed = false
        private val poison = ShortArray(0)

        init {
            exec.execute {
                val bytes = ByteArray(VoiceAudioManagerFrameBytes)
                try {
                    while (true) {
                        val s = queue.take()
                        if (s === poison) break
                        val b = if (s.size * 2 <= bytes.size) bytes else ByteArray(s.size * 2)
                        for (k in s.indices) { b[2 * k] = (s[k].toInt() and 0xff).toByte(); b[2 * k + 1] = (s[k].toInt() shr 8 and 0xff).toByte() }
                        out.write(b, 0, s.size * 2)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "audio pipe closed: ${e.message}")
                } finally {
                    try { out.close() } catch (_: Exception) {}
                }
            }
        }

        override fun write(buf: ShortArray, n: Int) { if (!closed) queue.offer(buf.copyOf(n)) }

        /** End of speech: the recogniser finalises its result when the stream ends. */
        override fun close() { if (!closed) { closed = true; queue.offer(poison) } }
    }

    /**
     * Starts recognising. With [streamed] = true returns an [AudioStream] that the
     * microphone thread must feed and close; otherwise the recogniser uses the mic itself.
     */
    fun start(streamed: Boolean, biasing: List<String>, cb: Callback): AudioStream? {
        cancel()
        val r = create()
        recognizer = r
        val intent = baseIntent().apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            if (Build.VERSION.SDK_INT >= 33 && biasing.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing.take(400)))
            }
        }
        var stream: AudioStream? = null
        var readSide: ParcelFileDescriptor? = null
        if (streamed && Build.VERSION.SDK_INT >= 33) {
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            stream = AudioStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]), writerExec)
        }
        val wasStreamed = stream != null
        var gotSpeech = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { gotSpeech = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(cb::onPartial)
            }

            override fun onResults(results: Bundle?) {
                if (wasStreamed) audioSourceFailures = 0
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val confs = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val hyps = texts.mapIndexedNotNull { i, t ->
                    if (t.isBlank()) null else {
                        // The system gives one score per sentence (or none: -1). Use 0.9 when unknown.
                        val c = confs?.getOrNull(i)?.takeIf { it >= 0f } ?: 0.9f
                        Hypothesis(t, t.split(' ').map { RecognizedWord(it, c, 0f, 0f) }, Hypothesis.Source.ON_DEVICE)
                    }
                }
                release(); cb.onResults(hyps)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "error $error (streamed=$wasStreamed, speech=$gotSpeech)")
                stream?.close()
                release()
                val refused = wasStreamed && !gotSpeech &&
                    (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_AUDIO)
                // Two refusals in a row -> this phone does not accept streamed audio; use the mic mode.
                if (refused && ++audioSourceFailures >= 2) audioSourceUnsupported = true
                cb.onError(error, refused)
            }
        })
        try {
            r.startListening(intent)
            // The recogniser received its own copy of the read side; close ours so the
            // writer gets an error (instead of blocking) if the recogniser stops reading.
            readSide?.close()
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            stream?.close(); release()
            if (wasStreamed) audioSourceUnsupported = true
            cb.onError(SpeechRecognizer.ERROR_CLIENT, wasStreamed)
            return null
        }
        return stream
    }

    fun cancel() {
        recognizer?.let { try { it.cancel() } catch (_: Exception) {}; try { it.destroy() } catch (_: Exception) {} }
        recognizer = null
    }

    private fun release() {
        recognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        recognizer = null
    }

    fun shutdown() { cancel(); writerExec.shutdownNow() }
}

private const val VoiceAudioManagerFrameBytes = 1600 * 2
