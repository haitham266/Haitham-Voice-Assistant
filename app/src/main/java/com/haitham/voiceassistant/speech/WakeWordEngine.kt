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
class WakeWordEngine(
    model: Model,
    grammarJson: String?,
    private val detector: WakeWordDetector,
    /**
     * Direct mode with the AI recogniser: every utterance (after 300 ms of speech) is
     * handed over, starting from its first syllable; the AI result decides if it is a command.
     */
    private val triggerOnSpeech: Boolean = false
) : AutoCloseable {

    data class Result(val confidence: Float, val trailingSpeech: Boolean)

    private val rec: Recognizer =
        (if (grammarJson != null) Recognizer(model, 16000f, grammarJson) else Recognizer(model, 16000f)).apply { setWords(true) }

    private val maxSamples = 16000 * 12
    private var utter = ShortArray(16000 * 4)
    private var utterLen = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private var inUtterance = false
    private var partialHits = 0
    private var speechFrames = 0

    /**
     * @param speech whether the VAD considers this frame part of speech.
     * @return a [Result] when the wake phrase was detected.
     */
    fun process(buf: ShortArray, n: Int, speech: Boolean): Result? {
        if (!speech) {
            if (inUtterance) {
                inUtterance = false
                partialHits = 0
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
            speechFrames = 0
            utterLen = 0
            for (p in preRoll) { append(p, p.size); if (rec.acceptWaveForm(p, p.size)) evaluate(rec.result)?.let { preRoll.clear(); return it } }
            preRoll.clear()
        }
        append(buf, n)
        if (triggerOnSpeech) {
            if (++speechFrames >= 3) { speechFrames = 0; rec.reset(); inUtterance = false; return Result(1f, trailingSpeech = true) }
            return null
        }
        if (rec.acceptWaveForm(buf, n)) {
            partialHits = 0
            val r = evaluate(rec.result)
            if (r != null) { rec.reset(); inUtterance = false; return r }
        } else {
            // Instant trigger: the wake phrase is in the running hypothesis for two
            // consecutive frames (~200 ms) -> wake now, without waiting for silence.
            val partial = org.json.JSONObject(rec.partialResult).optString("partial")
            partialHits = if (partial.isNotBlank() && detector.matchesPartial(partial)) partialHits + 1 else 0
            if (partialHits >= 2) {
                partialHits = 0
                rec.reset(); inUtterance = false
                return Result(1f, trailingSpeech = true) // keep the audio: the command may already be spoken
            }
        }
        return null
    }

    private fun evaluate(json: String): Result? {
        val words = SpeechRecognitionEngine.parseResult(json).second
        val hit = detector.detect(words) ?: return null
        val trailing = detector.isTriggerMode ||
            words.any { it.start >= hit.endTime - 0.01f && it.end > hit.endTime + 0.05f && it.conf > 0.3f }
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
