package com.local.assistant.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.device.PermissionBroker
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.live.LiveCalls
import com.local.assistant.live.LiveContext
import com.local.assistant.live.LiveSession
import com.local.assistant.live.LiveToolBridge
import com.local.assistant.memory.tools.SystemAlarms
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.ui.chat.ChatViewModel
import com.local.assistant.ui.chat.chipsByMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The call screen's state: the call in progress ([LiveCalls.current]), what was done in it as
 * cards, and Undo. The phone's own tools are shown from the call's chat, as a chat shows them
 * ([chipsByMessage]), so Undo and Edit work and an undone card reads as undone; what the server
 * did itself, and the family's news, come from the bridge as they happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallViewModel(
    private val calls: LiveCalls,
    private val chats: ChatRepository,
    private val tools: ToolExecutor,
    private val clockAlarms: SystemAlarms,
    permissions: PermissionBroker,
) : ViewModel() {

    /** What the server did or heard, in the order it came. */
    data class ServerCard(val title: String, val detail: String?, val ok: Boolean, val news: Boolean)

    val session: StateFlow<LiveSession?> = calls.current

    /** A tool in the call asking for a permission (contacts, location): this screen asks. */
    val permissionRequests: SharedFlow<PermissionBroker.Request> = permissions.requests

    /** The call's chat, once anything has been said or done. */
    private val chatId: StateFlow<Long?> = calls.current
        .flatMapLatest { call -> call?.let { merge(it.transcripts, it.events).map { _ -> it.chatId() } } ?: flowOf(null) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The phone's tools' cards, oldest first. */
    val chips: StateFlow<List<ChatViewModel.ChipItem>> = chatId
        .flatMapLatest { id -> id?.let { chats.observeMessages(it) }?.map { chipsByMessage(it).values.flatten() } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _serverCards = MutableStateFlow<List<ServerCard>>(emptyList())
    val serverCards: StateFlow<List<ServerCard>> = _serverCards.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** A note under the cards: an Undo that couldn't be done. */
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            calls.current.filterNotNull().flatMapLatest { call ->
                _serverCards.value = emptyList()
                call.events
            }.collect { event ->
                when (event) {
                    is LiveToolBridge.Event.Action -> _serverCards.value += ServerCard(event.title, event.detail, event.ok, news = false)
                    is LiveToolBridge.Event.HubUpdate -> _serverCards.value += ServerCard(event.text, null, ok = true, news = true)
                    is LiveToolBridge.Event.Chip -> Unit
                }
            }
        }
    }

    /** Starts the call this screen was opened for, or goes back to the one already on. */
    fun start(purpose: String, doseId: String?, locked: Boolean, fromChat: Long?) {
        calls.current.value?.takeUnless { it.isOver }?.let { return }
        calls.start(purpose = purpose, doseId = doseId, locked = locked, fromChat = fromChat)
    }

    /** Puts back whatever the card's tool call changed, as a chat's Undo does. */
    fun undo(recordId: Long) {
        viewModelScope.launch {
            if (tools.undo(recordId) == ToolExecutor.UndoResult.CHANGED_SINCE) {
                _error.value = "That has changed since, so it was left as it is."
            }
        }
    }

    fun editTime(recordId: Long, at: Long) {
        viewModelScope.launch {
            if (!tools.editTime(recordId, at)) _error.value = "That reminder no longer exists."
        }
    }

    fun openClock() = clockAlarms.openClock()

    fun end() {
        calls.current.value?.end()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LiveCallViewModel(
                    calls = container.liveCalls,
                    chats = container.chatRepository,
                    tools = container.toolExecutor,
                    clockAlarms = container.clockAlarms,
                    permissions = container.permissions,
                )
            }
        }

        /** Why the call is being made, when the intent doesn't say. */
        const val DEFAULT_PURPOSE = LiveContext.PURPOSE_USER
    }
}
