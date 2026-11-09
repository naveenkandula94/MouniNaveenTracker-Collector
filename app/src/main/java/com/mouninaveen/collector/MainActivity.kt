package com.mouninaveen.collector

import android.app.AppOpsManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("collector", MODE_PRIVATE) }
    private var callChangeObserver: ContentObserver? = null

    override fun onResume() {
        super.onResume()
        if (getSharedPreferences(CallBackupWorker.PREFS, MODE_PRIVATE)
                .getBoolean(CallBackupWorker.ENABLED, false) &&
            FirebaseAuth.getInstance().currentUser?.uid ==
                getSharedPreferences(CallBackupWorker.PREFS, MODE_PRIVATE)
                    .getString(CallBackupWorker.OWNER, "") &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            CallBackupWorker.schedule(this)
            if (callChangeObserver == null) {
                callChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (!selfChange) CallBackupWorker.trigger(this@MainActivity)
                    }
                }
                contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callChangeObserver!!)
            }
        }
    }

    override fun onPause() {
        callChangeObserver?.let { contentResolver.unregisterContentObserver(it) }
        callChangeObserver = null
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF3459AD),
                secondary = androidx.compose.ui.graphics.Color(0xFF178A83),
                surface = androidx.compose.ui.graphics.Color(0xFFF8FAFF),
                background = androidx.compose.ui.graphics.Color(0xFFF4F6FC)
            )) {
                CollectorScreen()
            }
        }
    }

    @Composable
    private fun CollectorScreen() {
        val auth = remember { FirebaseAuth.getInstance() }
        var signedIn by remember { mutableStateOf(auth.currentUser != null) }
        var consent by remember { mutableStateOf(prefs.getBoolean("consent", false)) }
        var usageGranted by remember { mutableStateOf(hasUsageAccess(this@MainActivity)) }
        var email by remember { mutableStateOf(auth.currentUser?.email.orEmpty()) }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var tab by remember { mutableIntStateOf(0) }
        var status by remember { mutableStateOf("Select a feature to see its status.") }
        var diagnosticVersion by remember { mutableIntStateOf(0) }
        val context = LocalContext.current

        fun report(category: String, message: String) {
            status = message
            CollectorDiagnostics.record(context, category, message)
            diagnosticVersion++
        }

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text("COLLECTOR", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Device sharing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "${if (signedIn) "Signed in" else "Not signed in"}  •  ${if (consent) "Sharing enabled" else "Sharing off"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    listOf("Home", "Calls", "Live audio", "Account", "Logs").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                Text(listOf("⌂","☎","◉","●","≡")[index], style = MaterialTheme.typography.titleMedium)
                            },
                            label = { Text(label) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    0 -> {
                        Text("Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Sharing controls", style = MaterialTheme.typography.titleMedium)
                                Text("Your choices control uploads. Live microphone requires a separate start action each session.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !consent,
                                        onClick = {
                                            prefs.edit().putBoolean("consent", true).putLong("consent_at", System.currentTimeMillis()).apply()
                                            consent = true
                                            report("CONSENT", "Sharing enabled by user.")
                                        }
                                    ) { Text("Enable sharing") }
                                    OutlinedButton(
                                        onClick = {
                                            prefs.edit().putBoolean("consent", false).putLong("last_sync", System.currentTimeMillis()).apply()
                                            consent = false
                                            WorkManager.getInstance(this@MainActivity).cancelUniqueWork("usage-sync")
                                            report("CONSENT", "Sharing stopped. Previously uploaded records require separate deletion.")
                                        }
                                    ) { Text("Stop sharing") }
                                }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Device status", style = MaterialTheme.typography.titleMedium)
                                Text("Account: ${if (signedIn) "Connected" else "Sign-in required"}")
                                Text("Usage Access: ${if (usageGranted) "Granted" else "Not granted"}")
                                Text("Last event upload: Android-managed; not guaranteed instant")
                                OutlinedButton(onClick = {
                                    usageGranted = hasUsageAccess(this@MainActivity)
                                    if (consent && signedIn && usageGranted) {
                                        scheduleSync()
                                        report("SYNC", "Lock/unlock sync queued.")
                                    } else report("SYNC", "Sync requires sign-in, consent, and Usage Access.")
                                }) { Text("Refresh and sync now") }
                                TextButton(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                                    Text("Open Usage Access settings")
                                }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("What is shared", style = MaterialTheme.typography.titleMedium)
                                Text("• Lock/unlock timestamps with Usage Access and consent\n• Private call backups for your signed-in account only when enabled under Calls\n• Live microphone audio only during an owner-started session\n• Connection and sync diagnostics stay on this phone")
                                Text("No SMS, WhatsApp chats, Instagram messages, camera or screenshots are collected.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    1 -> {
                        Text("Calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("One-time permission enables private, automatic call backup to your own signed-in account. You can disable or delete it anytime.")
                        PrivateCallBackup(onStatus = { report("BACKUP", it) })
                    }
                    2 -> {
                        Text("Live audio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Start or stop your live audio session below. Switching app tabs will not stop an active session.")
                    }
                    3 -> {
                        Text("Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (signedIn) {
                                    Text("Signed in as ${auth.currentUser?.email.orEmpty()}")
                                    Text("Collector UID", style = MaterialTheme.typography.labelLarge)
                                    Text(auth.currentUser?.uid.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                            .setPrimaryClip(android.content.ClipData.newPlainText("Collector UID", auth.currentUser?.uid.orEmpty()))
                                        report("ACCOUNT", "Collector UID copied for enrollment.")
                                    }) { Text("Copy UID") }
                                    Button(onClick = {
                                        WorkManager.getInstance(this@MainActivity).cancelUniqueWork("usage-sync")
                                        CallBackupWorker.stop(this@MainActivity)
                                        auth.signOut()
                                        signedIn = false
                                        password = ""
                                        report("ACCOUNT", "Signed out; sharing sessions will close.")
                                    }) { Text("Sign out") }
                                } else {
                                    Text("Sign in to your enrolled Firebase account.")
                                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Button(
                                        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                                        onClick = {
                                            busy = true
                                            auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
                                                busy = false
                                                if (task.isSuccessful) {
                                                    signedIn = true
                                                    password = ""
                                                    report("ACCOUNT", "Signed in. Checking Collector enrollment.")
                                                    lifecycleScope.launch {
                                                        try {
                                                            val uid = auth.currentUser?.uid ?: error("Authentication missing")
                                                            val docs = FirebaseFirestore.getInstance().collection("devices").whereEqualTo("collectorUid", uid).get().await()
                                                            if (docs.isEmpty) report("ENROLL", "No enrolled device for this UID. Enroll from Admin > Devices.")
                                                            else if (docs.documents.none { it.getBoolean("enabled") != false }) report("ENROLL", "All enrolled devices disabled.")
                                                            else {
                                                                if (getSharedPreferences(CallBackupWorker.PREFS, MODE_PRIVATE).getBoolean(CallBackupWorker.ENABLED, false) &&
                                                                    getSharedPreferences(CallBackupWorker.PREFS, MODE_PRIVATE).getString(CallBackupWorker.OWNER, "") == uid) {
                                                                    CallBackupWorker.schedule(this@MainActivity)
                                                                }
                                                                if (consent && hasUsageAccess(this@MainActivity)) scheduleSync()
                                                                report("ENROLL", "Collector enrollment verified.")
                                                            }
                                                        } catch (e: Exception) {
                                                            report("ERROR", "Enrollment check: ${e.localizedMessage ?: "Firestore rules denied read"}")
                                                        }
                                                    }
                                                } else report("ERROR", "Sign-in failed: ${task.exception?.localizedMessage ?: "Unknown error"}")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (busy) "Signing in…" else "Sign in") }
                                }
                            }
                        }
                    }
                    else -> {
                        Text("Diagnostic logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("App-private diagnostic events. No phone numbers, passwords, audio or message contents are logged.")
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Last status: $status", style = MaterialTheme.typography.bodyMedium)
                                key(diagnosticVersion) {
                                    Text(CollectorDiagnostics.read(context), style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = {
                                    CollectorDiagnostics.clear(context)
                                    diagnosticVersion++
                                    status = "Diagnostics cleared."
                                }) { Text("Clear local logs") }
                                OutlinedButton(onClick = { diagnosticVersion++ }) { Text("Refresh logs") }
                            }
                        }
                    }
                }
                // Keep this composable in the tree across tab switches. Removing it destroyed
                // the WebRTC peer and caused unexpected CLOSED sessions on tab navigation.
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (tab == 2) Modifier else Modifier.height(0.dp))
                        .clipToBounds()
                ) {
                    LiveMicrophonePublisher(
                        sharingEnabled = consent && signedIn,
                        onStatus = { report("AUDIO", it) }
                    )
                }
                if (tab != 4 && status.isNotBlank()) {
                    Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun scheduleSync() {
        if (!prefs.getBoolean("consent", false) || !hasUsageAccess(this) || FirebaseAuth.getInstance().currentUser == null) return
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("usage-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<UsageSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
    }
}

fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}
