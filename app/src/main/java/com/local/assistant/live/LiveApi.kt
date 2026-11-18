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

    data class Member(val id: String = "", val name: String = "", val relation: String? = null, val role: String? = null)

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

    suspend fun family(): Family = parse(call("GET", "/family"), Family::class.java)

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

    /** The demo's shortcuts: `missed-dose` acts as if the 9:00 dose went unconfirmed, `reset` clears the hub. */
    suspend fun demo(action: String) {
        call("POST", "/demo/$action", "{}")
    }

    /** The phone's end of the call's tool bridge, at [url] (from [Session.bridgeUrl]). */
    fun openBridge(url: String, listener: WebSocketListener): WebSocket =
        http.newWebSocket(Request.Builder().url(url).header(KEY_HEADER, appKey()).build(), listener)

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val base = serverUrl().trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: throw LiveError("Live voice has no server set.")
        val request = Request.Builder()
            .url(base + path)
            .header(KEY_HEADER, appKey())
            .method(method, body?.toRequestBody(JSON))
            .build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw LiveError("Couldn't reach Luna's server. The phone may be offline.")
        } catch (e: IllegalArgumentException) {
            throw LiveError("The live voice server address isn't a web address.")
        }
        when (code) {
            in 200..299 -> text
            401, 403 -> throw LiveError("Luna's server refused the app key.")
            else -> throw LiveError(errorIn(text) ?: "Luna's server answered $code.")
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

/** A live call couldn't start or carry on; [message] is fit to show. */
class LiveError(message: String) : Exception(message)
