package io.github.jakharamit44.glidedrop

import android.net.Uri
import java.net.URI
import java.util.Locale

/**
 * Validates peer-supplied URLs before launching external Android intents.
 * Strictly permits only http/https schemes with a non-empty host.
 */
object UriSecurity {
    private val ALLOWED_WEB_SCHEMES = setOf("http", "https")

    fun isSafeWebUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val parsed = URI(trimmed)
            val scheme = parsed.scheme?.lowercase(Locale.US) ?: return false
            if (scheme !in ALLOWED_WEB_SCHEMES) return false
            val host = parsed.host?.trim()
            !host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun parseSafeWebUri(raw: String): Uri? {
        val trimmed = raw.trim()
        if (!isSafeWebUrl(trimmed)) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme !in ALLOWED_WEB_SCHEMES) return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }
}
