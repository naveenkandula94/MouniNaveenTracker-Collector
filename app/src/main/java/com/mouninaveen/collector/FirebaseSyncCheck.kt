package com.mouninaveen.collector

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Owner-only Firebase permission and per-device path verification.
 * Runs only when the signed-in user taps the check button.
 * Uses server-side counts: does not load call or SMS contents into the UI.
 */
private fun errorDiagnosis(error: Throwable): String {
    val firestoreError = generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
    return when (firestoreError?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "PERMISSION_DENIED: Published Firebase rules do not permit this account/path."
        FirebaseFirestoreException.Code.UNAVAILABLE ->
            "UNAVAILABLE: Firestore server unreachable. Check Wi-Fi, mobile data, VPN or DNS."
        FirebaseFirestoreException.Code.UNAUTHENTICATED ->
            "UNAUTHENTICATED: Sign in again and confirm this phone's Firebase account."
        else -> when (error) {
            is kotlinx.coroutines.TimeoutCancellationException ->
                "NO SERVER RESPONSE within 15 seconds. Check Firebase connection, network/VPN and deployed project."
            else -> "${error.javaClass.simpleName}: ${error.localizedMessage?.take(140) ?: "Unknown error"}"
        }
    }
}

@Composable
internal fun FirebaseSyncCheck() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val deviceId = uid?.let { DeviceBinding.selectedId(context, it) }
    var checking by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var writeResult by remember { mutableStateOf("") }
    var results by remember(uid, deviceId) { mutableStateOf(emptyList<String>()) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Firebase connection check", style = MaterialTheme.typography.titleMedium)
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val projectId = FirebaseApp.getInstance().options.projectId.orEmpty()
            Text("Firebase project: $projectId", style = MaterialTheme.typography.bodySmall)
            Text(
                "Android network: " + if (!hasInternet) "NO CONNECTIVITY" else if (!validated)
                    "Connected, internet not validated" else "Internet validated",
                color = if (validated) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
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
                    suspend fun verifyPath(label: String, action: suspend () -> String) {
                        try {
                            lines += "✓ $label: ${withTimeout(15_000L) { action() }}"
                        } catch (e: Exception) {
                            lines += "✗ $label: ${errorDiagnosis(e)}"
                        }
                        results = lines.toList()
                    }
                    val device = db.collection("devices").document(selected)
                    verifyPath("Selected phone enrollment") {
                        val document = device.get(Source.SERVER).await()
                        check(document.exists() && document.getString("collectorUid") == owner) {
                            "This account is not enrolled for the selected phone."
                        }
                        check(document.getBoolean("enabled") != false) { "Enrollment disabled." }
                        document.getString("displayName") ?: "Active enrollment"
                    }
                    verifyPath("Call backup storage") {
                        db.collection("users").document(owner)
                            .collection("deviceCallBackups").document(selected)
                            .collection("calls").count().get(AggregateSource.SERVER)
                            .await().count.toString() + " stored"
                    }
                    verifyPath("SMS backup storage") {
                        db.collection("users").document(owner)
                            .collection("deviceSmsBackups").document(selected)
                            .collection("messages").count().get(AggregateSource.SERVER)
                            .await().count.toString() + " stored"
                    }
                    verifyPath("Lock/unlock history") {
                        device.collection("usageEvents").count().get(AggregateSource.SERVER)
                            .await().count.toString() + " stored"
                    }
                    verifyPath("Microphone signaling access") {
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
            Text(
                "Optional write test: updates only this enrolled phone's sync status to CONNECTION_TEST; " +
                    "it does not upload call, SMS, or microphone contents.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                enabled = !checking && !writing && uid != null && deviceId != null,
                onClick = {
                    val owner = uid ?: return@OutlinedButton
                    val selected = deviceId ?: return@OutlinedButton
                    writing = true
                    writeResult = "Testing Firebase server acknowledgement…"
                    scope.launch {
                        try {
                            val device = db.collection("devices").document(selected)
                            val doc = withTimeout(15_000L) {
                                device.get(Source.SERVER).await()
                            }
                            check(doc.exists() && doc.getString("collectorUid") == owner &&
                                doc.getBoolean("enabled") != false) {
                                "Selected enrollment is missing, disabled, or belongs to another collector."
                            }
                            withTimeout(15_000L) {
                                device.update("lastSyncStatus", "CONNECTION_TEST").await()
                            }
                            writeResult = "✓ Firestore SERVER WRITE acknowledged (not a data-sync result)."
                        } catch (e: Exception) {
                            writeResult = "✗ Firestore write test: ${errorDiagnosis(e)}"
                        } finally {
                            writing = false
                        }
                    }
                }
            ) { Text(if (writing) "Testing…" else "Test Firebase server write") }
            if (writeResult.isNotBlank()) Text(
                writeResult,
                color = if (writeResult.startsWith("✗")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall
            )
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
