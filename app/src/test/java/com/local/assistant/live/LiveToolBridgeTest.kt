package com.local.assistant.live

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.local.assistant.data.db.Role
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.tools.FakeMemoryStore
import com.local.assistant.memory.tools.FakeReminders
import com.local.assistant.memory.tools.FakeSystemAlarms
import com.local.assistant.memory.tools.FakeToolLog
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolExecutor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The server's tool calls through the real executor, as the on-device model's go. */
class LiveToolBridgeTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, zone)
    private val store = FakeMemoryStore { now.toInstant().toEpochMilli() }
    private val reminders = FakeReminders()
    private val executor = ToolExecutor(store, reminders, FakeSystemAlarms(), FakeToolLog(), now = { now })
    private val chat = FakeCallChat()
    private val ran = mutableListOf<Pair<ToolCall, ToolContext>>()

    private fun bridge(
        locked: Boolean = false,
        lists: (suspend () -> List<LiveContext.ListContent>)? = null,
        execute: suspend (ToolCall, ToolContext) -> ToolExecutor.Outcome = executor::execute,
    ) =
        LiveToolBridge(
            execute = { call, context ->
                ran += call to context
                execute(call, context)
            },
            transcript = chat.log(),
            locked = locked,
            lists = lists,
            zone = { zone },
        )

    private fun toolCall(name: String, args: String, turnId: String? = "12", userText: String = "") =
        """{"type":"tool_call","call_id":"c1","name":"$name","args":$args""" +
            (turnId?.let { ""","turn_id":"$it"""" } ?: "") + ""","user_text":"$userText"}"""

    private fun json(text: String?): JsonObject = JsonParser.parseString(text ?: error("no reply")).asJsonObject

    private fun result(reply: JsonObject): JsonObject = JsonParser.parseString(reply["result"].asString).asJsonObject

    @Test
    fun `a reminder is set on the phone, with its chip, for the user's own message`() = runBlocking {
        val bridge = bridge()
        val chip = async(start = CoroutineStart.UNDISPATCHED) { bridge.events.first() }

        val reply = json(bridge.handle(toolCall("add_task", """{"title":"Take BP tablet","when":"today at 9 pm"}""", userText = "remind me to take my BP tablet at 9 tonight")))

        assertEquals("tool_result", reply["type"].asString)
        assertEquals("c1", reply["call_id"].asString)
        assertTrue(result(reply)["ok"].asBoolean)
        assertEquals("Take BP tablet", store.tasks.values.single().title)
        assertEquals(1, reminders.scheduled.size)
        val sent = reply["chip"].asJsonObject
        val shown = (chip.await() as LiveToolBridge.Event.Chip).chip
        assertEquals(shown.label, sent["title"].asString)
        assertTrue(sent["detail"].asString, "9:00 PM" in sent["detail"].asString)
        // The tool ran for a real, stored message of the user's: what they said.
        val asked = chat.messages.single()
        assertEquals(Role.USER to "remind me to take my BP tablet at 9 tonight", asked.role to asked.text)
        val context = ran.single().second
        assertEquals(asked.id, context.userMessageId)
        assertEquals(asked.chatId, context.chatId)
        assertTrue(context.heard)
        assertFalse(context.locked)
    }

    @Test
    fun `two tool calls in one turn share the user's message`() = runBlocking {
        val bridge = bridge()

        bridge.handle(toolCall("add_task", """{"title":"Call Rahul","when":"today at 5 pm"}""", userText = "remind me to call Rahul at 5 and save that he's my son"))
        bridge.handle(toolCall("save_fact", """{"subject":"Rahul","attribute":"relation","value":"son"}""", userText = "remind me to call Rahul at 5 and save that he's my son"))

        assertEquals(1, chat.messages.size)
        assertEquals(ran[0].second.userMessageId, ran[1].second.userMessageId)
    }

    @Test
    fun `on the lock screen only the lock screen's tools run`() = runBlocking {
        val bridge = bridge(locked = true)

        val refused = result(json(bridge.handle(toolCall("save_fact", """{"subject":"user","attribute":"bank","value":"SBI"}"""))))
        val allowed = result(json(bridge.handle(toolCall("add_task", """{"title":"Drink water","when":"in 1 hour"}"""))))

        assertFalse(refused["ok"].asBoolean)
        assertTrue(refused["error"].asString, "locked" in refused["error"].asString)
        assertTrue(allowed["ok"].asBoolean)
        assertEquals(listOf("add_task"), ran.map { it.first.name })
        assertTrue(ran.single().second.locked)
    }

    @Test
    fun `tools a call doesn't have are refused without running`() = runBlocking {
        val reply = json(bridge().handle(toolCall("remember_image", "{}")))

        assertFalse(result(reply)["ok"].asBoolean)
        assertNull(reply["chip"])
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a tool that fails on the phone still answers the server`() = runBlocking {
        val reply = json(bridge(execute = { _, _ -> error("database closed") }).handle(toolCall("add_task", """{"title":"x"}""")))

        assertEquals("c1", reply["call_id"].asString)
        assertFalse(result(reply)["ok"].asBoolean)
    }

    @Test
    fun `a call without a turn id still gets a message to point at`() = runBlocking {
        bridge().handle(toolCall("add_task", """{"title":"Buy milk","when":"tomorrow at 8 am"}""", turnId = null, userText = "remind me to buy milk tomorrow"))

        assertEquals(chat.messages.single().id, ran.single().second.userMessageId)
    }

    @Test
    fun `the server's own actions and the family's news are cards, with no reply`() = runBlocking {
        val bridge = bridge()
        val events = mutableListOf<LiveToolBridge.Event>()
        val collecting = async(start = CoroutineStart.UNDISPATCHED) { bridge.events.collect { events += it } }

        assertNull(bridge.handle("""{"type":"action","tool":"ask_family","ok":true,"title":"Asked Rahul","detail":"Can you take Amma to the doctor at 4:30?"}"""))
        assertNull(bridge.handle("""{"type":"hub_update","kind":"reply","text":"Rahul: I'll take her at 4:30","item":{}}"""))
        yield()
        collecting.cancel()

        assertEquals(
            listOf(
                LiveToolBridge.Event.Action("ask_family", true, "Asked Rahul", "Can you take Amma to the doctor at 4:30?"),
                LiveToolBridge.Event.HubUpdate("reply", "Rahul: I'll take her at 4:30"),
            ),
            events,
        )
    }

    @Test
    fun `anything else is ignored`() = runBlocking {
        val bridge = bridge()

        assertNull(bridge.handle("not json"))
        assertNull(bridge.handle("""{"type":"ping"}"""))
        assertNull(bridge.handle("""{"type":"tool_call","name":"add_task"}"""))
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a list tool's result brings her lists as they are now, and other tools' don't`() = runBlocking {
        val shopping = listOf(LiveContext.ListContent("Shopping list", listOf(LiveContext.ListContent.Item("Milk", false))))
        val done = ToolExecutor.Outcome("""{"ok":true}""", ok = true, recordId = null, chip = null, changedPrefix = false)
        val bridge = bridge(lists = { shopping }) { _, _ -> done }

        val edited = json(bridge.handle(toolCall(ToolCatalog.EDIT_NOTE, """{"note":"Shopping list","add":["Milk"]}""")))
        val other = json(bridge.handle(toolCall(ToolCatalog.SET_TIMER, """{"minutes":5}""")))

        assertEquals("Milk", edited["lists"].asJsonArray[0].asJsonObject["items"].asJsonArray[0].asJsonObject["text"].asString)
        assertNull(other["lists"])
    }
}
