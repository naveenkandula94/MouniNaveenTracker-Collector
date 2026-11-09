package com.mouninaveen.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

/**
 * Process-scoped observers for backups explicitly enabled by the owner.
 *
 * They keep detecting phone-provider changes when the Activity is paused but the
 * application process remains alive. They do not keep a killed process alive.
 * Persistent periodic WorkManager jobs supply catch-up after process death.
 */
internal object ProcessSyncObservers {
    private var callObserver: ContentObserver? = null
    private var smsObserver: ContentObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { refresh(app) }
            return
        }
        val owner = FirebaseAuth.getInstance().currentUser?.uid
        val consent = app.getSharedPreferences("collector", Context.MODE_PRIVATE)
            .getBoolean("consent", false)
        val selected = owner?.let { DeviceBinding.selectedId(app, it) }
        val calls = app.getSharedPreferences(CallBackupWorker.PREFS, Context.MODE_PRIVATE)
        val sms = app.getSharedPreferences(SmsBackupWorker.PREFS, Context.MODE_PRIVATE)
        val canCall = owner != null && selected != null && consent &&
            calls.getBoolean(CallBackupWorker.ENABLED, false) &&
            calls.getString(CallBackupWorker.OWNER, null) == owner &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED
        val canSms = owner != null && selected != null && consent &&
            sms.getBoolean(SmsBackupWorker.ENABLED, false) &&
            sms.getString(SmsBackupWorker.OWNER, null) == owner &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

        if (!canCall && callObserver != null) {
            app.contentResolver.unregisterContentObserver(callObserver!!)
            callObserver = null
        }
        if (canCall && callObserver == null) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    if (!selfChange) {
                        // Worker performs a fresh permission/owner/consent check.
                        CallBackupWorker.trigger(app)
                    }
                }
            }
            try {
                app.contentResolver.registerContentObserver(
                    CallLog.Calls.CONTENT_URI, true, observer
                )
                callObserver = observer
            } catch (e: SecurityException) {
                CollectorDiagnostics.record(app, "BACKUP",
                    "Call observer unavailable: restricted provider access.")
            }
        }
        if (!canSms && smsObserver != null) {
            app.contentResolver.unregisterContentObserver(smsObserver!!)
            smsObserver = null
        }
        if (canSms && smsObserver == null) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    if (!selfChange) SmsBackupWorker.trigger(app)
                }
            }
            try {
                app.contentResolver.registerContentObserver(
                    Telephony.Sms.CONTENT_URI, true, observer
                )
                smsObserver = observer
            } catch (e: SecurityException) {
                CollectorDiagnostics.record(app, "SMS",
                    "SMS observer unavailable: restricted provider access.")
            }
        }
    }
}
