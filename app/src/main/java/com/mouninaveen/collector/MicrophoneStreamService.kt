package com.mouninaveen.collector

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate

/**
 * One expressly user-started, visible microphone-sharing session.
 * Foreground service keeps the ongoing WebRTC peer alive while the Activity
 * is minimized, switched or the screen is locked. No auto-start after reboot,
 * force-stop or process death; this service returns START_NOT_STICKY.
 */
data class MicStreamState(
    val active: Boolean = false,
    val starting: Boolean = false,
    val status: String = "Not sharing",
    val inputLevel: Float = 0f
)

object MicStreamStatus {
    private val mutable = MutableStateFlow(MicStreamState())
    val state: StateFlow<MicStreamState> = mutable
    internal fun set(value: MicStreamState) { mutable.value = value }
    internal fun status(message: String) {
        mutable.value = mutable.value.copy(status = message)
    }
    internal fun level(value: Float) {
        mutable.value = mutable.value.copy(inputLevel = value)
    }
}

class MicrophoneStreamService : Service() {
    companion object {
        const val ACTION_START = "com.mouninaveen.collector.START_MIC_SHARING"
        const val ACTION_STOP = "com.mouninaveen.collector.STOP_MIC_SHARING"
        private const val CHANNEL = "ongoing_mic_stream"
        private const val NOTIFICATION_ID = 415

        /** Must be called from a visible Activity after an explicit user tap. */
        fun start(activity: Activity) {
            val intent = Intent(activity, MicrophoneStreamService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(activity, intent)
        }
        fun stop(context: Context) {
            context.startService(
                Intent(context, MicrophoneStreamService::class.java)
                    .setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionPeer: LiveAudioPeer? = null
    private var sessionRef: DocumentReference? = null
    private var registrations = emptyList<ListenerRegistration>()
    private var sessionId = 0
    private var streamOwnerUid: String? = null
    private var streamDeviceId: String? = null
    private var isStarted = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL, "Active microphone sharing", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Visible notification with Stop control while microphone audio is shared"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicrophoneStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Personal_App: microphone is being shared")
            .setContentText("Live session in progress. Tap Stop to end sharing.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "Stop sharing", stop
                ).build()
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                endSession("Microphone stopped by phone owner.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isStarted) return START_NOT_STICKY
                // A microphone FGS must be created while the owner still has
                // a visible Activity and RECORD_AUDIO is in its while-in-use scope.
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
                    MicStreamStatus.status("Microphone permission is not granted.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    val notification = ongoingNotification()
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(
                            NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else startForeground(NOTIFICATION_ID, notification)
                    isStarted = true
                    MicStreamStatus.set(MicStreamState(active = true, starting = true,
                        status = "Starting owner-approved microphone sharing…"))
                    scope.launch { startSession() }
                    scope.launch { monitorOwnerConsent() }
                } catch (e: Exception) {
                    MicStreamStatus.status("Unable to start a visible microphone service: ${e.localizedMessage}")
                    stopSelf()
                }
            }
        }
        // Never silently restart a microphone capture after process death.
        return START_NOT_STICKY
    }

    private fun recordPeerStatus(message: String, generation: Int) {
        if (!isStarted || generation != sessionId) return
        MicStreamStatus.status(message)
        val connectionState = message.substringAfterLast(": ").trim()
        if (connectionState == "CONNECTED" || connectionState == "COMPLETED") {
            reconnectAttempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
        } else if (connectionState == "DISCONNECTED" || connectionState == "FAILED") {
            if (reconnectJob?.isActive == true) return
            if (reconnectAttempts >= 3) {
                MicStreamStatus.status(
                    "Audio connection lost. Tap Stop and start another session to retry."
                )
                return
            }
            val pendingGeneration = sessionId
            reconnectJob = scope.launch {
                // Give WebRTC a chance to recover transient mobile handovers itself.
                delay(if (connectionState == "DISCONNECTED") 15_000L else 5_000L)
                if (!isStarted || pendingGeneration != sessionId) return@launch
                reconnectAttempts++
                MicStreamStatus.status(
                    "Reconnecting microphone session (${reconnectAttempts}/3)…"
                )
                clearCurrentPeer()
                startSession()
            }
        }
    }

    private fun clearCurrentPeer() {
        registrations.forEach { it.remove() }
        registrations = emptyList()
        sessionPeer?.close()
        sessionPeer = null
        sessionRef?.update(
            "status", "ENDED", "endedAt", FieldValue.serverTimestamp()
        )
        sessionRef = null
    }

    private suspend fun startSession() {
        val generation = ++sessionId
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
                ?: error("Sign in to Personal_App before starting the microphone.")
            val consent = getSharedPreferences("collector", MODE_PRIVATE)
                .getBoolean("consent", false)
            check(consent) { "Sharing has been disabled by the owner." }
            MicStreamStatus.status("Checking linked phone and Firebase enrollment…")
            val device = withTimeout(20_000L) { DeviceBinding.resolve(this@MicrophoneStreamService, uid) }
            if (!isStarted || generation != sessionId) return
            streamOwnerUid = uid
            streamDeviceId = device.id
            val ref = device.reference.collection("liveAudio").document()
            MicStreamStatus.status("Creating microphone session in Firebase…")
            withTimeout(25_000L) {
                ref.set(mapOf(
                    "collectorUid" to uid,
                    "deviceId" to device.id,
                    "status" to "STARTING",
                    "createdAt" to FieldValue.serverTimestamp()
                )).await()
            }

            if (!isStarted || generation != sessionId) {
                ref.update("status", "ENDED", "endedAt", FieldValue.serverTimestamp())
                return
            }
            sessionRef = ref
            val peer = LiveAudioPeer(this, true,
                onIce = { candidate ->
                    ref.collection("publisherIce").add(mapOf(
                        "sdp" to candidate.sdp,
                        "mid" to (candidate.sdpMid ?: ""),
                        "line" to candidate.sdpMLineIndex
                    )).addOnFailureListener { error ->
                        MicStreamStatus.status(
                            "Microphone network signaling failed: ${error.localizedMessage}"
                        )
                    }
                },
                onStatus = { message ->
                    recordPeerStatus(message, generation)
                }
            )
            sessionPeer = peer
            var appliedAnswer = false
            val answerListener = ref.addSnapshotListener { snapshot, error ->
                if (!isStarted || generation != sessionId) return@addSnapshotListener
                if (error != null) {
                    MicStreamStatus.status("Signaling error: ${error.localizedMessage}")
                    return@addSnapshotListener
                }
                if (snapshot?.getString("status") == "ENDED") {
                    MicStreamStatus.status("Session ended by phone or network.")
                    return@addSnapshotListener
                }
                val answer = snapshot?.getString("answer")
                if (!appliedAnswer && !answer.isNullOrBlank() &&
                    snapshot.getString("status") == "ANSWERED") {
                    appliedAnswer = true
                    peer.receiveAnswer(answer)
                    MicStreamStatus.status("Connecting live audio to Admin…")
                }
            }
            val candidates = ref.collection("listenerIce").addSnapshotListener { snapshot, _ ->
                if (!isStarted || generation != sessionId) return@addSnapshotListener
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val data = change.document
                        val sdp = data.getString("sdp") ?: return@forEach
                        peer.addRemoteCandidate(IceCandidate(
                            data.getString("mid") ?: "",
                            data.getLong("line")?.toInt() ?: 0, sdp
                        ))
                    }
                }
            }
            registrations = listOf(answerListener, candidates)
            MicStreamStatus.set(MicStreamState(
                active = true, starting = true,
                status = "Preparing WebRTC microphone offer for the selected phone."
            ))
            peer.createOffer { offer ->
                if (isStarted && generation == sessionId) {
                    ref.update("offer", offer, "status", "OFFERED")
                        .addOnSuccessListener {
                            if (isStarted && generation == sessionId) {
                                MicStreamStatus.set(MicStreamState(
                                    active = true, starting = false,
                                    status = "Microphone offer published. Open Admin Live and tap Listen now."
                                ))
                                CollectorDiagnostics.record(this, "AUDIO",
                                    "WebRTC offer published for selected phone.")
                            }
                        }
                        .addOnFailureListener { error ->
                            if (isStarted && generation == sessionId) {
                                val message = "Could not publish microphone offer: ${error.localizedMessage}"
                                MicStreamStatus.status(message)
                                CollectorDiagnostics.record(this, "AUDIO", message)
                            }
                        }
                }
            }
            CollectorDiagnostics.record(this, "AUDIO",
                "User-started foreground microphone session, phone-specific and visible.")

            // A WebRTC or Firestore callback can stall without throwing. Do not
            // misleadingly leave the screen saying "Starting" indefinitely.
            scope.launch {
                delay(45_000L)
                if (isStarted && generation == sessionId && MicStreamStatus.state.value.starting) {
                    val reason = "No microphone offer after 45 seconds. Check Firebase rules and network, then Start again."
                    CollectorDiagnostics.record(this@MicrophoneStreamService, "AUDIO", reason)
                    endSession(reason)
                    stopSelf()
                }
            }

            // Stats sampling belongs to the service rather than UI lifecycle.
            scope.launch {
                while (isStarted && generation == sessionId) {
                    peer.sampleAudioLevel { sample -> MicStreamStatus.level(sample) }
                    delay(250L)
                }
            }
        } catch (e: TimeoutCancellationException) {
            if (!isStarted || generation != sessionId) return
            val reason = "Firebase enrollment/session timed out. Check network, selected phone and published Firestore rules."
            CollectorDiagnostics.record(this, "AUDIO", reason)
            endSession(reason)
            stopSelf()
        } catch (e: CancellationException) {
            // Cancellation of an older start is expected when Stop was pressed.
            // An obsolete coroutine must never close the next live session.
            if (!isStarted || generation != sessionId) return
            val reason = "Microphone startup interrupted. Tap Start to retry."
            CollectorDiagnostics.record(this, "AUDIO", reason)
            endSession(reason)
            stopSelf()
        } catch (e: Exception) {
            if (!isStarted || generation != sessionId) return
            val reason = "Microphone start failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            CollectorDiagnostics.record(this, "AUDIO", reason)
            endSession(reason)
            stopSelf()
        }
    }

    private suspend fun monitorOwnerConsent() {
        while (isStarted) {
            val stillConsented = getSharedPreferences("collector", MODE_PRIVATE)
                .getBoolean("consent", false)
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            if (!stillConsented || (streamOwnerUid != null && streamOwnerUid != currentUid) ||
                (streamOwnerUid != null && streamDeviceId !=
                    DeviceBinding.selectedId(this, currentUid ?: ""))) {
                endSession("Owner withdrew sharing or signed out.")
                stopSelf()
                return
            }
            delay(2_000L)
        }
    }

    private fun endSession(reason: String) {
        if (!isStarted && sessionPeer == null) return
        isStarted = false
        sessionId++
        reconnectJob?.cancel()
        reconnectJob = null
        clearCurrentPeer()
        streamOwnerUid = null
        streamDeviceId = null
        MicStreamStatus.set(MicStreamState(status = reason))
        CollectorDiagnostics.record(this, "AUDIO", reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        endSession("Microphone service stopped. Start a new session in Personal_App.")
        scope.cancel()
        super.onDestroy()
    }
}
