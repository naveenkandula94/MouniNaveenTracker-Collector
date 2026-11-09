package com.mouninaveen.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Private call-history backup for the signed-in device owner.
 *
 * Backups live under users/{authUid}/deviceCallBackups/{deviceId}/calls.\n * This prevents phone-provider IDs from colliding across separate devices.
 * No WebRTC/media capture and no access to messaging apps.
 */
class CallBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ENABLED, false) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val user = FirebaseAuth.getInstance().currentUser ?: return Result.retry()
        val uid = user.uid
        if (prefs.getString(OWNER, "") != uid) return Result.success()

        return try {
            val database = FirebaseFirestore.getInstance()
            val device = DeviceBinding.resolve(context, uid)
            val deviceId = device.id
            val target = database.collection("users").document(uid)
                .collection("deviceCallBackups").document(deviceId).collection("calls")
            val lastCallTime = prefs.getLong("${LAST_CALL_DATE}_$deviceId", 0L)
            // Re-check the newest timestamp for a possible late duration update,
            // but never rescan a two-day window on every subsequent upload.
            val since = lastCallTime
            var total = 0
            var newCalls = 0
            var newest = lastCallTime
            var batch = database.batch()
            var pending = 0
            val projection = arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION
            )
            val selection = if (since == 0L) null else "${CallLog.Calls.DATE} >= ?"
            val arguments = if (since == 0L) null else arrayOf(since.toString())

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, arguments, CallLog.Calls.DATE + " ASC"
            ) ?: error("Call-log provider returned no cursor")

            cursor.use { rows ->
                val idIndex = rows.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = rows.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeIndex = rows.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIndex = rows.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIndex = rows.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (rows.moveToNext()) {
                    if (isStopped || !prefs.getBoolean(ENABLED, false) ||
                        prefs.getString(OWNER, "") != FirebaseAuth.getInstance().currentUser?.uid
                    ) return Result.success()

                    val providerId = rows.getLong(idIndex)
                    val dateMillis = rows.getLong(dateIndex)
                    if (providerId < 0 || dateMillis < 946684800000L ||
                        dateMillis > System.currentTimeMillis() + 86400000L) continue

                    val direction = when (rows.getInt(typeIndex)) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        else -> "UNKNOWN"
                    }
                    val number = rows.getString(numberIndex)?.take(48).orEmpty().ifBlank { "Unknown" }
                    val duration = rows.getLong(durationIndex).coerceIn(0L, 86400L).toInt()
                    val docId = "call_$providerId"
                    batch.set(target.document(docId), mapOf(
                        "callId" to docId,
                        "ownerUid" to uid,
                        "deviceId" to deviceId,
                        "number" to number,
                        "direction" to direction,
                        "callTimestamp" to Date(dateMillis),
                        "durationSeconds" to duration,
                        "source" to "OWNER_PRIVATE_BACKUP",
                        "schemaVersion" to 1
                    ))
                    pending++
                    total++
                    if (dateMillis > lastCallTime) newCalls++
                    if (dateMillis > newest) newest = dateMillis
                    if (pending >= 200) {
                        if (!prefs.getBoolean(ENABLED, false) || prefs.getString(OWNER, "") != FirebaseAuth.getInstance().currentUser?.uid) return Result.success()
                        batch.commit().await()
                        pending = 0
                        batch = database.batch()
                    }
                }
            }
            if (pending > 0) {
                if (!prefs.getBoolean(ENABLED, false) || prefs.getString(OWNER, "") != FirebaseAuth.getInstance().currentUser?.uid) return Result.success()
                batch.commit().await()
            }
            prefs.edit()
                .putLong("${LAST_CALL_DATE}_$deviceId", newest)
                .putLong(LAST_SUCCESS, System.currentTimeMillis())
                .putInt(LAST_COUNT, total)
                .putString(LAST_ERROR, "")
                .apply()
            CollectorDiagnostics.record(context, "BACKUP",
                if (lastCallTime == 0L) "Initial backup for bound phone: $total records."
                else "Incremental check: $newCalls new calls, $total checked.")
            // Non-sensitive, device-scoped sync diagnostics for the linked owner dashboard.
            if (newCalls > 0 || lastCallTime == 0L) {
                try {
                    device.reference.collection("syncLogs").add(
                        mapOf(
                            "eventType" to "CALL_BACKUP",
                            "count" to newCalls,
                            "timestamp" to FieldValue.serverTimestamp(),
                            "status" to "SUCCESS"
                        )
                    ).await()
                } catch (e: Exception) {
                    CollectorDiagnostics.record(context, "BACKUP", "Remote sync status unavailable; backup remains saved.")
                }
            }
            Result.success()
        } catch (e: SecurityException) {
            prefs.edit().putString(LAST_ERROR, "Android denied call-log access: ${e.localizedMessage}").apply()
            CollectorDiagnostics.record(context, "BACKUP", "Android call-log permission denied.")
            Result.failure()
        } catch (e: Exception) {
            prefs.edit().putString(LAST_ERROR, e.localizedMessage?.take(180) ?: "Sync failed").apply()
            CollectorDiagnostics.record(context, "BACKUP", "Private backup error: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        const val PREFS = "private_call_backup"
        const val ENABLED = "enabled"
        const val OWNER = "owner_uid"
        const val LAST_CALL_DATE = "last_call_date"
        const val LAST_SUCCESS = "last_success"
        const val LAST_COUNT = "last_count"
        const val LAST_ERROR = "last_error"
        private const val PERIODIC = "private-call-backup-periodic"
        private const val ONE_TIME = "private-call-backup-now"

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(ENABLED, false)) return
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CallBackupWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints).build()
            )
            trigger(context)
        }

        fun trigger(context: Context) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CallBackupWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }

        fun stop(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(ONE_TIME)
        }
    }
}
