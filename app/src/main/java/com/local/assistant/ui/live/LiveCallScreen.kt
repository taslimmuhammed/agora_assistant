package com.local.assistant.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.live.LiveContext
import com.local.assistant.live.LiveSession
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.ui.chat.MemoryChips
import com.local.assistant.ui.chat.ToolPermissionRequests
import com.local.assistant.ui.memory.ConfirmDelete
import com.local.assistant.ui.memory.MemoryViewModel
import com.local.assistant.ui.memory.ReminderEditor
import com.local.assistant.ui.notes.NotesScreen
import com.local.assistant.ui.notes.NotesViewModel
import com.local.assistant.ui.theme.LunaOrb
import com.local.assistant.ui.theme.LunaOrbMode
import com.local.assistant.wake.WakeWord
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The call: Luna's orb, what is being said in large type, what was done as cards, and three big
 * buttons. Dark and high-contrast for older eyes and a phone held at arm's length; the words are
 * the transcript Agora sends, Luna's word by word as she says them.
 */
@Composable
fun LiveCallScreen(
    viewModel: LiveCallViewModel,
    micRefused: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    /** Reminders as the Reminders screen has them: a place reminder's map opens its editor over the call. */
    reminders: MemoryViewModel? = null,
    /** Her notes: a list made or changed in the call shows under its card, and opens on the Notes screen. */
    notes: NotesViewModel? = null,
) {
    val call by viewModel.session.collectAsState()
    val chips by viewModel.chips.collectAsState()
    val serverCards by viewModel.serverCards.collectAsState()
    val note by viewModel.error.collectAsState()
    val uriHandler = LocalUriHandler.current
    ToolPermissionRequests(viewModel.permissionRequests)

    // Nothing of hers over the lock screen: no maps, lists or editors there.
    val unlocked = call?.locked == false
    val memory = reminders?.takeIf { unlocked }
    val noteBook = notes?.takeIf { unlocked }
    var showingNote by remember { mutableStateOf(false) }
    if (showingNote && noteBook != null) {
        // The call goes on behind it; Back comes back to the call.
        BackHandler {
            noteBook.close()
            showingNote = false
        }
        NotesScreen(viewModel = noteBook, onBack = { showingNote = false }, backToChat = true)
        return
    }
    val cards = CallCards(
        placeReminders = memory?.let { vm ->
            val tasks by vm.tasks.collectAsState()
            remember(tasks) { tasks.filter { it.place != null }.associateBy { it.id } }
        }.orEmpty(),
        notes = noteBook?.let { vm ->
            val all by vm.all.collectAsState()
            remember(all) { all.associateBy { it.id } }
        }.orEmpty(),
        // Each list under the latest card that made or changed it, as in the chat.
        notePreviews = remember(chips) {
            chips.filter { it.chip.noteId != null && !it.undone }
                .groupBy { it.chip.noteId }.values.mapTo(HashSet()) { items -> items.maxOf { it.recordId } }
        },
        onOpenNote = noteBook?.let { vm ->
            { opened: UserNoteEntity ->
                vm.open(opened)
                showingNote = true
            }
        },
        onToggleNoteItem = { id, index -> noteBook?.toggle(id, index) },
    )
    var editingReminder by remember { mutableStateOf<TaskEntity?>(null) }
    var deletingReminder by remember { mutableStateOf<TaskEntity?>(null) }

    Box(Modifier.fillMaxSize().background(Night)) {
        val session = call
        when {
            micRefused -> Ended(
                title = "${WakeWord.NAME} needs the microphone",
                text = "Allow the microphone to talk to ${WakeWord.NAME} in a call.",
                onRetry = onRetry,
                onClose = onClose,
            )
            session == null -> Ended(title = "Connecting…", text = null, onRetry = null, onClose = onClose)
            else -> Call(
                session = session,
                chips = chips,
                serverCards = serverCards,
                note = note,
                viewModel = viewModel,
                cards = cards.copy(onOpenReminder = memory?.let { { task: TaskEntity -> editingReminder = task } }),
                openLink = { url -> if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uriHandler.openUri(url) } },
                onRetry = onRetry,
                onClose = onClose,
            )
        }
    }
    memory?.let { vm ->
        editingReminder?.let { task ->
            ReminderEditor(task, vm, onDelete = { deletingReminder = task }, onDone = { editingReminder = null })
        }
        deletingReminder?.let { task -> ConfirmDelete(task, vm, onDone = { deletingReminder = null }) }
    }
}

/** What the call's cards need beyond the chips: the same maps, lists and editors the chat gives them. */
private data class CallCards(
    val placeReminders: Map<Long, TaskEntity> = emptyMap(),
    val onOpenReminder: ((TaskEntity) -> Unit)? = null,
    val notes: Map<Long, UserNoteEntity> = emptyMap(),
    val notePreviews: Set<Long> = emptySet(),
    val onOpenNote: ((UserNoteEntity) -> Unit)? = null,
    val onToggleNoteItem: (Long, Int) -> Unit = { _, _ -> },
)

@Composable
private fun Call(
    session: LiveSession,
    chips: List<com.local.assistant.ui.chat.ChatViewModel.ChipItem>,
    serverCards: List<LiveCallViewModel.ServerCard>,
    note: String?,
    viewModel: LiveCallViewModel,
    cards: CallCards,
    openLink: (String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val state by session.state.collectAsState()
    val error by session.error.collectAsState()
    val captions by session.transcripts.collectAsState()
    val muted by session.muted.collectAsState()
    var typing by remember { mutableStateOf(false) }

    when (state) {
        LiveSession.State.FAILED -> return Ended(
            title = "The call didn't go through",
            text = error ?: "Try again, or ask on the phone instead.",
            onRetry = onRetry,
            onClose = onClose,
        )
        LiveSession.State.ENDED -> {
            // A moment to see it end, then back to wherever the call was made from.
            LaunchedEffect(session) {
                delay(ENDED_LINGER_MS)
                onClose()
            }
            return Ended(title = "Call ended", text = null, onRetry = null, onClose = onClose)
        }
        else -> Unit
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(WakeWord.NAME, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = Bright)
        Text(
            listOfNotNull(
                statusOf(state, muted),
                "about your medicine".takeIf { session.purpose == LiveContext.PURPOSE_CHECKIN },
                "phone locked".takeIf { session.locked },
            ).joinToString(" · "),
            style = TextStyle(fontSize = 17.sp),
            color = Dim,
        )
        Spacer(Modifier.height(18.dp))
        Orb(
            state = state,
            // Tapping the orb while she talks stops her, as saying something would.
            onTap = { if (state == LiveSession.State.SPEAKING) session.interrupt() },
        )
        Spacer(Modifier.height(18.dp))
        Captions(captions, Modifier.weight(1f).fillMaxWidth())
        if (chips.isNotEmpty() || serverCards.isNotEmpty() || note != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = CARDS_MAX)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                serverCards.forEach { ServerCard(it) }
                MemoryChips(
                    chips = chips,
                    onUndo = viewModel::undo,
                    onEditTime = viewModel::editTime,
                    onOpenClock = viewModel::openClock,
                    onOpenLink = openLink,
                    placeReminders = cards.placeReminders,
                    onOpenReminder = cards.onOpenReminder,
                    notes = cards.notes,
                    notePreviews = cards.notePreviews,
                    onOpenNote = cards.onOpenNote,
                    onToggleNoteItem = cards.onToggleNoteItem,
                )
                note?.let { Text(it, style = TextStyle(fontSize = 15.sp), color = Dim) }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (typing) {
            TypeLine(onSend = session::sendText)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(
                icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (muted) "Unmute" else "Mute",
                background = if (muted) Bright else Raised,
                tint = if (muted) Night else Bright,
                onClick = { session.setMuted(!muted) },
            )
            RoundButton(
                icon = Icons.Filled.CallEnd,
                label = "End",
                background = EndRed,
                tint = Color.White,
                size = 84.dp,
                onClick = viewModel::end,
            )
            RoundButton(
                icon = Icons.Filled.Keyboard,
                label = "Type",
                background = if (typing) Bright else Raised,
                tint = if (typing) Night else Bright,
                onClick = { typing = !typing },
            )
        }
    }
}

private fun statusOf(state: LiveSession.State, muted: Boolean): String = when (state) {
    LiveSession.State.CONNECTING -> "Connecting…"
    LiveSession.State.LISTENING -> if (muted) "Muted" else "Listening"
    LiveSession.State.THINKING -> "Thinking…"
    LiveSession.State.SPEAKING -> "Speaking"
    LiveSession.State.ENDED -> "Call ended"
    LiveSession.State.FAILED -> "Call failed"
}

/**
 * Luna, as the overlay shows her, larger: the spinning light while the call connects and while
 * she thinks or speaks (tap to stop her), rings and the mic while she listens.
 */
@Composable
private fun Orb(state: LiveSession.State, onTap: () -> Unit) {
    val mode = when (state) {
        LiveSession.State.LISTENING -> LunaOrbMode.LISTENING
        LiveSession.State.CONNECTING, LiveSession.State.THINKING, LiveSession.State.SPEAKING -> LunaOrbMode.BUSY
        LiveSession.State.ENDED, LiveSession.State.FAILED -> LunaOrbMode.IDLE
    }
    val icon = when (state) {
        LiveSession.State.LISTENING -> Icons.Filled.Mic
        LiveSession.State.SPEAKING -> Icons.Filled.Stop
        else -> null
    }
    LunaOrb(
        mode = mode,
        level = 0f,
        icon = icon,
        label = if (state == LiveSession.State.SPEAKING) "Stop ${WakeWord.NAME} talking" else WakeWord.NAME,
        onClick = onTap,
        core = ORB_CORE,
        area = ORB_AREA,
        iconSize = 44.dp,
    )
}

/** What has been said, newest at the bottom and largest: Luna's in white, the user's in grey. */
@Composable
private fun Captions(captions: List<LiveSession.Caption>, modifier: Modifier) {
    val list = rememberLazyListState()
    // Newest first, laid out from the bottom: the reply growing as she speaks stays pinned above
    // the cards, its latest line in view, rather than running on below the fold.
    val shown = captions.takeLast(SHOWN_CAPTIONS).asReversed()
    LaunchedEffect(shown.size, shown.firstOrNull()?.text) {
        if (shown.isNotEmpty()) list.scrollToItem(0)
    }
    LazyColumn(modifier, state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)) {
        itemsIndexed(shown) { index, caption ->
            val latest = index == 0
            Text(
                caption.text,
                modifier = Modifier.fillMaxWidth(),
                style = if (caption.fromUser) {
                    TextStyle(fontSize = 21.sp, lineHeight = 28.sp)
                } else {
                    TextStyle(fontSize = if (latest) 28.sp else 23.sp, lineHeight = if (latest) 36.sp else 30.sp, fontWeight = FontWeight.Medium)
                },
                color = when {
                    caption.fromUser -> Dim
                    caption.final || latest -> Bright
                    else -> Bright.copy(alpha = 0.8f)
                },
                textAlign = if (caption.fromUser) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

/** What the server did, or the family's news: "Asked Rahul", "Rahul will take you at 4:30". */
@Composable
private fun ServerCard(card: LiveCallViewModel.ServerCard) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Raised)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                card.news -> Icons.Outlined.People
                card.ok -> Icons.Outlined.CheckCircle
                else -> Icons.Outlined.ErrorOutline
            },
            contentDescription = null,
            tint = if (card.ok) Bright else EndRed,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(card.title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = Bright)
            card.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = TextStyle(fontSize = 16.sp), color = Dim) }
        }
    }
}

/** Typing into the call, for a name the recogniser keeps getting wrong, or a quiet room. */
@Composable
private fun TypeLine(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun send() {
        if (text.isBlank()) return
        onSend(text.trim())
        text = ""
    }
    TextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = 19.sp, color = Bright),
        placeholder = { Text("Type to ${WakeWord.NAME}", style = TextStyle(fontSize = 19.sp), color = Dim) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = Bright,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { send() }.padding(8.dp),
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Raised,
            unfocusedContainerColor = Raised,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Bright,
        ),
    )
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 68.dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.42f))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = TextStyle(fontSize = 16.sp), color = Dim)
    }
}

/** The call is over, never began, or can't: a line or two and the way out. */
@Composable
private fun Ended(title: String, text: String?, onRetry: (() -> Unit)?, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold), color = Bright, textAlign = TextAlign.Center)
        text?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = TextStyle(fontSize = 19.sp, lineHeight = 26.sp), color = Dim, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(32.dp))
        onRetry?.let { PillButton("Try again", dark = false, onClick = it) }
        Spacer(Modifier.height(12.dp))
        PillButton("Close", dark = true, onClick = onClose)
    }
}

@Composable
private fun PillButton(text: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(50))
            .background(if (dark) Raised else Bright)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = if (dark) Bright else Night)
    }
}

private val Night = Color(0xFF0B0B0F)
private val Raised = Color(0xFF26262D)
private val Bright = Color(0xFFFAFAFA)
private val Dim = Color(0xFFB8B8C2)
private val EndRed = Color(0xFFE5484D)
private val ORB_CORE = 120.dp
private val ORB_AREA = 220.dp
private val CARDS_MAX = 300.dp
private const val SHOWN_CAPTIONS = 8
private const val ENDED_LINGER_MS = 1_200L
