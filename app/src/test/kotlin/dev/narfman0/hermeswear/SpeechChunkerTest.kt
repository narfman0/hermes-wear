package dev.narfman0.hermeswear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    private fun speakAll(vararg deltas: String): List<String> {
        val c = SpeechChunker()
        return deltas.flatMap { c.feed(it) } + c.flush()
    }

    @Test fun splitsSentencesAcrossDeltas() {
        val c = SpeechChunker()
        assertEquals(emptyList<String>(), c.feed("Hello th"))
        assertEquals(listOf("Hello there."), c.feed("ere. How a"))
        assertEquals(emptyList<String>(), c.feed("re you"))
        assertEquals(listOf("How are you?"), c.feed("? "))
        assertEquals(emptyList<String>(), c.flush())
    }

    @Test fun flushSpeaksTheTail() {
        assertEquals(listOf("One.", "Two without stop"), speakAll("One. Two ", "without stop"))
    }

    @Test fun decimalsDoNotSplit() {
        assertEquals(listOf("It is 3.5 degrees."), speakAll("It is 3.", "5 degrees."))
    }

    @Test fun stripsMarkdown() {
        val out = speakAll("# Title\n", "- **bold** item with `code`\n", "See [the docs](https://x.y/z). Or https://a.b/c now.")
        assertEquals(listOf("Title", "bold item with code", "See the docs.", "Or link now."), out)
    }

    @Test fun codeBlocksAreAnnouncedNotRead() {
        val out = speakAll("Here:\n", "```kotlin\n", "val x = 1\n", "```\n", "Done.")
        assertEquals(listOf("Here:", "Code block.", "Done."), out)
    }

    @Test fun punctuationOnlyIsSkipped() {
        assertTrue(speakAll("---\n", "...\n").isEmpty())
    }
}

class TileTextTest {
    @Test fun shortReplyUnchanged() = assertEquals("All good.", TileText.preview("All good."))

    @Test fun markdownFlattened() =
        assertEquals("Done see docs [code] ok", TileText.preview("**Done** see [docs](http://x)\n```\nls\n``` ok"))

    @Test fun longReplyTruncated() {
        val p = TileText.preview("word ".repeat(100), max = 20)
        assertTrue(p.length <= 20)
        assertTrue(p.endsWith("…"))
    }
}
