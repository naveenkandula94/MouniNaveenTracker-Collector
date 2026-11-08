
package com.mouninaveen.collector

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {

    private var refreshUsageAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var consent by remember {
                    mutableStateOf(
                        getPreferences(MODE_PRIVATE)
                            .getBoolean("consent", false)
                    )
                }

                var email by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var signedIn by remember {
                    mutableStateOf(FirebaseAuth.getInstance().currentUser != null)
                }
                var usageGranted by remember {
                    mutableStateOf(hasUsageAccess(this@MainActivity))
                }
                var status by remember {
                    mutableStateOf("Sharing is stopped until consent and setup are completed.")
                }

                // Refresh permission status when returning from Android settings.
                LaunchedEffect(refreshUsageAccess) {
                    usageGranted = hasUsageAccess(this@MainActivity)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "The Wedding of Mouni & Naveen",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Text(
                        text = "MouniNaveen Collector",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = "This app is designed to share limited Android app-usage information only with the administrator who enrolled this device. Collection requires your explicit consent and Android Usage Access permission."
                    )

                    // DATA SHARING DISCLOSURE
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "What data may be shared?",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text("APP USAGE INFORMATION")
                            Text("• App package name")
                            Text("• App display name, when available")
                            Text("• App foreground start and end times, when available")
                            Text("• Measured app usage duration")
                            Text("• Permitted app activity event type and timestamp")
                            Text("• Device enrollment identifier")

                            HorizontalDivider()

                            Text("SYNC AND CONSENT INFORMATION")
                            Text("• Consent status and consent timestamp")
                            Text("• Synchronization status and relevant timestamps")

                            HorizontalDivider()

                            Text(
                                text = "What this app does NOT collect",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text("• WhatsApp or other message contents")
                            Text("• SMS or call logs")
                            Text("• Notification contents")
                            Text("• Passwords or keystrokes")
                            Text("• Screenshots or screen recordings")
                            Text("• Microphone or camera recordings")
                            Text("• Personal photos, videos or documents")

                            HorizontalDivider()

                            Text("DESTINATION")
                            Text("Firebase project: mouninaveentracker")
                            Text("Recipient: the authorized administrator who enrolled this device.")

                            HorizontalDivider()

                            Text(
                                text = "CURRENT IMPLEMENTATION STATUS",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "This starter build does not yet implement actual UsageStats collection or Firebase activity uploads. The information above describes the intended data policy, not proof that records are currently being transmitted."
                            )
                        }
                    }

                    Text(
                        text = "Your consent",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "Review the information above. You can stop sharing at any time."
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            enabled = !consent,
                            onClick = {
                                consent = true
                                getPreferences(MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("consent", true)
                                    .apply()

                                status =
                                    "Consent saved on this device. Complete account and permission setup next."
                            }
                        ) {
                            Text("I consent")
                        }

                        OutlinedButton(
                            onClick = {
                                consent = false

                                getPreferences(MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("consent", false)
                                    .apply()

                                status =
                                    "Consent withdrawn. Collection must remain stopped."
                            }
                        ) {
                            Text("Stop sharing")
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "Android permission",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "Usage Access: ${if (usageGranted) "Granted" else "Not granted"}"
                    )

                    OutlinedButton(
                        onClick = {
                            startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            )
                        }
                    ) {
                        Text("Open Usage Access settings")
                    }

                    HorizontalDivider()

                    Text(
                        text = "Collector account",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "Use the account provisioned by your administrator."
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Collector email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Collector password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        enabled = consent &&
                            email.isNotBlank() &&
                            password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            status = "Signing in..."

                            FirebaseAuth.getInstance()
                                .signInWithEmailAndPassword(
                                    email.trim(),
                                    password
                                )
                                .addOnSuccessListener {
                                    signedIn = true
                                    password = ""
                                    status =
                                        "Account authenticated. Usage Access: ${
                                            if (usageGranted) "Granted" else "Not granted"
                                        }. Activity collection and upload are not implemented in this starter build."
                                }
                                .addOnFailureListener { error ->
                                    signedIn = false
                                    status =
                                        "Sign-in failed: ${
                                            error.localizedMessage ?: "Check account and network"
                                        }"
                                }
                        }
                    ) {
                        Text("Sign in")
                    }

                    Text(
                        text = if (signedIn) {
                            "Account status: Signed in"
                        } else {
                            "Account status: Not signed in"
                        }
                    )

                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = "You can withdraw consent at any time using Stop sharing.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUsageAccess = !refreshUsageAccess
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(
        Context.APP_OPS_SERVICE
    ) as AppOpsManager

    @Suppress("DEPRECATION")
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )

    return mode == AppOpsManager.MODE_ALLOWED
}
