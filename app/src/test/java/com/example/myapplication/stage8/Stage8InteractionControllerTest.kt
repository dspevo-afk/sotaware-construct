package com.example.myapplication.stage8

import com.example.myapplication.ToolMode
import org.junit.Assert.*
import org.junit.Test

class Stage8InteractionControllerTest {
    @Test fun clearRequestCancelConfirmAndContextChangeAreConsumed() {
        val c = Stage8InteractionController(); val calls = mutableListOf<Int>()
        c.requestClear("A", 2); c.cancelClear()
        assertFalse(c.confirmClear("A", 2) { calls += it })
        c.requestClear("A", 2)
        assertTrue(c.confirmClear("A", 2) { /* reducer no-op */ })
        assertFalse(c.confirmClear("A", 2) { calls += it })
        c.requestClear("A", 2)
        assertFalse(c.confirmClear("B", 2) { calls += it })
        c.requestClear("A", 2); c.clearPendingOnContextChange()
        assertFalse(c.confirmClear("A", 2) { calls += it })
        c.requestClear("A", 2)
        assertTrue(c.confirmClear("A", 2) { calls += it })
        assertEquals(listOf(2), calls)
        assertFalse(c.confirmClear("A", 2) { calls += it })
    }

    @Test fun optionsAndFullscreenBackHaveExplicitPrecedence() {
        val c = Stage8InteractionController(); var closed = 0
        assertFalse(c.selectMode(ToolMode.PAN)); assertFalse(c.optionsVisible)
        assertTrue(c.selectMode(ToolMode.NOTE)); assertTrue(c.optionsVisible)
        c.dismissOptions(); assertFalse(c.optionsVisible)
        assertFalse(c.onBack(false) { closed++ })
        assertTrue(c.onBack(true) { closed++ }); assertEquals(1, closed)
    }
}
