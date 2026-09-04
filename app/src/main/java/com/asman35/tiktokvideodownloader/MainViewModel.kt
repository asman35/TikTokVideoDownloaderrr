package com.asman35.tiktokvideodownloader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asman35.tiktokvideodownloader.data.DownloaderRepository
import com.asman35.tiktokvideodownloader.data.ResolveResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

data class DownloadUiState(
    val link: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val resolvedVideo: ResolveResponse? = null
)

class MainViewModel(
    private val repository: DownloaderRepository = DownloaderRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    fun onLinkChanged(link: String) = _uiState.update {
        it.copy(link = link, message = null, resolvedVideo = null)
    }

    fun resolveLink() {
        val link = _uiState.value.link.trim()
        if (!isTikTokLink(link)) {
            _uiState.update { it.copy(message = "Geçerli bir TikTok bağlantısı girin.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(link = "", isLoading = true, message = "Video hazırlanıyor…")
            }
            runCatching { repository.resolve(link) }
                .onSuccess { video ->
                    _uiState.update {
                        it.copy(isLoading = false, message = null, resolvedVideo = video)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = error.message ?: "Video bağlantısı alınamadı."
                        )
                    }
                }
        }
    }

    fun downloadStarted() = _uiState.update {
        it.copy(
            link = "",
            message = "İndirme başlatıldı. Bildirimlerden takip edebilirsiniz.",
            resolvedVideo = null
        )
    }

    private fun isTikTokLink(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in listOf("http", "https") &&
            (uri.host?.lowercase()?.let { it == "tiktok.com" || it.endsWith(".tiktok.com") } == true)
    }.getOrDefault(false)
}
