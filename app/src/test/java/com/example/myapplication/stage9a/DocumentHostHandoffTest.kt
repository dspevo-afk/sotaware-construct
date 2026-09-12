package com.example.myapplication.stage9a

import com.example.myapplication.stage3.DocumentHostHandoff
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DocumentHostHandoffTest {
    @Test fun replacementWaitsForEntirePreviousRetirement() = runBlocking {
        val gate = DocumentHostHandoff()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val old = gate.newOwner().apply { bind {
            events += "freeze"; entered.complete(Unit); release.await(); events += "joined"
        } }
        val next = gate.newOwner().apply { bind {} }
        old.activate()
        val close = async(start = CoroutineStart.UNDISPATCHED) { old.closeAndJoin() }
        entered.await()
        val open = async(start = CoroutineStart.UNDISPATCHED) { next.activate(); events += "load" }
        try {
            assertFalse(open.isCompleted)
            assertEquals(listOf("freeze"), events)
        } finally { release.complete(Unit) }
        close.await(); open.await()
        assertEquals(listOf("freeze", "joined", "load"), events)
        next.closeAndJoin()
    }

    @Test fun activationRetiresPredecessorEvenWithoutPauseEvent() = runBlocking {
        val gate = DocumentHostHandoff(); var retired = 0
        val old = gate.newOwner().apply { bind { retired++ } }
        val next = gate.newOwner().apply { bind {} }
        old.activate(); next.activate(); next.activate()
        assertEquals(1, retired)
        old.closeAndJoin(); assertEquals(1, retired)
        next.closeAndJoin()
    }

    @Test fun failedFlushBlocksReplacementAndCanBeRetried() = runBlocking {
        val gate = DocumentHostHandoff(); var fails = true; var attempts = 0
        val old = gate.newOwner().apply { bind { attempts++; check(!fails) { "save failed" } } }
        val next = gate.newOwner().apply { bind {} }
        old.activate()
        try { next.activate(); fail("failed predecessor was accepted") }
        catch (expected: IllegalStateException) { assertEquals("save failed", expected.message) }
        assertEquals(1, attempts)
        fails = false; next.activate(); old.closeAndJoin()
        assertEquals(2, attempts); next.closeAndJoin()
    }

    @Test fun retiredAndNeverActivatedOlderHostsCannotDisplaceNewOwner() = runBlocking {
        val gate = DocumentHostHandoff(); var newestRetirements = 0
        val old = gate.newOwner().apply { bind {} }
        val unused = gate.newOwner().apply { bind {} }
        val newest = gate.newOwner().apply { bind { newestRetirements++ } }
        old.activate(); newest.activate()
        for (stale in listOf(old, unused)) {
            try { stale.activate(); fail("stale host displaced its successor") }
            catch (_: IllegalStateException) { }
            stale.closeAndJoin()
        }
        assertEquals(0, newestRetirements)
        newest.closeAndJoin(); assertEquals(1, newestRetirements)
    }

    @Test fun cancellationDuringRetirementDoesNotSkipItsJoin() = runBlocking {
        val gate = DocumentHostHandoff()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var retired = false
        val old = gate.newOwner().apply { bind { entered.complete(Unit); release.await(); retired = true } }
        val next = gate.newOwner().apply { bind {} }
        val later = gate.newOwner().apply { bind {} }
        old.activate()
        val opening = launch(start = CoroutineStart.UNDISPATCHED) { next.activate() }
        entered.await(); opening.cancel()
        val newest = async(start = CoroutineStart.UNDISPATCHED) { later.activate() }
        try { assertFalse(newest.isCompleted); assertFalse(retired) }
        finally { release.complete(Unit) }
        opening.join(); newest.await(); assertTrue(retired)
        old.closeAndJoin(); next.closeAndJoin(); later.closeAndJoin()
    }

    @Test fun repeatedBindingAndConcurrentCloseCannotChangeTheRetirementAction() = runBlocking {
        val gate = DocumentHostHandoff(); var original = 0; var replacement = 0
        val owner = gate.newOwner().apply { bind { original++ }; bind { replacement++ } }
        owner.activate()
        coroutineScope { repeat(8) { launch { owner.closeAndJoin() } } }
        assertEquals(1, original); assertEquals(0, replacement)
    }
}
