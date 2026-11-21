package com.local.assistant.memory.prompt

import com.local.assistant.memory.core.CoreMemoryRenderer
import com.local.assistant.memory.core.FactLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds what a live call is told of the user ([com.local.assistant.live.LiveContext]), within
 * [budget]: B core memory, C the last summary, D the agenda and the names of their notes, after
 * any A instructions.
 */
class PromptAssembler(
    private val budget: MemoryBudget,
    private val estimator: TokenEstimator,
    private val zone: ZoneId,
) {

    private val coreRenderer = CoreMemoryRenderer(estimator)

    // ---- Prefix (A–D) ----

    fun buildPrefix(
        inputs: PrefixInputs,
        coreCap: Int = budget.coreCap,
        summaryCap: Int = summaryCapFor(inputs.summary),
    ): SystemPrefix {
        val core = coreRenderer.render(inputs.coreFacts, coreCap)
        val summary = inputs.summary?.let { renderSummary(it, summaryCap) }.orEmpty()
        val agenda = renderAgenda(inputs)
        val notes = renderNoteIndex(inputs.notes)
        val text = listOf(inputs.instructions.trim(), core.text, summary, agenda, notes)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return SystemPrefix(
            text = text,
            tokens = estimator.estimate(text),
            core = core,
            summaryTokens = estimator.estimate(summary),
            agendaTokens = estimator.estimate(agenda),
            notesTokens = estimator.estimate(notes),
        )
    }

    /**
     * "Notes and lists: “Shopping list” (list 3), “Wifi details” (note 7), and 2 more": their names,
     * within [MemoryBudget.notesIndexCap], so the model can tell "add eggs to the grocery list"
     * means the "Shopping list" and name it in edit_note. What's in them comes only with a message
     * about one, so ticking an item never changes the prefix.
     */
    private fun renderNoteIndex(notes: List<NoteTitle>): String {
        if (notes.isEmpty()) return ""
        val shown = mutableListOf<String>()
        for ((i, note) in notes.withIndex()) {
            val name = "“${FactLabels.inline(note.title)}” (${if (note.list) "list" else "note"} ${note.id})"
            val more = notes.size - i - 1
            val candidate = NOTES_HEADING + (shown + name).joinToString(", ") + if (more > 0) ", and $more more" else ""
            if (estimator.estimate(candidate) > budget.notesIndexCap) break
            shown += name
        }
        if (shown.isEmpty()) return ""
        val more = notes.size - shown.size
        return NOTES_HEADING + shown.joinToString(", ") + if (more > 0) ", and $more more" else ""
    }

    fun summaryCapFor(summary: SummaryBlock?): Int = when (summary?.kind) {
        SummaryBlock.Kind.EARLIER_IN_CHAT -> budget.rollingSummaryCap
        else -> budget.summaryCap
    }

    private fun renderSummary(summary: SummaryBlock, capTokens: Int): String {
        val heading = when (summary.kind) {
            SummaryBlock.Kind.LAST_SESSION -> "Last time you talked:"
            SummaryBlock.Kind.EARLIER_IN_CHAT -> "Earlier in this chat:"
        }
        val body = truncateToTokens(summary.text.trim(), capTokens - estimator.estimate("$heading\n"))
        return if (body.isEmpty()) "" else "$heading\n$body"
    }

    /** "Agenda as of <date>" and the next few open items, within the agenda cap. */
    private fun renderAgenda(inputs: PrefixInputs): String {
        if (inputs.agenda.isEmpty()) return ""
        val heading = "Agenda as of ${AGENDA_DATE.format(inputs.agendaDate)}:"
        val lines = mutableListOf<String>()
        for (item in inputs.agenda.take(budget.agendaItems)) {
            val ref = item.ref?.let { " ($it)" }.orEmpty()
            val line = "- ${formatWhen(item)} · ${FactLabels.inline(item.title)}$ref"
            val candidate = (listOf(heading) + lines + line).joinToString("\n")
            if (estimator.estimate(candidate) > budget.agendaCap) break
            lines += line
        }
        return if (lines.isEmpty()) "" else (listOf(heading) + lines).joinToString("\n")
    }

    private fun formatWhen(item: AgendaItem): String {
        val at = item.at ?: return item.place?.let { "near $it" } ?: "no date"
        val time = Instant.ofEpochMilli(at).atZone(zone)
        return if (item.allDay) DAY.format(time) else DAY_TIME.format(time)
    }

    /** Cuts [text] at a word boundary so it fits [capTokens], marking the cut with an ellipsis. */
    fun truncateToTokens(text: String, capTokens: Int): String {
        if (capTokens <= 0) return ""
        if (estimator.estimate(text) <= capTokens) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (estimator.estimate(text.take(mid) + "…") <= capTokens) low = mid else high = mid - 1
        }
        val cut = text.take(low)
        val atWord = cut.substringBeforeLast(' ', cut).trimEnd()
        return if (atWord.isEmpty()) "" else "$atWord…"
    }

    companion object {
        const val NOTES_HEADING = "Notes and lists: "

        private val AGENDA_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
