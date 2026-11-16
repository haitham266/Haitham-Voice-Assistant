package com.haitham.voiceassistant.contacts

import com.haitham.voiceassistant.model.Contact
import com.haitham.voiceassistant.model.ContactCandidate
import com.haitham.voiceassistant.model.MatchResult
import com.haitham.voiceassistant.model.PhoneEntry
import com.haitham.voiceassistant.nlu.ArabicNormalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Fuzzy matcher between a spoken name and the phone's contacts.
 *
 * Safety rules (the assistant calls without asking only when it is sure):
 *  - Confident: best score >= [confidentScore], every spoken word matched well,
 *    and no *other person* within [ambiguityGap] of it.
 *  - Ambiguous: several different people score close to each other -> ask the user.
 *  - NeedsConfirmation: a plausible but uncertain match -> ask yes/no.
 *  - NotFound: nothing reasonable.
 */
class ContactMatcher(
    private val confidentScore: Double = 0.78,
    private val minScore: Double = 0.60,
    private val ambiguityGap: Double = 0.10,
    private val minTokenForConfident: Double = 0.72
) {

    private class Prepared(val contact: Contact, val tokens: List<String>, val joined: String)

    private var prepared: List<Prepared> = emptyList()

    fun setContacts(contacts: List<Contact>) {
        prepared = contacts.filter { it.phones.isNotEmpty() && it.displayName.isNotBlank() }.map {
            val t = ArabicNormalizer.tokens(it.displayName)
            Prepared(it, t, t.joinToString(""))
        }
    }

    data class Scored(val score: Double, val minToken: Double)

    fun match(queries: List<String>): MatchResult {
        val qs = queries.map { ArabicNormalizer.tokens(it) }.filter { it.isNotEmpty() }
        if (qs.isEmpty() || prepared.isEmpty()) return MatchResult.NotFound

        val scored = prepared.map { p ->
            val best = qs.map { q -> score(q, p.tokens, p.joined) }.maxByOrNull { it.score }!!
            p to best
        }.filter { it.second.score >= minScore }
            .sortedByDescending { it.second.score }
        if (scored.isEmpty()) return MatchResult.NotFound

        // Merge duplicates of the same person (same number saved twice, SIM + Google ...).
        val byPerson = LinkedHashMap<String, Pair<Prepared, Scored>>()
        for (s in scored) {
            val key = phoneKey(choosePhone(s.first.contact).number)
            if (key !in byPerson) byPerson[key] = s
        }
        val people = byPerson.values.toList()
        val top = people.first()
        val topCandidate = ContactCandidate(top.first.contact, choosePhone(top.first.contact), top.second.score)

        val rivals = people.drop(1).filter { it.second.score >= top.second.score - ambiguityGap }
        if (rivals.isNotEmpty()) {
            return MatchResult.Ambiguous(
                (listOf(top) + rivals).take(4).map {
                    ContactCandidate(it.first.contact, choosePhone(it.first.contact), it.second.score)
                }
            )
        }
        return if (top.second.score >= confidentScore && top.second.minToken >= minTokenForConfident) {
            MatchResult.Confident(topCandidate)
        } else MatchResult.NeedsConfirmation(topCandidate)
    }

    /** Score of spoken tokens [q] against a contact name. */
    fun score(q: List<String>, c: List<String>, cJoined: String = c.joinToString("")): Scored {
        if (q.isEmpty() || c.isEmpty()) return Scored(0.0, 0.0)
        // Greedy one-to-one assignment of spoken words to contact name words.
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for (i in q.indices) for (j in c.indices) pairs += Triple(i, j, tokenSim(q[i], c[j]))
        pairs.sortByDescending { it.third }
        val qBest = DoubleArray(q.size)
        val cBest = DoubleArray(c.size)
        val usedQ = BooleanArray(q.size)
        val usedC = BooleanArray(c.size)
        for ((i, j, s) in pairs) {
            if (usedQ[i] || usedC[j]) continue
            usedQ[i] = true; usedC[j] = true
            qBest[i] = s; cBest[j] = s
        }
        val qScore = qBest.average()
        val coverage = cBest.average()
        var total = 0.75 * qScore + 0.25 * coverage
        var minTok = qBest.min()

        // Handles split/joined writing: "عبدالله" vs "عبد الله", "ابوبكر" vs "ابو بكر".
        val qJoined = q.joinToString("")
        if (qJoined.length >= 4) {
            val js = ratio(qJoined, cJoined)
            if (js > total) { total = js; minTok = max(minTok, js) }
        }
        return Scored(total, minTok)
    }

    fun tokenSim(a: String, b: String): Double {
        if (a == b) return 1.0
        val aa = ArabicNormalizer.stripArticle(a)
        val bb = ArabicNormalizer.stripArticle(b)
        if (aa == bb) return 0.97
        var best = ratio(a, b)
        best = max(best, ratio(aa, bb) * 0.97)
        val pa = ArabicNormalizer.phonetic(aa)
        val pb = ArabicNormalizer.phonetic(bb)
        best = max(best, if (pa == pb) 0.93 else ratio(pa, pb) * 0.92)
        // Arabic speech vs contact saved in Latin letters (or the opposite).
        if (ArabicNormalizer.isArabic(a) != ArabicNormalizer.isArabic(b)) {
            val sa = ArabicNormalizer.latinSkeleton(aa)
            val sb = ArabicNormalizer.latinSkeleton(bb)
            if (sa.length >= 2 && sb.length >= 2) best = max(best, if (sa == sb) 0.9 else ratio(sa, sb) * 0.85)
        }
        return best
    }

    companion object {
        fun ratio(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val d = levenshtein(a, b)
            return 1.0 - d.toDouble() / max(a.length, b.length)
        }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        /** Preferred number of a contact: super-primary, then mobile, then the first. */
        fun choosePhone(c: Contact): PhoneEntry =
            c.phones.firstOrNull { it.isPrimary }
                ?: c.phones.firstOrNull { it.type == 2 /* Phone.TYPE_MOBILE */ }
                ?: c.phones.first()

        /**
         * Comparison key only (never used for dialling): last 9 digits, which is
         * the national significant number length in Yemen (7XXXXXXXX) and close
         * to it for most neighbouring countries.
         */
        fun phoneKey(number: String): String {
            val d = number.filter { it.isDigit() }
            return if (d.length > 9) d.takeLast(9) else d
        }
    }
}
