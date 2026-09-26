package com.example.textinghelper

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var hour by remember { mutableIntStateOf(ctx.reminderHour) }
    var minute by remember { mutableIntStateOf(ctx.reminderMinute) }
    var cap by remember { mutableIntStateOf(ctx.dailyCap) }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily reminder time", Modifier.weight(1f))
            OutlinedButton(onClick = {
                // Android's built-in time picker dialog
                TimePickerDialog(ctx, { _, h, m ->
                    hour = h; minute = m
                    ctx.prefs().edit().putInt("hour", h).putInt("minute", m).apply()
                    scheduleDaily(ctx, replace = true)
                }, hour, minute, false).show()
            }) { Text(LocalTime.of(hour, minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Max people per daily check", Modifier.weight(1f))
            fun setCap(n: Int) { cap = n.coerceIn(1, 20); ctx.prefs().edit().putInt("cap", cap).apply() }
            TextButton(onClick = { setCap(cap - 1) }) { Text("−") }
            Text("$cap")
            TextButton(onClick = { setCap(cap + 1) }) { Text("+") }
        }
        var vary by remember { mutableStateOf(ctx.varyTiming) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vary reminder timing")
                Text("Every other reminder comes a little early or late so you're not texting on an obvious schedule: " +
                    "±1 day for weekly-plus cadences, ±2–3 days for monthly-plus.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(vary, { vary = it; ctx.prefs().edit().putBoolean("vary", it).apply() })
        }
        HorizontalDivider()
        var savedKey by remember { mutableStateOf(ctx.apiKey) }
        var keyInput by remember { mutableStateOf("") }
        Text("Claude API key")
        Text(savedKey?.let { "Saved (ends in …${it.takeLast(4)}). Stored encrypted on this phone only." }
            ?: "Not set. Reminders still work, just without suggested texts.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(keyInput, { keyInput = it.trim() }, Modifier.fillMaxWidth(),
            label = { Text(if (savedKey == null) "sk-ant-…" else "Replace key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keyInput.isNotEmpty(), onClick = {
                ctx.apiKey = keyInput; savedKey = keyInput; keyInput = ""
            }) { Text("Save key") }
            if (savedKey != null) OutlinedButton(onClick = { ctx.apiKey = null; savedKey = null }) { Text("Remove") }
        }
        HorizontalDivider()
        OutlinedButton(onClick = {
            // Fake contact (id -1): Snooze/Done do nothing, Text opens Messages with no recipient.
            val fake = ContactSetting(-1, "", "Test Person", 7)
            notify(ctx, Reminder(fake, unreplied = false, days = 9),
                listOf(Suggestion("this is what a suggested text will look like", "check-in")))
        }) { Text("Send test notification") }
        Button(enabled = !running, onClick = {
            running = true
            scope.launch {
                result = withContext(Dispatchers.IO) { runCheck(ctx, ignoreRecent = true) }
                running = false
            }
        }) { Text(if (running) "Checking…" else "Run check now") }
        Text("Testing only: ignores the \"don't re-remind within 3 days\" rule. Snooze and the daily cap still apply.",
            style = MaterialTheme.typography.bodySmall)
        result?.let { Text(it) }
    }
}
