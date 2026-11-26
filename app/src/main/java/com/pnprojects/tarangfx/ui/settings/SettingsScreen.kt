package com.pnprojects.tarangfx.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.BuildConfig
import com.pnprojects.tarangfx.data.prefs.AppSettings
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.ui.components.ChipRow
import com.pnprojects.tarangfx.ui.components.InfoDialog
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.components.SelectPill
import com.pnprojects.tarangfx.ui.components.card
import com.pnprojects.tarangfx.ui.theme.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun setCodec(c: Codec) = viewModelScope.launch { repo.setDefaultCodec(c) }
    fun setBitrate(b: Int) = viewModelScope.launch { repo.setDefaultBitrate(b) }

    /** Clears waveform caches and stale export staging files (never touches published outputs). */
    fun clearCache(onDone: (Long) -> Unit) = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) {
            listOf(File(context.cacheDir, "peaks"), File(context.cacheDir, "exports")).sumOf { dir ->
                dir.walkBottomUp().filter { it.isFile }.sumOf { f -> f.length().also { f.delete() } }
            }
        }
        onDone(freed)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var cleared by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar("Settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Column(Modifier.card()) {
                Text("Default export format", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                ChipRow(Codec.entries, { it.label }, s.defaultCodec) { vm.setCodec(it) }
                Spacer(Modifier.height(12.dp))
                Text("Default bitrate (lossy formats)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                ChipRow(ExportConfig.BITRATES, { "$it kbps" }, s.defaultBitrate) { vm.setBitrate(it) }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.card()) {
                Text("Storage", style = MaterialTheme.typography.titleSmall)
                Text("Exports are saved to Music/TarangFX. Waveform caches can be rebuilt at any time.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SelectPill("Clear cache", false) { vm.clearCache { cleared = it } }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.card()) {
                Text("Privacy", style = MaterialTheme.typography.titleSmall)
                Text(
                    "TarangFX has no internet permission, no analytics, no ads and no accounts. All audio processing, " +
                        "including AI denoising, runs on this device. App data is excluded from cloud backup.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.card()) {
                Text("About", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}\n" +
                        "TarangFX is free software under the GNU AGPL v3.0 or later. Source code: " +
                        "github.com/PN-Projects/TarangFX_android\n\n" +
                        "Open-source components: FFmpeg (LGPL 2.1+), LAME (LGPL), libopus/libvorbis/libogg (BSD), " +
                        "Rubber Band Library (GPL 2+), TagLib (LGPL 2.1 / MPL 1.1), Google Oboe (Apache 2.0), " +
                        "ONNX Runtime (MIT), GTCRN (MIT), DeepFilterNet3 (MIT / Apache 2.0), nlohmann/json (MIT), " +
                        "Plus Jakarta Sans (OFL 1.1).\n\nMade with ♥ at PN-projects by TeamPrabodhNandini",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    cleared?.let { InfoDialog("Cache cleared", "Freed ${it / 1024} KB.") { cleared = null } }
}
