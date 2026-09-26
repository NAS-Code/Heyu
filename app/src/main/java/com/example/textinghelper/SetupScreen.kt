package com.example.textinghelper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class Sort(val label: String) { MOST("Most texted"), NAME("A–Z"), RECENT("Recently texted"), CADENCE("Cadence") }

private val TIERS = listOf("Weekly" to 7, "Biweekly" to 14, "Monthly" to 30, "Quarterly" to 90)

fun tierLabel(days: Int?) = when (days) {
    null -> "Ignore"
    else -> TIERS.firstOrNull { it.second == days }?.first ?: "Every $days days"
}

@Composable
fun SetupScreen(contacts: List<ContactStats>) {
    val ctx = LocalContext.current
    val dao = AppDb.get(ctx).dao()
    val settings by remember { dao.all() }.collectAsState(initial = emptyList())
    val byId = settings.associateBy { it.contactId }
    val scope = rememberCoroutineScope()
    var onlyOptedIn by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Remember the last sort you picked.
    var sort by remember { mutableStateOf(Sort.entries.getOrElse(ctx.prefs().getInt("sort", 0)) { Sort.MOST }) }

    fun setDays(c: ContactStats, days: Int?) = scope.launch {
        val existing = byId[c.contactId]
        // Changing the tier is a fresh start: clear Done/snooze/last-reminded so the clock goes back
        // to my real last text.
        dao.save(existing?.copy(frequencyDays = days, name = c.name, phone = c.phone,
                handledAt = null, snoozedUntil = null, lastReminded = null, jitterCycle = null, jitterDays = 0)
            ?: ContactSetting(c.contactId, c.phone, c.name, days))
    }

    fun setStyle(c: ContactStats, style: Style) = scope.launch {
        dao.save((byId[c.contactId] ?: ContactSetting(c.contactId, c.phone, c.name, null)).copy(style = style.key))
    }

    val optedIn = contacts.count { byId[it.contactId]?.frequencyDays != null }
    val filtered = contacts.filter {
        (!onlyOptedIn || byId[it.contactId]?.frequencyDays != null) && it.name.contains(query.trim(), ignoreCase = true)
    }
    val shown = when (sort) {
        Sort.MOST -> filtered // already ranked by texts in the last 12 months
        Sort.NAME -> filtered.sortedBy { it.name.lowercase() }
        Sort.RECENT -> filtered.sortedByDescending { it.lastOutgoing ?: 0 } // never texted goes last
        // Shortest cadence first, Ignore last; ties by most texted.
        Sort.CADENCE -> filtered.sortedBy { byId[it.contactId]?.frequencyDays ?: Int.MAX_VALUE }
    }

    Column {
    // Outside the list so it stays put while you scroll.
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
        placeholder = { Text("Search names") }, singleLine = true,
        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$optedIn opted in", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                SortPicker(sort) { sort = it; ctx.prefs().edit().putInt("sort", it.ordinal).apply() }
                Spacer(Modifier.width(8.dp))
                FilterChip(onlyOptedIn, onClick = { onlyOptedIn = !onlyOptedIn }, label = { Text("Opted in") })
            }
        }
        items(shown, key = { it.contactId }) { c ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, Modifier.weight(1f), fontWeight = FontWeight.Bold,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.25f)
                    Text("my last text ${fmt(c.lastOutgoing)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TierPicker(byId[c.contactId]?.frequencyDays) { setDays(c, it) }
                    StylePicker(styleOf(byId[c.contactId]?.style)) { setStyle(c, it) }
                }
            }
        }
        if (shown.isEmpty()) item { Text("No matches.", style = MaterialTheme.typography.bodySmall) }
    }
    }
}

// Row buttons at 75% of Material's default (40dp tall, 24dp side padding, 14sp text).
private val SMALL_BUTTON = Modifier.height(30.dp)
private val SMALL_PADDING = PaddingValues(horizontal = 18.dp, vertical = 0.dp)

@Composable
private fun SmallLabel(text: String) = Text(text, fontSize = MaterialTheme.typography.labelLarge.fontSize * 0.75f)

@Composable
private fun StylePicker(style: Style, onPick: (Style) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, SMALL_BUTTON, contentPadding = SMALL_PADDING) { SmallLabel("${style.label} style") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Style.entries.forEach { s ->
                DropdownMenuItem(text = { Text("${s.label} style") }, onClick = { open = false; onPick(s) })
            }
        }
    }
}

@Composable
private fun SortPicker(sort: Sort, onPick: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("Sort: ${sort.label}") })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Sort.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { open = false; onPick(s) })
            }
        }
    }
}

@Composable
private fun TierPicker(days: Int?, onPick: (Int?) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    Box {
        if (days == null) OutlinedButton(onClick = { menuOpen = true }, SMALL_BUTTON, contentPadding = SMALL_PADDING) { SmallLabel("Ignore") }
        else Button(onClick = { menuOpen = true }, SMALL_BUTTON, contentPadding = SMALL_PADDING) { SmallLabel(tierLabel(days)) }
        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            TIERS.forEach { (label, d) ->
                DropdownMenuItem(text = { Text("$label ($d days)") }, onClick = { menuOpen = false; onPick(d) })
            }
            DropdownMenuItem(text = { Text("Custom…") }, onClick = { menuOpen = false; customOpen = true })
            DropdownMenuItem(text = { Text("Ignore") }, onClick = { menuOpen = false; onPick(null) })
        }
    }
    if (customOpen) {
        var input by remember { mutableStateOf(days?.toString() ?: "") }
        val n = input.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { customOpen = false },
            title = { Text("Remind me every N days") },
            text = {
                OutlinedTextField(input, { input = it.filter(Char::isDigit).take(4) }, label = { Text("Days") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            },
            confirmButton = { TextButton(onClick = { customOpen = false; onPick(n) }, enabled = n != null) { Text("Save") } },
            dismissButton = { TextButton(onClick = { customOpen = false }) { Text("Cancel") } },
        )
    }
}
