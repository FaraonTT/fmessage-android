@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.AuthState
import com.fworld.fmessage.R
import com.fworld.fmessage.data.b
import com.fworld.fmessage.data.i
import com.fworld.fmessage.data.s

@Composable
fun RootScreen(vm: AppVM) {
    val auth by vm.authState.collectAsState()
    val user by vm.user.collectAsState()
    val theme by vm.themeMode.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.toasts.collect { snack.showSnackbar(it) } }

    FTheme(user, theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when (auth) {
                    AuthState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_f_message_logo), null, Modifier.size(72.dp), tint = Color.Unspecified)
                    }
                    AuthState.LoggedOut -> AuthScreen(vm)
                    AuthState.LoggedIn -> MainShell(vm)
                }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
            }
        }
    }
}

private data class DockTab(val id: String, val label: String, val icon: Int, val filled: Int)

@Composable
private fun MainShell(vm: AppVM) {
    val user by vm.user.collectAsState()
    val chats by vm.chats.collectAsState()
    val openId by vm.openChatId.collectAsState()
    var tab by rememberSaveable { mutableStateOf("chats") }
    val me = user ?: return
    val isOwner = me.b("is_owner")
    val isAdmin = me.s("role") == "admin" || isOwner
    val current = if ((tab == "admin" && !isAdmin) || (tab == "owner" && !isOwner)) "chats" else tab

    BackHandler(enabled = openId != null) { vm.closeChat() }

    val tabs = buildList {
        add(DockTab("chats", "Чаты", R.drawable.ic_chat, R.drawable.ic_chat_filled))
        add(DockTab("music", "Музыка", R.drawable.ic_music, R.drawable.ic_music_filled))
        add(DockTab("profile", "Профиль", R.drawable.ic_profile, R.drawable.ic_profile_filled))
        add(DockTab("settings", "Ещё", R.drawable.ic_settings, R.drawable.ic_settings_filled))
        if (isAdmin) add(DockTab("admin", "Админ", R.drawable.ic_admin, R.drawable.ic_admin_filled))
        if (isOwner) add(DockTab("owner", "Владелец", R.drawable.ic_owner, R.drawable.ic_owner_filled))
    }
    val unread = remember(chats, me) {
        vm.myChats().filter { !it.b("archived") && !it.b("muted") }.sumOf { it.i("unread_count") }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            AnimatedContent(
                targetState = current,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.97f)) togetherWith fadeOut() },
                label = "tabs",
            ) { t ->
                when (t) {
                    "chats" -> ChatsHome(vm, onOpenChat = { vm.openChat(it) })
                    "music" -> MusicScreen(vm)
                    "profile" -> ProfileScreen(vm)
                    "settings" -> SettingsScreen(vm)
                    "admin" -> AdminScreen(vm, owner = false)
                    "owner" -> AdminScreen(vm, owner = true)
                }
            }
        }

        // floating mini player + dock
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MiniPlayer(vm)
            Spacer(Modifier.height(8.dp))
            FloatingDock(tabs, current, unread) { tab = it }
        }

        AnimatedVisibility(
            visible = openId != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val id = openId
            if (id != null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                ChatScreen(vm, id, onBack = { vm.closeChat() })
            }
        }

        CallOverlay(vm)
    }
}

@Composable
private fun FloatingDock(tabs: List<DockTab>, current: String, unread: Int, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = scheme.surfaceContainerHighest.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shadowElevation = 16.dp,
    ) {
        Row(Modifier.padding(6.dp).animateContentSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                val sel = current == t.id
                val bg by animateColorAsState(if (sel) scheme.primaryContainer else Color.Transparent, label = "dockBg")
                val pad by animateDpAsState(if (sel) 16.dp else 13.dp, label = "dockPad")
                val tint = if (sel) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                Row(
                    Modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(bg)
                        .clickable { onSelect(t.id) }.padding(horizontal = pad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Ico(if (sel) t.filled else t.icon, tint = tint, size = 23.dp)
                        if (t.id == "chats" && unread > 0 && !sel) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp)
                                    .defaultMinSize(minWidth = 16.dp).height(16.dp)
                                    .background(scheme.error, CircleShape).padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (unread > 99) "99+" else unread.toString(), color = scheme.onError, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    AnimatedVisibility(visible = sel, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                        Text(t.label, Modifier.padding(start = 8.dp), color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
