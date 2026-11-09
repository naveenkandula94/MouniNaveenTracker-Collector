package com.mouninaveen.collector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Opt-in, best-effort persistent background sync. WorkManager persists requests across
 * app process death/reboots and retries transient failures when networking returns.
 * It is NOT a 15-second background polling mechanism.
 */
internal object BackgroundSyncCoordinator {
    private const val LOCK_WORK = "usage-sync"

    fun maintain(context: Context, requestCatchUp: Boolean = false) {
        ProcessSyncObservers.refresh(context)
        val owner = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (DeviceBinding.selectedId(context, owner) == null) return
        val sharing = context.getSharedPreferences("collector", Context.MODE_PRIVATE)
            .getBoolean("consent", false)
        if (!sharing) return

        val callPrefs = context.getSharedPreferences(CallBackupWorker.PREFS, Context.MODE_PRIVATE)
        if (callPrefs.getBoolean(CallBackupWorker.ENABLED, false) &&
            callPrefs.getString(CallBackupWorker.OWNER, null) == owner &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED) {
            CallBackupWorker.schedule(context, requestCatchUp)
        }
        val smsPrefs = context.getSharedPreferences(SmsBackupWorker.PREFS, Context.MODE_PRIVATE)
        if (smsPrefs.getBoolean(SmsBackupWorker.ENABLED, false) &&
            smsPrefs.getString(SmsBackupWorker.OWNER, null) == owner &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED) {
            SmsBackupWorker.schedule(context, requestCatchUp)
        }
        if (hasUsageAccess(context)) {
            val connected = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                LOCK_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(connected)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
                    ).build()
            )
            if (requestCatchUp) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "lock-check-auto", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<UsageSyncWorker>()
                        .setConstraints(connected)
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
                        ).build()
                )
            }
        }
    }

    fun syncNow(context: Context) {
        // A user's explicit Sync Now must not be blocked by a previously
        // enqueued KEEP work item stuck waiting on retry/backoff.
        maintain(context, requestCatchUp = false)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (DeviceBinding.selectedId(context, uid) == null ||
            !context.getSharedPreferences("collector", Context.MODE_PRIVATE)
                .getBoolean("consent", false)) return
        val connected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(context)
        fun <T : androidx.work.ListenableWorker> request(
            name: String, worker: Class<T>
        ) {
            val job = OneTimeWorkRequest.Builder(worker)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, job)
        }
        if (hasUsageAccess(context)) {
            request("manual-lock-sync", UsageSyncWorker::class.java)
        }
        val callPrefs = context.getSharedPreferences(CallBackupWorker.PREFS, Context.MODE_PRIVATE)
        if (callPrefs.getBoolean(CallBackupWorker.ENABLED, false) &&
            callPrefs.getString(CallBackupWorker.OWNER, null) == uid &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED) {
            request("manual-call-backup", CallBackupWorker::class.java)
        }
        val smsPrefs = context.getSharedPreferences(SmsBackupWorker.PREFS, Context.MODE_PRIVATE)
        if (smsPrefs.getBoolean(SmsBackupWorker.ENABLED, false) &&
            smsPrefs.getString(SmsBackupWorker.OWNER, null) == uid &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED) {
            request("manual-sms-backup", SmsBackupWorker::class.java)
        }
        CollectorDiagnostics.record(context, "SYNC",
            "Requested independent immediate checks for enabled services.")
    }

    fun stopAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LOCK_WORK)
        wm.cancelUniqueWork("lock-check-auto")
        wm.cancelUniqueWork("manual-lock-sync")
        wm.cancelUniqueWork("manual-call-backup")
        wm.cancelUniqueWork("manual-sms-backup")
        CallBackupWorker.stop(context)
        SmsBackupWorker.stop(context)
        ProcessSyncObservers.refresh(context)
    }
}

/** Process starts caused by Android's scheduled work do not require opening an Activity. */
class PersonalApp : Application() {
    private val screenChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action !in listOf(
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT
                )) return
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            if (!getSharedPreferences("collector", Context.MODE_PRIVATE)
                    .getBoolean("consent", false) ||
                DeviceBinding.selectedId(this@PersonalApp, uid) == null ||
                !hasUsageAccess(this@PersonalApp)) return
            // Only screen/keyguard metadata; never turn on microphone or SMS here.
            val request = OneTimeWorkRequestBuilder<UsageSyncWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
                ).build()
            WorkManager.getInstance(this@PersonalApp).enqueueUniqueWork(
                "lock-check-auto", ExistingWorkPolicy.KEEP, request
            )
        }
    }
    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        ProcessSyncObservers.refresh(this)
        if (auth.currentUser != null) {
            // Firebase may restore its persisted sign-in after Application.onCreate.
            // Don't rely on the user opening MainActivity to restore enabled jobs.
            BackgroundSyncCoordinator.maintain(this, requestCatchUp = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this, screenChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
    }

    override fun onTerminate() {
        FirebaseAuth.getInstance().removeAuthStateListener(authStateListener)
        unregisterReceiver(screenChangeReceiver)
        super.onTerminate()
    }
}
