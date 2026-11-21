package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.core.CoreMemoryRenderer
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskStatus
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.location.GeoPoint
import com.local.assistant.location.PlaceFinder
import com.local.assistant.location.SavedPlaces
import com.local.assistant.memory.db.PlaceDistance
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskPlace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Test group 3: tool calls as Luna's server asks for them, scripted, run through the real executor
 * against in-memory fakes. Fixed clock: Monday 21 September 2026, 10:00, Asia/Kolkata.
 */
class ToolRoutingTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private var now = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, zone)

    private val store = FakeMemoryStore { now.toInstant().toEpochMilli() }

    private val reminders = FakeReminders()

    private val log = FakeToolLog()

    private val clock = FakeSystemAlarms()

    private val images = FakeImageNotes()

    private var paused = false

    private val phone = FakePhone()

    private var contacts: List<Contact>? = listOf(
        Contact("Amma", listOf(ContactPhone("+91 98450 11111"))),
        Contact("Priya Sharma", listOf(ContactPhone("98450 22222")), listOf("priya@example.com")),
        Contact("Priya Nair", listOf(ContactPhone("98450 33333"))),
        Contact("Dr Rao Clinic", listOf(ContactPhone("080 4444 5555"))),
    )

    private val people = mutableMapOf<String, PersonHints>()

    /** The family hub on Luna's server: who her WhatsApp reaches, by the name asked for. */
    private val hub = mutableMapOf<String, String>()

    private val sentByLuna = mutableListOf<String>()

    /** Whether Luna's server can send now (live voice on); off, the phone's own apps open. */
    private var lunaOn = false

    private val device = DeviceTools(
        phone, { contacts }, { who -> people[who] ?: PersonHints.NONE }, clock, now = { now },
        family = { to, text, contact ->
            when {
                !lunaOn -> LunaSent.Off
                contact == null -> hub[to.lowercase()]?.let { sentByLuna += "$it: $text"; LunaSent.To(it) } ?: LunaSent.Unknown
                contact.number.endsWith("33333") -> LunaSent.Refused("${contact.name} isn't on WhatsApp, so nothing was sent. Offer an SMS instead.")
                else -> {
                    hub[to.lowercase()] = contact.name
                    sentByLuna += "${contact.name} (${contact.number}): $text"
                    LunaSent.To(contact.name)
                }
            }
        },
    )

    private val web = object : WebSearch {
        var searched = mutableListOf<Pair<String, Boolean>>()
        var failure: String? = null
        override val enabled = true
        override suspend fun search(query: String, news: Boolean): WebResults {
            failure?.let { throw WebSearchError(it) }
            searched += query to news
            return WebResults(
                answer = "It is 29°C and humid in Kochi. " + "More detail. ".repeat(80),
                results = List(5) { WebResult("Result $it", "https://www.site$it.com/page", "Snippet $it " + "words ".repeat(100)) },
            )
        }
    }

    private val placeSearch = FakePlaceSearch().apply { places["dmart"] = FakePlaceSearch.DMARTS }

    private val location = FakeLocation()

    private val appState = mutableMapOf<String, String>()

    private val placeFinder = PlaceFinder(placeSearch, { TEST_TOWNS }, location, SavedPlaces(store, { appState[it] }, { key, value -> appState[key] = value }))

    private val executor = ToolExecutor(
        store, reminders, clock, log, now = { now }, memoryPaused = { paused }, images = images, device = device, web = WebTools(web), places = placeFinder,
    )

    private val context = ToolContext(chatId = 1, userMessageId = 10)

    private fun call(name: String, vararg args: Pair<String, Any?>) = ToolCall(name, mapOf(*args))

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(2026, month, day, hour, minute), zone).toInstant().toEpochMilli()

    /** Runs one message in which Luna's model makes [calls] and then says [reply]. */
    private fun turn(vararg calls: ToolCall, reply: String = "Done."): Pair<ToolTurn, List<ToolEvent>> =
        executor.play(calls.toList(), context, reply)

    private fun result(session: ToolTurn, index: Int = 0): Map<*, *> =
        Gson().fromJson(session.sentResults.first()[index].json, Map::class.java)

    private fun error(session: ToolTurn) = result(session)["error"].toString()

    // ---- The brief's three cases ----

    @Test
    fun `a misheard command that still acts is known by its tool`() {
        // "Set alarm at 4:30" as the recogniser wrote it: no command in the words, but the turn
        // acted, so no related-photos card goes under it (TurnRunner).
        val (_, events) = turn(call("set_alarm", "when" to "6am"))
        val memory = events.filterIsInstance<ToolEvent.Memory>().single()
        assertEquals("set_alarm", memory.tool)
        assertTrue(memory.tool in ToolCatalog.ACTS)
        assertFalse(ToolCatalog.FIND_PHOTOS in ToolCatalog.ACTS || ToolCatalog.WEB_SEARCH in ToolCatalog.ACTS || ToolCatalog.SEARCH_MEMORY in ToolCatalog.ACTS)
    }

    // ---- Beyond the three ----

    @Test
    fun `hinglish time words resolve the same way`() {
        turn(call("add_task", "title" to "CA ko call karna", "when" to "kal subah 11 baje"))
        assertEquals(at(9, 22, 11), store.tasks.values.single().dueAt)
    }

    @Test
    fun `no time means an undated to-do with no alarm`() {
        val (_, events) = turn(call("add_task", "title" to "renew the passport"))
        assertNull(store.tasks.values.single().dueAt)
        assertTrue(reminders.scheduled.isEmpty())
        assertEquals("To-do", events.filterIsInstance<ToolEvent.Memory>().single().chip.label)
    }

    @Test
    fun `arguments the app cannot use come back as errors, and nothing is written`() {
        val (session, events) = turn(call("add_task", "title" to "call the CA", "when" to "someday"))
        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("someday"))
        assertTrue(store.tasks.isEmpty())
        assertTrue(events.none { it is ToolEvent.Memory })

        val (past, _) = turn(call("add_task", "title" to "call the CA", "when" to "today at 9"))
        assertTrue(result(past)["error"].toString().contains("passed"))

        val (missing, _) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist"))
        assertTrue(result(missing)["error"].toString().contains("value"))
    }

    @Test
    fun `every call is recorded, errors included`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "city", "value" to "Bengaluru"))
        turn(call("add_task", "title" to "x", "when" to "never ever"))
        assertEquals(listOf("save_fact", "add_task"), log.records.values.map { it.tool })
        assertNull(log.records.values.last().chip)
    }

    @Test
    fun `the same call within two minutes is not repeated`() {
        val add = call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11")
        val (first, _) = turn(add)
        now = now.plusSeconds(60)
        val (second, events) = turn(add)
        assertEquals(1, store.tasks.size)
        assertEquals(result(first)["id"], result(second)["id"])
        assertTrue("no second chip", events.none { it is ToolEvent.Memory })

        now = now.plusMinutes(2)
        turn(add)
        assertEquals(2, store.tasks.size)
    }

    @Test
    fun `undo takes a reminder back, alarm and all, exactly once`() {
        val (_, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val recordId = events.filterIsInstance<ToolEvent.Memory>().single().recordId

        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(recordId) })
        assertTrue(store.tasks.isEmpty())
        assertTrue(reminders.scheduled.isEmpty())
        assertTrue(log.records.getValue(recordId).undone)
        assertEquals(ToolExecutor.UndoResult.ALREADY_UNDONE, runBlocking { executor.undo(recordId) })
    }

    @Test
    fun `undo of an updated fact puts the old value back`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao"))
        now = now.plusMinutes(5)
        val (_, events) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Mehta"))
        assertEquals("Updated", events.filterIsInstance<ToolEvent.Memory>().single().chip.label)

        runBlocking { executor.undo(events.filterIsInstance<ToolEvent.Memory>().single().recordId) }
        assertEquals("Dr. Rao", store.facts.values.single().value)
    }

    @Test
    fun `undo refuses when the fact has changed since`() {
        val (_, events) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao"))
        now = now.plusMinutes(5)
        turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Mehta"))
        val first = events.filterIsInstance<ToolEvent.Memory>().single().recordId
        assertEquals(ToolExecutor.UndoResult.CHANGED_SINCE, runBlocking { executor.undo(first) })
        assertEquals("Dr. Mehta", store.facts.values.single().value)
    }

    @Test
    fun `move the CA call to 3 keeps the reminder's own day`() {
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val (session, events) = turn(call("update_task", "task" to "the CA call", "when" to "3"))
        val task = store.tasks.values.single()
        assertEquals(at(9, 22, 15), task.dueAt)
        assertEquals(at(9, 22, 15), reminders.scheduled[task.id])
        assertEquals("Moved", events.filterIsInstance<ToolEvent.Memory>().single().chip.label)
        assertEquals("Tue 22 Sep 15:00", result(session)["due"])
    }

    @Test
    fun `finishing a repeating reminder moves it to the next time`() {
        turn(call("add_task", "title" to "Submit timesheet", "when" to "monday 9am", "repeat" to "every Monday"))
        val (_, events) = turn(call("update_task", "task" to "timesheet", "status" to "done"))
        val task = store.tasks.values.single()
        assertEquals(TaskStatus.OPEN, task.status)
        assertEquals(at(10, 5, 9), task.dueAt)
        assertEquals("Done, next one set", events.filterIsInstance<ToolEvent.Memory>().single().chip.label)
    }

    @Test
    fun `finishing a one-off reminder closes it and silences it`() {
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        turn(call("update_task", "task" to "1", "status" to "completed"))
        assertEquals(TaskStatus.DONE, store.tasks.values.single().status)
        assertTrue(reminders.scheduled.isEmpty())
    }

    @Test
    fun `an ambiguous reminder is asked about, a specific one is found`() {
        runBlocking {
            store.insertTask(TaskEntity(title = "Call the CA", createdAt = 0, updatedAt = 0))
            store.insertTask(TaskEntity(title = "Call the CA office", createdAt = 0, updatedAt = 0))
        }
        val (ambiguous, _) = turn(call("update_task", "task" to "call CA", "status" to "done"))
        assertTrue(result(ambiguous)["error"].toString().contains("More than one"))

        turn(call("update_task", "task" to "CA office", "status" to "done"))
        assertEquals(TaskStatus.DONE, store.tasks.values.single { it.title == "Call the CA office" }.status)
    }

    @Test
    fun `forget my dentist finds it either way it is asked, and can be undone`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao"))
        val (session, events) = turn(call("forget", "subject" to "dentist"))
        assertEquals(1.0, result(session)["removed"])
        assertTrue(store.facts.isEmpty())
        assertEquals("Forgot", events.filterIsInstance<ToolEvent.Memory>().single().chip.label)

        runBlocking { executor.undo(events.filterIsInstance<ToolEvent.Memory>().single().recordId) }
        assertEquals("Dr. Rao", store.facts.values.single().value)
        assertTrue("tombstone lifted", store.tombstones.isEmpty())
    }

    @Test
    fun `events carry their end, and a date alone is all day`() {
        turn(call("add_event", "title" to "Team meeting", "when" to "tomorrow 10am", "ends" to "11am"))
        val meeting = store.events.values.single()
        assertEquals(at(9, 22, 10), meeting.startsAt)
        assertEquals(at(9, 22, 11), meeting.endsAt)

        turn(call("add_event", "title" to "Wife's birthday", "when" to "12 Oct", "repeat" to "every year"))
        val birthday = store.events.values.last()
        assertTrue(birthday.allDay)
        assertEquals("FREQ=YEARLY", birthday.recurrence)
    }

    @Test
    fun `events alert thirty minutes ahead, all-day ones at eight that morning`() {
        turn(call("add_event", "title" to "Dentist", "when" to "thursday 5pm"))
        val dentist = store.events.values.single()
        assertEquals(at(9, 24, 16, 30), reminders.eventAlerts[dentist.id])

        turn(call("add_event", "title" to "Anniversary", "when" to "friday"))
        val anniversary = store.events.values.last()
        assertEquals(at(9, 25, 8), reminders.eventAlerts[anniversary.id])

        runBlocking { executor.undo(log.records.entries.last().key) }
        assertNull(reminders.eventAlerts[anniversary.id])
    }

    @Test
    fun `a repeating event's alert is for its next occurrence`() {
        turn(call("add_event", "title" to "Standup", "when" to "tomorrow 10am", "repeat" to "every day"))
        val standup = store.events.values.single()
        assertEquals(at(9, 22, 9, 30), reminders.eventAlerts[standup.id])
        val next = EventTimes.nextAlert(standup, at(9, 22, 9, 45), zone, WhenDefaults().dateOnly)!!
        assertEquals(at(9, 23, 9, 30), next.at)
        assertEquals(at(9, 23, 10), next.startsAt)
    }

    // ---- Clock alarms ----

    @Test
    fun `wake me up at 6 sets a clock alarm for the next 6 o'clock`() {
        val (session, events) = turn(call("set_alarm", "when" to "6am", "label" to "wake up"))
        assertEquals(listOf(FakeSystemAlarms.Set(6, 0, emptySet(), "Wake up")), clock.set)
        assertEquals("Tue 22 Sep 06:00", result(session)["alarm"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.ALARM, chip.kind)
        assertEquals(at(9, 22, 6), chip.at)
        assertNull("the clock app owns it; no undo from here", log.records.values.single().undo)
    }

    @Test
    fun `wake me up at 5 30 tomorrow means the morning, and a label that just says alarm is dropped`() {
        turn(call("set_alarm", "when" to "tomorrow at 5:30", "label" to "Alarm"))
        assertEquals(listOf(FakeSystemAlarms.Set(5, 30, emptySet(), null)), clock.set)
    }

    @Test
    fun `a misheard request is neither the alarm's name nor a reminder`() {
        // "set alarm at 4:30" written down as "setalaam 4:30" / "sarlamat 4:30 p.m. today".
        turn(call("set_alarm", "when" to "6am", "label" to "Setalaam"))
        assertEquals(listOf(FakeSystemAlarms.Set(6, 0, emptySet(), null)), clock.set)

        val (session, _) = turn(call("add_task", "title" to "sarlamat", "when" to "today at 4:30 p.m."))
        assertTrue(store.tasks.isEmpty())
        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("set_alarm"))
    }

    /** As [turn], for a message the user said: [text]. */
    private fun said(text: String, vararg calls: ToolCall): Pair<ToolTurn, List<ToolEvent>> =
        executor.play(calls.toList(), context.copy(userText = text))

    @Test
    fun `an alarm's name is one the user said`() {
        // The model named it after a reminder in its list (2 Nov 2026).
        said("alarm at 6 a.m.", call("set_alarm", "when" to "6am", "label" to "Sarlamat (task 3)"))
        said("set an alarm for gym at 7", call("set_alarm", "when" to "7am", "label" to "gym"))
        said("wake me up at 8", call("set_alarm", "when" to "8am", "label" to "Morning routine"))
        assertEquals(listOf(null, "Gym", null), clock.set.map { it.label })
    }

    @Test
    fun `a new name for an alarm just set opens the clock app rather than setting a second`() {
        said("set an alarm at 6", call("set_alarm", "when" to "6am"))
        val (session, _) = said("name it gym", call("set_alarm", "when" to "6am", "label" to "gym"))
        assertEquals(listOf(FakeSystemAlarms.Set(6, 0, emptySet(), null)), clock.set)
        assertEquals(1, clock.clockOpened)
        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("rename"))
        // Another time is another alarm.
        said("and one at 7 for gym", call("set_alarm", "when" to "7am", "label" to "gym"))
        assertEquals(2, clock.set.size)
    }

    @Test
    fun `a weekday alarm repeats on those days`() {
        val (session, events) = turn(call("set_alarm", "when" to "7am", "repeat" to "weekdays"))
        val days = java.time.DayOfWeek.entries.take(5).toSet()
        assertEquals(listOf(FakeSystemAlarms.Set(7, 0, days, null)), clock.set)
        assertEquals("Mon, Tue, Wed, Thu, Fri", result(session)["repeats"])
        assertEquals(at(9, 22, 7), events.filterIsInstance<ToolEvent.Memory>().single().chip.at)
    }

    @Test
    fun `what the clock app cannot do is refused, so the model can offer a reminder`() {
        val (farOff, _) = turn(call("set_alarm", "when" to "friday 6am"))
        assertTrue(result(farOff)["error"].toString().contains("24 hours"))

        val (noTime, _) = turn(call("set_alarm", "when" to "tomorrow"))
        assertTrue(result(noTime)["error"].toString().contains("What time"))

        val (oddRepeat, _) = turn(call("set_alarm", "when" to "6am", "repeat" to "every other day"))
        assertTrue(result(oddRepeat)["error"].toString().contains("days of the week"))
        assertTrue(clock.set.isEmpty())
    }

    @Test
    fun `no clock app, or one that cannot be reached, is an error rather than a false promise`() {
        clock.reachable = false
        val (unreachable, _) = turn(call("set_alarm", "when" to "6am"))
        assertEquals(false, result(unreachable)["ok"])

        clock.present = false
        val (absent, _) = turn(call("set_alarm", "when" to "6am"))
        assertTrue(result(absent)["error"].toString().contains("reminder"))
    }

    @Test
    fun `get upcoming lists what is due, soonest first`() {
        turn(call("add_task", "title" to "Pay rent", "when" to "friday 9am"))
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        runBlocking { store.insertEvent(EventEntity(title = "Dentist", startsAt = at(9, 24, 17), createdAt = 0, updatedAt = 0)) }
        val (session, _) = turn(call("get_upcoming", "days" to 7))
        val titles = (result(session)["items"] as List<*>).map { (it as Map<*, *>)["title"] }
        assertEquals(listOf("Call the CA", "Dentist", "Pay rent"), titles)
    }

    @Test
    fun `search memory finds facts and reminders by their words`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "ca", "value" to "Mr. Iyer"))
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val (session, _) = turn(call("search_memory", "query" to "CA"))
        assertEquals(listOf("CA: Mr. Iyer"), result(session)["facts"])
        assertTrue((result(session)["items"] as List<*>).single().toString().startsWith("Call the CA"))
    }

    @Test
    fun `without exact alarms the chip says it may be late`() {
        reminders.exact = false
        val (_, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        assertEquals("may be a few minutes late", events.filterIsInstance<ToolEvent.Memory>().single().chip.note)
    }

    @Test
    fun `editing a chip's time moves the reminder and its alarm`() {
        val (_, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        val recordId = events.filterIsInstance<ToolEvent.Memory>().single().recordId
        assertTrue(runBlocking { executor.editTime(recordId, at(9, 23, 16)) })
        val task = store.tasks.values.single()
        assertEquals(at(9, 23, 16), task.dueAt)
        assertEquals(at(9, 23, 16), reminders.scheduled[task.id])
        assertEquals(at(9, 23, 16), log.records.getValue(recordId).chip!!.at)
    }

    // ---- Saved images ----

    @Test
    fun `remember this keeps the image the user just sent with the model's details`() {
        images.sent[1] = mutableMapOf(4L to "/a/old.jpg", 9L to "/a/card.jpg", 11L to "/a/later.jpg")
        val (session, events) = turn(
            call("remember_image", "title" to "wifi card", "details" to "Network: Home_5G. Password: kochi2026. Router: TP-Link Archer C6."),
        )
        val (title, details, image) = images.saved.values.single()
        assertEquals("Wifi card", title)
        assertTrue("kochi2026" in details)
        assertEquals("the newest image up to this message", 9L, image.messageId)
        assertEquals(true, result(session)["ok"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.IMAGE, chip.kind)
        assertEquals("Saved image", chip.label)
    }

    @Test
    fun `remember this with no image in the chat is an error, and nothing is saved`() {
        val (session, events) = turn(call("remember_image", "title" to "receipt", "details" to "Total 450"))
        assertEquals(false, result(session)["ok"])
        assertTrue(images.saved.isEmpty())
        assertTrue(events.none { it is ToolEvent.Memory })
    }

    @Test
    fun `undo of a saved image deletes it`() {
        images.sent[1] = mutableMapOf(10L to "/a/receipt.jpg")
        val (_, events) = turn(call("remember_image", "title" to "receipt", "details" to "Total 450"))
        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(events.filterIsInstance<ToolEvent.Memory>().single().recordId) })
        assertTrue(images.saved.isEmpty())
    }

    @Test
    fun `while memory is paused no image is saved`() {
        paused = true
        images.sent[1] = mutableMapOf(10L to "/a/receipt.jpg")
        val (session, _) = turn(call("remember_image", "title" to "receipt", "details" to "Total 450"))
        assertEquals(false, result(session)["ok"])
        assertTrue(images.saved.isEmpty())
    }

    // ---- Everyday tools ----

    @Test
    fun `call amma opens the dialer with her number, and nothing is placed`() {
        val (_, events) = turn(call("phone_call", "who" to "amma"))
        assertEquals(listOf("dial +91 98450 11111"), phone.done)
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.CALL, chip.kind)
        assertEquals("Amma · +91 98450 11111", chip.detail)
    }

    @Test
    fun `a number saved in memory is used before the contacts, which then need no permission`() {
        people["mom"] = PersonHints(names = listOf("amma"), phone = "98470 12345")
        contacts = null
        turn(call("phone_call", "who" to "mom"))
        assertEquals(listOf("dial 98470 12345"), phone.done)
    }

    @Test
    fun `my dentist is found by the name memory has for them`() {
        people["my dentist"] = PersonHints(names = listOf("Dr. Rao"))
        turn(call("phone_call", "who" to "my dentist"))
        assertEquals(listOf("dial 080 4444 5555"), phone.done)
    }

    @Test
    fun `a number said out loud is dialled as it is`() {
        turn(call("phone_call", "who" to "98450 12345"))
        assertEquals(listOf("dial 98450 12345"), phone.done)
    }

    @Test
    fun `two Priyas are asked about, no contacts access is explained, and nobody is dialled`() {
        val (several, _) = turn(call("phone_call", "who" to "Priya"))
        assertTrue(error(several).contains("Priya Sharma (…2222), Priya Nair (…3333)"))
        now = now.plusMinutes(1)
        contacts = null
        val (denied, _) = turn(call("phone_call", "who" to "Anjali"))
        assertTrue(error(denied).contains("allowed this app to read contacts"))
        assertTrue(phone.done.isEmpty())
    }

    @Test
    fun `messages open written, in the app asked for`() {
        turn(call("send_message", "to" to "Priya Sharma", "text" to "Running 10 minutes late", "app" to "whatsapp"))
        now = now.plusMinutes(1)
        turn(call("send_message", "to" to "amma", "text" to "Reached home"))
        now = now.plusMinutes(1)
        turn(call("send_message", "to" to "Priya Sharma", "text" to "Notes attached", "app" to "email"))
        assertEquals(
            listOf("WHATSAPP 98450 22222: Running 10 minutes late", "SMS +91 98450 11111: Reached home", "EMAIL priya@example.com: Notes attached"),
            phone.done,
        )
        phone.whatsapp = false
        now = now.plusMinutes(1)
        val (session, _) = turn(call("send_message", "to" to "amma", "text" to "hi", "app" to "WhatsApp"))
        assertTrue(error(session).contains("WhatsApp isn't installed"))
    }

    @Test
    fun `with live voice, a WhatsApp to anyone goes from Luna's number, so the answer comes back`() {
        lunaOn = true
        hub["filu"] = "Filu"
        val (session, events) = turn(call("send_message", "to" to "Filu", "text" to "Can you bring my BP tablets?"), reply = "I've sent your message to Filu.")
        assertEquals(listOf("Filu: Can you bring my BP tablets?"), sentByLuna)
        assertTrue("nothing to tap", phone.done.isEmpty())
        assertTrue(result(session)["sent"].toString().startsWith("on WhatsApp to Filu"))
        assertEquals("Filu: Can you bring my BP tablets?", events.filterIsInstance<ToolEvent.Memory>().single().chip.detail)

        // Not in the hub: the number from her contacts, sent from Luna's WhatsApp too.
        now = now.plusMinutes(1)
        val (contact, _) = turn(call("send_message", "to" to "Priya Sharma", "text" to "Running late", "app" to "whatsapp"))
        assertTrue(result(contact)["sent"].toString().startsWith("on WhatsApp to Priya Sharma"))
        assertEquals("Priya Sharma (98450 22222): Running late", sentByLuna.last())

        // Not on WhatsApp, or several people fit: said so, and nothing is opened or sent.
        now = now.plusMinutes(1)
        val (refused, _) = turn(call("send_message", "to" to "Priya Nair", "text" to "Hello"))
        assertTrue(error(refused), error(refused).contains("isn't on WhatsApp"))
        now = now.plusMinutes(1)
        val (several, _) = turn(call("send_message", "to" to "Priya", "text" to "Hi"))
        assertTrue(error(several), error(several).contains("Several contacts match"))

        // An SMS asked for: the phone's own app, ready to send, as before.
        now = now.plusMinutes(1)
        turn(call("send_message", "to" to "amma", "text" to "Reached home", "app" to "sms"))
        assertEquals(listOf("SMS +91 98450 11111: Reached home"), phone.done)
        assertEquals(2, sentByLuna.size)
    }

    @Test
    fun `with live voice the model is told WhatsApp goes from Luna, and SMS still opens the app`() {
        val luna = ToolCatalog.declarations(web = false, luna = true).single { "\"name\":\"send_message\"" in it }
        assertTrue(luna, "sent at once from Luna's WhatsApp to anyone" in luna)
        assertTrue(ToolCatalog.declarations(web = false).single { "\"name\":\"send_message\"" in it }.contains("the user taps send"))
        assertTrue("say it was sent only when the result says sent" in ToolCatalog.rules(web = false, luna = true))
        assertTrue("never that it was sent." in ToolCatalog.rules(web = false))
    }

    @Test
    fun `timers go to the clock app from the user's own words`() {
        val (session, events) = turn(call("set_timer", "duration" to "10 minutes", "label" to "pasta"))
        assertEquals(listOf(600 to "Pasta"), clock.timers)
        assertEquals("10 min", result(session)["timer"])
        assertEquals("10:10", result(session)["ends"])
        assertEquals("10 min · Pasta", events.filterIsInstance<ToolEvent.Memory>().single().chip.detail)
        now = now.plusMinutes(1)
        val (tooLong, _) = turn(call("set_timer", "duration" to "30 hours"))
        assertTrue(error(tooLong).contains("24 hours"))
    }

    @Test
    fun `open_app launches, searches and navigates`() {
        turn(call("open_app", "app" to "YouTube"))
        now = now.plusMinutes(1)
        turn(call("open_app", "app" to "maps", "query" to "Indiranagar"))
        assertEquals(
            listOf("open ${AppTarget.Launch(phone.apps[0])}", "open ${AppTarget.Directions("Indiranagar")}"),
            phone.done,
        )
        now = now.plusMinutes(1)
        val (missing, _) = turn(call("open_app", "app" to "Instagram"))
        assertTrue(error(missing).contains("no app called"))
    }

    @Test
    fun `phone settings switch what they can and open the panel for what they cannot`() {
        turn(call("phone_setting", "setting" to "flashlight", "value" to "on"))
        turn(call("phone_setting", "setting" to "silent mode", "value" to "on"))
        val (volume, _) = turn(call("phone_setting", "setting" to "volume", "value" to "up"))
        val (wifi, _) = turn(call("phone_setting", "setting" to "wifi", "value" to "on"))
        assertEquals(listOf("torch true", "ringer SILENT", "panel WIFI"), phone.done)
        assertEquals("60%", result(volume)["volume"])
        assertTrue(result(wifi)["note"].toString().contains("the user switches it there"))
    }

    @Test
    fun `silent without Do Not Disturb access falls back to vibrate, and Do Not Disturb asks for access`() {
        phone.silentNeedsAccess = true
        val (silent, _) = turn(call("phone_setting", "setting" to "ringer", "value" to "silent"))
        assertEquals("vibrate", result(silent)["ringer"])
        val (dnd, _) = turn(call("phone_setting", "setting" to "do_not_disturb", "value" to "on"))
        assertTrue(error(dnd).contains("allow Do Not Disturb access"))
    }

    @Test
    fun `with the app in the background nothing opens and the model is told why`() {
        phone.foreground = false
        val (session, _) = turn(call("phone_call", "who" to "amma"))
        assertTrue(error(session).contains("background"))
    }

    @Test
    fun `a repeated device call within seconds is not repeated, a minute later it is`() {
        turn(call("phone_call", "who" to "amma"))
        now = now.plusSeconds(5)
        turn(call("phone_call", "who" to "amma"))
        assertEquals(1, phone.done.size)
        now = now.plusMinutes(1)
        turn(call("phone_call", "who" to "amma"))
        assertEquals(2, phone.done.size)
    }

    // ---- Web search ----

    @Test
    fun `web search returns a short answer and three sources, and the chip opens the top one`() {
        val (session, events) = turn(call("web_lookup", "query" to "weather in Kochi today"))
        assertEquals(listOf("weather in Kochi today" to false), web.searched)
        val json = session.sentResults.first().single().json
        assertTrue("fits the tool-round budget: ${json.length} chars", json.length < 1_600)
        val sources = result(session)["sources"] as List<*>
        assertEquals(3, sources.size)
        assertEquals("site0.com", (sources[0] as Map<*, *>)["site"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals(MemoryChip.Kind.WEB, chip.kind)
        assertEquals("https://www.site0.com/page", chip.link)
    }

    @Test
    fun `news goes as news, and failures reach the model as errors`() {
        turn(call("web_lookup", "query" to "ISRO launch", "topic" to "news"))
        assertEquals(listOf("ISRO launch" to true), web.searched)
        web.failure = "The phone may be offline."
        val (session, _) = turn(call("web_lookup", "query" to "IPL score"))
        assertTrue(error(session).contains("offline"))
    }

    @Test
    fun `web search is declared, and its rule given, only when it is set up`() {
        assertFalse(ToolCatalog.declarations(web = false).any { "\"web_lookup\"" in it })
        assertTrue(ToolCatalog.declarations(web = true).any { "\"web_lookup\"" in it })
        assertFalse("web_lookup" in ToolCatalog.rules(web = false))
        assertTrue("web_lookup" in ToolCatalog.rules(web = true))
        assertFalse("{web}" in ToolCatalog.rules(web = false))
    }

    @Test
    fun `photo search is declared, and its rule given, only when it is set up`() {
        assertFalse(ToolCatalog.declarations(web = false, photos = false).any { "\"find_photos\"" in it })
        assertTrue(ToolCatalog.declarations(web = false, photos = true).any { "\"find_photos\"" in it })
        assertFalse("find_photos" in ToolCatalog.rules(web = true, photos = false))
        assertTrue("find_photos" in ToolCatalog.rules(web = false, photos = true))
        assertFalse("{photos}" in ToolCatalog.rules(web = true, photos = false))
    }

    // ---- Token soup ----

    /** Made up in the style of what the phone gave on 14 Nov 2026. */
    private val soup = listOf("\"840tsetUauthorx205619i ", "aponitimiCrap.M WMWWCOMXIai ", "/isdevincr 링Tı ", "Microsoft Sound***passou ", "setzuL6yCblue ", "and more")

    // ---- Nothing written after a lookup's result ----

    @Test
    fun `calls and messages say they are not done yet`() {
        val (dial, _) = turn(call("phone_call", "who" to "amma"))
        assertTrue(result(dial)["dialer"].toString().contains("not called yet"))
        now = now.plusMinutes(1)
        val (text, _) = turn(call("send_message", "to" to "amma", "text" to "Reached home"))
        assertTrue(result(text)["ready"].toString().contains("not sent yet"))
    }

    // ---- Screenshots ----

    private fun screenshotFile() = java.io.File.createTempFile("screen", ".png").apply { writeText("png"); deleteOnExit() }

    private fun withScreen(screen: String?, vararg calls: ToolCall): Pair<ToolTurn, List<ToolEvent>> =
        executor.play(calls.toList(), context.copy(screen = screen))

    @Test
    fun `take_screenshot saves the screen the assistant was opened over`() {
        val screen = screenshotFile()
        val (session, events) = withScreen(screen.path, call("take_screenshot"))
        assertEquals(true, result(session)["ok"])
        assertEquals(listOf("saved ${screen.name}"), phone.done)
        assertEquals(MemoryChip.Kind.SCREENSHOT, events.filterIsInstance<ToolEvent.Memory>().single().chip.kind)
    }

    @Test
    fun `with no screen to save, take_screenshot says how to get one`() {
        val (session, _) = withScreen(null, call("take_screenshot"))
        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("power button"))
        assertTrue(phone.done.isEmpty())
    }

    @Test
    fun `a screenshot goes with a message when asked for`() {
        val screen = screenshotFile()
        val (session, _) = withScreen(
            screen.path,
            call("send_message", "to" to "amma", "text" to "Rain alert on Thursday", "app" to "whatsapp", "screenshot" to true),
        )
        assertEquals("WHATSAPP +91 98450 11111: Rain alert on Thursday + ${screen.name}", phone.done.single())
        assertTrue(result(session)["ready"].toString().contains("the screenshot and the message"))
    }

    @Test
    fun `a message asked to carry a screenshot there isn't opens nothing`() {
        val (session, _) = withScreen(null, call("send_message", "to" to "amma", "app" to "whatsapp", "screenshot" to true))
        assertEquals(false, result(session)["ok"])
        assertTrue(phone.done.isEmpty())
    }

    @Test
    fun `remember_image keeps the screen when nothing was sent`() {
        val screen = screenshotFile()
        val (session, _) = withScreen(screen.path, call("remember_image", "title" to "Train ticket", "details" to "Kochi to Bengaluru"))
        assertEquals(true, result(session)["ok"])
        assertEquals(screen.path, images.saved.values.single().third.path)
    }

    // ---- Place reminders ----

    /**
     * As [turn], with what the user said (or, [locked], said on the lock screen), and [earlier]
     * what they said just before.
     */
    private fun said(text: String, vararg calls: ToolCall, locked: Boolean = false, earlier: String = ""): Pair<ToolTurn, List<ToolEvent>> =
        executor.play(calls.toList(), context.copy(userText = text, locked = locked, earlierText = earlier))

    private fun buyMilk() = said("remind me to buy milk near DMart", call("add_task", "title" to "Buy milk", "place" to "DMart"))

    @Test
    fun `when I reach office remind me to call Priya`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "office", "value" to "Infopark, Kakkanad"))
        placeSearch.places["infopark"] = listOf(FakePlaceSearch.place("Infopark", 10.0093607, 76.3615845, "Edachira", radiusM = 600))

        val (session, _) = said("when I reach office remind me to call Priya", call("add_task", "title" to "Call Priya", "place" to "office"))

        // A reminder, not a call.
        assertTrue(phone.done.isEmpty())
        val task = store.tasks.values.single()
        assertEquals("Call Priya", task.title)
        assertEquals(TaskPlace("office", listOf(PlaceSpot(10.0093607, 76.3615845, 600, "Edachira"))), task.place)
        assertEquals(true, result(session)["ok"])
        // Looked for around Kakkanad, the town the user named, and kept for next time.
        assertEquals(GeoPoint(10.0, 76.35).lat, placeSearch.asked.single().second.lat, 1e-9)
        assertTrue(appState.keys.single() == "place.office")
    }

    @Test
    fun `an office the user never described is asked about, and nothing is saved`() {
        val (session, _) = said("when I get to work remind me to call Priya", call("add_task", "title" to "Call Priya", "place" to "work"))

        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("save_fact (subject user, attribute office)"))
        assertTrue(store.tasks.isEmpty())
        assertTrue(reminders.places.isEmpty())
    }

    @Test
    fun `a place the user never said is the model's own idea and is left out`() {
        val (session, _) = said(
            "remind me to call the CA at 5pm",
            call("add_task", "title" to "Call the CA", "when" to "5pm", "place" to "office"),
        )

        val task = store.tasks.values.single()
        assertNull(task.place)
        assertEquals(at(9, 21, 17), task.dueAt)
        assertEquals(mapOf(task.id to at(9, 21, 17)), reminders.scheduled)
        assertTrue(reminders.places.isEmpty())
        assertTrue(location.asked.isEmpty() && placeSearch.asked.isEmpty())
        assertEquals("Mon 21 Sep 17:00", result(session)["due"])
    }

    /** What Gemma did with it on the phone (5 Nov 2026): the distance in the title, the town alone as the place. */
    @Test
    fun `set a reminder before 5 kilometer before I reach Munnar`() {
        val (session, events) = said(
            "set a reminder before 5 kilometer before I reach Munnar",
            call("add_task", "title" to "Reminder before 5 kilometer", "place" to "Munnar"),
        )

        val task = store.tasks.values.single()
        assertEquals("Almost at Munnar", task.title)
        val place = task.place!!
        assertEquals(PlaceDistance(5000, beforeArriving = true), place.distance)
        // Munnar's own edge (2 km out, a town of 40,000) and 5 km further.
        assertEquals(7000, place.spots.single().radiusM)
        assertEquals(mapOf(task.id to place), reminders.places)
        assertEquals("when the user is 5 km away from reaching Munnar", result(session)["rings"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals("Almost at Munnar" to "5 km before Munnar", chip.detail to chip.place)
    }

    @Test
    fun `a distance in the user's words is taken when the model leaves it out`() {
        said("remind me to buy milk when I'm within 500 m of DMart", call("add_task", "title" to "Buy milk", "place" to "DMart"))

        val place = store.tasks.values.single().place!!
        assertEquals("Buy milk", store.tasks.values.single().title)
        assertEquals(PlaceDistance(500, beforeArriving = false), place.distance)
        assertEquals(List(5) { 500 }, place.spots.map { it.radiusM })
    }

    @Test
    fun `from inside the distance it rings the next time, and says so`() {
        // The nearest DMart is 750 m from the user.
        val (session, events) = said("remind me to buy milk within 1 km of DMart", call("add_task", "title" to "Buy milk", "place" to "DMart"))

        assertEquals(true, result(session)["ok"])
        assertTrue(result(session)["note"].toString().contains("rings the next time they arrive"))
        assertEquals("You're already there: rings the next time you arrive", events.filterIsInstance<ToolEvent.Memory>().single().chip.note)
    }

    @Test
    fun `too far out is asked about, and nothing is saved`() {
        val (session, _) = said("remind me 200 km before Bengaluru", call("add_task", "title" to "Reminder", "place" to "Bengaluru"))

        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("at most 100 km"))
        assertTrue(store.tasks.isEmpty())
        assertTrue(reminders.places.isEmpty())
    }

    @Test
    fun `what's coming up says how far out`() {
        said("set a reminder before 5 kilometer before I reach Munnar", call("add_task", "title" to "Reminder before 5 kilometer", "place" to "Munnar"))
        val (session, _) = turn(call("get_upcoming"))

        assertEquals("5 km before Munnar", ((result(session)["at_places"] as List<*>).single() as Map<*, *>)["where"])
    }

    @Test
    fun `a place and a time ring at the place`() {
        said("remind me to buy milk near DMart this evening", call("add_task", "title" to "Buy milk", "when" to "this evening", "place" to "DMart"))

        val task = store.tasks.values.single()
        assertNull(task.dueAt)
        assertEquals("DMart", task.place?.name)
        assertTrue(reminders.scheduled.isEmpty())
    }

    @Test
    fun `a place reminder given a time rings at the time instead`() {
        buyMilk()
        turn(call("update_task", "task" to "milk", "when" to "tomorrow at 6pm"))

        val task = store.tasks.values.single()
        assertNull(task.place)
        assertEquals(mapOf(task.id to at(9, 22, 18)), reminders.scheduled)
        assertTrue(reminders.places.isEmpty())
    }

    @Test
    fun `undoing a place reminder takes it down`() {
        val (_, events) = buyMilk()

        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(events.filterIsInstance<ToolEvent.Memory>().single().recordId) })
        assertTrue(store.tasks.isEmpty())
        assertTrue(reminders.places.isEmpty())
    }

    @Test
    fun `what's coming up includes the place reminders`() {
        turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))
        buyMilk()
        val (session, _) = turn(call("get_upcoming"))

        assertEquals("Call the CA", (result(session)["items"] as List<*>).map { (it as Map<*, *>)["title"] }.single())
        val atPlaces = (result(session)["at_places"] as List<*>).single() as Map<*, *>
        assertEquals("Buy milk", atPlaces["title"])
        assertEquals("near DMart", atPlaces["where"])
    }

    @Test
    fun `a place it can't find is asked about, and nothing is saved`() {
        // In Kochi, where the map has no DMart.
        location.at = GeoPoint(10.0159, 76.3419)
        val (session, events) = buyMilk()

        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("Ask the user for its address or the area"))
        assertTrue(store.tasks.isEmpty())
        assertTrue(reminders.places.isEmpty())
        assertTrue(events.none { it is ToolEvent.Memory })
    }

    @Test
    fun `saved without location all the time, it says it can't ring yet`() {
        location.watch = false
        location.allowWatch = false
        val (session, events) = buyMilk()

        assertEquals(listOf("watch"), location.asked)
        assertEquals(true, result(session)["ok"])
        assertTrue(result(session)["note"].toString().contains("'All the time'"))
        assertEquals("Rings once location is allowed all the time", events.filterIsInstance<ToolEvent.Memory>().single().chip.note)
        // Kept, so it starts once location is allowed (watched again whenever the app opens).
        assertEquals(1, reminders.places.size)
    }

    @Test
    fun `Android's hundred places are shared by every place reminder`() {
        runBlocking {
            store.insertTask(TaskEntity(title = "Earlier", createdAt = 0, updatedAt = 0, place = TaskPlace("Somewhere", List(97) { PlaceSpot(0.0, 0.0, 250) })))
        }
        buyMilk()
        assertEquals(3, store.tasks.values.last().place!!.spots.size)

        val (session, _) = said("remind me to buy eggs near DMart", call("add_task", "title" to "Buy eggs", "place" to "DMart"))
        assertEquals(false, result(session)["ok"])
        assertTrue(result(session)["error"].toString().contains("at most 100"))
    }

    // ---- Alarms at a place ----

    /** What Gemma did on the phone (8 Nov 2026): the place as the alarm's time, "set alarm" as its name. */
    @Test
    fun `set alarm when I reach a place, passed to set_alarm as its time, is an alarm there`() {
        val (session, events) = said("set alarm when I reach Munnar", call("set_alarm", "label" to "set alarm", "when" to "when I reach Munnar"))

        val task = store.tasks.values.single()
        assertEquals("Alarm", task.title)
        assertNull(task.dueAt)
        assertEquals(mapOf(task.id to task.place), reminders.places)
        // Not the clock app's: it only knows times.
        assertTrue(clock.set.isEmpty())
        assertEquals(true, result(session)["ok"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals(Triple("Alarm", "", "near Munnar"), Triple(chip.label, chip.detail, chip.place))
        assertNotNull("undone like a reminder", log.records.values.single().undo)
    }

    /** The other way Gemma tried it: add_task titled "set alarm", sent on to set_alarm, which had no place. */
    @Test
    fun `an add_task titled set alarm with a place is an alarm there`() {
        val (session, events) = said("set alarm when I reach Munnar", call("add_task", "title" to "set alarm", "place" to "Munnar"))

        assertEquals("Alarm", store.tasks.values.single().title)
        assertEquals(true, result(session)["ok"])
        assertEquals("Alarm", events.filterIsInstance<ToolEvent.Memory>().single().chip.label)
    }

    @Test
    fun `an alarm before a place, with a distance or without, rings on the way`() {
        said("set an alarm 5 km before I reach Munnar", call("set_alarm", "when" to "5 km before I reach Munnar"))
        val near = store.tasks.values.single()
        assertEquals("Almost at Munnar", near.title)
        assertEquals(PlaceDistance(5000, beforeArriving = true), near.place!!.distance)

        said("set an alarm before I reach Bangalore", call("set_alarm", "when" to "before I reach Bangalore"))
        val bengaluru = store.tasks.values.last()
        assertEquals("Alarm", bengaluru.title)
        assertNull(bengaluru.place!!.distance)
    }

    @Test
    fun `an alarm at a place keeps a name the user gave it`() {
        said("set an alarm for medicines when I reach Munnar", call("set_alarm", "when" to "when I reach Munnar", "label" to "medicines"))
        assertEquals("Medicines", store.tasks.values.single().title)
    }

    // ---- A reminder's name ----

    /** "remind me when I reach avilora" was saved as "Buy chicken", a reminder from days before (8 Nov 2026). */
    @Test
    fun `a reminder the user gave no name doesn't take an open reminder's`() {
        buyMilk()
        val (_, events) = said("remind me when I reach Munnar", call("add_task", "title" to "remind me to buy milk", "place" to "Munnar"))

        assertEquals("Reminder", store.tasks.values.last().title)
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals("Reminder" to "", chip.label to chip.detail)

        said("remind me at 6pm", call("add_task", "title" to "Buy milk", "when" to "6pm"))
        val timed = store.tasks.values.last()
        assertEquals("Reminder" to at(9, 21, 18), timed.title to timed.dueAt)
    }

    @Test
    fun `an open reminder's name is kept when the user said it, just before or now, or pointed back to it`() {
        buyMilk()
        // Answering "where?", asked about it.
        said("when I reach Munnar", call("add_task", "title" to "Buy milk", "place" to "Munnar"), earlier = "remind me to buy milk")
        assertEquals("Buy milk", store.tasks.values.last().title)

        said("remind me to buy milk at 6pm too", call("add_task", "title" to "Buy milk", "when" to "6pm"))
        assertEquals("Buy milk", store.tasks.values.last().title)

        said("remind me about that again at 7pm", call("add_task", "title" to "Buy milk", "when" to "7pm"))
        assertEquals("Buy milk", store.tasks.values.last().title)
        assertEquals(4, store.tasks.values.count { it.title == "Buy milk" })
    }

    /** A quote left unescaped in a description once made the app crash as it started. */
    @Test
    fun `every declaration is valid JSON with a name and a description`() {
        for (json in ToolCatalog.declarations(web = true, photos = true)) {
            val parsed = com.google.gson.JsonParser.parseString(json).asJsonObject
            assertTrue(json, parsed.get("name").asString.isNotBlank())
            assertTrue(json, parsed.get("description").asString.isNotBlank())
        }
    }

    // ---- What the executor does with a call ----

    @Test
    fun `remind me to call the CA tomorrow at 11`() {
        val (session, events) = turn(call("add_task", "title" to "Call the CA", "when" to "tomorrow at 11"))

        val task = store.tasks.values.single()
        assertEquals("Call the CA", task.title)
        assertEquals(at(9, 22, 11), task.dueAt)
        assertEquals(mapOf(task.id to at(9, 22, 11)), reminders.scheduled)

        assertEquals(true, result(session)["ok"])
        assertEquals("Tue 22 Sep 11:00", result(session)["due"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals(MemoryChip(MemoryChip.Kind.TASK, "Reminder", "Call the CA", at(9, 22, 11), editable = true), chip)
        assertEquals("Done.", events.filterIsInstance<ToolEvent.Text>().single().delta)
    }

    @Test
    fun `my dentist is Dr Rao`() {
        val (_, events) = turn(call("save_fact", "subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao", "core" to false))

        val fact = store.facts.values.single()
        assertEquals(Triple("user", "dentist", "Dr. Rao"), Triple(fact.subject, fact.attribute, fact.value))
        assertFalse(fact.core)
        assertEquals(FactCategory.PEOPLE, fact.category)
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals("Saved", chip.label)
        assertEquals("Dentist: Dr. Rao", chip.detail)
    }

    @Test
    fun `reply in short answers becomes core`() {
        turn(call("save_fact", "subject" to "user", "attribute" to "pref.reply_style", "value" to "short answers", "core" to true))

        val fact = store.facts.values.single()
        assertEquals("pref.reply_style", fact.attribute)
        assertTrue(fact.core)
        assertEquals(FactCategory.PREFERENCE, fact.category)
        val core = CoreMemoryRenderer(HeuristicTokenEstimator).render(runBlocking { store.coreFacts() }, 1_200)
        assertTrue(core.text, "Preferences: reply style: short answers" in core.text)
    }

    @Test
    fun `the person messaged is the one the user spelled, not the model`() {
        contacts = contacts.orEmpty() + listOf(Contact("Uma", listOf(ContactPhone("98450 44444"))), Contact("Umma", listOf(ContactPhone("98450 55555"))))
        executor.play(listOf(call("send_message", "to" to "Uma", "text" to "Reached", "app" to "whatsapp")), ToolContext(1, 13, "Send to umma and turn on flashlight"))
        assertTrue(phone.done.single(), phone.done.single().contains("98450 55555"))
    }

    /**
     * The model heard the recording; the words are the recogniser's, which wrote "Umma" as "Uma"
     * and "two thousand four hundred and fifty" as "200450". Its own reading is kept.
     */
    @Test
    fun `a voice message the model heard is not corrected to the recogniser's words`() {
        contacts = contacts.orEmpty() + listOf(Contact("Uma", listOf(ContactPhone("98450 44444"))), Contact("Umma", listOf(ContactPhone("98450 55555"))))
        executor.play(listOf(call("send_message", "to" to "Umma", "text" to "Reached", "app" to "whatsapp")), ToolContext(1, 13, "Send to Uma reached", heard = true))
        assertTrue(phone.done.single(), phone.done.single().contains("98450 55555"))

        val sum = listOf(call("calculate", "expression" to "18% of 2450"))
        val (heard, _) = executor.play(sum, ToolContext(1, 14, "what is 18% of 200450", heard = true))
        assertEquals("18% of 2450", result(heard)["expression"])
        // Typed, the user's own digits win, as before.
        val (typed, _) = executor.play(sum, ToolContext(1, 15, "what is 18% of 200450"))
        assertTrue(result(typed)["expression"].toString().contains("200450"))
    }

    @Test
    fun `calculate gives Luna's model the sum worked out`() {
        val (session, events) = turn(call("calculate", "expression" to "18% of 2450"))
        assertEquals(true, result(session)["ok"])
        assertEquals("441", result(session)["result"].toString().removeSuffix(".0"))
        assertTrue("no chip for arithmetic", events.none { it is ToolEvent.Memory })
    }

    /** Digits dropped in copying: the user's own are used. */
    @Test
    fun `numbers the model mis-copied are taken from the user's own words`() {
        val (sum, _) = executor.play(listOf(call("calculate", "expression" to "5+5+2")), ToolContext(chatId = 1, userMessageId = 10, userText = "what is 25+25"))
        assertEquals("50", result(sum)["result"].toString().removeSuffix(".0"))

        executor.play(listOf(call("set_alarm", "when" to "6:5")), ToolContext(1, 11, "set an alarm for 6:35"))
        // 6:35 said at 10:00 is this evening's; what matters is the 35 the model dropped.
        assertEquals(FakeSystemAlarms.Set(18, 35, emptySet(), null), clock.set.single())

        executor.play(listOf(call("phone_call", "who" to "94501235")), ToolContext(1, 12, "call 98450 12345"), "Opening.")
        assertEquals(listOf("dial 98450 12345"), phone.done)
    }

    @Test
    fun `remind me to buy milk near DMart`() {
        val (session, events) = buyMilk()

        val task = store.tasks.values.single()
        assertEquals("Buy milk", task.title)
        assertNull(task.dueAt)
        val place = task.place!!
        assertEquals("DMart", place.name)
        assertEquals("Hoodi Main Road", place.spots.first().label)
        assertEquals(5, place.spots.size)
        // Watched, not timed.
        assertEquals(mapOf(task.id to place), reminders.places)
        assertTrue(reminders.scheduled.isEmpty())

        assertEquals(true, result(session)["ok"])
        assertEquals("when the user gets near DMart (any of the 5 nearest)", result(session)["rings"])
        assertEquals("750 m", result(session)["nearest"])
        assertNull(result(session)["note"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals(MemoryChip(MemoryChip.Kind.TASK, "Reminder", "Buy milk", note = "Any of the 5 nearest, closest 750 m", place = "near DMart"), chip)
    }

    @Test
    fun `a voice message's distance is the model's, not the recogniser's`() {
        val heard = context.copy(userText = "remind me 50 km before Munnar", heard = true)
        executor.play(listOf(call("add_task", "title" to "Reminder 5 km before", "place" to "Munnar")), heard)

        assertEquals(PlaceDistance(5000, beforeArriving = true), store.tasks.values.single().place!!.distance)
    }

    @Test
    fun `cancelling a place reminder stops watching it`() {
        buyMilk()
        val (session, events) = turn(call("update_task", "task" to "milk", "status" to "cancelled"))

        assertEquals(TaskStatus.CANCELLED, store.tasks.values.single().status)
        assertTrue(reminders.places.isEmpty())
        assertEquals(true, result(session)["ok"])
        val chip = events.filterIsInstance<ToolEvent.Memory>().single().chip
        assertEquals("Cancelled", chip.label)
        assertNull(chip.place)
    }
}
