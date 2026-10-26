package com.situationship.app.ui.call

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situationship.app.call.CallManager
import com.situationship.app.call.CallPhase
import com.situationship.app.ui.RootEntryPoint
import com.situationship.app.ui.components.Avatar
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Full-screen call UI drawn above the whole app while a call is ringing or active. */
@Composable
fun CallOverlay() {
    val ctx = LocalContext.current
    val calls: CallManager = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, RootEntryPoint::class.java).calls() }
    val s by calls.state.collectAsStateWithLifecycle()
    if (!s.active) return

    // Camera/mic permission is requested only when the user accepts
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) calls.accept() else calls.reject()
    }
    val acceptWithPermissions = {
        val perms = if (s.isVideo) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        permissions.launch(perms)
    }
    // User already tapped "Accept" on the notification
    LaunchedEffect(s.callId, s.autoAcceptCallId) {
        if (s.phase == CallPhase.INCOMING && s.autoAcceptCallId != null && s.autoAcceptCallId == s.callId) acceptWithPermissions()
    }
    BackHandler { /* stay on the call screen; use the red button to end */ }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.connectedAt) { while (s.connectedAt != null) { now = System.currentTimeMillis(); delay(1000) } }

    val connectedVideo = s.isVideo && s.phase == CallPhase.CONNECTED
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0A0C)).pointerInput(Unit) { detectTapGestures { } }) { // swallow touches
        if (s.isVideo) {
            s.remoteVideo?.let { VideoView(it, calls.eglBase, mirror = false, overlay = false, modifier = Modifier.fillMaxSize()) }
            if (s.localVideo != null && !s.cameraOff) {
                VideoView(
                    s.localVideo!!, calls.eglBase, mirror = s.frontCamera, overlay = true,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                        .size(width = 110.dp, height = 150.dp).clip(RoundedCornerShape(16.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                )
            }
        }

        val status = when (s.phase) {
            CallPhase.OUTGOING -> "Calling…"
            CallPhase.INCOMING -> if (s.isVideo) "Incoming video call…" else "Incoming voice call…"
            CallPhase.CONNECTING -> "Connecting…"
            CallPhase.CONNECTED -> s.connectedAt?.let { elapsed(now - it) } ?: ""
            CallPhase.IDLE -> ""
        }
        if (connectedVideo) {
            Column(Modifier.statusBarsPadding().padding(20.dp)) {
                Text(s.peer?.name.orEmpty(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(status, color = Color.White.copy(alpha = 0.85f))
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(s.peer, 120.dp, showOnline = false)
                Spacer(Modifier.height(18.dp))
                Text(s.peer?.name.orEmpty(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(status, color = Color.White.copy(alpha = 0.8f))
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (s.phase == CallPhase.INCOMING) {
                CallButton(Icons.Filled.CallEnd, "Decline", Color(0xFFEF4444)) { calls.reject() }
                CallButton(if (s.isVideo) Icons.Filled.Videocam else Icons.Filled.Call, "Accept", Color(0xFF22C55E)) { acceptWithPermissions() }
            } else {
                CallButton(if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute", if (s.muted) Color.White else Color.White.copy(alpha = 0.18f),
                    tint = if (s.muted) Color.Black else Color.White) { calls.toggleMute() }
                if (s.isVideo) {
                    CallButton(if (s.cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam, "Camera",
                        if (s.cameraOff) Color.White else Color.White.copy(alpha = 0.18f), tint = if (s.cameraOff) Color.Black else Color.White) { calls.toggleCamera() }
                    CallButton(Icons.Filled.Cameraswitch, "Flip", Color.White.copy(alpha = 0.18f)) { calls.switchCamera() }
                } else {
                    CallButton(Icons.AutoMirrored.Filled.VolumeUp, "Speaker", if (s.speakerOn) Color.White else Color.White.copy(alpha = 0.18f),
                        tint = if (s.speakerOn) Color.Black else Color.White) { calls.toggleSpeaker() }
                }
                CallButton(Icons.Filled.CallEnd, "End", Color(0xFFEF4444)) { calls.hangUp() }
            }
        }
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, bg: Color, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(28.dp)) }
}

/** Renders a WebRTC video track with a SurfaceViewRenderer. */
@Composable
private fun VideoView(track: VideoTrack, egl: EglBase, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(ctx).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            if (overlay) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    DisposableEffect(track, renderer) {
        track.addSink(renderer)
        onDispose { runCatching { track.removeSink(renderer) } }
    }
    AndroidView(factory = { renderer }, update = { it.setMirror(mirror) }, modifier = modifier)
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
