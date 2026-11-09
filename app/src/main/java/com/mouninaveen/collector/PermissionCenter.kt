package com.mouninaveen.collector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Consolidated access dashboard. Android permissions cannot be granted wholesale:
 * distinct call/SMS backup consents and user-started mic sessions remain required.
 */
@Composable
internal fun PermissionCenter(
    onEnableSharing: () -> Unit,
    onOpenCalls: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenAccount: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (isActive) {
            refresh++
            delay(8_000L)
        }
    }
    val user = remember(refresh) { FirebaseAuth.getInstance().currentUser }
    val uid = user?.uid
    val deviceId = uid?.let { DeviceBinding.selectedId(context, it) }
    val basePrefs = context.getSharedPreferences("collector", Context.MODE_PRIVATE)
    val callPrefs = context.getSharedPreferences(CallBackupWorker.PREFS, Context.MODE_PRIVATE)
    val smsPrefs = context.getSharedPreferences(SmsBackupWorker.PREFS, Context.MODE_PRIVATE)
    val sharing = basePrefs.getBoolean("consent", false)
    val usage = hasUsageAccess(context)
    val phoneGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED
    val smsGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED
    val micGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val noticeGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    val callEnabled = sharing && uid != null && callPrefs.getBoolean(
        CallBackupWorker.ENABLED, false
    ) && callPrefs.getString(CallBackupWorker.OWNER, null) == uid
    val smsEnabled = sharing && uid != null && smsPrefs.getBoolean(
        SmsBackupWorker.ENABLED, false
    ) && smsPrefs.getString(SmsBackupWorker.OWNER, null) == uid
    val connected = uid != null && deviceId != null
    val active = MicStreamStatus.state.collectAsState().value.active

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Set up Personal_App") },
        text = { Text(
            "Enable general device sharing and review each Android permission below. " +
            "Usage Access opens Android settings. Call history and SMS backups require " +
            "separate explicit approval on their own screens; Android may restrict access. " +
            "Live microphone must be started separately for each session. " +
            "You can stop sharing at any time."
        ) },
        confirmButton = {
            Button(onClick = {
                confirm = false
                onEnableSharing()
                if (!connected) {
                    onStatus("Select the correct phone under Account.")
                    onOpenAccount()
                } else if (!usage) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else {
                    onStatus("General sharing enabled. Review Calls, Messages and Live permissions below.")
                }
            }) { Text("Continue setup") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Permissions & sync", style = MaterialTheme.typography.headlineSmall)
        Text("Manage this phone's access", color = MaterialTheme.colorScheme.onSurfaceVariant)

        ElevatedCard(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE6F4FF))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${listOf(connected, sharing, usage, phoneGranted && callEnabled,
                    smsGranted && smsEnabled, micGranted, noticeGranted).count { it }} of 7 ready",
                    style = MaterialTheme.typography.titleLarge)
                Text("Each feature remains under your control.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(modifier = Modifier.fillMaxWidth(), onClick = { confirm = true }) {
                    Text("Set up / review access")
                }
            }
        }

        @Composable
        fun statusRow(
            title: String,
            detail: String,
            ok: Boolean,
            buttonLabel: String,
            onClick: () -> Unit
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                        Text(if (ok) "● Ready" else "○ Action needed",
                            color = if (ok) Color(0xFF11866D) else Color(0xFFA15A17),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = onClick) { Text(buttonLabel) }
                }
            }
        }

        statusRow("Account & phone",
            if (connected) "Selected: ${deviceId?.take(10)}…" else "Choose this device manually",
            connected, "Choose", onOpenAccount)
        statusRow("General sharing",
            if (sharing) "Enabled" else "Disabled",
            sharing, "Review", { confirm = true })
        statusRow("Screen lock events",
            if (usage) "Android Usage Access granted" else "Grant Usage Access in Settings",
            usage && sharing && connected, "Settings", {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
        statusRow("Phone call backup",
            if (callEnabled && phoneGranted) "Backup on · Android access granted"
            else if (!phoneGranted) "Phone permission not granted or restricted"
            else "Backup not enabled",
            callEnabled && phoneGranted && connected, "Calls", onOpenCalls)
        statusRow("SMS backup",
            if (smsEnabled && smsGranted) "Backup on · Android access granted"
            else if (!smsGranted) "SMS permission not granted or restricted"
            else "Backup not enabled",
            smsEnabled && smsGranted && connected, "SMS", onOpenMessages)
        statusRow("Microphone",
            if (active) "An owner-started live session is active"
            else if (micGranted) "Permission granted · Session stopped"
            else "Grant permission when starting a session",
            micGranted, "Live", onOpenLive)
        statusRow("Notifications",
            if (noticeGranted) "Allowed" else "Allow visible microphone Stop notification",
            noticeGranted, "Settings", {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            })

        ElevatedCard(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE9F6F0))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Background synchronization", style = MaterialTheme.typography.titleMedium)
                Text("Enabled jobs persist across app closing and phone restart. Android schedules " +
                    "them subject to battery and network conditions; delivery every 15 seconds is not guaranteed.",
                    style = MaterialTheme.typography.bodySmall)
                val callLast = if (deviceId == null) 0L else callPrefs.getLong(
                    "${CallBackupWorker.LAST_SUCCESS}_$deviceId", 0L
                )
                val smsLast = if (deviceId == null) 0L else smsPrefs.getLong(
                    "${SmsBackupWorker.LAST_SUCCESS}_$deviceId", 0L
                )
                val lockLast = if (deviceId == null) 0L else basePrefs.getLong(
                    "last_heartbeat_$deviceId", 0L
                )
                val formatter = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
                fun stamp(ts: Long) = if (ts > 0) formatter.format(Date(ts)) else "Not yet"
                Text("Calls: ${stamp(callLast)}", style = MaterialTheme.typography.bodySmall)
                Text("Messages: ${stamp(smsLast)}", style = MaterialTheme.typography.bodySmall)
                Text("Lock: ${stamp(lockLast)}", style = MaterialTheme.typography.bodySmall)
                val errors = listOf(
                    "Calls" to callPrefs.getString(CallBackupWorker.LAST_ERROR, "").orEmpty(),
                    "SMS" to smsPrefs.getString(SmsBackupWorker.LAST_ERROR, "").orEmpty(),
                    "Lock" to basePrefs.getString("last_lock_error", "").orEmpty()
                ).filter { it.second.isNotBlank() }
                if (errors.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Needs attention", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall)
                    errors.forEach { (feature, reason) ->
                        Text("$feature: $reason",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (deviceId == null) {
                    Text("Backups paused: choose this phone in Account.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!sharing) {
                    Text("Backups paused: sharing is disabled.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Android background work is scheduled, not continuously running. " +
                        "If offline, a pending upload waits for a network and retries.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = connected && sharing, onClick = {
                        BackgroundSyncCoordinator.syncNow(context)
                        onStatus("Scheduled an incremental catch-up for enabled backups.")
                    }) { Text("Check sync now") }
                    TextButton(onClick = { refresh++ }) { Text("Refresh") }
                }
            }
        }
    }
}
