package com.mouninaveen.collector

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.Locale
import java.util.UUID

// Call history is NEVER read from the Android call log provider.
// Only records explicitly imported, reviewed and approved by this device's owner are uploaded.
private data class ImportedCall(
    val number: String,
    val direction: String,
    val timestamp: Long,
    val durationSeconds: Int
)

private fun parseCsvLine(line: String): List<String> {
    val values = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                field.append('"'); i++
            }
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> { values.add(field.toString()); field.setLength(0) }
            else -> field.append(ch)
        }
        i++
    }
    require(!quoted) { "A CSV field has an unclosed quote." }
    values.add(field.toString())
    return values
}

private fun readSelectedCsv(context: android.content.Context, uri: Uri): List<ImportedCall> {
    val source = context.contentResolver.openInputStream(uri) ?: error("Could not open CSV")
    source.bufferedReader(Charsets.UTF_8).use { reader ->
        val header = reader.readLine()?.removePrefix("\uFEFF")?.let(::parseCsvLine)
            ?: error("CSV is empty")
        val expected = listOf("number", "direction", "timestamp", "durationSeconds")
        require(header.map { it.trim() } == expected) {
            "Header must be: number,direction,timestamp,durationSeconds"
        }
        val records = mutableListOf<ImportedCall>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            require(records.size < 100) { "For safety, import at most 100 records per file." }
            require(line.length <= 4096) { "A CSV row is too long." }
            val parts = parseCsvLine(line)
            require(parts.size == 4) { "Each row needs exactly four columns." }
            val number = parts[0].trim()
            val direction = parts[1].trim().uppercase(Locale.ROOT)
            val timestamp = parts[2].trim().toLongOrNull() ?: error("Invalid timestamp")
            val duration = parts[3].trim().toIntOrNull() ?: error("Invalid call duration")
            require(number.length in 1..48 && number.all { it.isDigit() || it in "+-() *#" }) {
                "Invalid phone number format"
            }
            require(direction in setOf("INCOMING", "OUTGOING", "MISSED", "REJECTED", "UNKNOWN")) {
                "Call direction must be INCOMING, OUTGOING, MISSED, REJECTED or UNKNOWN."
            }
            require(timestamp > 946684800000L && timestamp <= System.currentTimeMillis() + 86400000L) {
                "Timestamp must be Unix milliseconds from year 2000 onward."
            }
            require(duration in 0..86400) { "Duration must be between 0 and 86400 seconds." }
            records.add(ImportedCall(number, direction, timestamp, duration))
        }
        require(records.isNotEmpty()) { "The file has no call records." }
        return records
    }
}

@Composable
fun CallHistoryImport(
    consentToSharing: Boolean,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var calls by remember { mutableStateOf(emptyList<ImportedCall>()) }
    var fileName by remember { mutableStateOf("") }
    var approved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<Int>()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        approved = false
        calls = emptyList()
        chosen = emptySet()
        fileName = ""
        if (uri != null) {
            try {
                val loaded = readSelectedCsv(context, uri)
                calls = loaded
                chosen = loaded.indices.toSet()
                fileName = uri.lastPathSegment.orEmpty().take(80)
                onStatus("Loaded ${loaded.size} records locally. Review and confirm each selected item.")
            } catch (e: Exception) {
                onStatus("CSV import failed: ${e.localizedMessage}")
            }
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Call History — Share Selected Records", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "No phone call-log permission is used. Export a CSV from a source you control, select it here, inspect the phone numbers and call details, then explicitly approve uploading the selected records to your enrolled administrator. Nothing uploads automatically.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("CSV format: number,direction,timestamp,durationSeconds", style = MaterialTheme.typography.labelMedium)
            Text("timestamp is Unix milliseconds. Example: +919876543210,INCOMING,1760000000000,45", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !busy,
                onClick = { picker.launch("text/*") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Choose call-history CSV") }

            if (calls.isNotEmpty()) {
                Text("${calls.size} records loaded • ${chosen.size} selected", style = MaterialTheme.typography.titleMedium)
                Text("Preview of selected file: $fileName", style = MaterialTheme.typography.bodySmall)
                calls.take(12).forEachIndexed { index, call ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(
                            checked = index in chosen,
                            onCheckedChange = {
                                chosen = if (it) chosen + index else chosen - index
                                approved = false
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${call.direction} • ${call.number}")
                            Text(
                                "${Date(call.timestamp)} • ${call.durationSeconds}s",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (calls.size > 12) {
                    Text("Only the first 12 rows are shown. For a full review, import a CSV with 12 or fewer records.", style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = { chosen = calls.indices.toSet(); approved = false }) { Text("Select all") }
                    TextButton(onClick = { chosen = emptySet(); approved = false }) { Text("Clear") }
                }
                Row {
                    Checkbox(
                        checked = approved,
                        onCheckedChange = { approved = it }
                    )
                    Text(
                        "I own or am authorized to share these selected call records. I understand the administrator will see phone numbers, call types, dates and durations in Firebase.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    enabled = !busy && consentToSharing && approved && chosen.isNotEmpty()
                        && FirebaseAuth.getInstance().currentUser != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val uid = FirebaseAuth.getInstance().currentUser?.uid
                                    ?: error("Sign in first.")
                                val prefs = context.getSharedPreferences("collector", android.content.Context.MODE_PRIVATE)
                                check(prefs.getBoolean("consent", false)) { "Sharing consent was withdrawn." }
                                val db = FirebaseFirestore.getInstance()
                                val enrolled = db.collection("devices")
                                    .whereEqualTo("collectorUid", uid).get().await()
                                val device = enrolled.documents.firstOrNull {
                                    it.getBoolean("enabled") != false
                                } ?: error("Account is not enrolled on an active device.")
                                val batch = db.batch()
                                val selected = calls.filterIndexed { index, _ -> index in chosen }
                                selected.forEach { call ->
                                    val id = UUID.randomUUID().toString()
                                    val doc = device.reference.collection("callLogs").document(id)
                                    batch.set(doc, mapOf(
                                        "callId" to id,
                                        "deviceId" to device.id,
                                        "number" to call.number,
                                        "direction" to call.direction,
                                        "callTimestamp" to Date(call.timestamp),
                                        "durationSeconds" to call.durationSeconds,
                                        "sharedByUid" to uid,
                                        "sharedAt" to FieldValue.serverTimestamp(),
                                        "source" to "USER_IMPORTED_CSV",
                                        "schemaVersion" to 1
                                    ))
                                }
                                check(prefs.getBoolean("consent", false)) { "Sharing consent was withdrawn." }
                                check(FirebaseAuth.getInstance().currentUser?.uid == uid) {
                                    "Signed-in account changed."
                                }
                                batch.commit().await()
                                onStatus("Successfully uploaded ${selected.size} user-approved call records.")
                                calls = emptyList()
                                chosen = emptySet()
                                approved = false
                            } catch (e: Exception) {
                                onStatus("Call-history upload failed: ${e.localizedMessage}")
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Uploading approved records…" else "Approve & Upload ${chosen.size} to Firebase") }
                if (!consentToSharing) Text("Enable sharing consent above before uploading.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
