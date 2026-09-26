package com.example.textinghelper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
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
import com.example.textinghelper.ui.theme.TextingHelperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val PERMS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scheduleDaily(this, replace = false) // no-op if already scheduled
        setContent {
            TextingHelperTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().padding(16.dp)) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    fun granted() = PERMS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var hasPerms by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { hasPerms = granted() }

    if (!hasPerms) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Texting Helper", style = MaterialTheme.typography.headlineMedium)
            Text("To figure out who you haven't texted in a while, this app needs to read:")
            Text("• Your SMS/MMS messages (read-only). If you add a Claude API key, your last 50 messages with a person are sent to Anthropic to draft a suggested text when they're due.")
            Text("• Your contacts, to put names to phone numbers")
            Text("• Notifications, to remind you who to text")
            Button(onClick = { launcher.launch(PERMS) }) { Text("Grant access") }
        }
    } else {
        // Show the last saved read instantly, then re-read in the background (~180k rows takes a few seconds).
        var data by remember { mutableStateOf<Diagnostic?>(null) }
        var loading by remember { mutableStateOf(true) }
        var refreshKey by remember { mutableIntStateOf(0) }
        LaunchedEffect(refreshKey) {
            loading = true
            if (data == null) data = withContext(Dispatchers.IO) { loadCachedDiagnostic(ctx) }
            data = withContext(Dispatchers.IO) { readAndCacheDiagnostic(ctx) }
            loading = false
        }
        var tab by remember { mutableIntStateOf(0) }
        Column {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Home", "People", "Settings", "Data").forEachIndexed { i, label ->
                    Tab(tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val d = data
            when {
                tab == 2 -> SettingsScreen()
                d == null -> Text("Reading messages for the first time…", Modifier.padding(top = 16.dp))
                tab == 0 -> HomeScreen(d.contacts, onRefresh = { refreshKey++ })
                tab == 1 -> SetupScreen(d.contacts)
                else -> DiagnosticScreen(d, onRefresh = { refreshKey++ })
            }
        }
    }
}

@Composable
fun DiagnosticScreen(d: Diagnostic, onRefresh: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { UsageSection(d.contacts.associate { it.contactId to it.name }) }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Data diagnostic", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                }
                Text("SMS rows: ${d.smsRows}   MMS rows: ${d.mmsRows}")
                Text("Oldest message: ${fmt(d.oldest)}")
                Text("Messages in last 30 days: ${d.last30Days}")
                Text("Threads: ${d.oneToOneThreads} 1:1, ${d.groupThreads} group (skipped)")
                Text("Threads with no readable messages: ${d.emptyThreads}")
                Text("Numbers not in contacts: ${d.unmatched.size} (top: ${d.unmatched.take(3).joinToString { "${it.first} ×${it.second}" }})")
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        items(d.contacts) { c ->
            Column {
                Text("${c.name}  (${c.count})", fontWeight = FontWeight.Bold)
                Text("Last message ${fmt(c.lastDate)} from ${if (c.lastFromMe) "me" else "them"}")
                Text("My last text: ${fmt(c.lastOutgoing)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun fmt(ms: Long?) = ms?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "never"

/** Claude token usage over the last 30 days, from the reminder log. */
@Composable
fun UsageSection(names: Map<Long, String>) {
    val dao = AppDb.get(LocalContext.current).dao()
    val since = remember { System.currentTimeMillis() - 30 * DAY }
    val rows by remember { dao.usageSince(since) }.collectAsState(initial = emptyList())
    val tokIn = rows.sumOf { it.inputTokens ?: 0 }
    val tokOut = rows.sumOf { it.outputTokens ?: 0 }
    fun n(x: Int) = "%,d".format(x)
    fun usd(x: Double) = "$" + "%.3f".format(x)
    Column(Modifier.padding(top = 8.dp)) {
        Text("AI usage (last 30 days)", style = MaterialTheme.typography.titleMedium)
        if (rows.isEmpty()) Text("No suggestions generated yet.", style = MaterialTheme.typography.bodySmall)
        else {
            Text("${rows.size} suggestions · ${n(tokIn)} in / ${n(tokOut)} out tokens · ~${usd(costUsd(tokIn, tokOut))}")
            Text("Average per suggestion: ${n(tokIn / rows.size)} in / ${n(tokOut / rows.size)} out · ~${usd(costUsd(tokIn, tokOut) / rows.size)}",
                style = MaterialTheme.typography.bodySmall)
            Text("Estimated at Sonnet 5 list prices ($2 / $10 per million tokens). Output includes thinking.",
                style = MaterialTheme.typography.bodySmall)
            rows.take(10).forEach { r ->
                Text("${fmt(r.time)} · ${names[r.contactId] ?: "?"} · ${n(r.inputTokens ?: 0)} in / ${n(r.outputTokens ?: 0)} out" +
                    (if ((r.images ?: 0) > 0) " · ${r.images} img" else "") + " · ${usd(costUsd(r.inputTokens ?: 0, r.outputTokens ?: 0))}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
