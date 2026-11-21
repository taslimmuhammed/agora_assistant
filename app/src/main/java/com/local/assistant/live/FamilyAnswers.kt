package com.local.assistant.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.local.assistant.MainActivity
import com.local.assistant.R
import com.local.assistant.appContainer
import com.local.assistant.data.db.Role
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tells her what the family answered on WhatsApp, and what Luna told them for her, when it came
 * outside a call.
 *
 * In a call the server says it as it comes (Agora /speak). Outside one, while Luna is closed, each
 * answer is a notification: the background looks every minute while someone may answer soon, else
 * every quarter of an hour. When she opens Luna, everything since she last looked is one short
 * message from Luna ([briefing], `POST /family/briefing`: "Good morning, Lakshmi! Filu will bring
 * your tablets this evening at 6."), which the chat on screen shows as Luna's ([showIn]) and which
 * is spoken in Murf's voice, the one her calls use; without the server's audio, in the phone's
 * ([speak]: Kokoro when chosen). While she stays, each new answer comes the same way, without the
 * greeting.
 *
 * An answer that comes during a call but wasn't said in it (the call still connecting) waits for
 * the call to end. Only while live voice is on and has a server.
 */
class FamilyAnswers(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: LiveApi,
    private val chats: ChatRepository,
    /** The phone's own voice, when Murf's audio can't be had. */
    private val speak: (String) -> Unit,
    /** Live voice is on and has a server. */
    private val enabled: () -> Boolean,
    private val inCall: () -> Boolean,
    private val inSight: () -> Boolean,
    private val scope: CoroutineScope,
) {

    /** What Luna says, about the updates up to [upTo]. */
    data class Briefing(val text: String, val upTo: Long)

    private val mutex = Mutex()
    private var watching: Job? = null
    private val notified = context.getSharedPreferences("family_answers", Context.MODE_PRIVATE)

    private val _briefing = MutableStateFlow<Briefing?>(null)

    /** Waiting to be shown in the chat on screen ([showIn]). */
    val briefing: StateFlow<Briefing?> = _briefing.asStateFlow()

    /** The newest update a briefing was made for, so one waiting to be shown isn't made again. */
    private var briefedUpTo = -1L

    /** Greeted since Luna came into sight: later news while she stays comes without "Good morning". */
    private var greeted = false

    private var player: MediaPlayer? = null

    /** Fetches what came since she last looked and tells her; whether an answer may come soon. */
    suspend fun check(): Boolean = mutex.withLock {
        if (!enabled()) return false
        val seen = settings.familyAnswersAfter.takeIf { it >= 0 }
        val got = api.answers(seen)
        val untold = got.answers.filter { (seen == null || it.id > seen) && !it.told }.sortedBy { it.id }
        when {
            untold.isEmpty() -> if (_briefing.value == null) settings.familyAnswersAfter = maxOf(seen ?: 0L, got.last)
            inCall() -> Unit
            inSight() -> brief(seen, untold)
            else -> notifyNew(untold)
        }
        got.waiting
    }

    /**
     * Shows the waiting briefing in [chatId] (a new chat when null, as on the start screen) as
     * Luna's message; the chat it's in, or null when none was waiting.
     */
    suspend fun showIn(chatId: Long?): Long? {
        val shown = _briefing.getAndUpdate { null } ?: return null
        val id = chatId?.takeIf { chats.chat(it) != null } ?: chats.createChat(CHAT_TITLE)
        chats.addMessage(id, Role.ASSISTANT, shown.text)
        mutex.withLock { settings.familyAnswersAfter = maxOf(settings.familyAnswersAfter, shown.upTo) }
        return id
    }

    /** She started talking or typing: Luna stops. */
    fun stopVoice() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    /**
     * The app is in sight: look every few seconds until it isn't. Each look first asks [enabled],
     * so live voice turned on in Settings meanwhile starts them, and turned off stops them.
     */
    fun onForeground() {
        greeted = false
        if (watching?.isActive == true) return
        watching = scope.launch {
            var scheduled = false
            while (isActive && inSight()) {
                if (enabled()) {
                    if (!scheduled) schedule(context).also { scheduled = true }
                    runCatching { check() }.onFailure { Log.d(TAG, "No answers fetched: ${it.message}") }
                }
                delay(IN_SIGHT_MS)
            }
        }
    }

    /** Out of sight: the background looks instead, soon if someone may answer. */
    fun onBackground() {
        watching?.cancel()
        watching = null
        stopVoice()
        if (enabled()) soon(context)
    }

    /** Something just went to the family: an answer may come soon, wherever she is. */
    fun expect() {
        if (!inSight()) soon(context)
    }

    private suspend fun brief(seen: Long?, untold: List<LiveApi.Answer>) {
        val upTo = untold.last().id
        if (briefedUpTo == upTo && _briefing.value != null) return
        val greet = !greeted
        val text = runCatching { api.briefing(seen, upTo, greet).text }
            .onFailure { Log.d(TAG, "Briefing written on the phone: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: plain(untold, greet)
        greeted = true
        briefedUpTo = upTo
        _briefing.value = Briefing(text, upTo)
        untold.forEach { NotificationManagerCompat.from(context).cancel(notificationId(it)) }
        if (settings.speakReplies) say(text)
    }

    private fun plain(untold: List<LiveApi.Answer>, greet: Boolean): String {
        val news = untold.joinToString(" ") { it.text.trim() }
        return if (greet) "While you were away: $news" else news
    }

    /** In Murf's voice from the server, else the phone's. */
    private fun say(text: String) {
        scope.launch {
            val mp3 = runCatching { api.speech(text) }.onFailure { Log.d(TAG, "No Murf audio: ${it.message}") }.getOrNull()
            val played = mp3 != null && withContext(Dispatchers.Main) { play(mp3) }
            if (!played) speak(text)
        }
    }

    private fun play(mp3: ByteArray): Boolean = runCatching {
        val file = File(context.cacheDir, "luna-briefing.mp3").apply { writeBytes(mp3) }
        stopVoice()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            setDataSource(file.path)
            setOnCompletionListener { done ->
                done.release()
                if (player === done) player = null
            }
            prepare()
            start()
        }
        true
    }.getOrElse {
        Log.w(TAG, "Murf audio didn't play", it)
        false
    }

    /** Out of sight: a notification for each answer not notified yet; her briefing waits for her return. */
    private fun notifyNew(untold: List<LiveApi.Answer>) {
        val after = notified.getLong(KEY_NOTIFIED, -1L)
        val fresh = untold.filter { it.id > after }
        fresh.forEach(::notify)
        fresh.lastOrNull()?.let { notified.edit().putLong(KEY_NOTIFIED, it.id).apply() }
    }

    private fun notify(answer: LiveApi.Answer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel()
        val open = PendingIntent.getActivity(
            context,
            answer.id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentTitle(TITLE)
            .setContentText(answer.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(answer.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(answer.at.takeIf { it > 0 } ?: System.currentTimeMillis())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(answer), notification) }
    }

    private fun notificationId(answer: LiveApi.Answer) = NOTIFICATION_BASE + (answer.id % 1_000).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "Family's answers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "What the family answered on WhatsApp, when it comes outside a call"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "FamilyAnswers"
        private const val CHANNEL = "family_answers"
        private const val TITLE = "From your family"
        private const val CHAT_TITLE = "From your family"
        private const val KEY_NOTIFIED = "notified_up_to"
        private const val NOTIFICATION_BASE = 4_400
        private const val IN_SIGHT_MS = 5_000L
        private const val SOON_MIN = 1L
        private const val PERIODIC_MIN = 15L
        private const val WORK_PERIODIC = "family_answers"
        private const val WORK_SOON = "family_answers_soon"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every quarter of an hour while live voice is on; kept across restarts by WorkManager. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FamilyAnswersWorker>(PERIODIC_MIN, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        }

        /** A look in a minute; [after] the one running now, from inside it, rather than in its place. */
        fun soon(context: Context, after: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_SOON,
                if (after) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FamilyAnswersWorker>().setInitialDelay(SOON_MIN, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        }
    }
}

/** A look for the family's answers in the background; another in a minute while one may come. */
class FamilyAnswersWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val answers = applicationContext.appContainer.familyAnswers
        val waiting = runCatching { answers.check() }.getOrElse {
            Log.d("FamilyAnswers", "No answers fetched in the background: ${it.message}")
            return Result.success()
        }
        if (waiting && !applicationContext.appContainer.appForeground.isForeground) FamilyAnswers.soon(applicationContext, after = true)
        return Result.success()
    }
}
