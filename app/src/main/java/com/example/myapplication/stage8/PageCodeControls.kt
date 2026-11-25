package com.example.myapplication.stage8

import android.content.Context
import android.util.AtomicFile
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.PdfSearchEngine
import com.example.myapplication.R
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentWorkOwner
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Aggregate limits for this disposable cache directory. */
internal object PageCodeCacheBudget {
    const val MAX_TOTAL_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_FILE_COUNT: Int = 64

    data class Entry(
        val key: String,
        val bytes: Long,
        val files: Int,
        val lastUsedMillis: Long,
        val active: Boolean
    )

    data class Plan(val evictKeys: List<String>, val canFit: Boolean)

    /**
     * Returns the oldest inactive entries that must be removed before a write.
     * The existing version of [incomingKey] is retained while AtomicFile writes
     * its replacement, so incoming bytes/files are charged in addition to it.
     */
    fun plan(
        entries: List<Entry>?,
        incomingKey: String,
        incomingBytes: Long,
        incomingFiles: Int = 1,
        maxBytes: Long = MAX_TOTAL_BYTES,
        maxFiles: Int = MAX_FILE_COUNT
    ): Plan? {
        // A failed directory listing cannot establish aggregate usage. Treat it
        // as unavailable so callers fail closed instead of assuming an empty cache.
        val availableEntries = entries ?: return null
        require(availableEntries.map { it.key }.distinct().size == availableEntries.size)
        require(availableEntries.all { it.bytes >= 0L && it.files >= 0 })
        require(incomingBytes >= 0L && incomingFiles >= 0 && maxBytes >= 0L && maxFiles >= 0)
        if (incomingBytes > maxBytes || incomingFiles > maxFiles) return null

        var bytes = availableEntries.fold(0L) { total, entry ->
            if (Long.MAX_VALUE - total < entry.bytes) Long.MAX_VALUE else total + entry.bytes
        }
        var files = availableEntries.fold(0L) { total, entry ->
            if (Long.MAX_VALUE - total < entry.files.toLong()) Long.MAX_VALUE else total + entry.files
        }
        val evictions = mutableListOf<String>()

        fun fits() = bytes <= maxBytes - incomingBytes && files <= maxFiles.toLong() - incomingFiles
        if (fits()) return Plan(emptyList(), canFit = true)

        val candidates = availableEntries.asSequence()
            .filter { !it.active && it.key != incomingKey }
            .sortedWith(compareBy<Entry> { it.lastUsedMillis }.thenBy { it.key })
        for (entry in candidates) {
            evictions += entry.key
            bytes = (bytes - entry.bytes).coerceAtLeast(0L)
            files = (files - entry.files).coerceAtLeast(0L)
            if (fits()) return Plan(evictions, canFit = true)
        }
        return Plan(evictions, canFit = false)
    }
}

/** A private, disposable OCR cache, keyed by document ID, exact source and fingerprint. */
internal class PageCodeCache(context: Context, token: DocumentSessionToken) {
    companion object {
        private const val MAX_ENTRY_BYTES = 2_000_000
        private val ioLock = Any()
        private val activeFiles = mutableMapOf<String, Int>()
        private val cacheEntryName = Regex("^([0-9a-f]{64})\\.json(?:\\.(?:bak|new))?$")

        private fun identity(file: File): String = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
    }

    private val key = MessageDigest.getInstance("SHA-256").digest(token.sourceCacheKey.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val directory = File(context.cacheDir, "page_codes")
    private val file = AtomicFile(File(directory, "$key.json"))
    private val activeIdentity = identity(file.baseFile)

    /** Keeps this exact cache entry out of eviction while its viewer is composed. */
    fun acquire(): AutoCloseable {
        synchronized(ioLock) { activeFiles[activeIdentity] = (activeFiles[activeIdentity] ?: 0) + 1 }
        var closed = false
        return AutoCloseable {
            synchronized(ioLock) {
                if (!closed) {
                    val count = activeFiles[activeIdentity] ?: 0
                    if (count <= 1) activeFiles.remove(activeIdentity) else activeFiles[activeIdentity] = count - 1
                    closed = true
                }
            }
        }
    }

    /** Stored codes and whether the automatic pass (not the user) produced them. */
    data class Stored(val codes: Map<Int, String>, val automatic: Boolean)

    fun read(pageCount: Int): Map<Int, String> = readStored(pageCount)?.codes.orEmpty()

    /** The stored entry, or null when this document has none yet. */
    fun readStored(pageCount: Int): Stored? = synchronized(ioLock) {
        makeRoom(incomingBytes = 0L, incomingFiles = 0, requireDirectory = false)
        val input = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return null }
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_ENTRY_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_ENTRY_BYTES)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getInt("version") == 1 && root.getInt("pageCount") == pageCount)
        val labels = root.getJSONObject("codes")
        require(labels.length() <= pageCount && pageCount in 1..PageCodeIndex.MAX_PAGES)
        val result = labels.keys().asSequence().associate { index ->
            val page = index.toInt()
            val code = labels.getString(index)
            require(page in 0 until pageCount && code.isNotBlank() && code.length <= PageCodeIndex.MAX_CODE_LENGTH)
            page to code
        }
        val automatic = root.optString("source", "manual") == "auto"
        file.baseFile.setLastModified(System.currentTimeMillis())
        Stored(result, automatic)
    }

    fun write(
        pageCount: Int,
        codes: Map<Int, String>,
        isCurrent: () -> Boolean = { true },
        automatic: Boolean = false
    ) = synchronized(ioLock) {
        check(isCurrent()) { "Page-code request is stale" }
        val labels = JSONObject()
        codes.forEach { (page, code) -> labels.put(page.toString(), code) }
        val bytes = JSONObject().put("version", 1).put("pageCount", pageCount).put("codes", labels)
            .put("source", if (automatic) "auto" else "manual")
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_ENTRY_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        if (!makeRoom(incomingBytes = bytes.size.toLong(), incomingFiles = 1, requireDirectory = true)) return@synchronized
        check(isCurrent()) { "Page-code request is stale" }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            check(isCurrent()) { "Page-code request is stale" }
            file.finishWrite(stream)
        }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }

    /**
     * Makes room without ever deleting an active or unmanaged file. Returns
     * false when active entries alone prevent a bounded write.
     */
    private fun makeRoom(incomingBytes: Long, incomingFiles: Int, requireDirectory: Boolean): Boolean {
        val entries = inventory(requireDirectory)
        var plan = PageCodeCacheBudget.plan(
            entries = entries,
            incomingKey = key,
            incomingBytes = incomingBytes,
            incomingFiles = incomingFiles
        ) ?: return false

        var remaining = entries ?: return false
        while (plan.evictKeys.isNotEmpty()) {
            plan.evictKeys.forEach { evictedKey ->
                AtomicFile(File(directory, "$evictedKey.json")).delete()
                File(directory, "$evictedKey.json.new").delete()
            }
            val updated = inventory(requireDirectory) ?: return false
            if (updated == remaining) return false
            remaining = updated
            plan = PageCodeCacheBudget.plan(
                entries = remaining,
                incomingKey = key,
                incomingBytes = incomingBytes,
                incomingFiles = incomingFiles
            ) ?: return false
        }
        return plan.canFit
    }

    /** Includes AtomicFile sidecars in the physical byte and file totals. */
    private fun inventory(requireDirectory: Boolean): List<PageCodeCacheBudget.Entry>? {
        if (!directory.isDirectory) {
            return if (!requireDirectory && !directory.exists()) emptyList() else null
        }
        val files = directory.listFiles() ?: return null
        data class Accumulator(var bytes: Long = 0L, var files: Int = 0, var lastUsed: Long = 0L, var active: Boolean = false)
        val entries = linkedMapOf<String, Accumulator>()
        files.filter { it.isFile }.forEach { child ->
            val match = cacheEntryName.matchEntire(child.name)
            val entryKey = match?.groupValues?.get(1) ?: "@unmanaged:${child.name}"
            val accumulator = entries.getOrPut(entryKey) { Accumulator() }
            val length = child.length()
            accumulator.bytes = if (Long.MAX_VALUE - accumulator.bytes < length) Long.MAX_VALUE else accumulator.bytes + length
            accumulator.files++
            accumulator.lastUsed = maxOf(accumulator.lastUsed, child.lastModified())
            if (match == null || activeFiles.containsKey(identity(File(directory, "$entryKey.json")))) {
                accumulator.active = true
            }
        }
        return entries.map { (entryKey, value) ->
            PageCodeCacheBudget.Entry(entryKey, value.bytes, value.files, value.lastUsed, value.active)
        }
    }
}

internal class PageCodeState {
    var codes by mutableStateOf<Map<Int, String>>(emptyMap())

    /** Scan progress as `done of total`, so the retry pass never shows a shrinking count. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var message by mutableStateOf<Int?>(null)

    /** Sheets whose text never arrived in the last pass, named in the completion report. */
    var unreadPages by mutableStateOf(0)

    /** A short, non-blocking report from the automatic pass (a plural or string id). */
    var autoNotice by mutableStateOf<Pair<Int, Int>?>(null)
    var job: Job? = null
    var revision = 0L
    var scan: (PageCodeRegion) -> Unit = {}
    fun cancel() { revision++; job?.cancel(); job = null; progress = null }
}

@Composable
internal fun rememberPageCodeState(
    context: Context,
    token: DocumentSessionToken?,
    pageCount: Int,
    engine: PdfSearchEngine,
    owner: DocumentWorkOwner,
    launchDocumentWork: (DocumentSessionToken, suspend () -> Unit) -> Job,
    isCurrent: (DocumentSessionToken) -> Boolean
): PageCodeState {
    val state = remember(token, pageCount) { PageCodeState() }
    val currentAdmission by rememberUpdatedState(isCurrent)
    val cache = remember(context, token) { token?.let { PageCodeCache(context, it) } }
    DisposableEffect(cache) {
        val lease = cache?.acquire()
        onDispose { lease?.close() }
    }
    LaunchedEffect(state, cache) {
        val revision = state.revision
        // A read failure is not "no entry": an unreadable entry is never replaced by a guess.
        val stored = try { Result.success(withContext(Dispatchers.IO) { cache?.readStored(pageCount) }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        if (token == null || !currentAdmission(token) || state.revision != revision) return@LaunchedEffect
        state.codes = stored.getOrNull()?.codes.orEmpty()
        // Automatic page codes: only for a document that has never had codes stored, so the
        // user's own codes (or an earlier automatic pass) are never overwritten. A manual
        // "Identify page codes" cancels this pass through state.cancel().
        if (cache == null || stored.isFailure || stored.getOrNull() != null) return@LaunchedEffect
        val autoRevision = state.revision
        state.job = launchDocumentWork(token) {
            fun accepts() = currentAdmission(token) && state.revision == autoRevision
            try {
                val result = PageCodeIndex.autoScan(pageCount, ::accepts) { page ->
                    engine.getOrBuildPageOcr(token, page, owner = owner, isAccepted = { accepts() })
                }
                if (accepts()) {
                    withContext(Dispatchers.IO) { if (accepts()) cache.write(pageCount, result.codes, ::accepts, automatic = true) }
                    if (accepts()) {
                        state.codes = result.codes
                        state.autoNotice = when {
                            result.codes.isEmpty() -> R.string.page_codes_auto_none to 0
                            result.codes.size < pageCount -> R.plurals.page_codes_auto_partial to result.codes.size
                            else -> null
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* quiet pass: the manual box is the fix */ }
            finally { if (state.revision == autoRevision) state.job = null }
        }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    state.scan = scan@{ region ->
        if (token == null || !currentAdmission(token)) return@scan
        state.cancel()
        val revision = state.revision
        state.message = null
        // The progress dialog counts the sheets of the document, so it always names a sheet
        // the user can count. See PageCodeIndex.scan, which reports against the same total.
        state.progress = 0 to pageCount
        state.job = launchDocumentWork(token) {
            fun accepts() = currentAdmission(token) && state.revision == revision
            try {
                val result = PageCodeIndex.scan(pageCount, region, ::accepts,
                    loadPage = { page ->
                        engine.getOrBuildPageOcr(token, page, owner = owner, isAccepted = { accepts() })
                    }, onProgress = { done, total -> state.progress = done to total })
                val codes = result.codes
                if (accepts()) {
                    withContext(Dispatchers.IO) { if (accepts()) cache?.write(pageCount, codes, ::accepts) }
                    if (accepts()) {
                        state.codes = codes
                        // Sheets whose text never arrived are named in the report: a sheet number
                        // drawn as graphics cannot be read, and the user must not be left counting
                        // registered sheets against their set.
                        state.message = when {
                            codes.isEmpty() -> R.string.page_codes_none
                            result.unreadPages > 0 -> R.plurals.page_codes_unread
                            else -> R.plurals.page_codes_complete
                        }
                        state.unreadPages = result.unreadPages
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (accepts()) state.message = R.string.page_codes_failed }
            finally { if (state.revision == revision) { state.progress = null; state.job = null } }
        }
    }
    return state
}

@Composable
internal fun PageCodeStatus(state: PageCodeState, pageCount: Int) {
    val notice = state.autoNotice
    if (notice != null) {
        val text = if (notice.first == R.plurals.page_codes_auto_partial)
            pluralStringResource(notice.first, pageCount, notice.second, pageCount)
        else stringResource(notice.first)
        val toastContext = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(notice) {
            android.widget.Toast.makeText(toastContext, text, android.widget.Toast.LENGTH_LONG).show()
            state.autoNotice = null
        }
    }
    val progress = state.progress
    if (progress != null) AlertDialog(
        onDismissRequest = state::cancel,
        title = { Text(stringResource(R.string.identify_page_codes)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.page_codes_progress, progress.first, progress.second))
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
        } },
        confirmButton = { TextButton(onClick = state::cancel) { Text(stringResource(android.R.string.cancel)) } }
    )
    state.message?.let { message -> AlertDialog(
        onDismissRequest = { state.message = null },
        title = { Text(stringResource(R.string.identify_page_codes)) },
        text = { Text(when (message) {
            R.plurals.page_codes_complete ->
                pluralStringResource(message, pageCount, state.codes.size, pageCount)
            R.plurals.page_codes_unread ->
                pluralStringResource(message, state.unreadPages, state.codes.size, pageCount, state.unreadPages)
            else -> stringResource(message)
        }) },
        confirmButton = { TextButton(onClick = { state.message = null }) { Text(stringResource(android.R.string.ok)) } }
    ) }
}
