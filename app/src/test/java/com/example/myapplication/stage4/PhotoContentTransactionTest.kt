package com.example.myapplication.stage4

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoContentTransactionTest {
    @Test
    fun laterPhotoMoveFailure_restoresEveryPreviousPhotoByte() {
        val root = Files.createTempDirectory("stage4-photo-rollback").toFile()
        try {
            File(root, "first.jpg").writeBytes("old-first".toByteArray())
            File(root, "second.jpg").writeBytes("old-second".toByteArray())
            var moveCount = 0
            val transaction = StagedPhotoContentTransaction.stage(
                root,
                mapOf(
                    "first.jpg" to "new-first".toByteArray(),
                    "second.jpg" to "new-second".toByteArray()
                ),
                move = { source: Path, target: Path ->
                    moveCount++
                    if (moveCount == 3) throw IOException("injected later photo move failure")
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            )

            var failed = false
            try {
                kotlinx.coroutines.runBlocking { transaction.publish() }
            } catch (_: IOException) {
                failed = true
            }

            assertTrue(failed)
            assertEquals("old-first", File(root, "first.jpg").readText())
            assertEquals("old-second", File(root, "second.jpg").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
