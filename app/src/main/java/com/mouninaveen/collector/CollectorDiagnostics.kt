package com.mouninaveen.collector

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only diagnostic messages, no call numbers, credentials, or microphone payloads.
 * Retains at most 100 messages in app-private storage.
 */
internal object CollectorDiagnostics {
    private const val KEY = "diagnostic_lines"
    fun record(context: Context, category: String, message: String) {
        val prefs = context.getSharedPreferences("collector_diagnostics", Context.MODE_PRIVATE)
        val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val safe = message.replace(Regex("[\\r\\n]+"), " ").take(240)
        val lines = (listOf("$time | ${category.take(20)} | $safe") +
            prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }).take(100)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }
    fun read(context: Context): String =
        context.getSharedPreferences("collector_diagnostics", Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().ifBlank { "No diagnostic events yet." }
    fun clear(context: Context) =
        context.getSharedPreferences("collector_diagnostics", Context.MODE_PRIVATE).edit().remove(KEY).apply()
}
