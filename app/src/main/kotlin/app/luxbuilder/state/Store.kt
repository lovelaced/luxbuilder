package app.luxbuilder.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny UDF store. Wraps the pure [reduce] with undo/redo tracking.
 *
 * Not every intent contributes to history — set [recordInHistory] = false for
 * actions that shouldn't be undoable (e.g., source-photo loading, MKL transform
 * recomputation, preset list mutations).
 */
class LuxStore(initial: LuxState = LuxState()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<LuxState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<LuxState>()
    private val redoStack = ArrayDeque<LuxState>()
    private val maxHistory = 64

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun dispatch(intent: LuxIntent, recordInHistory: Boolean = true) {
        when (intent) {
            LuxIntent.Undo -> performUndo()
            LuxIntent.Redo -> performRedo()
            else -> {
                val previous = _state.value
                val next = reduce(previous, intent)
                if (next != previous) {
                    if (recordInHistory) {
                        undoStack.addLast(previous)
                        if (undoStack.size > maxHistory) undoStack.removeFirst()
                        redoStack.clear()
                    }
                    _state.value = next
                }
            }
        }
    }

    private fun performUndo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value)
        _state.value = previous
    }

    private fun performRedo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value)
        _state.value = next
    }
}
