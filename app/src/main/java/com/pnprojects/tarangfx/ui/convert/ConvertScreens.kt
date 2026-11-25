package com.pnprojects.tarangfx.ui.convert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.AudioFileRef
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.ui.EditorViewModel
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.ExportFormatSection
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.PrimaryButton
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.components.card
import com.pnprojects.tarangfx.ui.theme.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BatchUi(
    val files: List<AudioFile> = emptyList(),
    val config: ExportConfig = ExportConfig(),
    val streamCopy: Boolean = true,
)

@HiltViewModel
class BatchViewModel @Inject constructor(
    selection: SelectionHolder,
    settings: SettingsRepository,
    playback: PlaybackController,
    exports: ExportManager,
) : EditorViewModel(playback, exports) {
    private val _ui = MutableStateFlow(BatchUi(files = selection.peek()))
    val ui: StateFlow<BatchUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val st = settings.current()
            _ui.update { it.copy(config = it.config.copy(codec = st.defaultCodec, bitrateKbps = st.defaultBitrate)) }
        }
    }

    fun setConfig(c: ExportConfig) = _ui.update { it.copy(config = c) }
    fun setStreamCopy(v: Boolean) = _ui.update { it.copy(streamCopy = v) }
    fun remove(f: AudioFile) = _ui.update { it.copy(files = it.files - f) }

    private fun renderJob(f: AudioFile, category: OutputCategory) = ExportJob.Render(
        title = f.title, category = category,
        project = Project.single(Clip(1, f.uri, f.title, f.durationMs.coerceAtLeast(1))),
        config = _ui.value.config.copy(outputName = f.title),
        copyTagsFrom = AudioFileRef(f.uri, f.displayName),
    )

    fun convertAll() = startExport(_ui.value.files.map { renderJob(it, OutputCategory.CONVERT) })

    fun extractAll() = startExport(_ui.value.files.map { f ->
        if (_ui.value.streamCopy) ExportJob.Remux(f.title, f.uri) else renderJob(f, OutputCategory.VIDEO_TO_AUDIO)
    })
}

@Composable
fun ConvertScreen(onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: BatchViewModel = hiltViewModel()) {
    BatchScreen(video = false, onBack = onBack, onOpenOutputs = onOpenOutputs, vm = vm)
}

@Composable
fun VideoToAudioScreen(onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: BatchViewModel = hiltViewModel()) {
    BatchScreen(video = true, onBack = onBack, onOpenOutputs = onOpenOutputs, vm = vm)
}

@Composable
private fun BatchScreen(video: Boolean, onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: BatchViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val statuses by vm.exportStatuses.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar(
            if (video) "Video to audio" else "Convert format", onBack,
            helpText = if (video) "Extract the soundtrack from MP4, MKV, MOV, WEBM or AVI videos. \"Keep original audio\" copies the stream bit-for-bit (fastest, no quality loss); turn it off to transcode to any format."
            else "Convert many files at once. Tags and cover art are carried over to the new files.",
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            if (ui.files.isEmpty()) item { EmptyState("Nothing selected", "Go back and pick files.") }
            items(ui.files, key = { it.uri }) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (video) Icons.Rounded.Movie else Icons.Rounded.AudioFile, null, tint = AppColors.Crimson)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Fmt.time(f.durationMs)} | ${Fmt.size(f.sizeBytes)} | ${f.formatLabel}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.remove(f) }) { Icon(Icons.Rounded.Close, "Remove", tint = AppColors.TextSecondary) }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.card()) {
                    if (video) {
                        Row(
                            Modifier.toggleable(value = ui.streamCopy, onValueChange = vm::setStreamCopy, role = Role.Switch),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep original audio (no re-encoding)", style = MaterialTheme.typography.bodyMedium)
                                Text("Stream copy: instant and lossless", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(ui.streamCopy, null, colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Crimson))
                        }
                    }
                    if (!video || !ui.streamCopy) ExportFormatSection(ui.config, vm::setConfig)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        PrimaryButton(
            if (video) "Extract ${ui.files.size} file${if (ui.files.size == 1) "" else "s"}" else "Convert ${ui.files.size} file${if (ui.files.size == 1) "" else "s"}",
            modifier = Modifier.padding(16.dp).navigationBarsPadding(), enabled = ui.files.isNotEmpty(),
        ) { if (video) vm.extractAll() else vm.convertAll() }
    }
    ExportProgressDialog(statuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}
