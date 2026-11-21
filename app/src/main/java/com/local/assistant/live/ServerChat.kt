package com.local.assistant.live

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.llm.ToolCall
import com.local.assistant.media.AttachmentStore
import com.local.assistant.memory.retrieval.QueryText
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The chat's replies, written by Luna's server (`server/src/chat.ts`): the same brain the Agora
 * calls use, Gemini with the family hub's tools, given the phone's memory and tools as a call is.
 *
 * One request per message, answered as a stream: the words as they're written, and each call to
 * one of the phone's own tools (a reminder, the flashlight, a photo search), which runs here
 * through the same [ToolExecutor] a call's tools do, with its chip and Undo, and is answered with
 * one small POST. Nothing is kept on the server once the reply is over.
 */
class ServerChat(
    /** The server's base URL; blank when none is set. */
    private val serverUrl: () -> String,
    private val appKey: () -> String,
    private val contexts: LiveContextBuilder,
    private val execute: suspend (ToolCall, ToolContext) -> ToolExecutor.Outcome,
    /** The chat's stored messages, oldest first. */
    private val history: suspend (chatId: Long) -> List<MessageEntity>,
    /** Who the server knows the user as; blank for its own elder. */
    private val userId: () -> String,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // The model may think a while before its first word; a phone tool may wait on the user.
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) {

    sealed interface Event {
        data class Text(val delta: String) : Event

        /** One of the phone's tools ran; [recordId] is its TOOL row, which Undo takes back. */
        data class Memory(val recordId: Long, val chip: MemoryChip) : Event
    }

    /**
     * The reply to the stored message [userMessageId] in [chatId]. [image]: a photo sent with it;
     * [screen]: the screenshot the assistant panel was opened over, sent only when the message is
     * about the screen. Fails with [LiveError], fit to show, when the server can't be reached.
     */
    fun reply(
        chatId: Long,
        userMessageId: Long,
        userText: String,
        locked: Boolean,
        heard: Boolean,
        image: String? = null,
        screen: String? = null,
    ): Flow<Event> = callbackFlow {
        val base = serverUrl().trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: throw LiveError(NO_SERVER)
        val shownScreen = screen?.takeIf { !locked && File(it).isFile && QueryText.asksAboutScreen(userText) }
        val body = withContext(Dispatchers.IO) {
            gson.toJson(
                linkedMapOf(
                    "user_id" to userId().ifBlank { null },
                    "context" to contexts.build(LiveContext.PURPOSE_CHAT, null, locked, chatId),
                    "messages" to messagesFor(history(chatId), userMessageId),
                    "image" to (image ?: shownScreen)?.let(::photo),
                ),
            )
        }
        val call = http.newCall(
            Request.Builder().url("$base/chat/turn").header(LiveApi.KEY_HEADER, appKey()).post(body.toRequestBody(JSON)).build(),
        )
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val text = response.body?.string().orEmpty()
                        throw LiveError(
                            when (response.code) {
                                401, 403 -> "Luna's server refused the app key."
                                else -> LiveApi.errorIn(text) ?: "Luna's server answered ${response.code}."
                            },
                        )
                    }
                    val source = response.body?.source() ?: throw LiveError(UNREADABLE)
                    var session: String? = null
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith(DATA)) continue
                        val event = runCatching { JsonParser.parseString(line.removePrefix(DATA)).asJsonObject }.getOrNull() ?: continue
                        when (event.text("type")) {
                            "session" -> session = event.text("session_id")
                            "text" -> event.text("text", blankOk = true)?.let { send(Event.Text(it)) }
                            "tool_call" -> {
                                val (answer, memory) = runTool(event, chatId, userMessageId, userText, locked, heard, screen)
                                memory?.let { send(it) }
                                postResult(base, session, answer)
                            }
                            "done" -> break
                        }
                    }
                }
                channel.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveError) {
                channel.close(e)
            } catch (e: IOException) {
                channel.close(LiveError(if (call.isCanceled()) STOPPED else LOST))
            }
        }
        // Stopped by the user: the server sees the request go and stops the model.
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    /** A photo's title and what's written on it, for a saved image; throws [LiveError] when the server can't. */
    data class Described(val title: String = "", val details: String = "")

    suspend fun describe(path: String): Described = withContext(Dispatchers.IO) {
        val base = serverUrl().trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: throw LiveError(NO_SERVER)
        val image = photo(path) ?: throw LiveError("Couldn't read that image.")
        val request = Request.Builder().url("$base/chat/describe").header(LiveApi.KEY_HEADER, appKey())
            .post(gson.toJson(mapOf("image" to image)).toRequestBody(JSON)).build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw LiveError(LOST)
        }
        if (code !in 200..299) throw LiveError(LiveApi.errorIn(text) ?: "Luna's server answered $code.")
        runCatching { gson.fromJson(text, Described::class.java) }.getOrNull()?.takeIf { it.title.isNotBlank() } ?: throw LiveError(UNREADABLE)
    }

    /** One of the phone's tools, as the server asked for it: the answer to POST, and its chip. */
    private suspend fun runTool(
        event: JsonObject,
        chatId: Long,
        userMessageId: Long,
        userText: String,
        locked: Boolean,
        heard: Boolean,
        screen: String?,
    ): Pair<Map<String, Any?>, Event.Memory?> {
        val callId = event.text("call_id").orEmpty()
        val name = event.text("name") ?: return answer(callId, failure("The call named no tool."), null) to null
        if (locked && name !in ToolCatalog.LOCKED) {
            return answer(callId, failure("The phone is locked. Ask the user to unlock it first, then ask again."), null) to null
        }
        val arguments = event.get("args")?.takeIf { it.isJsonObject }?.let { gson.fromJson<Map<String, Any?>>(it, mapType) }.orEmpty()
        val outcome = try {
            // On the main thread, as a typed turn's tools always ran: some open a dialog or a screen.
            withContext(Dispatchers.Main) {
                execute(ToolCall(name, arguments), ToolContext(chatId, userMessageId, userText, locked, screen?.takeIf { !locked && File(it).isFile }, heard = heard))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed on the phone", e)
            return answer(callId, failure("$name failed on the phone."), null) to null
        }
        val memory = outcome.recordId?.let { id -> outcome.chip?.let { Event.Memory(id, it) } }
        // A photo the tool found to answer from ("what's my PAN number?"): the server's model reads it.
        return answer(callId, withReply(outcome), outcome.chip, outcome.image) to memory
    }

    /** The tool's result, with the words it wrote for the reply, when it wrote them, for the model to say. */
    private fun withReply(outcome: ToolExecutor.Outcome): String {
        val reply = outcome.reply ?: return outcome.result
        val json = runCatching { JsonParser.parseString(outcome.result).asJsonObject }.getOrNull() ?: return outcome.result
        json.addProperty("reply", reply)
        json.addProperty("note", "Tell the user this, in your own words if you like: it's the answer.")
        return json.toString()
    }

    private suspend fun answer(callId: String, result: String, chip: MemoryChip?, image: String? = null): Map<String, Any?> = linkedMapOf(
        "call_id" to callId,
        "result" to result,
        "chip" to chip?.let { linkedMapOf("title" to it.label, "detail" to LiveToolBridge.chipDetail(it, zone())) },
        "image" to image?.let { withContext(Dispatchers.IO) { ServerPhoto.of(it, ServerPhoto.READ_PIXELS) } },
    )

    private fun postResult(base: String, session: String?, answer: Map<String, Any?>) {
        val body = gson.toJson(answer + ("session_id" to session))
        try {
            http.newCall(Request.Builder().url("$base/chat/tool_result").header(LiveApi.KEY_HEADER, appKey()).post(body.toRequestBody(JSON)).build())
                .execute().close()
        } catch (e: IOException) {
            // The reply is waiting on it and will say the phone didn't answer.
            Log.w(TAG, "Couldn't send a tool's result", e)
        }
    }

    /** The chat up to [upTo], as the server reads it. */
    private fun messagesFor(messages: List<MessageEntity>, upTo: Long): List<Map<String, String>> =
        messages.filter { it.id <= upTo && (it.role == Role.USER || it.role == Role.ASSISTANT) }
            .takeLast(MAX_MESSAGES)
            .mapNotNull { message ->
                val content = if (message.role == Role.USER) userWords(message) else message.text.trim()
                content.takeIf { it.isNotEmpty() }?.let { mapOf("role" to if (message.role == Role.USER) "user" else "assistant", "content" to it) }
            }

    /** What the user sent, its attachment named: the photo itself goes only with the latest message. */
    private fun userWords(message: MessageEntity): String {
        val text = message.text.trim()
        val attached = when (message.attachmentKind) {
            AttachmentKind.IMAGE -> "[a photo]"
            AttachmentKind.DOCUMENT -> message.attachmentPath?.let { "[attached the document \"${AttachmentStore.documentName(it)}\": read_file can read it]" }
            AttachmentKind.AUDIO -> if (text.isEmpty()) "[a voice message the phone couldn't make out]" else null
            null -> null
        }
        return listOfNotNull(attached, text.takeIf { it.isNotEmpty() }).joinToString(" ")
    }

    private fun photo(path: String): Map<String, String>? = ServerPhoto.of(path, PHOTO_PIXELS)

    private fun failure(error: String): String = gson.toJson(linkedMapOf("ok" to false, "error" to error))

    private fun JsonObject.text(key: String, blankOk: Boolean = false): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf { blankOk || it.isNotBlank() }

    companion object {
        private const val TAG = "ServerChat"
        private const val DATA = "data: "
        private const val MAX_MESSAGES = 24
        private const val PHOTO_PIXELS = 1024

        const val NO_SERVER = "Luna's server isn't set up: add it in Settings → Live voice and family."
        const val LOST = "Lost the connection to Luna's server. Check the internet and try again."
        const val STOPPED = "Stopped."
        const val UNREADABLE = "Luna's server sent back something unreadable."

        private val JSON = "application/json".toMediaType()
        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    }
}
