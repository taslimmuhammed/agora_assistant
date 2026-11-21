package com.local.assistant.memory.prompt

import com.local.assistant.memory.core.CoreMemory
import com.local.assistant.memory.db.FactEntity
import java.time.LocalDate

/** One line of the agenda snapshot: an open task or an upcoming event. */
data class AgendaItem(
    val title: String,
    /** Null for an undated task. */
    val at: Long?,
    val allDay: Boolean = false,
    /** "task 42" or "event 7": lets the model name exactly the item it means in a tool call. */
    val ref: String? = null,
    /** A place reminder's place, said instead of a time: "near DMart". */
    val place: String? = null,
)

/** Section C: context carried over from before this conversation's verbatim window. */
data class SummaryBlock(val kind: Kind, val text: String) {
    enum class Kind {
        /** The summary of the most recent finished session, from any chat. */
        LAST_SESSION,

        /** This chat's own older turns, folded away by compaction. */
        EARLIER_IN_CHAT,
    }
}

/** A note or list as the prefix names it, so the model knows what there is and can name it in edit_note. */
data class NoteTitle(val id: Long, val title: String, val list: Boolean)

/** Everything the static system instruction (sections A–D) is built from. */
data class PrefixInputs(
    val instructions: String,
    val coreFacts: List<FactEntity>,
    val summary: SummaryBlock?,
    /** Day granularity only, so the prefix changes at most once a day on its own. */
    val agendaDate: LocalDate,
    val agenda: List<AgendaItem>,
    /** The user's notes and lists, pinned first then the latest changed: names only, never what's in them. */
    val notes: List<NoteTitle> = emptyList(),
)

data class SystemPrefix(
    val text: String,
    val tokens: Int,
    val core: CoreMemory,
    val summaryTokens: Int,
    val agendaTokens: Int,
    val notesTokens: Int = 0,
)

/** An archived exchange recalled for this turn. */
data class Snippet(val text: String, val at: Long)
