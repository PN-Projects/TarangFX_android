package com.pnprojects.tarangfx.audio

import com.pnprojects.tarangfx.domain.model.AudioFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Hands picked files from the picker to the next editor without stuffing URIs into routes. */
@Singleton
class SelectionHolder @Inject constructor() {
    @Volatile private var files: List<AudioFile> = emptyList()

    fun set(selection: List<AudioFile>) { files = selection }
    fun peek(): List<AudioFile> = files

    /** Files picked via "Add audio" inside an open editor (Merge / Mix). Consumed by the editor. */
    private val _appended = MutableStateFlow<List<AudioFile>>(emptyList())
    val appended: StateFlow<List<AudioFile>> = _appended.asStateFlow()
    fun offerAppend(more: List<AudioFile>) { _appended.value = more }
    fun consumeAppend(): List<AudioFile> = _appended.value.also { _appended.value = emptyList() }
}
