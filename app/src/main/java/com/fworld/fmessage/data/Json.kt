package com.fworld.fmessage.data

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

typealias Rec = JsonObject

fun Rec.s(k: String): String {
    val p = this[k] as? JsonPrimitive ?: return ""
    return if (p is JsonNull) "" else p.content
}

fun Rec.b(k: String, d: Boolean = false): Boolean {
    val p = this[k] as? JsonPrimitive ?: return d
    return p.booleanOrNull ?: d
}

fun Rec.i(k: String, d: Int = 0): Int {
    val p = this[k] as? JsonPrimitive ?: return d
    return p.intOrNull ?: p.doubleOrNull?.toInt() ?: d
}

fun Rec.has(k: String): Boolean {
    val e = this[k] ?: return false
    return e !is JsonNull
}

val Rec.id: String get() = s("id")

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Collection<*> -> JsonArray(v.map { toJson(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    else -> JsonPrimitive(v.toString())
}

fun obj(vararg p: Pair<String, Any?>): JsonObject =
    JsonObject(p.associate { it.first to toJson(it.second) })

fun Rec.with(vararg p: Pair<String, Any?>): Rec = JsonObject(this + obj(*p))
fun Rec.merge(o: Rec): Rec = JsonObject(this + o)

/** Parses a JSON-encoded string field into a JsonObject (or empty). */
fun Rec.jsonObj(k: String): JsonObject = try {
    val t = s(k)
    if (t.isBlank()) JsonObject(emptyMap()) else AppJson.parseToJsonElement(t) as? JsonObject ?: JsonObject(emptyMap())
} catch (e: Exception) { JsonObject(emptyMap()) }

fun Rec.jsonArr(k: String): JsonArray = try {
    val t = s(k)
    if (t.isBlank()) JsonArray(emptyList()) else AppJson.parseToJsonElement(t) as? JsonArray ?: JsonArray(emptyList())
} catch (e: Exception) { JsonArray(emptyList()) }

fun parseIso(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    return try {
        Instant.parse(s).toEpochMilli()
    } catch (e: Exception) {
        try {
            LocalDateTime.parse(s.removeSuffix("Z").substringBefore('+')).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e2: Exception) { 0L }
    }
}

fun nowIso(): String = Instant.now().toString()

fun Rec.displayName(): String =
    s("display_name").ifBlank { s("full_name") }.ifBlank { s("email") }
