package com.asman35.tiktokvideodownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaGalleryDownloader {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    suspend fun downloadVideo(
        context: Context,
        url: String,
        title: String?
    ): String = withContext(Dispatchers.IO) {
        val displayName = ensureMp4FileName(
            sanitizeFileName(title?.takeIf { it.isNotBlank() } ?: "MediaSave Video")
        )

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
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Video galeriye kaydedilemedi.")

        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 45_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "keep-alive")
            }

            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Video indirilemedi: HTTP ${connection.responseCode}")
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
                            throw IllegalStateException("İndirilen video dosyası geçersiz veya çok küçük.")
                        }
                    }
                } ?: throw IllegalStateException("Video dosyasına yazılamadı.")
            } finally {
                connection.disconnect()
            }

            validateVideo(context, uri)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, ready, null, null)
            }

            triggerMediaScan(context, displayName)
            displayName
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun triggerMediaScan(context: Context, fileName: String) {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            .resolve("MediaSave")
            .resolve(fileName)
            .absolutePath

        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(path),
            arrayOf("video/mp4"),
            null
        )
    }

    private fun validateVideo(context: Context, uri: android.net.Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                ?.equals("yes", ignoreCase = true) == true
            if (duration <= 0L || !hasVideo) {
                throw IllegalStateException("İndirilen dosya geçerli bir video değil.")
            }
        } finally {
            retriever.release()
        }
    }

    private fun sanitizeFileName(value: String): String =
        value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "MediaSave Video" }

    private fun ensureMp4FileName(value: String): String =
        if (value.lowercase().endsWith(".mp4")) value else "$value.mp4"
}
