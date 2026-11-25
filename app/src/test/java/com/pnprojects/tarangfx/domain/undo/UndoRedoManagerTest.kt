package com.pnprojects.tarangfx.domain.undo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoRedoManagerTest {

    @Test
    fun `push, undo and redo walk the history`() {
        val m = UndoRedoManager(0)
        m.push("a", 1)
        m.push("b", 2)
        m.push("c", 3)
        assertEquals(3, m.current)
        assertTrue(m.undo()); assertEquals(2, m.current)
        assertTrue(m.undo()); assertEquals(1, m.current)
        assertTrue(m.redo()); assertEquals(2, m.current)
        assertTrue(m.canUndo.value)
        assertTrue(m.canRedo.value)
    }

    @Test
    fun `a new edit clears the redo stack`() {
        val m = UndoRedoManager("x")
        m.push("1", "y")
        m.undo()
        assertTrue(m.canRedo.value)
        m.push("2", "z")
        assertFalse(m.canRedo.value)
        assertFalse(m.redo())
        assertEquals("z", m.current)
    }

    @Test
    fun `pushing an identical state records nothing`() {
        val m = UndoRedoManager(5)
        m.push("noop", 5)
        assertFalse(m.canUndo.value)
    }

    @Test
    fun `history is bounded to capacity`() {
        val m = UndoRedoManager(0, capacity = 10)
        for (i in 1..25) m.push("e$i", i)
        var undos = 0
        while (m.undo()) undos++
        assertEquals(10, undos)
        assertEquals(15, m.current)  // oldest 15 edits were dropped
    }

    @Test
    fun `replace does not create history and reset clears it`() {
        val m = UndoRedoManager(0)
        m.replace(42)
        assertEquals(42, m.current)
        assertFalse(m.canUndo.value)
        m.push("a", 1)
        m.reset(100)
        assertEquals(100, m.current)
        assertFalse(m.canUndo.value)
        assertFalse(m.canRedo.value)
    }

    @Test
    fun `undo and redo on empty stacks are safe no-ops`() {
        val m = UndoRedoManager(1)
        assertFalse(m.undo())
        assertFalse(m.redo())
        assertEquals(1, m.current)
    }
}
