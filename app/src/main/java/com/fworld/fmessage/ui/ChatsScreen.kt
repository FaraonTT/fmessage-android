@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.fworld.fmessage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun ChatsScreen(vm: AppVM, onOpenChat: (String) -> Unit) {
    val user by vm.user.collectAsState()
    val chats by vm.chats.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val loading by vm.chatsLoading.collectAsState()
    val scope = rememberCoroutineScope()

    var search by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var showCalls by remember { mutableStateOf(false) }
    var msgResults by remember { mutableStateOf<List<Rec>>(emptyList()) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val me = user
    val h24 = me?.s("time_format") != "12h"
    val my = remember(chats, me) { vm.myChats() }
    val views = remember(my, me, profiles) { my.map { chatView(it, me, profiles) } }
    val q = search.trim().lowercase()
    val byAlpha = me?.s("chat_sort_order") == "pinned_alpha"
    val cmp = Comparator<ChatView> { a, b ->
        if (a.chat.b("pinned") != b.chat.b("pinned")) if (a.chat.b("pinned")) -1 else 1
        else if (byAlpha) a.name.compareTo(b.name)
        else parseIso(b.chat.s("last_message_time").ifBlank { b.chat.s("updated_date") })
            .compareTo(parseIso(a.chat.s("last_message_time").ifBlank { a.chat.s("updated_date") }))
    }
    val active = views.filter { !it.chat.b("archived") && it.name.lowercase().contains(q) }.sortedWith(cmp)
    val archived = views.filter { it.chat.b("archived") && it.name.lowercase().contains(q) }.sortedWith(cmp)

    LaunchedEffect(search, loading) {
        if (search.isBlank() || loading) { msgResults = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        msgResults = vm.searchMessages(search.trim())
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Чаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showCalls = true }) { Ico(R.drawable.ic_phone) }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true, placeholder = { Text("Поиск чатов и сообщений") },
                leadingIcon = { Ico(R.drawable.ic_search) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Ico(R.drawable.ic_close) } },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (msgResults.isNotEmpty()) {
                        item { SectionTitle("Сообщения") }
                        items(msgResults, key = { "m" + it.id }) { m ->
                            val cv = views.firstOrNull { it.id == m.s("chat_id") }
                            ListItem(
                                headlineContent = { Text(cv?.name ?: "Чат", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("${m.s("sender_name")}: ${m.s("content")}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Avatar(cv?.name ?: "?", cv?.color ?: "#6366f1", cv?.avatarUrl ?: "", 44.dp) },
                                modifier = Modifier.clickable { onOpenChat(m.s("chat_id")) },
                            )
                        }
                        item { SectionTitle("Чаты") }
                    }
                    if (active.isEmpty() && archived.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Ico(R.drawable.ic_chat, size = 64.dp, tint = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(12.dp))
                                Text(if (search.isBlank()) "Пока нет чатов" else "Ничего не найдено", style = MaterialTheme.typography.titleMedium)
                                if (search.isBlank()) Text("Нажмите «+», чтобы начать общение", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(active, key = { it.id }) { cv ->
                        ChatRow(cv, me, h24, menuFor == cv.id, onMenu = { menuFor = if (it) cv.id else null }, vm = vm, onClick = { onOpenChat(cv.id) })
                    }
                    if (archived.isNotEmpty()) {
                        item {
                            TextButton(onClick = { showArchive = !showArchive }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Ico(R.drawable.ic_archive, size = 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Архив (${archived.size})")
                            }
                        }
                        if (showArchive) items(archived, key = { "a" + it.id }) { cv ->
                            ChatRow(cv, me, h24, menuFor == cv.id, onMenu = { menuFor = if (it) cv.id else null }, vm = vm, onClick = { onOpenChat(cv.id) })
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) { Ico(R.drawable.ic_plus) }
    }

    if (showNew) NewChatSheet(vm, onDismiss = { showNew = false }, onOpen = { showNew = false; onOpenChat(it) })
    if (showCalls) CallHistorySheet(vm, onDismiss = { showCalls = false })
}

@Composable
private fun ChatRow(cv: ChatView, me: Rec?, h24: Boolean, menuOpen: Boolean, onMenu: (Boolean) -> Unit, vm: AppVM, onClick: () -> Unit) {
    val c = cv.chat
    val unread = c.i("unread_count")
    val showBadge = me?.b("show_unread_badge", true) != false
    val showPreview = me?.b("show_chat_preview", true) != false
    val compact = me?.s("chat_list_density") == "compact"
    Box {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { onMenu(true) }).padding(vertical = if (compact) 0.dp else 2.dp),
            leadingContent = {
                Box {
                    Avatar(cv.name, cv.color, cv.avatarUrl, if (compact) 44.dp else 52.dp, me?.s("avatar_shape").orEmpty().ifBlank { "circle" }, online = cv.online && me?.b("show_online_dot", true) != false)
                    if (cv.isGroup) Box(
                        Modifier.align(Alignment.BottomEnd).size(18.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp),
                    ) { Ico(R.drawable.ic_group_filled, tint = MaterialTheme.colorScheme.primary, size = 14.dp) }
                }
            },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cv.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    if (cv.emojiStatus.isNotBlank()) Text(" ${cv.emojiStatus}")
                    if (cv.other != null) {
                        val t = when { cv.other.b("is_premium") -> "fpremium"; cv.other.b("is_pro") -> "fpro"; else -> "free" }
                        if (t != "free") { Spacer(Modifier.width(6.dp)); TierChip(t) }
                    }
                    if (c.b("muted")) { Spacer(Modifier.width(6.dp)); Ico(R.drawable.ic_bell_off, size = 14.dp, tint = MaterialTheme.colorScheme.outline) }
                }
            },
            supportingContent = {
                if (showPreview) Text(
                    c.s("last_message").ifBlank { "Нет сообщений" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(fmtChatTime(c.s("last_message_time").ifBlank { c.s("updated_date") }, h24), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.b("pinned")) Ico(R.drawable.ic_pin_filled, size = 14.dp, tint = MaterialTheme.colorScheme.outline)
                        if ((unread > 0 || c.b("marked_unread")) && showBadge) {
                            Spacer(Modifier.width(4.dp))
                            Badge(containerColor = if (c.b("muted")) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary) {
                                Text(if (unread > 0) (if (unread > 99) "99+" else unread.toString()) else "•")
                            }
                        }
                    }
                }
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
            DropdownMenuItem(text = { Text(if (c.b("pinned")) "Открепить" else "Закрепить") }, leadingIcon = { Ico(R.drawable.ic_pin) }, onClick = { vm.togglePin(c); onMenu(false) })
            DropdownMenuItem(text = { Text(if (c.b("archived")) "Из архива" else "В архив") }, leadingIcon = { Ico(R.drawable.ic_archive) }, onClick = { vm.toggleArchive(c); onMenu(false) })
            DropdownMenuItem(text = { Text(if (c.b("muted")) "Включить звук" else "Без звука") }, leadingIcon = { Ico(R.drawable.ic_bell_off) }, onClick = { vm.toggleMute(c); onMenu(false) })
            DropdownMenuItem(text = { Text("Пометить непрочитанным") }, leadingIcon = { Ico(R.drawable.ic_check) }, onClick = { vm.markUnread(c); onMenu(false) })
        }
    }
}

@Composable
fun NewChatSheet(vm: AppVM, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val me by vm.user.collectAsState()
    val profiles by vm.profiles.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val list = profiles.filter {
        it.s("user_id") != me?.id &&
            (it.displayName() + " " + it.s("username") + " " + it.s("email")).contains(q.trim(), ignoreCase = true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Личный чат") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Группа") })
            }
            Spacer(Modifier.height(8.dp))
            if (tab == 1) {
                OutlinedTextField(value = groupName, onValueChange = { groupName = it }, label = { Text("Название группы") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = q, onValueChange = { q = it }, placeholder = { Text("Поиск людей") }, singleLine = true,
                leadingIcon = { Ico(R.drawable.ic_search) }, modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { p ->
                    val uid = p.s("user_id")
                    ListItem(
                        modifier = Modifier.clickable {
                            if (tab == 0) vm.startChat(p, onOpen) else selected = if (uid in selected) selected - uid else selected + uid
                        },
                        leadingContent = { Avatar(p.displayName(), p.s("avatar_color"), p.s("avatar_url"), 44.dp, online = profileOnline(p)) },
                        headlineContent = { Text(p.displayName(), fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(if (p.s("username").isNotBlank()) "@${p.s("username")}" else lastSeenText(p)) },
                        trailingContent = { if (tab == 1) Checkbox(checked = uid in selected, onCheckedChange = { selected = if (it) selected + uid else selected - uid }) },
                    )
                }
                if (list.isEmpty()) item { Text("Никого не найдено", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (tab == 1) {
                Button(
                    enabled = groupName.isNotBlank() && selected.isNotEmpty(),
                    onClick = { vm.createGroup(groupName.trim(), selected.toList(), onOpen) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(52.dp),
                ) { Text("Создать группу (${selected.size})") }
            }
        }
    }
}

@Composable
fun CallHistorySheet(vm: AppVM, onDismiss: () -> Unit) {
    val me by vm.user.collectAsState()
    val profiles by vm.profiles.collectAsState()
    var calls by remember { mutableStateOf<List<Rec>?>(null) }
    LaunchedEffect(Unit) {
        calls = try {
            E.Call.list("-created_date", 100).filter { it.s("caller_id") == me?.id || it.s("receiver_id") == me?.id }
        } catch (e: Exception) { emptyList() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.8f)) {
            Text("История звонков", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val list = calls
            if (list == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (list.isEmpty()) Text("Звонков пока нет", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn {
                items(list, key = { it.id }) { c ->
                    val outgoing = c.s("caller_id") == me?.id
                    val otherId = if (outgoing) c.s("receiver_id") else c.s("caller_id")
                    val name = if (outgoing) c.s("receiver_name") else c.s("caller_name")
                    val p = profiles.firstOrNull { it.s("user_id") == otherId }
                    val status = when (c.s("status")) { "rejected" -> "отклонён"; "ringing" -> "без ответа"; "accepted" -> "принят"; else -> "завершён" }
                    ListItem(
                        leadingContent = { Avatar(name, p?.s("avatar_color").orEmpty(), p?.s("avatar_url").orEmpty(), 44.dp) },
                        headlineContent = { Text(name.ifBlank { "Пользователь" }, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text((if (outgoing) "Исходящий" else "Входящий") + (if (c.b("is_video")) " видео" else "") + " · $status · " + fmtChatTime(c.s("created_date"))) },
                        trailingContent = {
                            IconButton(onClick = { onDismiss(); vm.calls.startCall(c.s("chat_id"), otherId, name, c.b("is_video")) }) { Ico(if (c.b("is_video")) R.drawable.ic_video else R.drawable.ic_phone) }
                        },
                    )
                }
            }
        }
    }
}
