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
        if (!hasUsageAccess(applicationContext)) return Result.success()
        return try {
            val db = FirebaseFirestore.getInstance()
            val device = DeviceBinding.resolve(applicationContext, uid)
            val deviceId = device.id
            val now = System.currentTimeMillis()
            val from = prefs.getLong("last_sync", now - 24L * 60 * 60 * 1000).coerceAtMost(now - 60_000)
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
            prefs.edit().putLong("last_sync", now).apply()
            if (batchRecords.isNotEmpty()) {
                CollectorDiagnostics.record(
                    applicationContext, "LOCK", "Uploaded ${batchRecords.size} lock/unlock event(s)."
                )
            }
            Result.success()
        } catch (e: Exception) {
            CollectorDiagnostics.record(applicationContext, "LOCK",
                "Lock sync failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
