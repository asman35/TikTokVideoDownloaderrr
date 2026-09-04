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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asman35.tiktokvideodownloader.data.ResolveResponse

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { DownloaderScreen() }
            }
        }
    }
}

@Composable
private fun DownloaderScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.resolvedVideo) {
        state.resolvedVideo?.let {
            enqueueDownload(context, it)
            viewModel.downloadStarted()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TikTok Video İndirici", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Canım Babam",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text("TikTok bağlantısını yapıştırın ve videoyu cihazınıza indirin.")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.link,
            onValueChange = viewModel::onLinkChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("TikTok bağlantısı") },
            placeholder = { Text("https://www.tiktok.com/@.../video/...") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Videoyu İndir")
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

private fun enqueueDownload(context: Context, video: ResolveResponse) {
    val safeName = video.fileName
        ?.takeIf { it.endsWith(".mp4", ignoreCase = true) }
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?: "tiktok_${System.currentTimeMillis()}.mp4"
    val request = DownloadManager.Request(Uri.parse(video.downloadUrl))
        .setTitle(safeName)
        .setDescription("TikTok videosu indiriliyor")
        .setMimeType("video/mp4")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
        .setAllowedOverMetered(true)

    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
}
