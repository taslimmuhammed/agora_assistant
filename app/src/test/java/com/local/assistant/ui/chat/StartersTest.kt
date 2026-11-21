package com.local.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StartersTest {

    @Test
    fun greetingFirstThenFourOfDifferentKinds() {
        repeat(50) { seed ->
            val picked = Starters.pick(Random(seed), hour = 9, web = true, photos = true, count = 4)
            assertEquals(5, picked.size)
            assertEquals("Hey, good morning!", picked.first().text)
            assertEquals(picked.size, picked.map { it.kind }.distinct().size)
        }
    }

    @Test
    fun nothingThatIsNotSetUp() {
        repeat(50) { seed ->
            val picked = Starters.pick(Random(seed), hour = 20, web = false, photos = false, health = false, count = 4)
            assertTrue(picked.all { it.needs == Starter.Needs.NOTHING })
        }
    }

    @Test
    fun atMostOnePlainChatAndTheRestItsOwn() {
        repeat(200) { seed ->
            val picked = Starters.pick(Random(seed), hour = 9, web = false, photos = false, count = 4).drop(1)
            assertEquals(4, picked.size)
            assertTrue(picked.count { it.kind.plainChat } <= 1)
        }
    }

    @Test
    fun everyKindTurnsUp() {
        // The new abilities among them: place reminders, lists, health, two at once.
        val seen = (0 until 400).flatMap { seed ->
            Starters.pick(Random(seed), hour = 9, web = true, photos = true, health = true, count = 4, calls = seed % 2 == 0).map { it.kind }
        }.toSet()
        assertEquals(Starter.Kind.entries.toSet(), seen)
    }

    @Test
    fun greetingFollowsTheClock() {
        assertEquals("Hey, good morning!", Starters.greeting(5).text)
        assertTrue(Starters.greeting(14).text.startsWith("Good afternoon"))
        assertEquals("Good evening!", Starters.greeting(21).text)
        assertEquals("Hey, still up?", Starters.greeting(2).text)
    }

    @Test
    fun noSuggestionReachesSomeoneElse() {
        // A tap sends at once: nothing that calls or messages a person.
        val reaching = Regex("""\b(call|text|message|whatsapp|send)\b""", RegexOption.IGNORE_CASE)
        // A call idea only rings Luna: the words are hers to say, and Luna asks before sending.
        assertFalse(Starters.ALL.any { it.kind != Starter.Kind.REMINDER && it.kind != Starter.Kind.CALL && reaching.containsMatchIn(it.text) })
    }

    @Test
    fun withCallsTwoTilesAreThingsToSayToLuna() {
        repeat(100) { seed ->
            val picked = Starters.pick(Random(seed), hour = 9, web = false, photos = false, count = 4, calls = true)
            assertEquals(5, picked.size)
            assertEquals(Starter.Kind.GREETING, picked.first().kind)
            assertEquals(Starters.CALL_TILES, picked.count { it.kind == Starter.Kind.CALL })
            assertTrue(picked.drop(1).take(Starters.CALL_TILES).all { it.kind == Starter.Kind.CALL })
            assertEquals(2, picked.drop(1).filter { it.kind != Starter.Kind.CALL }.map { it.kind }.distinct().size)
        }
    }

    @Test
    fun withoutCallsNoCallIdeas() {
        repeat(100) { seed ->
            assertTrue(Starters.pick(Random(seed), hour = 9, web = true, photos = true, health = true, count = 4).none { it.kind == Starter.Kind.CALL })
        }
    }
}
