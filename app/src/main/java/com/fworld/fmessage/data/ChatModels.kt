package com.fworld.fmessage.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatView(
    val chat: Rec,
    val name: String,
    val color: String,
    val avatarUrl: String,
    val isGroup: Boolean,
    val online: Boolean,
    val other: Rec?,
    val emojiStatus: String,
) {
    val id: String get() = chat.id
}

fun participantIds(chat: Rec): List<String> =
    chat.s("participant_ids").split(",").filter { it.isNotBlank() }

fun profileOnline(p: Rec?): Boolean =
    p != null && p.b("is_online") && p.b("show_activity_status", true) &&
        System.currentTimeMillis() - parseIso(p.s("last_seen")) < 5 * 60_000L

fun chatView(chat: Rec, me: Rec?, profiles: List<Rec>): ChatView {
    val color = chat.s("avatar_color").ifBlank { "#6366f1" }
    if (chat.b("is_group") || chat.s("participant_ids").isBlank() || me == null) {
        return ChatView(chat, chat.s("name"), color, chat.s("avatar_url"), chat.b("is_group"), false, null, "")
    }
    val otherId = participantIds(chat).firstOrNull { it != me.id }
        ?: return ChatView(chat, chat.s("name"), color, "", false, false, null, "")
    val p = profiles.firstOrNull { it.s("user_id") == otherId }
    if (p != null) {
        return ChatView(
            chat, p.displayName(), p.s("avatar_color").ifBlank { "#6366f1" }, p.s("avatar_url"),
            false, profileOnline(p), p, p.s("emoji_status"),
        )
    }
    val info = chat.jsonObj("participant_info")[otherId] as? JsonObject
    if (info != null) {
        return ChatView(chat, info.s("name"), info.s("color").ifBlank { "#6366f1" }, info.s("avatar_url"), false, false, null, "")
    }
    return ChatView(chat, chat.s("name"), color, "", false, false, null, "")
}

fun reactionsOf(m: Rec): Map<String, List<String>> = try {
    m.jsonObj("reactions").mapValues { (_, v) ->
        (v as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    }
} catch (e: Exception) { emptyMap() }

fun starredBy(m: Rec): List<String> = try {
    m.jsonArr("starred_by").map { it.jsonPrimitive.content }
} catch (e: Exception) { emptyList() }
