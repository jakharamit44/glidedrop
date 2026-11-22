package io.github.jakharamit44.glidedrop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface StorageState {
    data object Loading : StorageState
    data class Available(val breakdown: StorageBreakdown) : StorageState
    data object Unavailable : StorageState
}

/**
 * Survives configuration changes (rotation, dark-mode toggle) so staged files,
 * modal drafts, and URI metadata staging coroutines are never lost mid-edit.
 */
class GlideDropViewModel : ViewModel() {

    private val _stagedItems = MutableStateFlow<List<StagedFileItem>>(emptyList())
    val stagedItems: StateFlow<List<StagedFileItem>> = _stagedItems.asStateFlow()

    private val _showTextModal = MutableStateFlow(false)
    val showTextModal: StateFlow<Boolean> = _showTextModal.asStateFlow()

    private val _textSnippetDraft = MutableStateFlow("")
    val textSnippetDraft: StateFlow<String> = _textSnippetDraft.asStateFlow()

    private val _showRenameDialog = MutableStateFlow(false)
    val showRenameDialog: StateFlow<Boolean> = _showRenameDialog.asStateFlow()

    private val _renameText = MutableStateFlow("")
    val renameText: StateFlow<String> = _renameText.asStateFlow()

    private val _storageState = MutableStateFlow<StorageState>(StorageState.Loading)
    val storageState: StateFlow<StorageState> = _storageState.asStateFlow()

    fun stageUris(
        context: Context,
        node: GlideDropNode,
        uris: List<Uri>,
        onStaged: (addedCount: Int, totalCount: Int) -> Unit = { _, _ -> }
    ) {
        if (uris.isEmpty()) return
        val existingIds = _stagedItems.value.mapTo(HashSet()) { it.id }
        val toResolve = uris.filter { it.toString() !in existingIds }
        if (toResolve.isEmpty()) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                toResolve.mapNotNull { uri ->
                    if (!UriSecurity.isSafeShareUri(appContext, uri)) return@mapNotNull null
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val (name, size) = node.inspectUriMeta(uri)
                    StagedFileItem(
                        id = uri.toString(),
                        uri = uri,
                        name = name,
                        sizeBytes = size,
                        category = classifyFileCategory(name)
                    )
                }
            }
            var added = 0
            _stagedItems.update { current ->
                val seen = current.mapTo(HashSet()) { it.id }
                val next = ArrayList(current)
                for (item in resolved) {
                    if (seen.add(item.id)) {
                        next.add(item)
                        added++
                    }
                }
                next
            }
            if (added > 0) {
                onStaged(added, _stagedItems.value.size)
            }
        }
    }

    fun stageTextOrUrl(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val isLink = UriSecurity.isSafeWebUrl(trimmed)
        val key = "text:$trimmed"
        _stagedItems.update { current ->
            if (current.any { it.id == key }) {
                current
            } else {
                current + StagedFileItem(
                    id = key,
                    textOrUrl = trimmed,
                    name = if (isLink) trimmed else "Text note (${trimmed.take(28)})",
                    sizeBytes = trimmed.toByteArray().size.toLong(),
                    category = if (isLink) "Link" else "Doc"
                )
            }
        }
    }

    fun removeStagedItem(item: StagedFileItem) {
        _stagedItems.update { current -> current.filterNot { it.id == item.id } }
    }

    fun removeStagedItemIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        _stagedItems.update { current -> current.filterNot { it.id in ids } }
    }

    fun clearStagedItems() {
        _stagedItems.value = emptyList()
    }

    fun openTextModal() {
        _showTextModal.value = true
    }

    fun dismissTextModal() {
        _showTextModal.value = false
    }

    fun updateTextSnippetDraft(value: String) {
        _textSnippetDraft.value = value
    }

    fun openRenameDialog(currentDeviceName: String) {
        _renameText.value = currentDeviceName
        _showRenameDialog.value = true
    }

    fun dismissRenameDialog() {
        _showRenameDialog.value = false
    }

    fun updateRenameText(value: String) {
        _renameText.value = value.take(28)
    }

    fun syncDeviceNameIfClosed(deviceName: String) {
        if (!_showRenameDialog.value) {
            _renameText.value = deviceName
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                readInternalStorageState()
            }
            _storageState.value = state
        }
    }

    companion object {
        internal fun readInternalStorageState(): StorageState {
            return try {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.totalBytes
                if (total <= 0L) return StorageState.Unavailable
                val free = stat.availableBytes.coerceIn(0L, total)
                StorageState.Available(
                    StorageBreakdown(
                        totalBytes = total,
                        freeBytes = free,
                        usedBytes = (total - free).coerceAtLeast(0L)
                    )
                )
            } catch (_: Throwable) {
                StorageState.Unavailable
            }
        }

        internal fun classifyFileCategory(name: String, isUrl: Boolean = false): String {
            if (isUrl) return "Link"
            return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
                "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "svg" -> "Image"
                "mp4", "mkv", "mov", "avi", "webm", "m4v", "ts" -> "Video"
                "mp3", "m4a", "flac", "wav", "aac", "ogg", "opus" -> "Audio"
                "pdf", "doc", "docx", "txt", "md", "rtf", "xls", "xlsx", "ppt", "pptx", "csv", "json" -> "Doc"
                else -> "File"
            }
        }
    }
}
