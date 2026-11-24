@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.fworld.fmessage.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.data.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private val AVATAR_COLORS = listOf("#6366f1", "#8b5cf6", "#ec4899", "#ef4444", "#f97316", "#eab308", "#22c55e", "#14b8a6", "#0ea5e9", "#64748b")
private val EMOJI_STATUSES = listOf("", "😊", "😎", "🔥", "💻", "🎧", "📚", "🌙", "✈️", "🏖️", "🎮", "❤️", "🚀", "☕", "🤝")
private val PLATFORMS = listOf("Instagram", "Twitter", "Telegram", "YouTube", "TikTok", "GitHub", "Website")

@Composable
fun ProfileScreen(vm: AppVM) {
    val user by vm.user.collectAsState()
    val me = user ?: return
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val limits = Plans.limits(me)
    val tier = Plans.tier(me)

    var name by remember(me.id) { mutableStateOf(me.s("display_name").ifBlank { me.s("full_name") }) }
    var username by remember(me.id) { mutableStateOf(me.s("username")) }
    var bio by remember(me.id) { mutableStateOf(me.s("bio")) }
    var status by remember(me.id) { mutableStateOf(me.s("status")) }
    var pronouns by remember(me.id) { mutableStateOf(me.s("pronouns")) }
    var links by remember(me.s("social_links")) {
        mutableStateOf(me.jsonArr("social_links").mapNotNull { it as? JsonObject }.map { it.s("label") to it.s("url") })
    }
    var newLabel by remember { mutableStateOf(PLATFORMS[0]) }
    var newUrl by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var showPro by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    fun pick(kind: String, uri: Uri?) {
        if (uri == null) return
        scope.launch {
            uploading = true
            val f = readUri(ctx, uri)
            if (f != null) {
                val url = vm.uploadFile(f.bytes, f.name, f.mime)
                if (url != null) vm.saveProfile((if (kind == "avatar") "avatar_url" else "banner_url") to url)
            }
            uploading = false
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { pick("avatar", it) }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { pick("banner", it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(colorOf(me.s("profile_theme")).copy(alpha = 0.5f)).clickable { bannerPicker.launch("image/*") }) {
            if (me.s("banner_url").isNotBlank()) AsyncImage(model = me.s("banner_url"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.TopEnd).padding(12.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.4f)) {
                Ico(R.drawable.ic_camera, Modifier.padding(8.dp), tint = Color.White, size = 20.dp)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp).offset(y = (-40).dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.clickable { avatarPicker.launch("image/*") }) {
                Avatar(name.ifBlank { me.s("email") }, me.s("avatar_color").ifBlank { "#6366f1" }, me.s("avatar_url"), 92.dp, me.s("avatar_shape").ifBlank { "circle" })
                Surface(Modifier.align(Alignment.BottomEnd), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Ico(R.drawable.ic_camera, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.onPrimary, size = 16.dp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.padding(bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name.ifBlank { "Без имени" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (me.s("emoji_status").isNotBlank()) Text(" ${me.s("emoji_status")}", fontSize = 20.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (username.isNotBlank()) Text("@$username", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TierChip(tier)
                    CustomBadgeChip(me)
                }
            }
        }
        if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth().offset(y = (-30).dp))

        Column(Modifier.padding(horizontal = 20.dp).offset(y = (-24).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // subscription card
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().clickable { showPro = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(R.drawable.ic_crown, size = 32.dp, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Подписка: ${Plans.label(tier)}", fontWeight = FontWeight.Bold)
                        val exp = Subscription.expires(me)
                        Text(
                            if (tier == "free") "fPro и fPremium: больше закреплений, треков, участников групп" else if (exp.isNotBlank()) "Активна до ${fmtChatTime(exp)}" else "Активна",
                            fontSize = 13.sp,
                        )
                    }
                    Ico(R.drawable.ic_chevron_right)
                }
            }

            OutlinedTextField(value = name, onValueChange = { name = it; saved = false }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = username, onValueChange = { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }; saved = false },
                label = { Text("Юзернейм") }, prefix = { Text("@") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = status, onValueChange = { status = it.take(80); saved = false }, label = { Text("Статус") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pronouns, onValueChange = { pronouns = it.take(30); saved = false }, label = { Text("Местоимения") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = bio, onValueChange = { bio = it.take(limits.bioLength); saved = false }, label = { Text("О себе") },
                supportingText = { Text("${bio.length}/${Plans.inf(limits.bioLength)}") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )

            Text("Цвет аватара", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AVATAR_COLORS.forEach { c ->
                    Box(
                        Modifier.size(38.dp).background(colorOf(c), CircleShape).clickable { vm.saveProfile("avatar_color" to c, "profile_theme" to c) },
                        contentAlignment = Alignment.Center,
                    ) { if (me.s("avatar_color").equals(c, true)) Ico(R.drawable.ic_check, tint = Color.White, size = 20.dp) }
                }
            }
            Text("Форма аватара", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("circle" to "Круг", "squircle" to "Сквиркл", "square" to "Квадрат").forEach { (k, l) ->
                    FilterChip(selected = me.s("avatar_shape").ifBlank { "circle" } == k, onClick = { vm.saveProfile("avatar_shape" to k) }, label = { Text(l) })
                }
            }
            Text("Эмодзи-статус", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EMOJI_STATUSES.forEach { e ->
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(if (me.s("emoji_status") == e) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { vm.saveProfile("emoji_status" to e) },
                        contentAlignment = Alignment.Center,
                    ) { if (e.isEmpty()) Ico(R.drawable.ic_close, size = 18.dp) else Text(e, fontSize = 22.sp) }
                }
            }

            Text("Социальные ссылки (${links.size}/${Plans.inf(limits.socialLinks)})", style = MaterialTheme.typography.labelLarge)
            links.forEachIndexed { i, (l, u) ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(l, fontWeight = FontWeight.SemiBold); Text(u, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        IconButton(onClick = { links = links.filterIndexed { j, _ -> j != i }; saved = false }) { Ico(R.drawable.ic_trash, size = 20.dp) }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PLATFORMS.forEach { p -> FilterChip(selected = newLabel == p, onClick = { newLabel = p }, label = { Text(p) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text("Ссылка") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = {
                    if (newUrl.isBlank()) return@FilledTonalIconButton
                    if (links.size >= limits.socialLinks) { vm.toast("Лимит ссылок. Активируйте fPro/fPremium"); return@FilledTonalIconButton }
                    links = links + (newLabel to newUrl.trim()); newUrl = ""; saved = false
                }) { Ico(R.drawable.ic_plus) }
            }

            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    val handle = username.trim()
                    vm.saveProfile(
                        "display_name" to name.trim(), "username" to handle, "bio" to bio.trim(), "status" to status.trim(), "pronouns" to pronouns.trim(),
                        "social_links" to toJson(links.map { obj("label" to it.first, "url" to it.second) }).toString(),
                    ) { ok -> saved = ok }
                },
            ) { Text(if (saved) "Сохранено ✓" else "Сохранить профиль") }

            OutlinedButton(onClick = { showPassword = true }, modifier = Modifier.fillMaxWidth()) { Text("Сменить пароль") }
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) {
                Ico(R.drawable.ic_logout, size = 20.dp); Spacer(Modifier.width(8.dp)); Text("Выйти из аккаунта")
            }
        }
    }
    if (showPro) FProSheet(vm, onDismiss = { showPro = false })
    if (showPassword) PasswordDialog(vm) { showPassword = false }
}

@Composable
private fun PasswordDialog(vm: AppVM, onDismiss: () -> Unit) {
    var cur by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Смена пароля") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = cur, onValueChange = { cur = it }, label = { Text("Текущий пароль") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(value = new, onValueChange = { new = it }, label = { Text("Новый пароль") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { vm.changePassword(cur, new) { e -> if (e == null) { vm.toast("Пароль изменён"); onDismiss() } else err = e } }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun FProSheet(vm: AppVM, onDismiss: () -> Unit) {
    val user by vm.user.collectAsState()
    val me = user ?: return
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tier = Plans.tier(me)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("fPro и fPremium", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            listOf("fpro" to Plans.PRO, "fpremium" to Plans.PREMIUM).forEach { (t, l) ->
                val c = if (t == "fpremium") Color(0xFFA855F7) else Color(0xFFF59E0B)
                Surface(shape = MaterialTheme.shapes.large, color = c.copy(alpha = 0.14f)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ico(R.drawable.ic_crown, tint = c); Spacer(Modifier.width(8.dp))
                            Text("${Plans.label(t)} — ${Plans.price(t)} ₽/мес", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (tier == t) Text("Ваш план", color = c, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Закреплённых чатов: ${Plans.inf(l.pinnedChats)}\nТреков: ${Plans.inf(l.savedTracks)}\nУчастников группы: ${Plans.inf(l.groupMembers)}\nГолосовое: до ${l.voiceMessageDuration / 60} мин", fontSize = 13.sp)
                    }
                }
            }
            OutlinedTextField(
                value = code, onValueChange = { code = it.uppercase(); error = "" }, label = { Text("Промокод") }, singleLine = true,
                isError = error.isNotBlank(), supportingText = { if (error.isNotBlank()) Text(error) }, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = code.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val r = Subscription.redeemPromo(code, me)
                            vm.refreshUser(); vm.user.value?.let { vm.syncProfile(it) }
                            vm.toast("${Plans.label(r.tier)} активирован на ${r.days} дн.")
                            onDismiss()
                        } catch (e: ApiException) { error = e.message ?: "Неверный промокод" }
                        busy = false
                    }
                },
            ) { Text("Активировать") }
            if (tier != "free" && me.s("subscription_status") == "active") {
                TextButton(onClick = { scope.launch { try { Subscription.cancel(); vm.refreshUser() } catch (e: ApiException) {} } }) { Text("Отменить продление") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun UserProfileSheet(p: Rec, vm: AppVM, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (p.s("banner_url").isNotBlank()) AsyncImage(model = p.s("banner_url"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(12.dp))
            Avatar(p.displayName(), p.s("avatar_color"), p.s("avatar_url"), 88.dp, online = profileOnline(p))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.displayName(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (p.s("emoji_status").isNotBlank()) Text(" ${p.s("emoji_status")}", fontSize = 20.sp)
            }
            if (p.s("username").isNotBlank()) Text("@${p.s("username")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                TierChip(if (p.b("is_premium")) "fpremium" else if (p.b("is_pro")) "fpro" else "free")
                CustomBadgeChip(p)
            }
            Text(lastSeenText(p), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (p.s("status").isNotBlank()) Text(p.s("status"), Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp), fontWeight = FontWeight.Medium)
            if (p.s("pronouns").isNotBlank()) Text(p.s("pronouns"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.s("bio").isNotBlank()) Surface(Modifier.padding(16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(p.s("bio"), Modifier.padding(16.dp))
            }
            p.jsonArr("social_links").mapNotNull { it as? JsonObject }.forEach { l ->
                AssistChip(
                    onClick = {
                        val u = l.s("url").let { if (it.startsWith("http")) it else "https://$it" }
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                    },
                    label = { Text("${l.s("label")}: ${l.s("url")}", maxLines = 1) }, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
fun GroupInfoSheet(chat: Rec, profiles: List<Rec>, me: Rec, onDismiss: () -> Unit) {
    val ids = participantIds(chat)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.7f)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(chat.s("name"), chat.s("avatar_color"), chat.s("avatar_url"), 56.dp)
                Spacer(Modifier.width(12.dp))
                Column { Text(chat.s("name"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${ids.size} участников", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            LazyColumn {
                items(ids, key = { it }) { uid ->
                    val p = profiles.firstOrNull { it.s("user_id") == uid }
                    val info = chat.jsonObj("participant_info")[uid] as? JsonObject
                    val name = p?.displayName() ?: info?.s("name").orEmpty().ifBlank { "Пользователь" }
                    ListItem(
                        leadingContent = { Avatar(name, p?.s("avatar_color") ?: info?.s("color").orEmpty(), p?.s("avatar_url").orEmpty(), 44.dp, online = profileOnline(p)) },
                        headlineContent = { Text(name + if (uid == me.id) " (вы)" else "") },
                        supportingContent = { Text(if (uid == chat.s("group_admin_id")) "Администратор" else lastSeenText(p)) },
                    )
                }
            }
        }
    }
}
