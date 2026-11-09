package com.mouninaveen.collector

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Owner-only Firebase permission and per-device path verification.
 * Runs only when the signed-in user taps the check button.
 * Uses server-side counts: does not load call or SMS contents into the UI.
 */
@Composable
internal fun FirebaseSyncCheck() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val deviceId = uid?.let { DeviceBinding.selectedId(context, it) }
    var checking by remember { mutableStateOf(false) }
    var results by remember(uid, deviceId) { mutableStateOf(emptyList<String>()) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Firebase connection check", style = MaterialTheme.typography.titleMedium)
            Text(
                "Checks real server access for the selected phone and its backup paths. " +
                    "Android permission being granted does not prove that records were uploaded.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(enabled = !checking && uid != null && deviceId != null, onClick = {
                val owner = uid ?: return@Button
                val selected = deviceId ?: return@Button
                checking = true
                results = listOf("Contacting Firebase server…")
                scope.launch {
                    val lines = mutableListOf<String>()
                    suspend fun check(label: String, action: suspend () -> String) {
                        try {
                            lines += "✓ $label: ${withTimeout(15_000L) { action() }}"
                        } catch (e: Exception) {
                            val reason = e.localizedMessage?.take(150) ?: e.javaClass.simpleName
                            lines += "✗ $label: $reason"
                        }
                        results = lines.toList()
                    }
                    val device = db.collection("devices").document(selected)
                    check("Selected phone enrollment") {
                        val document = device.get(Source.SERVER).await()
                        check(document.exists() && document.getString("collectorUid") == owner) {
                            "This account is not enrolled for the selected phone."
                        }
                        check(document.getBoolean("enabled") != false) { "Enrollment disabled." }
                        document.getString("displayName") ?: "Active enrollment"
                    }
                    check("Call backup storage") {
                        db.collection("users").document(owner)
                            .collection("deviceCallBackups").document(selected)
                            .collection("calls").count().get(AggregateSource.SERVER)
                            .await().count.toString() + " stored"
                    }
                    check("SMS backup storage") {
                        db.collection("users").document(owner)
                            .collection("deviceSmsBackups").document(selected)
                            .collection("messages").count().get(AggregateSource.SERVER)
                            .await().count.toString() + " stored"
                    }
                    check("Lock/unlock history") {
                        device.collection("usageEvents").count().get(AggregateSource.SERVER)
                            .await().count.toString() + " stored"
                    }
                    check("Microphone signaling access") {
                        device.collection("liveAudio").count().get(AggregateSource.SERVER)
                            .await().count.toString() + " sessions"
                    }
                    if (lines.any { it.startsWith("✗") }) {
                        lines += "If Firebase says PERMISSION_DENIED, publish the complete matching Firestore rules from the current feature branch. Confirm both apps use the same project and the selected enrollment."
                    }
                    results = lines
                    checking = false
                }
            }) { Text(if (checking) "Checking…" else "Verify Firebase paths") }
            if (uid == null) {
                Text("Sign in to run the check.", color = MaterialTheme.colorScheme.error)
            } else if (deviceId == null) {
                Text("Select THIS phone under Account first.",
                    color = MaterialTheme.colorScheme.error)
            }
            results.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall,
                    color = if (line.startsWith("✗")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
