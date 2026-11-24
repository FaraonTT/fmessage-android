package com.fworld.fmessage.music

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val videoId: String,
) {
    fun toRec(): Rec = obj(
        "id" to id, "title" to title, "artist" to artist, "artwork" to artwork,
        "videoId" to videoId, "source" to "YouTube",
        "youtubeUrl" to "https://www.youtube.com/watch?v=$videoId",
    )

    fun toMessage(): String = Msg.encode(
        Msg.MUSIC,
        obj(
            "title" to title, "artist" to artist, "artwork" to artwork, "videoId" to videoId,
            "audioUrl" to "", "youtubeUrl" to "https://www.youtube.com/watch?v=$videoId",
            "isFull" to true, "source" to "YouTube",
        ),
    )

    companion object {
        fun fromRec(o: Rec): Track? {
            val vid = o.s("videoId")
            if (vid.isBlank()) return null
            return Track(
                o.s("id").ifBlank { "yt-$vid" }, o.s("title").ifBlank { "Без названия" }, o.s("artist"),
                o.s("artwork").ifBlank { "https://img.youtube.com/vi/$vid/hqdefault.jpg" }, vid,
            )
        }
    }
}

class MusicController(private val app: Application, private val vm: AppVM) {
    val current = MutableStateFlow<Track?>(null)
    val playing = MutableStateFlow(false)
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val saved = MutableStateFlow<List<Track>>(emptyList())

    private var me: Rec? = null
    private var web: WebView? = null
    private var ready = false
    private var pendingId: String? = null
    private val main = Handler(Looper.getMainLooper())

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://pipedapi.leptons.xyz",
        "https://pipedapi.r4fo.com", "https://api.piped.private.coffee", "https://pipedapi.reallyaweso.me",
    )
    private val invidiousInstances = listOf(
        "https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://inv.tux.pizza",
        "https://invidious.privacyredirect.net", "https://invidious.private.coffee",
    )
    private val fast = Api.http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()

    fun attach(u: Rec) {
        me = u
        saved.value = try {
            u.jsonArr("saved_tracks").mapNotNull { (it as? JsonObject)?.let { o -> Track.fromRec(o) } }
        } catch (e: Exception) { emptyList() }
    }

    // ---------- player view ----------

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(ctx: android.content.Context): WebView {
        web?.let { return it }
        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = WebChromeClient()
        w.webViewClient = WebViewClient()
        w.addJavascriptInterface(Bridge(), "Android")
        w.loadDataWithBaseURL("https://www.youtube.com", HTML, "text/html", "utf-8", null)
        web = w
        return w
    }

    private inner class Bridge {
        @JavascriptInterface fun onReady() {
            ready = true
            main.post {
                pendingId?.let { js("load('$it')") }
                pendingId = null
                js("setVol(${(me?.i("music_volume", 80) ?: 80).coerceIn(0, 100)})")
            }
        }
        @JavascriptInterface fun onState(s: Int) {
            main.post {
                when (s) {
                    1 -> playing.value = true
                    2 -> playing.value = false
                    0 -> { playing.value = false; if (me?.b("music_auto_next", true) != false) next() }
                }
            }
        }
        @JavascriptInterface fun onError(code: Int) { main.post { playing.value = false; next() } }
    }

    private fun js(code: String) { web?.evaluateJavascript(code, null) }

    // ---------- controls ----------

    fun play(t: Track, list: List<Track> = listOf(t)) {
        queue.value = if (list.any { it.id == t.id }) list else listOf(t)
        current.value = t
        playing.value = true
        if (ready) js("load('${t.videoId}')") else pendingId = t.videoId
    }

    fun toggle() {
        if (current.value == null) return
        if (playing.value) js("pauseV()") else js("playV()")
        playing.value = !playing.value
    }

    fun next() {
        val q = queue.value
        val c = current.value ?: return
        val i = q.indexOfFirst { it.id == c.id }
        if (i >= 0 && i + 1 < q.size) play(q[i + 1], q)
    }

    fun prev() {
        val q = queue.value
        val c = current.value ?: return
        val i = q.indexOfFirst { it.id == c.id }
        if (i > 0) play(q[i - 1], q)
    }

    fun stop() {
        js("pauseV()")
        playing.value = false
        current.value = null
        queue.value = emptyList()
    }

    // ---------- saved ----------

    fun isSaved(t: Track) = saved.value.any { it.id == t.id }

    fun toggleSaved(t: Track) {
        val limit = Plans.limits(me).savedTracks
        val list = if (isSaved(t)) saved.value.filter { it.id != t.id } else {
            if (saved.value.size >= limit) { vm.toast("Лимит: $limit треков. Активируйте fPro/fPremium."); return }
            saved.value + t
        }
        saved.value = list
        vm.updateMe("saved_tracks" to toJson(list.map { it.toRec() }).toString())
    }

    // ---------- search ----------

    suspend fun search(query: String, limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        for (inst in pipedInstances) {
            try {
                val text = fetch("$inst/search?q=$q&filter=videos") ?: continue
                val items = ((AppJson.parseToJsonElement(text) as? JsonObject)?.get("items") as? JsonArray) ?: continue
                val res = items.mapNotNull { it as? JsonObject }
                    .filter { it.s("type") == "stream" && it.s("url").contains("/watch?v=") }
                    .take(limit)
                    .map {
                        val vid = it.s("url").substringAfter("/watch?v=").substringBefore("&")
                        Track("yt-$vid", it.s("title"), it.s("uploaderName"), "https://img.youtube.com/vi/$vid/hqdefault.jpg", vid)
                    }
                if (res.isNotEmpty()) return@withContext res
            } catch (ignored: Exception) {}
        }
        for (inst in invidiousInstances) {
            try {
                val text = fetch("$inst/api/v1/search?q=$q&type=video") ?: continue
                val arr = AppJson.parseToJsonElement(text) as? JsonArray ?: continue
                val res = arr.mapNotNull { it as? JsonObject }.filter { it.s("videoId").isNotBlank() }.take(limit).map {
                    val vid = it.s("videoId")
                    Track("yt-$vid", it.s("title"), it.s("author"), "https://img.youtube.com/vi/$vid/hqdefault.jpg", vid)
                }
                if (res.isNotEmpty()) return@withContext res
            } catch (ignored: Exception) {}
        }
        emptyList()
    }

    private fun fetch(url: String): String? {
        fast.newCall(Request.Builder().url(url).build()).execute().use { r ->
            return if (r.isSuccessful) r.body?.string() else null
        }
    }

    companion object {
        val GENRES = listOf("Хиты 2026", "Рок", "Поп", "Хип-хоп", "Электроника", "Джаз", "Классика", "Lo-fi", "Русский рэп", "Инди")

        private const val HTML = """
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;background:#000"><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player=null, ready=false, pending=null;
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{height:'100%',width:'100%',
    playerVars:{playsinline:1,controls:0,autoplay:1,rel:0},
    events:{
      onReady:function(){ready=true;Android.onReady();if(pending){player.loadVideoById(pending);pending=null;}},
      onStateChange:function(e){Android.onState(e.data);},
      onError:function(e){Android.onError(e.data);}
    }});
}
function load(id){ if(ready){player.loadVideoById(id);}else{pending=id;} }
function playV(){ if(ready) player.playVideo(); }
function pauseV(){ if(ready) player.pauseVideo(); }
function setVol(v){ if(ready) player.setVolume(v); }
</script></body></html>
"""
    }
}
