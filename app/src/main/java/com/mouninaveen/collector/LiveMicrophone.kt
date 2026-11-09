package com.mouninaveen.collector

import android.Manifest
import android.content.Context
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate

private class PublisherSession(
    val peer: LiveAudioPeer,
    val reference: DocumentReference,
    val listeners: List<ListenerRegistration>
) {
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        listeners.forEach { it.remove() }
        peer.close()
        reference.update("status", "ENDED", "endedAt", FieldValue.serverTimestamp())
    }
}

@Composable
fun LiveMicrophonePublisher(sharingEnabled: Boolean, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<PublisherSession?>(null) }
    var starting by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var sessionConsent by remember { mutableStateOf(false) }
    var connectionState by remember { mutableStateOf("Not sharing") }
    var audioLevel by remember { mutableFloatStateOf(0f) }

    fun begin() {
        if (starting || session != null || !sessionConsent || !sharingEnabled) return
        starting = true
        scope.launch {
            try {
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "Microphone permission is required."
                }
                val uid = auth.currentUser?.uid ?: error("Sign in as the Collector first.")
                val prefs = context.getSharedPreferences("collector", Context.MODE_PRIVATE)
                check(prefs.getBoolean("consent", false)) { "Sharing consent is off." }
                val device = DeviceBinding.resolve(context, uid)
                val ref = device.reference.collection("liveAudio").document()
                ref.set(mapOf(
                    "collectorUid" to uid,
                    "deviceId" to device.id,
                    "status" to "STARTING",
                    "createdAt" to FieldValue.serverTimestamp()
                )).await()
                val peer = LiveAudioPeer(context, true,
                    onIce = { candidate ->
                        ref.collection("publisherIce").add(mapOf(
                            "sdp" to candidate.sdp,
                            "mid" to (candidate.sdpMid ?: ""),
                            "line" to candidate.sdpMLineIndex
                        )).addOnFailureListener { error ->
                            onStatus("WebRTC ICE signaling failed: ${error.localizedMessage}")
                        }
                    },
                    onStatus = { message ->
                        (context as android.app.Activity).runOnUiThread {
                            connectionState = message
                            onStatus(message)
                        }
                    }
                )
                var answerApplied = false
                val signal = ref.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        connectionState = "Signaling error: ${error.localizedMessage}"
                        onStatus(connectionState)
                        return@addSnapshotListener
                    }
                    if (snapshot?.getString("status") == "ENDED") {
                        connectionState = "Session ended. Tap Stop sharing to close."
                    }
                    val answer = snapshot?.getString("answer")
                    if (answer != null && answer.isNotBlank() && snapshot.getString("status") == "ANSWERED" && !answerApplied) {
                        answerApplied = true
                        peer.receiveAnswer(answer)
                        connectionState = "Connecting audio…"
                    }
                }
                val ice = ref.collection("listenerIce").addSnapshotListener { snapshot, _ ->
                    snapshot?.documentChanges?.forEach { change ->
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val d = change.document
                            val sdp = d.getString("sdp") ?: return@forEach
                            peer.addRemoteCandidate(IceCandidate(d.getString("mid") ?: "", d.getLong("line")?.toInt() ?: 0, sdp))
                        }
                    }
                }
                session = PublisherSession(peer, ref, listOf(signal, ice))
                peer.createOffer { offer ->
                    ref.update("offer", offer, "status", "OFFERED").addOnFailureListener { error ->
                        onStatus("Could not publish microphone offer: ${error.localizedMessage}")
                    }
                }
                connectionState = "Live microphone sharing started. Keep this screen open."
                onStatus("Live microphone session created; Admin can now join.")
            } catch (e: Exception) {
                onStatus("Microphone session failed: ${e.localizedMessage}")
            } finally {
                starting = false
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) begin() else onStatus("Microphone permission denied. No audio sent.")
    }
    // Snapshot the specific session: capturing mutable state closed the newly started
    // PeerConnection when DisposableEffect was restarted after Start was pressed.
    DisposableEffect(session) {
        val currentSession = session
        onDispose { currentSession?.stop() }
    }
    LaunchedEffect(sharingEnabled) {
        if (!sharingEnabled && session != null) {
            session?.stop()
            session = null
            sessionConsent = false
            connectionState = "Sharing consent withdrawn; microphone stopped."
        }
    }

    LaunchedEffect(session) {
        val activeSession = session
        audioLevel = 0f
        while (activeSession != null && isActive) {
            activeSession.peer.sampleAudioLevel { sample ->
                (context as android.app.Activity).runOnUiThread { audioLevel = sample }
            }
            delay(250L)
        }
        audioLevel = 0f
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live microphone", style = MaterialTheme.typography.titleLarge)
            Text("The device owner must start this stream. Admin can listen only while sharing is active. No audio is saved to Firebase.")
            Text("Audio is end-to-end encrypted by WebRTC. Keep this screen open during the session; background sharing is not enabled.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = sessionConsent,
                    enabled = session == null,
                    onCheckedChange = { sessionConsent = it }
                )
                Text("I agree to transmit live microphone sound to my paired Admin until I press Stop.")
            }
            Text("Microphone permission: ${if (permissionGranted) "Granted" else "Not granted"}")
            Text("Session: $connectionState")
            AudioLevelMeter(
                label = "Microphone input • live volume",
                level = audioLevel,
                active = session != null
            )
            Text(
                "Speak near the phone. The bar reflects the actual WebRTC microphone input; " +
                    "it does not confirm Admin is hearing it until the call connects.",
                style = MaterialTheme.typography.bodySmall
            )
            if (session == null) {
                Button(
                    enabled = sharingEnabled && sessionConsent && !starting && auth.currentUser != null,
                    onClick = {
                        permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (permissionGranted) begin() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                ) { Text(if (starting) "Starting…" else "Start live microphone") }
            } else {
                Button(onClick = {
                    session?.stop()
                    session = null
                    sessionConsent = false
                    connectionState = "Stopped"
                    onStatus("Microphone sharing stopped.")
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Stop microphone sharing")
                }
            }
            if (!sharingEnabled) Text("Enable Collector consent above before starting live audio.", color = MaterialTheme.colorScheme.error)
        }
    }
}
