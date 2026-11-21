package com.local.assistant.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The ring heard while a call to Luna connects, as a phone rings out: "brr-brr … brr-brr", the
 * double ring of Indian and British phones (400 and 450 Hz, on 0.4 s, off 0.2 s, on 0.4 s, off
 * 2 s). Made here rather than shipped as a sound, and looped until [stop]: the moment Luna picks up.
 */
class Ringback {

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        track = runCatching {
            val samples = cycle()
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .apply {
                    write(samples, 0, samples.size)
                    setLoopPoints(0, samples.size, -1)
                    setVolume(VOLUME)
                    play()
                }
        }.onFailure { Log.w(TAG, "No ring", it) }.getOrNull()
    }

    fun stop() {
        val playing = track ?: return
        track = null
        runCatching {
            playing.stop()
            playing.release()
        }
    }

    companion object {
        private const val TAG = "Ringback"
        private const val RATE = 22_050
        private const val VOLUME = 0.5f

        /** One ring's sound: two bursts, each faded in and out so it doesn't click, then quiet. */
        internal fun cycle(): ShortArray {
            val on = (0.4 * RATE).toInt()
            val gap = (0.2 * RATE).toInt()
            val rest = (2.0 * RATE).toInt()
            val fade = (0.01 * RATE).toInt()
            val out = ShortArray(on + gap + on + rest)
            for (start in listOf(0, on + gap)) {
                for (i in 0 until on) {
                    val t = i.toDouble() / RATE
                    val edge = min(1.0, min(i, on - 1 - i).toDouble() / fade)
                    val wave = (sin(2 * PI * 400 * t) + sin(2 * PI * 450 * t)) / 2
                    out[start + i] = (wave * edge * 0.8 * Short.MAX_VALUE).toInt().toShort()
                }
            }
            return out
        }
    }
}
