package com.mouninaveen.collector

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

/**
 * UI controls only. The live peer is owned by the user-started foreground
 * service, not by Compose navigation. Switching tabs/app or locking phone
 * does not dispose the ongoing stream.
 */
@Composable
fun LiveMicrophonePublisher(sharingEnabled: Boolean, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val stream by MicStreamStatus.state.collectAsState()
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED)
    }
    var perSessionConsent by remember { mutableStateOf(false) }

    fun startFromVisibleApp() {
        if (!perSessionConsent || !sharingEnabled || stream.active) return
        try {
            check(auth.currentUser != null) { "Sign in first." }
            val uid = auth.currentUser!!.uid
            check(DeviceBinding.selectedId(context, uid) != null) {
                "Choose THIS phone in Account before starting microphone sharing."
            }
            check(permissionGranted) { "Grant microphone permission first." }
            MicrophoneStreamService.start(context as Activity)
            onStatus("Starting foreground microphone service with visible Stop notification.")
        } catch (e: Exception) {
            onStatus("Microphone could not start: ${e.localizedMessage}")
        }
    }

    val micPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) startFromVisibleApp()
        else onStatus("Android microphone permission denied. Nothing is transmitted.")
    }

    LaunchedEffect(sharingEnabled) {
        if (!sharingEnabled && stream.active) {
            MicrophoneStreamService.stop(context)
            perSessionConsent = false
        }
    }

    LaunchedEffect(stream.status) {
        if (stream.status != "Not sharing") onStatus(stream.status)
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live microphone sharing", style = MaterialTheme.typography.titleLarge)
            Text(
                "Start a live session yourself. Microphone audio goes only to the Admin " +
                    "connected to your chosen phone; no audio is saved to Firebase."
            )
            Text(
                "An ongoing Android notification with a Stop button stays visible while sharing. " +
                    "You can switch apps or lock the screen without closing this user-started session.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = perSessionConsent || stream.active,
                    enabled = !stream.active,
                    onCheckedChange = { perSessionConsent = it }
                )
                Text(
                    "I approve live microphone sharing for this session until I press Stop. " +
                        "The foreground notification identifies active capture."
                )
            }
            Text("Permission: ${if (permissionGranted) "Granted" else "Not granted"}")
            Text("Stream: ${stream.status}")
            AudioLevelMeter(
                label = "Microphone input • live volume",
                level = stream.inputLevel,
                active = stream.active && !stream.starting
            )
            if (!stream.active) {
                Button(
                    enabled = sharingEnabled && perSessionConsent && auth.currentUser != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        permissionGranted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!permissionGranted) {
                            micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                        } else startFromVisibleApp()
                    }
                ) { Text("Start microphone session") }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        MicrophoneStreamService.stop(context)
                        perSessionConsent = false
                    }
                ) { Text("Stop microphone sharing") }
            }
            if (!sharingEnabled) Text(
                "Enable sharing in Home before starting a new session.",
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "Android can end background services under resource pressure, or when you " +
                    "force-stop the app. After reboot or force-stop, microphone sharing does not " +
                    "restart automatically; a new user action is required.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
