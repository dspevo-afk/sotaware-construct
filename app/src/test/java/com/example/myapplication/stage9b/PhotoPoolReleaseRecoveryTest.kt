package com.example.myapplication.stage9b

import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@RunWith(Parameterized::class)
class PhotoPoolReleaseRecoveryTest(private val retained: Boolean) {
    @Test fun failureBeforeMoveCanRetry() = releaseFault(Fault.BEFORE_MOVE)
    @Test fun interruptedStagingCanRecoverAndRetry() = releaseFault(Fault.KEEP_STAGING)
    @Test fun moveThenThrowDoesNotReleaseAnotherOwner() = releaseFault(Fault.AFTER_MOVE)
    @Test fun postPublicationCleanupFailureDoesNotReleaseAnotherOwner() = releaseFault(Fault.OLD_SLOT_CLEANUP)

    private fun releaseFault(fault: Fault) = fixture { f ->
        val held = f.handle(retained)
        val survivor = f.owner.retain(f.assets)
        val hash = f.assets.values.single().descriptor.sha256
        assertEquals(2L, retention(f.root))
        f.operations.arm(fault)
        expectFailure { held.close() }
        assertFalse("a failed public close remains retryable", released(held))
        val published = fault in setOf(Fault.AFTER_MOVE, Fault.OLD_SLOT_CLEANUP)
        assertEquals(if (published) 1L else 2L, retention(f.root))
        assertEquals(fault == Fault.KEEP_STAGING, File(f.root, TEMP).exists())
        f.operations.clearFaults()
        f.newPool().use { observer ->
            assertFalse(File(f.root, TEMP).exists())
            // A later publication must not make a retry debit this third owner.
            observer.retain(f.assets).use {
                held.close()
                assertTrue(released(held))
                assertEquals(2L, retention(f.root))
                val completed = committed(f.root).readBytes()
                repeat(3) { held.close() }
                assertArrayEquals(completed, committed(f.root).readBytes())
                assertEquals(0, observer.cleanupUnreachable())
                assertArrayEquals(f.bytes, f.assets.values.single().open().use { it.readBytes() })
            }
            assertEquals(1L, retention(f.root))
            survivor.close()
            assertEquals(0L, retention(f.root))
            assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(hash))
            assertEquals(1, observer.cleanupUnreachable())
        }
        f.owner.close()
        assertEquals(0, f.operations.active.size)
    }

    @Test fun closedPoolReleaseRetriesAfterStagingRecovery() = fixture { f ->
        val held = f.handle(retained)
        f.owner.close()
        f.operations.arm(Fault.KEEP_STAGING)
        expectFailure { held.close() }
        assertFalse(released(held))
        f.operations.clearFaults()
        f.newPool().use { observer ->
            held.close()
            assertTrue(released(held))
            assertEquals(1, observer.cleanupUnreachable())
        }
        assertEquals(0, f.operations.active.size)
    }

    @Test fun resolverCloseFailureRetriesWithoutReadingCollectedPhotos() = fixture { f ->
        val held = f.handle(retained)
        f.owner.close()
        f.operations.failOwnerClose = true
        expectFailure { held.close() }
        assertFalse(released(held))
        assertEquals(0L, retention(f.root))
        assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.assets.values.single().descriptor.sha256))
        f.operations.clearFaults()
        f.newPool().use { observer ->
            assertEquals("published release already permits collection", 1, observer.cleanupUnreachable())
            val beforeRetry = committed(f.root).readBytes()
            held.close()
            held.close()
            assertTrue(released(held))
            assertArrayEquals(beforeRetry, committed(f.root).readBytes())
        }
        f.owner.close()
        assertEquals(0, f.operations.active.size)
    }

    @Test fun uncertainMoveCanResolveFromExactPublishedIndex() = fixture { f ->
        val held = f.handle(retained)
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        assertFalse(released(held))
        assertTrue("unproven publication must preserve the claim", PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
        assertEquals(0L, retention(f.root))
        val beforeRetry = committed(f.root).readBytes()
        f.operations.clearFaults()
        held.close()
        assertTrue(released(held))
        assertArrayEquals(beforeRetry, committed(f.root).readBytes())
        f.newPool().use { assertEquals(1, it.cleanupUnreachable()) }
    }

    @Test fun uncertainMoveBlocksAdmissionUntilReceiptReadsRecover() = fixture { f ->
        val held = f.handle(retained)
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        f.operations.fault = Fault.NONE // Original resolver still cannot inspect its receipt.
        f.newPool().use { observer ->
            val before = committed(f.root).readBytes()
            expectFailure { observer.retain(f.assets) }
            assertArrayEquals("admission must not overwrite an unresolved receipt", before, committed(f.root).readBytes())
            assertTrue(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
            f.operations.clearFaults()
            observer.retain(f.assets).use {
                assertEquals(1L, retention(f.root))
                held.close()
                assertTrue(released(held))
                assertEquals(1L, retention(f.root))
            }
            assertEquals(1, observer.cleanupUnreachable())
        }
    }

    @Test fun uncertainMoveNeverGuessesAfterAnotherPublication() = fixture { f ->
        val held = f.handle(retained)
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        assertFalse(released(held))
        f.operations.clearFaults()
        // Simulate an external writer replacing the exact receipt. Ordinary
        // pool admission now retries/blocks before it can cause this ambiguity.
        val path = committed(f.root)
        val lines = path.readLines().toMutableList()
        lines[1] = (lines[1].toLong() + 1L).toString()
        path.writeText(lines.joinToString("\n", postfix = "\n"))
        f.newPool().use { observer ->
            val beforeRetry = committed(f.root).readBytes()
            expectFailure { held.close() }
            assertFalse(released(held))
            assertArrayEquals("ambiguous retry must not mutate retention", beforeRetry, committed(f.root).readBytes())
            assertTrue(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
            expectFailure { observer.cleanupUnreachable() }
            assertArrayEquals(beforeRetry, committed(f.root).readBytes())
            assertArrayEquals(f.bytes, f.assets.values.single().open().use { it.readBytes() })
        }
    }

    @Test fun samePoolReleaseResolvesEarlierReceipt() = laterRelease(separatePool = false)
    @Test fun separatePoolReleaseResolvesEarlierReceipt() = laterRelease(separatePool = true)
    @Test fun samePoolSetReleaseResolvesEarlierReceipt() = laterRelease(separatePool = false, frozen = true)
    @Test fun separatePoolSetReleaseResolvesEarlierReceipt() = laterRelease(separatePool = true, frozen = true)

    private fun laterRelease(separatePool: Boolean, frozen: Boolean = false) = fixture { f ->
        val held = f.handle(retained)
        val keeper = if (separatePool) f.newPool() else f.owner
        val later = if (frozen) {
            val assets = keeper.freeze(f.assets)
            AutoCloseable { keeper.release(assets) }
        } else keeper.retain(f.assets)
        assertEquals(2L, retention(f.root))
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        assertEquals(1L, retention(f.root))
        f.operations.clearFaults()
        // No admission/collection/manual retry is allowed to drain the first
        // receipt before this ordinary public release publishes its decrement.
        later.close()
        assertEquals(0L, retention(f.root))
        assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
        val completed = committed(f.root).readBytes()
        repeat(3) { held.close() }
        assertArrayEquals("retry must not decrement a second time", completed, committed(f.root).readBytes())
        f.newPool().use { observer ->
            assertEquals(1, observer.cleanupUnreachable())
            observer.capture(f.assetsFromBytes()).close()
            assertEquals(1, observer.cleanupUnreachable())
        }
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
    }

    @Test fun samePoolBlockedReleaseRemainsOwned() = blockedRelease(separatePool = false)
    @Test fun separatePoolBlockedReleaseRemainsOwned() = blockedRelease(separatePool = true)

    private fun blockedRelease(separatePool: Boolean) = fixture { f ->
        val held = f.handle(retained)
        val keeper = if (separatePool) f.newPool() else f.owner
        val later = keeper.retain(f.assets)
        val survivor = keeper.retain(f.assets)
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        f.operations.fault = Fault.NONE // Only the original receipt stays unreadable.
        val before = committed(f.root).readBytes()
        repeat(3) { expectFailure { later.close() } }
        assertArrayEquals("a later release must not obscure the receipt", before, committed(f.root).readBytes())
        assertEquals(2L, retention(f.root))
        f.operations.clearFaults()
        f.newPool().use { observer ->
            // Neither failed public handle is retried by the caller.
            assertEquals(0, observer.cleanupUnreachable())
            assertEquals("both deferred owners must retire exactly once", 1L, retention(f.root))
            assertArrayEquals(f.bytes, f.assets.values.single().open().use { it.readBytes() })
            val recovered = committed(f.root).readBytes()
            held.close()
            later.close()
            assertArrayEquals(recovered, committed(f.root).readBytes())
            survivor.close()
            assertEquals(1, observer.cleanupUnreachable())
        }
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
    }

    @Test fun severalClosedPoolsReleaseInRecoveryOrder() = fixture { f ->
        val held = f.handle(retained)
        val second = f.newPool()
        val third = f.newPool()
        val later = listOf(second.retain(f.assets), third.retain(f.assets))
        f.pools.forEach { it.close() }
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        f.operations.fault = Fault.NONE
        val before = committed(f.root).readBytes()
        later.forEach { lease -> expectFailure { lease.close() } }
        assertArrayEquals(before, committed(f.root).readBytes())
        f.operations.clearFaults()
        f.newPool().use { observer ->
            assertEquals(1, observer.cleanupUnreachable())
            assertEquals("all closed owners must release their anchors", 1, f.operations.active.size)
        }
        assertEquals(0, f.operations.active.size)
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
    }

    @Test fun concurrentSetReleasesSelectDistinctClaims() = fixture { f ->
        val held = f.handle(retained)
        val keeper = f.newPool()
        val frozen = keeper.freeze(f.assets)
        val extra = keeper.retain(frozen)
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        f.operations.clearFaults()
        val reading = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val secondStarted = java.util.concurrent.CountDownLatch(1)
        val secondThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        f.operations.beforeOwnerSlotRead = {
            reading.countDown()
            check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Unit> { keeper.release(frozen) }
            assertTrue(reading.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit<Unit> {
                secondThread.set(Thread.currentThread())
                secondStarted.countDown()
                keeper.release(frozen)
            }
            assertTrue(secondStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // The first recovery holds the shared root lock. The second public
            // release must be waiting before we let the first one finish.
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            val waiting = setOf(Thread.State.BLOCKED, Thread.State.WAITING)
            while (secondThread.get().state !in waiting && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("second release must be waiting on a lock", secondThread.get().state in waiting)
            resume.countDown()
            first.get(10, java.util.concurrent.TimeUnit.SECONDS)
            second.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("two set releases must select two distinct claims", 0L, retention(f.root))
            assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
            val completed = committed(f.root).readBytes()
            held.close()
            extra.close()
            assertArrayEquals(completed, committed(f.root).readBytes())
            f.newPool().use { assertEquals(1, it.cleanupUnreachable()) }
        } finally {
            resume.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            f.operations.beforeOwnerSlotRead = null
        }
    }

    @Test fun recoveredHandleIgnoresNewerFailureWhilePoolOpen() = recoveredHandleIgnoresNewerFailure(ownerClosed = false)
    @Test fun recoveredHandleIgnoresNewerFailureAfterPoolClose() = recoveredHandleIgnoresNewerFailure(ownerClosed = true)

    private fun recoveredHandleIgnoresNewerFailure(ownerClosed: Boolean) = fixture { f ->
        val held = f.handle(retained)
        val newer = f.newPool().retain(f.assets)
        val observer = f.newPool()
        val survivor = observer.retain(f.assets)
        if (ownerClosed) f.owner.close()
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        assertFalse(released(held))
        f.operations.clearFaults()
        assertEquals(0, observer.cleanupUnreachable())
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
        assertEquals(2L, retention(f.root))
        assertFalse("recovery does not acknowledge the public handle", released(held))
        if (ownerClosed) assertFalse("recovery closed the original anchor", 1 in f.operations.active)

        f.operations.arm(Fault.BEFORE_MOVE)
        expectFailure { newer.close() }
        assertFalse(newer.isReleased)
        val beforeRetry = committed(f.root).readBytes()
        repeat(3) { held.close() }
        assertTrue("completed ownership must not wait on newer work", released(held))
        assertFalse("retry must not acknowledge another handle", newer.isReleased)
        assertEquals(2L, retention(f.root))
        assertArrayEquals("completed retry must not publish", beforeRetry, committed(f.root).readBytes())
        assertArrayEquals(f.bytes, f.assets.values.single().open().use { it.readBytes() })
        expectFailure { DeferredPhotoReleaseOwner.requireDrained(f.root.toPath()) }

        f.operations.clearFaults()
        newer.close()
        assertEquals(1L, retention(f.root))
        assertEquals(0, observer.cleanupUnreachable())
        survivor.close()
        assertEquals(0L, retention(f.root))
        assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
        assertEquals(1, observer.cleanupUnreachable())
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
    }

    @Test fun completedOwnershipStillRetriesPendingAnchorCleanup() = fixture { f ->
        val held = f.handle(retained)
        val newer = f.newPool().retain(f.assets)
        val observer = f.newPool()
        val survivor = observer.retain(f.assets)
        f.owner.close()
        f.operations.failOwnerClose = true
        expectFailure { held.close() }
        assertFalse(released(held))
        assertEquals(2L, retention(f.root))
        assertTrue("failed cleanup must retain its directory anchor", 1 in f.operations.active)

        // Queue newer work behind the completed ownership's pending cleanup.
        f.operations.arm(Fault.BEFORE_MOVE)
        expectFailure { newer.close() }
        val beforeRetry = committed(f.root).readBytes()
        expectFailure { held.close() }
        assertFalse("a completed token must not hide anchor failure", released(held))
        assertTrue(1 in f.operations.active)
        f.operations.failOwnerClose = false
        held.close()
        held.close()
        assertTrue(released(held))
        assertFalse("retry must actually close the pending anchor", 1 in f.operations.active)
        assertFalse(newer.isReleased)
        assertArrayEquals("anchor cleanup must not publish", beforeRetry, committed(f.root).readBytes())
        expectFailure { newer.close() }
        assertArrayEquals(beforeRetry, committed(f.root).readBytes())
        assertArrayEquals(f.bytes, f.assets.values.single().open().use { it.readBytes() })
        expectFailure { DeferredPhotoReleaseOwner.requireDrained(f.root.toPath()) }

        f.operations.clearFaults()
        newer.close()
        assertEquals(1L, retention(f.root))
        survivor.close()
        assertEquals(0L, retention(f.root))
        assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
        assertEquals(1, observer.cleanupUnreachable())
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
    }

    @Test fun completedHandleDoesNotInheritSamePoolAnchorFailure() = completedSamePoolAnchorFailure(replayCallback = false)
    @Test fun completedCallbackDoesNotInheritSamePoolAnchorFailure() = completedSamePoolAnchorFailure(replayCallback = true)

    private fun completedSamePoolAnchorFailure(replayCallback: Boolean) = fixture { f ->
        val held = f.handle(retained)
        val newer = f.owner.retain(f.assets)
        val observer = f.newPool()
        val survivor = observer.retain(f.assets)
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        // Save the actual callback, just as a concurrent recover() snapshot can.
        val staleRetry = pendingRetries(f.root).single()
        f.operations.clearFaults()
        assertEquals(0, observer.cleanupUnreachable())
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
        assertFalse("recovery does not acknowledge the public handle", released(held))
        assertEquals(2L, retention(f.root))

        f.owner.close() // The newer claim still owns this shared directory anchor.
        f.operations.failOwnerClose = true
        expectFailure { newer.close() }
        assertEquals(1L, retention(f.root))
        assertEquals(1, pendingRetries(f.root).size)
        assertTrue(1 in f.operations.active)
        val beforeRetry = committed(f.root).readBytes()
        val closeAttempts = f.operations.ownerCloseAttempts
        if (replayCallback) {
            repeat(3) { staleRetry() }
            assertFalse("a stale callback does not acknowledge the wrapper", released(held))
        }
        repeat(3) { held.close() }
        assertTrue("fully completed release must only acknowledge its handle", released(held))
        assertFalse("completed retry must not acknowledge newer cleanup", newer.isReleased)
        assertEquals("completed retry must not attempt newer cleanup", closeAttempts, f.operations.ownerCloseAttempts)
        assertEquals("completed ticket must not be resurrected", 1, pendingRetries(f.root).size)
        assertEquals(1L, retention(f.root))
        assertArrayEquals("completed retry must not publish", beforeRetry, committed(f.root).readBytes())
        assertArrayEquals(f.bytes, f.assets.values.single().open().use { it.readBytes() })
        assertTrue("the newer anchor must remain pending", 1 in f.operations.active)
        expectFailure { newer.close() }
        assertFalse(newer.isReleased)
        assertTrue(f.operations.ownerCloseAttempts > closeAttempts)
        assertArrayEquals(beforeRetry, committed(f.root).readBytes())

        f.operations.clearFaults()
        newer.close()
        assertFalse("the genuine retry must close its anchor", 1 in f.operations.active)
        assertEquals(1L, retention(f.root))
        survivor.close()
        assertEquals(0L, retention(f.root))
        assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
        assertEquals(1, observer.cleanupUnreachable())
        DeferredPhotoReleaseOwner.requireDrained(f.root.toPath())
    }

    /** Read-only snapshot of the real queue; never manufactures a retry ticket. */
    private fun pendingRetries(root: File): List<() -> Unit> {
        val field = DeferredPhotoReleaseOwner::class.java.getDeclaredField("pending").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = field.get(DeferredPhotoReleaseOwner) as Map<String, Map<PhotoAssetLease, () -> Unit>>
        return pending[PhotoDocumentCriticalSections.rootKey(root.toPath())]?.values?.toList().orEmpty()
    }

    @Test fun poolCloseItselfCanRetryFailedAnchorCleanup() = fixture { f ->
        f.operations.failOwnerClose = true
        expectFailure { f.owner.close() }
        f.operations.clearFaults()
        f.owner.close()
        f.owner.close()
        assertEquals(0, f.operations.active.size)
    }

    private enum class Fault { NONE, BEFORE_MOVE, KEEP_STAGING, AFTER_MOVE, OLD_SLOT_CLEANUP, UNREADABLE_AFTER_MOVE }
    private class FaultOperations : PhotoPathOperationsFactory {
        var fault = Fault.NONE
        var failOwnerClose = false
        var ownerCloseAttempts = 0
        var denyOwnerSlotReads = false
        @Volatile var beforeOwnerSlotRead: (() -> Unit)? = null
        private var published = false
        private var nextId = 0
        val active = mutableSetOf<Int>()
        fun arm(value: Fault) { fault = value; published = false }
        fun clearFaults() { fault = Fault.NONE; failOwnerClose = false; denyOwnerSlotReads = false }
        override fun open(root: Path): PhotoPathOperations {
            val id = ++nextId
            val delegate = LocalPhotoPathOperationsFactory.open(root)
            active.add(id)
            return object : PhotoPathOperations by delegate {
                override fun openRead(name: String): java.io.InputStream {
                    if (id == 1 && name in SLOTS) beforeOwnerSlotRead?.invoke()
                    if (id == 1 && denyOwnerSlotReads && name in SLOTS) throw IOException("injected receipt read failure")
                    return delegate.openRead(name)
                }
                override fun move(source: String, target: String, replaceExisting: Boolean) {
                    if (source == TEMP && fault in setOf(Fault.BEFORE_MOVE, Fault.KEEP_STAGING)) {
                        throw IOException("injected move before publication")
                    }
                    delegate.move(source, target, replaceExisting)
                    if (source == TEMP) {
                        published = true
                        if (fault == Fault.UNREADABLE_AFTER_MOVE) denyOwnerSlotReads = true
                        if (fault in setOf(Fault.AFTER_MOVE, Fault.UNREADABLE_AFTER_MOVE)) {
                            throw IOException("injected move after publication")
                        }
                    }
                }
                override fun delete(name: String) {
                    if (name == TEMP && fault == Fault.KEEP_STAGING) throw IOException("injected staging cleanup failure")
                    if (name in SLOTS && published && fault == Fault.OLD_SLOT_CLEANUP) {
                        throw IllegalStateException("injected post-publication cleanup failure")
                    }
                    delegate.delete(name)
                }
                override fun close() {
                    if (id == 1) ownerCloseAttempts++
                    if (id == 1 && failOwnerClose) throw IOException("injected anchor close failure")
                    if (active.remove(id)) delegate.close()
                }
            }
        }
    }

    private class Fixture {
        val root = Files.createTempDirectory("pool-release-recovery-").toFile()
        val operations = FaultOperations()
        val pools = mutableListOf<ImmutablePhotoAssetPool>()
        val owner = newPool()
        private val jpeg = Stage4PhotoFixture.jpegBytes()
        private val comment = UUID.randomUUID().toString().toByteArray()
        val bytes = jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (comment.size + 2).toByte()) + comment + jpeg.copyOfRange(2, jpeg.size)
        val hash = sha256Hex(bytes)
        lateinit var assets: PhotoAssetSet
        fun newPool() = ImmutablePhotoAssetPool(root, DefaultImageProbe, 4, 1024L * 1024L, operations, root).also { pools.add(it) }
        fun assetsFromBytes() = PhotoAssetSet.of(mapOf("photo.jpg" to object : PhotoAsset {
            override val descriptor = PhotoDescriptor(bytes.size.toLong(), hash, "image/jpeg", 64, 48)
            override fun open() = bytes.inputStream()
        }))
        fun handle(retained: Boolean): AutoCloseable {
            val capture = owner.capture(assetsFromBytes())
            assets = capture.assets
            return if (retained) owner.retain(assets).also { capture.close() } else capture
        }
        fun tearDown() {
            operations.clearFaults()
            // Teardown only, never recovery evidence: an intentionally blocked
            // receipt (or a red run on the old wrappers) can retain synthetic
            // claims. Release those registry tokens before deleting this root.
            val field = ImmutablePhotoAssetPool::class.java.getDeclaredField("claims").apply { isAccessible = true }
            pools.forEach { pool ->
                @Suppress("UNCHECKED_CAST")
                val claims = field.get(pool) as Map<PhotoAssetSet, List<PhotoAssetLease>>
                claims.values.flatten().forEach { it.close() }
                pool.close()
            }
            DeferredPhotoReleaseOwner.recover(root.toPath()) // Retire forced teardown-only tickets.
            assertEquals("all synthetic directory anchors must close", 0, operations.active.size)
            check(root.deleteRecursively())
        }
    }
    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.tearDown() }
    }
    private fun released(handle: AutoCloseable) = when (handle) {
        is PhotoAssetCapture -> handle.isReleased
        is PhotoAssetLease -> handle.isReleased
        else -> error("unknown test handle")
    }
    private fun expectFailure(block: () -> Unit) {
        try { block() } catch (_: IOException) { return } catch (_: IllegalStateException) { return }
        fail("injected or ambiguous release must fail")
    }
    companion object {
        private const val TEMP = ".stage9b-pool-index.tmp"
        private val SLOTS = setOf(".stage9b-photo-pool.a", ".stage9b-photo-pool.b")
        private fun committed(root: File) = root.listFiles()!!.filter { it.name in SLOTS }.maxBy { it.readLines()[1].toLong() }
        private fun retention(root: File) = committed(root).readLines().drop(3).single().split('\t')[5].toLong()
        @JvmStatic @Parameterized.Parameters(name = "retained={0}")
        fun modes() = listOf(arrayOf(false), arrayOf(true))
    }
}
