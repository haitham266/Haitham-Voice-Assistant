package com.haitham.voiceassistant.nlu

/**
 * Arabic text normalisation used everywhere we compare spoken text with
 * contact names or command keywords. Pure Kotlin, no Android dependencies.
 */
object ArabicNormalizer {

    private val diacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
    private val nonWord = Regex("[^\\p{L}\\p{Nd}+ ]")
    private val spaces = Regex("\\s+")

    /** Removes tashkeel and tatweel only (keeps letter shapes). */
    fun stripDiacritics(text: String): String = text.replace(diacritics, "")

    /** Full normalisation: unify letter variants, digits, case and spacing. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in stripDiacritics(text)) {
            sb.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ', 'ٲ', 'ٳ' -> 'ا'
                    'ى', 'ی', 'ئ' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ک', 'گ' -> 'ك'
                    'ڤ' -> 'ف'
                    'پ' -> 'ب'
                    'چ' -> 'ج'
                    'ـ' -> ' '
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '،', '؛', '؟', '.', ',', '!', '?', '-', '_', '/' -> ' '
                    else -> ch.lowercaseChar()
                }
            )
        }
        return sb.toString().replace(nonWord, " ").replace(spaces, " ").trim()
    }

    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotBlank() }

    /**
     * Phonetic folding for fuzzy name matching: merges letters that are often
     * confused by the recogniser or pronounced alike in Yemeni / Gulf / Levantine
     * dialects (ث/ت/س, ذ/ز/ظ, ض/د, ق/ك/ج-as-g, ص/س, ط/ت, ح/ه, ع/ء/ا ...).
     */
    fun phonetic(token: String): String {
        val n = normalize(token)
        val sb = StringBuilder()
        for (ch in n) {
            val m = when (ch) {
                'ث', 'س', 'ص' -> 'س'
                'ذ', 'ز', 'ظ' -> 'ز'
                'ض', 'د' -> 'د'
                'ط', 'ت' -> 'ت'
                'ق', 'ك', 'غ' -> 'ك'
                'ح', 'ه' -> 'ه'
                'ع', 'ء', 'ا' -> 'ا'
                else -> ch
            }
            if (sb.isEmpty() || sb.last() != m) sb.append(m) // collapse doubled letters
        }
        return sb.toString()
    }

    /** Removes the definite article "ال" from a single token (العريفي -> عريفي). */
    fun stripArticle(token: String): String =
        if (token.length > 4 && token.startsWith("ال")) token.substring(2) else token

    private val arToLatin = mapOf(
        'ا' to "a", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j", 'ح' to "h", 'خ' to "kh",
        'د' to "d", 'ذ' to "th", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "s",
        'ض' to "d", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q",
        'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'و' to "w", 'ي' to "y",
        'ء' to "a"
    )

    /**
     * Consonant skeleton in Latin letters. Lets an Arabic spoken name match a
     * contact stored in English letters ("هيثم" ~ "Haitham" -> "hthm").
     */
    fun latinSkeleton(token: String): String {
        val n = normalize(token)
        val latin = if (n.any { it in '؀'..'ۿ' }) {
            buildString { n.forEach { append(arToLatin[it] ?: if (it.isLetterOrDigit()) it.toString() else "") } }
        } else n
        val s = latin.lowercase()
            .replace("kh", "x").replace("sh", "c").replace("gh", "g").replace("th", "t")
            .replace("ph", "f").replace("ou", "u").replace("q", "k").replace("j", "g").replace("dh", "d")
        val sb = StringBuilder()
        for (c in s) {
            if (c in "aeiouyw") continue
            if (!c.isLetterOrDigit()) continue
            if (sb.isEmpty() || sb.last() != c) sb.append(c)
        }
        return sb.toString()
    }

    fun isArabic(text: String): Boolean = text.any { it in '؀'..'ۿ' }
}
