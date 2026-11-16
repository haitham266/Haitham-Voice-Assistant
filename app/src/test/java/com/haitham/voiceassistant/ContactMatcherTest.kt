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
    private val resolver = CommandResolver(ArabicCommandParser(), matcher)

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

    @Test fun haithamAndHaithamOfficeIsAmbiguous() {
        // "هيثم" alone matches the Arabic contact and the Latin "Haitham Office" -> must ask
        val d = decide("اتصل بهيثم")
        assertTrue("$d", d is Decision.AskChoice)
    }

    @Test fun similarButDifferentSurnameDoesNotCallBlindly() {
        // "محمد سالم" vs "محمد صالح": the full name must win clearly
        val d = decide("اتصل بمحمد سالم")
        assertTrue("$d", d is Decision.CallCandidate)
        assertEquals("711222333", (d as Decision.CallCandidate).candidate.phone.number)
    }

    @Test fun wrongSurnameIsNotConfident() {
        val d = decide("اتصل بهيثم الحربي")
        assertFalse("must not call directly: $d", d is Decision.CallCandidate)
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
        // low recogniser confidence -> read the number back and ask
        assertEquals(Decision.ConfirmDigits("777"), resolver.resolveCommand(listOf(hyp("اتصل بالرقم سبعة سبعة سبعة", 0.4f))))
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

    @Test fun vadGatesSilence() {
        val vad = EnergyVad(1)
        repeat(30) { assertFalse(vad.update(80.0)) } // quiet room
        assertTrue(vad.update(2500.0))              // speech
        repeat(8) { assertTrue(vad.update(80.0)) }  // hangover keeps feeding the tail
        assertFalse(vad.update(80.0))
    }
}
