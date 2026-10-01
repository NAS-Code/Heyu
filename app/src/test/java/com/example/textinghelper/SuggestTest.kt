package com.example.textinghelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {
    @Test fun promptSplitsRecentAndOlder() {
        val now = 100 * DAY
        val lines = (1..30).map { Line(now - (35 - it) * DAY, fromMe = it % 2 == 0, text = "msg$it") }
        val p = buildPrompt(lines, "Emily", unreplied = true, now = now)
        val older = p.indexOf("OLDER CONTEXT")
        val recent = p.indexOf("RECENT CONVERSATION")
        assertTrue(older in 0 until recent)
        // msg1-5 are older context, msg6-30 are the last 25.
        assertTrue(p.indexOf("msg5") in older until recent)
        assertTrue(p.indexOf("msg6") > recent)
        assertTrue("Me: msg30" in p)
        assertTrue("Them: msg29" in p)
        assertTrue("Days since last message: 5" in p)
        assertTrue("Last message sent by: me" in p)
        assertTrue("UNREPLIED" in p)
        assertTrue("Do NOT mention" in p) // 5 days quiet
    }

    @Test fun gapAllowedAfterSixWeeks() {
        val p = buildPrompt(listOf(Line(0, true, "hi")), "Ben", unreplied = false, now = 42 * DAY)
        assertTrue("over 6 weeks" in p)
        assertTrue("Do NOT mention" !in p)
    }

    @Test fun promptWithFewMessagesHasNoOlderSection() {
        val p = buildPrompt(listOf(Line(0, true, "hi")), "Ben", unreplied = false, now = DAY)
        assertTrue("OLDER CONTEXT" !in p)
        assertTrue("Me: hi" in p)
    }

    @Test fun picksNewestThreeMediaFromRecentOnly() {
        // 20 messages, each with a photo; #18 also has a video. All are within the recent 25.
        val lines = (1..20).map { i ->
            Line(i * DAY, false, "m$i", listOf(Attachment("p$i", false)) + if (i == 18) listOf(Attachment("v18", true)) else emptyList())
        }
        assertEquals(listOf("v18", "p19", "p20"), pickMedia(lines).map { it.second.uri })
        // A photo only in the older section is ignored.
        val old = listOf(Line(0, true, "[photo]", listOf(Attachment("old", false)))) + (1..25).map { Line(it * DAY, true, "t") }
        assertEquals(emptyList<Attachment>(), pickMedia(old).map { it.second })
    }

    @Test fun bannedWordsAreWholeWordAnyCase() {
        val banned = listOf("yo", "no worries")
        assertEquals("yo", bannedIn("Yo what's up", banned))
        assertEquals("yo", bannedIn("ok yo!", banned))
        assertEquals(null, bannedIn("you coming? your call", banned)) // "yo" inside a word doesn't count
        assertEquals("no worries", bannedIn("No worries at all", banned))
        assertEquals(null, bannedIn("anything", emptyList()))
    }

    @Test fun rulesGoInSystemPrompt() {
        val plain = systemPrompt("", emptyList())
        assertTrue("USER'S RULES (always" !in plain)
        val s = systemPrompt("dont use yo\n\nkeep it short", listOf("yo", "bro"))
        assertTrue(s.startsWith(plain))
        assertTrue("- dont use yo\n- keep it short\n- Never use these words or phrases: yo, bro" in s)
    }

    @Test fun parsesRecap() {
        assertEquals("saw akira sat; nothing open", parseRecap("""{"recap":" saw akira sat; nothing open ","suggestions":[]}"""))
        assertEquals("", parseRecap("nope"))
    }

    @Test fun parsesPlainFencedAndBroken() {
        val json = """{"suggestions":[{"text":"yo","angle":"reply"},{"text":"  ","angle":"check-in"},{"text":"dinner thu?","angle":"make-plans"}]}"""
        assertEquals(listOf(Suggestion("yo", "reply"), Suggestion("dinner thu?", "make-plans")), parseSuggestions(json))
        assertEquals(2, parseSuggestions("```json\n$json\n```").size)
        assertEquals(emptyList<Suggestion>(), parseSuggestions("sorry, I can't help"))
        assertEquals(emptyList<Suggestion>(), parseSuggestions("{not json}"))
    }

    @Test fun stripsOnlyALeadingLaugh() {
        assertEquals("It's been a minute!", stripLeadingLaugh("Hahaha it's been a minute!"))
        assertEquals("we should get food", stripLeadingLaugh("lol we should get food"))
        assertEquals("Dinner thu?", stripLeadingLaugh("HAHAHA 😂 dinner thu?"))
        assertEquals("that was so funny haha", stripLeadingLaugh("that was so funny haha")) // only the start
        assertEquals("Holy cow", stripLeadingLaugh("Holy cow"))                           // "Ho" isn't a laugh
        assertEquals("lollipop time", stripLeadingLaugh("lollipop time"))                 // part of a word
        assertEquals(null, stripLeadingLaugh("hahaha"))                                  // nothing left
    }

    @Test fun laughCounts() {
        assertTrue(LAUGH.containsMatchIn("Your ex misses you lol"))
        assertTrue(LAUGH.containsMatchIn("Fuck economics James 😂😂"))
        assertTrue(!LAUGH.containsMatchIn("Hell yeah congrats king!"))
        assertTrue(!LAUGH.containsMatchIn("Holy cow, lollipop"))
    }
}
