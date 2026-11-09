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
internal fun PrivateSmsBackup(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(SmsBackupWorker.PREFS, Context.MODE_PRIVATE)
    }
    val uid = auth.currentUser?.uid
    val deviceId = uid?.let { DeviceBinding.selectedId(context, it) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(SmsBackupWorker.ENABLED, false)) }
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED)
    }
    var consentDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    fun enableBackup() {
        val owner = auth.currentUser?.uid
        if (owner == null || !permitted) {
            onStatus("Sign in and grant eligible Android SMS permission first.")
            return
        }
        busy = true
        scope.launch {
            try {
                val device = DeviceBinding.resolve(context, owner)
                prefs.edit()
                    .putBoolean(SmsBackupWorker.ENABLED, true)
                    .putString(SmsBackupWorker.OWNER, owner)
                    .apply()
                enabled = true
                SmsBackupWorker.schedule(context)
                (context as? MainActivity)?.refreshSmsBackupObserver()
                onStatus("Private SMS backup enabled for ${device.getString("displayName") ?: "selected phone"}.")
            } catch (e: Exception) {
                onStatus("Choose this phone in Account first: ${e.localizedMessage}")
            } finally { busy = false }
        }
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permitted = granted
        if (granted) enableBackup()
        else onStatus("Android did not grant restricted READ_SMS permission. SMS backup is off.")
    }

    if (consentDialog) {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text("Enable automatic SMS backup?") },
            text = { Text(
                "Personal_App will read existing and future SMS, including sender phone numbers, " +
                "full message content, directions and timestamps, then upload to your own Firebase UID " +
                "for the selected phone. Only the signed-in owner UID can view or delete these backups. " +
                "Sensitive messages and one-time codes may be included. You can disable or delete at any time. " +
                "Android restricts READ_SMS; permission may be unavailable."
            ) },
            confirmButton = {
                Button(onClick = {
                    consentDialog = false
                    permitted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_SMS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (permitted) enableBackup()
                    else requestPermission.launch(Manifest.permission.READ_SMS)
                }) { Text("Accept and request access") }
            },
            dismissButton = {
                OutlinedButton(onClick = { consentDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete this phone's SMS cloud backup?") },
            text = { Text("All SMS backups for the selected phone will be permanently removed from Firebase. SMS on the phone will not be deleted. Backup will first be disabled.") },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    deleteDialog = false
                    busy = true
                    scope.launch {
                        try {
                            val owner = auth.currentUser?.uid ?: error("Sign in first.")
                            val selected = DeviceBinding.selectedId(context, owner)
                                ?: error("Select this phone in Account.")
                            check(prefs.getString(SmsBackupWorker.OWNER, "") == owner) {
                                "This backup belongs to another Firebase account."
                            }
                            prefs.edit().putBoolean(SmsBackupWorker.ENABLED, false).apply()
                            SmsBackupWorker.stop(context)
                            (context as? MainActivity)?.refreshSmsBackupObserver()
                            enabled = false
                            val db = FirebaseFirestore.getInstance()
                            val coll = db.collection("users").document(owner)
                                .collection("deviceSmsBackups").document(selected)
                                .collection("messages")
                            var deleted = 0
                            while (true) {
                                val docs = coll.limit(200).get().await().documents
                                if (docs.isEmpty()) break
                                val batch = db.batch()
                                docs.forEach { batch.delete(it.reference) }
                                batch.commit().await()
                                deleted += docs.size
                            }
                            prefs.edit().remove("${SmsBackupWorker.LAST_DATE}_$selected").apply()
                            onStatus("Deleted $deleted SMS cloud backups for this phone.")
                        } catch (e: Exception) {
                            onStatus("SMS deletion failed: ${e.localizedMessage}")
                        } finally { busy = false; refresh++ }
                    }
                }, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )) { Text("Delete backups") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Personal SMS backup", style = MaterialTheme.typography.titleLarge)
            Text(
                "Optional owner-controlled cloud backup. Personal_App collects only after you enable " +
                "it and Android grants SMS access. No SMS content is copied to a different Admin account.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Phone: ${deviceId ?: "Select this phone in Account first"}")
            Text("READ_SMS: ${if (permitted) "Granted" else "Not granted / restricted"}")
            Text("Backup: ${if (enabled && prefs.getString(SmsBackupWorker.OWNER, "") == uid) "On" else "Off"}")
            val last = prefs.getLong(SmsBackupWorker.LAST_SUCCESS, 0L)
            if (last > 0L) {
                Text("Last saved: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(last))}")
                Text("Messages checked in last run: ${prefs.getInt(SmsBackupWorker.LAST_COUNT, 0)}")
            }
            val error = prefs.getString(SmsBackupWorker.LAST_ERROR, "").orEmpty()
            if (error.isNotBlank()) Text("Last error: $error", color = MaterialTheme.colorScheme.error)
            if (!enabled) {
                Button(
                    enabled = !busy && uid != null && deviceId != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { consentDialog = true }
                ) { Text("Enable private SMS backup") }
            } else {
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    prefs.edit().putBoolean(SmsBackupWorker.ENABLED, false).apply()
                    SmsBackupWorker.stop(context)
                    (context as? MainActivity)?.refreshSmsBackupObserver()
                    enabled = false
                    onStatus("SMS backup disabled; existing cloud messages remain.")
                }) { Text("Disable SMS backup") }
                OutlinedButton(onClick = {
                    SmsBackupWorker.trigger(context)
                    onStatus("SMS backup check queued.")
                }) { Text("Check new messages") }
            }
            OutlinedButton(onClick = {
                permitted = (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
                refresh++
            }) { Text("Refresh SMS permission and status") }
            TextButton(enabled = uid != null && deviceId != null && !busy,
                onClick = { deleteDialog = true }) {
                Text("Delete this phone's SMS cloud backup",
                    color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Android may deny restricted SMS access even with consent. Background WorkManager " +
                "checks are periodic and can be delayed. No WhatsApp or Instagram chats are accessed.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    key(refresh) {}
}
