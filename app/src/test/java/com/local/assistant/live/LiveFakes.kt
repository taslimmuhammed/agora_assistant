package com.local.assistant.live

import com.local.assistant.data.db.Role

/** A call's chat in a list: what [LiveTranscriptLog] stored, in order, and what it archived. */
class FakeCallChat {
    data class Stored(val id: Long, val chatId: Long, val role: Role, var text: String, val incomplete: Boolean)

    val messages = mutableListOf<Stored>()
    val archived = mutableListOf<Long>()
    var chatsMade = 0
    private var nextId = 100L

    fun log() = LiveTranscriptLog(
        createChat = { ++chatsMade + 10L },
        addMessage = { chatId, role, text, incomplete -> (nextId++).also { messages += Stored(it, chatId, role, text, incomplete) } },
        updateMessage = { id, text -> messages.first { it.id == id }.text = text },
        onExchangeStored = { archived += it },
    )

    fun said(): List<Pair<Role, String>> = messages.map { it.role to it.text }
}
