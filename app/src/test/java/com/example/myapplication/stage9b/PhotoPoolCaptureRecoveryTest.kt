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
class PhotoPoolCaptureRecoveryTest(private val frozen: Boolean, private val seeded: Boolean) {
    @Test fun beforeMoveRollsBackOnlyUnpublishedBytes() = publicationFault(Fault.BEFORE_MOVE, false, false)
    @Test fun completedMoveRetainsCommittedBytes() = publicationFault(Fault.AFTER_MOVE, true, true)
    @Test fun unreadableCompletedMoveRetainsCommittedBytes() = publicationFault(Fault.UNREADABLE_AFTER_MOVE, true, true)
    @Test fun unreadableUnpublishedMoveRetainsEvidence() = publicationFault(Fault.UNREADABLE_BEFORE_MOVE, false, true)
    @Test fun retainedStagingKeepsItsProposedBytesRecoverable() = publicationFault(Fault.KEEP_STAGING, false, true)
    @Test fun failedReadbackRetainsPublishedBytes() = publicationFault(Fault.READBACK, true, true)

    @Test fun successfulCaptureProtectsCompleteBytesUntilRelease() = fixture { f ->
        f.newPool().use { observer ->
            withCapture(f.owner, f.source) { captured ->
                f.assertBytes(captured)
                assertEquals(0, observer.cleanupUnreachable())
                assertTrue(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
            }
            assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
            assertEquals(1, observer.cleanupUnreachable())
        }
    }

    private fun publicationFault(fault: Fault, published: Boolean, retainBytes: Boolean) = fixture { f ->
        f.operations.arm(fault)
        try {
            withCapture(f.owner, f.source) { fail("injected publication must not return a capture") }
            fail("injected publication must fail")
        } catch (_: IOException) {
            assertTrue("the intended publication fault was reached", f.operations.triggered)
        }
        assertEquals("rollback must preserve uncertain or published bytes", retainBytes, f.assetFile.exists())
        if (retainBytes) assertArrayEquals(f.bytes, f.assetFile.readBytes())
        assertEquals(fault == Fault.KEEP_STAGING, File(f.root, TEMP).exists())
        assertFalse("failed capture must not create a live claim", PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
        f.operations.clearFaults()
        f.newPool().use { observer ->
            assertFalse("complete staging is recoverable with its required bytes", File(f.root, TEMP).exists())
            assertEquals(if (published) f.source.size else if (seeded) 1 else 0, observer.assetCount)
            f.seed?.let { assertArrayEquals(f.seedBytes, it.assets.getValue("seed.jpg").open().use { input -> input.readBytes() }) }
            // Both the original owner and an independently reopened pool must
            // retry successfully at the exact physical count/byte admission bound.
            listOf(f.owner, observer).forEach { pool ->
                withCapture(pool, f.source) { captured ->
                    f.assertBytes(captured)
                    assertEquals(f.source.size, observer.assetCount)
                    assertEquals(f.source.size, observer.physicalFileCount)
                    assertEquals(0, observer.cleanupUnreachable())
                }
            }
            assertFalse(PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
            assertEquals("only the new, released asset is collectible", 1, observer.cleanupUnreachable())
            assertEquals(if (seeded) 1 else 0, observer.assetCount)
        }
    }

    private fun withCapture(pool: ImmutablePhotoAssetPool, input: PhotoAssetSet, check: (PhotoAssetSet) -> Unit) {
        if (frozen) {
            val captured = pool.freeze(input)
            try { check(captured) } finally { pool.release(captured) }
        } else {
            pool.capture(input).use { check(it.assets) }
        }
    }

    private enum class Fault { NONE, BEFORE_MOVE, AFTER_MOVE, UNREADABLE_BEFORE_MOVE, UNREADABLE_AFTER_MOVE, KEEP_STAGING, READBACK }
    private class FaultOperations : PhotoPathOperationsFactory {
        private var fault = Fault.NONE
        private var denyInspection = false
        private var nextId = 0
        var triggered = false
            private set
        val active = mutableSetOf<Int>()
        fun arm(value: Fault) { fault = value; triggered = false }
        fun clearFaults() { fault = Fault.NONE; denyInspection = false }
        override fun open(root: Path): PhotoPathOperations {
            val id = ++nextId
            val delegate = LocalPhotoPathOperationsFactory.open(root)
            active.add(id)
            return object : PhotoPathOperations by delegate {
                override fun exists(name: String): Boolean {
                    if (id == 1 && denyInspection && name in SLOTS) throw IOException("injected manifest inspection failure")
                    return delegate.exists(name)
                }
                override fun openRead(name: String): java.io.InputStream {
                    if (id == 1 && denyInspection && name in SLOTS) throw IOException("injected manifest read failure")
                    return delegate.openRead(name)
                }
                override fun move(source: String, target: String, replaceExisting: Boolean) {
                    if (source == TEMP && fault in setOf(Fault.BEFORE_MOVE, Fault.KEEP_STAGING, Fault.UNREADABLE_BEFORE_MOVE)) {
                        triggered = true
                        denyInspection = fault == Fault.UNREADABLE_BEFORE_MOVE
                        throw IOException("injected failure before manifest publication")
                    }
                    delegate.move(source, target, replaceExisting)
                    if (source == TEMP && fault in setOf(Fault.AFTER_MOVE, Fault.UNREADABLE_AFTER_MOVE, Fault.READBACK)) {
                        triggered = true
                        denyInspection = fault != Fault.AFTER_MOVE
                        if (fault != Fault.READBACK) throw IOException("injected failure after manifest publication")
                    }
                }
                override fun delete(name: String) {
                    if (name == TEMP && fault == Fault.KEEP_STAGING) throw IOException("injected staging cleanup failure")
                    delegate.delete(name)
                }
                override fun close() {
                    if (active.remove(id)) delegate.close()
                }
            }
        }
    }

    private inner class Fixture {
        val root = Files.createTempDirectory("pool-capture-recovery-").toFile()
        val operations = FaultOperations()
        val pools = mutableListOf<ImmutablePhotoAssetPool>()
        val bytes = uniqueJpeg()
        val seedBytes = uniqueJpeg()
        val hash = sha256Hex(bytes)
        val assetFile = File(root, ".stage9b-photo-asset-$hash.bin")
        val owner = newPool()
        val seed = if (seeded) owner.capture(PhotoAssetSet.of(mapOf("seed.jpg" to byteAsset(seedBytes)))) else null
        val source = PhotoAssetSet.of(buildMap {
            put("new.jpg", byteAsset(bytes))
            seed?.let { put("seed.jpg", it.assets.getValue("seed.jpg")) }
        })
        fun newPool() = ImmutablePhotoAssetPool(
            root, DefaultImageProbe, if (seeded) 2 else 1,
            bytes.size.toLong() + if (seeded) seedBytes.size else 0,
            operations, root
        ).also { pools.add(it) }
        fun assertBytes(captured: PhotoAssetSet) {
            assertArrayEquals(bytes, captured.getValue("new.jpg").open().use { it.readBytes() })
            seed?.let { assertArrayEquals(seedBytes, captured.getValue("seed.jpg").open().use { input -> input.readBytes() }) }
        }
        fun tearDown() {
            operations.clearFaults()
            // Teardown only, not recovery evidence: a red run can leave a
            // broken manifest that prevents normal release of the seed owner.
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
    private fun fixture(check: (Fixture) -> Unit) {
        val f = Fixture()
        try {
            check(f)
            f.seed?.close()
            f.pools.forEach { it.close() }
            assertEquals("normal release must close all anchors", 0, f.operations.active.size)
            assertFalse("no new capture claim may leak", PhotoAssetOwnershipRegistry.isHashClaimed(f.hash))
        } finally { f.tearDown() }
    }
    companion object {
        private const val TEMP = ".stage9b-pool-index.tmp"
        private val SLOTS = setOf(".stage9b-photo-pool.a", ".stage9b-photo-pool.b")
        private fun uniqueJpeg(): ByteArray {
            val jpeg = Stage4PhotoFixture.jpegBytes()
            val comment = UUID.randomUUID().toString().toByteArray()
            return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (comment.size + 2).toByte()) + comment + jpeg.copyOfRange(2, jpeg.size)
        }
        private fun byteAsset(bytes: ByteArray): PhotoAsset = object : PhotoAsset {
            override val descriptor = PhotoDescriptor(bytes.size.toLong(), sha256Hex(bytes), "image/jpeg", 64, 48)
            override fun open() = bytes.inputStream()
        }
        @JvmStatic @Parameterized.Parameters(name = "freeze={0},existingOwner={1}")
        fun modes() = listOf(arrayOf(false, false), arrayOf(false, true), arrayOf(true, false), arrayOf(true, true))
    }
}
