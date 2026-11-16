package com.haitham.voiceassistant.speech

import org.vosk.Model
import org.vosk.Recognizer

/**
 * Wake phrase spotting ("يا مساعد" by default), separate from command recognition.
 *
 * Uses a Vosk recogniser restricted to a tiny grammar (wake phrase + filler
 * "garbage" words). The caller only passes frames while the energy VAD hears
 * speech, plus a short pre-roll, so the decoder is idle in silence.
 * The audio of the current utterance is kept in RAM (max 12 s) so a command
 * spoken in the same breath ("يا مساعد اتصل بأحمد") can be re-decoded by the
 * command recogniser; it is discarded right after.
 */
class WakeWordEngine(model: Model, grammarJson: String?, private val detector: WakeWordDetector) : AutoCloseable {

    data class Result(val confidence: Float, val trailingSpeech: Boolean)

    private val rec: Recognizer =
        (if (grammarJson != null) Recognizer(model, 16000f, grammarJson) else Recognizer(model, 16000f)).apply { setWords(true) }

    private val maxSamples = 16000 * 12
    private var utter = ShortArray(16000 * 4)
    private var utterLen = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private var inUtterance = false

    /**
     * @param speech whether the VAD considers this frame part of speech.
     * @return a [Result] when the wake phrase was detected.
     */
    fun process(buf: ShortArray, n: Int, speech: Boolean): Result? {
        if (!speech) {
            if (inUtterance) {
                inUtterance = false
                val r = evaluate(rec.finalResult)
                rec.reset()
                if (r != null) return r
            }
            preRoll.addLast(buf.copyOf(n))
            while (preRoll.size > 3) preRoll.removeFirst()
            return null
        }
        if (!inUtterance) {
            inUtterance = true
            utterLen = 0
            for (p in preRoll) { append(p, p.size); if (rec.acceptWaveForm(p, p.size)) evaluate(rec.result)?.let { preRoll.clear(); return it } }
            preRoll.clear()
        }
        append(buf, n)
        if (rec.acceptWaveForm(buf, n)) {
            val r = evaluate(rec.result)
            if (r != null) { rec.reset(); inUtterance = false; return r }
        }
        return null
    }

    private fun evaluate(json: String): Result? {
        val words = SpeechRecognitionEngine.parseResult(json).second
        val hit = detector.detect(words) ?: return null
        val trailing = words.any { it.start >= hit.endTime - 0.01f && it.end > hit.endTime + 0.05f && it.conf > 0.3f }
        return Result(hit.confidence, trailing)
    }

    private fun append(b: ShortArray, n: Int) {
        if (utterLen + n > utter.size) {
            if (utter.size < maxSamples) utter = utter.copyOf(minOf(maxSamples, maxOf(utter.size * 2, utterLen + n)))
            if (utterLen + n > utter.size) { // drop oldest audio
                val drop = utterLen + n - utter.size
                System.arraycopy(utter, drop, utter, 0, utterLen - drop)
                utterLen -= drop
            }
        }
        System.arraycopy(b, 0, utter, utterLen, n)
        utterLen += n
    }

    /** Audio of the last utterance (for same-breath commands). */
    fun takeUtterance(): ShortArray = utter.copyOf(utterLen)

    fun resetUtterance() { utterLen = 0; inUtterance = false; preRoll.clear(); rec.reset() }

    override fun close() { rec.close() }
}
