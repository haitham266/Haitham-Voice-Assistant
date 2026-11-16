package com.haitham.voiceassistant.nlu

/**
 * Converts spoken Arabic numbers (MSA + Yemeni/Gulf/Levantine/Tunisian forms),
 * Arabic-Indic digits and Latin digits into a dial string.
 *
 * Phone numbers are usually spoken digit by digit ("سبعة سبعة سبعة ...") but
 * people also group them ("سبعمية وسبعة وسبعين", "اثنعش"). Each spoken group is
 * converted to its value and the groups are concatenated, so the original digits
 * are preserved exactly; nothing is added, removed or re-formatted.
 */
object ArabicNumberParser {

    data class ParsedNumber(val digits: String, val consumedTokens: Int, val numericTokens: Int)

    private val units = mapOf(
        "صفر" to 0, "زيرو" to 0, "zero" to 0,
        "واحد" to 1, "واحده" to 1, "وحده" to 1, "احد" to 1,
        "اثنين" to 2, "اثنان" to 2, "اتنين" to 2, "ثنين" to 2, "تنين" to 2, "اثنتين" to 2, "ثنتين" to 2, "اثنينن" to 2,
        "ثلاثه" to 3, "ثلاث" to 3, "تلاته" to 3, "تلاتة" to 3, "ثلاثة" to 3, "ثلاثا" to 3, "تلات" to 3,
        "اربعه" to 4, "اربع" to 4, "اربعة" to 4,
        "خمسه" to 5, "خمس" to 5, "خمسة" to 5,
        "سته" to 6, "ست" to 6, "ستة" to 6,
        "سبعه" to 7, "سبع" to 7, "سبعة" to 7,
        "ثمانيه" to 8, "ثمان" to 8, "ثماني" to 8, "ثمانية" to 8, "تمانيه" to 8, "تمنيه" to 8, "ثمنيه" to 8, "تمانية" to 8, "ثمنية" to 8,
        "تسعه" to 9, "تسع" to 9, "تسعة" to 9
    ).mapKeys { ArabicNormalizer.normalize(it.key) }

    private val tens = mapOf(
        "عشره" to 10, "عشر" to 10, "عشرة" to 10,
        "عشرين" to 20, "عشرون" to 20,
        "ثلاثين" to 30, "ثلاثون" to 30, "تلاتين" to 30,
        "اربعين" to 40, "اربعون" to 40,
        "خمسين" to 50, "خمسون" to 50,
        "ستين" to 60, "ستون" to 60,
        "سبعين" to 70, "سبعون" to 70,
        "ثمانين" to 80, "ثمانون" to 80, "تمانين" to 80,
        "تسعين" to 90, "تسعون" to 90
    ).mapKeys { ArabicNormalizer.normalize(it.key) }

    private val hundreds = mapOf(
        "ميه" to 100, "مية" to 100, "مائه" to 100, "مايه" to 100, "مئه" to 100, "مائة" to 100, "مئة" to 100,
        "ميتين" to 200, "مئتين" to 200, "مائتين" to 200, "ميتان" to 200, "مئتان" to 200, "مائتان" to 200,
        "الف" to 1000, "الفين" to 2000
    ).mapKeys { ArabicNormalizer.normalize(it.key) }

    /** Teen roots, e.g. حداش / احدعشر / اطناش / سبعطعش / ثمنطاش / تسعتاش. */
    private val teenRoots = listOf(
        11 to listOf("احد", "حد", "حدا", "احدا"),
        12 to listOf("اثن", "اثنا", "اطن", "طن", "ثن", "اتن", "اثني", "ثنا", "اطنا"),
        13 to listOf("ثلاث", "ثلث", "تلت", "تلات", "ثلط", "تلط"),
        14 to listOf("اربع", "اربط", "ربع", "ربط"),
        15 to listOf("خمس", "خمست", "خمط"),
        16 to listOf("ست", "سط", "سد"),
        17 to listOf("سبع", "سبعت", "سبط"),
        18 to listOf("ثمان", "ثمن", "تمن", "ثمنت", "تمنت", "ثماني"),
        19 to listOf("تسع", "تسعت", "تسط")
    )
    private val teenSuffixes = listOf("عشر", "عش", "طعش", "طاش", "تاش", "طاشر", "تاشر", "اش", "طعشر", "تعش")

    private val multiplierWords = mapOf("دبل" to 2, "double" to 2, "تريبل" to 3, "triple" to 3, "تربل" to 3)
    private val plusWords = setOf("زائد", "زايد", "بلس", "plus", "+")

    private fun teenValue(t: String): Int? {
        for (suffix in teenSuffixes) {
            if (t.length > suffix.length && t.endsWith(suffix)) {
                val root = t.removeSuffix(suffix)
                for ((v, roots) in teenRoots) if (root in roots) return v
            }
        }
        return null
    }

    private fun hundredsCompound(t: String): Int? {
        // ثلاثمية / سبعمائه / تسعميه ...
        for (h in listOf("ميه", "مائه", "مايه", "مئه")) {
            if (t.length > h.length && t.endsWith(h)) {
                val root = t.removeSuffix(h)
                val u = units[root] ?: units[root + "ه"] ?: when (root) {
                    "ثلث", "تلت" -> 3; "ثمن", "تمن" -> 8; "ربع" -> 4; else -> null
                }
                if (u != null && u in 1..9) return u * 100
            }
        }
        return null
    }

    /** Value of a single normalised token, or null if it is not a number word. */
    fun tokenValue(token: String): Int? {
        units[token]?.let { return it }
        tens[token]?.let { return it }
        hundreds[token]?.let { return it }
        hundredsCompound(token)?.let { return it }
        teenValue(token)?.let { return it }
        return null
    }

    fun isNumberToken(token: String): Boolean {
        val t = token.removePrefix("و").takeIf { token.startsWith("و") && token.length > 2 } ?: token
        return token.all { it.isDigit() } || tokenValue(token) != null || tokenValue(t) != null ||
            token in plusWords || token in multiplierWords
    }

    private fun place(v: Int): Int = when {
        v >= 1000 -> 1000
        v >= 100 -> 100
        v >= 10 -> 10
        else -> 1
    }

    /**
     * Parses a digit string from normalised tokens. Non-number tokens are skipped
     * only if they are fillers between digits ("و", "فاصله"); parsing stops at the
     * first other word after digits started.
     */
    fun parse(tokens: List<String>): ParsedNumber? {
        val out = StringBuilder()
        var group = -1 // current additive group value, -1 = none
        var pendingMultiplier = 1
        var consumed = 0
        var numeric = 0
        var started = false

        fun flush() {
            if (group >= 0) {
                repeat(pendingMultiplier.coerceAtLeast(1)) { out.append(group.toString()) }
                pendingMultiplier = 1
                group = -1
            }
        }

        for ((i, raw) in tokens.withIndex()) {
            val tok = raw.trim()
            if (tok.isEmpty()) continue
            if (tok in plusWords) {
                if (!started) { out.append('+'); started = true; consumed = i + 1; continue } else break
            }
            val mult = multiplierWords[tok]
            if (mult != null) {
                flush(); pendingMultiplier = mult; started = true; consumed = i + 1
                continue
            }

            if (tok.all { it.isDigit() || it == '+' }) {
                flush()
                out.append(tok)
                started = true; numeric++; consumed = i + 1
                continue
            }

            var hasWaw = false
            var v = tokenValue(tok)
            if (v == null && tok.length > 2 && tok.startsWith("و")) {
                v = tokenValue(tok.substring(1)); hasWaw = v != null
            }
            if (v == null) {
                if (tok == "و" && started) { consumed = i + 1; continue }
                if (started) break else continue
            }
            started = true; numeric++; consumed = i + 1

            if (hasWaw && group > 0 && canAbsorb(group, v)) {
                group += v
            } else if (v == 1000 || v == 2000) {
                // "سبعة الف" -> 7000
                group = if (group in 1..999) group * 1000 else v
            } else {
                flush()
                group = v
                if (pendingMultiplier > 1) { flush() }
            }
        }
        flush()
        val digits = out.toString()
        if (digits.isEmpty() || digits == "+") return null
        return ParsedNumber(digits, consumed, numeric)
    }

    private fun canAbsorb(group: Int, v: Int): Boolean {
        val p = place(v)
        return when {
            v in 11..19 -> group % 100 == 0
            p == 1 -> group % 10 == 0 && group >= 10
            p == 10 -> (group / 10) % 10 == 0 && group % 10 != 0 || (group >= 100 && (group / 10) % 10 == 0)
            p == 100 -> (group / 100) % 10 == 0 && group >= 1000
            else -> false
        }
    }

    /** True if [digits] looks like something that can be dialled (3..15 digits, optional +). */
    fun isDialable(digits: String): Boolean {
        val d = digits.removePrefix("+")
        return d.length in 3..15 && d.all { it.isDigit() }
    }
}
