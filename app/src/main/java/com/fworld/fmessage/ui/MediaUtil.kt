package com.fworld.fmessage.ui

import android.content.ContentResolver
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.fworld.fmessage.data.Api
import com.fworld.fmessage.data.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File

class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

suspend fun readUri(ctx: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    try {
        val cr: ContentResolver = ctx.contentResolver
        var name = "file"
        cr.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: "file"
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        PickedFile(bytes, name, mime)
    } catch (e: Exception) { null }
}

fun bitmapToJpeg(bmp: android.graphics.Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}

/** Headers needed to fetch media from the private data repo. */
fun mediaHeaders(url: String): Map<String, String> =
    if (url.contains("raw.githubusercontent.com") && Gh.tok.isNotBlank()) mapOf("Authorization" to "Bearer ${Gh.tok}") else emptyMap()

suspend fun downloadFile(ctx: Context, url: String, name: String): File? = withContext(Dispatchers.IO) {
    try {
        val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: ctx.cacheDir
        val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        Api.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body?.byteStream()?.use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        f
    } catch (e: Exception) { null }
}

class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = try {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r; file = f; startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) { rec = null; false }

    /** Stops recording; returns file and duration seconds, or null when cancelled/failed. */
    fun stop(keep: Boolean): Pair<File, Int>? {
        val r = rec ?: return null
        rec = null
        val dur = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        try { r.stop() } catch (e: Exception) { file?.delete(); return null } finally { r.release() }
        val f = file
        file = null
        if (!keep || f == null || dur < 1) { f?.delete(); return null }
        return f to dur
    }
}

class VoicePlayer(private val ctx: Context) {
    private var mp: MediaPlayer? = null
    var onDone: (() -> Unit)? = null

    fun play(url: String, onReady: () -> Unit = {}) {
        release()
        try {
            val p = MediaPlayer()
            p.setDataSource(ctx, Uri.parse(url), mediaHeaders(url))
            p.setOnPreparedListener { it.start(); onReady() }
            p.setOnCompletionListener { onDone?.invoke() }
            p.setOnErrorListener { _, _, _ -> onDone?.invoke(); true }
            p.prepareAsync()
            mp = p
        } catch (e: Exception) { onDone?.invoke() }
    }

    fun pause() { try { mp?.pause() } catch (ignored: Exception) {} }
    fun release() { try { mp?.release() } catch (ignored: Exception) {}; mp = null }
}
