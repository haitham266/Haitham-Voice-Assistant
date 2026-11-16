package com.haitham.voiceassistant.assistant

import com.haitham.voiceassistant.contacts.ContactMatcher
import com.haitham.voiceassistant.model.ContactCandidate
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.model.MatchResult
import com.haitham.voiceassistant.model.VoiceIntent
import com.haitham.voiceassistant.nlu.ArabicCommandParser
import com.haitham.voiceassistant.nlu.ArabicNormalizer

/** What the assistant should do after a recognition round. Pure logic, JVM-tested. */
sealed class Decision {
    data class CallCandidate(val candidate: ContactCandidate) : Decision()
    data class CallDigits(val digits: String) : Decision()
    data class ConfirmDigits(val digits: String) : Decision()
    data class AskChoice(val candidates: List<ContactCandidate>) : Decision()
    data class AskConfirm(val candidate: ContactCandidate) : Decision()
    data object Redial : Decision()
    data object Stop : Decision()
    data object OpenApp : Decision()
    data object Cancelled : Decision()
    data class NotFound(val name: String) : Decision()
    data class NotUnderstood(val text: String) : Decision()
    data object NothingHeard : Decision()
}

class CommandResolver(
    private val parser: ArabicCommandParser,
    private val matcher: ContactMatcher,
    /**
     * false (default, "iPhone-like"): always call the best match immediately; ask only
     * when two people have practically the same name and nothing breaks the tie.
     * true: the cautious mode (confirm uncertain names and low-confidence numbers).
     */
    private val askWhenUnsure: Boolean = false,
    /** In cautious mode numbers are dialled directly only if the recogniser was this sure. */
    private val minNumberConfidence: Float = 0.6f
) {

    /** Picks one candidate among close ones: clearly best score, then starred, then most called. */
    private fun pickDecisive(cands: List<ContactCandidate>): ContactCandidate? {
        val sorted = cands.sortedByDescending { it.score }
        val top = sorted[0]
        val second = sorted.getOrNull(1) ?: return top
        if (top.score - second.score >= TIE_EPSILON) return top
        val tied = sorted.filter { top.score - it.score < TIE_EPSILON }
        val starred = tied.filter { it.contact.starred }
        if (starred.size == 1) return starred[0]
        val pool = if (starred.isNotEmpty()) starred else tied
        val maxCalls = pool.maxOf { it.contact.callCount }
        val mostCalled = pool.filter { it.contact.callCount == maxCalls }
        return if (maxCalls > 0 && mostCalled.size == 1) mostCalled[0] else null
    }

    companion object {
        /** Scores closer than this are treated as the same name. */
        const val TIE_EPSILON = 0.02
    }

    fun resolveCommand(hyps: List<Hypothesis>): Decision {
        val usable = hyps.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return Decision.NothingHeard
        val parsed = usable.map { it to parser.parse(it.text) }

        val first = parsed.firstOrNull { it.second !is VoiceIntent.Unknown } ?: return Decision.NotUnderstood(usable.first().text)
        val (hyp, intent) = first
        return when (intent) {
            VoiceIntent.StopListening -> Decision.Stop
            VoiceIntent.OpenApp -> Decision.OpenApp
            VoiceIntent.Redial -> Decision.Redial
            is VoiceIntent.CallNumber ->
                if (!askWhenUnsure || hyp.meanConfidence >= minNumberConfidence) Decision.CallDigits(intent.digits)
                else Decision.ConfirmDigits(intent.digits)
            is VoiceIntent.CallContact -> {
                // Combine the name readings of every recogniser that understood a call.
                val variants = parsed.mapNotNull { (it.second as? VoiceIntent.CallContact)?.alternatives }.flatten().distinct()
                when (val m = matcher.match(variants)) {
                    is MatchResult.Confident -> Decision.CallCandidate(m.candidate)
                    is MatchResult.Ambiguous ->
                        if (askWhenUnsure) Decision.AskChoice(m.candidates)
                        else pickDecisive(m.candidates)?.let { Decision.CallCandidate(it) } ?: Decision.AskChoice(
                            m.candidates.filter { m.candidates.maxOf { c -> c.score } - it.score < TIE_EPSILON }
                        )
                    is MatchResult.NeedsConfirmation ->
                        if (askWhenUnsure) Decision.AskConfirm(m.candidate) else Decision.CallCandidate(m.candidate)
                    MatchResult.NotFound -> Decision.NotFound(intent.nameQuery)
                }
            }
            VoiceIntent.Cancel, VoiceIntent.No -> Decision.Cancelled
            else -> Decision.NotUnderstood(hyp.text)
        }
    }

    /** The user answers "الأول / الثاني / <full name> / إلغاء" while choosing between people. */
    fun resolveChoice(hyps: List<Hypothesis>, candidates: List<ContactCandidate>): Decision {
        val usable = hyps.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return Decision.NothingHeard
        for (h in usable) {
            when (val i = parser.parse(h.text)) {
                is VoiceIntent.Choice -> return candidates.getOrNull(i.index)?.let { Decision.CallCandidate(it) } ?: Decision.NotUnderstood(h.text)
                VoiceIntent.Cancel, VoiceIntent.No -> return Decision.Cancelled
                else -> Unit
            }
        }
        // Spoken name: match only among the candidates; must be clearly one of them.
        val sub = ContactMatcher(confidentScore = 0.75, ambiguityGap = 0.06)
        sub.setContacts(candidates.map { it.contact })
        val texts = usable.flatMap { h ->
            val p = parser.parse(h.text)
            if (p is VoiceIntent.CallContact) p.alternatives else listOf(stripWake(h.text))
        }
        return when (val m = sub.match(texts)) {
            is MatchResult.Confident -> candidates.first { it.contact.id == m.candidate.contact.id }.let { Decision.CallCandidate(it) }
            else -> Decision.NotUnderstood(usable.first().text)
        }
    }

    /** Yes / no confirmation of a single uncertain candidate. */
    fun resolveConfirm(hyps: List<Hypothesis>): Boolean? {
        for (h in hyps.filter { it.text.isNotBlank() }) {
            when (parser.parse(h.text)) {
                VoiceIntent.Yes -> return true
                VoiceIntent.No, VoiceIntent.Cancel -> return false
                else -> Unit
            }
        }
        return null
    }

    private fun stripWake(t: String) = ArabicNormalizer.tokens(t).filter { it != "يا" && it != "مساعد" }.joinToString(" ")
}

/** Prevents the same command from running twice (echo, repeated result, double wake). */
class CommandDeduplicator(private val windowMs: Long = 8000) {
    private var lastSig: String? = null
    private var lastAt = 0L

    @Synchronized
    fun shouldExecute(signature: String, nowMs: Long): Boolean {
        if (signature == lastSig && nowMs - lastAt < windowMs) return false
        lastSig = signature; lastAt = nowMs
        return true
    }
}
