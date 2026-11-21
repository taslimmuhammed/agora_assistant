package com.local.assistant.memory.prompt

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's own events, for working out what to make faster or why it was slow: the photo model
 * and the natural voice loading and going, the app coming and going, the system closing it. One
 * line in the log (tag TurnTiming) and one row in `files/model-events.csv` each, the newest
 * [KEEP_ROWS]. No words of a chat: only what happened, how long it took, and why.
 */
object TurnTimings {

    /** Where the file goes; nothing is written until it is set (as the app starts). */
    @Volatile
    var directory: File? = null

    /** [kind] is what happened ("photo_model_load", "app_back"), [detail] why or what for. */
    fun event(kind: String, ms: Long? = null, detail: String = "") {
        Log.i(TAG, "event $kind${ms?.let { " in $it ms" }.orEmpty()}${if (detail.isNotEmpty()) " ($detail)" else ""}")
        append(listOf(LocalDateTime.now().format(STAMP), processAgeSeconds().toString(), kind, ms?.toString().orEmpty(), detail))
    }

    private val lock = Any()

    private fun append(row: List<String>) {
        val dir = directory ?: return
        runCatching {
            synchronized(lock) {
                val file = File(dir, EVENTS_FILE)
                if (!file.isFile) file.writeText("$EVENTS_HEADER\n")
                file.appendText(row.joinToString(",") { it.replace(',', ';') } + "\n")
                // Rolled over in a batch, not every event: rewriting the file is the costly part.
                val lines = file.readLines()
                if (lines.size > KEEP_ROWS + ROLL_SLACK + 1) {
                    file.writeText((listOf(EVENTS_HEADER) + lines.drop(1).takeLast(KEEP_ROWS)).joinToString("\n", postfix = "\n"))
                }
            }
        }.onFailure { Log.w(TAG, "Could not write $EVENTS_FILE", it) }
    }

    /** How long this process has been running: short after the system killed and restarted the app. */
    private fun processAgeSeconds(): Long = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000

    private const val TAG = "TurnTiming"
    const val EVENTS_FILE = "model-events.csv"
    private const val KEEP_ROWS = 500
    private const val ROLL_SLACK = 50
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val EVENTS_HEADER = "at,process_age_s,kind,ms,detail"
}
