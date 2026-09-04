package com.asman35.tiktokvideodownloader.data

import com.asman35.tiktokvideodownloader.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class DownloaderRepository {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val api: DownloaderApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(DownloaderApi::class.java)

    suspend fun resolve(url: String): ResolveResponse =
        api.resolve(ResolveRequest(url))
}
