package com.example.textinghelper

import android.app.Activity
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
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// Plain SharedPreferences (a small key-value file) for non-secret settings.
fun Context.prefs() = getSharedPreferences("prefs", Context.MODE_PRIVATE)
val Context.reminderHour get() = prefs().getInt("hour", 18)
val Context.reminderMinute get() = prefs().getInt("minute", 0)
val Context.dailyCap get() = prefs().getInt("cap", 3)
val Context.varyTiming get() = prefs().getBoolean("vary", true)
val Context.showRecap get() = prefs().getBoolean("showRecap", true)
val Context.showPromptButton get() = prefs().getBoolean("showPrompt", true)

/** Reads messages, picks who's due, posts notifications. Returns a summary for the "Run check now" button. */
suspend fun runCheck(ctx: Context, ignoreRecent: Boolean = false): String = withContext(Dispatchers.IO) {
    val dao = AppDb.get(ctx).dao()
    val now = System.currentTimeMillis()
    val stats = readAndCacheDiagnostic(ctx).contacts.associateBy { it.contactId }
    if (ctx.varyTiming) for (s in dao.list()) rollJitter(s, lastOut(s, stats[s.contactId]), Random.Default)?.let { dao.save(it) }
    val all = findDue(dao.list(), stats, now, ignoreRecent, vary = ctx.varyTiming)
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
    "${all.size} due, sent ${picks.size}: " + picks.joinToString { it.setting.name }.ifEmpty { "nobody" } +
        (if (ctx.apiKey == null) "\nNo API key set, so no suggestions." else "") +
        (if (tokensIn > 0) "\nAI usage: ${"%,d".format(tokensIn)} in / ${"%,d".format(tokensOut)} out tokens (~$${"%.3f".format(costUsd(tokensIn, tokensOut))})" else "") +
        aiErrors.joinToString("") { "\nAI failed for $it" }
}

class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCheck(applicationContext)
        return Result.success()
    }
}

// ponytail: a 24h periodic job can drift from the set time by a few minutes (or more in battery-saver
// Doze). Saving the time in Settings re-anchors it. Switch to exact alarms if drift ever bothers you.
fun scheduleDaily(ctx: Context, replace: Boolean) {
    val now = LocalDateTime.now()
    var next = now.toLocalDate().atTime(ctx.reminderHour, ctx.reminderMinute)
    if (!next.isAfter(now)) next = next.plusDays(1)
    val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(Duration.between(now, next))
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "daily",
        if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
        req,
    )
}

private const val CHANNEL = "reminders2"

fun reasonText(r: Reminder) = when {
    r.unreplied -> "They texted you ${r.days} days ago"
    r.days == null -> "You haven't texted them yet · ${tierLabel(r.setting.frequencyDays)}"
    else -> "Last texted ${r.days} days ago · ${tierLabel(r.setting.frequencyDays)}"
}

fun notify(ctx: Context, r: Reminder, suggestions: List<Suggestion>, recap: String = "") {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    // A channel's sound/vibration settings are fixed once created, so turning vibration on needs a new
    // channel id. The old one ("reminders") had vibration off.
    nm.deleteNotificationChannel("reminders")
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
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
        .setSmallIcon(android.R.drawable.sym_action_chat)
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
