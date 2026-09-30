package com.asman35.tiktokvideodownloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

object YoutubeLocalDownloader {
    private val initialized = AtomicBoolean(false)
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    suspend fun download(
        context: Context,
        url: String,
        title: String?
    ): String = withContext(Dispatchers.IO) {
        ensureInitialized()

        val info = StreamInfo.getInfo(url)
        val stream = pickProgressiveMp4(info.videoStreams)
            ?: throw IllegalStateException(
                "Bu videoda telefondan indirilebilen birleşik MP4 akışı bulunamadı."
            )

        val streamUrl = stream.content
        if (!stream.isUrl || !streamUrl.startsWith("http")) {
            throw IllegalStateException("YouTube MP4 bağlantısı alınamadı.")
        }

        val actualTitle = info.name?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: "YouTube Video"
        val displayName = "${sanitizeFileName(actualTitle)}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/MediaSave"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Video galeriye kaydedilemedi.")

        try {
            val connection = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "keep-alive")
            }

            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException(
                        "YouTube video akışı alınamadı: HTTP ${connection.responseCode}"
                    )
                }

                resolver.openOutputStream(uri, "w")?.use { out ->
                    BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            out.write(buffer, 0, read)
                            total += read
                        }
                        out.flush()
                        if (total < 100_000L) {
                            throw IllegalStateException("İndirilen MP4 dosyası geçersiz veya çok küçük.")
                        }
                    }
                } ?: throw IllegalStateException("Video dosyasına yazılamadı.")
            } finally {
                connection.disconnect()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, ready, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        displayName
    }

    private fun pickProgressiveMp4(streams: List<VideoStream>): VideoStream? =
        streams
            .asSequence()
            .filter { !it.isVideoOnly }
            .filter { it.isUrl }
            .filter { it.format == MediaFormat.MPEG_4 }
            .maxByOrNull { it.height }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(MediaSaveDownloader())
        }
    }

    private fun sanitizeFileName(value: String): String =
        value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "YouTube Video" }

    private class MediaSaveDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                requestMethod = request.httpMethod()
                setRequestProperty("User-Agent", UA)
                request.headers().forEach { (name, values) ->
                    values.forEach { value -> addRequestProperty(name, value) }
                }
                val data = request.dataToSend()
                if (data != null) {
                    doOutput = true
                    outputStream.use { it.write(data) }
                }
            }

            return try {
                val code = connection.responseCode
                val body = if (request.httpMethod() == "HEAD") {
                    ""
                } else {
                    val input = if (code >= 400) connection.errorStream else connection.inputStream
                    if (input == null) "" else BufferedReader(InputStreamReader(input)).use { it.readText() }
                }
                val headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                Response(
                    code,
                    connection.responseMessage ?: "",
                    headers,
                    body,
                    connection.url.toString()
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}
