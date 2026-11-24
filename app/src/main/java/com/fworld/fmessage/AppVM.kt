package com.fworld.fmessage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fworld.fmessage.call.CallManager
import com.fworld.fmessage.data.*
import com.fworld.fmessage.music.MusicController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

enum class AuthState { Loading, LoggedOut, LoggedIn }

class AppVM(private val app: Application) : AndroidViewModel(app) {
    val authState = MutableStateFlow(AuthState.Loading)
    val user = MutableStateFlow<Rec?>(null)
    val chats = MutableStateFlow<List<Rec>>(emptyList())
    val profiles = MutableStateFlow<List<Rec>>(emptyList())
    val chatsLoading = MutableStateFlow(true)
    val openChatId = MutableStateFlow<String?>(null)
    val messages = MutableStateFlow<List<Rec>>(emptyList())
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val themeMode = MutableStateFlow(Prefs.themeMode)

    val calls = CallManager(app, viewModelScope)
    val music = MusicController(app, this)

    @Volatile
    var foreground = true
    private var presenceJob: Job? = null
    private var eventsJob: Job? = null

    init {
        val t = Prefs.token
        if (t == null || !Gh.configured()) {
            authState.value = AuthState.LoggedOut
        } else {
            Api.token = t
            viewModelScope.launch { bootstrap() }
        }
    }

    fun toast(msg: String) { toasts.tryEmit(msg) }

    @Volatile var pendingOpenChat: String? = null

    fun tryOpenPending() {
        val id = pendingOpenChat ?: return
        if (authState.value == AuthState.LoggedIn) { pendingOpenChat = null; openChat(id) }
    }

    // ---------- auth ----------

    private suspend fun bootstrap() {
        try {
            var u = AuthApi.me()
            u = normalizeUser(u)
            user.value = u
            authState.value = AuthState.LoggedIn
            Realtime.start(Api.token.orEmpty())
            startEvents()
            calls.attach(u)
            music.attach(u)
            viewModelScope.launch { syncProfile(u) }
            loadChats()
            loadProfiles()
            startPresence()
            tryOpenPending()
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                Prefs.token = null
                Api.token = null
                authState.value = AuthState.LoggedOut
            } else if (user.value == null) {
                authState.value = AuthState.LoggedOut
                toast(e.message ?: "Ошибка сети")
            }
        }
    }

    private suspend fun normalizeUser(start: Rec): Rec {
        var u = start
        suspend fun upd(d: Rec) {
            try { AuthApi.updateMe(d); u = u.merge(d) } catch (ignored: Exception) {}
        }
        if (u.s("display_name").isBlank() && u.s("full_name").isBlank() && u.s("email").isNotBlank()) {
            upd(obj("display_name" to u.s("email").substringBefore("@")))
        }
        if (u.s("username").isBlank() && u.s("email").isNotBlank()) {
            val handle = u.s("email").substringBefore("@").lowercase().replace(Regex("[^a-z0-9_.]"), "")
            if (handle.isNotBlank()) upd(obj("username" to handle))
        }
        val now = System.currentTimeMillis()
        if (u.b("is_premium") && u.s("premium_expires_date").isNotBlank() && parseIso(u.s("premium_expires_date")) < now) {
            upd(obj("is_premium" to false, "subscription_tier" to (if (u.b("is_pro")) "fpro" else "free"), "subscription_status" to "expired"))
        }
        if (u.b("is_pro") && !u.b("is_premium") && u.s("pro_expires_date").isNotBlank() && parseIso(u.s("pro_expires_date")) < now) {
            upd(obj("is_pro" to false, "subscription_tier" to "free", "subscription_status" to "expired"))
        }
        if (u.b("is_owner")) {
            val m = mutableMapOf<String, Any?>()
            if (!u.b("is_premium")) {
                m["is_premium"] = true; m["is_pro"] = true; m["subscription_tier"] = "fpremium"
                m["subscription_status"] = "active"; m["premium_activated_date"] = nowIso()
                m["premium_expires_date"] = java.time.Instant.now().plusSeconds(365L * 86400).toString()
            }
            if (u.s("role") != "admin") m["role"] = "admin"
            if (m.isNotEmpty()) upd(toJson(m) as JsonObject)
        }
        return u
    }

    fun login(email: String, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val t = AuthApi.login(email, password)
                Prefs.token = t
                bootstrap()
                done(null)
            } catch (e: ApiException) {
                done(e.message ?: "Неверный email или пароль")
            }
        }
    }

    fun register(email: String, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                AuthApi.register(email, password)
                val t = AuthApi.login(email, password)
                Prefs.token = t
                bootstrap()
                done(null)
            } catch (e: ApiException) {
                done(e.message ?: "Не удалось зарегистрироваться")
            }
        }
    }

    fun changePassword(current: String, new: String, done: (String?) -> Unit) {
        val id = user.value?.id ?: return
        viewModelScope.launch {
            try { AuthApi.changePassword(id, current, new); done(null) }
            catch (e: ApiException) { done(e.message ?: "Не удалось сменить пароль") }
        }
    }

    fun logout() {
        viewModelScope.launch {
            user.value?.let { try { updateProfileOnline(it, false) } catch (ignored: Exception) {} }
            Realtime.stop()
            presenceJob?.cancel()
            eventsJob?.cancel()
            calls.detach()
            music.stop()
            Prefs.token = null
            Api.token = null
            user.value = null
            chats.value = emptyList()
            profiles.value = emptyList()
            messages.value = emptyList()
            openChatId.value = null
            authState.value = AuthState.LoggedOut
        }
    }

    fun refreshUser() {
        viewModelScope.launch {
            try { user.value = AuthApi.me().also { calls.attach(it); music.attach(it) } } catch (ignored: Exception) {}
        }
    }

    fun updateMe(vararg p: Pair<String, Any?>, after: ((Boolean) -> Unit)? = null) {
        val d = obj(*p)
        val before = user.value
        user.value = before?.merge(d)
        viewModelScope.launch {
            try {
                AuthApi.updateMe(d)
                user.value?.let { calls.attach(it) }
                after?.invoke(true)
            } catch (e: ApiException) {
                user.value = before
                toast(e.message ?: "Не удалось сохранить")
                after?.invoke(false)
            }
        }
    }

    // ---------- presence ----------

    private fun startPresence() {
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch {
            while (true) {
                val u = user.value
                if (u != null && foreground) markPresence(true)
                delay(120_000)
            }
        }
    }

    fun onForeground() {
        foreground = true
        Realtime.foreground = true
        if (authState.value == AuthState.LoggedIn) viewModelScope.launch { markPresence(true) }
    }

    fun onBackground() {
        foreground = false
        Realtime.foreground = false
        if (authState.value == AuthState.LoggedIn) viewModelScope.launch { markPresence(false) }
    }

    private suspend fun markPresence(online: Boolean) {
        val u = user.value ?: return
        updateProfileOnline(u, online)
    }

    // ---------- profiles ----------

    suspend fun syncProfile(u: Rec) {
        try {
            val existing = E.Profile.filter(obj("user_id" to u.id), "-updated_date", 1)
            val data = obj(
                "user_id" to u.id,
                "full_name" to u.s("display_name").ifBlank { u.s("full_name") },
                "username" to u.s("username"),
                "email" to u.s("email"),
                "avatar_color" to u.s("avatar_color").ifBlank { "#6366f1" },
                "avatar_url" to u.s("avatar_url"),
                "banner_url" to u.s("banner_url"),
                "bio" to u.s("bio"),
                "status" to u.s("status"),
                "pronouns" to u.s("pronouns"),
                "is_online" to u.b("is_online"),
                "last_seen" to u.s("last_seen").ifBlank { nowIso() },
                "show_activity_status" to u.b("show_activity_status", true),
                "is_pro" to u.b("is_pro"),
                "is_premium" to u.b("is_premium"),
                "custom_badge" to u.s("custom_badge"),
                "custom_badge_description" to u.s("custom_badge_description"),
                "custom_badge_color" to u.s("custom_badge_color").ifBlank { "#6366f1" },
                "custom_badge_icon" to u.s("custom_badge_icon"),
                "custom_badge_shape" to u.s("custom_badge_shape").ifBlank { "squircle" },
                "custom_badge_photo" to u.s("custom_badge_photo"),
                "profile_theme" to u.s("profile_theme").ifBlank { "#6366f1" },
                "social_links" to u.s("social_links").ifBlank { "[]" },
                "role" to u.s("role").ifBlank { "user" },
            )
            if (existing.isNotEmpty()) E.Profile.update(existing[0].id, data) else E.Profile.create(data)
        } catch (ignored: Exception) {}
    }

    private suspend fun updateProfileOnline(u: Rec, online: Boolean) {
        try {
            val existing = E.Profile.filter(obj("user_id" to u.id), "-updated_date", 1)
            if (existing.isNotEmpty()) {
                val show = u.b("show_activity_status", true)
                E.Profile.update(existing[0].id, obj("is_online" to (show && online), "last_seen" to nowIso()))
            }
        } catch (ignored: Exception) {}
    }

    /** Saves profile fields to User and mirrors them to Profile. */
    fun saveProfile(vararg p: Pair<String, Any?>, after: ((Boolean) -> Unit)? = null) {
        updateMe(*p) { ok ->
            if (ok) user.value?.let { u -> viewModelScope.launch { syncProfile(u) } }
            after?.invoke(ok)
        }
    }

    suspend fun loadProfiles() {
        try {
            profiles.value = E.Profile.list()
        } catch (ignored: Exception) {}
    }

    // ---------- chats ----------

    suspend fun loadChats() {
        try {
            chats.value = E.Chat.list("-updated_date", 100)
        } catch (ignored: Exception) {
        } finally {
            chatsLoading.value = false
        }
    }

    fun myChats(): List<Rec> {
        val me = user.value ?: return emptyList()
        return chats.value.filter { it.s("participant_ids").isBlank() || participantIds(it).contains(me.id) }
    }

    fun chatById(id: String?): Rec? = chats.value.firstOrNull { it.id == id }

    fun togglePin(chat: Rec) {
        viewModelScope.launch {
            if (!chat.b("pinned")) {
                val pinned = myChats().count { it.b("pinned") && !it.b("archived") }
                val lim = Plans.limits(user.value).pinnedChats
                if (pinned >= lim) { toast("Лимит: $lim закреплённых чатов. Активируйте fPro/fPremium."); return@launch }
            }
            safe { E.Chat.update(chat.id, obj("pinned" to !chat.b("pinned"))) }
        }
    }

    fun toggleArchive(chat: Rec) = viewModelScope.launch {
        safe { E.Chat.update(chat.id, obj("archived" to !chat.b("archived"), "pinned" to (if (chat.b("archived")) chat.b("pinned") else false))) }
    }

    fun toggleMute(chat: Rec) = viewModelScope.launch { safe { E.Chat.update(chat.id, obj("muted" to !chat.b("muted"))) } }

    fun markUnread(chat: Rec) = viewModelScope.launch { safe { E.Chat.update(chat.id, obj("marked_unread" to true, "unread_count" to 1)) } }

    fun deleteChat(chat: Rec) = viewModelScope.launch {
        safe {
            E.Message.deleteMany(obj("chat_id" to chat.id))
            E.Chat.delete(chat.id)
            if (openChatId.value == chat.id) closeChat()
        }
    }

    private suspend fun safe(block: suspend () -> Unit) {
        try { block() } catch (e: ApiException) { toast(e.message ?: "Ошибка") }
    }

    private fun infoOf(p: Rec) = obj(
        "name" to p.displayName(),
        "color" to p.s("avatar_color").ifBlank { "#6366f1" },
        "avatar_url" to p.s("avatar_url"),
        "online" to p.b("is_online"),
    )

    private fun meInfo(u: Rec) = obj(
        "name" to u.displayName(),
        "color" to u.s("avatar_color").ifBlank { "#6366f1" },
        "avatar_url" to u.s("avatar_url"),
        "online" to u.b("is_online"),
    )

    /** other is a Profile record. */
    fun startChat(other: Rec, onOpen: (String) -> Unit) {
        val me = user.value ?: return
        val otherId = other.s("user_id")
        val existing = myChats().firstOrNull {
            !it.b("is_group") && it.s("participant_ids").isNotBlank() && participantIds(it).containsAll(listOf(me.id, otherId))
        }
        if (existing != null) { onOpen(existing.id); return }
        viewModelScope.launch {
            try {
                val info = obj(me.id to meInfo(me), otherId to infoOf(other))
                val created = E.Chat.create(obj(
                    "name" to other.displayName(),
                    "avatar_color" to other.s("avatar_color").ifBlank { "#6366f1" },
                    "participant_ids" to "${me.id},$otherId",
                    "participants" to listOf(me.id, otherId),
                    "participant_info" to info.toString(),
                    "last_message" to "",
                    "last_message_time" to nowIso(),
                ))
                chats.value = listOf(created) + chats.value.filter { it.id != created.id }
                onOpen(created.id)
            } catch (e: ApiException) { toast(e.message ?: "Не удалось создать чат") }
        }
    }

    fun createGroup(name: String, memberUserIds: List<String>, onOpen: (String) -> Unit) {
        val me = user.value ?: return
        val lim = Plans.limits(me).groupMembers
        if (memberUserIds.size > lim) { toast("Лимит: $lim участников в группе. Активируйте fPro/fPremium."); return }
        viewModelScope.launch {
            try {
                val info = mutableMapOf<String, Any?>(me.id to meInfo(me))
                memberUserIds.forEach { sid ->
                    profiles.value.firstOrNull { it.s("user_id") == sid }?.let { info[sid] = infoOf(it) }
                }
                val created = E.Chat.create(obj(
                    "name" to name,
                    "avatar_color" to "#8b5cf6",
                    "participant_ids" to (listOf(me.id) + memberUserIds).joinToString(","),
                    "participants" to (listOf(me.id) + memberUserIds),
                    "participant_info" to toJson(info).toString(),
                    "is_group" to true,
                    "group_admin_id" to me.id,
                    "last_message" to "",
                    "last_message_time" to nowIso(),
                ))
                chats.value = listOf(created) + chats.value.filter { it.id != created.id }
                onOpen(created.id)
            } catch (e: ApiException) { toast(e.message ?: "Не удалось создать группу") }
        }
    }

    // ---------- messages ----------

    fun openChat(id: String) {
        openChatId.value = id
        Realtime.openChatId = id
        messages.value = emptyList()
        viewModelScope.launch {
            try {
                val list = E.Message.filter(obj("chat_id" to id), "created_date", 200)
                if (openChatId.value == id) messages.value = list
                list.filter { !it.b("is_read") && it.s("sender_id") != user.value?.id }.forEach { markAsRead(it) }
            } catch (ignored: Exception) {}
            val c = chatById(id)
            if (c != null && (c.i("unread_count") > 0 || c.b("marked_unread"))) {
                safe { E.Chat.update(id, obj("unread_count" to 0, "marked_unread" to false)) }
            }
        }
    }

    fun closeChat() {
        openChatId.value = null
        Realtime.openChatId = null
        messages.value = emptyList()
    }

    private suspend fun markAsRead(m: Rec) {
        val u = user.value ?: return
        if (m.s("sender_id") == u.id) return
        if (!u.b("read_receipts", true) || !u.b("auto_mark_read", true)) return
        try { E.Message.update(m.id, obj("is_read" to true)) } catch (ignored: Exception) {}
    }

    fun sendMessage(chat: Rec, content: String, replyTo: Rec? = null, done: ((Boolean) -> Unit)? = null) {
        val u = user.value ?: return
        viewModelScope.launch {
            try {
                val msg = E.Message.create(obj(
                    "content" to content,
                    "chat_id" to chat.id,
                    "sender_name" to u.displayName(),
                    "sender_id" to u.id,
                    "participants" to (participantIds(chat).ifEmpty { listOf(u.id) }),
                    "reply_to_id" to (replyTo?.id ?: ""),
                    "reply_to_name" to (replyTo?.s("sender_name") ?: ""),
                    "reply_to_content" to (replyTo?.let { Msg.preview(it.s("content")).take(80) } ?: ""),
                ))
                appendMessage(msg)
                E.Chat.update(chat.id, obj(
                    "last_message" to Msg.preview(content).take(60),
                    "last_message_time" to nowIso(),
                    "unread_count" to 0,
                    "marked_unread" to false,
                ))
                done?.invoke(true)
            } catch (e: ApiException) {
                toast(e.message ?: "Не удалось отправить")
                done?.invoke(false)
            }
        }
    }

    private fun appendMessage(m: Rec) {
        if (m.s("chat_id") != openChatId.value) return
        if (messages.value.any { it.id == m.id }) return
        messages.value = messages.value + m
    }

    fun editMessage(m: Rec, text: String) = viewModelScope.launch {
        safe {
            val upd = E.Message.update(m.id, obj("content" to text, "edited" to true))
            messages.value = messages.value.map { if (it.id == m.id) it.merge(upd) else it }
        }
    }

    fun deleteMessage(m: Rec) = viewModelScope.launch {
        safe { E.Message.delete(m.id); messages.value = messages.value.filter { it.id != m.id } }
    }

    fun toggleReaction(m: Rec, emoji: String) {
        val u = user.value ?: return
        val r = reactionsOf(m).toMutableMap()
        val cur = (r[emoji] ?: emptyList()).toMutableList()
        if (cur.contains(u.id)) cur.remove(u.id) else cur.add(u.id)
        if (cur.isEmpty()) r.remove(emoji) else r[emoji] = cur
        val json = toJson(r).toString()
        messages.value = messages.value.map { if (it.id == m.id) it.with("reactions" to json) else it }
        viewModelScope.launch { safe { E.Message.update(m.id, obj("reactions" to json)) } }
    }

    fun toggleStar(m: Rec) {
        val u = user.value ?: return
        val list = starredBy(m).toMutableList()
        if (list.contains(u.id)) list.remove(u.id) else list.add(u.id)
        val json = toJson(list).toString()
        messages.value = messages.value.map { if (it.id == m.id) it.with("starred_by" to json) else it }
        viewModelScope.launch { safe { E.Message.update(m.id, obj("starred_by" to json)) } }
    }

    fun updateMessageContent(m: Rec, content: String) {
        messages.value = messages.value.map { if (it.id == m.id) it.with("content" to content) else it }
        viewModelScope.launch { safe { E.Message.update(m.id, obj("content" to content)) } }
    }

    fun forward(m: Rec, target: Rec, done: () -> Unit) {
        val u = user.value ?: return
        viewModelScope.launch {
            safe {
                E.Message.create(obj(
                    "content" to m.s("content"),
                    "chat_id" to target.id,
                    "sender_name" to u.displayName(),
                    "sender_id" to u.id,
                    "participants" to (participantIds(target).ifEmpty { listOf(u.id) }),
                    "forwarded_from" to m.s("sender_name"),
                ))
                E.Chat.update(target.id, obj("last_message" to "↪ Пересланное сообщение", "last_message_time" to nowIso()))
                done()
            }
        }
    }

    suspend fun uploadFile(bytes: ByteArray, name: String, mime: String, public: Boolean = false): String? = try {
        Api.upload(bytes, name, mime, public)
    } catch (e: ApiException) {
        toast(e.message ?: "Не удалось загрузить файл"); null
    }

    suspend fun searchMessages(q: String): List<Rec> {
        val u = user.value ?: return emptyList()
        return try {
            val myIds = myChats().map { it.id }.toSet()
            E.Message.list("-created_date", 300)
                .filter { it.s("chat_id") in myIds && it.s("content").contains(q, ignoreCase = true) && Msg.type(it.s("content")) == "text" }
                .take(10)
        } catch (e: Exception) { emptyList() }
    }

    // ---------- realtime ----------

    private fun startEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            Realtime.events.collect { ev ->
                try { handleEvent(ev) } catch (ignored: Exception) {}
            }
        }
    }

    private suspend fun handleEvent(ev: RtEvent) {
        val u = user.value ?: return
        when (ev.entity) {
            "Chat" -> {
                var d = ev.data
                when (ev.type) {
                    "create" -> chats.value = listOf(d) + chats.value.filter { it.id != d.id }
                    "update" -> {
                        if (d.b("_oversize")) d = try { E.Chat.get(ev.id) } catch (e: Exception) { d }
                        chats.value = chats.value.map { if (it.id == d.id) d else it }
                            .sortedByDescending { parseIso(it.s("last_message_time").ifBlank { it.s("updated_date") }) }
                    }
                    "delete" -> chats.value = chats.value.filter { it.id != ev.id }
                }
            }
            "Profile" -> {
                val d = ev.data
                val uid = d.s("user_id")
                when (ev.type) {
                    "update" -> profiles.value = profiles.value.map { if (it.s("user_id") == uid) it.merge(d) else it }
                    "create" -> if (profiles.value.none { it.s("user_id") == uid }) profiles.value = profiles.value + d
                    "delete" -> profiles.value = profiles.value.filter { it.id != ev.id }
                }
            }
            "Message" -> {
                var d = ev.data
                if (d.b("_oversize") && ev.type != "delete") d = try { E.Message.get(ev.id) } catch (e: Exception) { d }
                val inOpen = d.s("chat_id") == openChatId.value
                when (ev.type) {
                    "create" -> {
                        if (inOpen) {
                            appendMessage(d)
                            if (foreground) markAsRead(d)
                        }
                        if (d.s("sender_id") != u.id) onIncoming(d, inOpen)
                    }
                    "update" -> if (inOpen) messages.value = messages.value.map { if (it.id == d.id) d else it }
                    "delete" -> messages.value = messages.value.filter { it.id != ev.id }
                }
            }
            "Call" -> calls.onEvent(ev)
        }
    }

    private suspend fun onIncoming(m: Rec, inOpen: Boolean) {
        val u = user.value ?: return
        val chat = chatById(m.s("chat_id")) ?: return
        if (chat.b("archived")) return
        if (chat.s("participant_ids").isNotBlank() && !participantIds(chat).contains(u.id)) return
        val active = inOpen && foreground
        if (!active) {
            try { E.Chat.update(chat.id, obj("unread_count" to chat.i("unread_count") + 1)) } catch (ignored: Exception) {}
            if (u.b("push_notifications", true) && !chat.b("muted") && !u.b("dnd_mode")) {
                Notifier.message(
                    app,
                    title = m.s("sender_name").ifBlank { chat.s("name") },
                    text = if (u.b("notification_preview", true)) Msg.preview(m.s("content")) else "Новое сообщение",
                    chatId = chat.id,
                )
            }
        }
    }

    override fun onCleared() {
        Realtime.stop()
        super.onCleared()
    }
}
