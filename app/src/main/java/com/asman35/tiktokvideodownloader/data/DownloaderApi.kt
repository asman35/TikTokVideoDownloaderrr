package com.asman35.tiktokvideodownloader.data

import retrofit2.http.Body
import retrofit2.http.POST

data class ResolveRequest(val url: String)

enum class MediaType { VIDEO, IMAGE }

data class MediaItem(
    val downloadUrl: String,
    val fileName: String? = null,
    val type: MediaType = MediaType.VIDEO,
    val title: String? = null,
    val description: String? = null
)

data class ResolveResponse(
    val platform: String = "unknown",
    val title: String? = null,
    val description: String? = null,
    val items: List<MediaItem> = emptyList()
)

interface DownloaderApi {
    @POST("resolve")
    suspend fun resolve(@Body request: ResolveRequest): ResolveResponse
}
