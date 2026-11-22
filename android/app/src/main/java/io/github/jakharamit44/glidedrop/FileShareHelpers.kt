package io.github.jakharamit44.glidedrop

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal fun resolveLocalFilePath(rawPath: String): String? {
    val trimmed = rawPath.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("file://", ignoreCase = true)) {
        return runCatching { Uri.parse(trimmed).path }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
    return trimmed
}

internal fun canOpenOrShareHistoryItem(item: ReceivedItem): Boolean {
    if (item.isUrl) return UriSecurity.isSafeWebUrl(item.pathOrUrl)
    if (item.pathOrUrl.startsWith("content://")) return true
    if (item.pathOrUrl.startsWith("file://") || item.pathOrUrl.startsWith("/")) {
        val localPath = resolveLocalFilePath(item.pathOrUrl) ?: return false
        val canonicalFile = runCatching { File(localPath).canonicalFile }.getOrNull() ?: return false
        val glideDropDir = runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GlideDrop").canonicalPath
        }.getOrNull() ?: return false
        return canonicalFile.exists() && UriSecurity.isPathInsideAnyRoot(canonicalFile.path, listOf(glideDropDir))
    }
    return false
}

internal fun openFileWithProviderAsync(
    scope: CoroutineScope,
    context: Context,
    path: String,
    fallback: () -> Unit
) {
    val appContext = context.applicationContext
    scope.launch {
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                val contentUri = path.toUri().takeIf { it.scheme == "content" }
                val uri = contentUri ?: run {
                    val localPath = resolveLocalFilePath(path) ?: return@runCatching null
                    val file = File(localPath)
                    if (!file.exists()) return@runCatching null
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
                }
                val ext = File(path.substringBefore('?')).extension.lowercase(Locale.US)
                val mime = appContext.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: "*/*"
                uri to mime
            }.getOrNull()
        }
        if (resolved == null) {
            fallback()
            return@launch
        }
        val (uri, mime) = resolved
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open received file"))
        }.onFailure {
            fallback()
        }
    }
}

internal fun shareFileOrUrlAsync(
    scope: CoroutineScope,
    context: Context,
    item: ReceivedItem
) {
    if (item.isUrl) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.pathOrUrl)
            }
            context.startActivity(Intent.createChooser(intent, "Share link"))
        }.onFailure {
            Toast.makeText(context, "Unable to share link", Toast.LENGTH_SHORT).show()
        }
        return
    }
    val appContext = context.applicationContext
    scope.launch {
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                val uri = item.pathOrUrl.toUri().takeIf { it.scheme == "content" } ?: run {
                    val localPath = resolveLocalFilePath(item.pathOrUrl) ?: return@runCatching null
                    val file = File(localPath)
                    if (!file.exists()) return@runCatching null
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
                }
                val mime = appContext.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        File(item.title).extension.lowercase(Locale.US)
                    ) ?: "*/*"
                uri to mime
            }.getOrNull()
        }
        if (resolved == null) {
            Toast.makeText(context, "Unable to share file", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val (uri, mime) = resolved
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("GlideDrop file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${item.title}"))
        }.onFailure {
            Toast.makeText(context, "Unable to share file", Toast.LENGTH_SHORT).show()
        }
    }
}
