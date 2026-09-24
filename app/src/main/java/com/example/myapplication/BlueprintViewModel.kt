package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage6.PdfExportRequestOwner
import com.example.myapplication.stage7.ByteAwareCachePutResult
import com.example.myapplication.stage7.Stage7CacheKey
import com.example.myapplication.stage7.Stage7OwnedResource
import com.example.myapplication.stage8.AnnotationReducer
import com.example.myapplication.stage9.DriveAuthorizationResolutionTracker

/**
 * The history state that must travel with a canonical replacement rollback.
 * The live annotation maps are restored from the canonical snapshot itself;
 * this detached checkpoint restores only the undo/redo reachability that was
 * intentionally invalidated while the replacement was admitted.
 */
internal data class CanonicalHistoryCheckpoint(
    val reducer: AnnotationReducer.HistoryOwner.Checkpoint
)

internal data class PendingCanonicalHistoryReplacement(
    val history: CanonicalHistoryCheckpoint,
    val previousSnapshot: DocumentSnapshotV1,
    val replacementSnapshot: DocumentSnapshotV1
)

class BlueprintViewModel : ViewModel() {
    /**
     * The reducer is recreated with the UI, but its history and replacement
     * epoch belong to the document ViewModel.  This is the lifecycle owner for
     * both undo/redo reachability and stale-closure admission.
     */
    internal val annotationHistory = AnnotationReducer.HistoryOwner()
    internal val documentHostHandoff = com.example.myapplication.stage3.DocumentHostHandoff()
    internal val pdfExportRequests = PdfExportRequestOwner(viewModelScope)
    val pageScales = mutableStateMapOf<Int, PageScale>()
    val pagePaths = mutableStateMapOf<Int, SnapshotStateList<DrawnPath>>()
    val pageMeasurements = mutableStateMapOf<Int, SnapshotStateList<Measurement>>()
    val pageNotes = mutableStateMapOf<Int, SnapshotStateList<Note>>()
    val pagePhotoPins = mutableStateMapOf<Int, SnapshotStateList<PhotoPin>>()
    val pageShapes = mutableStateMapOf<Int, SnapshotStateList<Shape>>()
    private var appliedCanonicalSource: DocumentSourceIdentityV1? = null
    private var historyDocumentAssociation: com.example.myapplication.stage2.DocumentAssociation? = null

    internal fun recordHistoryDocument(association: com.example.myapplication.stage2.DocumentAssociation) {
        historyDocumentAssociation = association
    }

    /** Only a new UI coordinator reopening the identical verified document may reuse this owner. */
    internal fun canRetainHistoryForTarget(association: com.example.myapplication.stage2.DocumentAssociation): Boolean {
        val prior = historyDocumentAssociation ?: return false
        return prior.documentId == association.documentId &&
            prior.source.sourceUri == association.source.sourceUri &&
            prior.sourceFingerprint != null && prior.sourceFingerprint == association.sourceFingerprint
    }

    /**
     * Kept only until the enclosing canonical/photo transaction reports
     * success.  A failed replacement can therefore restore the exact history
     * that belonged to the live state it displaced.
     */
    private var pendingCanonicalReplacementHistory: PendingCanonicalHistoryReplacement? = null
    // Memory thumbnails are keyed by an explicit verified-source namespace and
    // page. The adapter owns actual byte accounting, LRU eviction, and UI
    // observable state; a stale A thumbnail cannot appear for B.
    val thumbnailCache = Stage7BitmapCache()
    // Search highlights per page (survives rotation)
    val pageHighlights = mutableStateMapOf<Int, List<RectF>>()
    val pageSearchTerms = mutableStateMapOf<Int, String>()
    internal val driveAuthorizationResolutionTracker = DriveAuthorizationResolutionTracker()
    private var retainedDriveSyncManager: DriveSyncManager? = null

    /** Keeps the Drive authority owner aligned with this ViewModel across Activity recreation. */
    internal fun getOrCreateDriveSyncManager(applicationContext: Context): DriveSyncManager =
        synchronized(this) {
            retainedDriveSyncManager
                ?: DriveSyncManager(applicationContext.applicationContext).also {
                    retainedDriveSyncManager = it
                }
        }

    /** Main-thread cache mutation; ownership transfers only after admission. */
    fun putThumbnail(
        key: Stage7CacheKey<String>,
        owner: Stage7OwnedResource<Bitmap>
    ): ByteAwareCachePutResult = thumbnailCache.putOwned(key, owner)

    /** Compatibility entry point for an already-owned raw bitmap. */
    fun putThumbnail(key: String, bitmap: Bitmap): ByteAwareCachePutResult =
        thumbnailCache.put(Stage7CacheKey("legacy", key), bitmap)

    /** Clears all namespaces while preserving leases held by displayed items. */
    fun clearThumbnailCache() = thumbnailCache.clear()

    fun clearSession() {
        pageScales.clear()
        pagePaths.clear()
        pageMeasurements.clear()
        pageNotes.clear()
        pagePhotoPins.clear()
        pageShapes.clear()
        annotationHistory.resetForSession()
        appliedCanonicalSource = null
        historyDocumentAssociation = null
        pendingCanonicalReplacementHistory = null
        clearThumbnailCache()
        pageHighlights.clear()
        pageSearchTerms.clear()
    }

    override fun onCleared() {
        pdfExportRequests.close()
        thumbnailCache.close()
        super.onCleared()
    }

    internal fun annotationHistoryEpoch(): Long = annotationHistory.epoch

    /**
     * Called only after the incoming snapshot has been fully materialized into
     * the live maps.  Equal canonical content keeps valid user history; a real
     * replacement advances the epoch and invalidates all stale entries.
     */
    internal fun markCanonicalSnapshotApplied(
        source: DocumentSourceIdentityV1,
        changed: Boolean,
        historyBefore: CanonicalHistoryCheckpoint? = null,
        previousSnapshot: DocumentSnapshotV1? = null,
        replacementSnapshot: DocumentSnapshotV1? = null
    ) {
        appliedCanonicalSource = source.copy(providerMetadata = source.providerMetadata.toMap())
        if (changed) {
            pendingCanonicalReplacementHistory = if (historyBefore != null &&
                previousSnapshot != null && replacementSnapshot != null) {
                PendingCanonicalHistoryReplacement(historyBefore, previousSnapshot, replacementSnapshot)
            } else null
            invalidateHistoryForCanonicalReplacement()
        }
    }

    internal fun canonicalSourceOrNull(): DocumentSourceIdentityV1? = appliedCanonicalSource

    /** Complete photo reachability supplied to the post-commit GC boundary. */
    internal fun retainedPhotoNamesForPhotoRetention(): Set<String> = buildSet {
        addAll(annotationHistory.retainedPhotoNames())
    }

    /** Capture detached reducer and compatibility history before replacement. */
    internal fun captureCanonicalHistoryCheckpoint(): CanonicalHistoryCheckpoint =
        CanonicalHistoryCheckpoint(
            reducer = annotationHistory.captureCheckpoint()
        )

    /** Restore detached history after the old canonical snapshot is live again. */
    internal fun restoreCanonicalHistoryCheckpoint(checkpoint: CanonicalHistoryCheckpoint) {
        annotationHistory.restoreCheckpoint(checkpoint.reducer)
    }

    /** Restore history captured for the most recent accepted replacement. */
    internal fun restorePendingCanonicalReplacementHistory(
        rollbackSnapshot: DocumentSnapshotV1,
        replacedLiveSnapshot: DocumentSnapshotV1
    ): Boolean {
        val pending = pendingCanonicalReplacementHistory ?: return false
        // A later no-op compensation cannot borrow an older transaction's history.
        if (pending.previousSnapshot.source.sourceUri != rollbackSnapshot.source.sourceUri ||
            pending.previousSnapshot.pages != rollbackSnapshot.pages ||
            pending.replacementSnapshot.source.sourceUri != replacedLiveSnapshot.source.sourceUri ||
            pending.replacementSnapshot.pages != replacedLiveSnapshot.pages) return false
        restoreCanonicalHistoryCheckpoint(pending.history)
        pendingCanonicalReplacementHistory = null
        return true
    }

    /** Drop the rollback checkpoint after the enclosing transaction commits. */
    internal fun commitCanonicalReplacementHistory() {
        pendingCanonicalReplacementHistory = null
    }

    internal fun invalidateHistoryForCanonicalReplacement() {
        annotationHistory.invalidateForReplacement()
    }
}
