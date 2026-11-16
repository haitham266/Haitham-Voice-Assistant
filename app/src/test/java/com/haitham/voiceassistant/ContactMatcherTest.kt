package com.haitham.voiceassistant

import com.haitham.voiceassistant.assistant.CommandDeduplicator
import com.haitham.voiceassistant.assistant.CommandResolver
import com.haitham.voiceassistant.assistant.Decision
import com.haitham.voiceassistant.audio.EnergyVad
import com.haitham.voiceassistant.contacts.ContactMatcher
import com.haitham.voiceassistant.model.Contact
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.model.MatchResult
import com.haitham.voiceassistant.model.PhoneEntry
import com.haitham.voiceassistant.model.RecognizedWord
import com.haitham.voiceassistant.nlu.ArabicCommandParser
import com.haitham.voiceassistant.speech.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {
    private var nextId = 1L
    private fun c(name: String, vararg numbers: String) =
        Contact(nextId++, name, numbers.map { PhoneEntry(it, 2, null, false) })

    private val book = listOf(
        c("هيثم العريفي", "777123456"),
        c("أحمد علي", "+967 733 111 222"),
        c("محمد سالم", "711222333"),
        c("محمد صالح", "711999888"),
        c("عبدالله الحميري", "770000111"),
        c("Khaled Saeed", "+967772000333"),
        c("بلال", "735555444"),
        c("Haitham Office", "01234567")
    )
    private val matcher = ContactMatcher().apply { setContacts(book) }
    private val resolver = CommandResolver(ArabicCommandParser(), matcher)           // default: decisive (no questions)
    private val cautious = CommandResolver(ArabicCommandParser(), matcher, askWhenUnsure = true)

    private fun hyp(text: String, conf: Float = 0.95f) =
        Hypothesis(text, text.split(' ').map { RecognizedWord(it, conf, 0f, 0f) }, Hypothesis.Source.GRAMMAR)

    private fun decide(text: String) = resolver.resolveCommand(listOf(hyp(text)))

    @Test fun exactFullNameCallsDirectly() {
        val d = decide("اتصل بهيثم العريفي")
        assertTrue("$d", d is Decision.CallCandidate)
        assertEquals("777123456", (d as Decision.CallCandidate).candidate.phone.number)
    }

    @Test fun firstNameUniqueCallsDirectly() {
        val d = decide("دق على أحمد")
        assertTrue("$d", d is Decision.CallCandidate)
        assertEquals("+967 733 111 222", (d as Decision.CallCandidate).candidate.phone.number) // original format preserved
    }

    @Test fun sameFirstNameAsksUser() {
        val d = decide("اتصل بمحمد")
        assertTrue("$d", d is Decision.AskChoice)
        assertEquals(2, (d as Decision.AskChoice).candidates.size)
    }

    @Test fun haithamAndHaithamOfficeIsAmbiguousInCautiousMode() {
        // "هيثم" alone matches the Arabic contact and the Latin "Haitham Office"
        assertTrue(cautious.resolveCommand(listOf(hyp("اتصل بهيثم"))) is Decision.AskChoice)
        // decisive mode calls the closer (Arabic) one immediately
        val d = decide("اتصل بهيثم")
        assertTrue("$d", d is Decision.CallCandidate)
        assertEquals("777123456", (d as Decision.CallCandidate).candidate.phone.number)
    }

    @Test fun similarButDifferentSurnameDoesNotCallBlindly() {
        // "محمد سالم" vs "محمد صالح": the full name must win clearly
        val d = decide("اتصل بمحمد سالم")
        assertTrue("$d", d is Decision.CallCandidate)
        assertEquals("711222333", (d as Decision.CallCandidate).candidate.phone.number)
    }

    @Test fun wrongSurname() {
        // cautious mode asks; decisive mode calls the closest name
        assertFalse(cautious.resolveCommand(listOf(hyp("اتصل بهيثم الحربي"))) is Decision.CallCandidate)
        assertTrue(decide("اتصل بهيثم الحربي") is Decision.CallCandidate)
    }

    @Test fun joinedSpelling() {
        val d = decide("اتصل بعبد الله الحميري")
        assertTrue("$d", d is Decision.CallCandidate)
    }

    @Test fun arabicSpeechMatchesLatinContact() {
        val d = decide("اتصل بخالد سعيد")
        assertTrue("$d", d is Decision.CallCandidate)
        assertEquals("+967772000333", (d as Decision.CallCandidate).candidate.phone.number)
    }

    @Test fun nameStartingWithBa() {
        val d = decide("اتصل ببلال")
        assertTrue("$d", d is Decision.CallCandidate)
    }

    @Test fun unknownName() {
        assertTrue(decide("اتصل بسميرة") is Decision.NotFound)
    }

    @Test fun numbers() {
        assertEquals(Decision.CallDigits("777123456"), decide("اتصل بالرقم سبعة سبعة سبعة واحد اثنين ثلاثة أربعة خمسة ستة"))
        // low recogniser confidence: decisive mode still dials, cautious mode reads it back
        assertEquals(Decision.CallDigits("777"), resolver.resolveCommand(listOf(hyp("اتصل بالرقم سبعة سبعة سبعة", 0.4f))))
        assertEquals(Decision.ConfirmDigits("777"), cautious.resolveCommand(listOf(hyp("اتصل بالرقم سبعة سبعة سبعة", 0.4f))))
    }

    @Test fun gibberish() {
        assertTrue(decide("مرحبا كيف حالك") is Decision.NotUnderstood)
        assertEquals(Decision.NothingHeard, resolver.resolveCommand(emptyList()))
    }

    @Test fun duplicatePersonSavedTwiceIsNotAmbiguous() {
        val m = ContactMatcher().apply { setContacts(listOf(c("سالم", "777000111"), c("سالم", "+967777000111"))) }
        assertTrue(m.match(listOf("سالم")) is MatchResult.Confident)
    }

    @Test fun choiceResolution() {
        val d = decide("اتصل بمحمد") as Decision.AskChoice
        val second = resolver.resolveChoice(listOf(hyp("الثاني")), d.candidates)
        assertEquals(d.candidates[1], (second as Decision.CallCandidate).candidate)
        val byName = resolver.resolveChoice(listOf(hyp("محمد صالح")), d.candidates)
        assertEquals("711999888", (byName as Decision.CallCandidate).candidate.phone.number)
        assertEquals(Decision.Cancelled, resolver.resolveChoice(listOf(hyp("إلغاء")), d.candidates))
    }

    /** Real case recorded on the user's phone: contact "عمنا مختار", heard "اتصل بعي لمنى مختار". */
    private val family = listOf(
        c("عمنا مختار", "+967 772 408 936"), c("مختار علي", "771000001"), c("مختار سالم", "771000002"),
        c("ابو مختار", "771000003"), c("مختار", "771000004"), c("عمنا محمد", "771000005"), c("خالي مختار", "771000006")
    )
    private val familyResolver = CommandResolver(ArabicCommandParser(), ContactMatcher(minScore = 0.55).apply { setContacts(family) })

    private fun familyCall(text: String): String? =
        (familyResolver.resolveCommand(listOf(hyp(text))) as? Decision.CallCandidate)?.candidate?.contact?.displayName

    @Test fun kinshipNamesCallDirectly() {
        assertEquals("عمنا مختار", familyCall("اتصل بعي لمنى مختار"))   // exactly what the phone heard
        assertEquals("عمنا مختار", familyCall("اتصل بعمنا مختار"))
        assertEquals("عمنا مختار", familyCall("اتصل ب عمنا مختار"))
        assertEquals("عمنا مختار", familyCall("يا مساعد دق على عمنا مختار"))
        assertEquals("خالي مختار", familyCall("اتصل بخالي مختار"))
        assertEquals("مختار سالم", familyCall("كلم مختار سالم"))
        assertEquals("مختار", familyCall("اتصل بمختار"))
        assertEquals("عمنا محمد", familyCall("اتصل بعمنا محمد"))
    }

    @Test fun exactTieUsesStarredThenAsks() {
        val twins = listOf(
            Contact(100, "محمد", listOf(PhoneEntry("770000001", 2, null, false)), starred = false),
            Contact(101, "محمد", listOf(PhoneEntry("770000002", 2, null, false)), starred = true)
        )
        val r = CommandResolver(ArabicCommandParser(), ContactMatcher().apply { setContacts(twins) })
        val d = r.resolveCommand(listOf(hyp("اتصل بمحمد")))
        assertEquals("770000002", (d as Decision.CallCandidate).candidate.phone.number)
        val busy = listOf(twins[0].copy(callCount = 9), twins[1].copy(starred = false, callCount = 2))
        val r2 = CommandResolver(ArabicCommandParser(), ContactMatcher().apply { setContacts(busy) })
        assertEquals("770000001", ((r2.resolveCommand(listOf(hyp("اتصل بمحمد")))) as Decision.CallCandidate).candidate.phone.number)
        val plain = listOf(twins[0], twins[1].copy(starred = false))
        val r3 = CommandResolver(ArabicCommandParser(), ContactMatcher().apply { setContacts(plain) })
        assertTrue(r3.resolveCommand(listOf(hyp("اتصل بمحمد"))) is Decision.AskChoice) // truly identical: only case that asks
    }

    @Test fun dedupSuppressesRepeat() {
        val dd = CommandDeduplicator(8000)
        assertTrue(dd.shouldExecute("call:777", 1000))
        assertFalse(dd.shouldExecute("call:777", 3000))
        assertTrue(dd.shouldExecute("call:777", 12000))
        assertTrue(dd.shouldExecute("call:711", 12500))
    }

    private fun w(word: String, conf: Float, s: Float, e: Float) = RecognizedWord(word, conf, s, e)

    @Test fun wakeDetector() {
        val d = WakeWordDetector("يا مساعد", 1)
        assertTrue(d.detect(listOf(w("يا", 0.99f, 0.1f, 0.3f), w("مساعد", 0.99f, 0.3f, 0.8f))) != null)
        assertNull(d.detect(listOf(w("مساعد", 0.9f, 0.3f, 0.8f)))) // second word alone: not at medium
        assertNull(d.detect(listOf(w("يا", 0.3f, 0.1f, 0.3f), w("مساعد", 0.3f, 0.3f, 0.8f)))) // low confidence
        assertNull(d.detect(listOf(w("كلم", 0.85f, 0f, 0.4f))))
        val high = WakeWordDetector("يا مساعد", 2)
        assertTrue(high.detect(listOf(w("مساعد", 0.9f, 0.3f, 0.8f))) != null)
        // real values measured with the bundled model on synthetic speech (t3: 0.66 / 0.44)
        assertTrue(d.detect(listOf(w("يا", 0.66f, 0f, 0.2f), w("مساعد", 0.44f, 0.2f, 0.7f))) != null)
        assertNull(WakeWordDetector("يا مساعد", 0).detect(listOf(w("يا", 0.66f, 0f, 0.2f), w("مساعد", 0.44f, 0.2f, 0.7f))))
    }

    @Test fun directModeTriggerWords() {
        val trig = com.haitham.voiceassistant.speech.GrammarBuilder.triggerWordsNormalized
        val d = WakeWordDetector("يا مساعد", 1, trig)
        assertTrue(d.isTriggerMode)
        assertTrue(d.matchesPartial("اتصل بعمنا"))
        assertTrue(d.matchesPartial("دق على"))
        assertTrue(d.matchesPartial("كلم"))
        assertFalse(d.matchesPartial("مرحبا كيف حالك"))
        assertFalse(d.matchesPartial("يا مساعد")) // the wake phrase itself is not a trigger in direct mode
        assertTrue(d.detect(listOf(w("في", 0.9f, 0f, 0.2f), w("اتصل", 0.8f, 0.2f, 0.6f))) != null)
        assertNull(d.detect(listOf(w("اتصل", 0.2f, 0.2f, 0.6f)))) // too unsure
        // wake-phrase mode is unchanged and accepts partials with the phrase
        val wake = WakeWordDetector("يا مساعد", 1)
        assertFalse(wake.isTriggerMode)
        assertTrue(wake.matchesPartial("يا مساعد اتصل"))
        assertFalse(wake.matchesPartial("اتصل بأحمد"))
    }

    @Test fun aiStyleTranscripts() {
        // Typical full sentences from a neural recogniser (with or without the wake phrase).
        assertEquals("عمنا مختار", familyCall("يا مساعد اتصل بعمنا مختار"))
        assertEquals("عمنا مختار", familyCall("اتصل على عمنا مختار"))
        assertEquals("عمنا مختار", familyCall("يامساعد اتصل بعمنا مختار"))
        assertEquals("خالي مختار", familyCall("دق لخالي مختار لو سمحت"))
    }

    /**
     * Exact Whisper Turbo outputs on the user's own voice (screen recordings from the
     * Galaxy S22 Ultra), resolved against a phone book like the user's.
     */
    @Test fun whisperOnUsersRealVoice() {
        val book = listOf(
            c("عمنا مختار", "+967 772 408 936"), c("مختار سالم", "771000002"), c("عرفات العليمي", "771000010"),
            c("هيثم العريفي", "771000011"), c("ام الغالية", "771000012"), c("عمنا مطيع", "771000013"),
            c("جميل التهامي", "715101199"), c("خالي مختار", "771000006")
        )
        val r = CommandResolver(ArabicCommandParser(), ContactMatcher(minScore = 0.55).apply { setContacts(book) })
        fun call(t: String) = (r.resolveCommand(listOf(hyp(t))) as? Decision.CallCandidate)?.candidate?.contact?.displayName
        assertEquals("عمنا مختار", call("يمشاعد اتصل بعمنا مختار"))
        assertEquals("عرفات العليمي", call("يا مساعد اتصل بعرفات العليمي"))
        assertEquals("هيثم العريفي", call("يا مساعد اتصل بحيثم العريفين"))
        assertEquals("ام الغالية", call("اتصل بأم الغالية"))
        assertEquals("عمنا مطيع", call("يا مساعد اتصل بعما نوطيع"))
        assertEquals("عمنا مختار", call("يا مساعد بتصل بعما لا مختار"))
        // Whisper "hallucinations" on silence/noise must never call anyone
        for (noise in listOf("شكرا", "ترجمة نانسي", "اشتركوا في الق", "تمال إلغاء", "المراح")) {
            assertFalse(noise, r.resolveCommand(listOf(hyp(noise))) is Decision.CallCandidate)
        }
    }

    @Test fun vadGatesSilence() {
        val vad = EnergyVad(1)
        repeat(30) { assertFalse(vad.update(80.0)) } // quiet room
        assertTrue(vad.update(2500.0))              // speech
        repeat(8) { assertTrue(vad.update(80.0)) }  // hangover keeps feeding the tail
        assertFalse(vad.update(80.0))
    }
}
