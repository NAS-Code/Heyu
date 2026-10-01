package com.example.textinghelper

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

// Plain SharedPreferences (a small key-value file) for non-secret settings.
fun Context.prefs() = getSharedPreferences("prefs", Context.MODE_PRIVATE)
val Context.reminderHour get() = prefs().getInt("hour", 18)
val Context.reminderMinute get() = prefs().getInt("minute", 0)
val Context.dailyCap get() = prefs().getInt("cap", 3)
val Context.varyTiming get() = prefs().getBoolean("vary", true)
val Context.rotateReminders get() = prefs().getBoolean("rotate", true)
val Context.showRecap get() = prefs().getBoolean("showRecap", true)
val Context.showPromptButton get() = prefs().getBoolean("showPrompt", true)

/**
 * Reads messages, picks who's due, posts notifications. Returns a summary for the "Run check now" button.
 * [daily] = the scheduled check: it also records its status for Settings and says so when nobody's due.
 */
suspend fun runCheck(ctx: Context, daily: Boolean = false): String = withContext(Dispatchers.IO) {
    val dao = AppDb.get(ctx).dao()
    val now = System.currentTimeMillis()
    val stats = readDiagnostic(ctx).contacts.associateBy { it.contactId }
    if (ctx.varyTiming) for (s in dao.list()) rollJitter(s, lastOut(s, stats[s.contactId]), Random.Default)?.let { dao.save(it) }
    val all = findDue(dao.list(), stats, now, vary = ctx.varyTiming, rotate = ctx.rotateReminders)
    val picks = all.take(ctx.dailyCap)
    val aiErrors = mutableListOf<String>()
    var tokensIn = 0
    var tokensOut = 0
    for (r in picks) {
        // No key or API failure: still send the reminder, just without a suggestion.
        val result = if (ctx.apiKey == null) null else try {
            suggest(ctx, r, stats[r.setting.contactId]?.threadIds.orEmpty())
        } catch (e: Exception) {
            aiErrors += "${r.setting.name}: ${e.message}"
            null
        }
        if (result != null && result.suggestions.isEmpty()) aiErrors += "${r.setting.name}: couldn't parse reply"
        result?.let { tokensIn += it.inputTokens; tokensOut += it.outputTokens }
        notify(ctx, r, result?.suggestions.orEmpty(), result?.recap.orEmpty())
        dao.save(r.setting.copy(lastReminded = now))
        dao.log(ReminderLog(contactId = r.setting.contactId, time = now, reason = if (r.unreplied) "unreplied" else "due",
            inputTokens = result?.inputTokens, outputTokens = result?.outputTokens, images = result?.images))
    }
    // The real daily check (not "Run check now") records what it did, shown under the reminder time in Settings.
    if (daily) ctx.saveLastCheck(checkStatus(picks.map { it.setting.name }, all.size - picks.size, aiErrors.size))
    // The daily check always says something, so a quiet day doesn't look like a broken app.
    if (daily && picks.isEmpty()) {
        val next = upcoming(dao.list(), stats, now, ctx.varyTiming).firstOrNull()
        notifyNobody(ctx, nobodyText(next?.setting?.name, next?.inDays))
    }
    "${all.size} due, sent ${picks.size}: " + picks.joinToString { it.setting.name }.ifEmpty { "nobody" } +
        (if (ctx.apiKey == null) "\nNo API key set, so no suggestions." else "") +
        (if (tokensIn > 0) "\nAI usage: ${"%,d".format(tokensIn)} in / ${"%,d".format(tokensOut)} out tokens (~$${"%.3f".format(costUsd(tokensIn, tokensOut))})" else "") +
        aiErrors.joinToString("") { "\nAI failed for $it" }
}

class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            runCheck(applicationContext, daily = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            applicationContext.saveLastCheck("failed: ${e.message}")
        }
        return Result.success()
    }
}

/** One line for Settings: what the last daily check did and why. Pure, so it's unit tested. */
fun checkStatus(sent: List<String>, overCap: Int, aiFailed: Int): String =
    listOfNotNull(
        if (sent.isEmpty()) "sent 0" else "sent ${sent.size}: ${sent.joinToString()}",
        overCap.takeIf { it > 0 }?.let { "$it more over the daily cap" },
        aiFailed.takeIf { it > 0 }?.let { "no suggestion for $it (AI failed)" },
    ).joinToString(" · ")

fun Context.saveLastCheck(status: String) =
    prefs().edit().putLong("lastCheckAt", System.currentTimeMillis()).putString("lastCheck", status).apply()

/**
 * Arms an exact alarm for the next reminder time (today if it's still ahead, else tomorrow). Exact alarms fire
 * on time even when the phone is idle; WorkManager's periodic jobs could be held for hours overnight and then
 * run the moment the app was opened. Safe to call any time: it replaces the pending alarm.
 */
fun scheduleDaily(ctx: Context) {
    WorkManager.getInstance(ctx).cancelUniqueWork("daily") // the old periodic job, from earlier versions
    val now = LocalDateTime.now()
    var next = now.toLocalDate().atTime(ctx.reminderHour, ctx.reminderMinute)
    if (!next.isAfter(now)) next = next.plusDays(1)
    val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val alarm = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, DailyAlarm::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val am = ctx.getSystemService(AlarmManager::class.java)
    // USE_EXACT_ALARM is granted automatically; the fallback only matters if a future Android revokes it.
    if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
}

/**
 * The daily alarm: runs the check (as WorkManager work, since it can take longer than a receiver is allowed)
 * and arms tomorrow's alarm. Also re-arms after a reboot or app update, which clear alarms.
 */
class DailyAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == null) WorkManager.getInstance(ctx).enqueueUniqueWork("dailyRun", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DailyWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        scheduleDaily(ctx)
    }
}

private const val CHANNEL = "reminders2"

fun reasonText(r: Reminder) = when {
    r.unreplied -> "They texted you ${r.days} days ago"
    r.days == null -> "You haven't texted them yet · ${tierLabel(r.setting.frequencyDays)}"
    else -> "Last texted ${r.days} days ago · ${tierLabel(r.setting.frequencyDays)}"
}

private fun channel(ctx: Context): NotificationManager {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    // A channel's sound/vibration settings are fixed once created, so turning vibration on needs a new
    // channel id. The old one ("reminders") had vibration off.
    nm.deleteNotificationChannel("reminders")
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
    return nm
}

/** Body of the "nobody to text" notification. Pure, so it's unit tested. */
fun nobodyText(nextName: String?, nextInDays: Long?): String = listOfNotNull(
    "You're all caught up.",
    nextName?.let { "Next up: $it ${if (nextInDays == 1L) "tomorrow" else "in $nextInDays days"}." },
).joinToString(" ")

private const val HEYU_ORANGE = 0xFFFF7518.toInt() // logo background, see docs/logo
private const val NOBODY_ID = 0 // contact ids are positive, the test notification is -1
private const val SUMMARY_CHANNEL = "summary"

private fun notifyNobody(ctx: Context, text: String) {
    val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    // Its own low-importance channel: shows up without sound or vibration, and can be turned off separately.
    nm.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, "Daily summary", NotificationManager.IMPORTANCE_LOW))
    nm.notify(NOBODY_ID, Notification.Builder(ctx, SUMMARY_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(HEYU_ORANGE)
        .setContentTitle("Nobody to text today")
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build())
}

fun notify(ctx: Context, r: Reminder, suggestions: List<Suggestion>, recap: String = "") {
    val nm = channel(ctx)
    val s = r.setting
    val id = s.contactId.toInt()
    val title = (if (r.unreplied) "Reply to " else "Text ") + s.name
    val text = reasonText(r)
    val first = suggestions.firstOrNull()?.text

    // Each PendingIntent gets a unique URI (th://action/contactId) so Android doesn't merge them.
    fun uri(action: String) = Uri.parse("th://$action/${s.contactId}")
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    fun broadcast(action: String) = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, ActionReceiver::class.java).setData(uri(action)), flags)
    val textIntent = PendingIntent.getActivity(ctx, 0, textIntent(ctx, s.contactId, s.phone, body = first), flags)
    // Tapping the notification body: all suggestions if we have them, otherwise just the app.
    val open = if (suggestions.isEmpty()) Intent(ctx, MainActivity::class.java)
    else Intent(ctx, SuggestionsActivity::class.java)
        .setData(uri("suggestions"))
        .putExtra("id", s.contactId).putExtra("name", s.name).putExtra("phone", s.phone)
        .putExtra("title", title).putExtra("reason", text)
        .putExtra("texts", suggestions.map { it.text }.toTypedArray())
        .putExtra("angles", suggestions.map { it.angle }.toTypedArray())
        .putExtra("recap", recap)
    val openPi = PendingIntent.getActivity(ctx, 0, open, flags)

    val n = Notification.Builder(ctx, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(HEYU_ORANGE)
        .setContentTitle(title)
        .setContentText(first?.let { "“$it”" } ?: text)
        .apply { if (first != null) setStyle(Notification.BigTextStyle().bigText("“$first”\n\n$text")) }
        .setContentIntent(openPi)
        .setAutoCancel(true)
        .setCategory(Notification.CATEGORY_REMINDER) // hint to Android that this is a reminder, not a suggestion
        .addAction(Notification.Action.Builder(null, "Text", textIntent).build())
        .addAction(Notification.Action.Builder(null, "Snooze 3 days", broadcast("snooze")).build())
        .addAction(Notification.Action.Builder(null, "Done", broadcast("done")).build())
        .build()
    nm.notify(id, n) // silently does nothing if notification permission is off
}

/** Intent for TextActivity, which clears the notification and opens the SMS app to this person. */
fun textIntent(ctx: Context, contactId: Long, phone: String, body: String?): Intent =
    Intent(ctx, TextActivity::class.java)
        .setData(Uri.parse("th://text/$contactId"))
        .putExtra("phone", phone)
        .putExtra("body", body)

// Android won't let a notification button start another app via a receiver, so "Text" goes through
// this invisible activity: dismiss notification, log it, open Google Messages, close.
class TextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.data?.lastPathSegment?.toLongOrNull()
        if (id != null) {
            getSystemService(NotificationManager::class.java).cancel(id.toInt())
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch { AppDb.get(app).dao().setLastAction(id, "text") }
        }
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + intent.getStringExtra("phone")))
            .putExtra("sms_body", intent.getStringExtra("body")))
        finish()
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.data?.host ?: return
        val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
        val pending = goAsync() // keep the receiver alive while we write to the database
        CoroutineScope(Dispatchers.IO).launch {
            try { applyAction(ctx, id, action) } finally { pending.finish() }
        }
    }
}

/** "snooze" or "done", from a notification button or the Home screen. */
suspend fun applyAction(ctx: Context, id: Long, action: String) {
    ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
    val dao = AppDb.get(ctx).dao()
    val s = dao.get(id) ?: return
    val now = System.currentTimeMillis()
    when (action) {
        "snooze" -> dao.save(s.copy(snoozedUntil = now + 3 * DAY))
        "done" -> dao.save(s.copy(handledAt = now))
    }
    dao.setLastAction(id, action)
}
