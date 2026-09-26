package com.example.textinghelper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private fun Context.batteryExempt() =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

@Composable
fun HomeScreen(contacts: List<ContactStats>, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val dao = AppDb.get(ctx).dao()
    val settings by remember { dao.all() }.collectAsState(initial = emptyList())
    val actions by remember { dao.recentActions() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val stats = remember(contacts) { contacts.associateBy { it.contactId } }
    val now = System.currentTimeMillis()
    // ignoreRecent: show everyone who's due, even if a notification already went out today.
    val vary = ctx.varyTiming
    val due = findDue(settings, stats, now, ignoreRecent = true, vary = vary)
    val soon = upcoming(settings, stats, now, vary).take(10)
    val names = settings.associate { it.contactId to it.name }

    var exempt by remember { mutableStateOf(ctx.batteryExempt()) }
    val batteryLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { exempt = ctx.batteryExempt() }

    LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!exempt) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reminders may arrive late", fontWeight = FontWeight.Bold)
                    Text("Android's battery saver can delay the daily check. Allow Heyu to run in the background so it fires on time.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        // Shows Android's own "Let app always run in background?" dialog. Fine for a
                        // sideloaded app; Play Store policy restricts this, which doesn't apply here.
                        @SuppressLint("BatteryLife")
                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        batteryLauncher.launch(i)
                    }) { Text("Allow") }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Due now (${due.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
        if (due.isEmpty()) item { Text("Nobody. You're all caught up.", style = MaterialTheme.typography.bodySmall) }
        items(due, key = { "due${it.setting.contactId}" }) { r ->
            val s = r.setting
            Column {
                Text((if (r.unreplied) "Reply to " else "") + s.name, fontWeight = FontWeight.Bold)
                Text(reasonText(r), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { ctx.startActivity(textIntent(ctx, s.contactId, s.phone, body = null)) }) { Text("Text") }
                    TextButton(onClick = { scope.launch { applyAction(ctx, s.contactId, "snooze") } }) { Text("Snooze 3 days") }
                    TextButton(onClick = { scope.launch { applyAction(ctx, s.contactId, "done") } }) { Text("Done") }
                }
            }
        }

        item { HorizontalDivider(); Text("Coming up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (soon.isEmpty()) item { Text("Nobody opted in yet. Set tiers on the People tab.", style = MaterialTheme.typography.bodySmall) }
        items(soon, key = { "soon${it.setting.contactId}" }) { u ->
            Row {
                Text(u.setting.name, Modifier.weight(1f))
                Text(if (u.inDays == 1L) "tomorrow" else "in ${u.inDays} days", style = MaterialTheme.typography.bodySmall)
            }
        }

        item { HorizontalDivider(); Text("Recently handled", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (actions.isEmpty()) item { Text("Nothing yet.", style = MaterialTheme.typography.bodySmall) }
        items(actions, key = { "log${it.id}" }) { a ->
            Row {
                Text(names[a.contactId] ?: "Unknown", Modifier.weight(1f))
                val verb = when (a.action) { "text" -> "Texted"; "snooze" -> "Snoozed"; "done" -> "Done"; else -> a.action ?: "" }
                Text("$verb · ${fmt(a.time)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
