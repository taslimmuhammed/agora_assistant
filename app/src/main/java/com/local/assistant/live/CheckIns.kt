package com.local.assistant.live

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.local.assistant.R
import com.local.assistant.appContainer
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.reminders.ReminderNotifications
import com.local.assistant.ui.live.IncomingCallActivity
import com.local.assistant.wake.WakeWord
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** A check-in call Luna makes at a dose's time: "Have you taken your BP tablet?" */
data class CheckIn(val doseId: String, val name: String, val detail: String? = null, val time: String) {

    /** "09:00" as a time of day; null when the server sent something else. */
    fun timeOfDay(): LocalTime? = try {
        LocalTime.parse(time.trim())
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * Luna rings the user at each dose's time, as a phone call does: a full-screen "Luna is calling"
 * over the lock screen ([IncomingCallActivity]), and answering starts a live call about that dose.
 * The doses come from the family hub (`GET /family`), set there by the caregiver, and are kept in
 * Settings so the alarms are set again after a restart without the network. Only while live voice
 * is on and has a server.
 */
class CheckIns(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: LiveApi,
    /** Live voice is on and has a server. */
    private val enabled: () -> Boolean,
) {

    /** The doses from the server, kept and set; also who the server knows the user as. */
    suspend fun refresh(): List<CheckIn> {
        val family = api.family()
        family.elder?.id?.takeIf { it.isNotBlank() }?.let { settings.liveUserId = it }
        val checkIns = family.doses.filter { it.id.isNotBlank() }.map { CheckIn(it.id, it.name, it.detail, it.time) }
        settings.checkInTimes = checkIns
        schedule()
        return checkIns
    }

    /** Sets each kept check-in's next alarm, or takes them all off while live voice is off. */
    fun schedule() {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = ZonedDateTime.now()
        for (checkIn in settings.checkInTimes) {
            val intent = ringIntent(context, checkIn)
            val at = checkIn.timeOfDay()?.let { nextAt(it, now) }
            if (!enabled() || at == null) {
                alarms.cancel(intent)
                continue
            }
            val ms = at.toInstant().toEpochMilli()
            // Exact when allowed, as reminders are; otherwise it may ring a few minutes late.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, intent)
            }
        }
    }

    companion object {
        private const val TAG = "CheckIns"
        private const val CHANNEL = "check_in_calls"
        private const val NOTIFICATION_ID = 7201

        /** How long it rings before giving up, as a missed call does. */
        private const val RING_MS = 60_000L

        /** "Later": rung again after this long. */
        const val LATER_MS = 15 * 60_000L

        internal const val ACTION_RING = "com.local.assistant.action.CHECK_IN"
        internal const val ACTION_LATER = "com.local.assistant.action.CHECK_IN_LATER"
        const val EXTRA_DOSE_ID = "dose_id"
        const val EXTRA_NAME = "dose_name"
        const val EXTRA_DETAIL = "dose_detail"
        const val EXTRA_TIME = "dose_time"

        /** The next time [time] comes round after [now]: today if it is still ahead, else tomorrow. */
        fun nextAt(time: LocalTime, now: ZonedDateTime): ZonedDateTime {
            val today = now.with(time).withSecond(0).withNano(0)
            return if (today.isAfter(now)) today else today.plusDays(1)
        }

        /**
         * "Luna is calling": the whole screen of a locked or sleeping phone, ringing with the
         * phone's ringtone until answered, put off, or a minute has gone by. As a place alarm is
         * ([ReminderNotifications.showPlace]); without full-screen access it rings as a banner.
         */
        fun ring(context: Context, checkIn: CheckIn) {
            if (!ReminderNotifications.canPost(context)) return
            createChannel(context)
            val screen = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                IncomingCallActivity.intent(context, checkIn),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val answer = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID + 1,
                IncomingCallActivity.intent(context, checkIn, answer = true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val later = PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID + 2,
                withDose(Intent(context, CheckInReceiver::class.java).setAction(ACTION_LATER), checkIn),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_assistant)
                .setContentTitle("${WakeWord.NAME} is calling")
                .setContentText("About your ${checkIn.name}")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setTimeoutAfter(RING_MS)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .addAction(0, "Answer", answer)
                .addAction(0, "Later", later)
                .build()
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notifications not allowed", e)
            }
        }

        /** Stops the ringing: answered, or put off. */
        fun stopRinging(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        /** Rings again in [LATER_MS]. */
        fun later(context: Context, checkIn: CheckIn) {
            stopRinging(context)
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val at = System.currentTimeMillis() + LATER_MS
            val intent = PendingIntent.getBroadcast(
                context,
                checkIn.doseId.hashCode() + 1,
                withDose(Intent(context, CheckInReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_LATER, true), checkIn),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }

        fun checkInIn(intent: Intent): CheckIn? {
            val id = intent.getStringExtra(EXTRA_DOSE_ID) ?: return null
            return CheckIn(id, intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "medicine" }, intent.getStringExtra(EXTRA_DETAIL), intent.getStringExtra(EXTRA_TIME).orEmpty())
        }

        fun withDose(intent: Intent, checkIn: CheckIn): Intent = intent
            .putExtra(EXTRA_DOSE_ID, checkIn.doseId)
            .putExtra(EXTRA_NAME, checkIn.name)
            .putExtra(EXTRA_DETAIL, checkIn.detail)
            .putExtra(EXTRA_TIME, checkIn.time)

        private const val EXTRA_LATER = "later"

        private fun ringIntent(context: Context, checkIn: CheckIn): PendingIntent = PendingIntent.getBroadcast(
            context,
            checkIn.doseId.hashCode(),
            withDose(Intent(context, CheckInReceiver::class.java).setAction(ACTION_RING), checkIn),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Rings with the phone's ringtone, on the ringtone stream, as calls do. */
        private fun createChannel(context: Context) {
            val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val channel = NotificationChannel(CHANNEL, "Check-in calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "${WakeWord.NAME} calling at your medicine times, when live voice is on."
                setSound(
                    tone,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** A check-in's time came round, or "Later" was tapped. */
class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val checkIn = CheckIns.checkInIn(intent) ?: return
        val container = context.appContainer
        when (intent.action) {
            CheckIns.ACTION_LATER -> CheckIns.later(context, checkIn)
            CheckIns.ACTION_RING -> {
                // Set for tomorrow first: whatever happens to this one, the next still rings.
                container.checkIns.schedule()
                if (!container.liveCalls.visible()) return
                // Already talking to Luna: she can ask in the call instead of ringing over it.
                if (container.liveCalls.current.value?.isOver == false) return
                CheckIns.ring(context, checkIn)
            }
        }
    }
}
