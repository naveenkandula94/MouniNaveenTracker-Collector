package com.mouninaveen.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

private data class DeviceCall(
    val number: String,
    val direction: String,
    val timestamp: Long,
    val durationSeconds: Int
)

private fun recentCalls(context: Context): List<DeviceCall> {
    check(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
        "READ_CALL_LOG was not granted by Android/installer."
    }
    val projection = arrayOf(
        CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
    )
    val result = mutableListOf<DeviceCall>()
    context.contentResolver.query(
        CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC"
    )?.use { cursor ->
        val numberColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val typeColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val dateColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val durationColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        while (cursor.moveToNext() && result.size < 12) {
            val number = cursor.getString(numberColumn)?.take(48).orEmpty().ifBlank { "Unknown" }
            val direction = when (cursor.getInt(typeColumn)) {
                CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                CallLog.Calls.MISSED_TYPE -> "MISSED"
                CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                else -> "UNKNOWN"
            }
            val time = cursor.getLong(dateColumn)
            val seconds = cursor.getLong(durationColumn).coerceIn(0L, 86400L).toInt()
            if (time > 946684800000L && time <= System.currentTimeMillis() + 86400000L) {
                result.add(DeviceCall(number, direction, time, seconds))
            }
        }
    } ?: error("Call-log provider not available.")
    return result
}

@Composable
fun DeviceCallHistorySharing(consent: Boolean, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { FirebaseAuth.getInstance() }
    var permission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    ) }
    var records by remember { mutableStateOf(emptyList<DeviceCall>()) }
    var chosen by remember { mutableStateOf(emptySet<Int>()) }
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun load() {
        try {
            records = recentCalls(context)
            chosen = records.indices.toSet()
            confirmed = false
            onStatus("Loaded ${records.size} recent call records locally. Select which records to share.")
        } catch (e: Exception) {
            onStatus("Call-log access unavailable: ${e.localizedMessage}. Android may restrict READ_CALL_LOG even when requested.")
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        if (granted) load() else onStatus("Call-log permission denied or restricted. Nothing was uploaded.")
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Phone call log — Review & Share", style = MaterialTheme.typography.titleLarge)
            Text("Uses Android's CallLog provider only when READ_CALL_LOG is eligible and granted. This access is restricted on modern Android. No automatic background collection.")
            Text("Call-log permission: ${if (permission) "Granted" else "Not granted / restricted"}")
            Button(enabled = !busy, onClick = {
                permission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                if (permission) load() else permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            }) { Text("Read latest 12 calls on this phone") }
            if (records.isNotEmpty()) {
                records.forEachIndexed { i, record ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = i in chosen,
                            onCheckedChange = { checked ->
                                chosen = if (checked) chosen + i else chosen - i
                                confirmed = false
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${record.direction} · ${record.number}")
                            Text("${Date(record.timestamp)} · ${record.durationSeconds}s", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text(
                        "I am the device owner or authorized to share these selected phone records, including the listed numbers, with my paired Admin.",
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    enabled = consent && confirmed && chosen.isNotEmpty() && !busy && auth.currentUser != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val uid = auth.currentUser?.uid ?: error("Sign in first.")
                                check(context.getSharedPreferences("collector", Context.MODE_PRIVATE).getBoolean("consent", false)) {
                                    "Sharing consent was withdrawn."
                                }
                                val db = FirebaseFirestore.getInstance()
                                val enrolled = db.collection("devices").whereEqualTo("collectorUid", uid).get().await()
                                val device = enrolled.documents.firstOrNull { it.getBoolean("enabled") != false }
                                    ?: error("Collector is not enrolled or was disabled.")
                                val selected = records.filterIndexed { i, _ -> i in chosen }
                                val batch = db.batch()
                                selected.forEach { call ->
                                    val id = UUID.randomUUID().toString()
                                    batch.set(device.reference.collection("callLogs").document(id), mapOf(
                                        "callId" to id,
                                        "deviceId" to device.id,
                                        "number" to call.number,
                                        "direction" to call.direction,
                                        "callTimestamp" to Date(call.timestamp),
                                        "durationSeconds" to call.durationSeconds,
                                        "sharedByUid" to uid,
                                        "sharedAt" to FieldValue.serverTimestamp(),
                                        "source" to "USER_APPROVED_DEVICE_CALL_LOG",
                                        "schemaVersion" to 1
                                    ))
                                }
                                check(context.getSharedPreferences("collector", Context.MODE_PRIVATE).getBoolean("consent", false)) { "Sharing disabled." }
                                check(auth.currentUser?.uid == uid) { "Account changed." }
                                batch.commit().await()
                                records = emptyList()
                                chosen = emptySet()
                                confirmed = false
                                onStatus("Uploaded ${selected.size} explicitly approved call records to Firebase.")
                            } catch (e: Exception) {
                                onStatus("Call-log upload failed: ${e.localizedMessage}")
                            } finally { busy = false }
                        }
                    }
                ) { Text(if (busy) "Uploading…" else "Approve & Upload ${chosen.size} records") }
            }
            if (!consent) Text("Enable overall sharing consent before uploading.", color = MaterialTheme.colorScheme.error)
        }
    }
}
