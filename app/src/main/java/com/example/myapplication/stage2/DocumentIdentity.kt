package com.example.myapplication.stage2

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Stable identity allocated by this app for one source association.
 *
 * A document id is deliberately not derived from a filename, URI hash, or
 * source contents.  The value is persisted in the Stage 2 manifest and is
 * therefore stable across process restarts.
 */
@JvmInline
value class DocumentId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun new(): DocumentId = DocumentId(UUID.randomUUID().toString())

        fun parse(value: String): DocumentId {
            val normalized = value.lowercase(Locale.ROOT)
            val parsed = try {
                UUID.fromString(normalized)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid DocumentId: $value", error)
            }
            require(parsed.toString() == normalized) { "Invalid DocumentId: $value" }
            return DocumentId(normalized)
        }
    }
}

/**
 * A source-content revision signal.  It is used to detect a changed source
 * behind an existing URI; it is never used as a document identity.
 */
data class SourceFingerprint(
    val algorithm: String,
    val digestHex: String,
    val byteCount: Long
) {
    init {
        require(algorithm.equals(SHA256_ALGORITHM, ignoreCase = true)) {
            "Only SHA-256 source fingerprints are supported"
        }
        require(digestHex.matches(SHA256_HEX_REGEX)) { "Invalid SHA-256 digest" }
        require(byteCount >= 0L) { "Fingerprint byteCount must be non-negative" }
    }

    companion object {
        const val SHA256_ALGORITHM = "SHA-256"
        private val SHA256_HEX_REGEX = Regex("[0-9a-fA-F]{64}")

        fun fromBytes(bytes: ByteArray): SourceFingerprint =
            fromInputStream(bytes.inputStream())

        fun fromInputStream(input: InputStream): SourceFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = 0L
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    count += read.toLong()
                }
            }
            val hex = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
            return SourceFingerprint(SHA256_ALGORITHM, hex, count)
        }
    }
}

/** Input seam used by JVM tests and by Android content-provider sources. */
fun interface SourceFingerprintReader {
    fun open(sourceUri: String): InputStream?
}

/** Reads the selected PDF through the Android content resolver. */
class ContentResolverSourceFingerprintReader(private val context: Context) : SourceFingerprintReader {
    override fun open(sourceUri: String): InputStream? =
        context.contentResolver.openInputStream(sourceUri.toUri())
}

/**
 * Computes the source fingerprint off the main thread.  Providers can deny
 * access or expose a non-readable stream; callers receive null and must treat
 * the source revision as unknown rather than silently inventing an identity.
 */
suspend fun fingerprintSource(
    reader: SourceFingerprintReader,
    sourceUri: String
): SourceFingerprint? = withContext(Dispatchers.IO) {
    try {
        val input = reader.open(sourceUri) ?: return@withContext null
        SourceFingerprint.fromInputStream(input)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

suspend fun fingerprintContentUri(context: Context, uri: Uri): SourceFingerprint? =
    fingerprintSource(ContentResolverSourceFingerprintReader(context), uri.toString())
