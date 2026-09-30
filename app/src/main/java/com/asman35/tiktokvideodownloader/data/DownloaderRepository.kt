package com.asman35.tiktokvideodownloader.data

import com.asman35.tiktokvideodownloader.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.net.URI

private data class TikWmResponse(
    val code: Int? = null,
    val msg: String? = null,
    val data: TikWmData? = null
)

private data class TikWmData(
    val play: String? = null,
    val hdplay: String? = null,
    val title: String? = null,
    val images: List<String>? = null
)

private interface TikWmApi {
    @FormUrlEncoded
    @POST("api/")
    suspend fun resolve(
        @Field("url") url: String,
        @Field("hd") hd: Int = 1
    ): TikWmResponse
}

class DownloaderRepository {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val tikWmApi: TikWmApi = Retrofit.Builder()
        .baseUrl("https://www.tikwm.com/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TikWmApi::class.java)

    suspend fun resolve(url: String): ResolveResponse {
        return when (detectPlatform(url)) {
            "tiktok" -> resolveTikTok(url)
            "instagram", "youtube", "twitter" -> resolveWithServer(url)
            else -> error("Desteklenen bir TikTok, Instagram, YouTube veya X bağlantısı girin.")
        }
    }

    private suspend fun resolveWithServer(url: String): ResolveResponse {
        if (BuildConfig.API_BASE_URL.contains("example.com")) {
            error("Instagram/YouTube/X sunucusu henüz bağlanmadı.")
        }

        val api = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DownloaderApi::class.java)

        return try {
            api.resolve(ResolveRequest(url))
        } catch (error: HttpException) {
            val raw = error.response()?.errorBody()?.string().orEmpty()
            val detail = Regex("\\\"detail\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(raw)?.groupValues?.getOrNull(1)
                ?.replace("\\u0131", "ı")
                ?.replace("\\u011f", "ğ")
                ?.replace("\\u015f", "ş")
                ?.replace("\\u00fc", "ü")
                ?.replace("\\u00f6", "ö")
                ?.replace("\\u00e7", "ç")
            error(detail ?: "Sunucu hatası: HTTP ${error.code()}")
        }
    }

    private suspend fun resolveTikTok(url: String): ResolveResponse {
        val response = tikWmApi.resolve(url)
        val data = response.data ?: error(response.msg ?: "İçerik bilgileri alınamadı.")

        val imageItems = data.images.orEmpty()
            .filter { it.isNotBlank() }
            .mapIndexed { index, item ->
                MediaItem(
                    downloadUrl = item,
                    fileName = data.title?.takeIf { it.isNotBlank() }?.let { "${it}_${index + 1}.jpg" }
                        ?: "tiktok_${System.currentTimeMillis()}_${index + 1}.jpg",
                    type = MediaType.IMAGE,
                    title = data.title,
                    description = data.title
                )
            }

        if (imageItems.isNotEmpty()) {
            return ResolveResponse(
                platform = "TikTok",
                title = data.title,
                description = data.title,
                items = imageItems
            )
        }

        val videoUrl = data.hdplay
            ?.takeIf { it.isNotBlank() }
            ?: data.play?.takeIf { it.isNotBlank() }
            ?: error("İndirilebilir medya bulunamadı.")

        return ResolveResponse(
            platform = "TikTok",
            title = data.title,
            description = data.title,
            items = listOf(
                MediaItem(
                    downloadUrl = videoUrl,
                    fileName = data.title?.takeIf { it.isNotBlank() }?.let { "${it}.mp4" }
                        ?: "tiktok_${System.currentTimeMillis()}.mp4",
                    type = MediaType.VIDEO,
                    title = data.title,
                    description = data.title
                )
            )
        )
    }

    fun detectPlatform(value: String): String = runCatching {
        val uri = URI(value)
        if (uri.scheme !in listOf("http", "https")) return@runCatching "unknown"
        val host = uri.host?.lowercase().orEmpty()
        when {
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "tiktok"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "instagram"
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> "youtube"
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") -> "twitter"
            else -> "unknown"
        }
    }.getOrDefault("unknown")
}
