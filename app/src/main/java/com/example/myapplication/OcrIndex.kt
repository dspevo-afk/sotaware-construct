package com.example.myapplication

import android.content.Context
import android.net.Uri
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentWorkOwner
import com.example.myapplication.stage7.OcrSession
import com.example.myapplication.stage7.OcrSessionClosedException
import com.example.myapplication.stage7.OcrSessionRegistry
import com.example.myapplication.stage7.OcrSessionResourceFactory
import com.example.myapplication.stage7.OcrSessionStaleException
import com.example.myapplication.stage7.Stage7NamespaceCacheAuthority
import com.example.myapplication.stage7.Stage7NamespaceCacheTransaction
import com.example.myapplication.stage7.Stage7PublicationFence
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.IdentityHashMap
import java.util.LinkedHashMap

/**
 * OCR index that first tries PDFBox embedded text extraction, then falls back to ML Kit OCR.
 */
class OcrIndex(
    private val context: Context,
    private val workerBoundary: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary(),
    private val sessionFactory: OcrSessionResourceFactory =
        AndroidOcrSessionResourceFactory(context),
    private val sessionRegistry: OcrSessionRegistry = workerBoundary.ocrSessionRegistry,
    /** JVM-only publication hook used by deterministic cache-fence tests. */
    cachePublicationHook: (() -> Unit)? = null
) : Closeable {
    private val legacyTokens = mutableMapOf<String, DocumentSessionToken>()
    /**
     * Binds a coordinator owner to the exact OCR graph it opened.  The map is
     * identity-keyed so a rebound coordinator cannot use a token-only lookup
     * to evict the newer owner's graph.
     */
    private val ownerSessionsLock = Mutex()
    private class OwnerSessionBinding(
        val token: DocumentSessionToken,
        val session: OcrSession
    )

    private val ownerSessions = IdentityHashMap<
        DocumentWorkOwner,
        MutableMap<DocumentSessionToken, OwnerSessionBinding>
    >()
    /**
     * A cleanup reservation fences owner binding while the exact session is
     * being checked/retired.  It is intentionally separate from the worker
     * close: binders wait only for the non-suspending registry decision, so
     * the owner mutex is never held across resource closure.
     */
    private class SessionCleanupReservation(
        val token: DocumentSessionToken,
        val session: OcrSession,
        val owner: DocumentWorkOwner?,
        val binding: OwnerSessionBinding?,
        val completion: CompletableDeferred<Unit>
    )

    private val cleanupReservations = IdentityHashMap<
        OcrSession,
        SessionCleanupReservation
    >()
    private val cacheAuthority: Stage7NamespaceCacheAuthority<PageOcr> =
        cacheAuthorityFor(workerBoundary.publicationFence, cachePublicationHook)

    companion object {
        // The default/global fence owns the process-wide compatibility cache.
        // Non-global injected fences receive an explicitly isolated store and
        // one shared authority per fence, so two OcrIndex instances cannot
        // share maps while using different visibility locks.
        private fun newPageCache() = object : LinkedHashMap<String, PageOcr>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageOcr>?): Boolean {
                return size > 200
            }
        }

        private val cache = newPageCache()
        private val fullyCachedDocs = mutableMapOf<String, Any>()
        private val sharedCacheAuthority = Stage7NamespaceCacheAuthority(
            pageStore = cache,
            markerStore = fullyCachedDocs,
            publicationFence = Stage7PublicationFence.global
        )
        private val authorityRegistryLock = Any()
        private val authoritiesByFence = IdentityHashMap<
            Stage7PublicationFence,
            Stage7NamespaceCacheAuthority<PageOcr>
        >().also { it[Stage7PublicationFence.global] = sharedCacheAuthority }

        private fun cacheAuthorityFor(
            fence: Stage7PublicationFence,
            beforeCommit: (() -> Unit)?
        ): Stage7NamespaceCacheAuthority<PageOcr> = synchronized(authorityRegistryLock) {
            authoritiesByFence[fence] ?: Stage7NamespaceCacheAuthority(
                pageStore = newPageCache(),
                markerStore = mutableMapOf(),
                beforeCommit = beforeCommit,
                publicationFence = fence
            ).also { authoritiesByFence[fence] = it }
        }
        
        fun isDocumentCached(uri: Uri, cacheNamespace: String = uri.toString()): Boolean {
            return sharedCacheAuthority.isDocumentCached(cacheNamespace)
        }
        
        fun markDocumentCached(uri: Uri, cacheNamespace: String = uri.toString()) {
            sharedCacheAuthority.markDocumentCached(cacheNamespace)
        }

        fun isDocumentCached(token: DocumentSessionToken): Boolean =
            sharedCacheAuthority.isDocumentCached(token.sourceCacheKey)
    }

    /**
     * Pre-cache OCR for all pages of a document.
     * Runs in the background and reports progress via callback.
     */
    suspend fun preCacheDocument(
        token: DocumentSessionToken,
        cacheNamespace: String = token.sourceCacheKey,
        isCurrent: () -> Boolean = { true },
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        owner: DocumentWorkOwner? = null
    ): Boolean {
        var expectedSession: OcrSession? = null
        var expectedBinding: OwnerSessionBinding? = null
        try {
            return workerBoundary.withWorker {
                ensureAdmitted(token, isCurrent)
                if (cacheAuthority.isDocumentCached(cacheNamespace)) {
                    ensureAdmitted(token, isCurrent)
                    false
                } else {
                    sessionRegistry.withSession(token, sessionFactory) { session ->
                        expectedSession = session
                        owner?.let { expectedBinding = bindOwnerSession(it, token, session) }
                        cacheAuthority.withNamespaceTransaction(
                            namespace = cacheNamespace,
                            publicationAdmission = {
                                ensurePublicationAdmitted(token, isCurrent)
                            }
                        ) {
                            ensureAdmitted(token, isCurrent)
                            if (cacheAuthority.isDocumentCached(cacheNamespace)) {
                                return@withNamespaceTransaction false
                            }
                            val pageCount = session.pageCount { isCurrent() }
                            for (pageIndex in 0 until pageCount) {
                                ensureAdmitted(token, isCurrent)
                                getPageOcrOnWorker(
                                    token,
                                    pageIndex,
                                    cacheNamespace,
                                    this,
                                    session,
                                    isCurrent
                                )
                                ensureAdmitted(token, isCurrent)
                                onProgress?.let { progress ->
                                    workerBoundary.withMain {
                                        if (isCurrent()) progress(pageIndex + 1, pageCount)
                                    }
                                }
                            }
                            markDocumentCachedIfActive(token, this, isCurrent)
                            true
                        }
                    }
                }
            }
        } catch (stale: OcrSessionStaleException) {
            evictSessionOnWorker(
                token,
                stale,
                expectedSession,
                expectedBinding,
                owner = owner,
                onlyIfNoActiveLeases = true
            )
            return false
        } catch (_: OcrSessionClosedException) {
            owner?.let { forgetOwnerSession(it, token, expectedBinding, expectedSession) }
            return false
        } catch (cancelled: CancellationException) {
            evictSessionOnWorker(
                token,
                cancelled,
                expectedSession,
                expectedBinding,
                owner = owner,
                onlyIfNoActiveLeases = true
            )
            throw cancelled
        } catch (error: Throwable) {
            evictSessionOnWorker(
                token,
                error,
                expectedSession,
                expectedBinding,
                owner = owner,
                onlyIfNoActiveLeases = true
            )
            throw error
        }
    }

    suspend fun getPageOcr(
        token: DocumentSessionToken,
        pageIndex: Int,
        cacheNamespace: String = token.sourceCacheKey,
        isCurrent: () -> Boolean = { true },
        owner: DocumentWorkOwner? = null
    ): PageOcr? {
        var expectedSession: OcrSession? = null
        var expectedBinding: OwnerSessionBinding? = null
        try {
            return workerBoundary.withWorker {
                ensureAdmitted(token, isCurrent)
                val key = cacheKey(cacheNamespace, pageIndex)
                val cached = cacheAuthority.page(key)
                if (cached != null) {
                    ensureAdmitted(token, isCurrent)
                    cached
                } else {
                    sessionRegistry.withSession(token, sessionFactory) { session ->
                        expectedSession = session
                        owner?.let { expectedBinding = bindOwnerSession(it, token, session) }
                        cacheAuthority.withNamespaceTransaction(
                            namespace = cacheNamespace,
                            publicationAdmission = {
                                ensurePublicationAdmitted(token, isCurrent)
                            }
                        ) {
                            ensureAdmitted(token, isCurrent)
                            this.page(key)?.let {
                                ensureAdmitted(token, isCurrent)
                                return@withNamespaceTransaction it
                            }
                            getPageOcrOnWorker(
                                token,
                                pageIndex,
                                cacheNamespace,
                                this,
                                session,
                                isCurrent
                            )
                        }
                    }
                }
            }
        } catch (stale: OcrSessionStaleException) {
            evictSessionOnWorker(
                token,
                stale,
                expectedSession,
                expectedBinding,
                owner = owner,
                onlyIfNoActiveLeases = true
            )
            return null
        } catch (_: OcrSessionClosedException) {
            owner?.let { forgetOwnerSession(it, token, expectedBinding, expectedSession) }
            return null
        } catch (cancelled: CancellationException) {
            evictSessionOnWorker(
                token,
                cancelled,
                expectedSession,
                expectedBinding,
                owner = owner,
                onlyIfNoActiveLeases = true
            )
            throw cancelled
        } catch (error: Throwable) {
            evictSessionOnWorker(
                token,
                error,
                expectedSession,
                expectedBinding,
                owner = owner,
                onlyIfNoActiveLeases = true
            )
            throw error
        }
    }

    /** Marker read bound to this instance's exact worker/fence authority. */
    fun isDocumentCached(
        token: DocumentSessionToken,
        cacheNamespace: String = token.sourceCacheKey,
        isCurrent: () -> Boolean = { true }
    ): Boolean = isCurrent() && cacheAuthority.isDocumentCached(cacheNamespace)

    fun getCachedPageOcr(
        token: DocumentSessionToken,
        pageIndex: Int,
        cacheNamespace: String = token.sourceCacheKey,
        isCurrent: () -> Boolean = { true }
    ): PageOcr? = if (isCurrent()) cacheAuthority.page(cacheKey(cacheNamespace, pageIndex)) else null

    suspend fun closeSessionAndJoin(token: DocumentSessionToken) {
        withContext(NonCancellable) {
            workerBoundary.withWorker {
                sessionRegistry.closeSessionAndJoin(token)
            }
        }
    }

    /**
     * Closes one document session without permanently fencing its token. This
     * is used when a coordinator is rebound inside the same composition owner;
     * the owner-level [closeAndJoin] remains the terminal registry shutdown.
     */
    suspend fun evictSessionAndJoin(token: DocumentSessionToken) {
        evictSessionOnWorker(
            token = token,
            primaryFailure = null,
            expectedSession = null,
            allowUnconditional = true
        )
    }

    /**
     * Evicts only the graph bound to [owner], and only while that graph is the
     * current idle registry entry.  A rebind can therefore happen between
     * lookup and cleanup without allowing the old coordinator to close it.
     */
    suspend fun evictSessionAndJoin(
        token: DocumentSessionToken,
        owner: DocumentWorkOwner
    ) {
        withContext(NonCancellable) {
            // Capture only the old identity while holding the binding mutex.
            // The actual reservation below fences a concurrent bind without
            // holding this mutex across worker close.
            val expectedBinding = ownerSessionsLock.withLock {
                ownerSessions[owner]?.get(token)
            } ?: return@withContext

            // The registry's exact entry identity plus the owner reservation
            // and idle-lease check linearize this cleanup with a concurrent
            // rebind/use.  A newer owner binding makes this a no-op; a caller
            // that acquired E before the reservation keeps it alive through
            // the registry check, and a caller after retirement opens N.
            evictSessionOnWorker(
                token = token,
                primaryFailure = null,
                expectedSession = expectedBinding.session,
                expectedBinding = expectedBinding,
                owner = owner,
                onlyIfNoActiveLeases = true
            )
        }
    }

    suspend fun closeAndJoin() {
        try {
            withContext(NonCancellable) {
                workerBoundary.withWorker {
                    sessionRegistry.closeAndJoin()
                }
            }
        } finally {
            withContext(NonCancellable) {
                ownerSessionsLock.withLock {
                    ownerSessions.clear()
                    cleanupReservations.values.forEach { it.completion.complete(Unit) }
                    cleanupReservations.clear()
                }
            }
        }
    }

    override fun close() {
        sessionRegistry.close()
    }

    /**
     * Resource eviction is an Android close path. Keep it on the worker even
     * when the caller is the Main-bound coordinator, and never let cleanup
     * replace the operation's cancellation/failure evidence.
     */
    private suspend fun evictSessionOnWorker(
        token: DocumentSessionToken,
        primaryFailure: Throwable?,
        expectedSession: OcrSession?,
        expectedBinding: OwnerSessionBinding? = null,
        allowUnconditional: Boolean = false,
        owner: DocumentWorkOwner? = null,
        onlyIfNoActiveLeases: Boolean = false
    ) {
        // A failure before a leased session was entered has no graph to evict.
        // Never fall back to token-only removal: a newer rebind may already
        // own the same full token.
        if (expectedSession == null && !allowUnconditional) return
        val cleanupReservation = expectedSession?.let { session ->
            withContext(NonCancellable) {
                reserveSessionCleanup(token, session, owner, expectedBinding)
            }
        }
        // A different owner already holds this exact graph, or another
        // cleanup is fencing it.  In both cases this stale cleanup must not
        // proceed to an idle-session eviction.
        if (expectedSession != null && cleanupReservation == null) return
        try {
            withContext(NonCancellable) {
                workerBoundary.withWorker {
                    if (expectedSession == null) {
                        sessionRegistry.evictSessionAndJoin(token, primaryFailure)
                    } else {
                        sessionRegistry.evictSessionAndJoinIfCurrent(
                            token = token,
                            expectedSession = expectedSession,
                            primaryFailure = primaryFailure,
                            onlyIfNoActiveLeases = onlyIfNoActiveLeases
                        )
                    }
                }
            }
        } catch (closeFailure: Throwable) {
            if (primaryFailure != null) {
                if (primaryFailure.suppressed.none { it === closeFailure }) {
                    primaryFailure.addSuppressed(closeFailure)
                }
            } else {
                throw closeFailure
            }
        } finally {
            cleanupReservation?.let { reservation ->
                withContext(NonCancellable) {
                    releaseSessionCleanup(reservation)
                }
            }
        }
    }

    /** Compatibility wrappers for callers without a durable session token. */
    suspend fun preCacheDocument(
        uri: Uri,
        cacheNamespace: String = uri.toString(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) {
        preCacheDocument(legacyToken(uri), cacheNamespace, { true }, onProgress)
    }

    suspend fun getPageOcr(
        uri: Uri,
        pageIndex: Int,
        cacheNamespace: String = uri.toString()
    ): PageOcr = getPageOcr(legacyToken(uri), pageIndex, cacheNamespace, { true })
        ?: throw IOException("OCR page became unavailable")

    fun getCachedPageOcr(
        uri: Uri,
        pageIndex: Int,
        cacheNamespace: String = uri.toString()
    ): PageOcr? = cacheAuthority.page(cacheKey(cacheNamespace, pageIndex))

    private suspend fun getPageOcrOnWorker(
        token: DocumentSessionToken,
        pageIndex: Int,
        cacheNamespace: String,
        cacheTransaction: Stage7NamespaceCacheTransaction<PageOcr>,
        session: OcrSession,
        isCurrent: () -> Boolean
    ): PageOcr {
        val key = cacheKey(cacheNamespace, pageIndex)
        cacheTransaction.page(key)?.let { return it }
        val pageOcr = session.pageOcr(pageIndex) { isCurrent() }
        currentCoroutineContext().ensureActive()
        cachePageIfActive(token, key, pageOcr, cacheTransaction, isCurrent)
        return pageOcr
    }

    private suspend fun bindOwnerSession(
        owner: DocumentWorkOwner,
        token: DocumentSessionToken,
        session: OcrSession
    ): OwnerSessionBinding {
        while (true) {
            val bindingAndWait: Pair<OwnerSessionBinding?, CompletableDeferred<Unit>?> =
                ownerSessionsLock.withLock {
                    cleanupReservations[session]?.completion?.let { completion ->
                        null to completion
                    } ?: OwnerSessionBinding(token, session).also { binding ->
                        ownerSessions.getOrPut(owner) { mutableMapOf() }[token] = binding
                    }.let { binding -> binding to null }
                }
            bindingAndWait.second?.await()
                ?: return checkNotNull(bindingAndWait.first)
        }
    }

    /**
     * Reserves one exact session for owner cleanup.  The reservation is made
     * under the same mutex as binding, then the registry performs its exact
     * entry/idle-lease decision on the worker.  A binder which races this
     * reservation waits until that decision is complete; it can never bind a
     * newer owner after the check but before retirement.
     */
    private suspend fun reserveSessionCleanup(
        token: DocumentSessionToken,
        session: OcrSession,
        owner: DocumentWorkOwner?,
        expectedBinding: OwnerSessionBinding?
    ): SessionCleanupReservation? = ownerSessionsLock.withLock {
        if (cleanupReservations.containsKey(session)) return@withLock null

        if (owner != null && ownerSessions[owner]?.get(token) !== expectedBinding) {
            return@withLock null
        }

        val hasOtherOwner = ownerSessions.entries.any { entry ->
            entry.key !== owner && entry.value[token]?.session === session
        }
        if (hasOtherOwner) {
            if (owner != null) {
                forgetOwnerSessionLocked(owner, token, expectedBinding, session)
            }
            return@withLock null
        }

        SessionCleanupReservation(
            token = token,
            session = session,
            owner = owner,
            binding = expectedBinding,
            completion = CompletableDeferred<Unit>()
        ).also { reservation ->
            cleanupReservations[session] = reservation
        }
    }

    private suspend fun releaseSessionCleanup(
        reservation: SessionCleanupReservation
    ) {
        ownerSessionsLock.withLock {
            if (cleanupReservations[reservation.session] === reservation) {
                cleanupReservations.remove(reservation.session)
            }
            reservation.owner?.let { owner ->
                forgetOwnerSessionLocked(
                    owner,
                    reservation.token,
                    reservation.binding,
                    reservation.session
                )
            }
            reservation.completion.complete(Unit)
        }
    }

    private suspend fun forgetOwnerSession(
        owner: DocumentWorkOwner,
        token: DocumentSessionToken,
        expectedBinding: OwnerSessionBinding?,
        expectedSession: OcrSession? = expectedBinding?.session
    ) {
        ownerSessionsLock.withLock {
            forgetOwnerSessionLocked(owner, token, expectedBinding, expectedSession)
        }
    }

    private fun forgetOwnerSessionLocked(
        owner: DocumentWorkOwner,
        token: DocumentSessionToken,
        expectedBinding: OwnerSessionBinding?,
        expectedSession: OcrSession? = expectedBinding?.session
    ) {
        val sessionsForOwner = ownerSessions[owner] ?: return
        val current = sessionsForOwner[token]
        if ((expectedBinding == null || current === expectedBinding) &&
            (expectedSession == null || current?.session === expectedSession)
        ) {
            sessionsForOwner.remove(token)
        }
        if (sessionsForOwner.isEmpty()) ownerSessions.remove(owner)
    }

    private suspend fun cachePageIfActive(
        token: DocumentSessionToken,
        key: String,
        pageOcr: PageOcr,
        cacheTransaction: Stage7NamespaceCacheTransaction<PageOcr>,
        isCurrent: () -> Boolean
    ) {
        val cacheContext = currentCoroutineContext()
        cacheTransaction.stagePageIfActive(key, pageOcr) {
            cacheContext.ensureActive()
            if (!isCurrent()) {
                throw OcrSessionStaleException("stale OCR page for generation ${token.generation}")
            }
        }
    }

    private suspend fun markDocumentCachedIfActive(
        token: DocumentSessionToken,
        cacheTransaction: Stage7NamespaceCacheTransaction<PageOcr>,
        isCurrent: () -> Boolean
    ) {
        val cacheContext = currentCoroutineContext()
        cacheTransaction.stageMarkerIfActive {
            cacheContext.ensureActive()
            if (!isCurrent()) {
                throw OcrSessionStaleException("stale OCR marker for generation ${token.generation}")
            }
        }
    }

    private suspend fun ensureAdmitted(
        token: DocumentSessionToken,
        isCurrent: () -> Boolean
    ) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) {
            throw OcrSessionStaleException("stale OCR session for generation ${token.generation}")
        }
    }

    /** Final non-suspending fence invoked while the cache visibility lock is held. */
    private fun ensurePublicationAdmitted(
        token: DocumentSessionToken,
        isCurrent: () -> Boolean
    ) {
        if (!isCurrent()) {
            throw OcrSessionStaleException(
                "stale OCR cache publication for generation ${token.generation}"
            )
        }
    }

    private fun cacheKey(cacheNamespace: String, pageIndex: Int): String =
        "$cacheNamespace|$pageIndex"

    private fun legacyToken(uri: Uri): DocumentSessionToken = synchronized(legacyTokens) {
        legacyTokens.getOrPut(uri.toString()) {
            DocumentSessionToken(
                documentId = DocumentId.new(),
                sourceUri = uri.toString(),
                sourceFingerprint = null,
                generation = 1L
            )
        }
    }

}
