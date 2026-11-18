package com.local.assistant.live

import com.local.assistant.data.db.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call kept as a chat: each thing said stored once, in order, and each exchange archived. */
class LiveTranscriptLogTest {

    private val chat = FakeCallChat()
    private val log = chat.log()

    @Test
    fun `a spoken exchange is stored once it is final, and archived`() = runBlocking {
        log.hearing(1, "remind me to")
        log.userSaid(1, "Remind me to call Rahul at 5")
        log.agentSaid(1, "Done, I'll remind you at 5 PM.")

        assertEquals(listOf(Role.USER to "Remind me to call Rahul at 5", Role.ASSISTANT to "Done, I'll remind you at 5 PM."), chat.said())
        assertEquals(listOf(chat.messages[0].id), chat.archived)
        assertEquals(1, chat.chatsMade)
    }

    @Test
    fun `a tool call's message is the turn's, not stored again when its transcript comes`() = runBlocking {
        val early = log.ensureUserMessage("7", "remind me to take my BP tablet at 9")
        log.userSaid(7, "Remind me to take my BP tablet at 9.")
        log.agentSaid(7, "Okay.")

        assertEquals(2, chat.messages.size)
        assertEquals(early, chat.messages[0].id)
        assertEquals(listOf(early), chat.archived)
        // A second tool call in the same turn points at the same message.
        assertEquals(early, log.ensureUserMessage("7", "remind me to take my BP tablet at 9"))
    }

    @Test
    fun `a tool call without a turn id is matched by its words`() = runBlocking {
        val early = log.ensureUserMessage(null, "Add milk to my shopping list")
        log.userSaid(3, "add milk to my shopping list")
        log.agentSaid(3, "Added milk.")

        assertEquals(listOf(Role.USER to "Add milk to my shopping list", Role.ASSISTANT to "Added milk."), chat.said())
        assertEquals(listOf(early), chat.archived)
    }

    @Test
    fun `a tool call that came with no words is stored as spoken and filled in later`() = runBlocking {
        val early = log.ensureUserMessage("4", "")
        assertEquals(LiveTranscriptLog.SPOKEN, chat.messages.single().text)

        log.userSaid(4, "Set an alarm for 6")

        assertEquals("Set an alarm for 6", chat.messages.single { it.id == early }.text)
        assertEquals(1, chat.messages.size)
    }

    @Test
    fun `words heard so far are what a tool call without user_text was asked with`() = runBlocking {
        log.hearing(5, "what's on my shopping list")
        log.ensureUserMessage("5", "")

        assertEquals("what's on my shopping list", chat.messages.single().text)
    }

    @Test
    fun `a reply cut off is kept as incomplete, and a reply twice in one turn is stored once`() = runBlocking {
        log.userSaid(2, "Tell me about my day")
        log.agentSaid(2, "You have a doctor's appointment at", interrupted = true)
        log.agentSaid(2, "You have a doctor's appointment at 11.")

        assertEquals(2, chat.messages.size)
        assertTrue(chat.messages[1].incomplete)
    }

    @Test
    fun `text typed into the call is the next reply's question`() = runBlocking {
        val typed = log.typed("Rahul's number is 98450 12345")
        log.agentSaid(9, "Saved Rahul's number.")

        assertEquals(listOf(Role.USER to "Rahul's number is 98450 12345", Role.ASSISTANT to "Saved Rahul's number."), chat.said())
        assertEquals(listOf(typed), chat.archived)
    }

    @Test
    fun `the greeting has no question, and is stored on its own`() = runBlocking {
        log.agentSaid(0, "Hello Amma, it's Luna. Have you taken your BP tablet?")

        assertEquals(listOf(Role.ASSISTANT to "Hello Amma, it's Luna. Have you taken your BP tablet?"), chat.said())
        assertTrue(chat.archived.isEmpty())
    }

    @Test
    fun `the reply comes after the user's words even when theirs aren't final yet, which are filled in`() = runBlocking {
        log.hearing(6, "is Rahul coming")
        log.agentSaid(6, "Yes, at 4:30.")
        log.userSaid(6, "Is Rahul coming today?")

        assertEquals(listOf(Role.USER to "Is Rahul coming today?", Role.ASSISTANT to "Yes, at 4:30."), chat.said())
    }
}
