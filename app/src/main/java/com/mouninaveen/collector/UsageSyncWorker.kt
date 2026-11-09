package com.mouninaveen.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Date
import java.util.TimeZone

class UsageSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("collector", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("consent", false)) return Result.success()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.retry()
        val selected = DeviceBinding.selectedId(applicationContext, uid)
        if (selected == null) {
            prefs.edit().putString("last_lock_error",
                "Choose this phone under Account to enable lock synchronization.").apply()
            return Result.failure()
        }
        if (!hasUsageAccess(applicationContext)) {
            prefs.edit().putString("last_lock_error",
                "Android Usage Access is off. Enable it under Access > Screen lock events.").apply()
            return Result.failure()
        }
        return try {
            val db = FirebaseFirestore.getInstance()
            val device = DeviceBinding.resolve(applicationContext, uid)
            val deviceId = device.id
            val now = System.currentTimeMillis()
            CollectorDiagnostics.record(applicationContext, "LOCK",
                "Checking screen events for the selected phone.")
            // Different phones share a Firebase UID, but never share an event cursor.
            // First scan asks Android for its available keyguard events over 7 days.
            // Future runs scan only the new interval with a short overlap for reliability.
            val cursorKey = "last_sync_$deviceId"
            val lastSuccess = prefs.getLong(cursorKey, 0L)
            val from = if (lastSuccess == 0L) now - 7L * 24 * 60 * 60 * 1000
                       else (lastSuccess - 60_000L).coerceAtMost(now - 60_000L)
            val manager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            val batchRecords = mutableListOf<Pair<String, Map<String, Any>>>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // Only keyguard transitions are eligible for syncing.
                // No app foreground/background activity is uploaded.
                val type = when (event.eventType) {
                    UsageEvents.Event.KEYGUARD_SHOWN -> "SCREEN_LOCKED"
                    UsageEvents.Event.KEYGUARD_HIDDEN -> "SCREEN_UNLOCKED"
                    else -> null
                } ?: continue
                val pkg = "android.keyguard"
                val ts = event.timeStamp
                // The overlap helps discover late keyguard events, but avoid
                // rewriting already uploaded IDs (which older published rules
                // could reject as updates and block the entire sync batch).
                if (lastSuccess > 0L && ts <= lastSuccess) continue
                val id = sha256("$type|$pkg|$ts")
                val label = "Device lock state"
                val record = hashMapOf<String, Any>(
                    "eventId" to id, "deviceId" to deviceId, "eventType" to type,
                    "packageName" to pkg, "applicationLabel" to label,
                    "eventTimestamp" to Date(ts), "serverTimestamp" to FieldValue.serverTimestamp(),
                    "timezone" to TimeZone.getDefault().id, "schemaVersion" to 1
                )
                batchRecords.add(id to record)
            }
            // Process-scoped Android screen broadcasts supplement keyguard
            // UsageStats events on OEMs that omit them. They are accurately
            // distinguished from confirmed locks; only USER_PRESENT means unlock.
            val transition = inputData.getString("screen_transition").orEmpty()
            val transitionTime = inputData.getLong("screen_transition_at", 0L)
            if (transition in setOf("SCREEN_ON", "SCREEN_OFF", "SCREEN_UNLOCKED") &&
                transitionTime in (now - 7L * 86400000L)..(now + 60_000L)) {
                val alreadyReported = batchRecords.any { (_, item) ->
                    item["eventType"] == transition &&
                        kotlin.math.abs(
                            (item["eventTimestamp"] as? Date)?.time?.minus(transitionTime)
                                ?: Long.MAX_VALUE
                        ) < 2_000L
                }
                if (!alreadyReported) {
                    val pkg = "android.screen"
                    val id = sha256("$transition|$pkg|$transitionTime")
                    val record = hashMapOf<String, Any>(
                        "eventId" to id,
                        "deviceId" to deviceId,
                        "eventType" to transition,
                        "packageName" to pkg,
                        "applicationLabel" to "Android screen state",
                        "eventTimestamp" to Date(transitionTime),
                        "serverTimestamp" to FieldValue.serverTimestamp(),
                        "timezone" to TimeZone.getDefault().id,
                        "schemaVersion" to 1
                    )
                    batchRecords.add(id to record)
                }
            }
            for (chunk in batchRecords.distinctBy { it.first }.chunked(400)) {
                if (!prefs.getBoolean("consent", false) || FirebaseAuth.getInstance().currentUser?.uid != uid) return Result.success()
                val batch = db.batch()
                chunk.forEach { (id, record) -> batch.set(db.collection("devices").document(deviceId).collection("usageEvents").document(id), record) }
                batch.commit().await()
            }
            if (!prefs.getBoolean("consent", false) || FirebaseAuth.getInstance().currentUser?.uid != uid) return Result.success()
            val lastHeartbeat = prefs.getLong("last_heartbeat_$deviceId", 0L)
            // Avoid excessive Firebase writes while performing inexpensive 15-second
            // app-foreground checks. Heartbeat once per minute, or on a new event.
            if (batchRecords.isNotEmpty() || now - lastHeartbeat >= 60_000L) {
                device.reference.update(
                    mapOf("lastSyncAt" to FieldValue.serverTimestamp(), "lastSyncStatus" to "SYNCED")
                ).await()
                prefs.edit().putLong("last_heartbeat_$deviceId", now).apply()
            }
            prefs.edit().putLong(cursorKey, now)
                .putString("last_lock_error", "").apply()
            if (batchRecords.isEmpty() &&
                now - prefs.getLong("last_no_event_log_$deviceId", 0L) >= 5 * 60_000L) {
                CollectorDiagnostics.record(
                    applicationContext, "LOCK",
                    "Checked Usage Access: no new Android keyguard events were exposed."
                )
                prefs.edit().putLong("last_no_event_log_$deviceId", now).apply()
            }
            if (batchRecords.isNotEmpty()) {
                CollectorDiagnostics.record(
                    applicationContext, "LOCK", "Uploaded ${batchRecords.size} lock/unlock event(s)."
                )
                try {
                    device.reference.collection("syncLogs").add(
                        mapOf(
                            "eventType" to "LOCK_SYNC",
                            "count" to batchRecords.size,
                            "timestamp" to FieldValue.serverTimestamp(),
                            "status" to "SUCCESS"
                        )
                    ).await()
                } catch (_: Exception) {
                    CollectorDiagnostics.record(
                        applicationContext, "LOCK", "Lock events uploaded; remote diagnostic log not available."
                    )
                }
            }
            Result.success()
        } catch (e: Exception) {
            val reason = "Lock sync failed: ${e.localizedMessage?.take(160) ?: e.javaClass.simpleName}"
            prefs.edit().putString("last_lock_error", reason).apply()
            CollectorDiagnostics.record(applicationContext, "LOCK", reason)
            Result.retry()
        }
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
