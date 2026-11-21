package com.local.assistant.ui.setup

import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.model.InstalledModel
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupProgressTest {

    private val photoModel = InstalledModel(File("photos.litertlm"), 387_710_976L)

    /** A new install, photos allowed, before anything is fetched. Chatting needs nothing here. */
    private val fresh = SetupState(
        photoModel = null,
        photoTransfer = null,
        photoError = null,
        photosAllowed = true,
        photos = GalleryIndex.Status(),
        findPeople = true,
        faces = FaceIndex.Status(),
        memoryLeft = 0,
    )

    private fun SetupState.step(kind: StepKind) = steps().single { it.kind == kind }

    @Test
    fun `a new install offers photo search's download, which the user starts`() {
        assertEquals(StepStatus.START, fresh.step(StepKind.PHOTO_MODEL).status)
        assertEquals(StepStatus.WAITING, fresh.step(StepKind.PHOTOS).status)
        // Nothing earlier to make searchable on a new install.
        assertTrue(fresh.steps().none { it.kind == StepKind.MEMORY })
        assertNull(fresh.pill())
    }

    @Test
    fun `a download shows how far it's got, and a part left behind is picked up`() {
        val downloading = fresh.copy(photoTransfer = Transfer(TransferKind.DOWNLOAD, 100_000_000L, 400_000_000L, bytesPerSecond = 10_000_000L))
        val step = downloading.step(StepKind.PHOTO_MODEL)
        assertEquals(StepStatus.RUNNING, step.status)
        assertEquals(0.25f, step.fraction!!, 0.001f)
        assertTrue(step.detail, "left" in step.detail)

        val resumable = fresh.copy(photoPartialBytes = 100_000_000L).step(StepKind.PHOTO_MODEL)
        assertEquals(StepStatus.START, resumable.status)
        assertNotNull(resumable.fraction)
    }

    @Test
    fun `a paused download says so, with a Resume, and the chat's note leads there`() {
        val paused = fresh.copy(paused = true, photoPartialBytes = 100_000_000L)
        val photo = paused.step(StepKind.PHOTO_MODEL)
        assertEquals(StepStatus.PAUSED, photo.status)
        assertTrue(photo.resume)
        assertTrue(photo.detail, photo.detail.startsWith("Paused"))
        assertEquals("Download paused · tap to resume", paused.pill()!!.text)
        // Bytes left by a Cancel, with nothing paused, don't make it paused.
        assertEquals(StepStatus.START, paused.copy(paused = false).step(StepKind.PHOTO_MODEL).status)
    }

    @Test
    fun `photos are counted as they're indexed, and a warm phone pauses it`() {
        val indexing = fresh.copy(photoModel = photoModel, photos = GalleryIndex.Status(total = 4000, indexed = 1000, running = true))
        val step = indexing.step(StepKind.PHOTOS)
        assertEquals(StepStatus.RUNNING, step.status)
        assertEquals(0.25f, step.fraction!!, 0.001f)
        assertEquals(StepStatus.PAUSED, indexing.copy(photos = indexing.photos.copy(cooling = true)).step(StepKind.PHOTOS).status)
        assertEquals(StepStatus.DONE, indexing.copy(photos = GalleryIndex.Status(total = 4000, indexed = 4000)).step(StepKind.PHOTOS).status)
    }

    @Test
    fun `no photo steps without photos allowed, and no faces without Find people`() {
        assertTrue(fresh.copy(photosAllowed = false).steps().none { it.kind == StepKind.PHOTOS || it.kind == StepKind.PEOPLE })
        assertTrue(fresh.copy(findPeople = false).steps().none { it.kind == StepKind.PEOPLE })
    }

    @Test
    fun `the chat's note names what goes on behind it, and only what's worth one`() {
        val photoDownload = fresh.copy(photoTransfer = Transfer(TransferKind.DOWNLOAD, 50, 100))
        assertEquals("Downloading photo search · 50%", photoDownload.pill()!!.text)

        val indexing = fresh.copy(photoModel = photoModel, photos = GalleryIndex.Status(total = 4000, indexed = 1000, running = true))
        assertTrue(indexing.pill()!!.text.startsWith("Indexing photos"))
        // A few new photos, as the nightly pass does them: no note.
        assertNull(fresh.copy(photoModel = photoModel, photos = GalleryIndex.Status(total = 4000, indexed = 3990, running = true)).pill())
    }
}
