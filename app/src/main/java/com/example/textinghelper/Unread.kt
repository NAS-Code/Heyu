package com.example.textinghelper

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Unread reminders: a nudge to open texts you haven't read yet. No suggestion, no Claude call.

const val HOUR = 3_600_000L
const val UNREAD_MAX_AGE = 7 * DAY // older unread texts are ignored (e.g. old messages restored as unread)
private const val SCAN_EVERY = 15 * 60_000L // Android doesn't announce new RCS messages, so we look

val Context.unreadOn get() = prefs().getBoolean("unreadOn", false)
val Context.unreadDelayOn get() = prefs().getBoolean("unreadDelayOn", true)
val Context.unreadDelayHours get() = prefs().getInt("unreadDelayHours", 3)
val Context.unreadDailyOn get() = prefs().getBoolean("unreadDailyOn", true)
val Context.unreadHour get() = prefs().getInt("unreadHour", 12)
val Context.unreadMinute get() = prefs().getInt("unreadMinute", 0)
val Context.quietOn get() = prefs().getBoolean("quietOn", true)
val Context.quietStart get() = prefs().getInt("quietStart", 22 * 60) // minutes after midnight
val Context.quietEnd get() = prefs().getInt("quietEnd", 8 * 60)

/** Is [minute] (of the day) inside quiet hours? Handles ranges past midnight. Pure, so it's unit tested. */
fun inQuietHours(minute: Int, start: Int, end: Int): Boolean =
    if (start <= end) minute in start until end else minute >= start || minute < end

data class UnreadThread(val threadId: Long, val name: String, val phone: String, val dates: List<Long>) {
    val oldest get() = dates.min()
}

/** Unread texts from saved contacts in 1:1 chats, received in the last 7 days. */
fun readUnread(ctx: Context, now: Long = System.currentTimeMillis()): List<UnreadThread> {
    val since = now - UNREAD_MAX_AGE
    val dates = HashMap<Long, MutableList<Long>>()
    ctx.query("content://sms", arrayOf("thread_id", "date"), "read=0 AND type=1 AND date>=$since") {
        dates.getOrPut(it.getLong(0)) { mutableListOf() } += it.getLong(1)
    }
    ctx.query("content://mms", arrayOf("thread_id", "date"), "read=0 AND msg_box=1 AND date>=${since / 1000}") {
        dates.getOrPut(it.getLong(0)) { mutableListOf() } += it.getLong(1) * 1000 // MMS dates are seconds
    }
    if (dates.isEmpty()) return emptyList()
    val addr = threadAddresses(ctx)
    val contacts = contactsByNumber(ctx)
    return dates.mapNotNull { (thread, ds) ->
        val phone = addr[thread] ?: return@mapNotNull null // group chat
        val (_, name) = contacts[normalize(phone)] ?: return@mapNotNull null // not a saved contact
        UnreadThread(thread, name, phone, ds.sorted())
    }.sortedBy { it.oldest }
}

/**
 * Threads to remind about now: the first unread text since the last reminder (the [watermark]) is at least
 * [delayHours] old. So a burst of texts gets one reminder, and a new text after a reminder restarts the clock.
 * Pure, so it's unit tested.
 */
fun dueUnread(threads: List<UnreadThread>, now: Long, delayHours: Int, watermark: Map<Long, Long>): List<UnreadThread> =
    threads.filter { t ->
        val firstNew = t.dates.firstOrNull { it > (watermark[t.threadId] ?: 0) } ?: return@filter false
        now - firstNew >= delayHours * HOUR
    }

// ---- Alarms ----

private fun unreadAlarm(ctx: Context, kind: String) = PendingIntent.getBroadcast(ctx, if (kind == "scan") 1 else 2,
    Intent(ctx, UnreadAlarm::class.java).putExtra("kind", kind), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** Arms (or cancels) the 15-minute scan and the daily unread reminder to match Settings. Safe to call any time. */
fun scheduleUnread(ctx: Context) {
    val am = ctx.getSystemService(AlarmManager::class.java)
    fun arm(kind: String, at: Long) =
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, unreadAlarm(ctx, kind))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, unreadAlarm(ctx, kind))

    if (ctx.unreadOn && ctx.unreadDelayOn) arm("scan", System.currentTimeMillis() + SCAN_EVERY) else am.cancel(unreadAlarm(ctx, "scan"))
    if (ctx.unreadOn && ctx.unreadDailyOn) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(ctx.unreadHour, ctx.unreadMinute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        arm("daily", next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    } else am.cancel(unreadAlarm(ctx, "daily"))
}

class UnreadAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind")
        val pending = goAsync() // the scan is a couple of quick queries
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val threads = readUnread(ctx)
                if (kind == "scan") scanUnread(ctx, threads) else if (threads.isNotEmpty()) notifyUnreadSummary(ctx, threads)
            } finally {
                scheduleUnread(ctx) // next scan / tomorrow's daily
                pending.finish()
            }
        }
    }
}

/** Posts a reminder per thread that's been unread long enough; clears ones you've since read. */
private fun scanUnread(ctx: Context, threads: List<UnreadThread>) {
    val p = ctx.prefs()
    // threadId -> newest unread text already reminded about. Threads no longer unread are forgotten.
    val watermark = p.getStringSet("unreadWatermark", emptySet())!!.mapNotNull { e ->
        e.split(':').takeIf { it.size == 2 }?.let { (t, d) -> t.toLongOrNull()?.let { it to (d.toLongOrNull() ?: 0) } }
    }.toMap().filterKeys { id -> threads.any { it.threadId == id } }

    val nm = ctx.getSystemService(NotificationManager::class.java)
    val unreadIds = threads.map { it.threadId }.toSet()
    p.getStringSet("unreadPosted", emptySet())!!.mapNotNull { it.toLongOrNull() }
        .filter { it !in unreadIds }.forEach { nm.cancel(unreadNotifId(it)) } // read since: clear it

    val now = System.currentTimeMillis()
    // Quiet hours: hold new reminders (the watermark isn't advanced, so they go out at the first scan after).
    val quiet = ctx.quietOn && LocalTime.now().let { inQuietHours(it.hour * 60 + it.minute, ctx.quietStart, ctx.quietEnd) }
    val due = if (quiet) emptyList() else dueUnread(threads, now, ctx.unreadDelayHours, watermark)
    due.forEach { notifyUnread(ctx, it, now) }
    p.edit()
        .putStringSet("unreadWatermark", (watermark + due.associate { it.threadId to it.dates.max() }).map { "${it.key}:${it.value}" }.toSet())
        .putStringSet("unreadPosted", unreadIds.map { it.toString() }.toSet())
        .apply()
}

private fun unreadNotifId(threadId: Long) = -(1000 + threadId).toInt() // negative: contact reminders use contact ids
private const val UNREAD_SUMMARY_ID = -3

private fun ago(now: Long, t: Long) = ((now - t) / HOUR).let { h -> if (h < 24) "${h}h ago" else "${h / 24}d ago" }

private fun openThread(ctx: Context, phone: String) = PendingIntent.getActivity(ctx, phone.hashCode(),
    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")), PendingIntent.FLAG_IMMUTABLE)

private fun builder(ctx: Context): Notification.Builder {
    ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
        android.app.NotificationChannel("unread", "Unread texts", NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
    return Notification.Builder(ctx, "unread").setSmallIcon(R.drawable.ic_notification).setColor(0xFFFF7518.toInt()).setAutoCancel(true)
}

private fun notifyUnread(ctx: Context, t: UnreadThread, now: Long) {
    val n = t.dates.size
    ctx.getSystemService(NotificationManager::class.java).notify(unreadNotifId(t.threadId), builder(ctx)
        .setContentTitle("Unread text from ${t.name}")
        .setContentText("${if (n == 1) "1 unread text" else "$n unread texts"} · first ${ago(now, t.oldest)}")
        .setContentIntent(openThread(ctx, t.phone))
        .build())
}

private fun notifyUnreadSummary(ctx: Context, threads: List<UnreadThread>) {
    val now = System.currentTimeMillis()
    val lines = threads.map { "${it.name} · ${it.dates.size} unread · ${ago(now, it.oldest)}" }
    val style = Notification.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
    val messages = ctx.packageManager.getLaunchIntentForPackage(Telephony.Sms.getDefaultSmsPackage(ctx) ?: "")
    ctx.getSystemService(NotificationManager::class.java).notify(UNREAD_SUMMARY_ID, builder(ctx)
        .setContentTitle(if (threads.size == 1) "1 person is waiting on you" else "${threads.size} people are waiting on you")
        .setContentText(threads.joinToString { it.name })
        .setStyle(style)
        .apply { if (messages != null) setContentIntent(PendingIntent.getActivity(ctx, 0, messages, PendingIntent.FLAG_IMMUTABLE)) }
        .build())
}

// ---- Settings UI ----

@Composable
fun UnreadSettings() {
    val ctx = LocalContext.current
    val p = ctx.prefs()
    var on by remember { mutableStateOf(ctx.unreadOn) }
    var delayOn by remember { mutableStateOf(ctx.unreadDelayOn) }
    var hours by remember { mutableIntStateOf(ctx.unreadDelayHours) }
    var dailyOn by remember { mutableStateOf(ctx.unreadDailyOn) }
    var hour by remember { mutableIntStateOf(ctx.unreadHour) }
    var minute by remember { mutableIntStateOf(ctx.unreadMinute) }
    fun save(block: android.content.SharedPreferences.Editor.() -> Unit) { p.edit().apply(block).apply(); scheduleUnread(ctx) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Remind me about unread texts")
            Text("Saved contacts, 1:1 chats, unread in the last 7 days. Just a nudge, no suggested reply.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, { on = it; save { putBoolean("unreadOn", it) } })
    }
    if (!on) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$hours ${if (hours == 1) "hour" else "hours"} after a text arrives", Modifier.weight(1f))
        TextButton(enabled = delayOn, onClick = { hours = (hours - 1).coerceIn(1, 12); save { putInt("unreadDelayHours", hours) } }) { Text("−") }
        TextButton(enabled = delayOn, onClick = { hours = (hours + 1).coerceIn(1, 12); save { putInt("unreadDelayHours", hours) } }) { Text("+") }
        Switch(delayOn, { delayOn = it; save { putBoolean("unreadDelayOn", it) } })
    }
    var quietOn by remember { mutableStateOf(ctx.quietOn) }
    var quietStart by remember { mutableIntStateOf(ctx.quietStart) }
    var quietEnd by remember { mutableIntStateOf(ctx.quietEnd) }
    fun fmtMin(m: Int) = LocalTime.of(m / 60, m % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    fun pickMin(current: Int, onPick: (Int) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, current / 60, current % 60, false).show()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Quiet hours")
            Text("No \"hours after\" reminders in this window; they wait until it ends.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(quietOn, { quietOn = it; save { putBoolean("quietOn", it) } })
    }
    if (quietOn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickMin(quietStart) { quietStart = it; save { putInt("quietStart", it) } } }) { Text(fmtMin(quietStart)) }
        Text("to")
        OutlinedButton(onClick = { pickMin(quietEnd) { quietEnd = it; save { putInt("quietEnd", it) } } }) { Text(fmtMin(quietEnd)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Daily unread reminder", Modifier.weight(1f))
        OutlinedButton(enabled = dailyOn, onClick = {
            TimePickerDialog(ctx, { _, h, m -> hour = h; minute = m; save { putInt("unreadHour", h).putInt("unreadMinute", m) } },
                hour, minute, false).show()
        }) { Text(LocalTime.of(hour, minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
        Spacer(Modifier.width(8.dp))
        Switch(dailyOn, { dailyOn = it; save { putBoolean("unreadDailyOn", it) } })
    }
}
