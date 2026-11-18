package com.local.assistant.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.wake.WakeWord
import io.agora.conversational.api.AgentState
import io.agora.conversational.api.ConversationalAIAPIConfig
import io.agora.conversational.api.ConversationalAIAPIError
import io.agora.conversational.api.ConversationalAIAPIImpl
import io.agora.conversational.api.IConversationalAIAPIEventHandler
import io.agora.conversational.api.InterruptEvent
import io.agora.conversational.api.MessageError
import io.agora.conversational.api.MessageReceipt
import io.agora.conversational.api.Metric
import io.agora.conversational.api.ModuleError
import io.agora.conversational.api.Priority
import io.agora.conversational.api.StateChangeEvent
import io.agora.conversational.api.TextMessage
import io.agora.conversational.api.Transcript
import io.agora.conversational.api.TranscriptRenderMode
import io.agora.conversational.api.TranscriptStatus
import io.agora.conversational.api.TranscriptType
import io.agora.conversational.api.Turn
import io.agora.conversational.api.VoiceprintStateChangeEvent
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtm.ErrorInfo
import io.agora.rtm.LinkStateEvent
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.WebSocket
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One live voice call with Luna through Agora Conversational AI. Agora hears the user (ARES,
 * en-IN), sends their words to the Luna server's model and speaks its answer with Murf; this
 * phone carries the audio over RTC, gets the transcripts and the agent's state over RTM (read by
 * Agora's client toolkit), and runs the phone's own tools when the server asks ([LiveToolBridge]).
 *
 * Started in the order the toolkit needs, so nothing the agent says first is missed: the audio set
 * up for an AI agent (echo cancelled, barge-in kept), RTM logged in and the channel's messages
 * subscribed to, the RTC channel joined with the microphone published, the bridge opened, and only
 * then the agent started (`POST /session/start`). A foreground service ([LiveCallService]) keeps
 * the call going if the user switches app; the wake word stands aside meanwhile ([active]).
 */
class LiveSession internal constructor(
    private val context: Context,
    private val api: LiveApi,
    private val contexts: LiveContextBuilder,
    execute: suspend (ToolCall, ToolContext) -> ToolExecutor.Outcome,
    private val transcript: LiveTranscriptLog,
    /** [LiveContext.PURPOSE_USER] or [LiveContext.PURPOSE_CHECKIN]. */
    val purpose: String,
    val doseId: String?,
    val locked: Boolean,
    /** The chat the call was started from, whose summary the call is told; null for none. */
    private val fromChat: Long?,
    private val userId: String,
    /** Outlives the call: the stop request and the last transcripts finish after it. */
    private val appScope: CoroutineScope,
) {

    enum class State { CONNECTING, LISTENING, THINKING, SPEAKING, ENDED, FAILED }

    /** A line of the call as the captions show it; [final] once Agora has settled its words. */
    data class Caption(val turnId: Long, val fromUser: Boolean, val text: String, val final: Boolean)

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Why the call failed, fit to show; null unless [state] is [State.FAILED]. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _transcripts = MutableStateFlow<List<Caption>>(emptyList())
    val transcripts: StateFlow<List<Caption>> = _transcripts.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** How loud Luna is speaking, 0..1, for the orb. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val bridge = LiveToolBridge(execute, transcript, locked)

    /** What the call did: phone tools' chips, the server's actions, the family's news. */
    val events: SharedFlow<LiveToolBridge.Event> = bridge.events

    /** The call's chat, once anything has been said. */
    suspend fun chatId(): Long? = transcript.chatIfAny()

    val isOver: Boolean get() = _state.value == State.ENDED || _state.value == State.FAILED

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Transcripts are stored one after another, in the order Agora settled them. */
    private val toStore = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private var engine: RtcEngine? = null
    private var rtm: RtmClient? = null
    private var ai: ConversationalAIAPIImpl? = null
    private var socket: WebSocket? = null
    private var session: LiveApi.Session? = null
    private var joined: CompletableDeferred<Unit>? = null
    private val agentJoined = CompletableDeferred<Unit>()
    private var bridgeRetries = 0
    private var released = false

    private val agentUserId: String? get() = session?.agentUid?.toString()

    internal fun start() {
        _active.value = true
        appScope.launch { for (store in toStore) runCatching { store() }.onFailure { Log.w(TAG, "Could not store a transcript", it) } }
        scope.launch { connect() }
    }

    private suspend fun connect() {
        try {
            val (call, opening) = coroutineScope {
                val call = async { api.token(userId) }
                val opening = async { contexts.build(purpose, doseId, locked, fromChat) }
                call.await() to opening.await()
            }
            session = call
            // In the foreground before the microphone opens: the call goes on if they switch app.
            LiveCallService.start(context)
            val engine = createEngine(call.appId).also { engine = it }
            val rtm = RtmClient.create(RtmConfig.Builder(call.appId, call.uid.toString()).eventListener(rtmListener).build()).also { rtm = it }
            val ai = ConversationalAIAPIImpl(ConversationalAIAPIConfig(rtcEngine = engine, rtmClient = rtm, renderMode = TranscriptRenderMode.Word)).also { ai = it }
            ai.loadAudioSettings(Constants.AUDIO_SCENARIO_AI_CLIENT)
            ai.addHandler(handler)
            awaitRtm { rtm.login(call.token, it) }
            awaitToolkit { ai.subscribeMessage(call.channel, it) }
            join(engine, call)
            openBridge(call.bridgeUrl)
            api.start(call.sessionId, opening)
            // The agent joins the channel a moment after it is started, and greets.
            withTimeoutOrNull(AGENT_JOIN_TIMEOUT_MS) { agentJoined.await() } ?: throw LiveError("Luna didn't join the call. Try again in a minute.")
            if (_state.value == State.CONNECTING) _state.value = State.LISTENING
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The call couldn't start", e)
            fail(e.message?.takeIf { e is LiveError } ?: "The call couldn't start. Check the connection and try again.")
        }
    }

    private fun createEngine(appId: String): RtcEngine {
        val config = RtcEngineConfig().apply {
            mContext = context.applicationContext
            mAppId = appId
            mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            mAudioScenario = Constants.AUDIO_SCENARIO_AI_CLIENT
            mEventHandler = rtcHandler
        }
        val engine = RtcEngine.create(config)
        // Agora's AI echo cancellation and noise suppression, as its toolkit sample loads them:
        // Luna's voice from the speaker mustn't come back as the user talking over her.
        listOf("ai_echo_cancellation_extension", "ai_noise_suppression_extension").forEach { name ->
            if (engine.loadExtensionProvider(name) != 0) Log.w(TAG, "No $name")
        }
        engine.enableAudio()
        // A call to talk to, not hold to the ear: an elderly user puts the phone down to listen.
        engine.setDefaultAudioRoutetoSpeakerphone(true)
        engine.enableAudioVolumeIndication(VOLUME_INTERVAL_MS, 3, false)
        return engine
    }

    private suspend fun join(engine: RtcEngine, call: LiveApi.Session) {
        val done = CompletableDeferred<Unit>().also { joined = it }
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
        }
        val result = engine.joinChannel(call.token, call.channel, call.uid, options)
        if (result != 0) throw LiveError("Couldn't join the call (${RtcEngine.getErrorDescription(result)}).")
        withTimeoutOrNull(JOIN_TIMEOUT_MS) { done.await() } ?: throw LiveError("Joining the call took too long. The network may be slow.")
    }

    private fun openBridge(url: String) {
        socket = api.openBridge(
            url,
            bridge.listener(
                scope,
                onOpen = { bridgeRetries = 0 },
                onClosed = { failure ->
                    if (failure != null) Log.w(TAG, "The tool bridge dropped", failure)
                    // Opened again while the call is on: without it Luna can't set a reminder.
                    scope.launch {
                        if (released || bridgeRetries >= MAX_BRIDGE_RETRIES) return@launch
                        bridgeRetries++
                        delay(BRIDGE_RETRY_MS * bridgeRetries)
                        if (!released) openBridge(url)
                    }
                },
            ),
        )
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        // The stream stays published, silent: the agent keeps its place in the conversation.
        engine?.adjustRecordingSignalVolume(if (muted) 0 else 100)
    }

    /** Stops Luna mid-sentence. */
    fun interrupt() {
        val agent = agentUserId ?: return
        ai?.interrupt(agent) { error -> error?.let { Log.w(TAG, "Interrupt failed: ${it.errorMessage}") } }
    }

    /** Typed instead of said: sent to the agent as the user's next words. */
    fun sendText(text: String) {
        val words = text.trim().takeIf { it.isNotEmpty() } ?: return
        val agent = agentUserId ?: return
        _transcripts.update { (it + Caption(TYPED_TURN, fromUser = true, text = words, final = true)).takeLast(MAX_CAPTIONS) }
        toStore.trySend { transcript.typed(words) }
        ai?.chat(agent, TextMessage(priority = Priority.INTERRUPT, responseInterruptable = true, text = words)) { error ->
            error?.let { Log.w(TAG, "Sending typed text failed: ${it.errorMessage}") }
        }
    }

    /** Hangs up: the agent is stopped and everything let go. */
    fun end() {
        if (isOver) return
        _state.value = State.ENDED
        release()
    }

    private fun fail(message: String) {
        if (isOver) return
        _error.value = message
        _state.value = State.FAILED
        release()
    }

    private fun release() {
        if (released) return
        released = true
        // The agent leaves on its own after idle_timeout too, should this never arrive.
        session?.let { call -> appScope.launch { runCatching { withTimeout(STOP_TIMEOUT_MS) { api.stop(call.sessionId) } } } }
        runCatching { socket?.close(1000, "call ended") }
        val channel = session?.channel
        ai?.let { api ->
            if (channel != null) runCatching { api.unsubscribeMessage(channel) {} }
            runCatching { api.removeHandler(handler) }
        }
        runCatching { engine?.leaveChannel() }
        runCatching { ai?.destroy() }
        rtm?.let { client ->
            runCatching { client.logout(null) }
            runCatching { client.release() }
        }
        if (engine != null) runCatching { RtcEngine.destroy() }
        ai = null
        rtm = null
        engine = null
        toStore.close()
        LiveCallService.stop(context)
        _level.value = 0f
        _active.value = false
        scope.cancel()
    }

    /** A line of the call from the toolkit: shown at once, and stored once its words are final. */
    private fun caption(update: Transcript) {
        val fromUser = update.type == TranscriptType.USER
        val interrupted = update.status == TranscriptStatus.INTERRUPTED
        val final = update.status == TranscriptStatus.END || interrupted
        val line = Caption(update.turnId, fromUser, update.text, final)
        _transcripts.update { lines ->
            val at = lines.indexOfLast { it.turnId == line.turnId && it.fromUser == fromUser }
            (if (at >= 0) lines.toMutableList().apply { set(at, line) } else lines + line).takeLast(MAX_CAPTIONS)
        }
        val turn = update.turnId
        val text = update.text
        when {
            fromUser && !final -> toStore.trySend { transcript.hearing(turn, text) }
            fromUser -> toStore.trySend { transcript.userSaid(turn, text) }
            final -> toStore.trySend { transcript.agentSaid(turn, text, interrupted) }
        }
    }

    private val handler = object : IConversationalAIAPIEventHandler {
        @Deprecated("Use onAgentListeningChanged, onAgentThinkingChanged, and onAgentSpeakingChanged instead.")
        override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
            if (isOver) return
            _state.value = when (event.state) {
                AgentState.THINKING -> State.THINKING
                AgentState.SPEAKING -> State.SPEAKING
                AgentState.IDLE, AgentState.SILENT, AgentState.LISTENING, AgentState.UNKNOWN -> State.LISTENING
            }
            if (event.state != AgentState.SPEAKING) _level.value = 0f
        }

        override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) = caption(transcript)

        override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) = Unit

        override fun onAgentMetrics(agentUserId: String, metric: Metric) = Unit

        override fun onTurnFinished(agentUserId: String, turn: Turn) {
            // The figure for the slide: how long from the end of her words to Luna's voice.
            Log.i(TAG, "Turn ${turn.turnId}: ${turn.e2eLatency.toInt()} ms end to end (ASR ${turn.segmentedLatency.asrTTLW.toInt()}, LLM ${turn.segmentedLatency.llmTTFT.toInt()}, TTS ${turn.segmentedLatency.ttsTTFB.toInt()})")
        }

        override fun onAgentError(agentUserId: String, error: ModuleError) {
            Log.w(TAG, "Agent ${error.type.value} error ${error.code}: ${error.message}")
        }

        override fun onMessageError(agentUserId: String, error: MessageError) {
            Log.w(TAG, "Message error ${error.code}: ${error.message}")
        }

        override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) = Unit

        override fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent) = Unit

        override fun onDebugLog(log: String) = Unit
    }

    private val rtcHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            joined?.complete(Unit)
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            if (uid == session?.agentUid) agentJoined.complete(Unit)
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            // The agent left: stopped by the server, or quiet for too long (idle_timeout).
            if (uid == session?.agentUid) scope.launch { end() }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            if (state == Constants.CONNECTION_STATE_FAILED) scope.launch { fail("The call's connection failed (${RtcEngine.getErrorDescription(reason)}).") }
        }

        override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
            val agent = session?.agentUid ?: return
            speakers?.firstOrNull { it.uid == agent }?.let { _level.value = (it.volume / 255f).coerceIn(0f, 1f) }
        }

        override fun onError(err: Int) {
            Log.w(TAG, "RTC error $err: ${RtcEngine.getErrorDescription(err)}")
        }
    }

    private val rtmListener = object : RtmEventListener {
        override fun onLinkStateEvent(event: LinkStateEvent?) {
            if (event?.currentState == RtmConstants.RtmLinkState.FAILED) {
                // Without RTM there are no captions and no agent state, but the voice still works.
                Log.w(TAG, "RTM link failed: ${event.reasonCode}")
            }
        }
    }

    private suspend fun awaitRtm(block: (ResultCallback<Void>) -> Unit) = suspendCancellableCoroutine { continuation ->
        block(object : ResultCallback<Void> {
            override fun onSuccess(result: Void?) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onFailure(error: ErrorInfo?) {
                if (continuation.isActive) continuation.resumeWithException(LiveError("Couldn't connect the call's messages (${error?.errorCode}: ${error?.errorReason})."))
            }
        })
    }

    private suspend fun awaitToolkit(block: ((ConversationalAIAPIError?) -> Unit) -> Unit) = suspendCancellableCoroutine { continuation ->
        block { error ->
            if (!continuation.isActive) return@block
            if (error == null) continuation.resume(Unit) else continuation.resumeWithException(LiveError("Couldn't listen for the call's messages (${error.errorMessage})."))
        }
    }

    companion object {
        private const val TAG = "LiveSession"
        private const val JOIN_TIMEOUT_MS = 15_000L
        private const val AGENT_JOIN_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val VOLUME_INTERVAL_MS = 150
        private const val MAX_CAPTIONS = 40
        private const val MAX_BRIDGE_RETRIES = 5
        private const val BRIDGE_RETRY_MS = 1_500L

        /** A typed line's caption, which has no turn of Agora's. */
        private const val TYPED_TURN = -1L

        private val _active = MutableStateFlow(false)

        /** A call is on: the wake word listener stands aside, as it does for the app's own recording. */
        val active: StateFlow<Boolean> = _active.asStateFlow()
    }
}

/**
 * Live calls: whether one can be made, and the one in progress. One at a time; asking for another
 * while one is on gives that one.
 */
class LiveCalls(
    private val context: Context,
    val api: LiveApi,
    private val contexts: LiveContextBuilder,
    private val execute: suspend (ToolCall, ToolContext) -> ToolExecutor.Outcome,
    private val chats: ChatRepository,
    /** The same archiving a typed exchange gets ([com.local.assistant.memory.prompt.TurnRunner.finish]). */
    private val onExchangeStored: suspend (userMessageId: Long) -> Unit,
    private val appScope: CoroutineScope,
    /** Settings › Live voice is on. */
    private val enabled: () -> Boolean,
    /** Who the server knows the user as ("lakshmi"), from the family it last sent; blank for its own elder. */
    private val userId: () -> String = { "" },
) {

    private val _current = MutableStateFlow<LiveSession?>(null)
    val current: StateFlow<LiveSession?> = _current.asStateFlow()

    /** Live voice is on and has a server: the entry points show. */
    fun visible(): Boolean = enabled() && api.configured

    /**
     * A call can be made now: [visible], and the phone is online. Otherwise the power button and
     * the wake word open the on-device assistant, as they always did.
     */
    fun available(): Boolean = visible() && online()

    fun start(purpose: String = LiveContext.PURPOSE_USER, doseId: String? = null, locked: Boolean = false, fromChat: Long? = null): LiveSession {
        _current.value?.takeUnless { it.isOver }?.let { return it }
        val log = LiveTranscriptLog(
            createChat = { chats.createChat(title()) },
            addMessage = { chatId, role, text, incomplete -> chats.addMessage(chatId, role, text, incomplete = incomplete) },
            updateMessage = { id, text -> chats.updateMessage(id, text, incomplete = false) },
            onExchangeStored = onExchangeStored,
        )
        val session = LiveSession(
            context = context.applicationContext,
            api = api,
            contexts = contexts,
            execute = execute,
            transcript = log,
            purpose = purpose,
            doseId = doseId,
            locked = locked,
            fromChat = fromChat,
            userId = userId(),
            appScope = appScope,
        )
        _current.value = session
        session.start()
        return session
    }

    /** "Call with Luna, 9:02 AM": how the call's chat is listed. */
    private fun title(): String = "Call with ${WakeWord.NAME}, ${LocalTime.now().format(TITLE_TIME)}"

    private fun online(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        val TITLE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
