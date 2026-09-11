package com.example.myapplication.stage9b

import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No public handle leaves abandon(): recovery must own the failed release. */
@RunWith(Parameterized::class)
class DeferredPhotoReleaseRecoveryTest(private val mode: Int) {
    @Test fun droppedHandleRecoversBeforeCollection() = recover(Fault.BEFORE_MOVE, false)
    @Test fun droppedHandleRecoversBeforeAdmission() = recover(Fault.BEFORE_MOVE, true)
    @Test fun retainedStagingRecoversAfterCallerReturns() = recover(Fault.STAGING, false)
    @Test fun publishedReleaseDoesNotDecrementSurvivingOwner() = recover(Fault.AFTER_MOVE, false)
    @Test fun unreadableReceiptRecoversAfterCallerReturns() = recover(Fault.UNREADABLE_AFTER_MOVE, false)
    @Test fun anchorCleanupRemainsOwnedAfterCallerReturns() = recover(Fault.ANCHOR_CLOSE, false)

    private fun recover(fault: Fault, admission: Boolean) = fixture { f ->
        val survivor = f.abandon(mode, fault)
        f.operations.clear()
        f.pool().use { observer ->
            if (admission) {
                observer.capture(f.source).use { assertEquals(2L, retention(f.root)) }
            } else {
                assertEquals(0, observer.cleanupUnreachable())
            }
            assertEquals("only the surviving owner remains", 1L, retention(f.root))
            assertEquals("the retired pool anchor must be closed", 2, f.operations.active.size)
            survivor.close()
            assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
            assertEquals(1, observer.cleanupUnreachable())
            assertEquals(0, observer.assetCount)
        }
        assertEquals(0, f.operations.active.size)
    }

    @Test fun persistentFaultFailsAdmissionWithoutAddingAnotherClaim() = fixture { f ->
        val survivor = f.abandon(mode, Fault.BEFORE_MOVE)
        val before = committed(f.root).readBytes()
        f.pool().use { observer ->
            repeat(3) {
                expectFailure { observer.capture(f.source).close() }
                assertArrayEquals(before, committed(f.root).readBytes())
                assertEquals(2L, retention(f.root))
            }
            f.operations.clear()
            assertEquals(0, observer.cleanupUnreachable())
            assertEquals(1L, retention(f.root))
            survivor.close()
            assertEquals(1, observer.cleanupUnreachable())
        }
        assertEquals(0, f.operations.active.size)
    }

    @Test fun overlappingRecoveryPassesDoNotDoubleRelease() = fixture { f ->
        val survivor = f.abandon(mode, Fault.BEFORE_MOVE)
        f.operations.clear()
        val observers = listOf(f.pool(), f.pool())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = observers.map { pool -> executor.submit<Int> { pool.cleanupUnreachable() } }
            results.forEach { assertEquals(0, it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1L, retention(f.root))
            survivor.close()
            assertEquals(1, observers.first().cleanupUnreachable())
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            observers.forEach { it.close() }
        }
        assertEquals(0, f.operations.active.size)
    }

    @Test fun closedPoolRejectsBeforeRunningRecovery() = fixture { f ->
        val survivor = f.abandon(mode, Fault.BEFORE_MOVE)
        val before = committed(f.root).readBytes()
        try {
            f.pools.first().cleanupUnreachable()
            fail("closed pool must reject the operation")
        } catch (_: IllegalStateException) { }
        assertArrayEquals(before, committed(f.root).readBytes())
        f.operations.clear()
        f.pool().use { observer ->
            assertEquals(0, observer.cleanupUnreachable())
            survivor.close()
            assertEquals(1, observer.cleanupUnreachable())
        }
        assertEquals(0, f.operations.active.size)
    }

    @Test fun failedRootDoesNotBlockAnotherDocument() = fixture { f ->
        val survivor = f.abandon(mode, Fault.BEFORE_MOVE)
        fixture { other ->
            other.pool().use { pool ->
                pool.capture(other.source).close()
                assertEquals(1, pool.cleanupUnreachable())
            }
            assertEquals(0, other.operations.active.size)
        }
        f.operations.clear()
        f.pool().use { observer ->
            assertEquals(0, observer.cleanupUnreachable())
            survivor.close()
            assertEquals(1, observer.cleanupUnreachable())
        }
        assertEquals(0, f.operations.active.size)
    }

    private class Fixture {
        val root = Files.createTempDirectory("deferred-pool-release-").toFile()
        val operations = ReleaseFaults()
        val bytes = uniqueJpeg()
        val hash = sha256Hex(bytes)
        val source = PhotoAssetSet.of(mapOf("photo.jpg" to object : PhotoAsset {
            override val descriptor = PhotoDescriptor(bytes.size.toLong(), hash, "image/jpeg", 64, 48)
            override fun open() = bytes.inputStream()
        }))
        val pools = mutableListOf<ImmutablePhotoAssetPool>()
        fun pool() = ImmutablePhotoAssetPool(root, DefaultImageProbe, 2, 1024L * 1024L, operations, root)
            .also { pools.add(it) }

        fun abandon(mode: Int, fault: Fault): PhotoAssetLease {
            val owner = pool()
            val captured = if (mode == 2) null else owner.capture(source)
            val assets = captured?.assets ?: owner.freeze(source)
            val held: AutoCloseable = if (mode == 1) owner.retain(assets).also { captured!!.close() }
                else captured ?: AutoCloseable { owner.release(assets) }
            // Put the surviving owner on a separate pool so the abandoned
            // pool's directory anchor can be observed closing independently.
            val keeper = pool()
            val survivor = keeper.retain(assets)
            keeper.close()
            owner.close()
            operations.arm(fault, root.toPath())
            expectFailure { held.close() }
            assertEquals(if (fault in setOf(Fault.BEFORE_MOVE, Fault.STAGING)) 2L else 1L, retention(root))
            // The closed keeper retains its anchor until survivor.close().
            return survivor
        }

        fun tearDown() {
            operations.clear()
            // Red-run hygiene only. These forced tokens are not recovery proof.
            val field = ImmutablePhotoAssetPool::class.java.getDeclaredField("claims").apply { isAccessible = true }
            pools.forEach { pool ->
                @Suppress("UNCHECKED_CAST")
                val claims = field.get(pool) as Map<PhotoAssetSet, List<PhotoAssetLease>>
                claims.values.flatten().forEach { it.close() }
                pool.close()
            }
            DeferredPhotoReleaseOwner.recover(root.toPath()) // Forced teardown-only tickets.
            check(root.deleteRecursively())
        }
    }
    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try { test(f) } finally { f.tearDown() }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "capture=0,retain=1,freeze=2: {0}")
        fun modes() = listOf(arrayOf(0), arrayOf(1), arrayOf(2))
    }
}

class DocumentDeferredPhotoReleaseTest {
    @Test fun storeAdmissionRecoversDroppedExportCapture() = recover(true)
    @Test fun storeCollectionRecoversDroppedSyncCapture() = recover(false)
    @Test fun emptyAdmissionRecoversDroppedCapture() = recover(true, empty = true)

    private fun recover(admission: Boolean, empty: Boolean = false) {
        val root = Files.createTempDirectory("deferred-document-release-").toFile()
        val id = DocumentId.new()
        val operations = ReleaseFaults()
        val snapshot = DocumentSnapshotV1(schemaVersion = 2, snapshotRevision = 1L,
            source = DocumentSourceIdentityV1("content://synthetic/deferred-release", "plan.pdf"),
            pages = mapOf(0 to PageSnapshotV1(photoPins = listOf(PhotoPinSnapshotV1(
                x = .5f, y = .5f, id = "pin", imageFileNames = listOf("photo.jpg"),
                imageNotes = emptyMap(), imageShapes = emptyMap())))))
        val poolRoot = File(root, "immutable-photo-assets/${id.value}")
        val bytes = uniqueJpeg()
        val hash = sha256Hex(bytes)
        try {
            abandonStoreCapture(root, id, snapshot, operations, bytes, poolRoot)
            operations.clear()
            DocumentPhotoAssetStore(root, id, DefaultImageProbe, operations).use { store ->
                if (admission) {
                    val next = if (empty) snapshot.copy(pages = emptyMap()) else snapshot
                    store.capturePhotoAssetsForAdmission(snapshot, next).close()
                    assertFalse("admission must recover before returning", PhotoAssetOwnershipRegistry.isHashClaimed(hash))
                }
                val collected = store.cleanupUnreachablePhotoAssets()
                assertFalse("caller-local capture must no longer pin content", PhotoAssetOwnershipRegistry.isHashClaimed(hash))
                assertEquals(1, collected)
                assertArrayEquals("canonical photo must not be deleted", bytes, File(store.resolver.root, "photo.jpg").readBytes())
            }
            DeferredPhotoReleaseOwner.requireDrained(poolRoot.toPath())
            assertEquals("every synthetic directory anchor must close", 0, operations.active.size)
        } finally {
            operations.clear()
            // Teardown-only cleanup for a deliberately failing red run.
            // The JVM invocation is disposable; no public handle was retained
            // by the oracle and no forced release is used as successful proof.
            check(root.deleteRecursively())
        }
    }

    private fun abandonStoreCapture(root: File, id: DocumentId, snapshot: DocumentSnapshotV1,
        operations: ReleaseFaults, bytes: ByteArray, poolRoot: File) {
        val store = DocumentPhotoAssetStore(root, id, DefaultImageProbe, operations)
        File(store.resolver.root, "photo.jpg").writeBytes(bytes)
        val capture = store.capturePhotoAssetsForAdmission(snapshot, snapshot)
        store.close() // Production closes its short-lived store before async work ends.
        operations.arm(Fault.BEFORE_MOVE, poolRoot.toPath())
        expectFailure { capture.release() }
        assertTrue(PhotoAssetOwnershipRegistry.isHashClaimed(sha256Hex(bytes)))
    }
}

private enum class Fault { NONE, BEFORE_MOVE, STAGING, AFTER_MOVE, UNREADABLE_AFTER_MOVE, ANCHOR_CLOSE }
private class ReleaseFaults : PhotoPathOperationsFactory {
    @Volatile private var fault = Fault.NONE
    private var armedRoot: Path? = null
    @Volatile private var denyReads = false
    private var nextId = 0
    val active = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    fun arm(value: Fault, root: Path) { armedRoot = root; fault = value }
    fun clear() { fault = Fault.NONE; denyReads = false }
    override fun open(root: Path): PhotoPathOperations {
        val id = synchronized(this) { ++nextId }
        val delegate = LocalPhotoPathOperationsFactory.open(root)
        active.add(id)
        return object : PhotoPathOperations by delegate {
            override fun openRead(name: String): InputStream {
                if (root == armedRoot && denyReads && name in SLOTS) throw IOException("injected receipt read failure")
                return delegate.openRead(name)
            }
            override fun move(source: String, target: String, replaceExisting: Boolean) {
                val inject = root == armedRoot && source == TEMP
                if (inject && fault in setOf(Fault.BEFORE_MOVE, Fault.STAGING)) throw IOException("injected pre-publication failure")
                delegate.move(source, target, replaceExisting)
                if (inject && fault in setOf(Fault.AFTER_MOVE, Fault.UNREADABLE_AFTER_MOVE)) {
                    denyReads = fault == Fault.UNREADABLE_AFTER_MOVE
                    throw IOException("injected post-publication failure")
                }
            }
            override fun delete(name: String) {
                if (root == armedRoot && name == TEMP && fault == Fault.STAGING) throw IOException("injected staging cleanup failure")
                delegate.delete(name)
            }
            override fun close() {
                if (root == armedRoot && fault == Fault.ANCHOR_CLOSE) throw IOException("injected anchor cleanup failure")
                if (active.remove(id)) delegate.close()
            }
        }
    }
}
private const val TEMP = ".stage9b-pool-index.tmp"
private val SLOTS = setOf(".stage9b-photo-pool.a", ".stage9b-photo-pool.b")
private fun committed(root: File) = root.listFiles()!!.filter { it.name in SLOTS }.maxBy { it.readLines()[1].toLong() }
private fun retention(root: File) = committed(root).readLines().drop(3).single().split('\t')[5].toLong()
private fun expectFailure(block: () -> Unit) {
    try { block() } catch (_: IOException) { return }
    fail("expected injected release/recovery failure")
}
private fun uniqueJpeg(): ByteArray {
    val jpeg = Stage4PhotoFixture.jpegBytes()
    val comment = UUID.randomUUID().toString().toByteArray()
    return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (comment.size + 2).toByte()) + comment + jpeg.copyOfRange(2, jpeg.size)
}
