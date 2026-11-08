package com.mouninaveen.collector

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("collector", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val auth = FirebaseAuth.getInstance()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF70465F), secondary = androidx.compose.ui.graphics.Color(0xFFB08A48))) {
                var consent by remember { mutableStateOf(prefs.getBoolean("consent", false)) }
                var usageGranted by remember { mutableStateOf(hasUsageAccess(this@MainActivity)) }
                var email by remember { mutableStateOf(auth.currentUser?.email.orEmpty()) }
                var password by remember { mutableStateOf("") }
                var status by remember { mutableStateOf("Review the disclosure and enable sharing only if you agree.") }
                var busy by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("The Wedding of Mouni & Naveen", style = MaterialTheme.typography.headlineSmall)
                    Text("MouniNaveen Collector", style = MaterialTheme.typography.titleLarge)
                    Text("Privacy & sharing controls", style = MaterialTheme.typography.headlineSmall)
                    Text("Sharing is voluntary, visible and can be withdrawn. No SMS or call-log permissions are requested.")
                    Text("WHAT THIS APP SHARES", style = MaterialTheme.typography.titleMedium)
                    Text("• Device keyguard locked/unlocked events (when Android exposes them).\n• Event date/time and device timezone.\n• Random enrollment document ID and sync status.\n• No app activity tracking.")
                    Text("WHY AND WHERE", style = MaterialTheme.typography.titleMedium)
                    Text("Only lock/unlock transitions are sent to the MouniNaveenTracker Firebase project and may be viewed by the administrator who enrolled this device. Android can delay or omit these events; this is not real-time monitoring.")
                    Text("NOT COLLECTED", style = MaterialTheme.typography.titleMedium)
                    Text("No app activity, SMS messages, call history, contacts, notification contents, passwords, camera, microphone, screenshots or private files are collected.")
                    Text("Android runs periodic sync no more frequently than approximately every 15 minutes; a manual sync request can be queued sooner but is not guaranteed instant. Data may use mobile data. You can stop sharing here at any time. Stopping prevents future collection/uploads; ask the administrator to delete records already uploaded.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !consent, onClick = { prefs.edit().putBoolean("consent", true).putLong("consent_at", System.currentTimeMillis()).apply(); consent = true; status = "Consent saved on this device. Sign in and grant Usage Access to start sync." }) { Text("I consent") }
                        OutlinedButton(onClick = { prefs.edit().putBoolean("consent", false).putLong("last_sync", System.currentTimeMillis()).apply(); consent = false; WorkManager.getInstance(this@MainActivity).cancelUniqueWork("usage-sync"); status = "Sharing stopped. Future uploads are disabled. Previously uploaded records remain until deleted by the administrator." }) { Text("Stop sharing") }
                    }
                    HorizontalDivider()
                    Text("Call history: user-controlled", style = MaterialTheme.typography.titleMedium)
                    Text("Call history and SMS contents are not read or uploaded by this app. To share particular information, open your Phone or Messages app, select the record, and use Android\u2019s built-in Share action. You control the recipient and what is sent.")
                    Text("Private sharing checklist: review recipients, remove sensitive details, then confirm in the Android share sheet.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "I am sharing information I have reviewed and selected myself.")
                        }
                        startActivity(Intent.createChooser(intent, "Choose how to share"))
                    }) { Text("Open manual sharing") }
                    HorizontalDivider()
                    Text("Android permission", style = MaterialTheme.typography.titleMedium)
                    Text("Usage Access: ${if (usageGranted) "Granted" else "Not granted"}")
                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open Usage Access settings") }
                    HorizontalDivider()
                    Text("Collector account", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(email, { email = it }, label = { Text("Collector email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Collector password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = consent && email.isNotBlank() && password.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true
                        auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
                            busy = false
                            if (task.isSuccessful) {
                                status = "Signed in. Checking enrollment…"
                                lifecycleScope.launch {
                                    try {
                                        val uid = auth.currentUser?.uid ?: error("No authenticated user")
                                        val docs = FirebaseFirestore.getInstance().collection("devices").whereEqualTo("collectorUid", uid).get().await()
                                        if (docs.isEmpty) status = "Signed in, but this account is not enrolled. Ask the administrator to add this Collector UID to a device."
                                        else if (docs.documents.none { it.getBoolean("enabled") != false }) status = "Enrollment is disabled by the administrator."
                                        else if (!hasUsageAccess(this@MainActivity)) status = "Signed in. Grant Usage Access to enable collection."
                                        else { scheduleSync(); status = "Enrolled. Lock/unlock sync is scheduled; Android may delay execution." }
                                    } catch (e: Exception) { status = "Enrollment check failed: ${e.localizedMessage ?: "Firestore access denied"}" }
                                }
                            } else status = "Sign-in failed: ${task.exception?.localizedMessage ?: "Check account details"}"
                        }
                    }) { Text(if (busy) "Signing in…" else "Sign in") }
                    if (auth.currentUser != null) {
                        Text("Account status: Signed in (${auth.currentUser?.email ?: ""})")
                        Text("Collector UID: ${auth.currentUser?.uid.orEmpty()}")
                        OutlinedButton(onClick = {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Collector UID", auth.currentUser?.uid.orEmpty()))
                            status = "Collector UID copied. Enroll this UID in the Admin app."
                        }) { Text("Copy Collector UID") }
                        OutlinedButton(onClick = { WorkManager.getInstance(this@MainActivity).cancelUniqueWork("usage-sync"); auth.signOut(); password = ""; status = "Signed out. Collection will not run until you sign in again." }) { Text("Sign out") }
                    } else Text("Account status: Not signed in")
                    Text("Sharing: ${if (consent) "Consent given" else "Stopped / not consented"} · Usage Access: ${if (usageGranted) "Granted" else "Missing"}")
                    Text(status)
                    Button(onClick = { usageGranted = hasUsageAccess(this@MainActivity); if (consent && auth.currentUser != null && usageGranted) { scheduleSync(); status = "Lock/unlock sync queued. Android controls exact timing." } }) { Text("Refresh permission and sync status") }
                }
            }
        }
    }
    private fun scheduleSync() {
        if (!prefs.getBoolean("consent", false) || !hasUsageAccess(this) || FirebaseAuth.getInstance().currentUser == null) return
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("usage-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<UsageSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION") val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}
