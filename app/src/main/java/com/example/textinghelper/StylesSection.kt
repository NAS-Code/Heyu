package com.example.textinghelper

import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Text Style Settings: pick sample chats per style, build, review/edit. */
@Composable
fun TextStyleSettings() {
    val ctx = LocalContext.current
    var contacts by remember { mutableStateOf<List<ContactStats>?>(null) }
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { (loadCachedDiagnostic(ctx) ?: readDiagnostic(ctx)).contacts } }
    var styles by remember { mutableStateOf(ctx.allStyles()) }
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Style?>(null) }
    var deleting by remember { mutableStateOf<Style?>(null) }
    fun reload() { styles = ctx.allStyles() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(styles.joinToString(" · ") { st -> st.label + if (ctx.loadStyle(st).builtAt != null) " ✓" else " (not built)" },
            style = MaterialTheme.typography.bodySmall)
        run {
            Text("Pick up to $MAX_STYLE_SAMPLES chats per style that show how you text those people. Claude studies up to 250 " +
                "of your own messages from each and builds a style used for every suggestion. Contacts without a style use Friends.",
                style = MaterialTheme.typography.bodySmall)
            Text("Building sends those messages (yours only, no names) to Anthropic once.", style = MaterialTheme.typography.bodySmall)
            val c = contacts
            // One tab per style, like the app's main tabs; scrolls sideways once custom styles are added.
            val canAdd = styles.count { it.custom } < MAX_CUSTOM_STYLES
            PrimaryScrollableTabRow(selectedTabIndex = tab.coerceAtMost(styles.lastIndex), edgePadding = 0.dp) {
                styles.forEachIndexed { i, st -> Tab(tab == i, onClick = { tab = i }, text = { Text(st.label) }) }
                if (canAdd) Tab(false, onClick = { adding = true }, text = { Text("+ New") })
            }
            val st = styles[tab.coerceAtMost(styles.lastIndex)]
            if (st.custom) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Custom style", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { renaming = st }) { Text("Rename") }
                TextButton(onClick = { deleting = st }) { Text("Delete") }
            }
            if (c == null) Text("Loading contacts…") else key(st.key) { StyleCard(st, c) } // key: each tab keeps its own state
        }
    }
    if (adding) StyleNameDialog("New style", "", styles.map { it.label }, { adding = false }) { name ->
        ctx.addCustomStyle(name); reload(); tab = styles.lastIndex; adding = false
    }
    renaming?.let { st ->
        StyleNameDialog("Rename style", st.label, styles.filter { it.key != st.key }.map { it.label }, { renaming = null }) { name ->
            ctx.renameCustomStyle(st.key, name); reload(); renaming = null
        }
    }
    deleting?.let { st ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${st.label}”?") },
            text = { Text("Its samples and profile are removed. Anyone using it switches to the Friends style.") },
            confirmButton = { TextButton(onClick = { ctx.deleteCustomStyle(st.key); reload(); tab = 0; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StyleNameDialog(title: String, initial: String, taken: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val clean = name.trim()
    val error = when {
        clean.isEmpty() -> null
        taken.any { it.equals(clean, ignoreCase = true) } -> "There's already a style with that name"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it.take(20) }, singleLine = true, label = { Text("Name") },
                placeholder = { Text("e.g. Gym crew, College, Work friends") }, isError = error != null,
                supportingText = { Text(error ?: "Up to 20 characters") })
        },
        confirmButton = { TextButton(enabled = clean.isNotEmpty() && error == null, onClick = { onSave(clean) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StyleCard(style: Style, contacts: List<ContactStats>) {
    val ctx = LocalContext.current
    val dao = AppDb.get(ctx).dao()
    val scope = rememberCoroutineScope()
    val byId = remember(contacts) { contacts.associateBy { it.contactId } }
    var p by remember { mutableStateOf(ctx.loadStyle(style)) }
    fun update(np: StyleProfile) { p = np; ctx.saveStyle(style, np) }
    var picking by remember { mutableStateOf(false) }
    var building by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var showProfile by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sample chats (${p.samples.size}/$MAX_STYLE_SAMPLES)", style = MaterialTheme.typography.labelMedium)
            p.samples.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(byId[id]?.name ?: "Unknown contact", Modifier.weight(1f))
                    TextButton(onClick = { update(p.copy(samples = p.samples - id)) }) { Text("Remove") }
                }
            }
            if (p.samples.size < MAX_STYLE_SAMPLES) OutlinedButton(onClick = { picking = true }) { Text("Add contacts") }

            OutlinedTextField(p.notes, { update(p.copy(notes = it)) }, Modifier.fillMaxWidth(),
                label = { Text("Your own notes (optional)") },
                placeholder = { Text("e.g. no emojis, full sentences, always sign off with love") },
                supportingText = { Text("Treated as rules Claude must follow. Rebuild so the examples follow them too.") })
            OutlinedTextField(p.banned, { update(p.copy(banned = it)) }, Modifier.fillMaxWidth(),
                label = { Text("Never use (optional)") }, placeholder = { Text("e.g. yo, bro, lol") }, singleLine = true,
                supportingText = { Text("Comma-separated. Enforced: any suggestion using one is thrown out and retried.") })

            Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = p.samples.isNotEmpty() && !building, onClick = {
                building = true
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        try { buildStyle(ctx, style, byId) } catch (e: Exception) { "Build failed: ${e.message}" }
                    }
                    p = ctx.loadStyle(style)
                    building = false
                    showProfile = true
                }
            }) { Text(if (building) "Building…" else if (p.builtAt == null) "Build style" else "Rebuild") }
            p.builtAt?.let { Text("Built ${fmt(it)}", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (p.builtAt != null) {
                Row(Modifier.fillMaxWidth().clickable { showProfile = !showProfile }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.examples.size} example texts", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(if (showProfile) "Hide ▲" else "Show ▼", style = MaterialTheme.typography.bodyLarge)
                }
                if (showProfile) {
                    OutlinedTextField(p.description, { update(p.copy(description = it)) }, Modifier.fillMaxWidth(),
                        label = { Text("How you text (Claude's analysis, editable)") })
                    Text("Example texts", fontWeight = FontWeight.Bold)
                    p.examples.forEach { ex ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("“$ex”", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { update(p.copy(examples = p.examples - ex)) }) { Text("✕") }
                        }
                    }
                }
            }
        }
    }

    if (picking) ContactPicker(
        contacts = contacts.filter { it.contactId !in p.samples },
        maxPicks = MAX_STYLE_SAMPLES - p.samples.size,
        onDismiss = { picking = false },
        onPick = { picked ->
            picking = false
            update(p.copy(samples = p.samples + picked.map { it.contactId }))
            // Sample contacts automatically use this style.
            scope.launch {
                for (c in picked) dao.save((dao.get(c.contactId) ?: ContactSetting(c.contactId, c.phone, c.name, null)).copy(style = style.key))
            }
        },
    )
}

@Composable
private fun ContactPicker(contacts: List<ContactStats>, maxPicks: Int, onDismiss: () -> Unit, onPick: (List<ContactStats>) -> Unit) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<Long>()) }
    val shown = contacts.filter { it.name.contains(query.trim(), ignoreCase = true) } // already ranked by most texted
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick up to $maxPicks") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search names") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.contactId }) { c ->
                        val on = c.contactId in chosen
                        Row(Modifier.fillMaxWidth().clickable(enabled = on || chosen.size < maxPicks) {
                            chosen = if (on) chosen - c.contactId else chosen + c.contactId
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, null, enabled = on || chosen.size < maxPicks)
                            Column {
                                Text(c.name)
                                Text("${c.count12Months} texts/yr", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onPick(contacts.filter { it.contactId in chosen }) }) {
                Text("Add ${chosen.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
