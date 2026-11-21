package com.local.assistant.memory.prompt

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.core.FactCategorizer
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptBudgetTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private val budget = MemoryBudget.SIXTEEN_K

    private val estimator = HeuristicTokenEstimator

    private val assembler = PromptAssembler(budget, estimator, zone)

    private val now = ZonedDateTime.of(2026, 9, 21, 10, 42, 0, 0, zone)

    private var nextId = 1L

    private fun fact(attribute: String, value: String, core: Boolean = true, subject: String = "user") = FactEntity(
        id = nextId++,
        subject = subject,
        attribute = attribute,
        value = value,
        category = FactCategorizer.categorize(subject, attribute),
        core = core,
        origin = FactOrigin.CHAT,
        sourceMessageId = null,
        statedAt = 0,
        createdAt = 0,
        updatedAt = 0,
        lastConfirmedAt = nextId,
    )

    private fun message(role: Role, text: String) = MessageEntity(
        id = nextId++,
        chatId = 1,
        role = role,
        text = text,
        createdAt = 0,
        tokenEst = estimator.estimate(text),
    )

    private fun conversation(turns: Int, words: Int) = (1..turns).flatMap { i ->
        listOf(
            message(Role.USER, "question $i " + "lorem ".repeat(words)),
            message(Role.ASSISTANT, "answer $i " + "ipsum ".repeat(words)),
        )
    }

    private fun prefixInputs(
        core: List<FactEntity> = listOf(fact("pref.reply_style", "short answers"), fact("name", "Arjun")),
        summary: SummaryBlock? = null,
        agenda: List<AgendaItem> = emptyList(),
    ) = PrefixInputs(
        instructions = "You are a helpful assistant.",
        coreFacts = core,
        summary = summary,
        agendaDate = now.toLocalDate(),
        agenda = agenda,
    )

    private fun snippets(count: Int, words: Int) =
        (1..count).map { Snippet("snippet $it " + "dolor ".repeat(words), at = now.toInstant().toEpochMilli()) }

    // ---- Shedding order ----

    private val summary = SummaryBlock(SummaryBlock.Kind.LAST_SESSION, "We planned the trip to Kochi and the CA meeting.")

    // ---- What Luna's server is told ----

    @Test
    fun `the memory prefix holds the agenda and no time of day`() {
        val text = assembler.buildPrefix(
            prefixInputs(
                summary = summary,
                agenda = listOf(AgendaItem("Call the CA", now.plusDays(1).withHour(11).withMinute(0).toInstant().toEpochMilli())),
            ),
        ).text
        assertFalse("no time of day in the prefix", text.contains("10:42"))
        assertTrue(text, text.contains("Agenda as of Mon 21 Sep 2026:\n- Tue 22 Sep 11:00 · Call the CA"))
    }

    @Test
    fun `a place reminder is on the agenda by where it rings`() {
        val inputs = prefixInputs(
            agenda = listOf(
                AgendaItem("Call the CA", now.plusDays(1).withHour(11).withMinute(0).toInstant().toEpochMilli(), ref = "task 1"),
                AgendaItem("Buy milk", null, ref = "task 2", place = "DMart"),
                AgendaItem("Renew passport", null, ref = "task 3"),
            ),
        )
        val text = assembler.buildPrefix(inputs).text
        assertTrue(text, text.contains("- near DMart · Buy milk (task 2)\n- no date · Renew passport (task 3)"))
    }

    @Test
    fun `core is trimmed by priority, keeping preferences`() {
        val core = listOf(fact("pref.reply_style", "short answers")) +
            (1..30).map { fact("detail_$it", "remembered detail number $it") }
        val prefix = assembler.buildPrefix(prefixInputs(core = core), coreCap = 60)
        assertTrue(prefix.core.included.any { it.attribute == "pref.reply_style" })
        assertTrue(prefix.core.omitted.isNotEmpty())
    }

    // ---- Profiles ----

    @Test
    fun `on 8K replies keep their full length`() {
        assertEquals(MemoryBudget.SIXTEEN_K.generationReserve, MemoryBudget.EIGHT_K.generationReserve)
    }

    @Test
    fun `the profile follows the measured window`() {
        assertEquals(MemoryBudget.SIXTEEN_K, MemoryBudget.forWindow(16_384))
        assertEquals(MemoryBudget.SIXTEEN_K, MemoryBudget.forWindow(32_768))
        assertEquals(MemoryBudget.EIGHT_K, MemoryBudget.forWindow(12_288))
        assertEquals(4_096, MemoryBudget.forWindow(4_096).contextTokens)
    }
}
