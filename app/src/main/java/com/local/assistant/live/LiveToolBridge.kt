package com.local.assistant.live

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phone's end of the server's tool bridge (WebSocket `/bridge?session=…`). The cloud model's
 * calls to the phone's own tools — reminders, events, lists, alarms, health, photos — come here as
 * `tool_call` and run through the same [ToolExecutor] the on-device model uses, so they are
 * checked, deduplicated, kept with Undo and shown the same way; the result goes back as
 * `tool_result`, which the server waits up to 8 s for. The server also pushes what it did itself
 * (`action`: "asked Rahul") and what the family answered (`hub_update`), for the call screen.
 *
 * [handle] is the protocol, kept apart from the socket so it can be tested on its own.
 */
class LiveToolBridge(
    private val execute: suspend (ToolCall, ToolContext) -> ToolExecutor.Outcome,
    private val transcript: LiveTranscriptLog,
    /** On the lock screen: only [ToolCatalog.LOCKED] tools run, as on the phone's own model. */
    private val locked: Boolean,
    /** Her lists as they are now, sent back with a list tool's result so the call's prompt keeps up; null on the lock screen. */
    private val lists: (suspend () -> List<LiveContext.ListContent>)? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** A photo a tool found to answer from, as the server takes it ([ServerPhoto]); null if it can't be read. */
    private val photo: (String) -> Map<String, String>? = { ServerPhoto.of(it, ServerPhoto.READ_PIXELS) },
) {

    /** What the call screen shows as cards. */
    sealed interface Event {
        /** A phone tool ran; [recordId] is its TOOL row, which Undo takes back. */
        data class Chip(val recordId: Long, val chip: MemoryChip) : Event

        /** The server acted for the user: a task sent to Rahul, an order to the shop. */
        data class Action(val tool: String, val ok: Boolean, val title: String, val detail: String?) : Event

        /** Family news during the call: "Rahul will take you at 4:30". */
        data class HubUpdate(val kind: String, val text: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** One message from the server, as sent; the reply to send back, if it wants one. */
    suspend fun handle(message: String): String? {
        val json = runCatching { JsonParser.parseString(message).asJsonObject }.getOrNull() ?: return null
        when (json.text("type")) {
            TOOL_CALL -> return toolCall(json)
            ACTION -> _events.emit(
                Event.Action(
                    tool = json.text("tool").orEmpty(),
                    ok = json.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean != false,
                    title = json.text("title") ?: json.text("tool").orEmpty(),
                    detail = json.text("detail"),
                ),
            )
            HUB_UPDATE -> json.text("text")?.let { _events.emit(Event.HubUpdate(json.text("kind").orEmpty(), it)) }
        }
        return null
    }

    private suspend fun toolCall(json: JsonObject): String? {
        val callId = json.text("call_id") ?: return null
        val name = json.text("name") ?: return reply(callId, failure("The call named no tool."), null)
        // Never given these (LiveContext.deviceTools), but the server's model may still try one.
        if (name in LiveContext.EXCLUDED) return reply(callId, failure("$name isn't available during a call."), null)
        if (locked && name !in ToolCatalog.LOCKED) {
            return reply(callId, failure("The phone is locked. Ask the user to unlock it first, then ask again."), null)
        }
        val arguments = json.get("args")?.takeIf { it.isJsonObject }?.let { gson.fromJson<Map<String, Any?>>(it, mapType) }.orEmpty()
        val turnId = json.get("turn_id")?.takeIf { it.isJsonPrimitive }?.asString
        val userText = json.text("user_text").orEmpty()
        val outcome = try {
            val messageId = transcript.ensureUserMessage(turnId, userText)
            val context = ToolContext(
                chatId = transcript.chat(),
                userMessageId = messageId,
                userText = userText,
                locked = locked,
                // Agora's recogniser wrote these words, and the cloud model heard them as written:
                // the numbers in its calls are not to be "corrected" back from them.
                heard = true,
            )
            execute(ToolCall(name, arguments), context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed on the phone", e)
            return reply(callId, failure("$name failed on the phone."), null)
        }
        val recordId = outcome.recordId
        if (outcome.chip != null && recordId != null) _events.emit(Event.Chip(recordId, outcome.chip))
        val fresh = lists?.takeIf { name == ToolCatalog.EDIT_NOTE }?.let { read ->
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading the lists again failed", e)
                null
            }
        }
        // A photo the tool found to answer from ("what's my PAN number?"): the server's model reads it.
        val image = outcome.image?.let { path -> withContext(Dispatchers.IO) { photo(path) } }
        return reply(callId, outcome.result, outcome.chip, fresh, image)
    }

    private fun reply(
        callId: String,
        result: String,
        chip: MemoryChip?,
        lists: List<LiveContext.ListContent>? = null,
        image: Map<String, String>? = null,
    ): String = gson.toJson(
        linkedMapOf<String, Any?>(
            "type" to TOOL_RESULT,
            "call_id" to callId,
            "result" to result,
            "chip" to chip?.let { linkedMapOf("title" to it.label, "detail" to chipDetail(it, zone())) },
        ).apply {
            if (lists != null) put("lists", lists)
            if (image != null) put("image", image)
        },
    )

    /**
     * Keeps the socket's messages going through [handle], each in its own coroutine on [scope]:
     * a tool waiting on a permission dialog mustn't hold up the next. [onClosed] hears when the
     * socket goes, whether the server closed it or the network dropped it.
     */
    fun listener(scope: CoroutineScope, onOpen: () -> Unit = {}, onClosed: (Throwable?) -> Unit = {}): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handle(text)?.let(webSocket::send) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed(t)
    }

    private fun failure(error: String): String = gson.toJson(linkedMapOf("ok" to false, "error" to error))

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf { it.isNotBlank() }

    companion object {
        const val TOOL_CALL = "tool_call"
        const val TOOL_RESULT = "tool_result"
        const val ACTION = "action"
        const val HUB_UPDATE = "hub_update"

        private const val TAG = "LiveToolBridge"
        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)

        /** The chip's words as the call screen shows them, its time written out: the server keeps them for its prompt. */
        fun chipDetail(chip: MemoryChip, zone: ZoneId): String = listOfNotNull(
            chip.detail.takeIf { it.isNotBlank() },
            chip.at?.let { at -> (if (chip.allDay) DAY else DAY_TIME).format(Instant.ofEpochMilli(at).atZone(zone)) },
            chip.place?.let { "near $it" },
        ).joinToString(" · ")
    }
}
