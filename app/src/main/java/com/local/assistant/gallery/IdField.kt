package com.local.assistant.gallery

import com.local.assistant.privacy.SensitiveText

/** An ID number asked for: which, how it's printed, and the documents it's on. */
enum class IdField(
    val kind: SensitiveText.Kind,
    /** As the reply names it. */
    val label: String,
    /** As the photo is asked for it. */
    val printed: String,
    /** The document it's on, as the reply names it. */
    val document: String,
    /** Words one of those documents is called by. */
    private val documentWords: List<String>,
    private val asked: Regex,
) {
    AADHAAR(
        SensitiveText.Kind.AADHAAR, "Aadhaar number", "the Aadhaar number", "Aadhaar card",
        listOf("aadhaar", "aadhar", "uidai", "unique identification"), Regex("\\baadhaa?r\\b"),
    ),
    PAN(
        SensitiveText.Kind.PAN, "PAN", "the Permanent Account Number (PAN)", "PAN card",
        listOf("pan", "permanent account", "income tax"), Regex("\\bpan\\b"),
    ),
    DRIVING_LICENCE(
        SensitiveText.Kind.DRIVING_LICENCE, "driving licence number", "the driving licence number", "driving licence",
        listOf("licen", "driving"), Regex("\\b(licen[cs]e|dl)\\b"),
    ),
    PASSPORT(
        SensitiveText.Kind.PASSPORT, "passport number", "the passport number", "passport",
        listOf("passport"), Regex("\\bpassport\\b"),
    ),
    // Last: "PAN card number" and "Aadhaar card number" are theirs.
    CARD(
        SensitiveText.Kind.CARD, "card number", "the card number", "card",
        listOf("card"), Regex("\\bcard\\b"),
    ),
    ;

    /** Whether [document] (a file's or photo's name) is one this number is on. */
    fun isOn(document: String): Boolean = documentWords.any { it in document.lowercase() }

    /** The number as printed in [copied], when it has this one's shape (and check digit); else null. */
    fun valueIn(copied: String): String? {
        // A passport number counts only beside the word, as in a reply.
        val prefix = if (this == PASSPORT) "passport " else ""
        val text = prefix + copied
        return SensitiveText.find(text).firstOrNull { it.kind == kind }?.let { text.substring(it.start, it.end) }
    }

    companion object {
        /** The ID number [question] asks for; null when it asks something else ("when does my licence expire?"). */
        fun of(question: String): IdField? {
            val text = question.lowercase()
            // "What's my PAN?" asks for the number; "my PAN card's date of birth" doesn't.
            val number = NUMBER.containsMatchIn(text) || Regex("\\bpan\\b(?!\\s*card)").containsMatchIn(text)
            if (!number) return null
            return entries.firstOrNull { it.asked.containsMatchIn(text) }
        }

        private val NUMBER = Regex("\\b(numbers?|no|num)\\b")
    }
}
