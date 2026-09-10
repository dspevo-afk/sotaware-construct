package com.example.myapplication.stage9b

import com.example.myapplication.stage4.PhotoContentTransaction
import com.example.myapplication.stage4.StagedPhotoContentTransaction
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.sha256Hex
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoContentTransactionAssetStreamTest {
    @Test
    fun transactionStagesAssetByReopeningStreamAndPublishesOwnedBytes() = runBlocking {
        val root = Files.createTempDirectory("stage9b-photo-transaction").toFile()
        val bytes = Stage4PhotoFixture.jpegBytes()
        val opens = AtomicInteger()
        val source = object : PhotoAsset {
            override val descriptor = PhotoDescriptor(
                bytes.size.toLong(),
                sha256Hex(bytes),
                "image/jpeg",
                64,
                48
            )

            override fun open() = bytes.copyOf().inputStream().also { opens.incrementAndGet() }
        }
        try {
            val transaction = StagedPhotoContentTransaction.stageWithOperationsFactory(
                root,
                PhotoAssetSet.of(mapOf("photo.jpg" to source)),
                LocalPhotoPathOperationsFactory,
                root
            )
            transaction.publish()
            transaction.commit()

            assertEquals(1, opens.get())
            assertArrayEquals(bytes, root.resolve("photo.jpg").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
