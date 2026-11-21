package com.local.assistant.memory.summary

import com.local.assistant.memory.db.SessionDao
import com.local.assistant.memory.work.AppForeground

/**
 * Ends sessions that have gone quiet, from the session-end job ten minutes after the app leaves
 * the foreground and from the nightly job. A session's summary, once written, is what the next
 * call starts from ("Last time you talked: …"); summaries written before are kept and read.
 */
class SessionSummaries(
    private val sessions: SessionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Closes every session that has been quiet for a full session gap. */
    suspend fun endQuietSessions(): Int {
        var ended = 0
        val now = clock()
        for (open in sessions.openSessions()) {
            val lastActivity = maxOf(open.startedAt, sessions.lastActivity(open.id) ?: 0L)
            if (now - lastActivity >= AppForeground.SESSION_GAP_MS) {
                sessions.end(open.id, endedAt = lastActivity)
                ended++
            }
        }
        return ended
    }
}
