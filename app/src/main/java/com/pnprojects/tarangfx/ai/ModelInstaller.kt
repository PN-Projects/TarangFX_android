package com.pnprojects.tarangfx.ai

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Copies bundled ONNX models from APK assets (assets/models/) into private storage once, so the
 * native ONNX Runtime session can open them by path. Nothing is ever downloaded.
 */
object ModelInstaller {
    private const val ASSET_DIR = "models"

    fun modelDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun install(context: Context) {
        val dir = modelDir(context)
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull().orEmpty()
        for (name in names) {
            if (!name.endsWith(".onnx") && !name.endsWith(".json")) continue
            val target = File(dir, name)
            runCatching {
                context.assets.openFd("$ASSET_DIR/$name").use { afd ->
                    if (target.exists() && target.length() == afd.length) return@runCatching
                }
            }
            runCatching {
                val tmp = File(dir, "$name.part")
                context.assets.open("$ASSET_DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            }.onFailure { Log.w("ModelInstaller", "Failed to install $name: ${it.message}") }
        }
    }
}
