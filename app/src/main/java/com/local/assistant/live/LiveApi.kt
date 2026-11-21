package com.local.assistant.live

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Luna server a live voice call goes through (`server/` in this repository): it hands out the
 * Agora tokens, starts and stops the Conversational AI agent, runs the cloud model's tool loop and
 * keeps the family hub. Every request carries the shared app key. Nothing is sent until a call
 * starts, and with no server set ([configured] false) every live entry point stays hidden.
 */
class LiveApi(
    /** The server's base URL, `https://luna.example.com`; blank when none is set. */
    private val serverUrl: () -> String,
    private val appKey: () -> String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // /session/start waits for Agora's join, which can take several seconds.
        .readTimeout(30, TimeUnit.SECONDS)
        // The bridge stays open for the whole call; a ping keeps mobile networks from dropping it.
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {

    val configured: Boolean
        get() = serverUrl().isNotBlank()

    /** Who's in the family and Amma's medicines: the check-in calls are set from [Family.doses]. */
    data class Family(
        val elder: Member? = null,
        val members: List<Member> = emptyList(),
        val doses: List<Dose> = emptyList(),
    )

    data class Member(
        val id: String = "",
        val name: String = "",
        val relation: String? = null,
        val role: String? = null,
        /** Their WhatsApp number, as saved on the server; blank when Luna can't message them. */
        val phone: String? = null,
        val reachable: Boolean = false,
        /** Added from this app, not the server's seed: they can be removed here. */
        val added: Boolean = false,
        /** After a save: whether WhatsApp knows the number; null when the server couldn't check. */
        @SerializedName("on_whatsapp") val onWhatsApp: Boolean? = null,
        /** After a save: Luna said hello to them on WhatsApp. */
        val introduced: Boolean = false,
    )

    /** A medicine and when it is due, "09:00" in the elder's local time. */
    data class Dose(val id: String = "", val name: String = "", val detail: String? = null, val time: String = "")

    /** One call's channel and token. The token covers RTC and RTM; RTM's user id is [uid]'s digits. */
    data class Session(
        @SerializedName("session_id") val sessionId: String = "",
        @SerializedName("app_id") val appId: String = "",
        val channel: String = "",
        val uid: Int = 0,
        val token: String = "",
        @SerializedName("agent_uid") val agentUid: Int = 0,
        @SerializedName("bridge_url") val bridgeUrl: String = "",
    )

    data class Started(@SerializedName("agent_id") val agentId: String? = null, val status: String? = null)

    /** Something the family answered on WhatsApp, written to be said to her. [told]: she heard it in a call. */
    data class Answer(val id: Long = 0, val kind: String = "", val text: String = "", val at: Long = 0, val told: Boolean = false)

    /** The answers after a point, the newest event's id to carry on from, and whether one may come soon. */
    data class Answers(val answers: List<Answer> = emptyList(), val last: Long = 0, val waiting: Boolean = false)

    /** What Luna says as she opens the app, about the updates up to [upTo]; null [text] when there were none. */
    data class Briefing(val text: String? = null, @SerializedName("up_to") val upTo: Long? = null)

    /** Murf's MP3 of something, base64; null when Murf couldn't. */
    data class Audio(val mime: String = "", val data: String = "")
    private data class Speech(val audio: Audio? = null)

    /** Sent through Luna's own WhatsApp: [sentTo] names who got it ("Filu", "the family group"). */
    data class Sent(val ok: Boolean = false, @SerializedName("sent_to") val sentTo: String? = null)

    suspend fun family(): Family = parse(call("GET", "/family"), Family::class.java)

    /**
     * Saves [memberId]'s WhatsApp number on the server (picked from the contacts); blank clears it.
     * With [introduce], Luna says hello to a new number on WhatsApp.
     */
    suspend fun setPhone(memberId: String, phone: String, introduce: Boolean = false): Member =
        parse(call("PUT", "/family/members/${encode(memberId)}/phone", gson.toJson(mapOf("phone" to phone, "introduce" to introduce))), Member::class.java)

    /** Adds someone picked from the contacts; [role] is family, caregiver or provider (a shop or service). */
    suspend fun addMember(name: String, relation: String, role: String, phone: String, introduce: Boolean): Member =
        parse(
            call("POST", "/family/members", gson.toJson(mapOf("name" to name, "relation" to relation, "role" to role, "phone" to phone, "introduce" to introduce))),
            Member::class.java,
        )

    /** Removes someone added here; the server's seed people stay. */
    suspend fun removeMember(memberId: String) {
        call("DELETE", "/family/members/${encode(memberId)}")
    }

    private fun encode(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    suspend fun token(userId: String): Session =
        parse(call("POST", "/session/token", gson.toJson(mapOf("user_id" to userId))), Session::class.java).also {
            if (it.sessionId.isBlank() || it.appId.isBlank() || it.token.isBlank()) throw LiveError("The server sent back an incomplete call token.")
        }

    /** Starts the agent: only once the phone is listening on RTM, or its first words are lost. */
    suspend fun start(sessionId: String, context: LiveContext): Started =
        parse(call("POST", "/session/start", gson.toJson(mapOf("session_id" to sessionId, "context" to context))), Started::class.java)

    suspend fun stop(sessionId: String) {
        call("POST", "/session/stop", gson.toJson(mapOf("session_id" to sessionId)))
    }

    /** The family's answers after event [after], or on a first visit (null) the last few of the past 12 hours. */
    suspend fun answers(after: Long?): Answers =
        parse(call("GET", "/family/answers" + (after?.let { "?after=$it" } ?: "")), Answers::class.java)

    /** The updates after [after] up to [upTo] that she didn't hear in a call, as one short message from Luna; [greet] as she comes back. */
    suspend fun briefing(after: Long?, upTo: Long, greet: Boolean): Briefing =
        parse(
            call("POST", "/family/briefing", gson.toJson(mapOf("after" to (after ?: -1L), "up_to" to upTo, "greet" to greet, "audio" to false)), client = quick),
            Briefing::class.java,
        )

    /** [text] in Murf's voice, the one her calls use, as MP3 bytes; null when Murf couldn't. */
    suspend fun speech(text: String): ByteArray? =
        parse(call("POST", "/family/speech", gson.toJson(mapOf("text" to text)), client = speaking), Speech::class.java)
            .audio?.data?.takeIf { it.isNotEmpty() }?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }

    private val speaking: OkHttpClient by lazy { http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    /**
     * [text] to [to] from Luna's WhatsApp: someone in the family hub by that name, or, with
     * [phone], that number from her contacts (saved as [name]). Null when no one in the hub is
     * called [to] and no [phone] came: look in the contacts. Quick to give up, since the call or
     * chat is waiting on it; [LiveError.code] says why it wasn't sent.
     */
    suspend fun whatsapp(to: String, text: String, name: String? = null, phone: String? = null): Sent? = try {
        parse(
            call("POST", "/family/whatsapp", gson.toJson(mapOf("to" to to, "text" to text, "name" to name, "phone" to phone)), client = quick),
            Sent::class.java,
        ).also { if (!it.ok) throw LiveError("Luna's server didn't send it.") }
    } catch (e: LiveError) {
        if (e.code == 404) null else throw e
    }

    private val quick: OkHttpClient by lazy { http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

    /** The demo's shortcuts: `missed-dose` acts as if the 9:00 dose went unconfirmed, `reset` clears the hub. */
    suspend fun demo(action: String) {
        call("POST", "/demo/$action", "{}")
    }

    /** The phone's end of the call's tool bridge, at [url] (from [Session.bridgeUrl]). */
    fun openBridge(url: String, listener: WebSocketListener): WebSocket =
        http.newWebSocket(Request.Builder().url(url).header(KEY_HEADER, appKey()).build(), listener)

    private suspend fun call(method: String, path: String, body: String? = null, client: OkHttpClient = http): String = withContext(Dispatchers.IO) {
        val base = serverUrl().trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: throw LiveError("Live voice has no server set.")
        val request = Request.Builder()
            .url(base + path)
            .header(KEY_HEADER, appKey())
            .method(method, body?.toRequestBody(JSON))
            .build()
        val (code, text) = try {
            client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw LiveError("Couldn't reach Luna's server. The phone may be offline.")
        } catch (e: IllegalArgumentException) {
            throw LiveError("The live voice server address isn't a web address.")
        }
        when (code) {
            in 200..299 -> text
            401, 403 -> throw LiveError("Luna's server refused the app key.")
            else -> throw LiveError(errorIn(text) ?: "Luna's server answered $code.", code)
        }
    }

    private fun <T> parse(json: String, type: Class<T>): T =
        runCatching { gson.fromJson(json, type) }.getOrNull() ?: throw LiveError("Luna's server sent back something unreadable.")

    companion object {
        const val KEY_HEADER = "X-App-Key"
        private val JSON = "application/json".toMediaType()
        private val gson = Gson()

        /** The server's `{"error": "…"}`, when it says why. */
        internal fun errorIn(json: String): String? = runCatching {
            JsonParser.parseString(json).asJsonObject.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** A live call couldn't start or carry on; [message] is fit to show. [code]: the server's HTTP status, when it answered. */
class LiveError(message: String, val code: Int? = null) : Exception(message)
