package com.fworld.fmessage.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ApiException(message: String, val status: Int = 0) : Exception(message)

private class Conflict : Exception()

/** Session holder: [token] is the current user's id. */
object Api {
    @Volatile
    var token: String? = null

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.url.host == "raw.githubusercontent.com" && req.header("Authorization") == null && Gh.tok.isNotBlank()) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer ${Gh.tok}").build())
            } else chain.proceed(req)
        }
        .build()

    suspend fun upload(bytes: ByteArray, filename: String, mime: String, public: Boolean = false): String =
        Gh.upload(bytes, filename)
}

/** Thin client for the GitHub Contents API — the whole "server" lives in a private repo. */
object Gh {
    private const val API = "https://api.github.com"
    private const val BRANCH = "main"
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    val owner: String get() = Prefs.ghRepoFull.substringBefore("/", "")
    val repo: String get() = Prefs.ghRepoFull.substringAfter("/", "")
    val tok: String get() = Prefs.ghToken
    fun configured() = tok.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    class GhFile(val sha: String, val text: String)
    private class Cached(val etag: String?, val sha: String, val text: String)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(p: String) = locks.getOrPut(p) { Mutex() }

    private fun enc(path: String) = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun url(path: String) = "$API/repos/$owner/$repo/contents/${enc(path)}"

    private fun builder(u: String) = Request.Builder().url(u)
        .header("Authorization", "Bearer $tok")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun fail(code: Int, body: String): Nothing {
        val msg = try { (AppJson.parseToJsonElement(body) as? JsonObject)?.s("message").orEmpty() } catch (e: Exception) { "" }
        throw when {
            code == 401 -> ApiException("Токен GitHub недействителен", 401)
            code == 403 && msg.contains("rate limit", true) -> ApiException("Лимит запросов GitHub исчерпан, подождите немного", 403)
            code == 403 || code == 404 -> ApiException("Нет доступа к репозиторию данных. Проверьте токен и имя репозитория", code)
            else -> ApiException(msg.ifBlank { "Ошибка GitHub ($code)" }, code)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: java.io.IOException) { throw ApiException("Нет соединения с сервером", 0) }
    }

    /** Returns null when the file does not exist. */
    suspend fun read(path: String, fresh: Boolean = false): GhFile? = io {
        val c = cache[path]
        val rb = builder("${url(path)}?ref=$BRANCH")
        if (!fresh && c?.etag != null) rb.header("If-None-Match", c.etag)
        Api.http.newCall(rb.build()).execute().use { r ->
            when {
                r.code == 304 && c != null -> GhFile(c.sha, c.text)
                r.code == 404 -> { cache.remove(path); null }
                r.isSuccessful -> {
                    val j = AppJson.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject
                    val sha = j.s("sha")
                    var text = ""
                    val content = j.s("content")
                    if (content.isNotBlank()) text = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
                    else if (j.i("size") > 0) {
                        val br = builder("$API/repos/$owner/$repo/git/blobs/$sha").header("Accept", "application/vnd.github.raw+json").build()
                        Api.http.newCall(br).execute().use { b -> text = b.body?.string().orEmpty() }
                    }
                    cache[path] = Cached(r.header("ETag"), sha, text)
                    GhFile(sha, text)
                }
                else -> fail(r.code, r.body?.string().orEmpty())
            }
        }
    }

    private suspend fun putRaw(path: String, bytes: ByteArray, sha: String?, message: String): String = io {
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("branch", BRANCH)
            if (sha != null) put("sha", sha)
        }.toString().toRequestBody(JSON_MT)
        Api.http.newCall(builder(url(path)).put(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            cache.remove(path)
            when {
                r.isSuccessful -> ((AppJson.parseToJsonElement(text) as JsonObject)["content"] as? JsonObject)?.s("sha").orEmpty()
                r.code == 409 || r.code == 422 -> throw Conflict()
                else -> fail(r.code, text)
            }
        }
    }

    suspend fun remove(path: String) {
        repeat(4) {
            val f = read(path, fresh = true) ?: return
            val ok = io {
                val body = buildJsonObject { put("message", "db: delete $path"); put("sha", f.sha); put("branch", BRANCH) }
                    .toString().toRequestBody(JSON_MT)
                Api.http.newCall(builder(url(path)).delete(body).build()).execute().use { r ->
                    cache.remove(path)
                    when {
                        r.isSuccessful || r.code == 404 -> true
                        r.code == 409 || r.code == 422 -> false
                        else -> fail(r.code, r.body?.string().orEmpty())
                    }
                }
            }
            if (ok) return
            delay(150)
        }
    }

    suspend fun listDir(path: String): List<String> = io {
        Api.http.newCall(builder("${url(path)}?ref=$BRANCH").build()).execute().use { r ->
            when {
                r.code == 404 -> emptyList()
                r.isSuccessful -> (AppJson.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.s("name") } ?: emptyList()
                else -> fail(r.code, r.body?.string().orEmpty())
            }
        }
    }

    /** Optimistic read-modify-write with retries on concurrent edits. */
    suspend fun <T> modify(path: String, empty: String, f: (String) -> Pair<String, T>): T =
        lockFor(path).withLock {
            var attempt = 0
            while (true) {
                val cur = read(path, fresh = true)
                val (next, result) = f(cur?.text ?: empty)
                try {
                    putRaw(path, next.toByteArray(Charsets.UTF_8), cur?.sha, "db: ${path.substringAfterLast('/')}")
                    return@withLock result
                } catch (c: Conflict) {
                    if (++attempt >= 8) throw ApiException("Сервер данных занят, повторите попытку", 409)
                    delay(120L * attempt + (0..150).random())
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }

    suspend fun readArr(path: String): List<Rec> {
        val f = read(path) ?: return emptyList()
        return parseArr(f.text)
    }

    fun parseArr(text: String): List<Rec> = try {
        if (text.isBlank()) emptyList() else (AppJson.parseToJsonElement(text) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    suspend fun upload(bytes: ByteArray, filename: String): String {
        val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
        val path = "uploads/${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}-$safe"
        if (bytes.size > 40 * 1024 * 1024) throw ApiException("Файл слишком большой (максимум 40 МБ)")
        putRaw(path, bytes, null, "upload: $safe")
        return "https://raw.githubusercontent.com/$owner/$repo/$BRANCH/$path"
    }

    /** Checks that the repo is reachable with the given token. */
    suspend fun ping(): Boolean = io {
        Api.http.newCall(builder("$API/repos/$owner/$repo").build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.body?.string().orEmpty())
            true
        }
    }
}

// ------------------------------------------------------------------ query engine

private fun cmpPrim(a: JsonElement?, b: JsonElement?): Int {
    val x = (a as? JsonPrimitive)?.takeIf { it !is JsonNull }
    val y = (b as? JsonPrimitive)?.takeIf { it !is JsonNull }
    if (x == null || y == null) return if (x == null && y == null) 0 else if (x == null) -1 else 1
    val dx = x.doubleOrNull
    val dy = y.doubleOrNull
    if (dx != null && dy != null && !x.isString) return dx.compareTo(dy)
    val ix = parseIso(x.content)
    val iy = parseIso(y.content)
    if (ix > 0 && iy > 0) return ix.compareTo(iy)
    return x.content.compareTo(y.content)
}

private fun eqv(a: JsonElement?, b: JsonElement?): Boolean {
    if (a == null || a is JsonNull) return b == null || b is JsonNull
    if (b == null || b is JsonNull) return false
    if (a is JsonPrimitive && b is JsonPrimitive) return a.content == b.content
    return a == b
}

fun matches(r: Rec, q: Rec): Boolean = q.all { (k, cond) ->
    val v = r[k]
    if (cond is JsonObject && cond.keys.all { it.startsWith("$") }) {
        cond.all { (op, arg) ->
            when (op) {
                "\$lt" -> cmpPrim(v, arg) < 0
                "\$lte" -> cmpPrim(v, arg) <= 0
                "\$gt" -> cmpPrim(v, arg) > 0
                "\$gte" -> cmpPrim(v, arg) >= 0
                "\$ne" -> !eqv(v, arg)
                "\$in" -> (arg as? JsonArray)?.any { eqv(v, it) } ?: false
                else -> true
            }
        }
    } else eqv(v, cond)
}

private fun sortList(list: List<Rec>, sort: String?): List<Rec> {
    if (sort.isNullOrBlank()) return list
    val desc = sort.startsWith("-")
    val key = sort.removePrefix("-")
    val sorted = list.sortedWith { a, b -> cmpPrim(a[key], b[key]) }
    return if (desc) sorted.reversed() else sorted
}

private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(24)

private fun serialize(list: List<Rec>): String = JsonArray(list).toString()

// ------------------------------------------------------------------ entities

/** Generic collection stored as one JSON array file (or one file per chat for messages). */
class Entity(private val name: String, private val sharded: Boolean = false) {
    private val file = "db/$name.json"
    private val shardOf = ConcurrentHashMap<String, String>()

    private fun shardPath(chatId: String) = "db/$name/$chatId.json"

    private suspend fun shardsFor(q: Rec?): List<String> {
        val chat = q?.get("chat_id") as? JsonPrimitive
        if (chat != null && chat !is JsonNull) return listOf(shardPath(chat.content))
        return Gh.listDir("db/$name").filter { it.endsWith(".json") }.map { "db/$name/$it" }
    }

    private suspend fun readAll(q: Rec?): List<Rec> {
        if (!sharded) return Gh.readArr(file)
        return shardsFor(q).flatMap { p ->
            Gh.readArr(p).also { l -> l.forEach { shardOf[it.id] = p } }
        }
    }

    suspend fun list(sort: String? = null, limit: Int? = null): List<Rec> =
        sortList(readAll(null), sort).let { if (limit != null) it.take(limit) else it }

    suspend fun filter(q: Rec, sort: String? = null, limit: Int? = null): List<Rec> =
        sortList(readAll(q).filter { matches(it, q) }, sort).let { if (limit != null) it.take(limit) else it }

    suspend fun get(id: String): Rec =
        resolveAll(id).let { p -> Gh.readArr(p).firstOrNull { it.id == id } } ?: throw ApiException("Не найдено", 404)

    private suspend fun resolveAll(id: String): String {
        if (!sharded) return file
        shardOf[id]?.let { return it }
        for (p in shardsFor(null)) {
            val l = Gh.readArr(p)
            l.forEach { shardOf[it.id] = p }
            if (l.any { it.id == id }) return p
        }
        throw ApiException("Не найдено", 404)
    }

    suspend fun create(data: Rec): Rec {
        val now = nowIso()
        val rec = JsonObject(data + mapOf("id" to JsonPrimitive(newId()), "created_date" to JsonPrimitive(now), "updated_date" to JsonPrimitive(now)))
        val path = if (sharded) shardPath(rec.s("chat_id").ifBlank { "misc" }) else file
        Gh.modify(path, "[]") { text ->
            var l = Gh.parseArr(text)
            if (name == "Call") {
                val cutoff = System.currentTimeMillis() - 2 * 86_400_000L
                l = l.filter { parseIso(it.s("created_date")) > cutoff }
            }
            serialize(l + rec) to Unit
        }
        if (sharded) shardOf[rec.id] = path
        return rec
    }

    suspend fun update(id: String, data: Rec): Rec {
        val path = resolveAll(id)
        return Gh.modify(path, "[]") { text ->
            val l = Gh.parseArr(text)
            val i = l.indexOfFirst { it.id == id }
            if (i < 0) throw ApiException("Не найдено", 404)
            val merged = JsonObject(l[i] + data + mapOf("id" to JsonPrimitive(id), "updated_date" to JsonPrimitive(nowIso())))
            serialize(l.toMutableList().also { it[i] = merged }) to merged
        }
    }

    suspend fun delete(id: String) {
        val path = try { resolveAll(id) } catch (e: ApiException) { return }
        Gh.modify(path, "[]") { text -> serialize(Gh.parseArr(text).filter { it.id != id }) to Unit }
        shardOf.remove(id)
    }

    suspend fun deleteMany(q: Rec) {
        if (sharded) {
            val chat = (q["chat_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (chat != null && q.size == 1) { Gh.remove(shardPath(chat)); return }
            for (p in shardsFor(q)) Gh.modify(p, "[]") { text -> serialize(Gh.parseArr(text).filter { !matches(it, q) }) to Unit }
            return
        }
        Gh.modify(file, "[]") { text -> serialize(Gh.parseArr(text).filter { !matches(it, q) }) to Unit }
    }
}

/** One file per user: db/User/<id>.json. Includes private auth fields. */
class UserEntity {
    private fun path(id: String) = "db/User/$id.json"

    fun clean(r: Rec): Rec = JsonObject(r.filterKeys { it != "password_hash" && it != "password_salt" })

    suspend fun readRaw(id: String): Rec? =
        Gh.read(path(id))?.let { try { AppJson.parseToJsonElement(it.text) as? JsonObject } catch (e: Exception) { null } }

    suspend fun get(id: String): Rec = clean(readRaw(id) ?: throw ApiException("Пользователь не найден", 404))

    suspend fun list(sort: String? = null, limit: Int? = null): List<Rec> {
        val names = Gh.listDir("db/User").filter { it.endsWith(".json") && !it.startsWith("_") }
        val all = names.mapNotNull { n -> readRaw(n.removeSuffix(".json"))?.let { clean(it) } }
        return sortList(all, sort).let { if (limit != null) it.take(limit) else it }
    }

    suspend fun update(id: String, data: Rec): Rec {
        val safe = JsonObject(data.filterKeys { it != "id" && it != "password_hash" && it != "password_salt" })
        return Gh.modify(path(id), "{}") { text ->
            val cur = try { AppJson.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
                ?: throw ApiException("Пользователь не найден", 404)
            if (cur.s("id").isBlank()) throw ApiException("Пользователь не найден", 404)
            val merged = JsonObject(cur + safe + mapOf("updated_date" to JsonPrimitive(nowIso())))
            merged.toString() to clean(merged)
        }
    }

    suspend fun delete(id: String) {
        val u = readRaw(id)
        Gh.remove(path(id))
        val email = u?.s("email").orEmpty()
        if (email.isNotBlank()) Gh.modify("db/UserIndex.json", "{}") { text ->
            val idx = try { AppJson.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: JsonObject(emptyMap())
            JsonObject(idx.filterKeys { it != email }).toString() to Unit
        }
    }
}

object E {
    val Chat = Entity("Chat")
    val Message = Entity("Message", sharded = true)
    val Profile = Entity("Profile")
    val Call = Entity("Call")
    val PromoCode = Entity("PromoCode")
    val AppConfig = Entity("AppConfig")
    val User = UserEntity()
}

// ------------------------------------------------------------------ auth (accounts live in the repo)

object AuthApi {
    private const val INDEX = "db/UserIndex.json"
    private const val ITER = 60_000

    private fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITER, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun index(): JsonObject {
        val f = Gh.read(INDEX, fresh = true) ?: return JsonObject(emptyMap())
        return try { AppJson.parseToJsonElement(f.text) as? JsonObject ?: JsonObject(emptyMap()) } catch (e: Exception) { JsonObject(emptyMap()) }
    }

    /** Creates an account and returns the new user id (used as session token). */
    suspend fun register(email: String, password: String): String {
        val e = email.trim().lowercase()
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e)) throw ApiException("Некорректный email")
        if (password.length < 6) throw ApiException("Пароль должен быть не короче 6 символов")
        if (!Gh.configured()) throw ApiException("Не настроен репозиторий данных")
        val id = UUID.randomUUID().toString().replace("-", "").take(24)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val now = nowIso()
        val isFirst = Gh.modify(INDEX, "{}") { text ->
            val idx = try { AppJson.parseToJsonElement(text) as? JsonObject } catch (ex: Exception) { null } ?: JsonObject(emptyMap())
            if (idx.has(e)) throw ApiException("Пользователь с таким email уже существует", 409)
            JsonObject(idx + (e to JsonPrimitive(id))).toString() to idx.isEmpty()
        }
        val user = obj(
            "id" to id, "email" to e, "role" to (if (isFirst) "admin" else "user"), "is_owner" to isFirst,
            "password_salt" to Base64.encodeToString(salt, Base64.NO_WRAP), "password_hash" to hash(password, salt),
            "created_date" to now, "updated_date" to now,
        )
        Gh.modify("db/User/$id.json", "") { _ -> user.toString() to Unit }
        return id
    }

    suspend fun login(email: String, password: String): String {
        if (!Gh.configured()) throw ApiException("Не настроен репозиторий данных")
        val e = email.trim().lowercase()
        val id = (index()[e] as? JsonPrimitive)?.content ?: throw ApiException("Неверный email или пароль", 401)
        val u = E.User.readRaw(id) ?: throw ApiException("Неверный email или пароль", 401)
        val salt = Base64.decode(u.s("password_salt"), Base64.DEFAULT)
        if (!MessageDigest.isEqual(hash(password, salt).toByteArray(), u.s("password_hash").toByteArray()))
            throw ApiException("Неверный email или пароль", 401)
        Api.token = id
        return id
    }

    suspend fun changePassword(userId: String, current: String, new: String) {
        if (new.length < 6) throw ApiException("Пароль должен быть не короче 6 символов")
        val u = E.User.readRaw(userId) ?: throw ApiException("Пользователь не найден", 404)
        val oldSalt = Base64.decode(u.s("password_salt"), Base64.DEFAULT)
        if (hash(current, oldSalt) != u.s("password_hash")) throw ApiException("Текущий пароль неверен", 401)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Gh.modify("db/User/$userId.json", "{}") { text ->
            val cur = AppJson.parseToJsonElement(text) as JsonObject
            JsonObject(cur + mapOf("password_salt" to JsonPrimitive(Base64.encodeToString(salt, Base64.NO_WRAP)), "password_hash" to JsonPrimitive(hash(new, salt)))).toString() to Unit
        }
    }

    suspend fun me(): Rec {
        val id = Api.token ?: throw ApiException("Не авторизован", 401)
        return try { E.User.get(id) } catch (e: ApiException) {
            if (e.status == 404) throw ApiException("Не авторизован", 401) else throw e
        }
    }

    suspend fun updateMe(data: Rec): Rec {
        val id = Api.token ?: throw ApiException("Не авторизован", 401)
        return E.User.update(id, data)
    }
}
