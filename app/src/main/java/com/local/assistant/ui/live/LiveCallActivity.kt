package com.local.assistant.ui.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.local.assistant.appContainer
import com.local.assistant.live.LiveContext
import com.local.assistant.ui.theme.LocalAssistantTheme

/**
 * A live call with Luna, full screen. Opened by "Call Luna" in the chat, by the power button or
 * "Hey Luna" while live voice is on and the phone is online, and by answering a check-in. The
 * screen stays on for the call, and over the lock screen when the call began there (the tools
 * are then the lock screen's few, as on the phone's own model). The call itself belongs to the
 * app ([com.local.assistant.live.LiveCalls]): leaving this screen doesn't end it, and its
 * notification comes back here.
 */
class LiveCallActivity : ComponentActivity() {

    private val viewModel: LiveCallViewModel by viewModels { LiveCallViewModel.factory(appContainer) }

    /** Android's microphone dialog was answered no: the call can't be made. */
    private val micRefused = mutableStateOf(false)

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCall(intent) else micRefused.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverLockScreen(intent)
        setContent {
            LocalAssistantTheme {
                LiveCallScreen(
                    viewModel = viewModel,
                    micRefused = micRefused.value,
                    onRetry = ::retry,
                    onClose = ::finish,
                )
            }
        }
        // Recreated (turned, or back from the notification): the call is already on.
        if (savedInstanceState == null) askOrStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showOverLockScreen(intent)
        askOrStart(intent)
    }

    override fun onStart() {
        super.onStart()
        inView = true
    }

    override fun onStop() {
        super.onStop()
        inView = false
    }

    private fun askOrStart(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_RETURN, false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall(intent)
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Try again: the call this screen was opened for, or a plain one when it was opened to go back to one. */
    private fun retry() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall(intent)
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCall(intent: Intent) {
        micRefused.value = false
        viewModel.start(
            purpose = intent.getStringExtra(EXTRA_PURPOSE) ?: LiveCallViewModel.DEFAULT_PURPOSE,
            doseId = intent.getStringExtra(EXTRA_DOSE_ID),
            locked = intent.getBooleanExtra(EXTRA_LOCKED, false),
            fromChat = intent.getLongExtra(EXTRA_FROM_CHAT, -1L).takeIf { it > 0 },
        )
    }

    /** A call begun on the lock screen shows over it; one begun unlocked doesn't stay up once locked. */
    private fun showOverLockScreen(intent: Intent) {
        val locked = intent.getBooleanExtra(EXTRA_LOCKED, false) || appContainer.liveCalls.current.value?.let { !it.isOver && it.locked } == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(locked)
            setTurnScreenOn(locked)
        } else {
            @Suppress("DEPRECATION")
            if (locked) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    companion object {
        private const val EXTRA_PURPOSE = "com.local.assistant.extra.LIVE_PURPOSE"
        private const val EXTRA_DOSE_ID = "com.local.assistant.extra.LIVE_DOSE_ID"
        private const val EXTRA_LOCKED = "com.local.assistant.extra.LIVE_LOCKED"
        private const val EXTRA_FROM_CHAT = "com.local.assistant.extra.LIVE_FROM_CHAT"
        private const val EXTRA_RETURN = "com.local.assistant.extra.LIVE_RETURN"

        /** The call screen is in front: a tool's permission dialog is asked here, not in the chat behind. */
        @Volatile
        var inView: Boolean = false
            private set

        /** Opens the call screen and starts a call (or goes back to the one on). */
        fun intent(
            context: Context,
            purpose: String = LiveContext.PURPOSE_USER,
            doseId: String? = null,
            locked: Boolean = false,
            fromChat: Long? = null,
        ): Intent = Intent(context, LiveCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_PURPOSE, purpose)
            .putExtra(EXTRA_DOSE_ID, doseId)
            .putExtra(EXTRA_LOCKED, locked)
            .apply { fromChat?.let { putExtra(EXTRA_FROM_CHAT, it) } }

        /** Back to the call in progress, from its notification: starts nothing. */
        fun returnIntent(context: Context): Intent = Intent(context, LiveCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_RETURN, true)
    }
}
