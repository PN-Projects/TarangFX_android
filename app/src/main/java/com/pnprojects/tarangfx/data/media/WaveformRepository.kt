package com.pnprojects.tarangfx.data.media

import android.content.Context
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import com.pnprojects.tarangfx.nativebridge.NativeProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Waveform peaks (PRD §4.4): native code streams the source once and writes `.peaks` into
 * cacheDir; subsequent opens are instant. Only the compact FloatArray reaches Compose.
 */
@Singleton
class WaveformRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourceRegistry,
) {
    private val memory = object : LinkedHashMap<String, Waveform>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Waveform>) = size > 12
    }

    private fun cacheFile(uri: String, sizeHint: Long): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$uri|$sizeHint|v1".toByteArray())
        val key = digest.take(16).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "peaks").apply { mkdirs() }, "$key.peaks")
    }

    suspend fun load(uri: String, sizeHint: Long = 0, onProgress: (Float) -> Unit = {}): Waveform? =
        withContext(Dispatchers.IO) {
            synchronized(memory) { memory[uri] }?.let { return@withContext it }
            val file = cacheFile(uri, sizeHint)
            if (!file.exists()) {
                val src = sources.resolve(uri) ?: return@withContext null
                val ctx = coroutineContext
                val n = NativeAudio.extractPeaks(src, file.absolutePath, PEAKS_PER_SECOND, NativeProgressListener { p ->
                    onProgress(p)
                    ctx.isActive
                })
                ensureActive()
                if (n < 0) return@withContext null
            }
            val wf = read(file) ?: run { file.delete(); null }
            if (wf != null) synchronized(memory) { memory[uri] = wf }
            wf
        }

    private fun read(file: File): Waveform? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            if (String(magic) != "PKS1") return null
            buf.int // version
            val pps = buf.int
            val durationMs = buf.long
            val count = buf.int
            val peaks = FloatArray(count)
            buf.asFloatBuffer().get(peaks)
            Waveform(peaks, pps, durationMs)
        }
    }.getOrNull()

    companion object { const val PEAKS_PER_SECOND = 100 }
}
