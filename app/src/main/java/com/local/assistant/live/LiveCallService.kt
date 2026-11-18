package com.local.assistant.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.local.assistant.R
import com.local.assistant.appContainer
import com.local.assistant.ui.live.LiveCallActivity
import com.local.assistant.wake.WakeWord

/**
 * Keeps a live call going while the user is in another app: Android lets a background app use the
 * microphone only from a foreground service of the microphone type, and the media-playback type
 * keeps Luna's voice playing. Started by [LiveSession] while the call screen is in front, which is
 * when Android allows a microphone service to start, and stopped when the call ends. Its
 * notification goes back to the call, and can end it.
 */
class LiveCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            appContainer.liveCalls.current.value?.end()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), TYPES)
        } catch (e: RuntimeException) {
            // Refused from the background; the call still runs while its screen is in front.
            Log.w(TAG, "Not allowed to keep the call in the foreground", e)
            stopSelf()
        }
        // Not sticky: a call that died with the app is over; Agora ends the agent after its idle timeout.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            LiveCallActivity.returnIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveCallService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentTitle("On a call with ${WakeWord.NAME}")
            .setContentText("Tap to go back to the call")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setUsesChronometer(true)
            .addAction(0, "End call", end)
            .build()
    }

    companion object {
        private const val TAG = "LiveCallService"
        private const val CHANNEL = "live_call"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_END = "com.local.assistant.action.END_LIVE_CALL"

        private val TYPES = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveCallService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start the call's service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveCallService::class.java))
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Calls with ${WakeWord.NAME}", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown during a live voice call with the assistant."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
