package com.example.textinghelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DueTest {
    private val now = 1_000 * DAY

    private fun setting(id: Long, freq: Int?, reminded: Long? = null, snoozed: Long? = null, handled: Long? = null) =
        ContactSetting(id, "+1555000$id", "P$id", freq, snoozed, reminded, handled)

    private fun stats(id: Long, lastOutDaysAgo: Long?, lastDaysAgo: Long, lastFromMe: Boolean) =
        ContactStats(id, "P$id", "+1555000$id", 10, 10, now - lastDaysAgo * DAY,
            lastOutDaysAgo?.let { now - it * DAY }, lastFromMe)

    // Most tests use a fixed 2-day reply wait (the original rule); replyWaitScalesWithCadence covers the default.
    private fun names(settings: List<ContactSetting>, vararg st: ContactStats, vary: Boolean = false, rotate: Boolean = false,
                      replyDays: Int? = 2) =
        findDue(settings, st.associateBy { it.contactId }, now, vary, rotate, replyDays).map { it.setting.name }

    @Test fun dueWhenPastFrequency() {
        assertEquals(listOf("P1"), names(listOf(setting(1, 7)), stats(1, 7, 7, true)))
        assertEquals(emptyList<String>(), names(listOf(setting(1, 7)), stats(1, 6, 6, true)))
    }

    @Test fun ignoredAndSnoozedSkipped() {
        assertEquals(emptyList<String>(), names(listOf(setting(1, null)), stats(1, 30, 30, true)))
        assertEquals(emptyList<String>(), names(listOf(setting(1, 7, snoozed = now + DAY)), stats(1, 30, 30, true)))
    }

    @Test fun unrepliedAfterTwoDaysAndFirst() {
        val s = listOf(setting(1, 7), setting(2, 90))
        // P1 is 23 days overdue; P2 texted me 3 days ago -> P2 comes first.
        assertEquals(listOf("P2", "P1"), names(s, stats(1, 30, 30, true), stats(2, 10, 3, false)))
        // They texted 1 day ago: no reminder at all yet, even though I'm overdue.
        assertEquals(emptyList<String>(), names(listOf(setting(2, 7)), stats(2, 30, 1, false)))
    }

    @Test fun mostOverdueFirst() {
        val s = listOf(setting(1, 30), setting(2, 7))
        // P1: 35 days vs 30 (5 over). P2: 20 vs 7 (13 over).
        assertEquals(listOf("P2", "P1"), names(s, stats(1, 35, 35, true), stats(2, 20, 20, true)))
    }

    @Test fun remindedAgainEveryDayUntilHandled() {
        // Reminded yesterday, still haven't texted: due again today.
        assertEquals(listOf("P1"), names(listOf(setting(1, 7, reminded = now - DAY)), stats(1, 10, 10, true)))
        // Same for an unreplied reminder.
        assertEquals(listOf("P1"), names(listOf(setting(1, 7, reminded = now - DAY)), stats(1, 1, 3, false)))
    }

    @Test fun doneResetsClockAndClearsUnreplied() {
        val s = listOf(setting(1, 7, handled = now - DAY))
        assertEquals(emptyList<String>(), names(s, stats(1, 30, 5, false)))
    }

    @Test fun upcomingSoonestFirstSkipsDueAndIgnored() {
        val s = listOf(setting(1, 30), setting(2, 7), setting(3, 7), setting(4, null), setting(5, 7, snoozed = now + 3 * DAY))
        val st = arrayOf(stats(1, 10, 10, true), stats(2, 5, 5, true), stats(3, 9, 9, true), stats(4, 1, 1, true), stats(5, 20, 20, true))
        val up = upcoming(s, st.associateBy { it.contactId }, now)
        // P2 due in 2 days, P5 snoozed 3 days, P1 in 20. P3 is already due, P4 ignored.
        assertEquals(listOf("P2" to 2L, "P5" to 3L, "P1" to 20L), up.map { it.setting.name to it.inDays })
    }

    @Test fun neverTextedIsDue() {
        assertEquals(listOf("P1"), names(listOf(setting(1, 30)), stats(1, null, 100, true)))
        assertEquals(listOf("P1"), names(listOf(setting(1, 30))))
    }

    @Test fun jitterSizesByCadence() {
        val rnd = kotlin.random.Random(1)
        repeat(200) {
            assertEquals(0, jitterFor(3, rnd))
            assertEquals(1, kotlin.math.abs(jitterFor(14, rnd)))
            assertTrue(kotlin.math.abs(jitterFor(30, rnd)) in 2..3)
            assertTrue(kotlin.math.abs(jitterFor(90, rnd)) in 2..3)
        }
    }

    @Test fun jitterAlternatesPerCycle() {
        val rnd = kotlin.random.Random(2)
        var s = setting(1, 14)
        val shifted = (1..6).map { cycle ->
            s = rollJitter(s, cycleStart = cycle * 100L, rnd)!!
            assertEquals(null, rollJitter(s, cycle * 100L, rnd)) // same cycle: decided once
            s.jitterDays != 0
        }
        assertEquals(listOf(true, false, true, false, true, false), shifted)
    }

    @Test fun varyShiftsDueDate() {
        // Weekly, last texted 7 days ago. Delayed a day: not due yet. Setting off: due.
        val s = listOf(setting(1, 7).copy(jitterDays = 1))
        val st = stats(1, 7, 7, true)
        assertEquals(emptyList<String>(), names(s, st, vary = true))
        assertEquals(listOf("P1"), names(s, st, vary = false))
        // Shortened a day: due at 6 days.
        assertEquals(listOf("P1"), names(listOf(setting(1, 7).copy(jitterDays = -1)), stats(1, 6, 6, true), vary = true))
    }

    @Test fun lastCheckStatusExplainsWhy() {
        assertEquals("sent 0", checkStatus(emptyList(), 0, 0))
        assertEquals("sent 2: Ben, Emily · 1 more over the daily cap · no suggestion for 1 (AI failed)",
            checkStatus(listOf("Ben", "Emily"), 1, 1))
    }

    @Test fun nobodyNotificationText() {
        assertEquals("You're all caught up.", nobodyText(null, null))
        assertEquals("You're all caught up. Next up: Ben tomorrow.", nobodyText("Ben", 1))
        assertEquals("You're all caught up. Next up: Emily in 4 days.", nobodyText("Emily", 4))
    }

    @Test fun rotateTakesTurns() {
        // P1 most overdue, P3 least. P1 and P2 were reminded yesterday, P3 two days ago, P4 never.
        val s = listOf(setting(1, 7, reminded = now - DAY), setting(2, 7, reminded = now - DAY),
            setting(3, 7, reminded = now - 2 * DAY), setting(4, 7))
        val st = arrayOf(stats(1, 30, 30, true), stats(2, 20, 20, true), stats(3, 10, 10, true), stats(4, 15, 15, true))
        assertEquals(listOf("P1", "P2", "P4", "P3"), names(s, *st))                 // most overdue first
        assertEquals(listOf("P4", "P3", "P1", "P2"), names(s, *st, rotate = true))  // longest since reminded first
        // Unreplied still beats due when rotating, even if reminded more recently.
        val u = listOf(setting(1, 7), setting(5, 7, reminded = now - DAY))
        assertEquals(listOf("P5", "P1"), names(u, stats(1, 30, 30, true), stats(5, 1, 3, false), rotate = true))
    }

    @Test fun replyWaitScalesWithCadence() {
        assertEquals(DAY * 7 / 2, replyWait(7, null))   // Weekly: 3.5 days
        assertEquals(7 * DAY, replyWait(14, null))      // Biweekly: 7
        assertEquals(7 * DAY, replyWait(90, null))      // Quarterly: capped at 7
        assertEquals(DAY, replyWait(1, null))           // floor of 1 day
        assertEquals(2 * DAY, replyWait(90, 2))         // fixed overrides
        // Quarterly friend texted 3 days ago: not yet with the default (7 days), yes with a fixed 2.
        val s = listOf(setting(1, 90))
        assertEquals(emptyList<String>(), names(s, stats(1, 10, 3, false), replyDays = null))
        assertEquals(listOf("P1"), names(s, stats(1, 10, 3, false), replyDays = 2))
        // Weekly friend texted 4 days ago: past 3.5 days, so reminded.
        assertEquals(listOf("P1"), names(listOf(setting(1, 7)), stats(1, 10, 4, false), replyDays = null))
    }
}
