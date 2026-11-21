package com.local.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitingTextTest {

    @Test
    fun `a short wait says what is being done, a longer one counts its seconds`() {
        assertEquals("Thinking…", waitingText(null, shownAt = 0, now = 1_000))
        assertEquals("Reading the instructions…", waitingText("Reading the instructions…", shownAt = 0, now = 2_900))
        assertEquals("Reminder set · writing the reply · 4 s", waitingText("Reminder set · writing the reply…", shownAt = 0, now = 4_200))
    }
}
