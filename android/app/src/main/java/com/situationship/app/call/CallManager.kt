package com.situationship.app.call

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.situationship.app.data.model.PublicProfile
import com.situationship.app.data.remote.errorMessage
import com.situationship.app.data.socket.RtEvent
import com.situationship.app.data.socket.SocketManager
import com.situationship.app.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED }

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val matchId: String? = null,
    val kind: String = "video",
    val peer: PublicProfile? = null,
    val muted: Boolean = false,
    val cameraOff: Boolean = false,
    val speakerOn: Boolean = false,
    val frontCamera: Boolean = true,
    val connectedAt: Long? = null,
    val localVideo: VideoTrack? = null,
    val remoteVideo: VideoTrack? = null,
    /** Accept automatically when this call arrives (user tapped "Accept" on the notification). */
    val autoAcceptCallId: String? = null,
) {
    val isVideo get() = kind == "video"
    val active get() = phase != CallPhase.IDLE
}

/**
 * Audio/video calls: Socket.IO signaling (invite/accept/offer/answer/ICE) + WebRTC media (DTLS-SRTP encrypted).
 * Camera and microphone are only opened after the user starts or accepts a call.
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val socket: SocketManager,
    @AppScope private val scope: CoroutineScope,
) {
    private val tag = "Call"
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val eglBase: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var pc: PeerConnection? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private val pendingIce = mutableListOf<IceCandidate>()
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ringer = Ringer(ctx)

    init {
        scope.launch {
            socket.events.collect { ev ->
                // never let one bad signaling message kill the collector
                runCatching { onEvent(ev) }.onFailure { Log.e(tag, "signaling error", it); if (_state.value.active) hangUp() }
            }
        }
    }

    // --------------------------------------------------------------------------------- public API

    /** Caller side. Permissions must already be granted. */
    fun startCall(matchId: String, peer: PublicProfile, kind: String) {
        if (_state.value.active) return emit("You are already in a call")
        if (!socket.isConnected) return emit("Connecting… try again in a moment")
        _state.value = CallUiState(phase = CallPhase.OUTGOING, matchId = matchId, peer = peer, kind = kind, speakerOn = kind == "video")
        scope.launch(Dispatchers.Main) {
            openLocalMedia(kind)
            val ack = socket.emitWithAck("call:invite", JSONObject().put("matchId", matchId).put("kind", kind))
            val err = ack?.optString("error", "")?.takeIf { it.isNotEmpty() } ?: if (ack == null) "network" else null
            if (err != null) {
                cleanup()
                return@launch emit(errorMessage(err))
            }
            iceServers = parseIce(ack!!.optJSONArray("iceServers"))
            _state.update { it.copy(callId = ack.getString("callId")) }
            ringer.startRingback()
            startForegroundService()
        }
    }

    /** Callee side. Permissions must already be granted. */
    fun accept() {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING || s.callId == null) return
        ringer.stop()
        _state.update { it.copy(phase = CallPhase.CONNECTING) }
        scope.launch(Dispatchers.Main) {
            openLocalMedia(s.kind) // camera/mic open only now, after the user accepted
            val ack = socket.emitWithAck("call:accept", JSONObject().put("callId", s.callId))
            if (ack == null || ack.optString("error", "").isNotEmpty()) {
                cleanup(); return@launch emit("Call is no longer available")
            }
            iceServers = parseIce(ack.optJSONArray("iceServers"))
            createPeer()
            startForegroundService()
        }
    }

    fun reject() {
        _state.value.callId?.let { socket.emit("call:reject", JSONObject().put("callId", it)) }
        cleanup()
    }

    fun hangUp() {
        _state.value.callId?.let { socket.emit("call:end", JSONObject().put("callId", it)) }
        cleanup()
    }

    fun toggleMute() {
        val m = !_state.value.muted
        audioTrack?.setEnabled(!m)
        _state.update { it.copy(muted = m) }
    }

    fun toggleCamera() {
        val off = !_state.value.cameraOff
        videoTrack?.setEnabled(!off)
        if (off) capturer?.stopCapture() else capturer?.startCapture(1280, 720, 30)
        _state.update { it.copy(cameraOff = off) }
    }

    fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { _state.update { it.copy(frontCamera = isFront) } }
            override fun onCameraSwitchError(error: String?) { emit("Could not switch camera") }
        })
    }

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        routeAudio(on)
        _state.update { it.copy(speakerOn = on) }
    }

    /** Set when the user tapped "Accept" on the incoming-call notification before the socket was connected. */
    fun autoAcceptWhenArrives(callId: String) {
        _state.update { it.copy(autoAcceptCallId = callId) }
    }

    // --------------------------------------------------------------------------------- signaling

    private suspend fun onEvent(ev: RtEvent) = withContext<Unit>(Dispatchers.Main) {
        val s = _state.value
        when (ev) {
            is RtEvent.CallIncoming -> {
                if (s.active) { socket.emit("call:reject", JSONObject().put("callId", ev.c.callId)); return@withContext }
                _state.value = CallUiState(
                    phase = CallPhase.INCOMING, callId = ev.c.callId, matchId = ev.c.matchId, kind = ev.c.kind,
                    peer = ev.c.from, speakerOn = ev.c.kind == "video",
                    // UI auto-accepts (after the permission check) when the user already tapped Accept on the notification
                    autoAcceptCallId = s.autoAcceptCallId?.takeIf { it == ev.c.callId },
                )
                if (s.autoAcceptCallId != ev.c.callId) ringer.startRingtone()
            }
            is RtEvent.CallAccepted -> if (s.callId == ev.callId && s.phase == CallPhase.OUTGOING) {
                ringer.stop()
                _state.update { it.copy(phase = CallPhase.CONNECTING) }
                val peer = createPeer()
                val offer = peer.createSdp(offer = true)
                peer.setLocal(offer)
                socket.emit("call:offer", JSONObject().put("callId", ev.callId).put("sdp", offer.toJson()))
            }
            is RtEvent.CallOffer -> if (s.callId == ev.callId) {
                val peer = pc ?: return@withContext
                peer.setRemote(ev.sdp.toSdp())
                flushIce()
                val answer = peer.createSdp(offer = false)
                peer.setLocal(answer)
                socket.emit("call:answer", JSONObject().put("callId", ev.callId).put("sdp", answer.toJson()))
            }
            is RtEvent.CallAnswer -> if (s.callId == ev.callId) {
                pc?.setRemote(ev.sdp.toSdp())
                flushIce()
            }
            is RtEvent.CallIce -> if (s.callId == ev.callId) {
                val c = IceCandidate(ev.candidate.optString("sdpMid"), ev.candidate.optInt("sdpMLineIndex"), ev.candidate.optString("candidate"))
                val peer = pc
                if (peer?.remoteDescription != null) peer.addIceCandidate(c) else pendingIce += c
            }
            is RtEvent.CallEnded -> if (s.callId == ev.callId) {
                cleanup()
                emit(
                    when (ev.reason) {
                        "rejected" -> "Call declined"
                        "no_answer" -> "No answer"
                        "answered_elsewhere" -> "Answered on another device"
                        "disconnected" -> "Call disconnected"
                        else -> "Call ended"
                    }
                )
            }
            else -> Unit
        }
    }

    private fun flushIce() {
        val peer = pc ?: return
        pendingIce.forEach { peer.addIceCandidate(it) }
        pendingIce.clear()
    }

    // --------------------------------------------------------------------------------- media

    private fun openLocalMedia(kind: String) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeAudio(kind == "video")
        val aSrc = factory.createAudioSource(MediaConstraints())
        audioSource = aSrc
        audioTrack = factory.createAudioTrack("audio0", aSrc)
        if (kind == "video") {
            val enumerator = Camera2Enumerator(ctx)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                val cap = enumerator.createCapturer(name, null)
                val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
                val src = factory.createVideoSource(cap.isScreencast)
                cap.initialize(helper, ctx, src.capturerObserver)
                cap.startCapture(1280, 720, 30)
                capturer = cap; surfaceHelper = helper; videoSource = src
                videoTrack = factory.createVideoTrack("video0", src)
                _state.update { it.copy(localVideo = videoTrack, frontCamera = enumerator.isFrontFacing(name)) }
            }
        }
    }

    private fun createPeer(): PeerConnection {
        pc?.let { return it }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                val id = _state.value.callId ?: return
                socket.emit("call:ice", JSONObject().put("callId", id).put("candidate",
                    JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) scope.launch(Dispatchers.Main) { _state.update { it.copy(remoteVideo = track) } }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch(Dispatchers.Main) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> _state.update {
                            if (it.connectedAt == null) it.copy(phase = CallPhase.CONNECTED, connectedAt = System.currentTimeMillis()) else it
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            emit("Connection failed. A TURN server may be needed on this network.")
                            hangUp()
                        }
                        else -> Unit
                    }
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: error("Could not create PeerConnection")
        audioTrack?.let { peer.addTrack(it, listOf("stream0")) }
        videoTrack?.let { peer.addTrack(it, listOf("stream0")) }
        pc = peer
        return peer
    }

    private fun routeAudio(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            // Prefer a connected Bluetooth / wired headset when not on speaker
            val headset = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            val device = if (!speaker && headset != null) headset else audio.availableCommunicationDevices.firstOrNull { it.type == wanted }
            device?.let { audio.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = speaker
        }
    }

    private fun cleanup() {
        ringer.stop()
        stopForegroundService()
        try { capturer?.stopCapture() } catch (e: InterruptedException) { Log.w(tag, "stopCapture", e) }
        capturer?.dispose(); capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        pc?.close(); pc?.dispose(); pc = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        pendingIce.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
        _state.value = CallUiState()
    }

    private fun startForegroundService() {
        val s = _state.value
        val i = Intent(ctx, CallForegroundService::class.java)
            .putExtra(CallForegroundService.EXTRA_NAME, s.peer?.name ?: "")
            .putExtra(CallForegroundService.EXTRA_VIDEO, s.isVideo)
        runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { Log.w(tag, "FGS start failed", it) }
    }

    private fun stopForegroundService() {
        ctx.stopService(Intent(ctx, CallForegroundService::class.java))
    }

    private fun emit(msg: String) { _messages.tryEmit(msg) }

    private fun parseIce(arr: JSONArray?): List<PeerConnection.IceServer> {
        if (arr == null) return listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val urls = when (val u = o.opt("urls")) {
                is JSONArray -> (0 until u.length()).map { u.getString(it) }
                is String -> listOf(u)
                else -> return@mapNotNull null
            }
            PeerConnection.IceServer.builder(urls).apply {
                if (o.has("username")) setUsername(o.getString("username"))
                if (o.has("credential")) setPassword(o.getString("credential"))
            }.createIceServer()
        }
    }
}

// ------------------------------------------------------------------------------- SDP helpers

private fun SessionDescription.toJson() = JSONObject().put("type", type.canonicalForm()).put("sdp", description)
private fun JSONObject.toSdp() = SessionDescription(SessionDescription.Type.fromCanonicalForm(getString("type")), getString("sdp"))

private suspend fun PeerConnection.createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
    val obs = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
        override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }
    if (offer) createOffer(obs, MediaConstraints()) else createAnswer(obs, MediaConstraints())
}

private suspend fun PeerConnection.setLocal(sdp: SessionDescription) = setSdp(sdp, local = true)
private suspend fun PeerConnection.setRemote(sdp: SessionDescription) = setSdp(sdp, local = false)

private suspend fun PeerConnection.setSdp(sdp: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { cont ->
    val obs = object : SdpObserver {
        override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
        override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}
    }
    if (local) setLocalDescription(obs, sdp) else setRemoteDescription(obs, sdp)
}
