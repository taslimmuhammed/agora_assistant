package com.local.assistant.chat

import android.util.Log
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.prompt.TurnEvent
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.tools.MemoryChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends a message and gets its reply ([TurnRunner]), for both screens that can: the chat, and the
 * assistant overlay that opens from the power button.
 *
 * App-scoped rather than per screen: only one turn is ever in flight, whichever screen started
 * it, and a reply outlives the screen that asked for it: the overlay closing, or a tool opening a
 * settings panel, doesn't cut it off. It finishes and is stored in its chat.
 */
class ChatSender(
    private val repository: ChatRepository,
    private val turns: TurnRunner,
    private val scope: CoroutineScope,
) {

    /** A file sent with the message: a voice message's recording, a photo or a document. */
    data class Attachment(val path: String, val kind: AttachmentKind, val durationMs: Long? = null)

    /**
     * The reply on its way. [chatId] is null only for the moment a new chat is being made;
     * [owner] is the screen that sent the message. [asked]: the words just sent, for the screen
     * to show before the stored copy comes back from the database; [askedId] is that copy's id
     * once it is stored.
     */
    data class Live(
        val chatId: Long?,
        val text: String,
        val owner: Any,
        val asked: String? = null,
        val askedId: Long? = null,
    ) {
        /** What may be read aloud. */
        val sayable: String get() = text
    }

    /** What the screen that sent a message hears back. Called on the main thread. */
    interface Listener {
        /** A chat was made for the message, before anything is stored in it. */
        fun onChatCreated(chatId: Long) {}

        /** The user's message is stored; the reply is on its way. */
        fun onUserMessageStored(messageId: Long) {}

        /** Something was saved, scheduled or switched; its chip is stored with the chat too. */
        fun onMemory(chip: MemoryChip) {}

        /** The turn is over. [reply] is all of it, as stored; [failure] says what went wrong, if anything. */
        fun onFinished(reply: String, failure: String?) {}
    }

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    val isBusy: Boolean get() = _live.value != null

    private var job: Job? = null
    private var stopRequested = false

    /**
     * Stores [text] and [attachment] in [chatId] — a new chat when null — and gets the reply.
     * [hidden]: a new chat is left out of the chat list (the assistant overlay's). [locked]: asked
     * on the lock screen ([TurnRunner.run]). [screen]: the screenshot of the screen the assistant
     * overlay was opened over, if it was. Returns false, storing nothing, when there is nothing to
     * send or another turn is still in flight.
     */
    fun send(
        chatId: Long?,
        text: String,
        attachment: Attachment?,
        owner: Any,
        listener: Listener,
        hidden: Boolean = false,
        locked: Boolean = false,
        screen: String? = null,
    ): Boolean {
        val prompt = text.trim()
        if ((prompt.isEmpty() && attachment == null) || isBusy) return false
        stopRequested = false
        // Plain words only: a recording is shown once stored, as it was sent.
        _live.value = Live(chatId, "", owner, asked = prompt.takeIf { it.isNotEmpty() && attachment == null })

        // Main, as when each screen ran its own turns: the listeners update screen state.
        job = scope.launch(Dispatchers.Main.immediate) {
            var chat = chatId
            var userMessageId: Long? = null
            val reply = StringBuilder()
            var failure: String? = null
            try {
                if (chat == null) {
                    chat = repository.createChat(hidden = hidden)
                    // Both in one go, with no suspension between, so a screen matching the reply
                    // to its chat never sees one without the other.
                    _live.update { it?.copy(chatId = chat) }
                    listener.onChatCreated(chat)
                }

                // Stored first, so nothing the user wrote is lost whatever happens next.
                val asked = repository.addMessage(
                    chatId = chat,
                    role = Role.USER,
                    text = prompt,
                    attachmentPath = attachment?.path,
                    attachmentKind = attachment?.kind,
                    attachmentDurationMs = attachment?.durationMs,
                )
                userMessageId = asked
                _live.update { it?.copy(askedId = asked) }
                listener.onUserMessageStored(asked)
                repository.titleFromFirstMessage(
                    chatId = chat,
                    firstMessage = prompt,
                    fallback = if (attachment?.kind == AttachmentKind.AUDIO) ChatRepository.VOICE_TITLE else ChatRepository.DEFAULT_TITLE,
                )
                val heard = attachment?.kind == AttachmentKind.AUDIO
                val image = attachment?.path?.takeIf { attachment.kind == AttachmentKind.IMAGE }
                turns.run(chat, asked, prompt, locked, screen, heard, image).collect { event ->
                    when (event) {
                        is TurnEvent.Text -> {
                            reply.append(event.delta)
                            _live.update { it?.copy(text = reply.toString()) }
                        }
                        is TurnEvent.Memory -> listener.onMemory(event.chip)
                        TurnEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "That didn't work"
                // The screen shows it for a moment; the log keeps it, for working out why later.
                Log.w(TAG, "Turn failed in chat $chat", e)
            } finally {
                withContext(NonCancellable) {
                    val asked = userMessageId
                    // Null only if stopped before the message was stored: then there is no turn.
                    if (chat != null && asked != null) {
                        if (reply.isNotEmpty()) {
                            repository.addMessage(
                                chatId = chat,
                                role = Role.ASSISTANT,
                                text = reply.toString(),
                                incomplete = stopRequested || failure != null,
                            )
                        }
                        turns.finish(asked)
                    }
                    _live.value = null
                    listener.onFinished(reply.toString(), failure)
                }
            }
        }
        return true
    }

    /** Stops the turn in flight, keeping what it had said. */
    fun stop() {
        if (!isBusy) return
        stopRequested = true
        job?.cancel()
    }

    private companion object {
        const val TAG = "ChatSender"
    }
}
