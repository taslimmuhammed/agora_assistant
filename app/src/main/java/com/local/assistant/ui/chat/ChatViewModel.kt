package com.local.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import com.local.assistant.AppContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.chat.ReplyReport
import com.local.assistant.chat.ReplyReporter
import com.local.assistant.chat.ReportError
import com.local.assistant.chat.ReportReason
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.device.PermissionBroker
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.media.Recording
import com.local.assistant.media.RecordingState
import com.local.assistant.voice.Transcriber
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.SystemAlarms
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.privacy.SensitiveLock
import com.local.assistant.privacy.SensitiveText
import com.local.assistant.voice.AudioFocus
import com.local.assistant.voice.Speaker
import com.local.assistant.voice.SpeechChunker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    /** Whether a message beyond the phone's own commands can be answered now: Luna's server, online. */
    private val server: () -> ServerState,
    private val sender: ChatSender,
    private val tools: ToolExecutor,
    private val clockAlarms: SystemAlarms,
    private val settings: SettingsStore,
    private val attachments: AttachmentStore,
    private val recorder: AudioRecorder,
    private val speaker: Speaker,
    private val focus: AudioFocus,
    permissions: PermissionBroker,
    /** Writes voice messages down as they're recorded; nothing when the phone can't. */
    private val transcriber: Transcriber? = null,
    /** Card numbers, PINs and the like are read out only while shown on screen ([SensitiveText.spoken]). */
    private val sensitive: SensitiveLock? = null,
    /** Sends a reply the user reports to the developer. */
    private val reporter: ReplyReporter? = null,
    /** The app's version and model, which a report carries. */
    private val appVersion: String = "",
    /** How often the phone closed the app lately, when worth a note ([com.local.assistant.AppContainer.phoneKills]). */
    val phoneKills: StateFlow<Int?> = MutableStateFlow(null),
    private val onPhoneKillsAnswered: () -> Unit = {},
    /** Family news Luna's server has for the user, shown in the chat on screen as Luna's message. */
    private val familyAnswers: com.local.assistant.live.FamilyAnswers? = null,
) : ViewModel() {

    /** A tool waiting on the user's answer to a permission dialog, e.g. contacts for "call amma". */
    val permissionRequests: SharedFlow<PermissionBroker.Request> = permissions.requests

    /**
     * A memory chip as the chat shows it. [recordId] is the TOOL row it came from; [taskId] the
     * place reminder it made or changed, shown on a map under it.
     */
    data class ChipItem(val recordId: Long, val chip: MemoryChip, val undone: Boolean, val canUndo: Boolean, val taskId: Long? = null)

    /** A file staged for the next send, shown as a chip above the composer. */
    data class PendingAttachment(
        val path: String,
        val kind: AttachmentKind,
        val durationMs: Long? = null,
    )

    val chats: StateFlow<List<ChatEntity>> = repository.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Null means "a new, not-yet-created chat". The row is only written once the first message
     * is sent, so tapping "New chat" repeatedly does not litter the sidebar.
     */
    private val _activeChatId = MutableStateFlow<Long?>(null)
    val activeChatId: StateFlow<Long?> = _activeChatId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val storedMessages = _activeChatId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeMessages(id) }

    val messages: StateFlow<List<MessageEntity>> = storedMessages
        // Tool calls are stored for the record but are not part of the conversation shown.
        .map { messages -> messages.filter { it.role != Role.TOOL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Chips by the message they sit under (see [chipsByMessage]). */
    val chips: StateFlow<Map<Long, List<ChipItem>>> = storedMessages
        .map(::chipsByMessage)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Set when a timed reminder has been made and notifications have never been asked for. */
    private val _askForNotifications = MutableStateFlow(false)
    val askForNotifications: StateFlow<Boolean> = _askForNotifications.asStateFlow()

    /** Set once, the first time a reminder could only be scheduled inexactly. */
    private val _offerExactAlarms = MutableStateFlow(false)
    val offerExactAlarms: StateFlow<Boolean> = _offerExactAlarms.asStateFlow()

    /**
     * The reply being streamed into the chat on screen, or null when it has none in flight. The
     * reply may be one the assistant overlay asked for, opened here while it streams.
     */
    val streamingText: StateFlow<String?> = combine(sender.live, _activeChatId) { live, chatId ->
        live?.takeIf { isHere(it, chatId) }?.text
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What the user just sent, shown at once until the stored copy comes back from the database. */
    val pendingQuestion: StateFlow<String?> = combine(sender.live, _activeChatId, messages) { live, chatId, shown ->
        live?.takeIf { isHere(it, chatId) }?.asked?.takeIf { live.askedId == null || shown.none { it.id == live.askedId } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The chat on screen is generating a reply. */
    val isGenerating: StateFlow<Boolean> = streamingText
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Another chat's reply is in flight — the overlay's, asked from the power button — and one turn
     * runs at a time, so a send here waits until it is done.
     */
    val busyElsewhere: StateFlow<Boolean> = combine(sender.live, _activeChatId) { live, chatId ->
        live != null && !isHere(live, chatId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A new chat's stream has no chat yet: it is this screen's only if this screen sent it. */
    private fun isHere(live: ChatSender.Live, chatId: Long?): Boolean =
        live.chatId == chatId && (chatId != null || live.owner === this)

    private fun generatingHere(): Boolean = sender.live.value?.let { isHere(it, _activeChatId.value) } == true

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _serverState = MutableStateFlow(server())

    /** Luna's server can answer now; when not, the chat says why above the composer ([serverNotice]). */
    val serverReady: StateFlow<Boolean> = _serverState.map { it == ServerState.READY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _serverState.value == ServerState.READY)

    /** Why only the phone's own commands work now. */
    fun serverNotice(): String = when (_serverState.value) {
        ServerState.OFFLINE -> "Offline: alarms, timers, sums and phone switches still work"
        else -> "Set up Luna's server in Settings to chat: alarms and timers work already"
    }

    /** A reply is being read aloud: one to a voice message, or one from its speaker button. */
    val speaking: StateFlow<Boolean> = speaker.speaking

    /** Cuts the streaming reply into sentences to speak; null when this reply isn't spoken. */
    private var chunker: SpeechChunker? = null

    /** A voice message is waiting for its spoken reply: other audio stays quiet until it's said. */
    private val awaitingSpeech = MutableStateFlow(false)

    private val _readingAloud = MutableStateFlow<Long?>(null)

    /** The reply being read aloud from its speaker button ([readAloud]); null when none is. */
    val readingAloud: StateFlow<Long?> = _readingAloud.asStateFlow()

    init {
        // A reply to a voice message is spoken sentence by sentence as it arrives, as in the overlay.
        viewModelScope.launch {
            sender.live.collect { live ->
                if (live?.owner === this@ChatViewModel) chunker?.push(live.sayable)?.forEach(::say)
            }
        }

        // Music pauses while recording and while the answer is read out; the grace period
        // bridges the gaps between sentences.
        viewModelScope.launch {
            combine(recorder.state, speaker.speaking, awaitingSpeech) { recording, speaking, awaiting ->
                recording != null || speaking || awaiting
            }
                .distinctUntilChanged()
                .collectLatest { busy ->
                    if (busy) {
                        focus.hold()
                    } else {
                        delay(FOCUS_GRACE_MS)
                        focus.release()
                    }
                }
        }

        // A reply read from its button has been read once the voice falls quiet (speak() makes it
        // speaking at once, so this never ends one about to start).
        viewModelScope.launch {
            speaker.speaking.collect { speaking -> if (!speaking) _readingAloud.value = null }
        }
        // And it stops when its chat is left: another one, a new one, or this one deleted.
        viewModelScope.launch {
            _activeChatId.collect { if (_readingAloud.value != null) stopSpeaking() }
        }

        // What the family answered, briefed by Luna: into the chat on screen, or a new one.
        familyAnswers?.let { answers ->
            viewModelScope.launch {
                answers.briefing.filterNotNull().collect { answers.showIn(_activeChatId.value)?.let(::openChat) }
            }
        }
    }

    /** Says [sentence], with its sensitive details said as how they end, unless they're being shown. */
    private fun say(sentence: String) {
        speaker.speak(if (sensitive?.revealed?.value == true) sentence else SensitiveText.spoken(sentence))
    }

    /** Stops reading the reply aloud, and doesn't read the rest of it as it comes. */
    fun stopSpeaking() {
        chunker = null
        awaitingSpeech.value = false
        _readingAloud.value = null
        speaker.stop()
    }

    /**
     * The speaker button under a finished reply: reads it aloud in place of anything being said,
     * or, pressed again while it is being read, stops it.
     */
    fun readAloud(message: MessageEntity) {
        val again = _readingAloud.value == message.id
        stopSpeaking()
        if (again) return
        val pieces = SpeechChunker.pieces(message.text)
        if (pieces.isEmpty()) return
        _readingAloud.value = message.id
        pieces.forEach(::say)
    }

    /**
     * The report sheet's Send: the reply and the question before it go to the developer. Throws
     * [ReportError], with words for the user, when the report didn't arrive.
     */
    suspend fun report(reply: MessageEntity, reason: ReportReason, note: String) {
        val reporter = reporter ?: throw ReportError("Reporting isn't set up in this build of the app.")
        reporter.send(replyReport(messages.value, reply, reason, note, appVersion))
    }

    /** The chat screen was left for another (Settings, Photos…): a reply read from its button stops. */
    fun stopReadingAloud() {
        if (_readingAloud.value != null) stopSpeaking()
    }

    val memoryPaused: StateFlow<Boolean> = settings.observeMemoryPaused()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.memoryPaused)

    /** Photos go to Luna's server, which looks at them; the composer offers them while it's set up. */
    val supportsImages: StateFlow<Boolean> = _serverState.map { it != ServerState.NONE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _serverState.value != ServerState.NONE)

    /** Voice messages are written down on the phone ([Transcriber]); the words are what's answered. */
    val supportsAudio: StateFlow<Boolean> = MutableStateFlow(transcriber != null).asStateFlow()

    private val _pendingAttachment = MutableStateFlow<PendingAttachment?>(null)
    val pendingAttachment: StateFlow<PendingAttachment?> = _pendingAttachment.asStateFlow()

    val recordingState: StateFlow<RecordingState?> = recorder.state

    fun startNewChat() {
        if (generatingHere()) return
        stopSpeaking()
        _activeChatId.value = null
    }

    fun selectChat(chatId: Long) {
        if (generatingHere() || _activeChatId.value == chatId) return
        stopSpeaking()
        _activeChatId.value = chatId
    }

    /**
     * From the assistant overlay's "Open in app": show that chat, even over one generating here.
     * Its reply, if still streaming, shows as it would have had it been sent from this screen.
     * Continued in the app, the overlay's chat joins the chat list.
     */
    fun openChat(chatId: Long) {
        viewModelScope.launch { repository.showChat(chatId) }
        if (_activeChatId.value == chatId) return
        _activeChatId.value = chatId
    }

    /** The chat screen came into view again (back from the panel, Settings or another app). */
    fun onScreenShown() {
        _serverState.value = server()
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
            // The rows are gone, so any file they referenced is now orphaned.
            attachments.pruneExcept(repository.attachmentPaths())
            if (_activeChatId.value == chatId) _activeChatId.value = null
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            discardPendingAttachment()
            runCatching { attachments.importImage(uri) }
                .onSuccess {
                    _pendingAttachment.value = PendingAttachment(it.absolutePath, AttachmentKind.IMAGE)
                }
                .onFailure { _error.value = it.message ?: "Could not attach that image" }
        }
    }

    /**
     * A PDF, Word, Excel or other document to send with the next message. It stays on the phone:
     * read_file reads what the reply needs of it ([com.local.assistant.files.DocumentExcerpts]).
     */
    fun attachDocument(uri: Uri) {
        viewModelScope.launch {
            discardPendingAttachment()
            runCatching { attachments.importDocument(uri) }
                .onSuccess {
                    _pendingAttachment.value = PendingAttachment(it.absolutePath, AttachmentKind.DOCUMENT)
                }
                .onFailure { _error.value = it.message ?: "Could not attach that document" }
        }
    }

    fun discardPendingAttachment() {
        _pendingAttachment.value?.let { attachments.delete(it.path) }
        _pendingAttachment.value = null
    }

    /**
     * Starts a voice message, which goes as soon as it ends ([stopRecording], or the length cap):
     * on its own, as on WhatsApp, never with text. Caller must already hold RECORD_AUDIO permission.
     */
    fun startRecording() {
        if (generatingHere() || recorder.isRecording) return
        // Don't talk over the user; and load the natural voice while they speak.
        stopSpeaking()
        if (settings.speakReplies) speaker.prepare()
        val destination = attachments.newAudioFile()
        // Written down as it's said, so the words are ready about when the user stops.
        val words = transcriber?.start()
        recorder.start(destination, onAudio = words?.let { it::feed }) { recording ->
            if (recording != null) viewModelScope.launch { sendVoice(recording, words?.finish()) } else words?.cancel()
        }
    }

    /** Ends the recording and sends it. */
    fun stopRecording() = recorder.stop()

    /** Ends the recording and throws it away. */
    fun cancelRecording() = recorder.cancel()

    fun send(text: String) {
        stopSpeaking()
        _serverState.value = server()
        val attachment = _pendingAttachment.value
        val started = sender.send(
            chatId = _activeChatId.value,
            text = text,
            attachment = attachment?.let { ChatSender.Attachment(it.path, it.kind, it.durationMs) },
            owner = this,
            listener = listener,
        )
        if (started) _pendingAttachment.value = null
    }

    /**
     * A finished recording goes straight out, and its reply is read aloud (Settings: Answer out
     * loud). With [transcript], the words are the message's text: Luna reads them, and the
     * recording stays for playing back.
     */
    private fun sendVoice(recording: Recording, transcript: String?) {
        val speak = settings.speakReplies
        chunker = if (speak) SpeechChunker() else null
        awaitingSpeech.value = speak
        val started = sender.send(
            chatId = _activeChatId.value,
            text = transcript.orEmpty(),
            attachment = ChatSender.Attachment(recording.file.absolutePath, AttachmentKind.AUDIO, recording.durationMs),
            owner = this,
            listener = listener,
        )
        if (!started) {
            chunker = null
            awaitingSpeech.value = false
            attachments.delete(recording.file.absolutePath)
            _error.value = "Another reply is still coming. Try again when it's done."
        }
    }

    private val listener = object : ChatSender.Listener {
        override fun onChatCreated(chatId: Long) {
            _activeChatId.value = chatId
        }

        override fun onMemory(chip: MemoryChip) = noticeReminder(chip)

        override fun onFinished(reply: String, failure: String?) {
            chunker?.finish(reply)?.forEach(::say)
            chunker = null
            awaitingSpeech.value = false
            _error.value = failure
        }
    }

    /** Puts back whatever the chip's tool call changed. */
    fun undo(recordId: Long) {
        viewModelScope.launch {
            if (tools.undo(recordId) == ToolExecutor.UndoResult.CHANGED_SINCE) {
                _error.value = "That has changed since, so it was left as it is."
            }
        }
    }

    /** Moves the chip's reminder or event to [at]. */
    fun editTime(recordId: Long, at: Long) {
        viewModelScope.launch {
            if (!tools.editTime(recordId, at)) _error.value = "That reminder no longer exists."
        }
    }

    fun openClock() = clockAlarms.openClock()

    fun notificationsAsked() {
        settings.askedForNotifications = true
        _askForNotifications.value = false
    }

    fun exactAlarmsOffered() {
        settings.offeredExactAlarms = true
        _offerExactAlarms.value = false
    }

    private fun noticeReminder(chip: MemoryChip) {
        if (chip.kind != MemoryChip.Kind.TASK || (chip.at == null && chip.place == null)) return
        if (!settings.askedForNotifications) _askForNotifications.value = true
        if (chip.note != null && !settings.offeredExactAlarms) _offerExactAlarms.value = true
    }

    /** The stop button: the reply stops where it is, and so does the voice. */
    fun stop() {
        stopSpeaking()
        if (generatingHere()) sender.stop()
    }

    /** The note about the phone closing the app was answered (opened or put away): not shown again. */
    fun phoneKillsAnswered() = onPhoneKillsAnswered()

    override fun onCleared() {
        // As when this screen ran its own turns: gone for good, it takes its reply with it.
        if (sender.live.value?.owner === this) sender.stop()
        stopSpeaking()
        focus.release()
    }

    companion object {
        /** How long other audio stays paused after the chat falls quiet. */
        private const val FOCUS_GRACE_MS = 800L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = container.chatRepository,
                    server = container::serverState,
                    sender = container.chatSender,
                    tools = container.toolExecutor,
                    clockAlarms = container.clockAlarms,
                    settings = container.settings,
                    attachments = container.attachmentStore,
                    recorder = container.audioRecorder,
                    speaker = container.speaker,
                    focus = container.chatAudioFocus,
                    permissions = container.permissions,
                    sensitive = container.sensitiveLock,
                    transcriber = container.transcriber,
                    reporter = container.replyReporter,
                    appVersion = container.appVersion,
                    phoneKills = container.phoneKills,
                    onPhoneKillsAnswered = container::toldAboutPhoneKills,
                    familyAnswers = container.familyAnswers,
                )
            }
        }
    }
}

/**
 * Chips by the message they sit under: the reply that followed the tool calls, or — while that
 * reply is still streaming, or if there never was one — the user message that asked.
 */
internal fun chipsByMessage(messages: List<MessageEntity>): Map<Long, List<ChatViewModel.ChipItem>> {
    val byMessage = mutableMapOf<Long, List<ChatViewModel.ChipItem>>()
    var asker: Long? = null
    var waiting = mutableListOf<ChatViewModel.ChipItem>()
    for (message in messages) {
        when (message.role) {
            Role.USER -> {
                asker?.let { if (waiting.isNotEmpty()) byMessage[it] = waiting }
                asker = message.id
                waiting = mutableListOf()
            }
            Role.TOOL -> ChatToolLog.parse(message.text)?.let { record ->
                record.chip?.let { chip ->
                    val placeTask = (record.undo?.createdTaskId ?: record.undo?.previousTask?.id)
                        ?.takeIf { chip.kind == MemoryChip.Kind.TASK && chip.place != null }
                    waiting += ChatViewModel.ChipItem(message.id, chip, record.undone, record.undo != null, placeTask)
                }
            }
            Role.ASSISTANT -> {
                if (waiting.isNotEmpty()) byMessage[message.id] = waiting
                asker = null
                waiting = mutableListOf()
            }
        }
    }
    asker?.let { if (waiting.isNotEmpty()) byMessage[it] = waiting }
    return byMessage
}

/**
 * A reported reply, as sent: the reply and the user's message just before it, with sensitive
 * details hidden whether or not they're shown on screen. A photo or voice message is named, not
 * sent; a voice message's words go as typed ones would.
 */
internal fun replyReport(
    messages: List<MessageEntity>,
    reply: MessageEntity,
    reason: ReportReason,
    note: String,
    version: String,
): ReplyReport {
    val at = messages.indexOfFirst { it.id == reply.id }.takeIf { it >= 0 } ?: messages.size
    val asked = messages.subList(0, at).lastOrNull { it.role == Role.USER }
    val question = asked?.let { message ->
        val attached = when (message.attachmentKind) {
            AttachmentKind.IMAGE -> "[a photo]"
            AttachmentKind.AUDIO -> "[a voice message]"
            AttachmentKind.DOCUMENT -> "[a document]"
            null -> null
        }
        listOfNotNull(attached, message.text.takeIf { it.isNotBlank() }?.let(SensitiveText::masked)).joinToString(" ")
    }.orEmpty()
    return ReplyReport(reason, note, question, SensitiveText.masked(reply.text), version)
}

/** Whether the chat's replies can come from Luna's server now. */
enum class ServerState {
    /** Set up and on, and the phone is online. */
    READY,

    /** Set up and on, but the phone is offline. */
    OFFLINE,

    /** No server set, or live voice and family turned off. */
    NONE,
}
