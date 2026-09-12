package com.example.myapplication.stage6

import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.Stage5Limits
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Worker-only, operation-owned copy. The renderer never reopens the provider URI. */
internal suspend fun <T> withVerifiedPdfSource(
    cacheDirectory: File,
    expected: SourceFingerprint,
    openSource: () -> InputStream?,
    render: suspend (File) -> T
): T = withVerifiedPdfSource(
    temporaryOwner = PdfExportTemporaryOwner(cacheDirectory),
    expected = expected,
    openSource = openSource,
    render = render
)

/** Uses the same dedicated owner as result staging when the caller has one. */
internal suspend fun <T> withVerifiedPdfSource(
    temporaryOwner: PdfExportTemporaryOwner,
    requestId: String = UUID.randomUUID().toString(),
    expected: SourceFingerprint,
    openSource: () -> InputStream?,
    render: suspend (File) -> T
): T {
    require(expected.byteCount > 0L) { "PDF source fingerprint is empty" }
    currentCoroutineContext().ensureActive()
    val staged = temporaryOwner.createSourceFile(requestId)
    var failure: Throwable? = null
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var zeroReads = 0
        val buffer = ByteArray(64 * 1024)
        (openSource() ?: throw IOException("PDF source is unavailable")).use { input ->
            Files.newOutputStream(staged.toPath()).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        if (++zeroReads > Stage5Limits.MAX_ZERO_READS) throw IOException("PDF source made no progress")
                        continue
                    }
                    zeroReads = 0
                    if (read.toLong() > expected.byteCount - count) throw IOException("PDF source revision changed")
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    count += read
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (count != expected.byteCount || !actual.equals(expected.digestHex, ignoreCase = true)) {
            throw IOException("PDF source revision changed")
        }
        currentCoroutineContext().ensureActive()
        temporaryOwner.touch(staged)
        return render(staged)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            temporaryOwner.release(staged)
        } catch (cleanup: Throwable) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}
