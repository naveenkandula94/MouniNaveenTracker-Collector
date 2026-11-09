package com.mouninaveen.collector

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.RTCStatsCollectorCallback
import kotlin.math.sqrt
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule as NewAudioDeviceModule

/**
 * One-way, device-owner-started microphone stream. No recording, file saving or remote activation.
 * WebRTC uses DTLS/SRTP; Firestore is used for SDP/ICE signaling, not audio.
 */
internal class LiveAudioPeer(
    context: Context,
    private val broadcaster: Boolean,
    private val onIce: (IceCandidate) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private var initialized = false
        @Synchronized private fun init(context: Context) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                )
                initialized = true
            }
        }
    }

    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteReady = false
    @Volatile private var closed = false
    private var lastEnergy: Double? = null
    private var lastSampleDuration: Double? = null

    init {
        init(context)
        val module = NewAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        module.release()
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        peer = requireNotNull(factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    onStatus("Audio connection: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) { if (candidate != null) onIce(candidate) }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )) { "Could not create WebRTC audio peer" }
        if (broadcaster) {
            source = factory.createAudioSource(MediaConstraints())
            track = factory.createAudioTrack("consented-microphone", source)
            track?.setEnabled(true)
            requireNotNull(peer.addTrack(track, listOf("owner-approved-live-mic"))) {
                "Could not attach microphone audio track"
            }
        } else {
            // The listener receives audio only; it cannot turn on the Collector's microphone.
            peer.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                org.webrtc.RtpTransceiver.RtpTransceiverInit(
                    org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                )
            )
        }
    }

    private fun observer(onSuccess: () -> Unit): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() = onSuccess()
        override fun onCreateFailure(error: String?) { onStatus("SDP failed: $error") }
        override fun onSetFailure(error: String?) { onStatus("SDP failed: $error") }
    }

    fun createOffer(onOffer: (String) -> Unit) {
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peer.setLocalDescription(observer { onOffer(sdp.description) }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { onStatus("Offer failed: $error") }
            override fun onSetFailure(error: String?) { onStatus("Offer failed: $error") }
        }, MediaConstraints())
    }

    fun receiveOffer(offer: String, onAnswer: (String) -> Unit) {
        peer.setRemoteDescription(observer {
            remoteReady = true
            flushIce()
            peer.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) return
                    peer.setLocalDescription(observer { onAnswer(sdp.description) }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { onStatus("Answer failed: $error") }
                override fun onSetFailure(error: String?) { onStatus("Answer failed: $error") }
            }, MediaConstraints())
        }, SessionDescription(SessionDescription.Type.OFFER, offer))
    }

    fun receiveAnswer(answer: String) {
        peer.setRemoteDescription(observer {
            remoteReady = true
            flushIce()
        }, SessionDescription(SessionDescription.Type.ANSWER, answer))
    }

    @Synchronized fun addRemoteCandidate(candidate: IceCandidate) {
        if (remoteReady) peer.addIceCandidate(candidate) else pendingIce.add(candidate)
    }

    @Synchronized private fun flushIce() {
        pendingIce.forEach { peer.addIceCandidate(it) }
        pendingIce.clear()
    }

    /**
     * Collect the audio level from the active WebRTC peer (0 to 1).
     * Sender uses local audio media-source stats; listener uses incoming RTP.
     * No new microphone capture, audio recording, or Firebase data is involved.
     */
    fun sampleAudioLevel(onLevel: (Float) -> Unit) {
        if (closed) { onLevel(0f); return }
        try {
            peer.getStats(RTCStatsCollectorCallback { report ->
                if (closed) return@RTCStatsCollectorCallback
                val matching = report.statsMap.values.filter { stat ->
                    val values = stat.members
                    if (broadcaster) {
                        stat.type == "media-source" &&
                            (values["kind"] == "audio" || values["mediaType"] == "audio")
                    } else {
                        stat.type == "inbound-rtp" &&
                            (values["kind"] == "audio" || values["mediaType"] == "audio")
                    }
                }
                val amplitudes = matching.mapNotNull { stat ->
                    (stat.members["audioLevel"] as? Number)?.toDouble()
                }
                var measured = amplitudes.maxOrNull()
                if (measured == null) {
                    // Some native WebRTC builds report only cumulative audio energy.
                    // Its derivative is mean square audio amplitude in the interval.
                    val currentEnergy = matching.mapNotNull {
                        (it.members["totalAudioEnergy"] as? Number)?.toDouble()
                    }.maxOrNull()
                    val currentDuration = matching.mapNotNull {
                        (it.members["totalSamplesDuration"] as? Number)?.toDouble()
                    }.maxOrNull()
                    if (currentEnergy != null && currentDuration != null) {
                        val deltaEnergy = lastEnergy?.let { currentEnergy - it }
                        val deltaDuration = lastSampleDuration?.let { currentDuration - it }
                        if (deltaEnergy != null && deltaDuration != null && deltaDuration > 0.0) {
                            measured = sqrt((deltaEnergy / deltaDuration).coerceIn(0.0, 1.0))
                        }
                        lastEnergy = currentEnergy
                        lastSampleDuration = currentDuration
                    }
                }
                val levelValue = measured ?: 0.0
                // WebRTC reports a 0..1 amplitude, which is frequently small
                // for ordinary speech. Use sqrt for a readable display.
                onLevel(sqrt(levelValue.coerceIn(0.0, 1.0)).toFloat())
            })
        } catch (_: Exception) {
            onLevel(0f)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        track?.setEnabled(false)
        peer.close()
        peer.dispose()
        track?.dispose()
        source?.dispose()
        factory.dispose()
    }
}
