package com.local.assistant.live

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.tools.ToolCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What a live call starts with, sent once in `POST /session/start`: the server writes the cloud
 * model's prompt from it, with the family hub's news added. The memory stays on the phone and is
 * shared only per call, only this much of it, and none of it on the lock screen.
 */
data class LiveContext(
    @SerializedName("user_name") val userName: String?,
    val locale: String = LOCALE,
    @SerializedName("time_zone") val timeZone: String,
    /** The phone's time when the call began, ISO with its offset: the server never guesses the zone. */
    val now: String,
    /** "user" when she called Luna, "checkin" when Luna rang her about a dose, "chat" for a typed message ([ServerChat]). */
    val purpose: String,
    @SerializedName("dose_id") val doseId: String? = null,
    /** On the lock screen: only [ToolCatalog.LOCKED] tools, and no memory, lists or health. */
    val locked: Boolean,
    /** The prompt prefix the phone's own model would have: core facts, last summary, agenda, notes index. */
    val memory: String,
    /** Pinned lists in full, so "what's on my shopping list?" needs no tool call. */
    val lists: List<ListContent>,
    /** Today's steps and last night's sleep, when the user has allowed them; null otherwise. */
    val health: String?,
    /** How to use the phone's tools: the on-device model's own rules. */
    @SerializedName("tool_rules") val toolRules: String,
    /** The phone's tools as OpenAPI function declarations; the server offers them beside its own. */
    @SerializedName("device_tools") val deviceTools: List<Map<String, Any?>>,
    /** The Murf voice chosen in Settings; null for the server's own. */
    @SerializedName("murf_voice") val murfVoice: String? = null,
) {
    data class ListContent(val title: String, val items: List<Item>) {
        data class Item(val text: String, val done: Boolean)
    }

    companion object {
        const val LOCALE = "en-IN"
        const val PURPOSE_USER = "user"
        const val PURPOSE_CHECKIN = "checkin"
        const val PURPOSE_CHAT = "chat"

        /**
         * Tools a call can't use: remember_image needs a picture sent in the chat, and a screenshot
         * of the call screen is no use to anyone.
         */
        val EXCLUDED = setOf(ToolCatalog.REMEMBER_IMAGE, ToolCatalog.TAKE_SCREENSHOT)

        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

        /**
         * [declarations] (ToolCatalog's JSON) as the server takes them, without [EXCLUDED], and
         * with only [ToolCatalog.LOCKED] when [locked]: the same line the phone's own model keeps.
         */
        fun deviceTools(declarations: List<String>, locked: Boolean, excluded: Set<String> = EXCLUDED): List<Map<String, Any?>> =
            declarations.mapNotNull { json -> runCatching { gson.fromJson<Map<String, Any?>>(json, mapType) }.getOrNull() }
                .filter { declaration ->
                    val name = declaration["name"] as? String ?: return@filter false
                    name !in excluded && (!locked || name in ToolCatalog.LOCKED)
                }

        /** [rules] without the lines about [excluded] tools, which the call doesn't have. */
        fun toolRules(rules: String, excluded: Set<String> = EXCLUDED): String =
            rules.lines().filterNot { line -> excluded.any { it in line } }.joinToString("\n")

        /** The pinned lists, in full: what's in a list is otherwise only read when asked about. */
        fun pinnedLists(notes: List<UserNoteEntity>): List<ListContent> =
            notes.filter { it.pinned && it.kind == NoteKind.LIST }
                .map { note -> ListContent(note.title, note.items.map { ListContent.Item(it.text, it.done) }) }
    }
}

/**
 * Builds a [LiveContext] from what the phone has. Each source is a function, so tests give their
 * own; the app's are wired in `AppContainer`.
 */
class LiveContextBuilder(
    /** [com.local.assistant.memory.prompt.MemoryContext.liveContext]. */
    private val memory: suspend (chatId: Long?) -> String,
    /** ToolCatalog.declarations, for what is set up on this phone now. */
    private val declarations: () -> List<String>,
    /** ToolCatalog.rules, likewise. */
    private val rules: () -> String,
    private val notes: suspend () -> List<UserNoteEntity>,
    /** [com.local.assistant.health.HealthTools.briefing]. */
    private val health: suspend () -> String?,
    private val userName: suspend () -> String?,
    private val murfVoice: () -> String? = { null },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * [chatId]: the chat the call was started from, whose summary the memory then carries; null
     * for none. A typed chat ([LiveContext.PURPOSE_CHAT]) has every tool: a photo to remember, a
     * screenshot to take.
     */
    suspend fun build(purpose: String, doseId: String?, locked: Boolean, chatId: Long? = null): LiveContext {
        val zone = zone()
        val excluded = if (purpose == LiveContext.PURPOSE_CHAT) emptySet() else LiveContext.EXCLUDED
        val now = Instant.ofEpochMilli(clock()).atZone(zone)
        return LiveContext(
            userName = if (locked) null else userName(),
            timeZone = zone.id,
            now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.withNano(0)),
            purpose = purpose,
            doseId = doseId,
            locked = locked,
            memory = if (locked) "" else memory(chatId),
            lists = if (locked) emptyList() else LiveContext.pinnedLists(notes()),
            health = if (locked) null else health(),
            toolRules = LiveContext.toolRules(rules(), excluded),
            deviceTools = LiveContext.deviceTools(declarations(), locked, excluded),
            murfVoice = murfVoice()?.takeIf { it.isNotBlank() },
        )
    }
}
