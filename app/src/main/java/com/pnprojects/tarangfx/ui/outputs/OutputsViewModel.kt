package com.pnprojects.tarangfx.ui.outputs

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.db.OutputAudioEntity
import com.pnprojects.tarangfx.data.media.OutputRepository
import com.pnprojects.tarangfx.data.media.RingtoneHelper
import com.pnprojects.tarangfx.data.media.RingtoneTarget
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OutputSort(val label: String) { DATE("Newest"), NAME("Name"), DURATION("Duration"), SIZE("Size") }

data class OutputsFilter(
    val category: OutputCategory? = null,
    val query: String = "",
    val sort: OutputSort = OutputSort.DATE,
    val selecting: Boolean = false,
    val selected: Set<Long> = emptySet(),
)

data class OutputsUi(val filter: OutputsFilter = OutputsFilter(), val items: List<OutputAudioEntity> = emptyList(), val total: Int = 0)

@HiltViewModel
class OutputsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: OutputRepository,
    private val ringtones: RingtoneHelper,
    private val selection: SelectionHolder,
    private val playback: PlaybackController,
) : ViewModel() {

    private val filter = MutableStateFlow(OutputsFilter())
    private val owner = "outputs@${System.identityHashCode(this)}"

    val ui: StateFlow<OutputsUi> = combine(repo.outputs, filter) { all, f ->
        val q = f.query.trim()
        val items = all.asSequence()
            .filter { f.category == null || it.category == f.category.name }
            .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
            .let { s ->
                when (f.sort) {
                    OutputSort.DATE -> s.sortedByDescending { it.createdAt }
                    OutputSort.NAME -> s.sortedBy { it.title.lowercase() }
                    OutputSort.DURATION -> s.sortedByDescending { it.durationMs }
                    OutputSort.SIZE -> s.sortedByDescending { it.sizeBytes }
                }
            }.toList()
        OutputsUi(f, items, all.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OutputsUi())

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()
    val isPlaying = playback.isPlaying
    val position = playback.positionMs

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _consent = MutableStateFlow<PendingIntent?>(null)
    /** API 30+ system dialog asking the user to confirm deleting files the app no longer owns. */
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()
    private var consentIds: List<Long> = emptyList()

    init {
        viewModelScope.launch { repo.pruneMissing() }
    }

    fun setCategory(c: OutputCategory?) = filter.update { it.copy(category = c) }
    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun setSort(s: OutputSort) = filter.update { it.copy(sort = s) }
    fun toggleSelecting() = filter.update { it.copy(selecting = !it.selecting, selected = emptySet()) }
    fun toggleSelected(id: Long) = filter.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectAll() = filter.update { f -> f.copy(selected = ui.value.items.map { it.id }.toSet()) }

    fun play(e: OutputAudioEntity) {
        viewModelScope.launch {
            if (_playingId.value == e.id && playback.owner.value == owner) { playback.toggle(); return@launch }
            _playingId.value = e.id
            playback.load(Project.single(Clip(1, e.contentUri, e.title, e.durationMs.coerceAtLeast(1))), owner)
            playback.setPlayRange(0, 0)
            playback.setSkipRange(0, 0)
            playback.seekTo(0)
            playback.play()
        }
    }

    fun seek(ms: Long) = playback.seekTo(ms)

    fun stopPlayback() {
        _playingId.value = null
        playback.stopAndClear(owner)
    }

    fun rename(e: OutputAudioEntity, title: String) = viewModelScope.launch {
        if (!repo.rename(e, title)) _message.value = "Could not rename file"
    }

    fun delete(entities: List<OutputAudioEntity>) = viewModelScope.launch {
        if (entities.any { it.id == _playingId.value }) stopPlayback()
        val needConsent = repo.delete(entities)
        filter.update { it.copy(selected = emptySet(), selecting = false) }
        if (needConsent.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                consentIds = needConsent.map { it.id }
                _consent.value = MediaStore.createDeleteRequest(context.contentResolver, needConsent.map { Uri.parse(it.contentUri) })
            } else _message.value = "Some files could not be deleted"
        }
    }

    fun onConsentResult(granted: Boolean) {
        _consent.value = null
        if (granted) viewModelScope.launch { repo.forget(consentIds) }
        consentIds = emptyList()
    }

    fun canWriteSettings() = ringtones.canWriteSettings()
    fun writeSettingsIntent() = ringtones.writeSettingsIntent()

    fun setRingtone(e: OutputAudioEntity, target: RingtoneTarget) = viewModelScope.launch {
        ringtones.assign(Uri.parse(e.contentUri), target)
            .onSuccess { _message.value = "Set as ${target.label.lowercase()}" }
            .onFailure { _message.value = it.message ?: "Could not set ${target.label.lowercase()}" }
    }

    private fun toFile(e: OutputAudioEntity) = AudioFile(
        uri = e.contentUri, title = e.title, displayName = "${e.title}.${e.format.lowercase()}",
        durationMs = e.durationMs, sizeBytes = e.sizeBytes, mimeType = repo.mimeFor(e.format), dateAddedSec = e.createdAt / 1000,
    )

    fun prepareEditor(e: OutputAudioEntity) {
        stopPlayback()
        selection.set(listOf(toFile(e)))
    }

    fun mimeFor(e: OutputAudioEntity) = repo.mimeFor(e.format)
    fun clearMessage() { _message.value = null }

    override fun onCleared() {
        playback.stopAndClear(owner)
        super.onCleared()
    }
}
