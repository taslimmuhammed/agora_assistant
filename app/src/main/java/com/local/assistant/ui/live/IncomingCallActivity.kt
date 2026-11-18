package com.local.assistant.ui.live

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.live.CheckIn
import com.local.assistant.live.CheckIns
import com.local.assistant.live.LiveContext
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.LocalAssistantTheme
import com.local.assistant.wake.WakeWord

/**
 * "Luna is calling", over the lock screen, at a dose's time ([CheckIns.ring]): Answer or Later,
 * as an incoming call is. Answering asks the phone to unlock first, so the call can do what an
 * unlocked call can (tick the dose off, tell the family); if the user doesn't unlock, the call
 * still goes ahead with the lock screen's tools, which is enough to talk about the medicine.
 */
class IncomingCallActivity : ComponentActivity() {

    private val checkIn = mutableStateOf<CheckIn?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        checkIn.value = CheckIns.checkInIn(intent) ?: return finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LocalAssistantTheme {
                checkIn.value?.let { shown ->
                    Ringing(
                        checkIn = shown,
                        onAnswer = { answer(shown) },
                        onLater = { later(shown) },
                    )
                }
            }
        }
        // The notification's own Answer: straight into the call.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ANSWER, false)) answer(checkIn.value ?: return)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = CheckIns.checkInIn(intent) ?: return
        checkIn.value = next
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) answer(next)
    }

    private fun answer(shown: CheckIn) {
        CheckIns.stopRinging(this)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            call(shown, locked = keyguard?.isKeyguardLocked == true)
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = call(shown, locked = false)

                override fun onDismissCancelled() = call(shown, locked = true)

                override fun onDismissError() = call(shown, locked = true)
            },
        )
    }

    private fun call(shown: CheckIn, locked: Boolean) {
        startActivity(LiveCallActivity.intent(this, purpose = LiveContext.PURPOSE_CHECKIN, doseId = shown.doseId, locked = locked))
        finish()
    }

    private fun later(shown: CheckIn) {
        CheckIns.later(this, shown)
        finish()
    }

    companion object {
        private const val EXTRA_ANSWER = "com.local.assistant.extra.CHECK_IN_ANSWER"

        fun intent(context: Context, checkIn: CheckIn, answer: Boolean = false): Intent =
            CheckIns.withDose(Intent(context, IncomingCallActivity::class.java), checkIn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ANSWER, answer)
    }
}

@Composable
private fun Ringing(checkIn: CheckIn, onAnswer: () -> Unit, onLater: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Night)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Pulse()
        Spacer(Modifier.height(32.dp))
        Text("${WakeWord.NAME} is calling", style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold), color = Bright, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            listOfNotNull("About your ${checkIn.name}", checkIn.detail?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
            color = Dim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1.3f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Choice(Icons.Filled.Snooze, "Later", Raised, onLater)
            Choice(Icons.Filled.Call, "Answer", Answer, onAnswer)
        }
    }
}

/** The assistant's light, a ring breathing out from it while it rings, as the place alarm's does. */
@Composable
private fun Pulse() {
    val pulse = rememberInfiniteTransition(label = "ring")
    val grow by pulse.animateFloat(1f, 1.4f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "grow")
    val fade by pulse.animateFloat(0.6f, 0f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "fade")
    val iris = Brush.sweepGradient(Glass.Iris)
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(120.dp).scale(grow).graphicsLayer { alpha = fade }.border(3.dp, iris, CircleShape))
        Box(Modifier.size(120.dp).clip(CircleShape).background(iris))
    }
}

@Composable
private fun Choice(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = Bright)
    }
}

private val Night = Color(0xFF0B0B0F)
private val Raised = Color(0xFF3A3A42)
private val Answer = Color(0xFF22A06B)
private val Bright = Color(0xFFFAFAFA)
private val Dim = Color(0xFFB8B8C2)
