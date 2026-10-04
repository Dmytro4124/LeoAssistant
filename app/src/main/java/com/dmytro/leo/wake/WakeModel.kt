package com.dmytro.leo.wake

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** The offline Ukrainian Vosk model (~78 MB), downloaded once into the app's files. */
class WakeModel(context: Context, private val httpClient: OkHttpClient) {

    val directory = File(context.applicationContext.filesDir, "vosk-uk")
    private val readyMarker = File(directory, ".ready")

    val isReady: Boolean get() = readyMarker.exists()

    /** Downloads and unpacks the model; [onProgress] gets 0..1. Throws IOException on failure. */
    suspend fun download(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(URL).build()
        // A big file: no overall time limit, only the usual connect/read timeouts
        httpClient.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: APPROX_SIZE
                directory.deleteRecursively()
                directory.mkdirs()

                val counting = object : FilterInputStream(body.byteStream()) {
                    var read = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { if (it > 0) { read += it; onProgress((read.toFloat() / total).coerceAtMost(1f)) } }
                }
                ZipInputStream(counting).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // Drop the top folder ("vosk-model-small-uk-v3-nano/…") and refuse paths escaping the folder
                        val relative = entry.name.substringAfter('/', "")
                        if (relative.isEmpty() || relative.contains("..")) continue
                        val target = File(directory, relative)
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
        readyMarker.writeText("ok")
    }

    private companion object {
        const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip"
        const val APPROX_SIZE = 77_622_640L
    }
}
