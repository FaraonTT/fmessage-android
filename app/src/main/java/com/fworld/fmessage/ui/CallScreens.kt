@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.data.b
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
private fun VideoRenderer(track: VideoTrack?, egl: EglBase, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(overlay)
                view = this
            }
        },
        onRelease = { it.release() },
        modifier = modifier,
    )
    val v = view
    DisposableEffect(track, v) {
        if (track != null && v != null) track.addSink(v)
        onDispose { if (track != null && v != null) try { track.removeSink(v) } catch (ignored: Exception) {} }
    }
}

@Composable
fun CallOverlay(vm: AppVM) {
    val st by vm.calls.state.collectAsState()
    val remote by vm.calls.remoteTrack.collectAsState()
    val local by vm.calls.localTrack.collectAsState()
    val me by vm.user.collectAsState()
    var pendingAccept by remember { mutableStateOf(false) }

    LaunchedEffect(st.error) { if (st.error.isNotBlank()) vm.toast(st.error) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) vm.calls.accept() else { vm.toast("Нет разрешения на микрофон/камеру"); vm.calls.reject() }
        pendingAccept = false
    }

    if (st.status == "idle") return

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.status) { while (st.status == "active") { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val seconds = if (st.status == "active" && st.startedAt > 0) ((now - st.startedAt) / 1000).toInt().coerceAtLeast(0) else 0

    Box(Modifier.fillMaxSize().background(Color(0xFF0F1020))) {
        if (st.isVideo && remote != null) VideoRenderer(remote, vm.calls.eglBase, false, false, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            if (!(st.isVideo && remote != null)) Avatar(st.otherName, "#6366f1", "", 120.dp)
            Spacer(Modifier.height(16.dp))
            Text(st.otherName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                when (st.status) {
                    "ringing_in" -> if (st.isVideo) "Входящий видеозвонок" else "Входящий звонок"
                    "ringing_out" -> "Вызов…"
                    "connecting" -> "Соединение…"
                    else -> fmtDuration(seconds)
                },
                color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp,
            )
            Spacer(Modifier.weight(1f))
            if (st.status == "ringing_in") {
                Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    LargeFloatingActionButton(onClick = { vm.calls.reject() }, containerColor = Color(0xFFEF4444)) { Ico(R.drawable.ic_phone_off, tint = Color.White, size = 32.dp) }
                    LargeFloatingActionButton(
                        onClick = {
                            pendingAccept = true
                            perms.launch(if (st.isVideo) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                        containerColor = Color(0xFF22C55E),
                    ) { Ico(R.drawable.ic_phone, tint = Color.White, size = 32.dp) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { vm.calls.toggleMute() }, modifier = Modifier.size(56.dp)) { Ico(if (st.muted) R.drawable.ic_mic_off else R.drawable.ic_mic) }
                    if (st.isVideo) {
                        FilledTonalIconButton(onClick = { vm.calls.toggleCamera() }, modifier = Modifier.size(56.dp)) { Ico(if (st.cameraOff) R.drawable.ic_video_off else R.drawable.ic_video) }
                        FilledTonalIconButton(onClick = { vm.calls.switchCamera() }, modifier = Modifier.size(56.dp)) { Ico(R.drawable.ic_swap_cam) }
                    }
                    FloatingActionButton(onClick = { vm.calls.end() }, containerColor = Color(0xFFEF4444), shape = CircleShape) { Ico(R.drawable.ic_phone_off, tint = Color.White) }
                }
            }
        }
        if (st.isVideo && local != null) {
            VideoRenderer(local, vm.calls.eglBase, me?.b("video_call_mirror", true) != false, true,
                Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(16.dp).size(width = 110.dp, height = 150.dp).clip(RoundedCornerShape(16.dp)))
        }
    }
}
