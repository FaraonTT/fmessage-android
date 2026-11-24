package com.fworld.fmessage.call

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.fworld.fmessage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CallUi(
    val status: String = "idle", // idle | ringing_out | ringing_in | connecting | active
    val isVideo: Boolean = false,
    val isCaller: Boolean = false,
    val otherName: String = "",
    val muted: Boolean = false,
    val cameraOff: Boolean = false,
    val callId: String = "",
    val startedAt: Long = 0L,
    val error: String = "",
)

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

class CallManager(private val app: Application, private val scope: CoroutineScope) {
    val state = MutableStateFlow(CallUi())
    val localTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteTrack = MutableStateFlow<VideoTrack?>(null)
    val eglBase: EglBase by lazy { EglBase.create() }

    private var me: Rec? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var callId = ""
    private var isCaller = false
    private var incoming: Rec? = null
    private var remoteDescSet = false
    private var processedRemoteIce = 0
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private val myIce = mutableListOf<JsonObject>()
    private val iceSignal = Channel<Unit>(Channel.CONFLATED)
    private var iceJob: Job? = null
    private var ringTimeout: Job? = null
    private var ringtone: Ringtone? = null
    private var ending = false

    fun attach(u: Rec) { me = u }
    fun detach() { me = null; cleanup() }

    // ---------- events ----------

    fun onEvent(ev: RtEvent) {
        val u = me ?: return
        val d = ev.data
        if (ev.type == "create" && d.s("receiver_id") == u.id && d.s("status") == "ringing") {
            if (state.value.status != "idle") {
                scope.launch { try { E.Call.update(d.id, obj("status" to "rejected")) } catch (ignored: Exception) {} }
                return
            }
            incoming = d
            state.value = CallUi(status = "ringing_in", isVideo = d.b("is_video"), otherName = d.s("caller_name"), callId = d.id)
            Realtime.fast = true
            startRinging(u)
            if (u.b("auto_answer_calls")) scope.launch { delay(600); accept() }
            return
        }
        if (d.id != callId && d.id != incoming?.id) return
        if (ev.type == "delete") { finish(); return }
        val status = d.s("status")
        if (status == "ended" || status == "rejected") {
            if (state.value.status != "idle") finish()
            return
        }
        if (d.id == callId && isCaller) {
            if (!remoteDescSet && status == "accepted" && d.s("answer_sdp").isNotBlank()) {
                scope.launch { applyAnswer(d.s("answer_sdp")) }
            }
            if (d.s("receiver_ice").isNotBlank()) addRemoteIce(d.s("receiver_ice"))
        } else if (!isCaller && d.id == callId && d.s("caller_ice").isNotBlank()) {
            addRemoteIce(d.s("caller_ice"))
        }
    }

    // ---------- outgoing ----------

    fun startCall(chatId: String, otherId: String, otherName: String, video: Boolean) {
        val u = me ?: return
        if (state.value.status != "idle") return
        ending = false
        Realtime.fast = true
        isCaller = true
        state.value = CallUi(status = "ringing_out", isVideo = video, isCaller = true, otherName = otherName)
        scope.launch {
            try {
                val call = E.Call.create(obj(
                    "chat_id" to chatId,
                    "caller_id" to u.id,
                    "caller_name" to u.displayName(),
                    "receiver_id" to otherId,
                    "receiver_name" to otherName,
                    "status" to "ringing",
                    "is_video" to video,
                ))
                callId = call.id
                state.value = state.value.copy(callId = callId)
                setupPeer(video, true)
                val offer = createOffer(video)
                setLocal(offer)
                E.Call.update(callId, obj("offer_sdp" to sdpJson(offer)))
                ringTimeout = scope.launch { delay(45_000); if (state.value.status == "ringing_out") end() }
            } catch (e: Exception) {
                fail(e.message ?: "Не удалось начать звонок")
            }
        }
    }

    private suspend fun applyAnswer(json: String) {
        try {
            val o = AppJson.parseToJsonElement(json) as JsonObject
            setRemote(SessionDescription(SessionDescription.Type.ANSWER, o.s("sdp")))
            remoteDescSet = true
            flushRemoteIce()
            state.value = state.value.copy(status = "connecting")
            ringTimeout?.cancel()
        } catch (e: Exception) { fail("Ошибка соединения") }
    }

    // ---------- incoming ----------

    fun accept() {
        val inc = incoming ?: return
        if (state.value.status != "ringing_in") return
        stopRinging()
        ending = false
        Realtime.fast = true
        isCaller = false
        callId = inc.id
        state.value = state.value.copy(status = "connecting")
        scope.launch {
            try {
                val full = E.Call.get(inc.id)
                val video = full.b("is_video")
                setupPeer(video, false)
                val offerJson = AppJson.parseToJsonElement(full.s("offer_sdp")) as JsonObject
                setRemote(SessionDescription(SessionDescription.Type.OFFER, offerJson.s("sdp")))
                remoteDescSet = true
                if (full.s("caller_ice").isNotBlank()) addRemoteIce(full.s("caller_ice"))
                flushRemoteIce()
                val answer = createAnswer()
                setLocal(answer)
                E.Call.update(callId, obj("answer_sdp" to sdpJson(answer), "status" to "accepted"))
                state.value = state.value.copy(isVideo = video)
            } catch (e: Exception) {
                fail("Не удалось принять звонок")
                try { E.Call.update(inc.id, obj("status" to "ended")) } catch (ignored: Exception) {}
            }
        }
    }

    fun reject() {
        val inc = incoming ?: return
        scope.launch { try { E.Call.update(inc.id, obj("status" to "rejected")) } catch (ignored: Exception) {} }
        finish()
    }

    fun end() {
        val id = callId.ifBlank { incoming?.id.orEmpty() }
        if (id.isNotBlank()) scope.launch { try { E.Call.update(id, obj("status" to "ended")) } catch (ignored: Exception) {} }
        finish()
    }

    // ---------- controls ----------

    fun toggleMute() {
        val m = !state.value.muted
        audioTrack?.setEnabled(!m)
        state.value = state.value.copy(muted = m)
    }

    fun toggleCamera() {
        val off = !state.value.cameraOff
        videoTrack?.setEnabled(!off)
        state.value = state.value.copy(cameraOff = off)
    }

    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    // ---------- WebRTC plumbing ----------

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    private fun setupPeer(video: Boolean, caller: Boolean) {
        val f = ensureFactory()
        remoteDescSet = false
        processedRemoteIce = 0
        pendingRemoteIce.clear()
        myIce.clear()

        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = video

        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        )
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val side = if (caller) "caller" else "receiver"
        val peer = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        if (state.value.status != "active") state.value = state.value.copy(status = "active", startedAt = System.currentTimeMillis())
                    }
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> {
                        if (state.value.status != "idle") scope.launch(Dispatchers.Main) { end() }
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                synchronized(myIce) {
                    myIce.add(obj("candidate" to c.sdp, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex))
                }
                iceSignal.trySend(Unit)
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val t = receiver?.track()
                if (t is VideoTrack) remoteTrack.value = t
            }
        }) ?: throw IllegalStateException("PeerConnection")
        pc = peer

        val audioSrc = f.createAudioSource(MediaConstraints())
        audioSource = audioSrc
        val at = f.createAudioTrack("audio0", audioSrc)
        audioTrack = at
        peer.addTrack(at, listOf("stream0"))

        if (video) {
            val enumerator = Camera2Enumerator(app)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                val cap = enumerator.createCapturer(name, null)
                capturer = cap
                val helper = SurfaceTextureHelper.create("capture", eglBase.eglBaseContext)
                surfaceHelper = helper
                val vs = f.createVideoSource(false)
                videoSource = vs
                cap.initialize(helper, app, vs.capturerObserver)
                val hd = me?.s("video_call_quality") == "hd"
                cap.startCapture(if (hd) 1280 else 640, if (hd) 720 else 480, 30)
                val vt = f.createVideoTrack("video0", vs)
                videoTrack = vt
                peer.addTrack(vt, listOf("stream0"))
                localTrack.value = vt
            }
        }

        iceJob?.cancel()
        iceJob = scope.launch(Dispatchers.IO) {
            for (u in iceSignal) {
                val id = callId
                if (id.isBlank()) continue
                val arr = synchronized(myIce) { JsonArray(myIce.toList()) }
                try { E.Call.update(id, obj("${side}_ice" to arr.toString())) } catch (ignored: Exception) {}
                delay(150)
            }
        }
    }

    private fun sdpJson(d: SessionDescription): String =
        obj("type" to d.type.canonicalForm(), "sdp" to d.description).toString()

    private suspend fun createOffer(video: Boolean): SessionDescription = suspendCancellableCoroutine { c ->
        val mc = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
        }
        pc!!.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) { if (d != null) c.resume(d) else c.resumeWithException(Exception("offer")) }
            override fun onCreateFailure(e: String?) { c.resumeWithException(Exception(e ?: "offer")) }
        }, mc)
    }

    private suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { c ->
        pc!!.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) { if (d != null) c.resume(d) else c.resumeWithException(Exception("answer")) }
            override fun onCreateFailure(e: String?) { c.resumeWithException(Exception(e ?: "answer")) }
        }, MediaConstraints())
    }

    private suspend fun setLocal(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
        pc!!.setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() { c.resume(Unit) }
            override fun onSetFailure(e: String?) { c.resumeWithException(Exception(e ?: "setLocal")) }
        }, d)
    }

    private suspend fun setRemote(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
        pc!!.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() { c.resume(Unit) }
            override fun onSetFailure(e: String?) { c.resumeWithException(Exception(e ?: "setRemote")) }
        }, d)
    }

    private fun addRemoteIce(json: String) {
        try {
            val arr = AppJson.parseToJsonElement(json) as? JsonArray ?: return
            val items = arr.drop(processedRemoteIce).mapNotNull { it as? JsonObject }
            processedRemoteIce = arr.size
            items.forEach { o ->
                val idx = (o["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0
                val cand = IceCandidate(o.s("sdpMid"), idx, o.s("candidate"))
                if (remoteDescSet && pc != null) pc?.addIceCandidate(cand) else pendingRemoteIce.add(cand)
            }
        } catch (ignored: Exception) {}
    }

    private fun flushRemoteIce() {
        val p = pc ?: return
        pendingRemoteIce.forEach { p.addIceCandidate(it) }
        pendingRemoteIce.clear()
    }

    // ---------- lifecycle ----------

    private fun fail(msg: String) {
        Realtime.fast = false
        cleanup()
        state.value = CallUi(error = msg)
    }

    private fun finish() {
        Realtime.fast = false
        cleanup()
        state.value = CallUi()
    }

    private fun cleanup() {
        if (ending) return
        ending = true
        stopRinging()
        ringTimeout?.cancel()
        iceJob?.cancel()
        try { (capturer as? CameraVideoCapturer)?.stopCapture() } catch (ignored: Exception) {}
        try { capturer?.dispose() } catch (ignored: Exception) {}
        try { surfaceHelper?.dispose() } catch (ignored: Exception) {}
        try { videoTrack?.dispose() } catch (ignored: Exception) {}
        try { videoSource?.dispose() } catch (ignored: Exception) {}
        try { audioTrack?.dispose() } catch (ignored: Exception) {}
        try { audioSource?.dispose() } catch (ignored: Exception) {}
        try { pc?.close() } catch (ignored: Exception) {}
        capturer = null; surfaceHelper = null; videoTrack = null; videoSource = null
        audioTrack = null; audioSource = null; pc = null
        localTrack.value = null
        remoteTrack.value = null
        callId = ""
        incoming = null
        isCaller = false
        remoteDescSet = false
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (ignored: Exception) {}
    }

    private fun startRinging(u: Rec) {
        try {
            if (u.b("call_ringtone", true)) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(app, uri)?.also { it.play() }
            }
            if (u.b("vibrate_on_call", true)) {
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val pattern = longArrayOf(0, 400, 300, 400, 300, 400, 1500)
                if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                else @Suppress("DEPRECATION") v?.vibrate(pattern, 0)
            }
        } catch (ignored: Exception) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (ignored: Exception) {}
        ringtone = null
        try { (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() } catch (ignored: Exception) {}
    }
}
