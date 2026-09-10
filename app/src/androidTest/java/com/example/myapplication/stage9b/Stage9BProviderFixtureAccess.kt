package com.example.myapplication.stage9b

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Bounded test-fixture IPC through a same-signature, exact-debug-target UID gate.
 * The DocumentsProvider retains MANAGE_DOCUMENTS. No target permission or URI
 * grant is added here: all measured source/import/export access uses DocumentsUI.
 */
object Stage9BProviderFixtureAccess {
    data class FileInfo(
        val name: String,
        val size: Long,
        val sha256: String
    )

    private const val KEY_NAMES = "names"
    private const val KEY_BYTES = "bytes"
    private const val KEY_FILE_DESCRIPTOR = "fileDescriptor"
    private const val KEY_SIZE = "size"
    private const val KEY_SHA256 = "sha256"
    private const val MAX_LIST_FILES = 128
    private const val MAX_CONTROL_BYTES = 512L * 1024L
    private const val MAX_MIRROR_BYTES = 64L * 1024L * 1024L

    /** Ensures the fixture provider is live and its actual root is available. */
    fun prepare(): Uri = withFixtureControl {
        val prepared = call(Stage9BQualificationDocumentsProvider.CALL_PREPARE)
        val source = call(
            Stage9BQualificationDocumentsProvider.CALL_HASH,
            Stage9BQualificationDocumentsProvider.SOURCE_NAME
        )
        check(source.getLong(KEY_SIZE, 0L) > 0L) {
            "Stage9B provider did not publish its seeded PDF"
        }
        check(prepared.getBoolean("rootAvailable")) { "Stage9B fixture root is unavailable" }
        Stage9BQualificationDocumentsProvider.documentUri(
            Stage9BQualificationDocumentsProvider.PDF_ID
        )
    }

    /** Deletes only provider-owned synthetic artifacts; the seeded source remains. */
    fun clearArtifacts() = withFixtureControl {
        call(Stage9BQualificationDocumentsProvider.CALL_CLEAR)
    }

    /** Lists provider file names through the protected provider boundary. */
    fun listNames(): List<String> = withFixtureControl {
        val names = call(Stage9BQualificationDocumentsProvider.CALL_LIST)
            .getStringArrayList(KEY_NAMES)
            .orEmpty()
        check(names.size <= MAX_LIST_FILES) {
            "Stage9B provider returned too many fixture files"
        }
        names.toList()
    }

    /** Lists provider files and obtains each bounded digest through the provider. */
    fun listFiles(): List<FileInfo> = withFixtureControl {
        val names = call(Stage9BQualificationDocumentsProvider.CALL_LIST)
            .getStringArrayList(KEY_NAMES)
            .orEmpty()
        check(names.size <= MAX_LIST_FILES) {
            "Stage9B provider returned too many fixture files"
        }
        names.map { name ->
            val response = call(Stage9BQualificationDocumentsProvider.CALL_HASH, name)
            FileInfo(
                name = name,
                size = response.getLong(KEY_SIZE, -1L),
                sha256 = requireNotNull(response.getString(KEY_SHA256))
            ).also { info ->
                check(info.size >= 0L) { "provider returned a negative artifact size" }
            }
        }
    }

    /** Gets one provider-owned file's bounded metadata. */
    fun fileInfo(name: String): FileInfo = withFixtureControl {
        val response = call(Stage9BQualificationDocumentsProvider.CALL_HASH, name)
        FileInfo(
            name = name,
            size = response.getLong(KEY_SIZE, -1L),
            sha256 = requireNotNull(response.getString(KEY_SHA256))
        ).also { info -> check(info.size >= 0L) }
    }

    /**
     * Copies a provider artifact into an isolated caller-owned cache mirror.
     * The source bytes are opened by the provider and transferred through a
     * ParcelFileDescriptor; the mirror is verified against the provider's
     * digest before publication and can safely be recreated after target data
     * is replaced.  The mirror is never authoritative; the provider retains
     * the canonical archive and oracle across target uninstall/reinstall.
     */
    fun copyToCache(context: Context, name: String): File {
        val info = fileInfo(name)
        check(info.size <= MAX_MIRROR_BYTES) {
            "Stage9B provider artifact exceeds the bounded mirror limit"
        }
        val mirrorDirectory = File(context.cacheDir, "stage9b-provider-mirrors")
        check(mirrorDirectory.isDirectory || mirrorDirectory.mkdirs()) {
            "could not create the Stage9B provider mirror directory"
        }
        val target = File(mirrorDirectory, name)
        val partial = File(mirrorDirectory, ".${name}.${UUID.randomUUID()}.partial")
        try {
            withFixtureControl {
                val response = call(Stage9BQualificationDocumentsProvider.CALL_OPEN, name)
                val descriptor = parcelFileDescriptor(response)
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            check(total <= MAX_MIRROR_BYTES) {
                                "Stage9B provider artifact exceeded the bounded mirror limit"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            check(partial.length() == info.size) {
                "provider artifact size changed during bounded copy"
            }
            check(sha256File(partial) == info.sha256) {
                "provider artifact digest changed during bounded copy"
            }
            atomicReplace(partial, target)
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    fun writeExpected(bytes: ByteArray) = writeControl(
        Stage9BQualificationDocumentsProvider.CALL_WRITE_EXPECTED,
        bytes
    )

    fun readExpected(): ByteArray = readControl(
        Stage9BQualificationDocumentsProvider.CALL_READ_EXPECTED
    )

    fun writeRetired(bytes: ByteArray) = writeControl(
        Stage9BQualificationDocumentsProvider.CALL_WRITE_RETIRED,
        bytes
    )

    fun readRetired(): ByteArray = readControl(
        Stage9BQualificationDocumentsProvider.CALL_READ_RETIRED
    )

    private fun writeControl(method: String, bytes: ByteArray) {
        require(bytes.size.toLong() <= MAX_CONTROL_BYTES) {
            "Stage9B control fixture exceeds the bounded Binder payload limit"
        }
        withFixtureControl {
            call(method, extras = Bundle().apply { putByteArray(KEY_BYTES, bytes) })
        }
    }

    private fun readControl(method: String): ByteArray =
        withFixtureControl {
            val bytes = requireNotNull(call(method).getByteArray(KEY_BYTES))
            require(bytes.size.toLong() <= MAX_CONTROL_BYTES) {
                "Stage9B control fixture exceeded the bounded Binder payload limit"
            }
            bytes
        }

    private fun parcelFileDescriptor(response: Bundle): ParcelFileDescriptor {
        @Suppress("DEPRECATION")
        val descriptor = response.getParcelable(KEY_FILE_DESCRIPTOR) as? ParcelFileDescriptor
        return requireNotNull(descriptor) {
            "Stage9B provider did not return an artifact descriptor"
        }
    }

    // Fixture IPC has its own UID/signature gate; it grants no document permissions.
    private inline fun <T> withFixtureControl(block: () -> T): T = block()

    private fun call(
        method: String,
        arg: String? = null,
        extras: Bundle? = null
    ): Bundle = requireNotNull(
        resolver.call(
            Uri.parse("content://${Stage9BFixtureControlProvider.AUTHORITY}"),
            method,
            arg,
            extras
        )
    ) { "Stage9B provider returned no result for $method" }

    private fun atomicReplace(partial: File, target: File) {
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: UnsupportedOperationException) {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun sha256File(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val resolver
        get() = instrumentation.context.contentResolver
}
