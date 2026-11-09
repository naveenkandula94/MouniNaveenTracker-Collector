package com.mouninaveen.collector

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * A Firebase account can be used on multiple personal phones, so the UID alone does
 * not identify the physical phone. Every installation must be bound to one enrollment.
 * Binding is held in this installation's private preferences.
 */
internal object DeviceBinding {
    private const val PREFS = "physical_device_binding"
    private const val KEY_UID = "owner_uid"
    private const val KEY_ID = "device_id"
    private const val KEY_CONFIRMED = "selected_by_user"
    fun selectedId(context: Context, uid: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_UID, null) != uid || !prefs.getBoolean(KEY_CONFIRMED, false)) return null
        return prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }
    }
    fun bind(context: Context, uid: String, deviceId: String) {
        require(uid.isNotBlank() && deviceId.isNotBlank())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, uid)
            .putString(KEY_ID, deviceId)
            .putBoolean(KEY_CONFIRMED, true)
            .apply()
    }
    suspend fun resolve(context: Context, uid: String): DocumentSnapshot {
        val selected = selectedId(context, uid)
            ?: error("Select this phone in Personal_App > Account > This phone before syncing.")
        // Fetch only the owner's explicitly selected enrollment. A server read
        // prevents a cached/old enrollment from falsely appearing active.
        val document = FirebaseFirestore.getInstance().collection("devices")
            .document(selected).get(com.google.firebase.firestore.Source.SERVER).await()
        check(document.exists()) { "Selected enrollment no longer exists in Firebase." }
        check(document.getString("collectorUid") == uid) {
            "This signed-in account is not the selected phone's collector."
        }
        check(document.getBoolean("enabled") != false) {
            "Selected phone enrollment is disabled by its administrator."
        }
        return document
    }
}
