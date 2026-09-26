package com.example.textinghelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleTest {
    @Test fun sampleKeepsOnlyMyRealTexts() {
        val lines = listOf(
            Line(1, true, "lol yeah"),
            Line(2, false, "their message"),
            Line(3, true, "Loved “see you sat”"),
            Line(4, true, "[photo]"),
            Line(5, true, "[photo] look at this"),
            Line(6, true, "  "),
        )
        assertEquals(listOf("lol yeah", "[photo] look at this"), styleSample(lines))
    }

    @Test fun verbatimOnlyDropsMadeUpExamples() {
        assertEquals(listOf("omw", "haha no"), verbatimOnly(listOf("omw ", "haha no", "I never said this", "omw"), listOf("omw", "haha no")))
    }

    @Test fun sectionIncludesOnlyWhatExists() {
        assertNull(styleSection(Style.FRIENDS, StyleProfile()))
        // Notes are rules now (system prompt), not part of this section.
        assertNull(styleSection(Style.FAMILY, StyleProfile(notes = "no emojis")))
        val built = styleSection(Style.FRIENDS, StyleProfile(description = "lowercase", examples = listOf("yo", "bet")))!!
        assertTrue("WITH FRIENDS" in built && "How I text: lowercase" in built && "- yo" in built && "- bet" in built)
        // An example with a banned word is left out.
        val filtered = styleSection(Style.FRIENDS, StyleProfile(examples = listOf("yo whats good", "bet"), banned = "yo"))!!
        assertTrue("yo whats good" !in filtered && "- bet" in filtered)
    }

    @Test fun promptPutsStyleFirst() {
        val p = buildPrompt(listOf(Line(0, true, "hi")), "Ben", unreplied = false, now = DAY, styleSection = "MY TEXTING STYLE WITH FRIENDS (baseline):\n- yo")
        assertTrue(p.indexOf("MY TEXTING STYLE") in 0 until p.indexOf("RECENT CONVERSATION"))
    }

    @Test fun unknownStyleIsFriends() {
        assertEquals(Style.FRIENDS, styleOf(null))
        assertEquals(Style.FAMILY, styleOf("family"))
    }
}
