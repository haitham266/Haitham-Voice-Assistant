package com.haitham.voiceassistant

import com.haitham.voiceassistant.assistant.CommandResolver
import com.haitham.voiceassistant.contacts.ContactMatcher
import com.haitham.voiceassistant.model.Contact
import com.haitham.voiceassistant.model.Hypothesis
import com.haitham.voiceassistant.model.PhoneEntry
import com.haitham.voiceassistant.model.RecognizedWord
import com.haitham.voiceassistant.nlu.ArabicCommandParser
import com.haitham.voiceassistant.speech.GrammarBuilder
import com.haitham.voiceassistant.speech.Vocabulary
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Integration helper (skipped unless HVA_MODEL is set): builds the real wake and
 * command grammars from the model vocabulary with the app's own code, and turns
 * recogniser transcripts (HVA_TRANSCRIPTS, "file<TAB>text<TAB>conf" lines) into decisions.
 */
class PipelineFixtureTest {
    private val book = listOf("هيثم العريفي", "أحمد علي", "محمد سالم", "محمد صالح", "خالد", "عبدالله الحميري")
        .mapIndexed { i, n -> Contact(i.toLong(), n, listOf(PhoneEntry("7771234${50 + i}", 2, null, false))) }

    @Test fun buildGrammarsAndResolve() {
        val model = System.getenv("HVA_MODEL"); assumeTrue(model != null)
        val out = File(System.getenv("HVA_OUT") ?: "build/hva").apply { mkdirs() }
        val vocab = Vocabulary(File(model, "graph/words.txt"))
        val wake = GrammarBuilder.wakeGrammar("يا مساعد", vocab)
        File(out, "wake.json").writeText(wake.json)
        File(out, "cmd.json").writeText(GrammarBuilder.commandGrammar(book.map { it.displayName } + "يا مساعد", vocab))
        File(out, "missing.txt").writeText(wake.missingWords.joinToString(","))
        val tr = System.getenv("HVA_TRANSCRIPTS")?.let { File(it) }
        if (tr != null && tr.exists()) {
            val matcher = ContactMatcher().apply { setContacts(book) }
            val resolver = CommandResolver(ArabicCommandParser(), matcher)
            val lines = tr.readLines().filter { it.isNotBlank() }.map { l ->
                val (f, text, conf) = l.split('\t')
                val h = Hypothesis(text, text.split(' ').map { RecognizedWord(it, conf.toFloat(), 0f, 0f) }, Hypothesis.Source.GRAMMAR)
                "$f\t$text\t${resolver.resolveCommand(listOf(h))}"
            }
            File(out, "decisions.txt").writeText(lines.joinToString("\n"))
        }
    }
}
