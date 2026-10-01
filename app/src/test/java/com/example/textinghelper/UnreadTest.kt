package com.example.textinghelper

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadTest {
    private val now = 100 * DAY
    private fun thread(id: Long, vararg hoursAgo: Long) = UnreadThread(id, "P$id", "+1555", hoursAgo.map { now - it * HOUR }.sorted())
    private fun due(vararg t: UnreadThread, watermark: Map<Long, Long> = emptyMap(), delay: Int = 3) =
        dueUnread(t.toList(), now, delay, watermark).map { it.name }

    @Test fun remindsOnceTheFirstUnreadIsOldEnough() {
        assertEquals(listOf("P1"), due(thread(1, 4, 1)))   // first unread 4h ago
        assertEquals(emptyList<String>(), due(thread(2, 2))) // only 2h ago
        assertEquals(listOf("P2"), due(thread(2, 2), delay = 1))
    }

    @Test fun oneReminderPerBurstAndNewTextsRestartTheClock() {
        val t = thread(1, 5, 4)
        val reminded = mapOf(1L to now - 4 * HOUR) // already reminded up to the newest text
        assertEquals(emptyList<String>(), due(t, watermark = reminded))
        // They texted again 1h ago: wait until that one is 3h old.
        assertEquals(emptyList<String>(), due(thread(1, 5, 4, 1), watermark = reminded))
        assertEquals(listOf("P1"), due(thread(1, 8, 7, 4), watermark = mapOf(1L to now - 7 * HOUR)))
    }
}
