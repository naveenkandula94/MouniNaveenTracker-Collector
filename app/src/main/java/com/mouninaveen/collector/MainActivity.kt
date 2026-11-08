package com.mouninaveen.collector

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var consent by remember { mutableStateOf(getPreferences(MODE_PRIVATE).getBoolean("consent", false)) }
                var email by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var status by remember { mutableStateOf("Sharing is stopped until you review and accept the disclosure.") }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("The Wedding of Mouni & Naveen", style = MaterialTheme.typography.headlineSmall)
                    Text("MouniNaveen Collector", style = MaterialTheme.typography.titleLarge)
                    Text("This app can read Android app-usage summaries only after you grant Usage Access and explicitly consent. It does not collect messages, notifications, keystrokes, screen contents, audio, photos or files. Usage history may be delayed by Android.")
                    Text("Records are intended for the administrator who enrolled this device in the MouniNaveenTracker Firebase project. Stop sharing at any time below.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { consent = true; getPreferences(MODE_PRIVATE).edit().putBoolean("consent", true).apply(); status = "Consent recorded locally. Sign in and grant Usage Access to continue." }) { Text("I consent") }
                        OutlinedButton(onClick = { consent = false; getPreferences(MODE_PRIVATE).edit().putBoolean("consent", false).apply(); status = "Sharing stopped. Pending local uploads must be discarded before enabling sync." }) { Text("Stop sharing") }
                    }
                    Text("Usage Access: ${if (hasUsageAccess(this@MainActivity)) "Granted" else "Not granted"}")
                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open Usage Access settings") }
                    Divider()
                    Text("Collector account (pre-created by your administrator)")
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true)
                    Button(enabled = consent && email.isNotBlank() && password.isNotBlank(), onClick = {
                        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
                            .addOnSuccessListener { status = "Signed in. Device enrollment and Firestore rules must be configured before uploads." }
                            .addOnFailureListener { status = "Sign-in failed: ${it.localizedMessage ?: "Check account setup"}" }
                    }) { Text("Sign in") }
                    Text(status)
                    Text("Privacy: Consent is stored on this device. This baseline does not yet schedule collection or upload records; see README and docs before use.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}
