package com.example.textinghelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.textinghelper.ui.theme.TextingHelperTheme

/** Opened by tapping a reminder notification: shows every suggestion with a "Use this" button. */
class SuggestionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getLongExtra("id", 0)
        val phone = intent.getStringExtra("phone") ?: return finish()
        val texts = intent.getStringArrayExtra("texts").orEmpty()
        val angles = intent.getStringArrayExtra("angles").orEmpty()
        // Both toggled in Settings → Testing Settings.
        val recap = intent.getStringExtra("recap").orEmpty().takeIf { showRecap && it.isNotBlank() }
        val prompt = if (showPromptButton) promptFile(this, id).takeIf { it.exists() }?.readText() else null

        // Hands off to TextActivity, which dismisses the notification and opens Google Messages.
        fun send(body: String?) {
            startActivity(textIntent(this, id, phone, body))
            finish()
        }

        setContent {
            TextingHelperTheme {
                Surface(Modifier.fillMaxSize()) {
                    var promptOpen by remember { mutableStateOf(false) }
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(intent.getStringExtra("title") ?: "", style = MaterialTheme.typography.headlineSmall)
                        Text(intent.getStringExtra("reason") ?: "", style = MaterialTheme.typography.bodySmall)
                        if (recap != null) OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Claude's recap", style = MaterialTheme.typography.labelMedium)
                                Text(recap, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        texts.forEachIndexed { i, text ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(angles.getOrNull(i) ?: "", style = MaterialTheme.typography.labelSmall)
                                    Text(text)
                                    Button(onClick = { send(text) }) { Text("Use this") }
                                }
                            }
                        }
                        OutlinedButton(onClick = { send(null) }) { Text("Write my own") }
                        if (prompt != null) TextButton(onClick = { promptOpen = true }) { Text("View prompt") }
                    }
                    if (promptOpen && prompt != null) AlertDialog(
                        onDismissRequest = { promptOpen = false },
                        title = { Text("Prompt sent to Claude") },
                        text = {
                            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                                Text(prompt, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = { TextButton(onClick = { promptOpen = false }) { Text("Close") } },
                    )
                }
            }
        }
    }
}
