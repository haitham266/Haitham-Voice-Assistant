package com.haitham.voiceassistant

import com.haitham.voiceassistant.model.VoiceIntent
import com.haitham.voiceassistant.nlu.ArabicCommandParser
import com.haitham.voiceassistant.nlu.ArabicNormalizer
import com.haitham.voiceassistant.nlu.ArabicNumberParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicCommandParserTest {
    private val p = ArabicCommandParser("يا مساعد")

    private fun contact(text: String): VoiceIntent.CallContact {
        val i = p.parse(text)
        assertTrue("expected CallContact for '$text' but was $i", i is VoiceIntent.CallContact)
        return i as VoiceIntent.CallContact
    }

    @Test fun callFullName() {
        val i = contact("اتصل بهيثم العريفي")
        assertTrue(i.alternatives.contains("هيثم العريفي"))
    }

    @Test fun wakeAndCommandInOneUtterance() {
        assertTrue(contact("يا مساعد اتصل بأحمد").alternatives.contains("احمد"))
    }

    @Test fun dialectVerbs() {
        assertTrue(contact("دق على هيثم").alternatives.contains("هيثم"))
        assertTrue(contact("كلم أحمد").alternatives.contains("احمد"))
        assertTrue(contact("عيط لمحمد سالم").alternatives.contains("محمد سالم"))
        assertTrue(contact("دقلي على خالد").alternatives.contains("خالد"))
        assertTrue(contact("اتصل على محمد سالم لو سمحت").alternatives.contains("محمد سالم"))
    }

    @Test fun nameStartingWithBaIsKept() {
        // "بلال" must stay a candidate even though it starts with ب
        assertTrue(contact("اتصل بلال").alternatives.contains("بلال"))
        assertTrue(contact("اتصل ببلال").alternatives.contains("بلال"))
    }

    @Test fun abdullahNotCut() {
        assertTrue(contact("اتصل بعبد الله").alternatives.contains("عبد الله"))
    }

    @Test fun aliAmbiguity() {
        val i = contact("اتصل علي احمد")
        assertTrue(i.alternatives.contains("علي احمد"))
        assertTrue(i.alternatives.contains("احمد"))
        assertTrue(contact("اتصل علي").alternatives.contains("علي"))
    }

    @Test fun spokenNumberDigitByDigit() {
        assertEquals(VoiceIntent.CallNumber("777123456"), p.parse("اتصل بالرقم سبعة سبعة سبعة واحد اثنين ثلاثة أربعة خمسة ستة"))
    }

    @Test fun writtenDigits() {
        assertEquals(VoiceIntent.CallNumber("777123456"), p.parse("يا مساعد اتصل بالرقم 777123456"))
        assertEquals(VoiceIntent.CallNumber("777123456"), p.parse("اتصل على الرقم ٧٧٧١٢٣٤٥٦"))
        assertEquals(VoiceIntent.CallNumber("+967777123456"), p.parse("اتصل بالرقم زائد 967777123456"))
    }

    @Test fun leadingZeroKept() {
        assertEquals(VoiceIntent.CallNumber("01234567"), p.parse("اتصل بالرقم صفر واحد اثنين ثلاثة اربعة خمسة ستة سبعة"))
    }

    @Test fun groupedNumbers() {
        assertEquals(VoiceIntent.CallNumber("777120"), p.parse("اتصل بالرقم سبعمية وسبعة وسبعين مية وعشرين"))
        assertEquals(VoiceIntent.CallNumber("771225"), p.parse("دق على الرقم سبعة سبعة اثنعش خمسة وعشرين"))
    }

    @Test fun stopOpenRedial() {
        assertEquals(VoiceIntent.StopListening, p.parse("يا مساعد أوقف الاستماع"))
        assertEquals(VoiceIntent.StopListening, p.parse("توقف"))
        assertEquals(VoiceIntent.OpenApp, p.parse("افتح التطبيق"))
        assertEquals(VoiceIntent.Redial, p.parse("أعد الاتصال بآخر رقم"))
        assertEquals(VoiceIntent.Redial, p.parse("اتصل بآخر رقم"))
        assertEquals(VoiceIntent.Redial, p.parse("عاود الاتصال"))
    }

    @Test fun answers() {
        assertEquals(VoiceIntent.Choice(0), p.parse("الأول"))
        assertEquals(VoiceIntent.Choice(1), p.parse("الثاني"))
        assertEquals(VoiceIntent.Yes, p.parse("نعم"))
        assertEquals(VoiceIntent.No, p.parse("لا"))
        assertEquals(VoiceIntent.Cancel, p.parse("إلغاء"))
    }

    @Test fun nonsenseIsNotACall() {
        assertTrue(p.parse("مرحبا كيف حالك اليوم") is VoiceIntent.Unknown)
        assertTrue(p.parse("اتصل") is VoiceIntent.Unknown)
        assertTrue(p.parse("اتصل بالرقم") is VoiceIntent.Unknown)
        assertTrue(p.parse("") is VoiceIntent.Unknown)
    }

    @Test fun normalizer() {
        assertEquals("احمد", ArabicNormalizer.normalize("أَحْمَد"))
        assertEquals("مستشفي فاطمه", ArabicNormalizer.normalize("مستشفى فاطمة"))
        assertEquals("777", ArabicNormalizer.normalize("٧٧٧"))
        assertEquals(ArabicNormalizer.latinSkeleton("Haitham"), ArabicNormalizer.latinSkeleton("هيثم"))
        assertEquals(ArabicNormalizer.latinSkeleton("Alarifi"), ArabicNormalizer.latinSkeleton("العريفي"))
    }

    @Test fun numberParserTeensAndTens() {
        fun d(s: String) = ArabicNumberParser.parse(ArabicNormalizer.tokens(s))?.digits
        assertEquals("77", d("سبعة وسبعين"))
        assertEquals("15", d("خمسطعش"))
        assertEquals("11", d("حداش"))
        assertEquals("777", d("سبعمائة وسبعة وسبعين"))
        assertEquals("77", d("دبل سبعة"))
        assertEquals("120", d("مية وعشرين"))
    }
}
