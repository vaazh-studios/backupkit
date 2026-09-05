package com.vocabloot.backupkit.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vocabloot.backupkit.CloudAvailability
import com.vocabloot.backupkit.SyncOutcome
import kotlinx.coroutines.launch

/**
 * [requestConsent] is Android's one-time Drive dialog (see MainActivity); iOS passes a no-op that
 * immediately reports true.
 */
@Composable
fun App(requestConsent: (onResult: (Boolean) -> Unit) -> Unit) {
    val backup = remember { NotesBackup() }
    val notes = remember { mutableStateListOf<String>().apply { addAll(backup.load()) } }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not synced yet") }
    var restoreOffer by remember { mutableStateOf<Header?>(null) }
    val scope = rememberCoroutineScope()

    fun sync() = scope.launch {
        status = "Syncing…"
        val outcome = backup.sync(notes.toList()) { done, total -> status = "Syncing $done/$total" }
        status = when (outcome) {
            is SyncOutcome.Synced -> "Up to date (${outcome.entryCount} files)"
            is SyncOutcome.Unavailable -> "Unavailable: ${outcome.reason}"
            is SyncOutcome.Failed -> "Stuck: ${outcome.error}"
        }
    }

    LaunchedEffect(Unit) {
        val remote = backup.inspect()
        if (remote.availability == CloudAvailability.NeedsConsent) {
            requestConsent { granted -> if (granted) scope.launch { backup.inspect().marker?.let { restoreOffer = backup.parseHeader(it) } } }
        } else if (notes.isEmpty()) {
            remote.marker?.let { restoreOffer = backup.parseHeader(it) }
        }
    }

    restoreOffer?.let { header ->
        AlertDialog(
            onDismissRequest = { restoreOffer = null },
            title = { Text("Restore ${header.noteCount} notes?") },
            text = { Text("A backup from your own cloud was found.") },
            confirmButton = {
                TextButton(onClick = {
                    restoreOffer = null
                    scope.launch { notes.clear(); notes.addAll(backup.restore()); status = "Restored ${notes.size} notes" }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreOffer = null }) { Text("Not now") } },
        )
    }

    MaterialTheme {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("BackupKit sample", style = MaterialTheme.typography.headlineSmall)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), label = { Text("New note") })
                    Button(
                        onClick = {
                            if (draft.isNotBlank()) {
                                notes.add(draft.trim()); draft = ""; backup.save(notes.toList()); sync()
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Add") }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(notes) { note ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(note, Modifier.weight(1f))
                            TextButton(onClick = { notes.remove(note); backup.save(notes.toList()); sync() }) { Text("Delete") }
                        }
                    }
                }
                Button(onClick = { sync() }, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
            }
        }
    }
}
