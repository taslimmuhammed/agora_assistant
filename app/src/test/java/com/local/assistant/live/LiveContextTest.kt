package com.local.assistant.live

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What a call starts with: the phone's tools and memory, and on the lock screen far less. */
class LiveContextTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 21, 9, 0, 30, 123_000_000, zone)
    private val declarations = ToolCatalog.declarations(web = true, photos = true, health = true, files = true, inbox = true)
    private val shopping = UserNoteEntity(
        id = 1, title = "Shopping list", kind = NoteKind.LIST, items = listOf(NoteItem("Milk"), NoteItem("Curd", done = true)),
        pinned = true, createdAt = 0, updatedAt = 0,
    )
    private val unpinned = shopping.copy(id = 2, title = "Packing", pinned = false)
    private val note = UserNoteEntity(id = 3, title = "Gate code", kind = NoteKind.NOTE, body = "4417", pinned = true, createdAt = 0, updatedAt = 0)
    private var memoryAskedFor: Long? = -1L

    private val builder = LiveContextBuilder(
        memory = { chatId ->
            memoryAskedFor = chatId
            "Core facts: the user's son is Rahul."
        },
        declarations = { declarations },
        rules = { ToolCatalog.rules(web = true, photos = true, health = true, files = true, inbox = true) },
        notes = { listOf(shopping, unpinned, note) },
        health = { "Steps today: 3120" },
        userName = { "Lakshmi" },
        murfVoice = { "Anusha" },
        zone = { zone },
        clock = { now.toInstant().toEpochMilli() },
    )

    private fun names(tools: List<Map<String, Any?>>) = tools.map { it["name"] as String }

    @Test
    fun `every tool set up on the phone but those a call can't use`() {
        val tools = names(LiveContext.deviceTools(declarations, locked = false))

        assertFalse(ToolCatalog.REMEMBER_IMAGE in tools)
        assertFalse(ToolCatalog.TAKE_SCREENSHOT in tools)
        assertTrue(ToolCatalog.ADD_TASK in tools)
        assertTrue(ToolCatalog.SAVE_FACT in tools)
        assertEquals(declarations.size - 2, tools.size)
    }

    @Test
    fun `declarations go as the server takes them, parameters and all`() {
        val addTask = LiveContext.deviceTools(declarations, locked = false).first { it["name"] == ToolCatalog.ADD_TASK }

        val parameters = addTask["parameters"] as Map<*, *>
        assertEquals(listOf("title"), parameters["required"])
        assertTrue((parameters["properties"] as Map<*, *>).containsKey("when"))
        assertTrue((addTask["description"] as String).isNotBlank())
    }

    @Test
    fun `on the lock screen only the lock screen's tools`() {
        val tools = names(LiveContext.deviceTools(declarations, locked = true))

        assertTrue(tools.isNotEmpty())
        assertTrue(tools.toString(), tools.all { it in ToolCatalog.LOCKED })
    }

    @Test
    fun `rules about tools the call hasn't got are left out`() {
        val rules = LiveContext.toolRules(ToolCatalog.rules(web = true))

        assertFalse(ToolCatalog.REMEMBER_IMAGE in rules)
        assertFalse(ToolCatalog.TAKE_SCREENSHOT in rules)
        assertTrue(ToolCatalog.ADD_TASK in rules)
    }

    @Test
    fun `pinned lists go in full, other notes don't`() {
        val lists = LiveContext.pinnedLists(listOf(shopping, unpinned, note))

        assertEquals(
            listOf(LiveContext.ListContent("Shopping list", listOf(LiveContext.ListContent.Item("Milk", false), LiveContext.ListContent.Item("Curd", true)))),
            lists,
        )
    }

    @Test
    fun `an unlocked call has the user's memory, lists and health`() = runBlocking {
        val context = builder.build(LiveContext.PURPOSE_CHECKIN, doseId = "bp-morning", locked = false, chatId = 42)

        assertEquals("Lakshmi", context.userName)
        assertEquals("Core facts: the user's son is Rahul.", context.memory)
        assertEquals(42L, memoryAskedFor)
        assertEquals("Shopping list", context.lists.single().title)
        assertEquals("Steps today: 3120", context.health)
        assertEquals("2026-11-21T09:00:30+05:30", context.now)
        assertEquals("Asia/Kolkata", context.timeZone)
        assertEquals("en-IN", context.locale)
        assertEquals("checkin", context.purpose)
        assertEquals("bp-morning", context.doseId)
    }

    @Test
    fun `a locked call has nothing about the user`() = runBlocking {
        val context = builder.build(LiveContext.PURPOSE_USER, doseId = null, locked = true)

        assertNull(context.userName)
        assertEquals("", context.memory)
        assertTrue(context.lists.isEmpty())
        assertNull(context.health)
        assertEquals(-1L, memoryAskedFor)
        assertTrue(names(context.deviceTools).all { it in ToolCatalog.LOCKED })
    }

    @Test
    fun `sent with the server's field names`() = runBlocking {
        val json = JsonParser.parseString(Gson().toJson(builder.build(LiveContext.PURPOSE_CHECKIN, "bp-morning", locked = false))).asJsonObject

        for (key in listOf("user_name", "locale", "time_zone", "now", "purpose", "dose_id", "locked", "memory", "lists", "health", "tool_rules", "device_tools", "murf_voice")) {
            assertTrue(key, json.has(key))
        }
        assertEquals("Milk", json["lists"].asJsonArray[0].asJsonObject["items"].asJsonArray[0].asJsonObject["text"].asString)
    }

    @Test
    fun `a check-in rings at its time today, or tomorrow once it's gone`() {
        val nine = LocalTime.of(9, 0)

        assertEquals(now.withHour(9).withMinute(0).withSecond(0).withNano(0).plusDays(1), CheckIns.nextAt(nine, now))
        assertEquals(now.withHour(21).withMinute(0).withSecond(0).withNano(0), CheckIns.nextAt(LocalTime.of(21, 0), now))
        assertEquals(LocalTime.of(9, 30), CheckIn("d", "BP tablet", time = "09:30").timeOfDay())
        assertNull(CheckIn("d", "BP tablet", time = "morning").timeOfDay())
    }
}
