package com.local.assistant.ui.setup

import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.GalleryModel
import com.local.assistant.gallery.IndexService
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.model.InstalledModel
import com.local.assistant.model.Transfer
import com.local.assistant.model.formatBytes
import java.text.NumberFormat

/**
 * What the phone sets up for itself, as one value: photo and memory search's model, and the
 * indexing that follows (photos, the faces in them, earlier chats). Replies need no download:
 * they come from Luna's server. [SetupScreen] lists it step by step, and the chat's pill names
 * the one under way ([pill]).
 */
internal data class SetupState(
    val photoModel: InstalledModel?,
    val photoTransfer: Transfer?,
    val photoError: String?,
    val photosAllowed: Boolean,
    val photos: GalleryIndex.Status,
    val findPeople: Boolean,
    val faces: FaceIndex.Status,
    /** Chunks of earlier chats waiting to be made searchable; null before the queue has counted. */
    val memoryLeft: Int?,
    /** The user paused the downloads (`AppContainer.pauseDownloads`). */
    val paused: Boolean = false,
    /** What the photo model's stopped download already has. */
    val photoPartialBytes: Long = 0,
)

internal enum class StepKind { PHOTO_MODEL, PHOTOS, PEOPLE, MEMORY }

internal enum class StepStatus {
    /** Waiting its turn: after another step, or for something to arrive. */
    WAITING,

    /** Needs the user's tap to begin: the photo model's download. */
    START,
    RUNNING,

    /** Stopped for now: a warm phone, the app closed, or a download the user paused. */
    PAUSED,
    DONE,
    FAILED,
}

/** One line of the setup: what it is, how it's going, and how far it's got (null when that's unknown). */
internal data class SetupStep(
    val kind: StepKind,
    val title: String,
    val detail: String,
    val status: StepStatus,
    val fraction: Float? = null,
    /** The button that stops it while it runs: a download pauses, a copy is cancelled. */
    val stop: String? = null,
    /** Paused by the user, with a Resume. */
    val resume: Boolean = false,
)

/** The chat's one-line note while setup work goes on, and how far it's got. */
internal data class SetupPill(val text: String, val fraction: Float?)

private val numbers: NumberFormat get() = NumberFormat.getIntegerInstance()

private fun transferDetail(transfer: Transfer): String = when {
    transfer.checking -> "Checking the download"
    else -> buildString {
        append("${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.bytesTotal)}")
        transfer.etaSeconds?.let { append(" · ${formatBytes(transfer.bytesPerSecond)}/s · ${duration(it)} left") }
    }
}

internal fun duration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "${seconds.coerceAtLeast(1)} s"
}

internal fun SetupState.steps(): List<SetupStep> = buildList {
    add(photoModelStep())
    if (photosAllowed) add(photosStep())
    if (photosAllowed && findPeople) add(peopleStep())
    memoryStep()?.let(::add)
}

private fun SetupState.photoModelStep(): SetupStep {
    val title = "Photo and memory search"
    return when {
        photoModel != null -> SetupStep(StepKind.PHOTO_MODEL, title, "Here · ${formatBytes(photoModel.sizeBytes)}", StepStatus.DONE)
        photoTransfer != null -> SetupStep(
            StepKind.PHOTO_MODEL,
            title,
            transferDetail(photoTransfer),
            StepStatus.RUNNING,
            photoTransfer.fraction.takeIf { photoTransfer.bytesTotal > 0 && !photoTransfer.checking },
        )
        photoError != null -> SetupStep(StepKind.PHOTO_MODEL, title, photoError, StepStatus.FAILED)
        paused && photoPartialBytes > 0 -> pausedStep(StepKind.PHOTO_MODEL, title, photoPartialBytes, GalleryModel.SIZE_BYTES)
        photoPartialBytes > 0 -> SetupStep(
            StepKind.PHOTO_MODEL,
            title,
            "${formatBytes(photoPartialBytes)} of ${formatBytes(GalleryModel.SIZE_BYTES)} already here; the rest picks up from there",
            StepStatus.START,
            (photoPartialBytes.toFloat() / GalleryModel.SIZE_BYTES).coerceIn(0f, 1f),
        )
        else -> SetupStep(StepKind.PHOTO_MODEL, title, "${formatBytes(GalleryModel.SIZE_BYTES)}, once, to find photos by what's in them and earlier chats by meaning. Best on Wi-Fi.", StepStatus.START)
    }
}

private fun SetupState.photosStep(): SetupStep {
    val title = "Indexing your photos"
    val counted = "${numbers.format(photos.indexed)} of ${numbers.format(photos.total)}"
    val fraction = if (photos.total > 0) photos.indexed.toFloat() / photos.total else null
    return when {
        photoModel == null -> SetupStep(StepKind.PHOTOS, title, "Starts when the photo model is here", StepStatus.WAITING)
        photos.cooling -> SetupStep(StepKind.PHOTOS, title, "$counted · paused while the phone cools", StepStatus.PAUSED, fraction)
        photos.running && photos.total == 0 -> SetupStep(StepKind.PHOTOS, title, "Finding your photos…", StepStatus.RUNNING)
        photos.running -> SetupStep(StepKind.PHOTOS, title, "$counted · on this phone only", StepStatus.RUNNING, fraction)
        photos.total > 0 && photos.remaining == 0 -> SetupStep(StepKind.PHOTOS, title, "${numbers.format(photos.total)} photos, searchable by what's in them", StepStatus.DONE)
        photos.total > 0 -> SetupStep(
            StepKind.PHOTOS,
            title,
            "$counted · carries on while the app is open, or at night on the charger",
            StepStatus.PAUSED,
            fraction,
        )
        else -> SetupStep(StepKind.PHOTOS, title, "Starting…", StepStatus.WAITING)
    }
}

private fun SetupState.peopleStep(): SetupStep {
    val title = "Finding people"
    val all = faces.photosDone + faces.photosLeft
    val fraction = if (all > 0) faces.photosDone.toFloat() / all else null
    return when {
        photoModel == null || all == 0 -> SetupStep(StepKind.PEOPLE, title, "Faces are grouped as the photos are indexed", StepStatus.WAITING)
        faces.photosLeft == 0 -> SetupStep(StepKind.PEOPLE, title, "Done · name them in Photos", StepStatus.DONE)
        photos.running -> SetupStep(StepKind.PEOPLE, title, "${numbers.format(faces.photosLeft)} photos left · faces stay on this phone", StepStatus.RUNNING, fraction)
        else -> SetupStep(StepKind.PEOPLE, title, "${numbers.format(faces.photosLeft)} photos left · carries on with the photos", StepStatus.PAUSED, fraction)
    }
}

/** Only with earlier chats to go through: a new install has none. */
private fun pausedStep(kind: StepKind, title: String, have: Long, size: Long) = SetupStep(
    kind,
    title,
    "Paused · ${formatBytes(have)} of ${formatBytes(size)}",
    StepStatus.PAUSED,
    (have.toFloat() / size).coerceIn(0f, 1f),
    resume = true,
)

private fun SetupState.memoryStep(): SetupStep? {
    val left = memoryLeft ?: return null
    if (left <= 0) return null
    return SetupStep(
        StepKind.MEMORY,
        "Making earlier chats searchable",
        if (photoModel == null) "${numbers.format(left)} left · after the photo model" else "${numbers.format(left)} left",
        if (photoModel == null) StepStatus.WAITING else StepStatus.RUNNING,
    )
}

/**
 * The chat's note while setup carries on in the background, or null: the photo model's download,
 * and indexing when there's enough of it to notice ([IndexService.BACKLOG], as for
 * its notification). The nightly pass over a few new photos isn't worth one.
 */
internal fun SetupState.pill(): SetupPill? {
    // Paused: the way to Resume is the setup page the pill opens.
    if (paused && photoPartialBytes > 0) return SetupPill("Download paused · tap to resume", null)
    photoTransfer?.let { return SetupPill("Downloading photo search · ${percent(it)}", it.fraction.takeIf { _ -> it.bytesTotal > 0 }) }
    if (photosAllowed && photoModel != null && (photos.running || photos.cooling) && photos.remaining >= IndexService.BACKLOG) {
        val fraction = if (photos.total > 0) photos.indexed.toFloat() / photos.total else null
        val paused = if (photos.cooling) " · paused, phone warm" else ""
        return SetupPill("Indexing photos · ${numbers.format(photos.indexed)} of ${numbers.format(photos.total)}$paused", fraction)
    }
    if (photosAllowed && findPeople && photos.running && faces.photosLeft >= IndexService.BACKLOG) {
        val all = faces.photosDone + faces.photosLeft
        return SetupPill("Finding people · ${numbers.format(faces.photosLeft)} photos left", if (all > 0) faces.photosDone.toFloat() / all else null)
    }
    if (photoModel != null && (memoryLeft ?: 0) >= MEMORY_WORTH_A_NOTE) {
        return SetupPill("Making earlier chats searchable · ${numbers.format(memoryLeft)} left", null)
    }
    return null
}

private fun percent(transfer: Transfer): String =
    if (transfer.checking || transfer.bytesTotal <= 0) "checking" else "${(transfer.fraction * 100).toInt()}%"

/** Fewer earlier messages than this are done in seconds. */
private const val MEMORY_WORTH_A_NOTE = 50
