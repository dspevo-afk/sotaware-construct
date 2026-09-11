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

    @Test fun uncertainMoveNeverGuessesAfterAnotherPublication() = fixture { f ->
        val held = f.handle(retained)
        f.operations.arm(Fault.UNREADABLE_AFTER_MOVE)
        expectFailure { held.close() }
        assertFalse(released(held))
        // Allow the independent instance to publish while the original cannot
        // inspect its receipt. The old attempt can no longer be proved exactly.
        f.operations.fault = Fault.NONE
        f.newPool().use { observer ->
            observer.retain(f.assets).use {
                f.operations.clearFaults()
                val beforeRetry = committed(f.root).readBytes()
                expectFailure { held.close() }
                assertFalse(released(held))
                assertArrayEquals("ambiguous retry must not mutate retention", beforeRetry, committed(f.root).readBytes())
                assertTrue(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
                assertEquals(0, observer.cleanupUnreachable())
                assertArrayEquals(f.bytes, f.assets.values.single().open().use { it.readBytes() })
            }
        }
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
        var denyOwnerSlotReads = false
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
        fun handle(retained: Boolean): AutoCloseable {
            val source = PhotoAssetSet.of(mapOf("photo.jpg" to object : PhotoAsset {
                override val descriptor = PhotoDescriptor(bytes.size.toLong(), hash, "image/jpeg", 64, 48)
                override fun open() = bytes.inputStream()
            }))
            val capture = owner.capture(source)
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
