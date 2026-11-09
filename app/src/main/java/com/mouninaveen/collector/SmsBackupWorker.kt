package com.mouninaveen.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Explicit owner-only SMS backup. READ_SMS is hard restricted by Android.
 * No attempts to bypass restricted permission, hidden forwarding, or Admin UID access.
 */
class SmsBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ENABLED, false) ||
            !context.getSharedPreferences("collector", Context.MODE_PRIVATE).getBoolean("consent", false))
            return Result.success()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString(LAST_ERROR,
                "READ_SMS not granted. Android restricts SMS permission eligibility.").apply()
            CollectorDiagnostics.record(context, "SMS", "Restricted READ_SMS not granted.")
            return Result.failure()
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.retry()
        if (prefs.getString(OWNER, null) != uid) return Result.success()

        return try {
            val device = DeviceBinding.resolve(context, uid)
            val deviceId = device.id
            val db = FirebaseFirestore.getInstance()
            val destination = db.collection("users").document(uid)
                .collection("deviceSmsBackups").document(deviceId).collection("messages")
            val cutoff = prefs.getLong("${LAST_DATE}_$deviceId", 0L)
            val projection = arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE
            )
            val selection = if (cutoff == 0L) null else "${Telephony.Sms.DATE} >= ?"
            val args = if (cutoff == 0L) null else arrayOf(cutoff.toString())
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, selection, args, "${Telephony.Sms.DATE} ASC"
            ) ?: error("Android SMS provider returned no cursor")
            var processed = 0
            var fresh = 0
            var newest = cutoff
            var batch = db.batch()
            var pending = 0
            cursor.use { rows ->
                val idIndex = rows.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = rows.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = rows.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = rows.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = rows.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (rows.moveToNext()) {
                    if (isStopped || !prefs.getBoolean(ENABLED, false) ||
                        !context.getSharedPreferences("collector", Context.MODE_PRIVATE).getBoolean("consent", false) ||
                        FirebaseAuth.getInstance().currentUser?.uid != uid ||
                        DeviceBinding.selectedId(context, uid) != deviceId) return Result.success()
                    val providerId = rows.getLong(idIndex)
                    val date = rows.getLong(dateIndex)
                    if (providerId < 0 || date < 946684800000L ||
                        date > System.currentTimeMillis() + 86400000L) continue
                    val direction = when (rows.getInt(typeIndex)) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "RECEIVED"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "SENT"
                        else -> continue // Do not upload drafts, failed, or pending messages.
                    }
                    val address = rows.getString(addressIndex)?.take(128)
                        ?.ifBlank { "Unknown" } ?: "Unknown"
                    val body = rows.getString(bodyIndex)?.take(4000) ?: ""
                    val smsId = "sms_$providerId"
                    batch.set(destination.document(smsId), mapOf(
                        "smsId" to smsId,
                        "ownerUid" to uid,
                        "deviceId" to deviceId,
                        "address" to address,
                        "body" to body,
                        "direction" to direction,
                        "receivedAt" to Date(date),
                        "source" to "OWNER_PRIVATE_SMS_BACKUP",
                        "schemaVersion" to 1
                    ))
                    processed++
                    if (date > cutoff) fresh++
                    if (date > newest) newest = date
                    pending++
                    if (pending >= 200) {
                        batch.commit().await()
                        batch = db.batch()
                        pending = 0
                    }
                }
            }
            if (pending > 0) batch.commit().await()
            prefs.edit()
                .putLong("${LAST_DATE}_$deviceId", newest)
                .putLong(LAST_SUCCESS, System.currentTimeMillis())
                .putLong("${LAST_SUCCESS}_$deviceId", System.currentTimeMillis())
                .putInt(LAST_COUNT, processed)
                .putString(LAST_ERROR, "")
                .apply()
            CollectorDiagnostics.record(context, "SMS",
                "Owner-private SMS backup checked $processed entries; $fresh new.")
            if (fresh > 0 || cutoff == 0L) {
                try {
                    device.reference.collection("syncLogs").add(
                        mapOf("eventType" to "SMS_BACKUP",
                            "count" to fresh,
                            "timestamp" to FieldValue.serverTimestamp(),
                            "status" to "SUCCESS")
                    ).await()
                } catch (_: Exception) {
                    CollectorDiagnostics.record(context, "SMS",
                        "SMS backup saved; device status log unavailable.")
                }
            }
            Result.success()
        } catch (e: SecurityException) {
            prefs.edit().putString(LAST_ERROR,
                "Android restricted SMS provider access.").apply()
            CollectorDiagnostics.record(context, "SMS", "SMS permission denied by Android.")
            Result.failure()
        } catch (e: Exception) {
            prefs.edit().putString(LAST_ERROR,
                e.localizedMessage?.take(160) ?: "SMS backup failed").apply()
            CollectorDiagnostics.record(context, "SMS", "Backup failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        const val PREFS = "private_sms_backup"
        const val ENABLED = "enabled"
        const val OWNER = "owner_uid"
        const val LAST_DATE = "last_sms_date"
        const val LAST_SUCCESS = "last_success"
        const val LAST_COUNT = "last_count"
        const val LAST_ERROR = "last_error"
        private const val PERIODIC = "private-sms-backup-periodic"
        private const val NOW = "private-sms-backup-now"

        fun schedule(context: Context, requestNow: Boolean = true) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(ENABLED, false)) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SmsBackupWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
                    ).build()
            )
            if (requestNow) trigger(context)
        }

        fun trigger(context: Context) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(ENABLED, false)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SmsBackupWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
                    ).build()
            )
        }

        fun stop(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(NOW)
        }
    }
}
