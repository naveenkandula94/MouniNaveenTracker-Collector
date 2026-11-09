package com.mouninaveen.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivateCallBackup(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(CallBackupWorker.PREFS, Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(CallBackupWorker.ENABLED, false)) }
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
    }
    var busy by remember { mutableStateOf(false) }
    var refreshed by remember { mutableIntStateOf(0) }
    var deletionConfirm by remember { mutableStateOf(false) }

    fun turnOn() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onStatus("Sign in on Account before enabling backup.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Call-log permission unavailable.")
            return
        }
        busy = true
        scope.launch {
            try {
                val device = DeviceBinding.resolve(context, uid)
                prefs.edit()
                    .putBoolean(CallBackupWorker.ENABLED, true)
                    .putString(CallBackupWorker.OWNER, uid)
                    .apply()
                enabled = true
                CallBackupWorker.schedule(context)
                (context as? MainActivity)?.refreshCallBackupObserver()
                onStatus("Automatic backup enabled for ${device.getString("displayName") ?: "this phone"}.")
            } catch (e: Exception) {
                onStatus("Select this phone in Account first: ${e.localizedMessage}")
            } finally { busy = false }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        if (granted) turnOn()
        else onStatus("Android did not grant the restricted call-log permission. No backup enabled.")
    }

    val currentOwner = auth.currentUser?.uid
    val ownerMatches = currentOwner != null && prefs.getString(CallBackupWorker.OWNER, "") == currentOwner
    val selectedDeviceId = currentOwner?.let { DeviceBinding.selectedId(context, it) }
    val lastSuccess = prefs.getLong(CallBackupWorker.LAST_SUCCESS, 0L)
    val lastError = prefs.getString(CallBackupWorker.LAST_ERROR, "").orEmpty()
    val lastCount = prefs.getInt(CallBackupWorker.LAST_COUNT, 0)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Private call-history backup", style = MaterialTheme.typography.titleLarge)
            Text(
                "Back up existing and new phone calls automatically to your own Firebase account after one-time approval. " +
                "This is not shared with another Admin account. You can disable or delete your backup anytime.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Phone: ${selectedDeviceId ?: "Not selected — choose this phone in Account"}", style = MaterialTheme.typography.bodySmall)
            Text("Permission: ${if (permission) "Granted" else "Not granted / restricted"}")
            Text("Backup: ${if (enabled && ownerMatches) "Enabled" else if (enabled) "Paused — sign in to the original account" else "Off"}")
            if (lastSuccess > 0L) {
                Text("Last successful backup: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(lastSuccess))}")
                Text("Records processed in last run: $lastCount")
            } else Text("Last successful backup: Not yet")
            if (lastError.isNotBlank()) Text("Latest error: $lastError", color = MaterialTheme.colorScheme.error)

            if (!enabled) {
                Button(
                    enabled = !busy && auth.currentUser != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                        if (permission) turnOn() else permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                    }
                ) { Text("Enable automatic private backup") }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        prefs.edit().putBoolean(CallBackupWorker.ENABLED, false).apply()
                        CallBackupWorker.stop(context)
                        (context as? MainActivity)?.refreshCallBackupObserver()
                        enabled = false
                        onStatus("Private call backup disabled. Existing backup remains until you delete it.")
                    }
                ) { Text("Disable backup") }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && ownerMatches && !busy,
                onClick = {
                    CallBackupWorker.trigger(context)
                    onStatus("Backup queued now. Background execution may take longer.")
                }
            ) { Text("Check for changes now") }
            Text(
                "New calls are picked up while the Collector app is running, and background checks are scheduled periodically. " +
                "Android may delay work; immediate sync is not guaranteed.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { refreshed++; permission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED }) {
                Text("Refresh backup status")
            }
            if (deletionConfirm) {
                Text("Delete private call backups for THIS selected phone? This cannot be undone.", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { deletionConfirm = false }) { Text("Cancel") }
                    Button(enabled = !busy && auth.currentUser != null && ownerMatches, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val uid = auth.currentUser?.uid ?: error("Sign in first.")
                                check(uid == prefs.getString(CallBackupWorker.OWNER, "")) { "Backup belongs to another account." }
                                prefs.edit().putBoolean(CallBackupWorker.ENABLED, false).apply()
                                CallBackupWorker.stop(context)
                                enabled = false
                                val deviceId = DeviceBinding.selectedId(context, uid)
                                    ?: error("Choose this phone in Account.")
                                val collection = FirebaseFirestore.getInstance()
                                    .collection("users").document(uid)
                                    .collection("deviceCallBackups").document(deviceId).collection("calls")
                                var deleted = 0
                                while (true) {
                                    val docs = collection.limit(250).get().await().documents
                                    if (docs.isEmpty()) break
                                    val batch = FirebaseFirestore.getInstance().batch()
                                    docs.forEach { batch.delete(it.reference) }
                                    batch.commit().await()
                                    deleted += docs.size
                                }
                                prefs.edit().remove("${CallBackupWorker.LAST_CALL_DATE}_$deviceId")
                                    .remove(CallBackupWorker.LAST_SUCCESS)
                                    .remove(CallBackupWorker.LAST_COUNT).apply()
                                onStatus("Deleted $deleted private backup records from Firebase.")
                            } catch (e: Exception) {
                                onStatus("Backup deletion failed: ${e.localizedMessage}")
                            } finally {
                                busy = false
                                deletionConfirm = false
                                refreshed++
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete all") }
                }
            } else {
                TextButton(enabled = !busy && auth.currentUser != null && ownerMatches && selectedDeviceId != null, onClick = { deletionConfirm = true }) {
                    Text("Delete this phone’s private call backups", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    // Re-evaluate persisted progress whenever the user opens this tab or presses Refresh.
    key(refreshed) { }
}
