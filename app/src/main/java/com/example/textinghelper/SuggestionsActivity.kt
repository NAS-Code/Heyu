package com.example.textinghelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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

        // Hands off to TextActivity, which dismisses the notification and opens Google Messages.
        fun send(body: String?) {
            startActivity(textIntent(this, id, phone, body))
            finish()
        }

        setContent {
            TextingHelperTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(intent.getStringExtra("title") ?: "", style = MaterialTheme.typography.headlineSmall)
                        Text(intent.getStringExtra("reason") ?: "", style = MaterialTheme.typography.bodySmall)
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
                    }
                }
            }
        }
    }
}
