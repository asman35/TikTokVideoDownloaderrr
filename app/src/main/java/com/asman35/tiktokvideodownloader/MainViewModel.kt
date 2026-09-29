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

data class DownloadUiState(
    val link: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val resolved: ResolveResponse? = null
)

class MainViewModel(
    private val repository: DownloaderRepository = DownloaderRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    fun onLinkChanged(link: String) = _uiState.update {
        it.copy(link = link, message = null, resolved = null)
    }

    fun resolveLink() {
        val link = _uiState.value.link.trim()
        if (repository.detectPlatform(link) == "unknown") {
            _uiState.update { it.copy(message = "Desteklenen bir bağlantı girin.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, message = "İçerik hazırlanıyor…", resolved = null)
            }

            runCatching { repository.resolve(link) }
                .onSuccess { media ->
                    _uiState.update {
                        it.copy(isLoading = false, message = null, resolved = media)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = error.message ?: "İçerik bağlantısı alınamadı."
                        )
                    }
                }
        }
    }

    fun downloadStarted(count: Int) = _uiState.update {
        it.copy(
            link = "",
            message = if (count > 1) "$count dosya indirmeye eklendi." else "İndirme başlatıldı.",
            resolved = null
        )
    }
}
