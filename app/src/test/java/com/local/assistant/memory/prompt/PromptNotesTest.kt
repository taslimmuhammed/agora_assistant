package com.local.assistant.memory.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The user's notes in the prompt: names in the prefix, what's in one only with a message about it. */
class PromptNotesTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private val budget = MemoryBudget.EIGHT_K

    private val estimator = HeuristicTokenEstimator

    private val assembler = PromptAssembler(budget, estimator, zone)

    private val now = ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, zone)

    private fun prefix(notes: List<NoteTitle>) =
        assembler.buildPrefix(PrefixInputs("Instructions.", emptyList(), null, LocalDate.of(2026, 11, 5), emptyList(), notes))

    @Test
    fun `the prefix names the notes, and nothing in them`() {
        val prefix = prefix(listOf(NoteTitle(3, "Shopping list", list = true), NoteTitle(7, "Wifi", list = false)))

        assertTrue(prefix.text.endsWith("Notes and lists: “Shopping list” (list 3), “Wifi” (note 7)"))
        assertTrue(prefix.notesTokens in 1..budget.notesIndexCap)
    }

    @Test
    fun `many notes are named within the cap, the rest counted`() {
        val many = (1L..40L).map { NoteTitle(it, "Packing list for the trip number $it", list = true) }
        val prefix = prefix(many)

        assertTrue(prefix.notesTokens <= budget.notesIndexCap)
        assertTrue(Regex("and \\d+ more$").containsMatchIn(prefix.text))
    }

    @Test
    fun `no notes, no line`() {
        assertEquals("Instructions.", prefix(emptyList()).text)
    }
}
