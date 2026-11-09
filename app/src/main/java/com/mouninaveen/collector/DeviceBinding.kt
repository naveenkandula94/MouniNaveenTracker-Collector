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
    fun selectedId(context: Context, uid: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_UID, null) != uid) return null
        return prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }
    }
    fun bind(context: Context, uid: String, deviceId: String) {
        require(uid.isNotBlank() && deviceId.isNotBlank())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, uid)
            .putString(KEY_ID, deviceId).apply()
    }
    suspend fun resolve(context: Context, uid: String): DocumentSnapshot {
        val docs = FirebaseFirestore.getInstance().collection("devices")
            .whereEqualTo("collectorUid", uid).get().await().documents
            .filter { it.getBoolean("enabled") != false }
        check(docs.isNotEmpty()) { "No active enrollment. Add this phone in Admin settings." }
        val selected = selectedId(context, uid)
        check(selected != null) {
            "Select this phone manually in Personal_App > Account > This phone before syncing."
        }
        return docs.firstOrNull { it.id == selected }
            ?: error("Selected phone is not actively enrolled. Choose a valid device in Account.")
    }
}
