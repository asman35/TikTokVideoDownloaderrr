package com.asman35.tiktokvideodownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asman35.tiktokvideodownloader.data.MediaItem
import com.asman35.tiktokvideodownloader.data.MediaType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DownloaderScreen()
                }
            }
        }
    }
}

@Composable
private fun DownloaderScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "MediaSave",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "TikTok • Instagram • YouTube",
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text("Bağlantıyı yapıştırın. Platform otomatik algılanır.")
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.link,
            onValueChange = viewModel::onLinkChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bağlantı") },
            placeholder = { Text("https://...") },
            singleLine = true,
            enabled = !state.isLoading
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::resolveLink,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading && state.link.isNotBlank()
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("İçeriği Bul")
            }
        }

        state.resolved?.let { resolved ->
            Spacer(Modifier.height(20.dp))
            Text(resolved.platform, fontWeight = FontWeight.SemiBold)

            resolved.title?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(12.dp))
            Text("${resolved.items.size} medya bulundu")
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    resolved.items.forEach { enqueueDownload(context, it) }
                    viewModel.downloadStarted(resolved.items.size)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = resolved.items.isNotEmpty()
            ) {
                Text(if (resolved.items.size > 1) "Tümünü İndir" else "İndir")
            }
        }

        state.message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Yalnızca indirme hakkına sahip olduğunuz içerikleri indirin.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun enqueueDownload(context: Context, media: MediaItem) {
    val isImage = media.type == MediaType.IMAGE
    val fallbackExt = if (isImage) "jpg" else "mp4"

    val safeName = media.fileName
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.takeIf { it.contains(".") }
        ?: "mediasave_${System.currentTimeMillis()}.$fallbackExt"

    val request = DownloadManager.Request(Uri.parse(media.downloadUrl))
        .setTitle(safeName)
        .setDescription("MediaSave indiriyor")
        .setMimeType(if (isImage) "image/jpeg" else "video/mp4")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
        .setAllowedOverMetered(true)

    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
        .enqueue(request)
}
