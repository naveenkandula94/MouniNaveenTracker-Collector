package com.mouninaveen.collector

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private data class Enrollment(val id: String, val name: String)

@Composable
fun PhoneSelection(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val scope = rememberCoroutineScope()
    var options by remember(uid) { mutableStateOf(emptyList<Enrollment>()) }
    var selected by remember(uid) { mutableStateOf(if (uid == null) null else DeviceBinding.selectedId(context, uid)) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        if (uid == null || busy) return
        busy = true
        scope.launch {
            try {
                val docs = FirebaseFirestore.getInstance().collection("devices")
                    .whereEqualTo("collectorUid", uid).get().await().documents
                    .filter { it.getBoolean("enabled") != false }
                options = docs.map { Enrollment(it.id, it.getString("displayName") ?: "Android phone") }
                if (selected == null && options.size == 1) {
                    DeviceBinding.bind(context, uid, options.first().id)
                    selected = options.first().id
                }
                onStatus("Found ${options.size} active phone enrollment(s).")
            } catch (e: Exception) {
                onStatus("Phone enrollment check failed: ${e.localizedMessage}")
            } finally { busy = false }
        }
    }

    LaunchedEffect(uid) { if (uid != null) refresh() }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("This phone", style = MaterialTheme.typography.titleLarge)
            Text("Choose the correct phone for this installation. This prevents two phones using the same Firebase UID from mixing calls and activity.",
                style = MaterialTheme.typography.bodySmall)
            if (options.isEmpty()) {
                Text("No enrollments loaded. Add a device in Admin_App > Settings, then refresh.")
            }
            options.forEach { option ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(option.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (selected == option.id) "Selected on this phone" else "Not selected",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        enabled = !busy && selected != option.id,
                        onClick = {
                            val oldId = selected
                            if (oldId != option.id) {
                                CallBackupWorker.stop(context)
                                DeviceBinding.bind(context, uid!!, option.id)
                                selected = option.id
                                if (context.getSharedPreferences(CallBackupWorker.PREFS, android.content.Context.MODE_PRIVATE)
                                        .getBoolean(CallBackupWorker.ENABLED, false)) {
                                    CallBackupWorker.schedule(context)
                                }
                                onStatus("This phone is now linked to ${option.name}. Future backups are separated by device.")
                            }
                        }
                    ) { Text(if (selected == option.id) "Selected" else "Use this phone") }
                }
                HorizontalDivider()
            }
            TextButton(enabled = !busy && uid != null, onClick = { refresh() }) { Text("Refresh linked phones") }
        }
    }
}
