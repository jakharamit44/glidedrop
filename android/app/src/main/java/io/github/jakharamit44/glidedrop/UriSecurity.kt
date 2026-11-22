package io.github.jakharamit44.glidedrop

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Validates peer-supplied URLs and incoming share URIs before acting on them.
 */
object UriSecurity {
    private val ALLOWED_WEB_SCHEMES = setOf("http", "https")

    private fun hasDisallowedChars(value: String): Boolean {
        return value.any { ch ->
            ch.code < 0x20 || ch.code == 0x7F || Character.getType(ch) == Character.FORMAT.toInt()
        }
    }

    fun isSafeWebUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || hasDisallowedChars(trimmed)) return false
        return try {
            val parsed = URI(trimmed)
            val scheme = parsed.scheme?.lowercase(Locale.US) ?: return false
            if (scheme !in ALLOWED_WEB_SCHEMES) return false
            if (!parsed.userInfo.isNullOrEmpty() || parsed.rawAuthority?.contains('@') == true) return false
            val host = parsed.host?.trim()
            !host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun parseSafeWebUri(raw: String): Uri? {
        val trimmed = raw.trim()
        if (!isSafeWebUrl(trimmed)) return null
        val uri = runCatching { trimmed.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme !in ALLOWED_WEB_SCHEMES) return null
        if (!uri.userInfo.isNullOrEmpty() || uri.encodedAuthority?.contains('@') == true) return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    internal fun isPathInsideAnyRoot(
        canonicalPath: String,
        forbiddenCanonicalRoots: Collection<String>
    ): Boolean {
        val cleanTarget = canonicalPath.trimEnd('/')
        if (cleanTarget.isEmpty()) return true
        return forbiddenCanonicalRoots.any { root ->
            val cleanRoot = root.trimEnd('/')
            cleanRoot.isNotEmpty() && (cleanTarget == cleanRoot || cleanTarget.startsWith("$cleanRoot/"))
        }
    }

    fun isSafeShareUri(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme == "content") {
            val selfAuthority = "${context.packageName}.fileprovider"
            val auth = uri.authority.orEmpty()
            return !auth.equals(selfAuthority, ignoreCase = true)
        }
        if (scheme == "file") {
            val rawPath = uri.path ?: return false
            val canonical = runCatching { File(rawPath).canonicalPath }.getOrNull() ?: return false
            val forbidden = linkedSetOf<String>()
            forbidden += "/proc"
            runCatching { File(context.applicationInfo.dataDir).canonicalPath }.getOrNull()?.let { forbidden += it }
            runCatching { context.filesDir.canonicalPath }.getOrNull()?.let { forbidden += it }
            runCatching { context.cacheDir.canonicalPath }.getOrNull()?.let { forbidden += it }
            runCatching { context.codeCacheDir.canonicalPath }.getOrNull()?.let { forbidden += it }
            runCatching { context.noBackupFilesDir.canonicalPath }.getOrNull()?.let { forbidden += it }
            runCatching { context.dataDir.canonicalPath }.getOrNull()?.let { forbidden += it }
            return !isPathInsideAnyRoot(canonical, forbidden)
        }
        return false
    }
}
