package com.fworld.fmessage.data

data class Limits(
    val pinnedChats: Int,
    val savedTracks: Int,
    val socialLinks: Int,
    val groupMembers: Int,
    val bioLength: Int,
    val voiceMessageDuration: Int,
)

object Plans {
    val FREE = Limits(3, 25, 3, 10, 200, 60)
    val PRO = Limits(100, Int.MAX_VALUE, Int.MAX_VALUE, 50, 2000, 600)
    val PREMIUM = Limits(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 500, 5000, 1800)

    fun tier(u: Rec?): String = when {
        u?.b("is_premium") == true -> "fpremium"
        u?.b("is_pro") == true -> "fpro"
        else -> "free"
    }

    fun limits(u: Rec?): Limits = when (tier(u)) {
        "fpremium" -> PREMIUM
        "fpro" -> PRO
        else -> FREE
    }

    fun label(tier: String) = when (tier) { "fpremium" -> "fPremium"; "fpro" -> "fPro"; else -> "Free" }
    fun price(tier: String) = when (tier) { "fpremium" -> 399; "fpro" -> 159; else -> 0 }
    fun inf(n: Int) = if (n == Int.MAX_VALUE) "∞" else n.toString()
}

object Subscription {
    class Redeemed(val tier: String, val days: Int)

    suspend fun redeemPromo(code: String, user: Rec): Redeemed {
        val trimmed = code.uppercase().trim()
        val codes = E.PromoCode.filter(obj("code" to trimmed))
        if (codes.isEmpty()) throw ApiException("Промокод не найден")
        val promo = codes[0]
        if (promo.i("used_count") >= promo.i("max_uses")) throw ApiException("Промокод уже использован")
        val isPremium = promo.s("tier") == "fpremium"
        val days = promo.i("duration_days")
        val now = nowIso()
        val expires = java.time.Instant.now().plusSeconds(days * 86400L).toString()
        val updates = obj(
            "is_pro" to true,
            "is_premium" to isPremium,
            "subscription_tier" to promo.s("tier"),
            "subscription_status" to "active",
            (if (isPremium) "premium_activated_date" else "pro_activated_date") to now,
            (if (isPremium) "premium_expires_date" else "pro_expires_date") to expires,
        )
        AuthApi.updateMe(updates)
        val used = promo.s("used_by").split(",").filter { it.isNotBlank() } + user.id
        E.PromoCode.update(promo.id, obj("used_count" to promo.i("used_count") + 1, "used_by" to used.joinToString(",")))
        return Redeemed(promo.s("tier"), days)
    }

    suspend fun cancel() {
        AuthApi.updateMe(obj("subscription_status" to "cancelled"))
    }

    fun generatePromoCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..10).map { chars.random() }.joinToString("")
    }

    fun expires(u: Rec?): String =
        if (u?.b("is_premium") == true) u.s("premium_expires_date") else u?.s("pro_expires_date").orEmpty()

    fun isActive(u: Rec?): Boolean {
        val exp = expires(u)
        return u?.s("subscription_status") == "active" && (exp.isBlank() || parseIso(exp) > System.currentTimeMillis())
    }
}

/** Message payload encodings (compatible with the web client). */
object Msg {
    const val MUSIC = "🎵MUSIC🎵"
    const val FILE = "📎FILE📎"
    const val PHOTO = "🖼️PHOTO🖼️"
    const val VIDEO = "🎥VIDEO🎥"
    const val POLL = "📊POLL📊"
    const val LOCATION = "📍LOCATION📍"
    const val VOICE = "🎤VOICE🎤"
    const val CONTACT = "👤CONTACT👤"

    fun type(c: String): String = when {
        c.startsWith(MUSIC) -> "music"
        c.startsWith(FILE) -> "file"
        c.startsWith(PHOTO) -> "photo"
        c.startsWith(VIDEO) -> "video"
        c.startsWith(POLL) -> "poll"
        c.startsWith(LOCATION) -> "location"
        c.startsWith(VOICE) -> "voice"
        c.startsWith(CONTACT) -> "contact"
        else -> "text"
    }

    fun decode(c: String, prefix: String): Rec? = try {
        if (!c.startsWith(prefix)) null else AppJson.parseToJsonElement(c.removePrefix(prefix)) as? JsonObjectAlias
    } catch (e: Exception) { null }

    fun encode(prefix: String, o: Rec): String = prefix + o.toString()

    fun preview(c: String): String {
        if (c.isBlank()) return ""
        return when (type(c)) {
            "music" -> decode(c, MUSIC)?.s("title")?.takeIf { it.isNotBlank() }?.let { "🎵 $it" } ?: "🎵 Музыка"
            "photo" -> "📷 Фото"
            "video" -> "🎥 Видео"
            "voice" -> "🎤 Голосовое сообщение"
            "location" -> "📍 Локация"
            "poll" -> "📊 Опрос"
            "file" -> "📎 Файл"
            "contact" -> "👤 Контакт"
            else -> if (c.length > 60) c.take(60) + "…" else c
        }
    }
}

private typealias JsonObjectAlias = kotlinx.serialization.json.JsonObject
