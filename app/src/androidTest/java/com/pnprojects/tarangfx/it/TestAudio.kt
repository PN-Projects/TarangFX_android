package com.pnprojects.tarangfx.it

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Minimal 16-bit PCM WAV writer/reader for generating deterministic test media on-device. */
object TestAudio {

    /** Stereo sine (L = [freqL], R = [freqR]) at [amp] full scale. Returns the interleaved samples written. */
    fun writeSineWav(file: File, seconds: Double, rate: Int = 44_100, freqL: Double = 440.0, freqR: Double = 660.0, amp: Double = 0.5): ShortArray {
        val frames = (seconds * rate).roundToInt()
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) {
            pcm[2 * i] = (amp * 32767 * sin(2 * PI * freqL * i / rate)).roundToInt().toShort()
            pcm[2 * i + 1] = (amp * 32767 * sin(2 * PI * freqR * i / rate)).roundToInt().toShort()
        }
        writeWav(file, pcm, rate, 2)
        return pcm
    }

    fun writeWav(file: File, pcm: ShortArray, rate: Int, channels: Int) {
        file.parentFile?.mkdirs()
        val dataBytes = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        bb.put("data".toByteArray()).putInt(dataBytes)
        pcm.forEach { bb.putShort(it) }
        file.writeBytes(bb.array())
    }

    /** Reads the 16-bit PCM samples of a canonical WAV (walks chunks to find `data`). */
    fun readWav16(file: File): Pair<Int, ShortArray> {
        RandomAccessFile(file, "r").use { raf ->
            val all = ByteArray(raf.length().toInt()).also { raf.readFully(it) }
            val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(12)
            var channels = 0
            while (bb.remaining() >= 8) {
                val id = String(ByteArray(4).also { bb.get(it) })
                val size = bb.int
                if (id == "fmt ") {
                    val start = bb.position()
                    bb.short
                    channels = bb.short.toInt()
                    bb.position(start + size)
                } else if (id == "data") {
                    val n = minOf(size, bb.remaining()) / 2
                    val out = ShortArray(n)
                    bb.asShortBuffer().get(out)
                    return channels to out
                } else {
                    bb.position(bb.position() + size + (size and 1))
                }
            }
        }
        error("No data chunk in $file")
    }
}
