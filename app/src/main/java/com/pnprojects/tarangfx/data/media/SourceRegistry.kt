package com.pnprojects.tarangfx.data.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pnprojects.tarangfx.domain.model.SourceResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns content:// URIs into native source strings ("fd:N"). The ParcelFileDescriptor stays open
 * while registered; native code dup()s the fd for each decoder, so evicting an entry never breaks
 * an already-open reader. Read-only ("r") access only — originals are never written.
 */
@Singleton
class SourceRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) : SourceResolver {

    private val maxEntries = 96
    private val open = object : LinkedHashMap<String, ParcelFileDescriptor>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParcelFileDescriptor>): Boolean {
            if (size > maxEntries) {
                runCatching { eldest.value.close() }
                return true
            }
            return false
        }
    }

    @Synchronized
    override fun resolve(uri: String): String? {
        if (uri.startsWith("/")) return uri
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return parsed.path
        open[uri]?.let { return "fd:${it.fd}" }
        return try {
            val pfd = context.contentResolver.openFileDescriptor(parsed, "r") ?: return null
            open[uri] = pfd
            "fd:${pfd.fd}"
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open $uri: ${e.message}")
            null
        }
    }

    @Synchronized
    fun release(uri: String) {
        open.remove(uri)?.let { runCatching { it.close() } }
    }

    companion object { private const val TAG = "SourceRegistry" }
}
