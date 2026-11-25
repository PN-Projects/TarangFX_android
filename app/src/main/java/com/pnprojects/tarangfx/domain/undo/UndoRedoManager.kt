package com.pnprojects.tarangfx.domain.undo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A reversible edit applied to an immutable editor state [S]. */
interface Command<S> {
    val label: String
    fun apply(state: S): S
    fun revert(state: S): S
}

/** Generic command that captures before/after snapshots (states are immutable data classes). */
class SnapshotCommand<S>(override val label: String, private val before: S, private val after: S) : Command<S> {
    override fun apply(state: S): S = after
    override fun revert(state: S): S = before
}

/**
 * Command-pattern undo/redo stack (PRD §5 domain/undo). Bounded to [capacity] entries so
 * memory stays flat on long editing sessions.
 */
class UndoRedoManager<S>(initial: S, private val capacity: Int = 100) {
    private val undoStack = ArrayDeque<Command<S>>()
    private val redoStack = ArrayDeque<Command<S>>()

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    val current: S get() = _state.value

    fun execute(command: Command<S>) {
        _state.value = command.apply(_state.value)
        undoStack.addLast(command)
        if (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
        refresh()
    }

    /** Convenience: record a transition from the current state to [newState]. */
    fun push(label: String, newState: S) {
        if (newState == _state.value) return
        execute(SnapshotCommand(label, _state.value, newState))
    }

    /** Replace state without recording history (e.g. transient drag previews, playback info). */
    fun replace(newState: S) {
        _state.value = newState
    }

    fun undo(): Boolean {
        val cmd = undoStack.removeLastOrNull() ?: return false
        _state.value = cmd.revert(_state.value)
        redoStack.addLast(cmd)
        refresh()
        return true
    }

    fun redo(): Boolean {
        val cmd = redoStack.removeLastOrNull() ?: return false
        _state.value = cmd.apply(_state.value)
        undoStack.addLast(cmd)
        refresh()
        return true
    }

    fun reset(state: S) {
        undoStack.clear()
        redoStack.clear()
        _state.value = state
        refresh()
    }

    private fun refresh() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
