package com.example.textinghelper

// Pure logic, no Android APIs, so it can be unit tested on the PC (see DueTest.kt).

import kotlin.random.Random

const val DAY = 86_400_000L

data class Reminder(
    val setting: ContactSetting,
    val unreplied: Boolean,
    val days: Long?, // unreplied: days since their text. due: days since mine (null = never texted)
)

/** Everyone who should be reminded right now, most urgent first. The caller applies the daily cap. */
fun findDue(
    settings: List<ContactSetting>,
    stats: Map<Long, ContactStats>,
    now: Long,
    ignoreRecent: Boolean = false, // "Run check now" skips the 3-day rule so you can test repeatedly
    vary: Boolean = false, // apply each person's jitterDays (the "vary timing" setting)
): List<Reminder> {
    val unreplied = ArrayList<Pair<Reminder, Long>>() // sort key: when they texted (oldest first)
    val due = ArrayList<Pair<Reminder, Long>>() // sort key: days past their frequency (biggest first)

    for (s in settings) {
        val freq = s.frequencyDays ?: continue
        if ((s.snoozedUntil ?: 0) > now) continue
        val st = stats[s.contactId]
        val handled = s.handledAt ?: 0
        val lastOut = maxOf(st?.lastOutgoing ?: 0, handled) // 0 = never
        val remindedAt = s.lastReminded ?: 0
        // No repeat within 3 days, or within the frequency if that's shorter (e.g. a 1-day custom tier).
        val recentlyReminded = !ignoreRecent && now - remindedAt < minOf(3, freq) * DAY

        // They sent the last message and I haven't replied (or tapped Done since).
        val theirText = st?.takeIf { !it.lastFromMe && it.lastDate > handled }?.lastDate
        if (theirText != null) {
            val waiting = now - theirText
            // Under 2 days: give me time to reply; don't nag with a "due" reminder either.
            // Recently reminded: skip unless they've texted again since.
            if (waiting > 2 * DAY && !(recentlyReminded && theirText <= remindedAt))
                unreplied += Reminder(s, true, waiting / DAY) to theirText
            continue
        }

        val daysSince = (now - lastOut) / DAY
        // Recently reminded: skip unless I've texted/tapped Done since and they're due again.
        val effective = if (vary) freq + s.jitterDays else freq
        if (daysSince >= effective && !(recentlyReminded && lastOut <= remindedAt))
            due += Reminder(s, false, if (lastOut == 0L) null else daysSince) to daysSince - effective
    }
    return unreplied.sortedBy { it.second }.map { it.first } +
        due.sortedByDescending { it.second }.map { it.first }
}

data class Upcoming(val setting: ContactSetting, val inDays: Long)

/** Opted-in people who aren't due yet, soonest first. Snoozed people count from when the snooze ends. */
fun upcoming(settings: List<ContactSetting>, stats: Map<Long, ContactStats>, now: Long, vary: Boolean = false): List<Upcoming> =
    settings.mapNotNull { s ->
        val freq = (s.frequencyDays ?: return@mapNotNull null) + if (vary) s.jitterDays else 0
        val lastOut = lastOut(s, stats[s.contactId])
        if (lastOut == 0L) return@mapNotNull null // never texted: due now
        val dueAt = maxOf(lastOut + freq * DAY, s.snoozedUntil ?: 0)
        if (dueAt <= now) null else Upcoming(s, (dueAt - now + DAY - 1) / DAY) // round up: "in 1 day", not "in 0"
    }.sortedBy { it.inDays }

/** When my current cycle with this person started: my last text, or tapping Done. 0 = never. */
fun lastOut(s: ContactSetting, st: ContactStats?) = maxOf(st?.lastOutgoing ?: 0, s.handledAt ?: 0)

/** Days to shift a cadence, randomly earlier or later. 1+ week: 1 day. 1+ month: 2-3 days. Shorter: none. */
fun jitterFor(freq: Int, rnd: Random): Int {
    val size = when {
        freq >= 30 -> rnd.nextInt(2, 4)
        freq >= 7 -> 1
        else -> return 0
    }
    return if (rnd.nextBoolean()) size else -size
}

/**
 * Decides the shift once per cycle, alternating: shifted, normal, shifted, ... so the gap between
 * reminders isn't the same every time. Returns the updated setting, or null if nothing changes.
 */
fun rollJitter(s: ContactSetting, cycleStart: Long, rnd: Random): ContactSetting? {
    val freq = s.frequencyDays ?: return null
    if (s.jitterCycle == cycleStart) return null // already decided for this cycle
    return s.copy(jitterCycle = cycleStart, jitterDays = if (s.jitterDays != 0) 0 else jitterFor(freq, rnd))
}
