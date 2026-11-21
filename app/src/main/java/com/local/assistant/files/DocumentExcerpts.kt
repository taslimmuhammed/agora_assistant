package com.local.assistant.files

import com.local.assistant.files.DocumentText.Read
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.prompt.TokenEstimator
import com.local.assistant.memory.retrieval.QueryText
import java.util.Locale
import kotlin.math.ln

/**
 * What of a document goes back with read_file's result, for the model in a call to answer from:
 * the whole text when it fits in [EXCERPT_TOKENS], else for a question the parts with its words in
 * them, and for a summary parts spread across it. A PDF of page pictures gives the little text
 * layer it has, if any: the phone has no model of its own to look at pictures.
 */
object DocumentExcerpts {

    /** [text]: what of the document goes with the result, null when there's nothing to read; [note]: how much that is, or why none. */
    data class Excerpt(val text: String?, val note: String?)

    fun of(name: String, read: Read, question: String?, estimator: TokenEstimator = HeuristicTokenEstimator, budget: Int = EXCERPT_TOKENS): Excerpt = when (read) {
        is Read.Unreadable -> Excerpt(null, "Couldn't read $name: ${read.why.said}.")
        is Read.Scanned -> read.text?.let { text(name, it, question, estimator, budget) }
            ?: Excerpt(null, "$name is pictures of pages, with no text in it, so it can't be read here.")
        is Read.Text -> text(name, read, question, estimator, budget)
    }

    private fun text(name: String, read: Read.Text, question: String?, estimator: TokenEstimator, budget: Int): Excerpt {
        val head = Plan.documentHeader(name, read)
        if (estimator.estimate(read.text) <= budget) return Excerpt(head + read.text, Plan.note(read, read.text, cutShort = read.cut))
        val parts = Plan.chunks(read.text, estimator, PART_TOKENS)
        val picked = if (Plan.isSummary(question)) Plan.spread(parts.size, maxOf(1, budget / PART_TOKENS)) else Plan.pick(parts, question!!, estimator, budget)
        val chosen = Plan.joined(parts, picked)
        return Excerpt(head + chosen, Plan.note(read, chosen, cutShort = true))
    }

    /** The arithmetic of what to read, kept pure for tests. */
    object Plan {

        /** No question, or one asking what the document says as a whole. */
        fun isSummary(question: String?): Boolean {
            val q = question?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (q.isEmpty()) return true
            return SUMMARY.containsMatchIn(q)
        }

        /**
         * [text] in parts of about [tokens] each, cut between paragraphs where it can, else
         * between lines, else between words.
         */
        fun chunks(text: String, estimator: TokenEstimator, tokens: Int): List<String> {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var currentTokens = 0
            fun flush() {
                if (current.isNotBlank()) parts += current.toString().trim()
                current.setLength(0)
                currentTokens = 0
            }
            for (piece in pieces(text, estimator, tokens)) {
                val cost = estimator.estimate(piece)
                if (currentTokens + cost > tokens && current.isNotEmpty()) flush()
                current.append(piece)
                currentTokens += cost
            }
            flush()
            return parts
        }

        /** Paragraphs (with their blank line), each no bigger than [tokens]. */
        private fun pieces(text: String, estimator: TokenEstimator, tokens: Int): Sequence<String> = sequence {
            for (paragraph in text.split(Regex("(?<=\n\n)"))) {
                if (estimator.estimate(paragraph) <= tokens) {
                    yield(paragraph)
                    continue
                }
                for (line in paragraph.split(Regex("(?<=\n)"))) {
                    if (estimator.estimate(line) <= tokens) {
                        yield(line)
                        continue
                    }
                    // One very long line: by words.
                    val words = StringBuilder()
                    for (word in line.split(Regex("(?<= )"))) {
                        if (estimator.estimate(words.toString() + word) > tokens && words.isNotEmpty()) {
                            yield(words.toString())
                            words.setLength(0)
                        }
                        words.append(word)
                    }
                    if (words.isNotEmpty()) yield(words.toString())
                }
            }
        }

        /**
         * The parts most about [question], up to [budget] tokens, in the document's order. Each
         * question word scores by how rare it is among the parts (a word in every part says
         * nothing); the first part, which usually says what the document is, wins a tie. With no
         * word found anywhere, the document's start.
         */
        fun pick(parts: List<String>, question: String, estimator: TokenEstimator, budget: Int): List<Int> {
            if (parts.isEmpty()) return emptyList()
            val terms = QueryText.terms(question).filter { it !in QUESTION_WORDS }.map(::stem).distinct()
            val lowered = parts.map { it.lowercase(Locale.ROOT) }
            val scores = DoubleArray(parts.size)
            for (term in terms) {
                val counts = lowered.map { occurrences(it, term) }
                val having = counts.count { it > 0 }
                if (having == 0) continue
                val idf = ln(1.0 + parts.size.toDouble() / having)
                counts.forEachIndexed { i, c -> scores[i] += idf * minOf(c, 3) }
            }
            scores[0] += 0.01
            val order = if (scores.all { it < 0.5 }) parts.indices.toList() else parts.indices.sortedByDescending { scores[it] }
            val chosen = ArrayList<Int>()
            var used = 0
            for (i in order) {
                val cost = estimator.estimate(parts[i])
                if (used + cost > budget) continue
                chosen += i
                used += cost
            }
            return chosen.sorted()
        }

        /** [count] indexes spread evenly over [size], the first always among them. */
        fun spread(size: Int, count: Int): List<Int> {
            if (size <= count) return (0 until size).toList()
            return (0 until count).map { (it.toLong() * size / count).toInt() }.distinct()
        }

        fun joined(parts: List<String>, picked: List<Int>): String =
            if (picked.size == parts.size) parts.joinToString("\n\n") else picked.joinToString("\n\n[…]\n\n") { parts[it] }

        fun documentHeader(name: String, read: Read.Text): String {
            val size = if (read.parts != null && read.unit != null) ", ${read.parts} ${read.unit}${if (read.parts == 1) "" else "s"}" else ""
            return "The document \"$name\"$size:\n\n"
        }

        /**
         * How much of the document [read] was read when only [readText] of it was: by its pages
         * when they're marked, else as a share. Null when all of it was.
         */
        fun note(read: Read.Text, readText: String, cutShort: Boolean): String? {
            if (!cutShort) return null
            val pages = Regex("\\[(Page|Slide) (\\d+)]").findAll(readText).map { it.groupValues[2] }.distinct().count()
            val total = read.parts
            if (pages > 0 && total != null && read.unit != null && pages < total) return "This is $pages of its $total ${read.unit}s."
            val share = (100.0 * readText.length / read.text.length).toInt().coerceIn(1, 99)
            return if (share < 95) "This is about $share% of it." else null
        }

        private fun occurrences(text: String, term: String): Int {
            var count = 0
            var from = 0
            while (true) {
                val at = text.indexOf(term, from)
                if (at < 0) return count
                count++
                from = at + term.length
            }
        }

        /** "payments" and "payment" alike: the start of a long word. */
        private fun stem(word: String): String = if (word.length > 6) word.take(word.length - 2) else word

        private val SUMMARY = Regex(
            "\\b(summar(y|ise|ize|ised|ized)|sum (it )?up|tl;?dr|gist|overview|key points|main points|outline|" +
                "what('s| is| does)( this| it| the \\w+)? (about|say)|brief me|explain (it|this|the (document|file|pdf)))\\b",
        )

        /** Words in a question that aren't what it's about. */
        private val QUESTION_WORDS = setOf("document", "file", "pdf", "doc", "says", "say", "mentioned", "mention", "written", "find", "check", "read")
    }


    /** A document's text sent with a result at most, by the heuristic's count (which overcounts English by about half). */
    const val EXCERPT_TOKENS = 3_000
    private const val PART_TOKENS = 600
}
