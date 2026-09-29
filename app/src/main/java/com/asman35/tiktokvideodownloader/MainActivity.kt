package com.asman35.tiktokvideodownloader

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var whatsappMessage by remember { mutableStateOf<String?>(null) }

    val statusFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val count = saveWhatsappStatuses(context, uri)
                whatsappMessage = if (count > 0) {
                    "$count WhatsApp durum dosyası İndirilenler'e kaydedildi."
                } else {
                    "Bu klasörde fotoğraf veya video bulunamadı."
                }
            }.onFailure {
                whatsappMessage = it.message ?: "WhatsApp durumları kaydedilemedi."
            }
        }
    }

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
            "TikTok • Instagram • YouTube • WhatsApp",
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
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { statusFolderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("WhatsApp Durumlarını Kaydet")
        }

        whatsappMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "WhatsApp için .Statuses klasörünü seçin. Yalnızca erişim hakkınız olan içerikleri indirin.",
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

    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
}

private fun saveWhatsappStatuses(context: Context, treeUri: Uri): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        error("WhatsApp durum kaydetme Android 10 ve üzeri için etkin.")
    }

    val resolver = context.contentResolver
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

    var saved = 0
    resolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null,
        null,
        null
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

        while (cursor.moveToNext()) {
            val documentId = cursor.getString(idIndex)
            val displayName = cursor.getString(nameIndex) ?: continue
            val mime = cursor.getString(mimeIndex) ?: continue
            if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue

            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val collection = if (mime.startsWith("image/")) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "MediaSave_$displayName")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MediaSave")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val targetUri = resolver.insert(collection, values) ?: continue
            runCatching {
                resolver.openInputStream(sourceUri).use { input ->
                    resolver.openOutputStream(targetUri).use { output ->
                        if (input == null || output == null) error("Dosya açılamadı.")
                        input.copyTo(output)
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(targetUri, values, null, null)
                saved++
            }.onFailure {
                resolver.delete(targetUri, null, null)
            }
        }
    }
    return saved
}
