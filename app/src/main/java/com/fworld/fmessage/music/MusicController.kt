package com.fworld.fmessage.music

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** A track from the device's own music library. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val uri: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
) {
    fun toRec(): Rec = obj("id" to id, "title" to title, "artist" to artist, "album" to album)

    /** Shared into chats as a card (the file itself stays on the device). */
    fun toMessage(): String = Msg.encode(
        Msg.MUSIC,
        obj("title" to title, "artist" to artist, "album" to album, "artwork" to "", "source" to "device"),
    )

    companion object {
        fun fromRec(o: Rec): Track? {
            val t = o.s("title")
            if (t.isBlank()) return null
            return Track(o.s("id").ifBlank { "msg-$t" }, t, o.s("artist"), "", "", o.s("album"))
        }
    }
}

class MusicController(private val app: Application, private val vm: AppVM) {
    val library = MutableStateFlow<List<Track>>(emptyList())
    val scanning = MutableStateFlow(false)
    val current = MutableStateFlow<Track?>(null)
    val playing = MutableStateFlow(false)
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val saved = MutableStateFlow<List<Track>>(emptyList())
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val shuffle = MutableStateFlow(false)

    /** 0 = off, 1 = whole queue, 2 = current track. */
    val repeat = MutableStateFlow(0)

    private val prefs = app.getSharedPreferences("fm_music", Context.MODE_PRIVATE)
    private var favs: Set<String> = prefs.getStringSet("favs", null)?.toSet() ?: emptySet()
    private var me: Rec? = null
    private var mp: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    fun attach(u: Rec) {
        me = u
        refreshSaved()
        if (hasPermission() && library.value.isEmpty()) scan()
    }

    // ---------- library scan ----------

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, permission()) == PackageManager.PERMISSION_GRANTED

    fun scan() {
        if (!hasPermission() || scanning.value) return
        scanning.value = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { query() }
            library.value = list
            scanning.value = false
            refreshSaved()
        }
    }

    private fun query(): List<Track> {
        val out = mutableListOf<Track>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        try {
            app.contentResolver.query(base, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val artist = c.getString(iArtist).orEmpty().let { if (it.isBlank() || it == "<unknown>") "Неизвестный исполнитель" else it }
                    out.add(
                        Track(
                            id = "l$id",
                            title = c.getString(iTitle).orEmpty().ifBlank { "Без названия" },
                            artist = artist,
                            artwork = "content://media/external/audio/albumart/${c.getLong(iAlbumId)}",
                            uri = ContentUris.withAppendedId(base, id).toString(),
                            album = c.getString(iAlbum).orEmpty(),
                            durationMs = c.getLong(iDur),
                        )
                    )
                }
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    suspend fun search(query: String, limit: Int = 30): List<Track> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return library.value.take(limit)
        return library.value.filter { (it.title + " " + it.artist + " " + it.album).lowercase().contains(q) }.take(limit)
    }

    // ---------- playback ----------

    fun play(t: Track, list: List<Track> = listOf(t)) {
        val target = if (t.uri.isNotBlank()) t else
            library.value.firstOrNull { it.title.equals(t.title, true) && it.artist.equals(t.artist, true) }
                ?: run { vm.toast("Этого трека нет на устройстве"); return }
        queue.value = if (list.any { it.id == target.id }) list else listOf(target)
        start(target)
    }

    fun playAll(list: List<Track>, shuffled: Boolean) {
        if (list.isEmpty()) return
        shuffle.value = shuffled
        play(if (shuffled) list.random() else list.first(), list)
    }

    private fun volume(): Float = (me?.i("music_volume", 80) ?: 80).coerceIn(0, 100) / 100f

    private fun start(t: Track) {
        release()
        current.value = t
        position.value = 0L
        duration.value = t.durationMs
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            p.setDataSource(app, Uri.parse(t.uri))
            val v = volume()
            p.setVolume(v, v)
            p.setOnPreparedListener {
                it.start()
                playing.value = true
                duration.value = it.duration.toLong()
                startTicker()
            }
            p.setOnCompletionListener { onCompleted() }
            p.setOnErrorListener { _, _, _ ->
                playing.value = false
                vm.toast("Не удалось воспроизвести трек")
                true
            }
            p.prepareAsync()
            mp = p
        } catch (e: Exception) {
            playing.value = false
            vm.toast("Не удалось воспроизвести трек")
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = mp
                if (p != null && playing.value) {
                    try { position.value = p.currentPosition.toLong() } catch (ignored: Exception) {}
                }
                delay(400)
            }
        }
    }

    fun toggle() {
        val p = mp
        if (p == null) {
            current.value?.let { start(it) }
            return
        }
        try {
            if (p.isPlaying) { p.pause(); playing.value = false } else { p.start(); playing.value = true }
        } catch (ignored: Exception) {
        }
    }

    fun next(auto: Boolean = false) {
        val q = queue.value
        if (q.isEmpty()) return
        val c = current.value
        val i = q.indexOfFirst { it.id == c?.id }
        val nxt = when {
            shuffle.value && q.size > 1 -> q.filter { it.id != c?.id }.random()
            i + 1 < q.size -> q[i + 1]
            repeat.value == 1 || !auto -> q.first()
            else -> null
        }
        if (nxt != null) start(nxt) else playing.value = false
    }

    fun prev() {
        if (position.value > 3000) { seekTo(0); return }
        val q = queue.value
        if (q.isEmpty()) return
        val i = q.indexOfFirst { it.id == current.value?.id }
        start(if (i > 0) q[i - 1] else q.last())
    }

    private fun onCompleted() {
        if (repeat.value == 2) current.value?.let { start(it) } else next(auto = true)
    }

    fun seekTo(ms: Long) {
        try { mp?.seekTo(ms.toInt()) } catch (ignored: Exception) {}
        position.value = ms
    }

    fun toggleShuffle() { shuffle.value = !shuffle.value }
    fun cycleRepeat() { repeat.value = (repeat.value + 1) % 3 }

    fun stop() {
        release()
        playing.value = false
        current.value = null
        queue.value = emptyList()
        position.value = 0L
    }

    private fun release() {
        ticker?.cancel()
        try { mp?.release() } catch (ignored: Exception) {}
        mp = null
    }

    // ---------- favorites (stored on this device) ----------

    fun isSaved(t: Track) = favs.contains(t.id)

    fun toggleSaved(t: Track) {
        favs = if (favs.contains(t.id)) favs - t.id else favs + t.id
        prefs.edit().putStringSet("favs", HashSet(favs)).apply()
        refreshSaved()
    }

    private fun refreshSaved() {
        saved.value = library.value.filter { it.id in favs }
    }

    companion object {
        fun permission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
