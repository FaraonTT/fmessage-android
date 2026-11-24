@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.data.*
import kotlinx.coroutines.launch

private class AdminData(
    val users: List<Rec>, val chats: List<Rec>, val messages: List<Rec>,
    val calls: List<Rec>, val profiles: List<Rec>, val promos: List<Rec>,
)

private suspend fun mirrorProfile(userId: String, data: Rec) {
    val ps = E.Profile.filter(obj("user_id" to userId))
    ps.firstOrNull()?.let { E.Profile.update(it.id, data) }
}

@Composable
fun AdminScreen(vm: AppVM, owner: Boolean) {
    val me by vm.user.collectAsState()
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AdminData?>(null) }
    var tab by remember { mutableStateOf("overview") }
    var loading by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var badgeUser by remember { mutableStateOf<Rec?>(null) }

    fun load() {
        scope.launch {
            loading = true
            try {
                data = AdminData(
                    users = try { E.User.list() } catch (e: Exception) { emptyList() },
                    chats = E.Chat.list("-updated_date", 200),
                    messages = try { E.Message.list("-created_date", 300) } catch (e: Exception) { emptyList() },
                    calls = try { E.Call.list("-created_date", 100) } catch (e: Exception) { emptyList() },
                    profiles = try { E.Profile.list() } catch (e: Exception) { emptyList() },
                    promos = try { E.PromoCode.list("-created_date", 100) } catch (e: Exception) { emptyList() },
                )
            } catch (e: ApiException) { vm.toast(e.message ?: "Ошибка загрузки") }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    fun op(block: suspend () -> Unit) {
        scope.launch { try { block(); load() } catch (e: ApiException) { vm.toast(e.message ?: "Ошибка") } }
    }

    val tabs = buildList {
        add("overview" to "Обзор"); add("users" to "Пользователи"); add("chats" to "Чаты"); add("promos" to "Промокоды")
        if (owner) add("badges" to "Бейджи")
        add("system" to "Система")
        if (owner) add("danger" to "Опасная зона")
    }

    Column(Modifier.fillMaxSize()) {
        Text(if (owner) "Панель владельца" else "Админ-панель", Modifier.padding(start = 20.dp, top = 12.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { (k, l) -> FilterChip(selected = tab == k, onClick = { tab = k }, label = { Text(l) }) }
            IconButton(onClick = { load() }) { Ico(R.drawable.ic_swap_cam) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val d = data
        if (d == null) { Box(Modifier.fillMaxSize()) {} } else when (tab) {
            "overview" -> LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val stat = listOf(
                    "Пользователей" to d.users.size, "Чатов" to d.chats.size, "Сообщений (посл.)" to d.messages.size, "Звонков" to d.calls.size,
                    "Админов" to d.users.count { it.s("role") == "admin" }, "fPro" to d.users.count { it.b("is_pro") },
                    "fPremium" to d.users.count { it.b("is_premium") }, "Промокодов" to d.promos.size,
                )
                items(stat.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (l, v) ->
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Column(Modifier.padding(16.dp)) { Text("$v", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(l, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            "users" -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                items(d.users, key = { it.id }) { u ->
                    var menu by remember { mutableStateOf(false) }
                    val isMe = u.id == me?.id
                    ListItem(
                        leadingContent = { Avatar(u.displayName(), u.s("avatar_color"), u.s("avatar_url"), 44.dp) },
                        headlineContent = { Text(u.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(u.s("email"), fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (u.b("is_owner")) AssistChip(onClick = {}, label = { Text("owner", fontSize = 10.sp) })
                                    if (u.s("role") == "admin") AssistChip(onClick = {}, label = { Text("admin", fontSize = 10.sp) })
                                    TierChip(Plans.tier(u))
                                }
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_more) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (u.s("role") == "admin") DropdownMenuItem(text = { Text("Снять админа") }, onClick = { menu = false; op { E.User.update(u.id, obj("role" to "user")); mirrorProfile(u.id, obj("role" to "user")) } })
                                    else DropdownMenuItem(text = { Text("Сделать админом") }, onClick = { menu = false; op { E.User.update(u.id, obj("role" to "admin")); mirrorProfile(u.id, obj("role" to "admin")) } })
                                    DropdownMenuItem(text = { Text(if (u.b("is_pro")) "Забрать fPro" else "Выдать fPro (год)") }, onClick = {
                                        menu = false
                                        op {
                                            val nw = !u.b("is_pro")
                                            val up = if (nw) obj("is_pro" to true, "subscription_tier" to "fpro", "subscription_status" to "active", "pro_expires_date" to java.time.Instant.now().plusSeconds(365L * 86400).toString())
                                            else obj("is_pro" to false, "is_premium" to false, "subscription_tier" to "free")
                                            E.User.update(u.id, up); mirrorProfile(u.id, obj("is_pro" to nw, "is_premium" to (nw && u.b("is_premium"))))
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(if (u.b("is_premium")) "Забрать fPremium" else "Выдать fPremium (год)") }, onClick = {
                                        menu = false
                                        op {
                                            val nw = !u.b("is_premium")
                                            val up = if (nw) obj("is_premium" to true, "is_pro" to true, "subscription_tier" to "fpremium", "subscription_status" to "active", "premium_expires_date" to java.time.Instant.now().plusSeconds(365L * 86400).toString())
                                            else obj("is_premium" to false, "subscription_tier" to (if (u.b("is_pro")) "fpro" else "free"))
                                            E.User.update(u.id, up); mirrorProfile(u.id, obj("is_premium" to nw, "is_pro" to (nw || u.b("is_pro"))))
                                        }
                                    })
                                    if (owner) DropdownMenuItem(text = { Text(if (u.b("is_owner")) "Снять владельца" else "Сделать владельцем") }, onClick = { menu = false; op { E.User.update(u.id, obj("is_owner" to !u.b("is_owner"))) } })
                                    if (!isMe) DropdownMenuItem(text = { Text("Удалить") }, onClick = {
                                        menu = false
                                        confirm = "Удалить пользователя ${u.s("email")}?" to {
                                            op { E.Profile.filter(obj("user_id" to u.id)).forEach { E.Profile.delete(it.id) }; E.User.delete(u.id) }
                                        }
                                    })
                                }
                            }
                        },
                    )
                }
            }
            "chats" -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                items(d.chats, key = { it.id }) { c ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(c.s("name")) },
                        supportingContent = { Text((if (c.b("is_group")) "Группа · " else "") + c.s("last_message").ifBlank { "нет сообщений" }, maxLines = 1) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_more) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Очистить сообщения") }, onClick = { menu = false; confirm = "Очистить чат «${c.s("name")}»?" to { op { E.Message.deleteMany(obj("chat_id" to c.id)) } } })
                                    DropdownMenuItem(text = { Text("Удалить чат") }, onClick = { menu = false; confirm = "Удалить чат «${c.s("name")}»?" to { op { E.Message.deleteMany(obj("chat_id" to c.id)); E.Chat.delete(c.id) } } })
                                }
                            }
                        },
                    )
                }
            }
            "promos" -> PromoTab(d.promos, ::op)
            "badges" -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                items(d.users, key = { it.id }) { u ->
                    ListItem(
                        modifier = Modifier.clickable { badgeUser = u },
                        leadingContent = { Avatar(u.displayName(), u.s("avatar_color"), u.s("avatar_url"), 44.dp) },
                        headlineContent = { Text(u.displayName()) },
                        supportingContent = { if (u.s("custom_badge").isBlank()) Text("Бейдж не задан") else CustomBadgeChip(u) },
                    )
                }
            }
            "system" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var days by remember { mutableStateOf("30") }
                Text("Очистка старых сообщений", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = days, onValueChange = { days = it.filter(Char::isDigit) }, label = { Text("Старше (дней)") }, singleLine = true)
                Button(enabled = days.isNotBlank(), onClick = {
                    val cutoff = java.time.Instant.now().minusSeconds(days.toLong() * 86400).toString()
                    confirm = "Удалить сообщения старше $days дн.?" to { op { E.Message.deleteMany(obj("created_date" to obj("\$lt" to cutoff))) } }
                }) { Text("Очистить") }
                Text("Репозиторий данных: ${Prefs.ghRepoFull}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "danger" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Необратимые действия", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                    confirm = "Удалить ВСЕ сообщения?" to { op { d.chats.forEach { E.Message.deleteMany(obj("chat_id" to it.id)) } } }
                }) { Text("Удалить все сообщения") }
                Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                    confirm = "Удалить ВСЕ чаты и сообщения?" to { op { d.chats.forEach { E.Message.deleteMany(obj("chat_id" to it.id)); E.Chat.delete(it.id) } } }
                }) { Text("Удалить все чаты") }
                Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                    confirm = "Удалить все промокоды?" to { op { d.promos.forEach { E.PromoCode.delete(it.id) } } }
                }) { Text("Удалить все промокоды") }
            }
        }
    }

    confirm?.let { (text, action) -> ConfirmDialog("Подтвердите действие", text, "Да", onDismiss = { confirm = null }) { action() } }
    badgeUser?.let { u -> BadgeDialog(u, onDismiss = { badgeUser = null }) { data -> op { E.User.update(u.id, data); mirrorProfile(u.id, data) } } }
}

@Composable
private fun PromoTab(promos: List<Rec>, op: (suspend () -> Unit) -> Unit) {
    var tier by remember { mutableStateOf("fpro") }
    var days by remember { mutableStateOf("30") }
    var uses by remember { mutableStateOf("1") }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Новый промокод", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tier == "fpro", onClick = { tier = "fpro" }, label = { Text("fPro") })
                        FilterChip(selected = tier == "fpremium", onClick = { tier = "fpremium" }, label = { Text("fPremium") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = days, onValueChange = { days = it.filter(Char::isDigit) }, label = { Text("Дней") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = uses, onValueChange = { uses = it.filter(Char::isDigit) }, label = { Text("Использований") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Button(enabled = days.isNotBlank() && uses.isNotBlank(), onClick = {
                        op { E.PromoCode.create(obj("code" to Subscription.generatePromoCode(), "tier" to tier, "duration_days" to days.toInt(), "max_uses" to uses.toInt(), "used_count" to 0, "used_by" to "")) }
                    }) { Text("Создать") }
                }
            }
        }
        items(promos, key = { it.id }) { p ->
            ListItem(
                headlineContent = { Text(p.s("code"), fontWeight = FontWeight.Bold) },
                supportingContent = { Text("${Plans.label(p.s("tier"))} · ${p.i("duration_days")} дн. · ${p.i("used_count")}/${p.i("max_uses")}") },
                trailingContent = { IconButton(onClick = { op { E.PromoCode.delete(p.id) } }) { Ico(R.drawable.ic_trash, tint = MaterialTheme.colorScheme.error) } },
            )
        }
    }
}

@Composable
private fun BadgeDialog(u: Rec, onDismiss: () -> Unit, onSave: (Rec) -> Unit) {
    var text by remember { mutableStateOf(u.s("custom_badge")) }
    var desc by remember { mutableStateOf(u.s("custom_badge_description")) }
    var icon by remember { mutableStateOf(u.s("custom_badge_icon")) }
    var color by remember { mutableStateOf(u.s("custom_badge_color").ifBlank { "#6366f1" }) }
    var shape by remember { mutableStateOf(u.s("custom_badge_shape").ifBlank { "squircle" }) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Бейдж: ${u.displayName()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(20) }, label = { Text("Текст") }, singleLine = true)
                OutlinedTextField(value = icon, onValueChange = { icon = it.take(2) }, label = { Text("Эмодзи") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Описание") }, singleLine = true)
                OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Цвет (#hex)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("squircle", "circle", "square").forEach { s -> FilterChip(selected = shape == s, onClick = { shape = s }, label = { Text(s) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(obj("custom_badge" to text.trim(), "custom_badge_description" to desc, "custom_badge_icon" to icon, "custom_badge_color" to color, "custom_badge_shape" to shape)); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
