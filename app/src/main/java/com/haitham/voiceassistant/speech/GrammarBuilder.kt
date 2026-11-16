package com.haitham.voiceassistant.speech

import com.haitham.voiceassistant.contacts.ContactMatcher
import com.haitham.voiceassistant.nlu.ArabicNormalizer
import java.io.File

/**
 * Looks up which words exist in the recogniser's vocabulary (graph/words.txt).
 * A Vosk grammar may only contain in-vocabulary words; unknown words are
 * silently dropped by the engine, so we check them ourselves and report them.
 */
class Vocabulary(private val wordsFile: File) {

    val available: Boolean get() = wordsFile.exists()

    /**
     * Resolves each requested word (any spelling) to the surface form used by the
     * model. Exact matches win; otherwise a word with the same normalised form.
     */
    fun resolve(words: Collection<String>): Map<String, String> {
        if (!available || words.isEmpty()) return emptyMap()
        val wantedExact = words.toHashSet()
        val wantedNorm = HashMap<String, MutableList<String>>()
        for (w in words) wantedNorm.getOrPut(ArabicNormalizer.normalize(w)) { mutableListOf() } += w
        val result = HashMap<String, String>()
        wordsFile.bufferedReader().useLines { lines ->
            for (line in lines) {
                val sp = line.indexOf(' ')
                val word = if (sp > 0) line.substring(0, sp) else line
                if (word.isEmpty() || word.startsWith("<") || word.startsWith("#")) continue
                if (word in wantedExact) result[word] = word
                val n = wantedNorm[ArabicNormalizer.normalize(word)] ?: continue
                for (orig in n) if (orig !in result) result[orig] = word
            }
        }
        return result
    }
}

object GrammarBuilder {

    /** Common words that absorb non-wake speech (garbage model for the wake grammar). */
    val fillerWords: List<String> = """
        في من على إلى الى عن مع هذا هذه ذلك كان كانت لا نعم أيوه شو ايش وين فين كيف كيفك مرحبا
        السلام عليكم اليوم بكرة امس الخير صباح انا انت هو هي احنا نحن هم والله يعني بس طيب تمام زين
        خلاص الحين الآن هنا هناك ليش لماذا متى كم شي شيء حاجة واحد اثنين ثلاثة اربعة خمسة ستة سبعة
        ثمانية تسعة عشرة اتصل كلم دق افتح الرقم سيارة بيت شغل ماء اكل قهوة شاي ولد بنت رجل امرأة الله
        محمد أحمد علي سالم قال قلت يقول سمعت شفت جيت روح تعال ابغى اريد ابي عايز ما لم لن قد كل بعض
        اخر أول جديد كبير صغير حلو سلام اخوي ابوي امي اهل صاحبي فلوس سوق مدرسة جامعة عمل وقت يوم ليل نهار
        باب غرفة تلفون جوال موبايل رسالة صورة اغنية تلفزيون نور كهرباء طريق مكان عربية بلاد صنعاء عدن تعز
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()

    /** Words that sound too close to "يا مساعد" in tests and steal real detections. */
    private val wakeConfusables = setOf("مساعدة", "ساعد", "مسافر", "مسعود", "سعيد", "مساء", "مسا", "ساعة", "ساعات", "يا", "اهلا", "موش", "مش")

    val callWords = listOf(
        "اتصل", "اتصلي", "اتصال", "الاتصال", "كلم", "كلملي", "كلمني", "دق", "دقي", "دقلي", "عيط", "عيطلي",
        "رن", "ارن", "اضرب", "اطلب", "على", "ب", "ل", "مع", "الى", "لي", "بالرقم", "الرقم", "رقم",
        "أعد", "اعد", "عاود", "آخر", "بآخر", "الأخير", "أوقف", "اوقف", "وقف", "توقف", "الاستماع", "افتح",
        "التطبيق", "البرنامج", "لو", "سمحت", "من", "فضلك", "زائد"
    )
    val numberWords = listOf(
        "صفر", "واحد", "اثنين", "ثنين", "ثلاثة", "ثلاث", "أربعة", "اربعة", "خمسة", "ستة", "سبعة", "ثمانية",
        "تسعة", "عشرة", "عشرين", "ثلاثين", "أربعين", "خمسين", "ستين", "سبعين", "ثمانين", "تسعين", "مية",
        "مائة", "ميتين", "وعشرين", "وثلاثين", "وأربعين", "وخمسين", "وستين", "وسبعين", "وثمانين", "وتسعين", "و"
    )
    val answerWords = listOf(
        "الأول", "الاول", "الثاني", "الثالث", "الرابع", "نعم", "ايوه", "أيوه", "لا", "إلغاء", "الغاء", "خلاص", "اكيد", "تمام"
    )

    /** Words that start a command in "without wake word" mode. */
    val triggerWords = listOf(
        "اتصل", "اتصلي", "دق", "دقي", "دقلي", "كلم", "كلمي", "كلملي", "عيط", "عيطلي", "رن", "ارن", "اضرب",
        "اطلب", "أعد", "اعد", "عاود", "أوقف", "اوقف", "توقف", "افتح"
    )

    val triggerWordsNormalized: Set<String> get() = triggerWords.map { ArabicNormalizer.normalize(it) }.toSet()

    /** Grammar for trigger mode: command verbs + fillers (that are not verbs). */
    fun triggerGrammar(vocab: Vocabulary): String {
        val trig = triggerWordsNormalized
        val resolved = vocab.resolve(triggerWords + fillerWords)
        val verbs = triggerWords.mapNotNull { resolved[it] }
        val fillers = fillerWords.mapNotNull { resolved[it] }.filter { ArabicNormalizer.normalize(it) !in trig }
        return toJson(verbs + fillers)
    }

    fun toJson(words: Collection<String>): String =
        words.distinct().joinToString(prefix = "[", postfix = "]", separator = ",") {
            "\"" + it.replace("\\", "").replace("\"", "") + "\""
        }

    private fun confusable(f: String, wakeTokens: List<String>): Boolean {
        if (f in wakeConfusables) return true
        val pf = ArabicNormalizer.phonetic(f)
        return wakeTokens.any { w ->
            val pw = ArabicNormalizer.phonetic(w)
            pf == pw || ContactMatcher.ratio(pf, pw) >= 0.6 || (pw.length >= 3 && (pf.contains(pw) || pw.contains(pf)))
        }
    }

    data class WakeGrammar(val json: String, val phrase: String, val missingWords: List<String>)

    /** Grammar = [wake phrase (model spelling)] + non-confusable fillers. */
    fun wakeGrammar(wakePhrase: String, vocab: Vocabulary): WakeGrammar {
        val raw = ArabicNormalizer.stripDiacritics(wakePhrase).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val resolved = vocab.resolve(raw + fillerWords)
        val missing = raw.filter { it !in resolved }
        val phrase = raw.joinToString(" ") { resolved[it] ?: it }
        val wakeNorm = raw.map { ArabicNormalizer.normalize(it) }
        val fillers = fillerWords.mapNotNull { resolved[it] }.filter { !confusable(it, wakeNorm) && ArabicNormalizer.normalize(it) !in wakeNorm }
        return WakeGrammar(toJson(listOf(phrase) + fillers), phrase, missing)
    }

    /**
     * Grammar for the command stage: call/stop/open verbs, numbers, answers,
     * and every in-vocabulary token of the user's contact names (plus the
     * attached-preposition forms "ب"+name and "ل"+name).
     */
    fun commandGrammar(contactNames: Collection<String>, vocab: Vocabulary): String {
        val nameTokens = LinkedHashSet<String>()
        for (n in contactNames) {
            for (t in ArabicNormalizer.stripDiacritics(n).split(Regex("[\\s\\-_.,]+"))) {
                if (t.length < 2 || !ArabicNormalizer.isArabic(t)) continue
                nameTokens += t; nameTokens += "ب$t"; nameTokens += "ل$t"
            }
        }
        val base = callWords + numberWords + answerWords + fillerWords.take(40)
        val resolved = vocab.resolve(base + nameTokens)
        val all = (base + nameTokens).mapNotNull { resolved[it] }
        return toJson(all)
    }

    /** Small grammar used while the user picks between similar contacts. */
    fun choiceGrammar(candidateNames: Collection<String>, vocab: Vocabulary): String {
        val tokens = candidateNames.flatMap { ArabicNormalizer.stripDiacritics(it).split(Regex("\\s+")) }.filter { it.length >= 2 }
        val base = answerWords + numberWords.take(12) + listOf("رقم", "اتصل", "ب") + fillerWords.take(20)
        val resolved = vocab.resolve(base + tokens)
        return toJson((base + tokens).mapNotNull { resolved[it] })
    }
}
