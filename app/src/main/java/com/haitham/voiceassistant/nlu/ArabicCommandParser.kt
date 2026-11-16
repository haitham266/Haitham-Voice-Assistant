package com.haitham.voiceassistant.nlu

import com.haitham.voiceassistant.model.VoiceIntent

/**
 * Local, rule based intent parser for Arabic voice commands (MSA + dialects).
 *
 * It separates the "call" verb (اتصل / دق / كلم / عيط / ارن ...) from the
 * target, strips prepositions (ب / على / ل / مع / الى) and politeness words,
 * and decides between calling a contact name or a spoken number.
 */
class ArabicCommandParser(wakePhrase: String = "يا مساعد") {

    private val wakeTokens = ArabicNormalizer.tokens(wakePhrase).toSet() + setOf("يا", "مساعد", "المساعد")

    companion object {
        private fun n(vararg w: String) = w.map { ArabicNormalizer.normalize(it) }.toSet()

        /** Verbs meaning "call" (with common attached pronouns like "-لي"). */
        val callVerbs = n(
            "اتصل", "اتصلي", "اتصلي", "تتصل", "اتصال", "اتصلبي", "اتصلت", "تصل", "وصلني", "صلني",
            "كلم", "كلمي", "كلملي", "كلمني", "تكلم", "كالم", "حاكي", "حكي",
            "دق", "دقي", "دقلي", "دق لي", "ادق", "تدق",
            "عيط", "عيطلي", "عيطي", "ارن", "رن", "رني", "رنلي", "ترن", "اضرب", "اضربلي",
            "اطلب", "اطلبلي", "تواصل", "هات", "جيب", "call", "dial", "phone"
        )
        val prepositions = n("على", "ب", "ل", "مع", "الى", "إلى", "لعند", "عند", "عل", "ع", "to")
        val numberKeywords = n("الرقم", "رقم", "بالرقم", "للرقم", "عالرقم", "نمره", "النمره", "بالنمره", "number")
        val politeness = n(
            "لو", "سمحت", "سمحتي", "فضلك", "من", "ممكن", "بليز", "please", "ابغى", "ابغا", "ابي", "اريد", "بدي",
            "عايز", "حابب", "اشتي", "اشتيك", "تقدر", "يمكنك", "حبيبي", "الان", "الحين", "دحين", "حالا", "بسرعه"
        )
        /** Polite phrases that may trail a name ("اتصل بأحمد لو سمحت"). */
        val trailingPhrases: List<List<String>> = listOf(
            "لو سمحت", "لو سمحتي", "من فضلك", "الله يخليك", "الله يرحم والديك", "بسرعه", "الحين", "الان", "دحين", "حالا", "please", "بليز"
        ).map { ArabicNormalizer.tokens(it) }
        val stopWords = n("اوقف", "وقف", "توقف", "اسكت", "بطل", "سكر", "اقفل", "اطفي", "طفي", "stop")
        val listenWords = n("الاستماع", "استماع", "التنصت", "المساعد", "السماع", "الاستماع", "الميكروفون", "المايك")
        val openWords = n("افتح", "فتح", "افتحي", "شغل", "اعرض", "open")
        val appWords = n("التطبيق", "البرنامج", "المساعد", "الواجهه", "تطبيق", "برنامج", "app")
        val redialWords = n("اعد", "اعيد", "عاود", "كرر", "رجع", "redial")
        val lastWords = n("اخر", "باخر", "لاخر", "الاخير", "اخير", "الاخر", "اخر", "last")
        val cancelWords = n("الغاء", "الغي", "الغ", "الغيه", "خلاص", "بلاش", "cancel", "لا شي", "انسى", "لاتتصل")
        val yesWords = n("نعم", "ايوه", "ايوا", "اي", "ايه", "اكيد", "صح", "تمام", "yes", "اوكي", "ok", "طيب", "هيا", "اتصل", "ايوة", "اه")
        val noWords = n("لا", "لأ", "مش", "مو", "no", "غلط", "خطا")
        val ordinals: Map<String, Int> = mapOf(
            "الاول" to 0, "الأول" to 0, "اول" to 0, "واحد" to 0, "الاولى" to 0, "first" to 0, "رقم واحد" to 0,
            "الثاني" to 1, "التاني" to 1, "ثاني" to 1, "اثنين" to 1, "الثانيه" to 1, "second" to 1, "ثنين" to 1,
            "الثالث" to 2, "التالت" to 2, "ثالث" to 2, "ثلاثه" to 2, "الثالثه" to 2, "third" to 2,
            "الرابع" to 3, "رابع" to 3, "اربعه" to 3, "الرابعه" to 3,
            "الخامس" to 4, "خامس" to 4, "خمسه" to 4
        ).mapKeys { ArabicNormalizer.normalize(it.key) }
    }

    /** Strips wake words and politeness fillers at the beginning. */
    private fun cleanLeading(tokens: List<String>): List<String> {
        var i = 0
        while (i < tokens.size && (tokens[i] in wakeTokens || tokens[i] in politeness)) i++
        return tokens.drop(i)
    }

    fun parse(text: String): VoiceIntent {
        val all = ArabicNormalizer.tokens(text)
        if (all.isEmpty()) return VoiceIntent.Unknown(text)
        val tokens = cleanLeading(all)
        if (tokens.isEmpty()) return VoiceIntent.Unknown(text)

        // 1) Stop listening
        if (tokens.first() in stopWords && (tokens.size <= 3 || tokens.drop(1).any { it in listenWords }) && tokens.none { it in callVerbs }) {
            return VoiceIntent.StopListening
        }
        // 2) Open the app
        if (tokens.first() in openWords && tokens.drop(1).any { it in appWords }) return VoiceIntent.OpenApp

        // 3) Redial: "أعد الاتصال", "اتصل بآخر رقم", "عاود الاتصال"
        val hasRedialVerb = tokens.first() in redialWords && tokens.drop(1).any { it in callVerbs || it == "الاتصال" || it in lastWords || it in numberKeywords }
        val callThenLast = tokens.first() in callVerbs && tokens.drop(1).take(2).any { it in lastWords }
        if (hasRedialVerb || callThenLast) return VoiceIntent.Redial

        // 4) Call
        val verbIndex = findCallVerb(tokens)
        if (verbIndex >= 0) {
            var rest = tokens.drop(verbIndex + 1)
            // "دق لي", "اتصل لي": drop attached "لي"
            rest = rest.dropWhile { it == "لي" || it == "بي" || it == "عليه" }
            var numberHinted = false
            if (rest.size > 1 && rest.first() == "علي" &&
                (rest[1] in numberKeywords || ArabicNumberParser.isNumberToken(rest[1]))) rest = rest.drop(1)
            // "علي" is kept: it can be the name Ali or colloquial "على" (handled in nameVariants).
            while (rest.isNotEmpty() && rest.first() != "علي" &&
                (rest.first() in prepositions || rest.first() in numberKeywords || rest.first() in politeness)) {
                if (rest.first() in numberKeywords) numberHinted = true
                rest = rest.drop(1)
            }
            if (rest.isEmpty()) return VoiceIntent.Unknown(text)

            // Number?
            val numberish = rest.count { ArabicNumberParser.isNumberToken(it) }
            if (numberHinted || numberish >= 3 || (numberish == rest.size && rest.isNotEmpty())) {
                val parsed = ArabicNumberParser.parse(rest)
                if (parsed != null && ArabicNumberParser.isDialable(parsed.digits)) return VoiceIntent.CallNumber(parsed.digits)
                if (numberHinted) return VoiceIntent.Unknown(text)
            }

            // Name: drop trailing politeness ("لو سمحت")
            val nameTokens = stripTrailing(rest).ifEmpty { rest }
            val query = nameTokens.joinToString(" ")
            return VoiceIntent.CallContact(query, nameVariants(nameTokens))
        }

        // 5) Short answers used during disambiguation / confirmation
        val joined = tokens.joinToString(" ")
        if (tokens.any { it in cancelWords } || joined in cancelWords) return VoiceIntent.Cancel
        ordinals[joined]?.let { return VoiceIntent.Choice(it) }
        tokens.firstNotNullOfOrNull { ordinals[it]?.takeIf { _ -> tokens.size <= 2 } }?.let { return VoiceIntent.Choice(it) }
        if (tokens.size <= 2 && tokens.first() in yesWords) return VoiceIntent.Yes
        if (tokens.size <= 2 && tokens.first() in noWords) return VoiceIntent.No

        return VoiceIntent.Unknown(text)
    }

    private fun stripTrailing(tokens: List<String>): List<String> {
        var t = tokens
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (p in trailingPhrases) {
                if (t.size > p.size && t.takeLast(p.size) == p) { t = t.dropLast(p.size); changed = true }
            }
        }
        return t
    }

    private fun findCallVerb(tokens: List<String>): Int {
        // The verb is expected near the start (after wake/politeness words).
        for (i in 0 until minOf(tokens.size, 3)) if (tokens[i] in callVerbs && tokens[i] != "شغل") return i
        return -1
    }

    /**
     * Alternative readings of the name, e.g. "بهيثم العريفي" -> "هيثم العريفي",
     * "علي احمد" (على + احمد) -> "احمد". The matcher scores all of them.
     */
    fun nameVariants(nameTokens: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        out += nameTokens.joinToString(" ")
        val first = nameTokens.firstOrNull() ?: return out.toList()
        if ((first.startsWith("ب") || first.startsWith("ل")) && first.length >= 4) {
            out += (listOf(first.substring(1)) + nameTokens.drop(1)).joinToString(" ")
        }
        if (first.startsWith("بال") && first.length >= 5) {
            out += (listOf(first.substring(1)) + nameTokens.drop(1)).joinToString(" ")
        }
        // "علي" may be the colloquial preposition "على" (normalised the same).
        if (first == "علي" && nameTokens.size > 1) out += nameTokens.drop(1).joinToString(" ")
        // "ع" + name, "عل" + name
        return out.filter { it.isNotBlank() }
    }
}
