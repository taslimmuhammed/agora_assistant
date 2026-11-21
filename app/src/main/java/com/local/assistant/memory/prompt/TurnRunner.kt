package com.local.assistant.memory.prompt

import android.util.Log
import com.local.assistant.live.LiveError
import com.local.assistant.live.ServerChat
import com.local.assistant.memory.tools.InstantCommands
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.NumberGrounding
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the chat UI hears about a turn. */
sealed interface TurnEvent {
    data class Text(val delta: String) : TurnEvent

    /** Something was saved, scheduled or switched; its chip is also stored with the chat. */
    data class Memory(val recordId: Long, val chip: MemoryChip) : TurnEvent

    data object Done : TurnEvent
}

/**
 * Answers one typed or spoken message. A sum is worked out on the phone, and the plainest commands
 * (an alarm at a time, a timer, the flashlight, the ringer, Do Not Disturb, the volume) are carried
 * out at once ([InstantCommands]) through [ToolExecutor], with the same checks, chip and undo a
 * call's are, online or not. Everything else is written by Luna's server ([ServerChat]): the brain
 * her Agora calls use, with the phone's tools run here as it asks for them. Without the server,
 * or the internet, the reply says what's needed.
 *
 * The user's message is stored before [run] is called and the reply after it finishes, so a crash
 * at any point loses nothing the user wrote; [finish] then archives the exchange.
 */
class TurnRunner(
    private val tools: ToolExecutor,
    /** Luna's server; null in tests that don't reach it. */
    private val server: ServerChat? = null,
    /** Luna's server can be asked now: it is set up and on, and the phone is online. */
    private val serverAvailable: () -> Boolean = { false },
    /** Luna's server is set up and on, whether or not the phone is online. */
    private val serverConfigured: () -> Boolean = { false },
    /** The exchange opened by this user message is stored, reply and all: archive it. */
    private val onExchangeStored: suspend (userMessageId: Long) -> Unit = {},
) {

    /**
     * The reply to [userText], the stored message [userMessageId]. [locked]: asked on the lock
     * screen, where only [ToolCatalog.LOCKED] tools run. [screen]: a screenshot of the screen the
     * assistant was opened over. [heard]: the words are the phone's hearing of a voice message,
     * whose digits a sum mustn't be read from ("two thousand four hundred" written "200400").
     * [image]: a photo sent with the message, which only the server can look at.
     */
    fun run(
        chatId: Long,
        userMessageId: Long,
        userText: String,
        locked: Boolean = false,
        screen: String? = null,
        heard: Boolean = false,
        image: String? = null,
    ): Flow<TurnEvent> = flow {
        if (!heard && image == null) {
            NumberGrounding.directAnswer(userText)?.let { answer ->
                emit(TurnEvent.Text(answer))
                emit(TurnEvent.Done)
                return@flow
            }
        }
        val call = InstantCommands.match(userText)?.takeIf { image == null && (!locked || it.name in ToolCatalog.LOCKED) }
        if (call != null) {
            val outcome = tools.execute(call, ToolContext(chatId, userMessageId, userText, locked, screen?.takeIf { !locked && java.io.File(it).isFile }, heard = heard))
            if (outcome.chip != null && outcome.recordId != null) emit(TurnEvent.Memory(outcome.recordId, outcome.chip))
            @Suppress("UNCHECKED_CAST")
            val result = runCatching { gson.fromJson(outcome.result, Map::class.java) as Map<String, Any?> }.getOrNull()
            val reply = result?.takeIf { outcome.ok }?.let { InstantCommands.reply(call, it, outcome.chip, java.time.ZonedDateTime.now()) }
            emit(TurnEvent.Text(reply ?: outcome.reply ?: (if (outcome.ok) DONE else COULDNT)))
            emit(TurnEvent.Done)
            return@flow
        }
        val chat = server
        if (chat == null || !serverAvailable()) {
            emit(TurnEvent.Text(if (serverConfigured()) NEEDS_INTERNET else NEEDS_SERVER))
            emit(TurnEvent.Done)
            return@flow
        }
        var said = false
        try {
            chat.reply(chatId, userMessageId, userText, locked, heard, image, screen).collect { event ->
                when (event) {
                    is ServerChat.Event.Text -> {
                        said = said || event.delta.isNotBlank()
                        emit(TurnEvent.Text(event.delta))
                    }
                    is ServerChat.Event.Memory -> emit(TurnEvent.Memory(event.recordId, event.chip))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveError) {
            Log.w(TAG, "Luna's server couldn't answer: ${e.message}")
            emit(TurnEvent.Text(if (said) " (${e.message})" else e.message ?: ServerChat.LOST))
        }
        emit(TurnEvent.Done)
    }

    /** Called once the reply (if any) is stored: the exchange is archived for recall. */
    suspend fun finish(userMessageId: Long) {
        // The backfill catches anything missed here, so a failure must not reach the chat screen.
        try {
            onExchangeStored(userMessageId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not archive message $userMessageId", e)
        }
    }

    companion object {
        private const val TAG = "TurnRunner"
        private val gson = com.google.gson.Gson()

        private const val ON_PHONE = "Without it I can still set alarms and timers, work out sums, and switch the flashlight, ringer, Do Not Disturb or volume."

        const val NEEDS_INTERNET = "I need the internet to answer that: I think on Luna's server. $ON_PHONE"
        const val NEEDS_SERVER = "To answer that I need Luna's server: set it up in Settings → Live voice and family. $ON_PHONE"

        const val DONE = "Done."
        const val COULDNT = "I couldn't do that just now. Please try again."
    }
}
