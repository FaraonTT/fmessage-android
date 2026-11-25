@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.AuthState
import com.fworld.fmessage.R
import com.fworld.fmessage.data.b
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
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
            }
        }
    }
}

@Composable
private fun MainShell(vm: AppVM) {
    val user by vm.user.collectAsState()
    val openId by vm.openChatId.collectAsState()
    var tab by rememberSaveable { mutableStateOf("chats") }
    val me = user ?: return
    val isOwner = me.b("is_owner")
    val isAdmin = me.s("role") == "admin" || isOwner
    val current = if ((tab == "admin" && !isAdmin) || (tab == "owner" && !isOwner)) "chats" else tab

    BackHandler(enabled = openId != null) { vm.closeChat() }

    data class T(val id: String, val label: String, val icon: Int, val filled: Int)
    val tabs = buildList {
        add(T("chats", "Чаты", R.drawable.ic_chat, R.drawable.ic_chat_filled))
        add(T("music", "Музыка", R.drawable.ic_music, R.drawable.ic_music_filled))
        add(T("profile", "Профиль", R.drawable.ic_profile, R.drawable.ic_profile_filled))
        add(T("settings", "Ещё", R.drawable.ic_settings, R.drawable.ic_settings_filled))
        if (isAdmin) add(T("admin", "Админ", R.drawable.ic_admin, R.drawable.ic_admin_filled))
        if (isOwner) add(T("owner", "Владелец", R.drawable.ic_owner, R.drawable.ic_owner_filled))
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    MiniPlayer(vm)
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = current == t.id, onClick = { tab = t.id },
                                icon = { Ico(if (current == t.id) t.filled else t.icon) },
                                label = { Text(t.label) }, alwaysShowLabel = tabs.size <= 4,
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.98f)) togetherWith fadeOut() },
                    label = "tabs",
                ) { t ->
                    when (t) {
                        "chats" -> ChatsScreen(vm, onOpenChat = { vm.openChat(it) })
                        "music" -> MusicScreen(vm)
                        "profile" -> ProfileScreen(vm)
                        "settings" -> SettingsScreen(vm)
                        "admin" -> AdminScreen(vm, owner = false)
                        "owner" -> AdminScreen(vm, owner = true)
                    }
                }
            }
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
