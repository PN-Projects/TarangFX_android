package com.pnprojects.tarangfx.nativebridge

/** Progress callback from native code (invoked on the calling thread). Return false to cancel. */
fun interface NativeProgressListener {
    fun onProgress(progress: Float): Boolean
}

/**
 * JNI surface of the C++20 audio core (libtarangfx.so). Every call that can block
 * (probe, peaks, render, remux, tags) MUST be invoked off the main thread.
 */
object NativeAudio {
    init {
        System.loadLibrary("tarangfx")
    }

    @JvmStatic external fun nativeInit(modelDir: String)

    // Probing / waveform
    @JvmStatic external fun probe(source: String): String?
    @JvmStatic external fun extractPeaks(source: String, cachePath: String, peaksPerSecond: Int, listener: NativeProgressListener?): Int

    // Real-time engine
    @JvmStatic external fun engineSetProject(projectJson: String): String?
    @JvmStatic external fun engineClear()
    @JvmStatic external fun enginePlay()
    @JvmStatic external fun enginePause()
    @JvmStatic external fun engineSeek(positionUs: Long)
    @JvmStatic external fun enginePosition(): Long
    @JvmStatic external fun engineDuration(): Long
    @JvmStatic external fun engineIsPlaying(): Boolean
    @JvmStatic external fun engineSampleRate(): Int
    @JvmStatic external fun engineSetPlayRange(startUs: Long, endUs: Long, loop: Boolean)
    @JvmStatic external fun engineSetSkipRange(startUs: Long, endUs: Long)
    @JvmStatic external fun engineUpdateClip(clipJson: String): Boolean
    @JvmStatic external fun engineSetMasterDsp(dspJson: String): Boolean
    @JvmStatic external fun engineSetTrackState(trackId: Long, gainDb: Float, muted: Boolean, solo: Boolean)

    // Offline export
    @JvmStatic external fun render(
        projectJson: String, encoderJson: String, outPath: String,
        startUs: Long, endUs: Long, renderRate: Int, tempDir: String,
        listener: NativeProgressListener?,
    ): String?

    @JvmStatic external fun planRemux(source: String): String?
    @JvmStatic external fun remux(source: String, outPath: String, muxer: String, listener: NativeProgressListener?): String?

    // Tags
    @JvmStatic external fun readTags(source: String, nameHint: String): String?
    @JvmStatic external fun readCover(source: String, nameHint: String): ByteArray?
    @JvmStatic external fun writeTags(path: String, tagsJson: String, cover: ByteArray?, coverMime: String?, coverMode: Int): String?

    // Recorder
    @JvmStatic external fun recStart(path: String, encoderJson: String, channels: Int, liveDenoise: Boolean): String?
    @JvmStatic external fun recPause()
    @JvmStatic external fun recResume()
    @JvmStatic external fun recStop(): Long
    @JvmStatic external fun recSetGain(db: Float)
    @JvmStatic external fun recLevel(): Float
    @JvmStatic external fun recPosition(): Long
    @JvmStatic external fun recIsActive(): Boolean
    @JvmStatic external fun recSetLiveDenoise(on: Boolean)
    @JvmStatic external fun recDrainPeaks(): FloatArray

    // AI
    @JvmStatic external fun denoiseAvailable(mode: String): Boolean
}
