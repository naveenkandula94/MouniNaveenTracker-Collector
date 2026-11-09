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
                if (selected != null && options.none { it.id == selected }) {
                    selected = null
                }
                onStatus(if (selected == null) "Choose THIS phone manually from the available enrollments." else "Phone selection verified. ${options.size} enrollment(s) available.")
            } catch (e: Exception) {
                onStatus("Phone enrollment check failed: ${e.localizedMessage}")
            } finally { busy = false }
        }
    }

    LaunchedEffect(uid) { if (uid != null) refresh() }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("This phone", style = MaterialTheme.typography.titleLarge)
            Text("Required: tap Use this phone on the correct device. No phone will be selected automatically. Your choice is saved for this installation.",
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
                                SmsBackupWorker.stop(context)
                                if (oldId != null && oldId != option.id) {
                                    // An SMS consent is scoped to the chosen phone.
                                    // Never silently carry it over to another enrollment.
                                    context.getSharedPreferences(
                                        SmsBackupWorker.PREFS, android.content.Context.MODE_PRIVATE
                                    ).edit().putBoolean(SmsBackupWorker.ENABLED, false).apply()
                                }
                                DeviceBinding.bind(context, uid!!, option.id)
                                // Prevent a previous phone selection's event cursor from skipping
                                // the new phone's first available lock/unlock events.
                                context.getSharedPreferences("collector", android.content.Context.MODE_PRIVATE)
                                    .edit().remove("last_sync").apply()
                                selected = option.id
                                (context as? MainActivity)?.scheduleSync()
                                if (context.getSharedPreferences(CallBackupWorker.PREFS, android.content.Context.MODE_PRIVATE)
                                        .getBoolean(CallBackupWorker.ENABLED, false)) {
                                    CallBackupWorker.schedule(context)
                                }
                                if (context.getSharedPreferences(SmsBackupWorker.PREFS, android.content.Context.MODE_PRIVATE)
                                        .getBoolean(SmsBackupWorker.ENABLED, false)) {
                                    SmsBackupWorker.schedule(context)
                                    (context as? MainActivity)?.refreshSmsBackupObserver()
                                }
                                onStatus("Selected ${option.name}. This installation now uses only this phone's backup and activity paths.")
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
