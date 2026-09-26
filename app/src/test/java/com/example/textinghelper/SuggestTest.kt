package com.example.textinghelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {
    @Test fun promptSplitsRecentAndOlder() {
        val now = 100 * DAY
        val lines = (1..20).map { Line(now - (25 - it) * DAY, fromMe = it % 2 == 0, text = "msg$it") }
        val p = buildPrompt(lines, "Emily", unreplied = true, now = now)
        val older = p.indexOf("OLDER CONTEXT")
        val recent = p.indexOf("RECENT CONVERSATION")
        assertTrue(older in 0 until recent)
        // msg1-5 are older context, msg6-20 are the last 15.
        assertTrue(p.indexOf("msg5") in older until recent)
        assertTrue(p.indexOf("msg6") > recent)
        assertTrue("Me: msg20" in p)
        assertTrue("Them: msg19" in p)
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
        // 20 messages, each with a photo; #18 also has a video. Only the last 15 are "recent".
        val lines = (1..20).map { i ->
            Line(i * DAY, false, "m$i", listOf(Attachment("p$i", false)) + if (i == 18) listOf(Attachment("v18", true)) else emptyList())
        }
        assertEquals(listOf("v18", "p19", "p20"), pickMedia(lines).map { it.second.uri })
        // A photo only in the older section is ignored.
        val old = listOf(Line(0, true, "[photo]", listOf(Attachment("old", false)))) + (1..15).map { Line(it * DAY, true, "t") }
        assertEquals(emptyList<Attachment>(), pickMedia(old).map { it.second })
    }

    @Test fun parsesPlainFencedAndBroken() {
        val json = """{"suggestions":[{"text":"yo","angle":"reply"},{"text":"  ","angle":"check-in"},{"text":"dinner thu?","angle":"make-plans"}]}"""
        assertEquals(listOf(Suggestion("yo", "reply"), Suggestion("dinner thu?", "make-plans")), parseSuggestions(json))
        assertEquals(2, parseSuggestions("```json\n$json\n```").size)
        assertEquals(emptyList<Suggestion>(), parseSuggestions("sorry, I can't help"))
        assertEquals(emptyList<Suggestion>(), parseSuggestions("{not json}"))
    }
}
