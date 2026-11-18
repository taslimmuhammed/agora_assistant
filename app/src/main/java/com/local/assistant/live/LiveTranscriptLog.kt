package com.local.assistant.live

import android.util.Log
import com.local.assistant.data.db.Role
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * A live call kept as a chat, so it reads like any other afterwards and memory works as it does
 * for typed chats: each exchange is archived the way `TurnRunner.finish` archives one, and recalled
 * in later chats ("what did I order today?").
 *
 * One chat per call, made with the first message. What was said is stored once it is final (the
 * toolkit's transcript END). A tool call can arrive before its words do, and the facts and tasks
 * it writes point at the user's message, so [ensureUserMessage] stores that message first; the
 * transcript that follows for the same turn then isn't stored again.
 *
 * Agora numbers each turn; the server passes the custom LLM request's turn_id on with a tool call,
 * which is the same number. Should a message come without one (typed into the call, or a tool
 * call with no turn), the words decide: the newest message stored early with the same words is it.
 */
class LiveTranscriptLog(
    private val createChat: suspend () -> Long,
    private val addMessage: suspend (chatId: Long, role: Role, text: String, incomplete: Boolean) -> Long,
    /** A message stored before its words were known gets them ([SPOKEN] until then). */
    private val updateMessage: suspend (messageId: Long, text: String) -> Unit,
    /** An exchange is stored, reply and all: archive it ([com.local.assistant.memory.prompt.TurnRunner.finish]). */
    private val onExchangeStored: suspend (userMessageId: Long) -> Unit,
) {

    private val mutex = Mutex()
    private var chatId: Long? = null

    /** The user's stored message for each turn, by turn id. */
    private val userMessages = HashMap<String, Long>()

    /** Messages stored with no turn id to match them by (typed, or a tool call's): id and words. */
    private val unmatched = ArrayDeque<Pair<Long, String>>()

    /** Turns stored before their words were final ([SPOKEN], or what was heard so far): given them when they are. */
    private val placeholders = HashSet<String>()

    /** What the user has said so far in each turn, before it is final. */
    private val heard = HashMap<String, String>()

    /** The latest user message stored. */
    private var lastUser: Long? = null

    /** Turns whose reply is stored. */
    private val answered = HashSet<String>()

    /** The chat, made on first use. */
    suspend fun chat(): Long = mutex.withLock { chatLocked() }

    /** The chat if the call has said anything yet. */
    suspend fun chatIfAny(): Long? = mutex.withLock { chatId }

    /**
     * The stored message for the user's turn [turnId], stored now with [text] (the server's
     * user_text) if it isn't yet. Always a real row: a tool call's facts and tasks refer to it.
     */
    suspend fun ensureUserMessage(turnId: String?, text: String): Long = mutex.withLock {
        val key = turnId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        key?.let(userMessages::get)?.let { return@withLock it }
        val words = text.trim().ifEmpty { key?.let(heard::get).orEmpty() }
        // The same words stored a moment ago without a turn are this message.
        unmatched.lastOrNull { same(it.second, words) }?.let { early ->
            if (key != null) {
                unmatched.remove(early)
                userMessages[key] = early.first
            }
            return@withLock early.first
        }
        if (key == null && words.isEmpty()) lastUser?.let { return@withLock it }
        val id = storeUser(words.ifEmpty { SPOKEN })
        when {
            key == null -> unmatched.addLast(id to words)
            else -> {
                userMessages[key] = id
                if (words.isEmpty()) placeholders += key
            }
        }
        id
    }

    /** The user typed [text] into the call instead of saying it. */
    suspend fun typed(text: String): Long = ensureUserMessage(null, text)

    /** The user's words so far in [turnId], not final yet: what a tool call arriving now was asked with. */
    suspend fun hearing(turnId: Long, text: String) = mutex.withLock {
        if (text.isNotBlank()) heard[turnId.toString()] = text.trim()
    }

    /** The user's words in [turnId] are final. */
    suspend fun userSaid(turnId: Long, text: String) = mutex.withLock {
        val key = turnId.toString()
        heard.remove(key)
        if (text.isBlank()) return@withLock
        userMessages[key]?.let { stored ->
            if (placeholders.remove(key)) updateMessage(stored, text.trim())
            return@withLock
        }
        // Stored early by a tool call that came without the turn's id.
        unmatched.lastOrNull { same(it.second, text) }?.let { early ->
            unmatched.remove(early)
            userMessages[key] = early.first
            return@withLock
        }
        userMessages[key] = storeUser(text.trim())
    }

    /**
     * Luna's reply in [turnId] is final, or was cut off ([interrupted]): stored, and the exchange
     * archived. A reply with no user turn of its own (the greeting) is stored on its own.
     */
    suspend fun agentSaid(turnId: Long, text: String, interrupted: Boolean = false) {
        val user = mutex.withLock {
            val key = turnId.toString()
            if (text.isBlank() || !answered.add(key)) return@withLock null
            if (key !in userMessages) {
                val words = heard.remove(key)
                when {
                    // Their words aren't final yet: stored first, so the reply comes after them.
                    words != null -> {
                        userMessages[key] = storeUser(words)
                        placeholders += key
                    }
                    // Typed into the call, or stored early by a tool call with no turn: this is its reply.
                    unmatched.isNotEmpty() -> userMessages[key] = unmatched.removeFirst().first
                }
            }
            addMessage(chatLocked(), Role.ASSISTANT, text.trim(), interrupted)
            userMessages[key]
        } ?: return
        try {
            onExchangeStored(user)
        } catch (e: Exception) {
            // The backfill catches anything missed here, as it does for typed chats.
            Log.w(TAG, "Could not archive message $user", e)
        }
    }

    private suspend fun storeUser(text: String): Long =
        addMessage(chatLocked(), Role.USER, text, false).also { lastUser = it }

    private suspend fun chatLocked(): Long = chatId ?: createChat().also { chatId = it }

    private fun same(a: String, b: String): Boolean = normal(a).let { it.isNotEmpty() && it == normal(b) }

    private fun normal(text: String): String =
        text.lowercase(Locale.ROOT).replace(NOT_WORDS, " ").trim()

    companion object {
        private const val TAG = "LiveTranscriptLog"
        private val NOT_WORDS = Regex("[^\\p{L}\\p{N}]+")

        /** A tool call that came with none of the user's words: they spoke, but what isn't known yet. */
        const val SPOKEN = "(spoken)"
    }
}
