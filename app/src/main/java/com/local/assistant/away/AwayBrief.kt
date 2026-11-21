package com.local.assistant.away

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.local.assistant.assist.AssistActivity
import com.local.assistant.assist.AssistantInteractionService
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "While you were away": what came in while the phone was locked, sorted by what needs the user,
 * shown when they're back.
 *
 * - **Collecting** ([capture]): [MessageListener] hands over each notification's lines; they're
 *   kept two days in their own database, then deleted.
 * - **Away** ([onScreenOff], [onUserPresent]): from the screen going off to the next unlock. Back
 *   after at least the user's chosen time with someone waiting on them, the brief opens.
 * - **Sorting**: [BriefRules] sorts conversations by what needs the user.
 */
class AwayBrief(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: AwayDao,
    val replies: ReplyActions,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val prefs = context.getSharedPreferences("away", Context.MODE_PRIVATE)

    /** When the screen last went off, unlocked since or not; 0 while the user is here. */
    private var awaySince: Long
        get() = prefs.getLong(KEY_AWAY_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_AWAY_SINCE, value).apply()

    /** How long the user was last away, for the brief's header. */
    var lastAwayMs: Long
        get() = prefs.getLong(KEY_LAST_AWAY_MS, 0L)
        private set(value) = prefs.edit().putLong(KEY_LAST_AWAY_MS, value).apply()


    // ---- Collecting ----

    fun capture(lines: List<CapturedEntity>) {
        if (lines.isEmpty() || !settings.awayBrief) return
        scope.launch {
            dao.insert(lines)
            dao.deleteBefore(clock() - KEEP_MS)
        }
    }

    /** The notification [notificationKey] went: read in its app, dismissed, or answered. */
    fun onRemoved(notificationKey: String) {
        scope.launch { dao.markSeenByNotification(notificationKey, clock()) }
    }

    // ---- Away and back ----

    fun onScreenOff() {
        if (awaySince == 0L) awaySince = clock()
    }

    fun onUserPresent() {
        val since = awaySince
        awaySince = 0L
        if (since == 0L || !settings.awayBrief) return
        val away = clock() - since
        lastAwayMs = away
        if (away < settings.awayMinutes * 60_000L) return
        scope.launch {
            // Let the lock screen get out of the way first.
            delay(SHOW_AFTER_UNLOCK_MS)
            val brief = BriefBuilder.build(dao.unseen(clock() - WINDOW_MS), dao.triage(), newOnly = true)
            if (brief.needs.isNotEmpty()) show(brief)
        }
    }

    private fun show(brief: Brief) {
        val audio = context.getSystemService(AudioManager::class.java)
        val inCall = audio?.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)
        val popped = settings.awayPopUp && !inCall && !AssistActivity.inView && AssistantInteractionService.showBrief()
        if (!popped) BriefNotifications.post(context, brief)
        Log.i(TAG, "Back after ${lastAwayMs / 60_000} min: ${brief.needs.size} need you (${if (popped) "shown" else "notified"})")
    }

    // ---- The brief ----

    /** The brief, kept current. [newOnly]: what wasn't in an earlier brief (as at unlock). */
    fun observe(newOnly: Boolean): Flow<Brief> =
        combine(dao.observeUnseen(clock() - WINDOW_MS), dao.observeTriage(), replies.version) { lines, triage, _ ->
            BriefBuilder.build(lines, triage, newOnly)
        }

    val apps get() = dao.observeApps()

    /** The user dealt with these: replied, called back, opened or swiped them away. */
    fun markSeen(keys: List<String>) {
        if (keys.isEmpty()) return
        scope.launch { dao.markSeen(keys, clock()) }
    }

    /** The brief showing these closed: the next one opens on what's new. */
    fun markBriefed(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            dao.markBriefed(ids, clock())
            BriefNotifications.cancel(context)
        }
    }

    suspend fun forgetApp(packageName: String) {
        dao.deleteApp(packageName)
    }

    suspend fun forgetAll() {
        dao.deleteAll()
        dao.deleteAllTriage()
        replies.clear()
    }

    companion object {
        private const val TAG = "AwayBrief"
        private const val KEY_AWAY_SINCE = "away_since"
        private const val KEY_LAST_AWAY_MS = "last_away_ms"

        /** Lines are deleted two days after they came. */
        const val KEEP_MS = 48 * 60 * 60 * 1000L

        /** A brief covers the last day at most. */
        const val WINDOW_MS = 24 * 60 * 60 * 1000L

        const val SHOW_AFTER_UNLOCK_MS = 600L

        val AWAY_CHOICES = listOf(15, 30, 60, 120)
        const val DEFAULT_AWAY_MINUTES = 30
    }
}
