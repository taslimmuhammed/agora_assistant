package com.local.assistant.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.AppContainer
import com.local.assistant.gallery.PhotoLibrary
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassMark
import com.local.assistant.ui.theme.GlassRoundButton
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.fadingEdges
import com.local.assistant.ui.theme.glass

/** What setup is doing now, from the app's downloads and indexes, kept current. */
@Composable
internal fun rememberSetupState(container: AppContainer): SetupState {
    val photoModel by container.galleryModelManager.installed.collectAsStateWithLifecycle()
    val photoTransfer by container.galleryModelManager.transfer.collectAsStateWithLifecycle()
    val photoError by container.galleryModelManager.error.collectAsStateWithLifecycle()
    val photos by container.galleryIndex.status.collectAsStateWithLifecycle()
    val faces by container.faceIndex.status.collectAsStateWithLifecycle()
    val memoryLeft by container.embeddingQueue.remaining.collectAsStateWithLifecycle()
    val paused by container.downloadsPaused.collectAsStateWithLifecycle()
    // Read again as a download stops or starts: a stopped one leaves its part behind.
    val photoPartial = remember(photoTransfer == null, photoModel, paused) { container.galleryModelManager.partialBytes() }
    return SetupState(
        photoModel = photoModel,
        photoTransfer = photoTransfer,
        photoError = photoError,
        photosAllowed = container.photoLibrary.access() != PhotoLibrary.Access.NONE,
        photos = photos,
        findPeople = container.faceIndex.isOn,
        faces = faces,
        memoryLeft = memoryLeft,
        paused = paused,
        photoPartialBytes = photoPartial,
    )
}

/**
 * Photo and memory search's setup, step by step, each with how far it's got: its model's
 * download, then the photos, their faces and earlier chats being indexed. It all carries on with
 * the app closed, with a notification, and the page says so. Opened from the chat's progress
 * pill, or from Settings.
 */
@Composable
fun SetupScreen(container: AppContainer, onBack: () -> Unit) {
    val state = rememberSetupState(container)
    val actions = remember(container) {
        // Starting one ends a pause, and the others paused with it carry on too, in turn.
        fun resuming(start: () -> Unit) {
            val paused = container.downloadsPaused.value
            start()
            if (paused) container.resumeDownloads()
        }
        SetupActions(
            download = {
                container.galleryModelManager.clearError()
                resuming(container.galleryModelManager::download)
            },
            stop = container::pauseDownloads,
            start = { kind -> if (kind == StepKind.PHOTO_MODEL) resuming(container.galleryModelManager::download) },
        )
    }
    SetupPage(state, actions, onBack)
}

/** What the setup page's buttons do. */
internal class SetupActions(
    /** The photo model's Download or Resume. */
    val download: () -> Unit,
    /** Pause. */
    val stop: () -> Unit,
    /** A step's Try again or Resume. */
    val start: (StepKind) -> Unit,
)

/** The page itself, drawn from [state] alone. */
@Composable
internal fun SetupPage(state: SetupState, actions: SetupActions, onBack: () -> Unit) {
    val steps = state.steps()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassRoundButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, size = 44.dp)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadingEdges(top = 12.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            GlassMark(size = 56.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Photo and memory search",
                style = TitleStyle,
                color = AppColors.TextPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Optional. Chatting needs no download: Luna's server answers. This lets Luna find your photos by what's in them, and earlier chats by meaning. You can leave the app: it keeps going, and a notification shows how far it's got.",
                style = BodyStyle,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().glass().padding(vertical = 6.dp).animateContentSize()) {
                steps.forEachIndexed { index, step ->
                    StepRow(step, actions)
                    if (index < steps.lastIndex) {
                        Box(Modifier.padding(start = 64.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(AppColors.Border))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Indexing runs on this phone: your photos and chats aren't sent anywhere for it. The internet is used only to download the model.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.glass(RoundedCornerShape(14.dp), Glass.Raised).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One step: its mark (a ring of progress, a tick, a dot), what it is, how it's going, and its buttons. */
@Composable
private fun StepRow(step: SetupStep, actions: SetupActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        StepMark(step)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (step.status == StepStatus.WAITING) AppColors.TextSecondary else AppColors.TextPrimary,
            )
            Text(
                step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (step.status == StepStatus.FAILED) AppColors.Danger else AppColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            val fraction = step.fraction
            if (step.status == StepStatus.RUNNING || step.status == StepStatus.PAUSED || (step.status == StepStatus.START && fraction != null)) {
                val shown by animateFloatAsState(fraction ?: 0f, label = "step")
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { shown },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                        color = if (step.status == StepStatus.PAUSED) AppColors.TextSecondary else AppColors.Accent,
                        trackColor = AppColors.SurfaceMuted,
                        drawStopIndicator = {},
                    )
                } else if (step.status == StepStatus.RUNNING) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                        color = AppColors.Accent,
                        trackColor = AppColors.SurfaceMuted,
                    )
                }
            }
            when {
                step.kind == StepKind.PHOTO_MODEL && step.status == StepStatus.START -> Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The size is in the line above.
                    NextButton(if (step.fraction != null) "Resume" else "Download", actions.download, icon = null)
                }
                step.kind == StepKind.PHOTO_MODEL && step.status == StepStatus.RUNNING -> SmallAction("Pause", actions.stop, Modifier.padding(top = 8.dp))
                step.resume -> SmallAction("Resume", { actions.start(step.kind) }, Modifier.padding(top = 8.dp))
                step.status == StepStatus.FAILED -> SmallAction("Try again", { actions.start(step.kind) }, Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun StepMark(step: SetupStep) {
    val icon = stepIcon(step.kind)
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        when (step.status) {
            StepStatus.DONE -> Box(Modifier.size(30.dp).background(AppColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(17.dp))
            }
            StepStatus.RUNNING -> {
                val fraction = step.fraction
                val shown by animateFloatAsState(fraction ?: 0f, label = "mark")
                if (fraction != null) {
                    CircularProgressIndicator(
                        progress = { shown },
                        modifier = Modifier.size(34.dp),
                        color = AppColors.Accent,
                        trackColor = AppColors.SurfaceMuted,
                        strokeWidth = 3.dp,
                        gapSize = 0.dp,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(34.dp), color = AppColors.Accent, trackColor = AppColors.SurfaceMuted, strokeWidth = 3.dp)
                }
                Icon(icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(16.dp))
            }
            else -> Box(
                Modifier.size(30.dp).glass(CircleShape, if (step.status == StepStatus.WAITING) Glass.Pane else Glass.Raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = when (step.status) {
                        StepStatus.FAILED -> AppColors.Danger
                        StepStatus.WAITING -> AppColors.TextSecondary
                        else -> AppColors.TextPrimary
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun stepIcon(kind: StepKind): ImageVector = when (kind) {
    StepKind.PHOTO_MODEL -> Icons.Outlined.Search
    StepKind.PHOTOS -> Icons.Outlined.PhotoLibrary
    StepKind.PEOPLE -> Icons.Outlined.Face
    StepKind.MEMORY -> Icons.Outlined.History
}

/** A quiet button beside the main one: words on a glass pill. */
@Composable
internal fun SmallAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = AppColors.TextPrimary,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .glass(GlassShapes.Pill, Glass.Raised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * The chat's note while the first setup carries on: what is under way and how far it's got. A
 * tap opens the setup page. Nothing when nothing worth one is running ([pill]).
 */
@Composable
fun SetupNote(container: AppContainer, onOpen: () -> Unit) {
    val pill = rememberSetupState(container).pill() ?: return
    SetupPillView(pill, onOpen)
}

@Composable
internal fun SetupPillView(pill: SetupPill, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .glass(GlassShapes.Pill, Glass.Raised)
                .clickable(role = Role.Button, onClickLabel = "See setup", onClick = onOpen)
                .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fraction = pill.fraction
            if (fraction != null) {
                val shown by animateFloatAsState(fraction, label = "pill")
                CircularProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.size(16.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.SurfaceMuted,
                    strokeWidth = 2.dp,
                    gapSize = 0.dp,
                )
            } else {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(pill.text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, maxLines = 1)
        }
    }
}
