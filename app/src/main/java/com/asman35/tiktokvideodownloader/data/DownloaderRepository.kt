package com.asman35.tiktokvideodownloader.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

private data class TikWmResponse(
    val code: Int? = null,
    val msg: String? = null,
    val data: TikWmData? = null
)

private data class TikWmData(
    val play: String? = null,
    val hdplay: String? = null,
    val title: String? = null
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

    private val api: TikWmApi = Retrofit.Builder()
        .baseUrl("https://www.tikwm.com/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TikWmApi::class.java)

    suspend fun resolve(url: String): ResolveResponse {
        val response = api.resolve(url)

        val video = response.data
            ?: error(response.msg ?: "Video bilgileri alınamadı.")

        val downloadUrl = video.hdplay
            ?.takeIf { it.isNotBlank() }
            ?: video.play?.takeIf { it.isNotBlank() }
            ?: error("İndirilebilir video bağlantısı bulunamadı.")

        return ResolveResponse(
            downloadUrl = downloadUrl,
            fileName = "tiktok_${System.currentTimeMillis()}.mp4"
        )
    }
}
