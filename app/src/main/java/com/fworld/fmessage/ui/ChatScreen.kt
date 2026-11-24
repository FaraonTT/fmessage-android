@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.fworld.fmessage.ui

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.data.*
import com.fworld.fmessage.music.Track
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private val EMOJI_SETS = linkedMapOf(
    "Часто" to "😀 😂 🥰 😍 😎 🤔 😴 🥳 😭 😡 👍 👎 👏 🙏 💪 🔥 ❤️ 💔 ✨ 🎉 🎁 🌟 ⭐ 😅 😉 😘 🤗 😱 🙄 😇",
    "Животные" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🦁 🐯 🐮 🐷 🐸 🐵 🦄 🐝 🦋 🐢 🐙",
    "Еда" to "🍎 🍔 🍕 🌭 🍿 🥗 🍝 🍜 🍣 🍰 🍩 🍪 ☕ 🍵 🍷 🍺 🥤 🍦 🧁 🍓",
    "Активности" to "⚽ 🏀 🏈 🎾 🏐 🎱 🎯 🎮 🎲 🎨 🎸 🎹 🎤 🎧 🎬 🏆 🥇 🎪",
    "Символы" to "✅ ❌ ⚠️ 🚫 ❓ ❗ ➡️ ⬅️ ⬆️ ⬇️ 🔄 🔁 🆗 🆕 💬 💭 🔔 📌",
)

private sealed interface Entry {
    data class Day(val ms: Long) : Entry
    data class M(val m: Rec, val grouped: Boolean) : Entry
}

@Composable
fun ChatScreen(vm: AppVM, chatId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val userState by vm.user.collectAsState()
    val chats by vm.chats.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val messages by vm.messages.collectAsState()

    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Rec?>(null) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var actionMsg by remember { mutableStateOf<Rec?>(null) }
    var forwardMsg by remember { mutableStateOf<Rec?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var showPoll by remember { mutableStateOf(false) }
    var showMusic by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Rec?>(null) }
    var viewPhoto by remember { mutableStateOf<String?>(null) }
    var viewVideo by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceRecorder(ctx) }
    val listState = rememberLazyListState()

    val me = userState
    val chat = chats.firstOrNull { it.id == chatId }
    if (me == null || chat == null) { Box(Modifier.fillMaxSize()); return }
    val cv = chatView(chat, me, profiles)
    val isAdmin = me.s("role") == "admin" || me.b("is_owner")
    val h24 = me.s("time_format") != "12h"

    fun send(content: String) { vm.sendMessage(chat, content, replyTo); replyTo = null }

    // ---------- media pickers ----------
    fun sendPicked(uri: Uri?, kind: String) {
        if (uri == null) return
        scope.launch {
            uploading = true
            val f = readUri(ctx, uri)
            if (f != null) {
                val url = vm.uploadFile(f.bytes, f.name, f.mime, kind == "video")
                if (url != null) send(when (kind) {
                    "photo" -> Msg.encode(Msg.PHOTO, obj("photo_url" to url, "filename" to f.name))
                    "video" -> Msg.encode(Msg.VIDEO, obj("video_url" to url, "filename" to f.name))
                    else -> Msg.encode(Msg.FILE, obj("filename" to f.name, "file_url" to url, "file_type" to f.mime, "size" to f.bytes.size))
                })
            }
            uploading = false
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { sendPicked(it, "photo") }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { sendPicked(it, "video") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { sendPicked(it, "file") }
    val cameraShot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) scope.launch {
            uploading = true
            val url = vm.uploadFile(bitmapToJpeg(bmp), "camera_${System.currentTimeMillis()}.jpg", "image/jpeg")
            if (url != null) send(Msg.encode(Msg.PHOTO, obj("photo_url" to url, "filename" to "camera.jpg")))
            uploading = false
        }
    }
    var pendingPerm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) pendingPerm?.invoke() else vm.toast("Нет разрешения")
        pendingPerm = null
    }
    fun withPerms(perms: Array<String>, action: () -> Unit) {
        val missing = perms.filter { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pendingPerm = action; permLauncher.launch(missing.toTypedArray()) }
    }

    fun shareLocation() = withPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) {
        try {
            val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null } }
                .maxByOrNull { it.time }
            if (loc == null) vm.toast("Не удалось определить местоположение")
            else send(Msg.encode(Msg.LOCATION, obj("lat" to loc.latitude, "lng" to loc.longitude, "name" to "Моя локация")))
        } catch (e: Exception) { vm.toast("Геолокация недоступна") }
    }

    fun startVoice() = withPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) {
        if (recorder.start()) { recording = true; recSeconds = 0 } else vm.toast("Не удалось начать запись")
    }
    fun stopVoice(keep: Boolean) {
        recording = false
        val r = recorder.stop(keep) ?: return
        scope.launch {
            uploading = true
            val url = vm.uploadFile(r.first.readBytes(), r.first.name, "audio/mp4")
            if (url != null) send(Msg.encode(Msg.VOICE, obj("audio_url" to url, "duration" to r.second)))
            r.first.delete()
            uploading = false
        }
    }
    LaunchedEffect(recording) {
        val max = Plans.limits(me).voiceMessageDuration
        while (recording) {
            kotlinx.coroutines.delay(1000)
            recSeconds++
            if (recSeconds >= max) { stopVoice(true); break }
        }
    }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.stop(false) } }

    fun call(video: Boolean) {
        val other = cv.other ?: return
        withPerms(if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)) {
            vm.calls.startCall(chat.id, other.s("user_id"), cv.name, video)
        }
    }

    // ---------- send ----------
    fun handleSend() {
        val text = input.trim()
        if (text.isEmpty()) return
        val ed = editing
        if (ed != null) { vm.editMessage(ed, text); editing = null; input = ""; return }
        input = ""
        if (text.startsWith("@music ", ignoreCase = true)) {
            val q = text.drop(7).trim()
            if (q.isNotEmpty()) scope.launch {
                val t = vm.music.search(q, 1).firstOrNull()
                send(t?.toMessage() ?: "🎵 Музыка не найдена: «$q»")
            }
            return
        }
        send(text)
    }

    // ---------- list ----------
    val groupingOn = me.b("message_grouping", true)
    val entries = remember(messages, groupingOn) {
        val out = mutableListOf<Entry>()
        var prev: Rec? = null
        var prevMs = 0L
        for (m in messages) {
            val ms = parseIso(m.s("created_date"))
            if (prev == null || !sameDay(prevMs, ms)) out.add(Entry.Day(ms))
            val grouped = groupingOn && prev != null && prev.s("sender_id") == m.s("sender_id") && sameDay(prevMs, ms) && ms - prevMs < 5 * 60_000L
            out.add(Entry.M(m, grouped))
            prev = m; prevMs = ms
        }
        out
    }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex) }

    val wallpaper = me.s("custom_wallpaper_color")
    val maxFrac = (me.i("bubble_max_width", 78).takeIf { it in 50..95 } ?: 78) / 100f
    val compact = me.s("message_density") == "compact"

    Column(
        Modifier.fillMaxSize().background(if (wallpaper.isNotBlank()) colorOf(wallpaper).copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.background) else MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding(),
    ) {
        // header
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Ico(R.drawable.ic_back) }
                Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { showProfile = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(cv.name, cv.color, cv.avatarUrl, 40.dp, me.s("avatar_shape").ifBlank { "circle" }, online = cv.online)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(cv.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (cv.isGroup) "${participantIds(chat).size} участников" else lastSeenText(cv.other),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
                if (!cv.isGroup && cv.other != null) {
                    IconButton(onClick = { call(false) }) { Ico(R.drawable.ic_phone) }
                    IconButton(onClick = { call(true) }) { Ico(R.drawable.ic_video) }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Ico(R.drawable.ic_more) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(if (chat.b("muted")) "Включить звук" else "Без звука") }, leadingIcon = { Ico(R.drawable.ic_bell_off) }, onClick = { vm.toggleMute(chat); showMenu = false })
                        DropdownMenuItem(text = { Text(if (chat.b("archived")) "Из архива" else "В архив") }, leadingIcon = { Ico(R.drawable.ic_archive) }, onClick = { vm.toggleArchive(chat); showMenu = false })
                        if (isAdmin) DropdownMenuItem(text = { Text("Удалить чат") }, leadingIcon = { Ico(R.drawable.ic_trash) }, onClick = { showMenu = false; vm.deleteChat(chat); onBack() })
                    }
                }
            }
        }

        // messages
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            if (entries.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("Напишите первое сообщение 👋", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(entries, key = { e -> when (e) { is Entry.Day -> "d${e.ms}"; is Entry.M -> e.m.id } }) { e ->
                when (e) {
                    is Entry.Day -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(fmtDay(e.ms), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is Entry.M -> MessageBubble(
                        m = e.m, me = me, grouped = e.grouped, showName = cv.isGroup, h24 = h24, maxFrac = maxFrac, compact = compact,
                        onLong = { actionMsg = e.m },
                        onDouble = { if (me.b("double_tap_to_like", true)) vm.toggleReaction(e.m, "❤️") },
                        onReaction = { vm.toggleReaction(e.m, it) },
                        onPhoto = { viewPhoto = it }, onVideo = { viewVideo = it },
                        onVote = { idx -> votePoll(vm, me, e.m, idx) },
                        onPlayTrack = { t -> vm.music.play(t) },
                    )
                }
            }
        }

        if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth())

        // reply / edit banner
        AnimatedVisibility(visible = replyTo != null || editing != null) {
            val m = replyTo ?: editing
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Редактирование" else "Ответ ${m?.s("sender_name").orEmpty()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(Msg.preview(m?.s("content").orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    }
                    IconButton(onClick = { replyTo = null; if (editing != null) { editing = null; input = "" } }) { Ico(R.drawable.ic_close, size = 20.dp) }
                }
            }
        }

        // emoji
        AnimatedVisibility(visible = showEmoji) {
            var cat by remember { mutableStateOf(EMOJI_SETS.keys.first()) }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.height(260.dp)) {
                    LazyRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(EMOJI_SETS.keys.toList()) { k -> FilterChip(selected = cat == k, onClick = { cat = k }, label = { Text(k) }) }
                    }
                    LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        gridItems(EMOJI_SETS[cat].orEmpty().split(" ")) { em ->
                            Box(Modifier.size(44.dp).clickable { input += em }, contentAlignment = Alignment.Center) { Text(em, fontSize = 26.sp) }
                        }
                    }
                }
            }
        }

        // input
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            if (recording) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { stopVoice(false) }) { Ico(R.drawable.ic_trash, tint = MaterialTheme.colorScheme.error) }
                    Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(fmtDuration(recSeconds), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    FilledIconButton(onClick = { stopVoice(true) }) { Ico(R.drawable.ic_send, tint = MaterialTheme.colorScheme.onPrimary) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { showAttach = true }) { Ico(R.drawable.ic_attach) }
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Сообщение") }, maxLines = 5, shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent,
                        ),
                        trailingIcon = { IconButton(onClick = { showEmoji = !showEmoji }) { Ico(R.drawable.ic_smile) } },
                    )
                    Spacer(Modifier.width(4.dp))
                    if (input.isBlank() && editing == null) {
                        FilledTonalIconButton(onClick = { startVoice() }) { Ico(R.drawable.ic_mic) }
                    } else {
                        FilledIconButton(onClick = { handleSend() }) { Ico(if (editing != null) R.drawable.ic_check else R.drawable.ic_send, tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }
        }
    }

    // ---------- sheets & dialogs ----------
    if (showAttach) {
        ModalBottomSheet(onDismissRequest = { showAttach = false }) {
            data class A(val icon: Int, val label: String, val color: Color, val run: () -> Unit)
            val items = listOf(
                A(R.drawable.ic_image, "Фото", Color(0xFF22C55E)) { photoPicker.launch("image/*") },
                A(R.drawable.ic_camera, "Камера", Color(0xFF14B8A6)) { withPerms(arrayOf(Manifest.permission.CAMERA)) { cameraShot.launch(null) } },
                A(R.drawable.ic_video, "Видео", Color(0xFF0EA5E9)) { videoPicker.launch("video/*") },
                A(R.drawable.ic_file, "Файл", Color(0xFF3B82F6)) { filePicker.launch("*/*") },
                A(R.drawable.ic_music, "Музыка", Color(0xFFA855F7)) { showMusic = true },
                A(R.drawable.ic_map_pin, "Локация", Color(0xFFEF4444)) { shareLocation() },
                A(R.drawable.ic_poll, "Опрос", Color(0xFFF59E0B)) { showPoll = true },
                A(R.drawable.ic_user, "Контакт", Color(0xFF6366F1)) { showContact = true },
                A(R.drawable.ic_mic, "Голос", Color(0xFFF43F5E)) { startVoice() },
            )
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.padding(16.dp).heightIn(max = 360.dp)) {
                gridItems(items) { a ->
                    Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable { showAttach = false; a.run() }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).background(a.color, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Ico(a.icon, tint = Color.White) }
                        Spacer(Modifier.height(6.dp))
                        Text(a.label, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    actionMsg?.let { m ->
        val own = m.s("sender_id") == me.id
        val isText = Msg.type(m.s("content")) == "text"
        ModalBottomSheet(onDismissRequest = { actionMsg = null }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                REACTIONS.forEach { e ->
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { vm.toggleReaction(m, e); actionMsg = null }, contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            ActionRow(R.drawable.ic_reply, "Ответить") { actionMsg = null; replyTo = m; editing = null }
            if (isText) ActionRow(R.drawable.ic_copy, "Копировать") { actionMsg = null; clipboard.setText(AnnotatedString(m.s("content"))) }
            ActionRow(R.drawable.ic_forward, "Переслать") { actionMsg = null; forwardMsg = m }
            if (own && isText) ActionRow(R.drawable.ic_edit, "Изменить") { actionMsg = null; editing = m; replyTo = null; input = m.s("content") }
            ActionRow(R.drawable.ic_star, if (starredBy(m).contains(me.id)) "Убрать из избранного" else "В избранное") { actionMsg = null; vm.toggleStar(m) }
            if (own || isAdmin) ActionRow(R.drawable.ic_trash, "Удалить") {
                actionMsg = null
                if (me.b("confirm_before_delete", true)) confirmDelete = m else vm.deleteMessage(m)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirmDelete?.let { m -> ConfirmDialog("Удалить сообщение?", "Это действие нельзя отменить.", "Удалить", onDismiss = { confirmDelete = null }) { vm.deleteMessage(m) } }

    forwardMsg?.let { m ->
        ModalBottomSheet(onDismissRequest = { forwardMsg = null }) {
            Text("Переслать в…", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(vm.myChats().map { chatView(it, me, profiles) }, key = { it.id }) { t ->
                    ListItem(
                        leadingContent = { Avatar(t.name, t.color, t.avatarUrl, 44.dp) },
                        headlineContent = { Text(t.name) },
                        modifier = Modifier.clickable {
                            val go = { vm.forward(m, t.chat) { vm.toast("Переслано") }; forwardMsg = null }
                            go()
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPoll) PollDialog(onDismiss = { showPoll = false }) { q, opts ->
        send(Msg.encode(Msg.POLL, obj("question" to q, "options" to opts.map { obj("text" to it, "votes" to emptyList<String>()) })))
    }
    if (showMusic) MusicShareDialog(vm, onDismiss = { showMusic = false }) { t -> send(t.toMessage()) }
    if (showContact) {
        ModalBottomSheet(onDismissRequest = { showContact = false }) {
            Text("Поделиться контактом", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(profiles.filter { it.s("user_id") != me.id }, key = { it.id }) { p ->
                    ListItem(
                        leadingContent = { Avatar(p.displayName(), p.s("avatar_color"), p.s("avatar_url"), 44.dp) },
                        headlineContent = { Text(p.displayName()) },
                        supportingContent = { Text(p.s("email")) },
                        modifier = Modifier.clickable {
                            showContact = false
                            send(Msg.encode(Msg.CONTACT, obj("name" to p.displayName(), "email" to p.s("email"), "avatar_color" to p.s("avatar_color").ifBlank { "#6366f1" }, "user_id" to p.s("user_id"))))
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showProfile) {
        if (cv.isGroup) GroupInfoSheet(chat, profiles, me) { showProfile = false }
        else cv.other?.let { UserProfileSheet(it, vm, onDismiss = { showProfile = false }) } ?: run { showProfile = false }
    }
    viewPhoto?.let { url ->
        Dialog(onDismissRequest = { viewPhoto = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewPhoto = null }) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                IconButton(onClick = { viewPhoto = null }, Modifier.align(Alignment.TopEnd).padding(16.dp).systemBarsPadding()) { Ico(R.drawable.ic_close, tint = Color.White) }
            }
        }
    }
    viewVideo?.let { url ->
        Dialog(onDismissRequest = { viewVideo = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setMediaController(MediaController(c).also { it.setAnchorView(this) })
                            setVideoURI(Uri.parse(url), mediaHeaders(url))
                            setOnPreparedListener { it.start() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(onClick = { viewVideo = null }, Modifier.align(Alignment.TopEnd).padding(16.dp).systemBarsPadding()) { Ico(R.drawable.ic_close, tint = Color.White) }
            }
        }
    }
}

private fun votePoll(vm: AppVM, me: Rec, m: Rec, idx: Int) {
    val poll = Msg.decode(m.s("content"), Msg.POLL) ?: return
    val opts = (poll["options"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return
    val newOpts = opts.mapIndexed { i, o ->
        val votes = ((o["votes"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()).filter { it != me.id }
        obj("text" to o.s("text"), "votes" to (if (i == idx) votes + me.id else votes))
    }
    vm.updateMessageContent(m, Msg.encode(Msg.POLL, obj("question" to poll.s("question"), "options" to newOpts)))
}

@Composable
private fun MessageBubble(
    m: Rec, me: Rec, grouped: Boolean, showName: Boolean, h24: Boolean, maxFrac: Float, compact: Boolean,
    onLong: () -> Unit, onDouble: () -> Unit, onReaction: (String) -> Unit,
    onPhoto: (String) -> Unit, onVideo: (String) -> Unit, onVote: (Int) -> Unit, onPlayTrack: (Track) -> Unit,
) {
    val own = m.s("sender_id") == me.id
    val scheme = MaterialTheme.colorScheme
    val bg = if (own) scheme.primaryContainer else scheme.surfaceContainerHigh
    val fg = if (own) scheme.onPrimaryContainer else scheme.onSurface
    val style = me.s("bubble_style")
    val r = when (style) { "sharp" -> 6.dp; "round" -> 26.dp; else -> 20.dp }
    val shape = if (own) RoundedCornerShape(r, r, 6.dp, r) else RoundedCornerShape(r, r, r, 6.dp)
    val content = m.s("content")
    val type = Msg.type(content)
    val reactions = reactionsOf(m)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth().padding(top = if (grouped) 2.dp else if (compact) 4.dp else 8.dp),
        horizontalAlignment = if (own) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = bg, contentColor = fg, shape = shape,
            modifier = Modifier.widthIn(max = (LocalConfigWidth() * maxFrac).dp).clip(shape)
                .combinedClickable(onClick = {}, onLongClick = onLong, onDoubleClick = onDouble),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showName && !own && !grouped && me.b("show_sender_names", true)) {
                    Text(m.s("sender_name"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                }
                if (m.s("forwarded_from").isNotBlank()) Text("↪ Переслано от ${m.s("forwarded_from")}", fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = fg.copy(alpha = 0.7f))
                if (m.s("reply_to_content").isNotBlank()) {
                    Row(Modifier.padding(bottom = 4.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.primary, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            if (m.s("reply_to_name").isNotBlank()) Text(m.s("reply_to_name"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                            Text(m.s("reply_to_content"), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = fg.copy(alpha = 0.75f))
                        }
                    }
                }
                when (type) {
                    "photo" -> {
                        val url = Msg.decode(content, Msg.PHOTO)?.s("photo_url").orEmpty()
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 240.dp, height = 240.dp).clip(RoundedCornerShape(14.dp)).clickable { onPhoto(url) })
                    }
                    "video" -> {
                        val url = Msg.decode(content, Msg.VIDEO)?.s("video_url").orEmpty()
                        Box(Modifier.size(width = 240.dp, height = 150.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable { onVideo(url) }, contentAlignment = Alignment.Center) {
                            Ico(R.drawable.ic_play, tint = Color.White, size = 48.dp)
                        }
                    }
                    "voice" -> {
                        val v = Msg.decode(content, Msg.VOICE)
                        VoiceBubble(v?.s("audio_url").orEmpty(), v?.i("duration") ?: 0)
                    }
                    "file" -> {
                        val f = Msg.decode(content, Msg.FILE)
                        Row(Modifier.clickable {
                            scope.launch {
                                val file = downloadFile(ctx, f?.s("file_url").orEmpty(), f?.s("filename").orEmpty().ifBlank { "file" })
                                android.widget.Toast.makeText(ctx, if (file != null) "Сохранено: ${file.absolutePath}" else "Не удалось скачать", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Ico(R.drawable.ic_file, size = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(f?.s("filename").orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val sz = f?.i("size") ?: 0
                                if (sz > 0) Text(if (sz > 1_048_576) "%.1f МБ".format(sz / 1_048_576f) else "${sz / 1024 + 1} КБ", fontSize = 12.sp, color = fg.copy(alpha = 0.7f))
                            }
                        }
                    }
                    "location" -> {
                        val l = Msg.decode(content, Msg.LOCATION)
                        val lat = (l?.get("lat") as? JsonPrimitive)?.content.orEmpty()
                        val lng = (l?.get("lng") as? JsonPrimitive)?.content.orEmpty()
                        Row(Modifier.clickable {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Ico(R.drawable.ic_map_pin, size = 32.dp, tint = Color(0xFFEF4444))
                            Spacer(Modifier.width(8.dp))
                            Column { Text(l?.s("name").orEmpty().ifBlank { "Локация" }, fontWeight = FontWeight.SemiBold); Text("$lat, $lng", fontSize = 12.sp, color = fg.copy(alpha = 0.7f)) }
                        }
                    }
                    "poll" -> {
                        val poll = Msg.decode(content, Msg.POLL)
                        val opts = (poll?.get("options") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                        val votes = opts.map { o -> (o["votes"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList() }
                        val total = votes.sumOf { it.size }
                        Column(Modifier.widthIn(min = 220.dp)) {
                            Text("📊 ${poll?.s("question").orEmpty()}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            opts.forEachIndexed { i, o ->
                                val n = votes[i].size
                                val mine = votes[i].contains(me.id)
                                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface.copy(alpha = 0.5f)).clickable { onVote(i) }) {
                                    Box(Modifier.fillMaxWidth(if (total == 0) 0f else n / total.toFloat()).height(36.dp).background(scheme.primary.copy(alpha = 0.25f)))
                                    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (mine) Ico(R.drawable.ic_check, size = 16.dp)
                                        Text(o.s("text"), Modifier.weight(1f).padding(start = if (mine) 4.dp else 0.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("$n", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Text("Голосов: $total", fontSize = 11.sp, color = fg.copy(alpha = 0.7f))
                        }
                    }
                    "contact" -> {
                        val c = Msg.decode(content, Msg.CONTACT)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c?.s("name").orEmpty(), c?.s("avatar_color").orEmpty(), "", 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column { Text(c?.s("name").orEmpty(), fontWeight = FontWeight.SemiBold); Text(c?.s("email").orEmpty(), fontSize = 12.sp, color = fg.copy(alpha = 0.7f)) }
                        }
                    }
                    "music" -> {
                        val d = Msg.decode(content, Msg.MUSIC)
                        val t = d?.let { Track.fromRec(it) }
                        Row(Modifier.clickable { t?.let(onPlayTrack) }, verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = d?.s("artwork").orEmpty(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.widthIn(max = 160.dp)) {
                                Text(d?.s("title").orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(d?.s("artist").orEmpty(), fontSize = 12.sp, maxLines = 1, color = fg.copy(alpha = 0.7f))
                            }
                            Spacer(Modifier.width(6.dp))
                            Ico(R.drawable.ic_play, size = 28.dp)
                        }
                    }
                    else -> Text(content)
                }
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m.b("edited")) Text("изм. ", fontSize = 10.sp, color = fg.copy(alpha = 0.6f))
                    if (me.b("show_timestamps", true)) Text(fmtClock(parseIso(m.s("created_date")), h24), fontSize = 10.sp, color = fg.copy(alpha = 0.6f))
                    if (own) {
                        Spacer(Modifier.width(3.dp))
                        Ico(if (m.b("is_read")) R.drawable.ic_check_double else R.drawable.ic_check, size = 14.dp, tint = if (m.b("is_read")) scheme.primary else fg.copy(alpha = 0.6f))
                    }
                }
            }
        }
        if (reactions.isNotEmpty()) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                reactions.forEach { (e, users) ->
                    val mine = users.contains(me.id)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (mine) scheme.primaryContainer else scheme.surfaceContainerHighest,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onReaction(e) },
                    ) { Text("$e ${users.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun LocalConfigWidth(): Int = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp

@Composable
private fun ActionRow(icon: Int, label: String, run: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, leadingContent = { Ico(icon) }, modifier = Modifier.clickable { run() })
}

@Composable
private fun VoiceBubble(url: String, duration: Int) {
    val ctx = LocalContext.current
    val player = remember { VoicePlayer(ctx) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { player.onDone = { playing = false }; onDispose { player.release() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { if (playing) { player.pause(); playing = false } else { playing = true; player.play(url) } }) {
            Ico(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        Spacer(Modifier.width(8.dp))
        Icon(painterResourceMic(), null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(fmtDuration(duration), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun painterResourceMic() = androidx.compose.ui.res.painterResource(R.drawable.ic_mic)

@Composable
private fun PollDialog(onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var q by remember { mutableStateOf("") }
    var opts by remember { mutableStateOf(listOf("", "")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Создать опрос") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Вопрос") }, singleLine = true)
                opts.forEachIndexed { i, o ->
                    OutlinedTextField(value = o, onValueChange = { v -> opts = opts.mapIndexed { j, x -> if (j == i) v else x } }, label = { Text("Вариант ${i + 1}") }, singleLine = true)
                }
                if (opts.size < 8) TextButton(onClick = { opts = opts + "" }) { Text("+ Вариант") }
            }
        },
        confirmButton = {
            TextButton(enabled = q.isNotBlank() && opts.count { it.isNotBlank() } >= 2, onClick = { onCreate(q.trim(), opts.map { it.trim() }.filter { it.isNotEmpty() }); onDismiss() }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun MusicShareDialog(vm: AppVM, onDismiss: () -> Unit, onPick: (Track) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(vm.music.saved.value) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.8f).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Найти трек") }, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Ico(R.drawable.ic_search) },
                trailingIcon = { IconButton(onClick = { if (q.isNotBlank()) scope.launch { busy = true; results = vm.music.search(q); busy = false } }) { Ico(R.drawable.ic_check) } },
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn {
                items(results, key = { it.id }) { t ->
                    ListItem(
                        leadingContent = { AsyncImage(model = t.artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))) },
                        headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(t.artist, maxLines = 1) },
                        modifier = Modifier.clickable { onPick(t); onDismiss() },
                    )
                }
            }
        }
    }
}
