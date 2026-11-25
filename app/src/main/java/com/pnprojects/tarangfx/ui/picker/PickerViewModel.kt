package com.pnprojects.tarangfx.ui.picker

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.media.MediaStoreRepository
import com.pnprojects.tarangfx.data.media.OutputRepository
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PickerTab(val label: String) { ALL("All"), FOLDERS("Folders"), RECENT("Recent"), OUTPUTS("Outputs") }
enum class SortBy(val label: String) { DATE("Date"), NAME("Name"), DURATION("Duration"), SIZE("Size") }

data class PickerUi(
    val loading: Boolean = true,
    val tab: PickerTab = PickerTab.ALL,
    val query: String = "",
    val sort: SortBy = SortBy.DATE,
    val folder: String? = null,
    val items: List<AudioFile> = emptyList(),
    val folders: List<Pair<String, Int>> = emptyList(),
    val selected: List<AudioFile> = emptyList(),
)

@HiltViewModel
class PickerViewModel @Inject constructor(
    private val media: MediaStoreRepository,
    private val outputs: OutputRepository,
    private val selection: SelectionHolder,
    private val playback: PlaybackController,
) : ViewModel() {

    private val all = MutableStateFlow<List<AudioFile>>(emptyList())
    private val state = MutableStateFlow(PickerUi())
    private var video = false
    private val owner = "picker@${System.identityHashCode(this)}"

    private val _previewUri = MutableStateFlow<String?>(null)
    val previewUri: StateFlow<String?> = _previewUri.asStateFlow()
    val previewPlaying = playback.isPlaying

    private val outputFiles = outputs.outputs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val ui: StateFlow<PickerUi> = combine(state, all, outputFiles) { s, files, outs ->
        val base: List<AudioFile> = when (s.tab) {
            PickerTab.OUTPUTS -> outs.map {
                AudioFile(it.contentUri, it.title, "${it.title}.${it.format.lowercase()}", it.durationMs, it.sizeBytes, null, it.createdAt / 1000)
            }
            PickerTab.RECENT -> files.sortedByDescending { it.dateAddedSec }.take(50)
            PickerTab.FOLDERS -> if (s.folder == null) emptyList() else files.filter { it.folder == s.folder }
            PickerTab.ALL -> files
        }
        val q = s.query.trim()
        val filtered = if (q.isEmpty()) base else base.filter { it.title.contains(q, true) || it.displayName.contains(q, true) }
        val sorted = when (s.sort) {
            SortBy.DATE -> if (s.tab == PickerTab.RECENT) filtered else filtered.sortedByDescending { it.dateAddedSec }
            SortBy.NAME -> filtered.sortedBy { it.title.lowercase() }
            SortBy.DURATION -> filtered.sortedByDescending { it.durationMs }
            SortBy.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
        s.copy(
            items = sorted,
            folders = files.groupBy { it.folder }.map { it.key to it.value.size }.sortedBy { it.first.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PickerUi())

    fun load(video: Boolean) {
        this.video = video
        viewModelScope.launch {
            state.value = state.value.copy(loading = true)
            all.value = runCatching { if (video) media.queryVideo() else media.queryAudio() }.getOrDefault(emptyList())
            state.value = state.value.copy(loading = false)
        }
    }

    fun setTab(t: PickerTab) { state.value = state.value.copy(tab = t, folder = null) }
    fun setQuery(q: String) { state.value = state.value.copy(query = q) }
    fun setSort(s: SortBy) { state.value = state.value.copy(sort = s) }
    fun openFolder(f: String?) { state.value = state.value.copy(folder = f) }

    fun toggle(file: AudioFile, multi: Boolean) {
        val cur = state.value.selected
        state.value = state.value.copy(
            selected = when {
                !multi -> listOf(file)
                cur.any { it.uri == file.uri } -> cur.filterNot { it.uri == file.uri }
                else -> cur + file
            },
        )
    }

    fun addDocuments(uris: List<Uri>, multi: Boolean, onSingle: () -> Unit) {
        viewModelScope.launch {
            val files = uris.mapNotNull { media.describe(it) }
            if (files.isEmpty()) return@launch
            if (!multi) {
                state.value = state.value.copy(selected = listOf(files.first()))
                onSingle()
            } else {
                state.value = state.value.copy(selected = (state.value.selected + files).distinctBy { it.uri })
            }
        }
    }

    fun preview(file: AudioFile) {
        viewModelScope.launch {
            if (_previewUri.value == file.uri) {
                playback.toggle()
                return@launch
            }
            _previewUri.value = file.uri
            val clip = Clip(1, file.uri, file.title, file.durationMs.coerceAtLeast(1))
            playback.load(Project.single(clip), owner)
            playback.setPlayRange(0, 0)
            playback.play()
        }
    }

    fun confirm(append: Boolean): Boolean {
        val sel = state.value.selected
        if (sel.isEmpty()) return false
        playback.stopAndClear(owner)
        if (append) selection.offerAppend(sel) else selection.set(sel)
        return true
    }

    override fun onCleared() {
        playback.stopAndClear(owner)
        super.onCleared()
    }
}
