package com.local.assistant.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Luna's replies in Murf's voice, the one her Agora calls speak with: each sentence is made into
 * speech by Murf through Luna's server (POST /family/speech), and played here in order. Every
 * sentence is asked for as soon as it is given, so the next is ready while one plays.
 *
 * When Murf can't answer (offline, the server down, no key), that sentence and the rest waiting
 * go to [fallback], the phone's own voice, and Murf is left alone for [REST_MS]. Everything here
 * runs on the main thread, as [Speaker] does.
 */
class MurfVoice(
    /** [text] as an MP3, or null when Murf can't make it. */
    private val speech: suspend (String) -> ByteArray?,
    /** Murf is chosen for replies, and Luna's server can be reached now. */
    private val chosen: () -> Boolean,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val fallback: (String) -> Unit,
    private val onSpeakingChanged: () -> Unit,
) {

    private class Line(val text: String, val audio: Deferred<ByteArray?>)

    private val queue = ArrayDeque<Line>()
    private var player: MediaPlayer? = null
    private var playing: Job? = null
    private var restUntil = 0L
    private var nextFile = 0

    /** Something is being said, or fetched to be. */
    val isSpeaking: Boolean get() = queue.isNotEmpty() || playing?.isActive == true

    /** Murf should say the next sentence: chosen, reachable, and not resting after a failure. */
    fun usable(): Boolean = chosen() && SystemClock.elapsedRealtime() >= restUntil

    fun speak(text: String) {
        queue.addLast(Line(text, scope.async(Dispatchers.IO) { fetch(text) }))
        if (playing?.isActive != true) playing = scope.launch(Dispatchers.Main.immediate) { playAll() }
        onSpeakingChanged()
    }

    fun stop() {
        queue.forEach { it.audio.cancel() }
        queue.clear()
        playing?.cancel()
        playing = null
        release()
        onSpeakingChanged()
    }

    private suspend fun playAll() {
        val self = currentCoroutineContext()[Job]
        try {
            while (queue.isNotEmpty()) {
                val line = queue.first()
                val audio = line.audio.await()
                queue.removeFirst()
                if (audio == null) {
                    // The rest waiting would likely fail too: the phone says them, in order.
                    restUntil = SystemClock.elapsedRealtime() + REST_MS
                    fallback(line.text)
                    while (queue.isNotEmpty()) queue.removeFirst().also { it.audio.cancel() }.let { fallback(it.text) }
                    break
                }
                play(audio)
            }
        } finally {
            // Still running here, so [isSpeaking] would say so: let go of this job first. A stop()
            // and a new speak() may have started another meanwhile, whose player stays.
            if (playing === self) {
                playing = null
                release()
            }
            onSpeakingChanged()
        }
    }

    private suspend fun fetch(text: String): ByteArray? = try {
        speech(text)?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Murf couldn't say a sentence: ${e.message}")
        null
    }

    /** Plays one MP3 to its end; cancelled, it stops at once. */
    private suspend fun play(mp3: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(cacheDir, "murf-${nextFile++ % FILES}.mp3").apply { writeBytes(mp3) }
        }
        suspendCancellableCoroutine { done ->
            val media = MediaPlayer()
            player = media
            try {
                media.setAudioAttributes(ASSISTANT_AUDIO)
                media.setDataSource(file.absolutePath)
                media.setOnCompletionListener { if (done.isActive) done.resume(Unit) }
                media.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Couldn't play Murf's audio: $what/$extra")
                    if (done.isActive) done.resume(Unit)
                    true
                }
                media.setOnPreparedListener { it.start() }
                media.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't play Murf's audio", e)
                if (done.isActive) done.resume(Unit)
            }
            done.invokeOnCancellation { release() }
        }
        release()
    }

    private fun release() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private companion object {
        const val TAG = "MurfVoice"

        /** After a failure, how long the phone's voice speaks before Murf is tried again. */
        const val REST_MS = 2 * 60_000L

        /** Audio files kept in turn: one playing, the next ones ready. */
        const val FILES = 4

        val ASSISTANT_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
