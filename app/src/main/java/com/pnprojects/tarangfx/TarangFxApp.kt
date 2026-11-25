package com.pnprojects.tarangfx

import android.app.Application
import com.pnprojects.tarangfx.ai.ModelInstaller
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import com.pnprojects.tarangfx.service.ExportForegroundService
import com.pnprojects.tarangfx.data.media.OutputRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class TarangFxApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @javax.inject.Inject lateinit var outputs: OutputRepository

    override fun onCreate() {
        super.onCreate()
        ExportForegroundService.ensureChannel(this)
        val modelDir = ModelInstaller.modelDir(this)
        // Loading the native library + copying bundled ONNX models happens off the main thread
        // so cold start stays well under the 800 ms budget.
        appScope.launch {
            // Process start: no export/recording can be in flight, so leftovers are garbage.
            outputs.purgeStaleStaging()
            ModelInstaller.install(this@TarangFxApp)
            NativeAudio.nativeInit(modelDir.absolutePath)
        }
    }
}
