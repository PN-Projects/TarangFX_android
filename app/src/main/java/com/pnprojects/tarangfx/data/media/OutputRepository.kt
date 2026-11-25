package com.pnprojects.tarangfx.data.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pnprojects.tarangfx.data.db.OutputAudioEntity
import com.pnprojects.tarangfx.data.db.OutputDao
import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.OutputCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Publishes rendered files to shared storage under Music/TarangFX/ (PRD §7 scoped storage)
 * and indexes them in Room for the Outputs tab.
 */
@Singleton
class OutputRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: OutputDao,
    private val mediaStore: MediaStoreRepository,
) {
    val outputs: Flow<List<OutputAudioEntity>> = dao.observeAll()

    /**
     * App-private staging directory for renders/recordings before they are published. Deliberately
     * NOT cacheDir: Android may purge app caches at any time when storage is low, which would delete
     * a long export or recording while it is still being written.
     */
    fun stagingDir(): File = File(context.noBackupFilesDir, "exports").apply { mkdirs() }

    /** Removes staged files left behind by a process kill; call when no job can be running. */
    fun purgeStaleStaging() {
        stagingDir().listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "exports").deleteRecursively()  // legacy location
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "opus", "ogg" -> "audio/ogg"
        "mka" -> "audio/x-matroska"
        else -> Codec.entries.firstOrNull { it.ext == ext }?.mime ?: "audio/*"
    }

    /** Ensures a unique, filesystem-safe display name. */
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "audio" }

    /**
     * Copies [staged] into MediaStore (IS_PENDING=1 while writing), records it in Room and deletes
     * the staged copy. Returns the new entity.
     */
    suspend fun publish(
        staged: File,
        title: String,
        category: OutputCategory,
        sampleRate: Int,
        bitrateKbps: Int,
        /** Shared-storage folder. Ringtones go to Ringtones/ etc. so Android lists them as such. */
        baseDirectory: String = Environment.DIRECTORY_MUSIC,
    ): OutputAudioEntity = withContext(Dispatchers.IO) {
        val ext = staged.extension.lowercase()
        val displayName = "${sanitize(title)}.$ext"
        val mime = mimeFor(ext)
        val uri: Uri
        val relPath = "$baseDirectory/TarangFX/"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, if (baseDirectory == Environment.DIRECTORY_MUSIC) 1 else 0)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            uri = context.contentResolver.insert(collection, values)
                ?: error("MediaStore refused the new file")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    staged.inputStream().use { it.copyTo(out, 256 * 1024) }
                } ?: error("Cannot open output stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(baseDirectory), "TarangFX")
            dir.mkdirs()
            var target = File(dir, displayName)
            var i = 1
            while (target.exists()) target = File(dir, "${sanitize(title)} ($i).$ext").also { i++ }
            staged.copyTo(target)
            uri = scan(target, mime) ?: Uri.fromFile(target)
        }
        val info = mediaStore.probe(uri.toString())
        val entity = OutputAudioEntity(
            title = title,
            filePath = relPath + displayName,
            contentUri = uri.toString(),
            category = category.name,
            durationMs = (info?.durationUs ?: 0) / 1000,
            sizeBytes = staged.length(),
            format = ext.uppercase(),
            sampleRate = info?.sampleRate ?: sampleRate,
            bitrate = ((info?.bitRate ?: 0L) / 1000).toInt().takeIf { it > 0 } ?: bitrateKbps,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insert(entity)
        staged.delete()
        entity.copy(id = id)
    }

    private suspend fun scan(file: File, mime: String): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
            if (cont.isActive) cont.resume(uri)
        }
    }

    /** Removes Room rows whose MediaStore item no longer exists (deleted from another app). */
    suspend fun pruneMissing() = withContext(Dispatchers.IO) {
        val missing = dao.getAll().filter { e ->
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(e.contentUri), "r")?.close() == null
            }.getOrDefault(true)
        }.map { it.id }
        if (missing.isNotEmpty()) dao.delete(missing)
    }

    suspend fun rename(entity: OutputAudioEntity, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val ext = entity.format.lowercase()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${sanitize(newTitle)}.$ext")
            put(MediaStore.MediaColumns.TITLE, newTitle)
        }
        val ok = runCatching { context.contentResolver.update(Uri.parse(entity.contentUri), values, null, null) > 0 }
            .getOrDefault(false)
        if (ok) dao.update(entity.copy(title = newTitle))
        ok
    }

    /** Deletes app-owned outputs. Returns the entities that need user consent (API 30+ request). */
    suspend fun delete(entities: List<OutputAudioEntity>): List<OutputAudioEntity> = withContext(Dispatchers.IO) {
        val needConsent = ArrayList<OutputAudioEntity>()
        val removed = ArrayList<Long>()
        for (e in entities) {
            try {
                context.contentResolver.delete(Uri.parse(e.contentUri), null, null)
                removed += e.id
            } catch (se: SecurityException) {
                needConsent += e
            }
        }
        if (removed.isNotEmpty()) dao.delete(removed)
        needConsent
    }

    suspend fun forget(ids: List<Long>) = dao.delete(ids)

    suspend fun updateEntity(entity: OutputAudioEntity) = dao.update(entity)
}
