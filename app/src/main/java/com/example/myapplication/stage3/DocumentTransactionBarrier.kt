package com.example.myapplication.stage3

import com.example.myapplication.stage2.DocumentId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared per-document transaction boundary for Stage 3 and Stage 4.
 *
 * The registry is deliberately keyed by durable [DocumentId], rather than by
 * a display name, URI, account, or session generation.  Callers must acquire
 * this boundary before taking the switch mutex or the autosave save mutex.
 * Stage 4 remote acceptance keeps it through durable local replacement,
 * in-memory replacement, and its accepted-metadata success boundary.
 */
class DocumentTransactionBarrier {
    private val mutexes = ConcurrentHashMap<DocumentId, Mutex>()

    private fun mutexFor(documentId: DocumentId): Mutex =
        mutexes.computeIfAbsent(documentId) { Mutex() }

    suspend fun <T> withDocument(
        documentId: DocumentId,
        block: suspend () -> T
    ): T = mutexFor(documentId).withLock { block() }
}
