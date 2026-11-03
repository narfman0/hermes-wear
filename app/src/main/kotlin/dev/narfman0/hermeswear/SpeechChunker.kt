package dev.narfman0.hermeswear

/**
 * Turns a streamed reply into speakable sentences.
 *
 * Text arrives in arbitrary pieces. [feed] returns the sentences completed so far,
 * so speech can start before the reply finishes; [flush] returns whatever is left.
 * Markdown that sounds silly read aloud (code, links, emphasis marks) is cleaned up.
 */
class SpeechChunker {
    private val pending = StringBuilder()
    private var inCodeBlock = false

    fun feed(delta: String): List<String> {
        pending.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val end = sentenceEnd(pending) ?: break
            val sentence = pending.substring(0, end)
            pending.delete(0, end)
            clean(sentence)?.let(out::add)
        }
        return out
    }

    fun flush(): List<String> {
        val rest = pending.toString()
        pending.setLength(0)
        val cleaned = clean(rest)
        inCodeBlock = false
        return listOfNotNull(cleaned)
    }

    fun reset() {
        pending.setLength(0)
        inCodeBlock = false
    }

    /** Index just past the first sentence end (". ", "! ", "? ", newline), or null. */
    private fun sentenceEnd(s: CharSequence): Int? {
        for (i in s.indices) {
            val c = s[i]
            if (c == '\n') return i + 1
            // Needs the following space, so "3.5" and a trailing "." still waiting for more text don't split.
            if ((c == '.' || c == '!' || c == '?') && i + 1 < s.length && s[i + 1].isWhitespace()) {
                return i + 2
            }
        }
        return null
    }

    private fun clean(raw: String): String? {
        var t = raw
        // Fenced code blocks: say it once, skip the contents.
        if (t.trimStart().startsWith("```")) {
            val wasIn = inCodeBlock
            inCodeBlock = !inCodeBlock
            return if (!wasIn) "Code block." else null
        }
        if (inCodeBlock) return null
        t = t.replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")      // images
        t = t.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")       // links -> text
        t = t.replace(Regex("""https?://\S+"""), "link")
        t = t.replace(Regex("""`([^`]*)`"""), "$1")                    // inline code
        t = t.replace(Regex("""^\s{0,3}#{1,6}\s+""", RegexOption.MULTILINE), "") // headings
        t = t.replace(Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE), "")       // bullets
        t = t.replace(Regex("""^\s*>\s?""", RegexOption.MULTILINE), "")           // quotes
        t = t.replace(Regex("""[*_~]{1,3}"""), "")                     // emphasis marks
        t = t.replace(Regex("""\|"""), " ")                            // table pipes
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t.takeIf { it.any(Char::isLetterOrDigit) }
    }
}
