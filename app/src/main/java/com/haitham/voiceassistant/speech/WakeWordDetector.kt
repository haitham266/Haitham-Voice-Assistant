package com.haitham.voiceassistant.speech

import com.haitham.voiceassistant.model.RecognizedWord
import com.haitham.voiceassistant.nlu.ArabicNormalizer

/**
 * Pure decision logic for the wake phrase, fed with word-level results of the
 * small grammar recogniser. The grammar contains the wake phrase plus many
 * "garbage" filler words, so ordinary speech is absorbed by fillers instead of
 * being forced onto the wake phrase.
 */
class WakeWordDetector(wakePhrase: String, sensitivity: Int) {

    val wakeTokens: List<String> = ArabicNormalizer.tokens(wakePhrase)

    /** Minimum mean confidence of the wake words (sensitivity 0=low .. 2=high). */
    val threshold: Float = when (sensitivity) {
        0 -> 0.70f
        2 -> 0.35f
        else -> 0.50f
    }

    /** Allows the last word alone ("مساعد") to wake the assistant at high sensitivity. */
    private val allowLastWordOnly = sensitivity >= 2 && wakeTokens.size > 1

    data class Hit(val endTime: Float, val confidence: Float)

    /**
     * Instant detection on a *partial* (still-being-spoken) hypothesis: true if the
     * wake phrase appears in it. The engine requires it in two consecutive partials.
     */
    fun matchesPartial(text: String): Boolean {
        val t = ArabicNormalizer.tokens(text)
        val k = wakeTokens.size
        if (k == 0 || t.size < k) return allowLastWordOnly && t.contains(wakeTokens.lastOrNull())
        for (i in 0..t.size - k) if ((0 until k).all { t[i + it] == wakeTokens[it] }) return true
        return allowLastWordOnly && t.contains(wakeTokens.last())
    }

    fun detect(words: List<RecognizedWord>): Hit? {
        if (wakeTokens.isEmpty() || words.isEmpty()) return null
        val norm = words.map { ArabicNormalizer.normalize(it.word) }
        val k = wakeTokens.size
        for (i in 0..norm.size - k) {
            if ((0 until k).all { norm[i + it] == wakeTokens[it] }) {
                val conf = (0 until k).map { words[i + it].conf }.average().toFloat()
                if (conf >= threshold) return Hit(words[i + k - 1].end, conf)
            }
        }
        if (allowLastWordOnly) {
            val last = wakeTokens.last()
            for (i in norm.indices) if (norm[i] == last && words[i].conf >= 0.8f) return Hit(words[i].end, words[i].conf)
        }
        return null
    }
}
