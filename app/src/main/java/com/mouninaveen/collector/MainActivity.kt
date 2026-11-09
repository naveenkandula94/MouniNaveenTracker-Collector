package com.mouninaveen.collector

import android.app.AppOpsManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("collector", MODE_PRIVATE) }
    private var callChangeObserver: ContentObserver? = null
    private var smsChangeObserver: ContentObserver? = null
    private var foregroundLockJob: Job? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    private fun queueLockCheck() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!prefs.getBoolean("consent", false) || !hasUsageAccess(this) ||
            DeviceBinding.selectedId(this, uid) == null) return
        val work = OneTimeWorkRequestBuilder<UsageSyncWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "lock-check-auto", ExistingWorkPolicy.KEEP, work
        )
    }

    private fun startForegroundLockChecks() {
        foregroundLockJob?.cancel()
        foregroundLockJob = lifecycleScope.launch {
            while (isActive) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid != null && prefs.getBoolean("consent", false) &&
                    hasUsageAccess(this@MainActivity) &&
                    DeviceBinding.selectedId(this@MainActivity, uid) != null) {
                    try {
                        // Run Firebase SDK writes directly while visible; no WorkManager queue.
                        UsageSyncEngine.sync(this@MainActivity)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        CollectorDiagnostics.record(
                            this@MainActivity, "LOCK",
                            "Foreground sync failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
                        )
                    }
                }
                delay(60_000L)
            }
        }
    }


    /**
     * Observe phone call-log changes only while this Activity is visible.
     * The observer does not read or upload records unless private backup is enabled.
     */
    fun refreshCallBackupObserver() {
        callChangeObserver?.let { contentResolver.unregisterContentObserver(it) }
        callChangeObserver = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) !=
                PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(CallBackupWorker.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(CallBackupWorker.ENABLED, false) &&
            FirebaseAuth.getInstance().currentUser?.uid == prefs.getString(CallBackupWorker.OWNER, "")
        ) {
            CallBackupWorker.schedule(this)
        }
        callChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (selfChange) return
                val activePrefs = getSharedPreferences(CallBackupWorker.PREFS, MODE_PRIVATE)
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid != null && activePrefs.getBoolean(CallBackupWorker.ENABLED, false) &&
                    uid == activePrefs.getString(CallBackupWorker.OWNER, "")
                ) CallBackupWorker.trigger(this@MainActivity)
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callChangeObserver!!)
    }

    /** Observe SMS changes only after explicit user enablement and Android permission. */
    fun refreshSmsBackupObserver() {
        smsChangeObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsChangeObserver = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED) return
        val smsPrefs = getSharedPreferences(SmsBackupWorker.PREFS, MODE_PRIVATE)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null || !smsPrefs.getBoolean(SmsBackupWorker.ENABLED, false) ||
            smsPrefs.getString(SmsBackupWorker.OWNER, "") != uid ||
            DeviceBinding.selectedId(this, uid) == null) return
        SmsBackupWorker.schedule(this)
        smsChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!selfChange && smsPrefs.getBoolean(SmsBackupWorker.ENABLED, false) &&
                    FirebaseAuth.getInstance().currentUser?.uid == uid) {
                    SmsBackupWorker.trigger(this@MainActivity)
                }
            }
        }
        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI, true, smsChangeObserver!!
        )
    }

    override fun onResume() {
        super.onResume()
        refreshCallBackupObserver()
        refreshSmsBackupObserver()
        BackgroundSyncCoordinator.maintain(this, requestCatchUp = true)
        // While Personal_App is visible, check keyguard events every 15 seconds.
        // Android limits background WorkManager jobs to OS-managed timing.
        startForegroundLockChecks()
    }

    override fun onPause() {
        foregroundLockJob?.cancel()
        foregroundLockJob = null
        callChangeObserver?.let { contentResolver.unregisterContentObserver(it) }
        callChangeObserver = null
        smsChangeObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsChangeObserver = null
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A process-alive screen transition queues a fresh usage-events check.
        // This is best effort, not a permanent background 15-second service.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action in listOf(
                        Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_USER_PRESENT
                    )) queueLockCheck()
            }
        }
        screenStateReceiver = receiver
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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

    override fun onDestroy() {
        screenStateReceiver?.let { unregisterReceiver(it) }
        screenStateReceiver = null
        foregroundLockJob?.cancel()
        super.onDestroy()
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
        var checkedAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(signedIn) {
            val uid = auth.currentUser?.uid
            if (signedIn && uid != null &&
                DeviceBinding.selectedId(this@MainActivity, uid) == null) {
                tab = 4
                status = "Please select THIS phone under Account before syncing begins."
            }
        }
        LaunchedEffect(tab, signedIn) {
            while (signedIn && tab == 0) {
                val id = auth.currentUser?.uid?.let {
                    DeviceBinding.selectedId(this@MainActivity, it)
                }
                checkedAt = if (id == null) 0L else
                    prefs.getLong("last_heartbeat_$id", 0L)
                delay(60_000L)
            }
        }

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
                        Text("PERSONAL APP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Personal dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "${if (signedIn) "Signed in" else "Not signed in"}  •  ${if (consent) "Sharing enabled" else "Sharing off"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color(0xFFE9F2FE)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        listOf("Home", "Calls", "Messages", "Live", "Account", "Logs", "Access")
                            .forEachIndexed { index, label ->
                                NavigationBarItem(
                                    modifier = Modifier.width(84.dp),
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = {
                                        Text(listOf("⌂","☎","✉","◉","●","≡","✓")[index],
                                            style = MaterialTheme.typography.titleMedium)
                                    },
                                    label = { Text(label, maxLines = 1) },
                                    alwaysShowLabel = true
                                )
                            }
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
                                Text("Choose this phone in Account. Lock/unlock checks run automatically with consent and Usage Access. Live microphone requires a separate start action.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !consent,
                                        onClick = {
                                            prefs.edit().putBoolean("consent", true).putLong("consent_at", System.currentTimeMillis()).apply()
                                            consent = true
                                            BackgroundSyncCoordinator.maintain(this@MainActivity, requestCatchUp = true)
                                            report("CONSENT", "Sharing enabled; background work scheduled.")
                                        }
                                    ) { Text("Enable sharing") }
                                    OutlinedButton(
                                        onClick = {
                                            prefs.edit().putBoolean("consent", false).putLong("last_sync", System.currentTimeMillis()).apply()
                                            consent = false
                                            BackgroundSyncCoordinator.stopAll(this@MainActivity)
                                            getSharedPreferences(CallBackupWorker.PREFS, MODE_PRIVATE).edit().putBoolean(CallBackupWorker.ENABLED, false).apply()
                                            CallBackupWorker.stop(this@MainActivity)
                                            getSharedPreferences(SmsBackupWorker.PREFS, MODE_PRIVATE)
                                                .edit().putBoolean(SmsBackupWorker.ENABLED, false).apply()
                                            SmsBackupWorker.stop(this@MainActivity)
                                            MicrophoneStreamService.stop(this@MainActivity)
                                            refreshCallBackupObserver()
                                            refreshSmsBackupObserver()
                                            report("CONSENT", "All sharing and automatic call backup disabled. Existing private backup can be deleted under Calls.")
                                        }
                                    ) { Text("Stop sharing") }
                                }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Device status", style = MaterialTheme.typography.titleMedium)
                                Text("Account: ${if (signedIn) "Connected" else "Sign-in required"}")
                                Text(
                                    "Selected phone: ${auth.currentUser?.uid?.let { DeviceBinding.selectedId(context, it) } ?: "Not selected — choose in Account"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("Usage Access: ${if (usageGranted) "Granted" else "Not granted"}")
                                Text("Lock/unlock: direct Firebase checks every 60 seconds while the app is visible.")
                                Text("Last Firebase status update: " +
                                    if (checkedAt > 0L)
                                        SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault())
                                            .format(Date(checkedAt))
                                    else "Not yet — check device selection and Usage Access"
                                )
                                Text("Background timing is controlled by Android; a locked phone cannot guarantee 60-second uploads.",
                                    style = MaterialTheme.typography.bodySmall)
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
                                Text("SMS backup requires separate enablement in Messages; no WhatsApp, Instagram, camera or screenshots.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    1 -> {
                        Text("Calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("One-time permission enables private, automatic call backup to your own signed-in account. You can disable or delete it anytime.")
                        PrivateCallBackup(onStatus = { report("BACKUP", it) })
                    }
                    2 -> {
                        Text("Messages", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Owner-controlled SMS backup. Android must grant restricted SMS access.")
                        PrivateSmsBackup(onStatus = { report("SMS", it) })
                    }
                    3 -> {
                        Text("Live audio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Start or stop your live audio session below. Switching app tabs will not stop an active session.")
                    }
                    4 -> {
                        Text("Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (signedIn) {
                                    Text("Signed in as ${auth.currentUser?.email.orEmpty()}")
                                    PhoneSelection(onStatus = { report("DEVICE", it) })
                                    Text("Collector UID", style = MaterialTheme.typography.labelLarge)
                                    Text(auth.currentUser?.uid.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                            .setPrimaryClip(android.content.ClipData.newPlainText("Collector UID", auth.currentUser?.uid.orEmpty()))
                                        report("ACCOUNT", "Collector UID copied for enrollment.")
                                    }) { Text("Copy UID") }
                                    Button(onClick = {
                                        WorkManager.getInstance(this@MainActivity).cancelUniqueWork("usage-sync")
                                        BackgroundSyncCoordinator.stopAll(this@MainActivity)
                                        MicrophoneStreamService.stop(this@MainActivity)
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
                                                                if (getSharedPreferences(SmsBackupWorker.PREFS, MODE_PRIVATE)
                                                                        .getBoolean(SmsBackupWorker.ENABLED, false) &&
                                                                    getSharedPreferences(SmsBackupWorker.PREFS, MODE_PRIVATE)
                                                                        .getString(SmsBackupWorker.OWNER, "") == uid) {
                                                                    SmsBackupWorker.schedule(this@MainActivity)
                                                                }
                                                                BackgroundSyncCoordinator.maintain(this@MainActivity, requestCatchUp = true)
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
                    5 -> {
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
                    else -> {
                        PermissionCenter(
                            onEnableSharing = {
                                if (!consent) {
                                    prefs.edit().putBoolean("consent", true)
                                        .putLong("consent_at", System.currentTimeMillis()).apply()
                                    consent = true
                                }
                                BackgroundSyncCoordinator.maintain(this@MainActivity, true)
                            },
                            onOpenCalls = { tab = 1 },
                            onOpenMessages = { tab = 2 },
                            onOpenLive = { tab = 3 },
                            onOpenAccount = { tab = 4 },
                            onStatus = { report("ACCESS", it) }
                        )
                    }
                }
                // Keep this composable in the tree across tab switches. Removing it destroyed
                // the WebRTC peer and caused unexpected CLOSED sessions on tab navigation.
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (tab == 3) Modifier else Modifier.height(0.dp))
                        .clipToBounds()
                ) {
                    LiveMicrophonePublisher(
                        sharingEnabled = consent && signedIn,
                        onStatus = { report("AUDIO", it) }
                    )
                }
                if (tab != 5 && tab != 6 && status.isNotBlank()) {
                    Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    fun scheduleSync() {
        BackgroundSyncCoordinator.syncNow(this)
    }

}

fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}
