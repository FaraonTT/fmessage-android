@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.fworld.fmessage.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.data.*

@Composable
fun ChatsHome(vm: AppVM, onOpenChat: (String) -> Unit) {
    val user by vm.user.collectAsState()
    val chats by vm.chats.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val loading by vm.chatsLoading.collectAsState()

    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var showNew by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var showCalls by remember { mutableStateOf(false) }
    var msgResults by remember { mutableStateOf<List<Rec>>(emptyList()) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val me = user
    val h24 = me?.s("time_format") != "12h"
    val views = remember(chats, me, profiles) { vm.myChats().map { chatView(it, me, profiles) } }
    fun isUnread(c: Rec) = c.i("unread_count") > 0 || c.b("marked_unread")

    val q = search.trim().lowercase()
    val byAlpha = me?.s("chat_sort_order") == "pinned_alpha"
    val cmp = Comparator<ChatView> { a, b ->
        if (byAlpha) a.name.compareTo(b.name)
        else parseIso(b.chat.s("last_message_time").ifBlank { b.chat.s("updated_date") })
            .compareTo(parseIso(a.chat.s("last_message_time").ifBlank { a.chat.s("updated_date") }))
    }
    val filtered = views.filter { cv ->
        cv.name.lowercase().contains(q) && when (filter) {
            "unread" -> isUnread(cv.chat)
            "direct" -> !cv.isGroup
            "groups" -> cv.isGroup
            else -> true
        }
    }
    val active = filtered.filter { !it.chat.b("archived") }.sortedWith(cmp)
    val archived = filtered.filter { it.chat.b("archived") }.sortedWith(cmp)
    val pinned = active.filter { it.chat.b("pinned") }
    val others = active.filter { !it.chat.b("pinned") }
    val unreadCount = views.count { !it.chat.b("archived") && isUnread(it.chat) }

    LaunchedEffect(search, loading) {
        if (search.isBlank() || loading) { msgResults = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        msgResults = vm.searchMessages(search.trim())
    }

    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Чаты", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text(
                    if (unreadCount > 0) "$unreadCount непрочитанных" else "Всё прочитано",
                    fontSize = 13.sp, color = if (unreadCount > 0) scheme.primary else scheme.onSurfaceVariant, fontWeight = FontWeight.Medium,
                )
            }
            IconButton(onClick = { showCalls = true }) { Ico(R.drawable.ic_phone) }
            FilledTonalIconButton(onClick = { showNew = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(46.dp)) { Ico(R.drawable.ic_plus) }
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it }, singleLine = true, placeholder = { Text("Поиск чатов и сообщений") },
            leadingIcon = { Ico(R.drawable.ic_search) },
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Ico(R.drawable.ic_close) } },
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = scheme.surfaceContainerHigh, unfocusedContainerColor = scheme.surfaceContainerHigh,
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = scheme.primary.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "Все", "unread" to "Непрочитанные", "direct" to "Личные", "groups" to "Группы").forEach { (k, l) ->
                FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(l) }, shape = RoundedCornerShape(14.dp))
            }
        }
        Spacer(Modifier.height(6.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(56.dp)) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 190.dp)) {
                if (msgResults.isNotEmpty()) {
                    item { SectionTitle("Сообщения") }
                    items(msgResults, key = { "m" + it.id }) { m ->
                        val cv = views.firstOrNull { it.id == m.s("chat_id") }
                        ListItem(
                            headlineContent = { Text(cv?.name ?: "Чат", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${m.s("sender_name")}: ${m.s("content")}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Avatar(cv?.name ?: "?", cv?.color ?: "#6366f1", cv?.avatarUrl ?: "", 46.dp) },
                            modifier = Modifier.clickable { onOpenChat(m.s("chat_id")) },
                        )
                    }
                    item { SectionTitle("Чаты") }
                }
                if (active.isEmpty() && archived.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(104.dp).clip(RoundedCornerShape(36.dp)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Ico(R.drawable.ic_chat_filled, size = 48.dp, tint = scheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(if (search.isBlank() && filter == "all") "Пока нет чатов" else "Ничего не найдено", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (search.isBlank() && filter == "all") Text("Нажмите «+», чтобы начать общение", color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (pinned.isNotEmpty()) {
                    item(key = "h-pin") { SectionTitle("Закреплённые") }
                    items(pinned, key = { it.id }) { cv ->
                        ChatItem(cv, me, h24, menuFor == cv.id, { menuFor = if (it) cv.id else null }, vm, { onOpenChat(cv.id) }, Modifier.animateItem())
                    }
                    if (others.isNotEmpty()) item(key = "h-all") { SectionTitle("Все чаты") }
                }
                items(others, key = { it.id }) { cv ->
                    ChatItem(cv, me, h24, menuFor == cv.id, { menuFor = if (it) cv.id else null }, vm, { onOpenChat(cv.id) }, Modifier.animateItem())
                }
                if (archived.isNotEmpty()) {
                    item(key = "h-arch") {
                        TextButton(onClick = { showArchive = !showArchive }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Ico(R.drawable.ic_archive, size = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Архив (${archived.size})")
                        }
                    }
                    if (showArchive) items(archived, key = { "a" + it.id }) { cv ->
                        ChatItem(cv, me, h24, menuFor == cv.id, { menuFor = if (it) cv.id else null }, vm, { onOpenChat(cv.id) }, Modifier.animateItem())
                    }
                }
            }
        }
    }

    if (showNew) NewChatSheet(vm, onDismiss = { showNew = false }, onOpen = { showNew = false; onOpenChat(it) })
    if (showCalls) CallHistorySheet(vm, onDismiss = { showCalls = false })
}

@Composable
private fun ChatItem(
    cv: ChatView, me: Rec?, h24: Boolean, menuOpen: Boolean, onMenu: (Boolean) -> Unit,
    vm: AppVM, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = cv.chat
    val unreadN = c.i("unread_count")
    val isUnread = unreadN > 0 || c.b("marked_unread")
    val scheme = MaterialTheme.colorScheme
    val showBadge = me?.b("show_unread_badge", true) != false
    val showPreview = me?.b("show_chat_preview", true) != false
    val compact = me?.s("chat_list_density") == "compact"
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { vm.toggleArchive(c); false }
            SwipeToDismissBoxValue.EndToStart -> { vm.togglePin(c); false }
            else -> false
        }
    })
    Box(modifier) {
        SwipeToDismissBox(
            state = state,
            backgroundContent = {
                val dir = state.dismissDirection
                val archive = dir == SwipeToDismissBoxValue.StartToEnd
                Box(
                    Modifier.fillMaxSize().background(if (archive) scheme.tertiaryContainer else scheme.primaryContainer).padding(horizontal = 26.dp),
                    contentAlignment = if (archive) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    if (dir != SwipeToDismissBoxValue.Settled) {
                        Ico(
                            if (archive) R.drawable.ic_archive else R.drawable.ic_pin_filled,
                            tint = if (archive) scheme.onTertiaryContainer else scheme.onPrimaryContainer, size = 26.dp,
                        )
                    }
                }
            },
        ) {
            Row(
                Modifier.fillMaxWidth().background(scheme.background)
                    .combinedClickable(onClick = onClick, onLongClick = { onMenu(true) })
                    .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Avatar(
                        cv.name, cv.color, cv.avatarUrl, if (compact) 48.dp else 58.dp,
                        me?.s("avatar_shape").orEmpty().ifBlank { "circle" },
                        online = cv.online && me?.b("show_online_dot", true) != false,
                    )
                    if (cv.isGroup) Box(
                        Modifier.align(Alignment.BottomEnd).size(20.dp).background(scheme.background, CircleShape).padding(2.dp),
                    ) { Ico(R.drawable.ic_group_filled, tint = scheme.primary, size = 16.dp) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cv.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp,
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (cv.emojiStatus.isNotBlank()) Text(" ${cv.emojiStatus}")
                        if (cv.other != null) {
                            val t = when { cv.other.b("is_premium") -> "fpremium"; cv.other.b("is_pro") -> "fpro"; else -> "free" }
                            if (t != "free") { Spacer(Modifier.width(6.dp)); TierChip(t) }
                        }
                        if (c.b("muted")) { Spacer(Modifier.width(6.dp)); Ico(R.drawable.ic_bell_off, size = 14.dp, tint = scheme.outline) }
                        Spacer(Modifier.weight(1f))
                        Text(
                            fmtChatTime(c.s("last_message_time").ifBlank { c.s("updated_date") }, h24), fontSize = 12.sp,
                            color = if (isUnread) scheme.primary else scheme.onSurfaceVariant,
                            fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showPreview) Text(
                            c.s("last_message").ifBlank { "Нет сообщений" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f), fontSize = 14.sp,
                            color = if (isUnread) scheme.onSurface else scheme.onSurfaceVariant,
                            fontWeight = if (isUnread) FontWeight.Medium else FontWeight.Normal,
                        ) else Spacer(Modifier.weight(1f))
                        if (c.b("pinned")) { Spacer(Modifier.width(6.dp)); Ico(R.drawable.ic_pin_filled, size = 15.dp, tint = scheme.outline) }
                        if (isUnread && showBadge) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.defaultMinSize(minWidth = 22.dp).height(22.dp)
                                    .background(if (c.b("muted")) scheme.outline else scheme.primary, RoundedCornerShape(11.dp))
                                    .padding(horizontal = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (unreadN > 99) "99+" else if (unreadN > 0) unreadN.toString() else "•",
                                    color = scheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
            DropdownMenuItem(text = { Text(if (c.b("pinned")) "Открепить" else "Закрепить") }, leadingIcon = { Ico(R.drawable.ic_pin) }, onClick = { vm.togglePin(c); onMenu(false) })
            DropdownMenuItem(text = { Text(if (c.b("archived")) "Из архива" else "В архив") }, leadingIcon = { Ico(R.drawable.ic_archive) }, onClick = { vm.toggleArchive(c); onMenu(false) })
            DropdownMenuItem(text = { Text(if (c.b("muted")) "Включить звук" else "Без звука") }, leadingIcon = { Ico(R.drawable.ic_bell_off) }, onClick = { vm.toggleMute(c); onMenu(false) })
            DropdownMenuItem(text = { Text("Пометить непрочитанным") }, leadingIcon = { Ico(R.drawable.ic_check) }, onClick = { vm.markUnread(c); onMenu(false) })
        }
    }
}
