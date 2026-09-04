package com.asman35.tiktokvideodownloader.data

import retrofit2.http.Body
import retrofit2.http.POST

data class ResolveRequest(val url: String)

data class ResolveResponse(
    val downloadUrl: String,
    val fileName: String? = null
)

interface DownloaderApi {
    @POST("resolve")
    suspend fun resolve(@Body request: ResolveRequest): ResolveResponse
}
