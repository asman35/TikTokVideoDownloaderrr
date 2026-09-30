package com.asman35.tiktokvideodownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YoutubeLocalDownloader {

    suspend fun download(
        context: Context,
        url: String,
        title: String?
    ): String = withContext(Dispatchers.IO) {
        YtDlp.init(context.applicationContext)

        val workDir = File(context.externalCacheDir ?: context.cacheDir, "mediasave_youtube")
        workDir.mkdirs()
        workDir.listFiles()?.forEach { it.delete() }

        val tempOutput = File(workDir, "youtube_%(id)s.%(ext)s").absolutePath
        val request = YtDlpRequest(url)
            .setOutputTemplate(tempOutput)
            .addOption("--no-playlist")
            .addOption("--no-part")
            .addOption(
                "-f",
                "best[ext=mp4][vcodec^=avc1][acodec^=mp4a]/best[ext=mp4]"
            )

        val response = YtDlp.execute(request, null)
        if (!response.isSuccess) {
            throw IllegalStateException("YouTube videosu telefondan indirilemedi.")
        }

        val source = workDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Geçerli MP4 dosyası oluşturulamadı.")

        if (source.length() < 100_000L || !isPlayableMp4(source)) {
            source.delete()
            throw IllegalStateException("İndirilen dosya geçerli bir MP4 video değil.")
        }

        val safeTitle = sanitizeFileName(title ?: "YouTube Video")
        val displayName = "${safeTitle}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/MediaSave"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Video galeriye kaydedilemedi.")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("Video dosyasına yazılamadı.")

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            } finally {
                source.delete()
            }
        } else {
            val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: throw IllegalStateException("Video klasörüne erişilemedi.")
            val targetDir = File(movies, "MediaSave").apply { mkdirs() }
            source.copyTo(File(targetDir, displayName), overwrite = true)
            source.delete()
        }

        displayName
    }

    private fun isPlayableMp4(file: File): Boolean {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val hasVideo = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    ?.equals("yes", ignoreCase = true) == true
                duration > 0L && hasVideo
            } finally {
                retriever.release()
            }
        }.getOrDefault(false)
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "YouTube Video" }
    }
}
