package com.local.assistant.memory.prompt

import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.db.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What a live voice call ([com.local.assistant.live.LiveContext]) is told of the user: their
 * profile, the chat's summary or the last session's, the agenda and the names of their notes. The
 * server writes its own instructions around it.
 */
class MemoryContext(
    private val chats: ChatRepository,
    private val memory: MemoryRepository,
    private val estimator: TokenEstimator,
    /** The user's notes and lists, named in it. */
    private val noteIndex: suspend () -> List<NoteTitle> = { emptyList() },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** [chatId]: the chat the call was started from, whose summary it carries; null for none, and then the last session's. */
    suspend fun liveContext(chatId: Long?): String {
        val budget = MemoryBudget.SIXTEEN_K
        val summary = chatId?.let { chats.chat(it) }?.rollingSummary?.let { SummaryBlock(SummaryBlock.Kind.EARLIER_IN_CHAT, it) }
            ?: memory.latestSessionSummary()?.let { SummaryBlock(SummaryBlock.Kind.LAST_SESSION, it) }
        val inputs = PrefixInputs(
            instructions = "",
            coreFacts = memory.coreFacts(),
            summary = summary,
            agendaDate = now().toLocalDate(),
            agenda = memory.agenda(clock(), budget.agendaItems),
            notes = noteIndex(),
        )
        return withContext(Dispatchers.Default) { PromptAssembler(budget, estimator, zone()).buildPrefix(inputs).text }
    }

    private fun now(): ZonedDateTime = Instant.ofEpochMilli(clock()).atZone(zone())
}
