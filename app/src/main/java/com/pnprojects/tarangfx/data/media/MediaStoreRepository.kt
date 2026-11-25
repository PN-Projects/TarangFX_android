package com.pnprojects.tarangfx.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.MediaInfo
import com.pnprojects.tarangfx.domain.model.EngineJson
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local media browser backed by MediaStore (PRD Screen 3). All queries run on Dispatchers.IO. */
@Singleton
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourceRegistry,
) {
    suspend fun queryAudio(): List<AudioFile> = withContext(Dispatchers.IO) {
        query(audioCollection(), isVideo = false)
    }

    suspend fun queryVideo(): List<AudioFile> = withContext(Dispatchers.IO) {
        query(videoCollection(), isVideo = true)
    }

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    @Suppress("DEPRECATION")
    private fun query(collection: Uri, isVideo: Boolean): List<AudioFile> {
        val folderCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(folderCol)
            add(if (isVideo) MediaStore.Video.Media.DURATION else MediaStore.Audio.Media.DURATION)
            if (!isVideo) add(MediaStore.Audio.Media.ARTIST)
        }.toTypedArray()
        val selection = if (isVideo) null else "${MediaStore.Audio.Media.DURATION} > 0"
        val out = ArrayList<AudioFile>()
        context.contentResolver.query(collection, projection, selection, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val folderI = c.getColumnIndexOrThrow(folderCol)
            val durI = c.getColumnIndexOrThrow(if (isVideo) MediaStore.Video.Media.DURATION else MediaStore.Audio.Media.DURATION)
            val artistI = if (isVideo) -1 else c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val id = c.getLong(idI)
                val name = c.getString(nameI) ?: "audio"
                val folderRaw = c.getString(folderI) ?: ""
                val folder = if (Build.VERSION.SDK_INT >= 29) folderRaw.trimEnd('/').substringAfterLast('/')
                else folderRaw.substringBeforeLast('/').substringAfterLast('/')
                out += AudioFile(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.getString(titleI)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    displayName = name,
                    durationMs = c.getLong(durI),
                    sizeBytes = c.getLong(sizeI),
                    mimeType = c.getString(mimeI),
                    dateAddedSec = c.getLong(dateI),
                    folder = folder.ifEmpty { "Internal storage" },
                    isVideo = isVideo,
                    artist = if (artistI >= 0) c.getString(artistI)?.takeIf { it != "<unknown>" } else null,
                )
            }
        }
        return out
    }

    /** Resolves metadata for an arbitrary URI (SAF document, shared file, output). */
    suspend fun describe(uri: Uri): AudioFile? = withContext(Dispatchers.IO) {
        var name = "audio"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c: Cursor ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        size = c.getLong(1)
                    }
                }
        }
        val info = probe(uri.toString()) ?: return@withContext null
        val mime = context.contentResolver.getType(uri)
        AudioFile(
            uri = uri.toString(), title = name.substringBeforeLast('.'), displayName = name,
            durationMs = info.durationUs / 1000, sizeBytes = size, mimeType = mime,
            isVideo = info.hasVideo || (mime?.startsWith("video") == true),
        )
    }

    suspend fun probe(uri: String): MediaInfo? = withContext(Dispatchers.IO) {
        val src = sources.resolve(uri) ?: return@withContext null
        NativeAudio.probe(src)?.let { runCatching { EngineJson.decodeFromString(MediaInfo.serializer(), it) }.getOrNull() }
    }
}
