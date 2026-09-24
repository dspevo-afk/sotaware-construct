package com.example.myapplication

import android.content.ContentResolver
import android.content.ContentValues
import com.example.myapplication.stage9.DiagnosticFacts
import com.example.myapplication.stage9.refreshUnexpectedBackupGrant
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.example.myapplication.stage8.PageCodeRegion
import com.example.myapplication.stage8.PageCodeRegionSelector
import com.example.myapplication.stage8.PageCodeStatus
import com.example.myapplication.stage8.rememberPageCodeState
import com.example.myapplication.stage8.appearance
import com.example.myapplication.stage8.appearanceMode
import com.example.myapplication.stage8.changeAppearance
import com.example.myapplication.stage8.changeImageAppearance
import com.example.myapplication.stage8.buildMeasurement
import com.example.myapplication.stage8.measurementSourceLength
import com.example.myapplication.stage8.ViewerFling
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.example.myapplication.stage8.formatFeet
import com.example.myapplication.stage8.ViewerTransform
import com.example.myapplication.stage8.PdfSelectionKey
import com.example.myapplication.stage8.PhotoAnnotationStack
import com.example.myapplication.stage8.MeasurementPointSelection
import com.example.myapplication.ui.ViewerFloatingControl
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.stage6.PdfExportOutcome
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.ToolRail
import com.example.myapplication.ui.HudOverlay
import com.example.myapplication.ui.ViewerTopBar
import com.example.myapplication.ui.InstructionBanner
import com.example.myapplication.ui.FloatingViewerControls
import com.example.myapplication.stage1.documentSourceIdentityForSnapshot
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentDurableSnapshotState
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.fingerprintContentUri
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage3.AndroidDocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage9.DiagnosticEvent
import com.example.myapplication.stage9.DriveAuthorizationApplyResult
import com.example.myapplication.stage9.DriveAuthorizationRequestResult
import com.example.myapplication.stage9.DriveAuthorizationResolutionContract
import com.example.myapplication.stage9.DriveAuthorizationResolutionRequest
import com.example.myapplication.stage9.GoogleCredentialDriveAuth
import com.example.myapplication.stage9.GoogleDriveAuthClient
import com.example.myapplication.stage9.PendingDriveAuthorizationResolution
import com.example.myapplication.stage9.changeDriveAuthority
import com.example.myapplication.stage9.SafeDiagnostics
import com.example.myapplication.stage3.DocumentWorkToken
import com.example.myapplication.stage3.DocumentWorkOwner
import com.example.myapplication.stage3.SessionSnapshotApplyResult
import com.example.myapplication.stage3.restoreAlreadyActiveSession
import com.example.myapplication.stage3.SwitchFailure
import com.example.myapplication.stage3.SwitchFailureStage
import com.example.myapplication.stage3.SwitchResult
import com.example.myapplication.stage8.AnnotationReducer
import com.example.myapplication.stage9b.PhotoAssetCapture
import com.example.myapplication.stage9b.PhotoAssetSet
import androidx.compose.ui.graphics.nativeCanvas
import com.example.myapplication.stage9b.RecentDocumentRecord
import com.example.myapplication.stage9b.RecentDocumentReadResult
import com.example.myapplication.stage9b.RecentDocumentWriteResult
import com.example.myapplication.stage9b.SharedPreferencesRecentDocumentStore
import com.example.myapplication.projects.ProjectBrowserScreen
import com.example.myapplication.projects.ProjectDrawing
import com.example.myapplication.projects.existingProjectSource
import com.example.myapplication.projects.rememberProjectBrowserState
import com.example.myapplication.projects.takePersistableReadGrant
import com.example.myapplication.projects.sourceUsesTreeGrant
import com.example.myapplication.stage8.AnnotationHistoryLimits
import com.example.myapplication.stage8.Stage8InteractionController
import com.example.myapplication.stage8.AnnotationGeometry
import com.example.myapplication.stage8.AnnotationSize
import com.example.myapplication.stage8.OcrSelection
import com.example.myapplication.stage4.DynamicDriveGateway
import com.example.myapplication.stage4.FileSyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncCoordinator
import com.example.myapplication.stage4.SyncError
import com.example.myapplication.stage4.SyncOutcome
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage4.SyncBinding
import com.example.myapplication.stage4.SnapshotApplyResult
import com.example.myapplication.stage4.SyncSessionBridge
import com.example.myapplication.stage4.RemoteSnapshotEnvelope
import com.example.myapplication.stage4.RemoteAdoptionCandidate
import com.example.myapplication.stage4.PhotoContentPreparation
import com.example.myapplication.stage4.StagedPhotoContentTransaction
import com.example.myapplication.stage4.validatedPhotoFiles
import com.example.myapplication.stage4.runNonCancellableFinalizers
import com.example.myapplication.stage4.runSyncCoordinatorLifecycleFinalizer
import com.example.myapplication.stage5.CameraCaptureStore
import com.example.myapplication.stage5.CameraCaptureActivity
import com.example.myapplication.stage5.CAMERA_CAPTURE_OPERATION_ID_EXTRA
import com.example.myapplication.stage5.CameraCaptureOperationRecord
import com.example.myapplication.stage5.CameraCaptureOperationStatus
import com.example.myapplication.stage5.CameraCaptureRecovery
import com.example.myapplication.stage5.CameraCaptureRecoveryDisposition
import com.example.myapplication.stage5.CameraCaptureStableIdentity
import com.example.myapplication.stage5.CameraCaptureOperationRequest
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.PhotoRetentionAuthority
import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.readBoundedUtf8
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage6.DocumentBundleService
import com.example.myapplication.stage6.BundlePhotoCapture
import com.example.myapplication.stage6.DocumentBundleImportWorkflowOutcome
import com.example.myapplication.stage6.DocumentBundlePhotoStore
import com.example.myapplication.stage6.DocumentBundleWorkflow
import com.example.myapplication.stage6.DocumentBundleWorkflowHost
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_EXTENSION
import com.example.myapplication.stage7.Stage7OwnedResource
import com.example.myapplication.stage7.Stage7ResourceOwner
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.example.myapplication.stage7.googleMlKitRecognitionTask
import com.example.myapplication.stage7.runOcrRecognitionTask
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.example.myapplication.stage7.BitmapSizePlan
import com.example.myapplication.stage7.ByteAwareCachePutResult
import com.example.myapplication.stage7.Stage7CacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
// Play Services Vision removed; ML Kit is used for OCR fallback
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.LinkedHashMap

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.io.PushbackInputStream
import java.nio.file.Files
import java.util.UUID
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix

// Use a local debug flag to gate temporary diagnostic logs
internal const val DEBUG_LOG = false
const val STAGE8_INITIAL_PDF_URI_EXTRA = "com.sotaware.construct.stage8.INITIAL_PDF_URI"

/**
 * Compose's pointer-input cancellation adaptation is not an Android
 * ACTION_CANCEL PointerEvent.  SuspendingPointerInputModifierNodeImpl copies
 * the last pressed changes into a synthetic all-up event and marks those
 * changes initially consumed before dispatching it.  A real UP is delivered
 * from the MotionEvent path and is not initially consumed.  Read that
 * incoming consumption before this handler consumes any change of its own.
 */
internal fun androidx.compose.ui.input.pointer.PointerEvent.isIncomingCancellation(): Boolean {
    val changes = this.changes
    if (changes.isEmpty() || changes.any { it.pressed }) return false
    // A preceding multi-pointer release can remain in the copied change list;
    // only changes that were still pressed when Compose canceled the stream
    // receive the synthetic initial-consumed marker.
    val activeChanges = changes.filter { it.previousPressed }
    return activeChanges.isNotEmpty() && activeChanges.all { it.isConsumed }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize pdfbox-android resource loader so bundled glyphlist/resources are available
        try { PDFBoxResourceLoader.init(applicationContext) } catch (t: Throwable) { SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = t) }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BlueprintApp(
                        initialPdfUri = intent.getStringExtra(STAGE8_INITIAL_PDF_URI_EXTRA)?.toUri()
                    )
                }
            }
        }
    }
}
// removed ML Kit helper; OCR fallback uses Play Services Vision TextRecognizer inline

enum class ToolMode(val label: String, val icon: ImageVector) { 
    PAN("Pan", Icons.Default.PanTool),
    MEASURE("Measure", Icons.Default.Straighten), 
    POLYLINE("Polyline", Icons.Default.Polyline),
    SCALE("Calibrate", Icons.Default.SquareFoot),
    PEN("Pen", Icons.Default.Create),
    HIGHLIGHTER("Highlighter", Icons.Default.Highlight),
    NOTE("Note", Icons.Default.StickyNote2),
    PHOTO("Photo", Icons.Default.CameraAlt),
    SHAPE("Shape", Icons.Default.Category)
}
enum class ShapeType { RECTANGLE, CIRCLE, ARROW, CLOUD }

enum class Screen { SELECTOR, BROWSER, VIEWER, SETTINGS, DRIVE_SETTINGS }


/** Deep, immutable-at-capture copies used by the session-bound PDF exporter. */
private fun DrawnPath.copyForPdfExport() = copy(
    points = points.map(Point::copyPoint)
)

private fun Measurement.copyForPdfExport() = copyMeasurement(
    p1 = p1.copyPoint(),
    p2 = p2.copyPoint()
)

private fun Note.copyForPdfExport() = copyNote()

private fun Shape.copyForPdfExport() = copyShape()

private fun PhotoPin.copyForPdfExport() = PhotoPin(
    x = x,
    y = y,
    id = id,
    imageFileNames = imageFileNames.toMutableList(),
    imageNotes = imageNotes.mapValues { (_, notes) ->
        notes.map(PhotoImageNote::copyImageNote).toMutableList()
    }.toMutableMap(),
    imageShapes = imageShapes.mapValues { (_, imageShapes) ->
        imageShapes.map(Shape::copyShape).toMutableList()
    }.toMutableMap(),
    imagePaths = imagePaths.mapValues { (_, paths) -> paths.map { it.copyPath() } },
    imageMeasurements = imageMeasurements.mapValues { (_, values) -> values.map { it.copyMeasurement() } },
    imageScales = imageScales.mapValues { it.value.copy() }
)

internal fun photoBytesFor(
    context: Context,
    sessionToken: DocumentSessionToken?,
    reference: String
): ByteArray? {
    validatePhotoFileName(reference)
    val documentId = sessionToken?.documentId ?: return null
    return DocumentPhotoAssetStore(context.filesDir, documentId).use { store ->
        try {
            store.read(reference)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

internal fun recycleBitmap(bitmap: Bitmap) {
    runCatching {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * Bounds-decodes a photo for the measured viewport, applies the existing EXIF
 * display transform, and keeps the transform peak within the Stage 7 policy.
 * This function is deliberately blocking; callers must invoke it through the
 * Stage 7 worker boundary.
 */
internal fun decodePhotoBitmapWithExif(
    photoBytes: ByteArray,
    viewportWidthPx: Int? = null,
    viewportHeightPx: Int? = null,
    onSourceAspect: ((Float) -> Unit)? = null
): Stage7OwnedResource<Bitmap>? {
    val owner = Stage7ResourceOwner<Bitmap>(::recycleBitmap)
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size, bounds)
        val sampling = BitmapBudgetPolicy.photoDecodePlan(
            sourceWidthPx = bounds.outWidth,
            sourceHeightPx = bounds.outHeight,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx
        ) ?: run {
            owner.close()
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampling.inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = false
        }
        val originalOwner = owner.ownedCreatedOrNull {
            BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size, options)
        } ?: run {
            owner.close()
            return null
        }
        val original = originalOwner.value
        val actualOriginal = if (original.config == Bitmap.Config.ARGB_8888) {
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = original.width,
                heightPx = original.height,
                actualAllocationBytes = actualBitmapAllocationBytes(original)
            )
        } else {
            null
        }
        if (actualOriginal == null ||
            original.width > sampling.target.width ||
            original.height > sampling.target.height
        ) {
            owner.close()
            return null
        }
        val orientation = try {
            ByteArrayInputStream(photoBytes).use { exif ->
                ExifInterface(exif).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val orientationPlan = BitmapBudgetPolicy.exifOrientationPlan(
            sourceWidthPx = original.width,
            sourceHeightPx = original.height,
            orientation = orientation
        ) ?: run {
            owner.close()
            return null
        }
        val swapsAxes = orientation in listOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
        onSourceAspect?.invoke(if (swapsAxes) bounds.outWidth.toFloat() / bounds.outHeight else bounds.outHeight.toFloat() / bounds.outWidth)
        val displayBitmap = if (orientationPlan.requiresBitmapTransform) {
            val plannedTransform = BitmapBudgetPolicy.exifTransformPlan(
                sourceWidthPx = original.width,
                sourceHeightPx = original.height,
                orientation = orientation
            ) ?: run {
                owner.close()
                return null
            }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                else -> {
                    owner.close()
                    return null
                }
            }
            val transformed = try {
                owner.ownCreated {
                    Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                owner.close()
                return null
            }
            val actualTransform = if (transformed.config == Bitmap.Config.ARGB_8888) {
                BitmapBudgetPolicy.actualTransformPlan(
                    sourceWidthPx = original.width,
                    sourceHeightPx = original.height,
                    sourceAllocationBytes = actualOriginal.allocationBytes,
                    transformedWidthPx = transformed.width,
                    transformedHeightPx = transformed.height,
                    transformedAllocationBytes = actualBitmapAllocationBytes(transformed)
                )
            } else {
                null
            }
            val transformedIsBounded = transformed !== original &&
                transformed.config == Bitmap.Config.ARGB_8888 &&
                transformed.width == plannedTransform.transformed.width &&
                transformed.height == plannedTransform.transformed.height &&
                actualTransform != null
            if (!transformedIsBounded) {
                if (transformed !== original) owner.release(transformed)
                owner.close()
                return null
            }
            transformed
        } else {
            original
        }
        if (displayBitmap !== original) owner.release(original)
        owner.owned(displayBitmap)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        owner.close()
        throw cancelled
    } catch (error: Exception) {
        owner.close()
        null
    } catch (error: Throwable) {
        owner.close()
        throw error
    }
}

internal fun loadPhotoBitmapBlocking(
    context: Context,
    sessionToken: DocumentSessionToken?,
    reference: String,
    viewportWidthPx: Int? = null,
    viewportHeightPx: Int? = null,
    onSourceAspect: ((Float) -> Unit)? = null
): Stage7OwnedResource<Bitmap>? {
    return photoBytesFor(context, sessionToken, reference)?.let { photoBytes ->
        decodePhotoBitmapWithExif(photoBytes, viewportWidthPx, viewportHeightPx, onSourceAspect)
    }
}

data class PageMarkups(
    val paths: List<DrawnPath>,
    val measurements: List<Measurement>,
    val notes: List<Note>,
    val photoPins: List<PhotoPin> = emptyList(),
    val shapes: List<Shape> = emptyList()
) : Serializable



/**
 * Activity-result anchors must survive the picker Activity/process boundary,
 * but the token itself is not a Bundle-saveable type.  Keep every identity
 * component in a saveable string list and fail closed if restored state is
 * malformed or incomplete.
 */
internal fun saveDocumentSessionTokenState(token: DocumentSessionToken?): List<String> {
    if (token == null) return emptyList()
    val fingerprint = token.sourceFingerprint
    return listOf(
        token.documentId.value,
        token.sourceUri,
        fingerprint?.algorithm.orEmpty(),
        fingerprint?.digestHex.orEmpty(),
        fingerprint?.byteCount?.toString().orEmpty(),
        token.generation.toString()
    )
}

internal fun restoreDocumentSessionTokenState(values: List<String>): DocumentSessionToken? {
    if (values.isEmpty()) return null
    return runCatching {
        require(values.size == 6) { "invalid saved document session token" }
        val hasFingerprint = values[2].isNotEmpty() ||
            values[3].isNotEmpty() ||
            values[4].isNotEmpty()
        val fingerprint = if (!hasFingerprint) {
            null
        } else {
            require(values[2].isNotEmpty() && values[3].isNotEmpty() && values[4].isNotEmpty()) {
                "incomplete saved source fingerprint"
            }
            SourceFingerprint(
                algorithm = values[2],
                digestHex = values[3],
                byteCount = values[4].toLong()
            )
        }
        DocumentSessionToken(
            documentId = DocumentId.parse(values[0]),
            sourceUri = values[1],
            sourceFingerprint = fingerprint,
            generation = values[5].toLong()
        )
    }.getOrNull()
}

private val documentSessionTokenSaver = listSaver<DocumentSessionToken?, String>(
    save = { token -> saveDocumentSessionTokenState(token) },
    restore = ::restoreDocumentSessionTokenState
)

/**
 * Cleans up both document-owned OCR and sync work during a switch. The
 * caller remains suspended until both owners have been attempted, even when
 * the first close fails.
 */
suspend fun runDocumentWorkCleanupFinalizer(
    evictOcr: suspend () -> Unit,
    cancelSync: suspend () -> Unit
) = runNonCancellableFinalizers(evictOcr, cancelSync)

/**
 * Search admission seam used by the Compose effect. The page and query
 * revision are read through live accessors so an older request cannot publish
 * after navigation or a same-page query replacement. The work token still
 * carries the captured request identity for the actual search operation.
 */
fun acceptsCurrentPageSearchWork(
    coordinator: DocumentSwitchCoordinator,
    candidate: DocumentWorkToken,
    currentPageIndex: () -> Int,
    queryRevision: () -> Long
): Boolean = coordinator.accepts(
    candidate,
    currentPageIndex = currentPageIndex(),
    currentQueryRevision = queryRevision()
)

/** Source-compatible fixed-revision overload for non-Compose callers/tests. */
fun acceptsCurrentPageSearchWork(
    coordinator: DocumentSwitchCoordinator,
    candidate: DocumentWorkToken,
    currentPageIndex: () -> Int,
    queryRevision: Long
): Boolean = acceptsCurrentPageSearchWork(
    coordinator = coordinator,
    candidate = candidate,
    currentPageIndex = currentPageIndex,
    queryRevision = { queryRevision }
)

/**
 * Page cards must not admit a viewer route until the coordinator has applied
 * the target snapshot.  The callback can outlive the composition that
 * created it, so repeat the session fence at the point of navigation.
 */
internal fun acceptsBrowserPageSelection(
    activeSessionToken: DocumentSessionToken?,
    readySessionToken: DocumentSessionToken?,
    pageIndex: Int,
    pageCount: Int,
    isCurrent: (DocumentSessionToken) -> Boolean,
    isCurrentApplied: (DocumentSessionToken) -> Boolean
): Boolean {
    val token = activeSessionToken ?: return false
    return pageIndex in 0 until pageCount &&
        token == readySessionToken &&
        isCurrent(token) &&
        isCurrentApplied(token)
}

/** Clear search progress only for the request that owns the flag. */
fun clearSearchProgressIfOwned(
    activeRequestRevision: Long,
    requestRevision: Long,
    clear: () -> Unit
): Boolean {
    if (activeRequestRevision != requestRevision) return false
    clear()
    return true
}

sealed class PageItem {
    data class Path(val data: DrawnPath) : PageItem()
    data class Measure(val data: Measurement) : PageItem()
    data class NoteItem(val data: Note, val ordinal: Int = -1) : PageItem()
    data class PhotoPinItem(val data: PhotoPin) : PageItem()
    data class ShapeItem(val data: Shape) : PageItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlueprintApp(
    vm: BlueprintViewModel = viewModel(),
    initialPdfUri: Uri? = null,
    /** Narrow test/host observation seam; production behavior remains unchanged. */
    onStage8EffectConsumed: (() -> Unit)? = null,
    /** Host-injected auth boundaries allow device tests without a real Google account. */
    driveSyncManagerOverride: DriveSyncManager? = null,
    googleDriveAuthOverride: GoogleDriveAuthClient? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val compositionDocumentHosts = remember(vm) {
        linkedSetOf<com.example.myapplication.stage3.DocumentHostHandoff.Owner>()
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val localDocumentRepository = remember(context) { LocalDocumentRepository(context) }
    val documentBundleService = remember(context) {
        DocumentBundleService(stagingDirectory = File(context.filesDir, "bundle-staging"), trustedRootDirectory = context.filesDir)
    }
    
    var pdfUri by rememberSaveable { mutableStateOf(initialPdfUri) }
    var currentScreen by rememberSaveable { mutableStateOf(Screen.SELECTOR) }
    var selectedPageIndex by rememberSaveable { mutableIntStateOf(0) }
    var totalPageCount by rememberSaveable { mutableIntStateOf(0) }
    var toolMode by rememberSaveable { mutableStateOf(ToolMode.PAN) }
    var toolSettingsMode by remember { mutableStateOf<ToolMode?>(null) }
    val stage8Interactions = remember { Stage8InteractionController() }
    var clearDialogRevision by remember { mutableIntStateOf(0) }
    var activePhotoTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingClearPhotoTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    
    val recentStore = remember(context) { SharedPreferencesRecentDocumentStore(context) }
    var recentFiles by remember { mutableStateOf<List<RecentDocumentRecord>>(emptyList()) }
    var recentLoadFailed by remember { mutableStateOf(false) }
    val projectBrowser = rememberProjectBrowserState()
    val toolSettingsStore = remember { com.example.myapplication.stage8.DrawingToolSettingsStore(context) }
    var toolSettings by remember { mutableStateOf(com.example.myapplication.stage8.DrawingToolSettings()) }
    var toolSettingsSaving by remember { mutableStateOf(false) }
    var toolSettingsError by remember { mutableStateOf(false) }
    var toolScopeRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(recentStore) {
        when (val loaded = withContext(Dispatchers.IO) { recentStore.read() }) {
            is RecentDocumentReadResult.Loaded -> { recentFiles = loaded.records; recentLoadFailed = false }
            is RecentDocumentReadResult.Failed -> { recentLoadFailed = true; SafeDiagnostics.warn(DiagnosticEvent.INPUT_REJECTED) }
        }
    }
    var searchTerm by rememberSaveable { mutableStateOf("") }
    var searchTrigger by rememberSaveable { mutableIntStateOf(0) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var searchOnlyCurrentPage by remember { mutableStateOf(false) }
    // Document-wide search state
    var documentSearchTerm by rememberSaveable { mutableStateOf("") }
    var showDocumentSearchDialog by remember { mutableStateOf(false) }
    var documentSearchInput by remember { mutableStateOf("") }
    var documentSearchActive by rememberSaveable { mutableStateOf(false) }
    var documentSearching by remember { mutableStateOf(false) }
    var documentSearchRevision by rememberSaveable { mutableLongStateOf(0L) }
    var pagesWithMatches by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var documentSearchResults by remember { mutableStateOf<Map<Int, List<RectF>>>(emptyMap()) }
    // pageHighlights and pageSearchTerms are in ViewModel (vm.pageHighlights, vm.pageSearchTerms)
    var foundCount by rememberSaveable { mutableIntStateOf(0) }
    var showFoundDialog by remember { mutableStateOf(false) }

    // One lifecycle-scoped worker boundary owns expensive PDF/image work.
    // The coordinator still owns session transitions on Main.immediate.
    val stage7Worker = remember { Stage7WorkerResourceBoundary() }
    // Create a single PdfSearchEngine instance scoped to this Composable. Reusing the
    // engine ensures OCR caches persist across searches and uses the same worker seam.
    val ocrIndex = remember(stage7Worker) { OcrIndex(context, stage7Worker) }
    val pdfSearchEngine = remember(stage7Worker, ocrIndex) {
        PdfSearchEngine(context, stage7Worker, ocrIndex)
    }
    var searching by remember { mutableStateOf(false) }
    // Query revision owning the visible search progress. A canceled older
    // effect may clear progress only while it still owns this revision.
    var activeSearchRequestRevision by rememberSaveable { mutableLongStateOf(0L) }
    var searchDone by remember { mutableIntStateOf(0) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var ocrCachingProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }  // (done, total)
    
    // Google Drive sync state
    val driveSyncManager = remember(vm, context.applicationContext, driveSyncManagerOverride) {
        driveSyncManagerOverride ?: vm.getOrCreateDriveSyncManager(context.applicationContext)
    }
    val googleCredentialDriveAuth = remember(context.applicationContext, googleDriveAuthOverride) {
        googleDriveAuthOverride ?: GoogleCredentialDriveAuth(context.applicationContext, BuildConfig.GOOGLE_WEB_CLIENT_ID)
    }
    val driveAuthorizationStatus by driveSyncManager.authorizationStatus.collectAsState()
    val syncMetadataStore = remember(context) { FileSyncMetadataStore(context) }
    val syncGateway = remember(driveSyncManager) {
        DynamicDriveGateway { driveSyncManager.stage4Gateway() }
    }
    val isSignedIn = driveAuthorizationStatus.isAuthorized
    val signedInAccountId = driveAuthorizationStatus.identity
        ?.email
        ?.takeIf { isSignedIn }
    val backupFolderName = driveAuthorizationStatus.backupFolder?.name
    val backupFolderId = driveAuthorizationStatus.backupFolder?.id
    var syncBlocked by remember { mutableStateOf(false) }  // Blocks sync if user rejected remote update
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updatePdfName by remember { mutableStateOf("") }
    var updateSessionToken by remember { mutableStateOf<DocumentSessionToken?>(null) }
    var updateBinding by remember { mutableStateOf<SyncBinding?>(null) }
    var showRemoteUpdateDialog by remember { mutableStateOf(false) }
    var remoteUpdatePdfName by remember { mutableStateOf("") }
    var remoteUpdateSessionToken by remember { mutableStateOf<DocumentSessionToken?>(null) }
    var remoteUpdateBinding by remember { mutableStateOf<SyncBinding?>(null) }
    var showAdoptionDialog by remember { mutableStateOf(false) }
    var pendingAdoptionCandidate by remember { mutableStateOf<RemoteAdoptionCandidate?>(null) }
    var pendingAdoptionBinding by remember { mutableStateOf<SyncBinding?>(null) }
    // Settings preferences
    val settingsPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var hintsDisabled by remember { mutableStateOf(settingsPrefs.getBoolean("hints_disabled", false)) }
    
    // Debounced sync trigger - increments when user makes changes
    var syncTrigger by remember { mutableIntStateOf(0) }

    var activeSessionToken by remember { mutableStateOf<DocumentSessionToken?>(null) }
    LaunchedEffect(activeSessionToken, selectedPageIndex) {
        stage8Interactions.clearPendingOnContextChange()
        clearDialogRevision++
    }
    var readySessionToken by remember { mutableStateOf<DocumentSessionToken?>(null) }
    var activeSyncBinding by remember { mutableStateOf<SyncBinding?>(null) }
    // One shared per-document barrier is the cross-stage transaction boundary
    // for switching/autosave and remote acceptance.
    val documentTransactionBarrier = remember { DocumentTransactionBarrier() }
    var coordinatorRef: DocumentSwitchCoordinator? = null
    var syncCoordinatorRef: SyncCoordinator? = null

    // Retain each owner identity across callback/coordinator rebinding. Old
    // cleanup must never resolve through the latest mutable coordinator ref.
    val coordinatorsByOwner = remember {
        mutableMapOf<DocumentWorkOwner, DocumentSwitchCoordinator>()
    }
    val syncCoordinatorsByOwner = remember {
        mutableMapOf<DocumentWorkOwner, SyncCoordinator>()
    }

    val startDocumentBackgroundWorkForOwner:
        (DocumentSession, DocumentWorkOwner) -> Unit = { session, owner ->
        readySessionToken = session.token
        val syncCoordinator = syncCoordinatorsByOwner[owner]
        val accountRoot = driveSyncManager.currentSyncAccountRoot()
        if (accountRoot != null) {
            syncCoordinator?.updateCurrentScope(
                SyncScope(accountRoot.first, accountRoot.second, session.token.documentId)
            )
        } else {
            syncCoordinator?.updateCurrentScope(null)
        }
        val coordinator = coordinatorsByOwner[owner]
        if (coordinator != null) {
            val workToken = DocumentWorkToken(session.token)
            coordinator.launchDocumentJob(session.token) {
                try {
                    ocrIndex.preCacheDocument(
                        token = session.token,
                        cacheNamespace = session.token.sourceCacheKey,
                        isCurrent = { coordinator.accepts(workToken) },
                        onProgress = { done, total ->
                            if (coordinator.accepts(workToken)) {
                                ocrCachingProgress = done to total
                            }
                        },
                        owner = owner
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    SafeDiagnostics.error(DiagnosticEvent.OCR_ACTIVITY, error = error)
                    if (coordinator.accepts(workToken)) {
                        stage7Worker.withMain {
                            if (coordinator.accepts(workToken)) {
                                Toast.makeText(
                                    context,
                                    "OCR preparation failed: ${error.message ?: "the document could not be prepared"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } finally {
                    if (coordinator.accepts(workToken)) ocrCachingProgress = null
                }
            }
        }
    }

    val startDocumentBackgroundWork: (DocumentSession) -> Unit = { session ->
        coordinatorRef?.let { coordinator ->
            startDocumentBackgroundWorkForOwner(session, coordinator.documentWorkOwner)
        }
    }

    val cancelAndJoinWorkForOwner:
        suspend (DocumentSession, DocumentWorkOwner) -> Unit = { session, owner ->
        runDocumentWorkCleanupFinalizer(
            { ocrIndex.evictSessionAndJoin(session.token, owner) },
            { syncCoordinatorsByOwner[owner]?.cancelForSessionAndJoin(session.token) }
        )
    }
    val cancelAndJoinWork: suspend (DocumentSession) -> Unit = { session ->
        coordinatorRef?.let { coordinator ->
            cancelAndJoinWorkForOwner(session, coordinator.documentWorkOwner)
        }
    }

    val documentCallbacks = remember(
        vm,
        context,
        localDocumentRepository,
        syncMetadataStore,
        isSignedIn,
        signedInAccountId,
        backupFolderId,
        ocrIndex
    ) {
        AndroidDocumentSessionCallbacks.withDefaultPageLoader(
            context = context,
            viewModel = vm,
            repository = localDocumentRepository,
            workerBoundary = stage7Worker,
            onSessionEstablished = { session ->
                syncCoordinatorRef?.invalidateCurrentScope()
                activeSessionToken = session.token
                readySessionToken = null
                pdfUri = session.token.sourceUri.toUri()
                currentScreen = Screen.BROWSER
                selectedPageIndex = 0
                totalPageCount = 0
                searchTerm = ""
                searchInput = ""
                searchOnlyCurrentPage = false
                showSearchDialog = false
                showDocumentSearchDialog = false
                showFoundDialog = false
                syncBlocked = false
                showUpdateDialog = false
                updatePdfName = ""
                updateSessionToken = null
                updateBinding = null
                showRemoteUpdateDialog = false
                remoteUpdatePdfName = ""
                remoteUpdateSessionToken = null
                remoteUpdateBinding = null
                showAdoptionDialog = false
                pendingAdoptionCandidate = null
                pendingAdoptionBinding = null
                activeSyncBinding = null
                documentSearchTerm = ""
                documentSearchInput = ""
                documentSearchActive = false
                documentSearching = false
                documentSearchRevision++
                pagesWithMatches = emptySet()
                documentSearchResults = emptyMap()
                searching = false
                activeSearchRequestRevision = 0L
                ocrCachingProgress = null
            },
            onStateCleared = {
                syncCoordinatorRef?.invalidateCurrentScope()
                activeSessionToken = null
                pdfUri = null
                currentScreen = Screen.SELECTOR
                readySessionToken = null
                selectedPageIndex = 0
                totalPageCount = 0
                showSearchDialog = false
                showDocumentSearchDialog = false
                showFoundDialog = false
                syncBlocked = false
                showUpdateDialog = false
                updatePdfName = ""
                updateSessionToken = null
                updateBinding = null
                showRemoteUpdateDialog = false
                remoteUpdatePdfName = ""
                remoteUpdateSessionToken = null
                remoteUpdateBinding = null
                showAdoptionDialog = false
                pendingAdoptionCandidate = null
                pendingAdoptionBinding = null
                activeSyncBinding = null
                pagesWithMatches = emptySet()
                documentSearchResults = emptyMap()
                documentSearching = false
                documentSearchRevision++
                searching = false
                activeSearchRequestRevision = 0L
                ocrCachingProgress = null
            },
            onPageCount = { session, count ->
                if (coordinatorRef?.isCurrent(session.token) == true) {
                    totalPageCount = count
                }
            },
            onRecovered = {
                Toast.makeText(context, context.getString(R.string.snapshot_recovered), Toast.LENGTH_LONG).show()
            },
            onFailure = { failure ->
                scope.launch(Dispatchers.Main.immediate) {
                    SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED)
                    val message = when (failure.stage) {
                        SwitchFailureStage.OUTGOING_FLUSH -> "Local annotations were not durably saved; the current document remains open."
                        SwitchFailureStage.RESOLVE_TARGET -> "The selected PDF could not be verified; the current document remains open."
                        SwitchFailureStage.TARGET_LOAD, SwitchFailureStage.TARGET_APPLY -> "The selected PDF could not be loaded safely; the current document was preserved."
                        SwitchFailureStage.CANCELLED -> "Document switching was cancelled; the current document remains open."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            onStart = startDocumentBackgroundWork,
            cancelAndJoinWork = cancelAndJoinWork,
            onStartWithOwner = startDocumentBackgroundWorkForOwner,
            cancelAndJoinWorkWithOwner = cancelAndJoinWorkForOwner,
            resumeWork = startDocumentBackgroundWork,
            resumeWorkWithOwner = startDocumentBackgroundWorkForOwner,
            photoRecoveryMetadataIdentity = { association ->
                val accountRoot = driveSyncManager.currentSyncAccountRoot()
                if (accountRoot == null) {
                    null
                } else {
                    val metadataScope = SyncScope(accountRoot.first, accountRoot.second, association.documentId)
                    when (val metadata = syncMetadataStore.read(metadataScope)) {
                        is MetadataReadResult.Loaded -> syncMetadataStore.recoveryIdentity(
                            metadata.metadata ?: SyncMetadata(scope = metadataScope)
                        )
                        is MetadataReadResult.Failed -> throw PhotoCanonicalRecoveryException(
                            "sync metadata could not be verified during photo recovery",
                            IllegalStateException(metadata.error.toString())
                        )
                    }
                }
            }
        )
    }
    val documentHost = remember(vm, documentCallbacks, scope, documentTransactionBarrier) {
        vm.documentHostHandoff.newOwner()
    }
    val sessionCoordinator = remember(documentCallbacks, scope, documentTransactionBarrier, documentHost) {
        DocumentSwitchCoordinator(
            callbacks = documentCallbacks,
            parentScope = scope,
            coordinatorDispatcher = Dispatchers.Main.immediate,
            transactionBarrier = documentTransactionBarrier,
            publicationFence = stage7Worker.publicationFence,
            beforeSwitch = documentHost::activate
        )
    }
    coordinatorRef = sessionCoordinator
    coordinatorsByOwner[sessionCoordinator.documentWorkOwner] = sessionCoordinator

    var viewerPdfName by remember { mutableStateOf("Document") }
    LaunchedEffect(activeSessionToken, pdfUri) {
        val uri = pdfUri
        val token = activeSessionToken
        if (uri == null) {
            viewerPdfName = "Document"
            return@LaunchedEffect
        }

        // Stage 1/3 source metadata is already available without another
        // provider query. Only the fallback display-name lookup crosses the
        // worker boundary, so composition never performs ContentResolver I/O.
        val metadataName = token?.let { currentToken ->
            sessionCoordinator.currentSession()
                ?.takeIf { it.token == currentToken }
                ?.target
                ?.association
                ?.source
                ?.displayName
        }
        if (!metadataName.isNullOrBlank()) {
            viewerPdfName = metadataName.removeSuffix(".pdf")
            return@LaunchedEffect
        }

        val loadedName = stage7Worker.withWorker {
            getPdfName(context, uri)
        }
        stage7Worker.withMain {
            if (token == activeSessionToken &&
                (token == null || sessionCoordinator.isCurrentApplied(token))
            ) {
                viewerPdfName = loadedName
            }
        }
    }

    suspend fun awaitReadyStage6Session(): DocumentSession {
        val restored = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            snapshotFlow {
                val session = sessionCoordinator.currentSession()
                if (session != null &&
                    activeSessionToken == session.token &&
                    readySessionToken == session.token &&
                    sessionCoordinator.isCurrentApplied(session.token)
                ) {
                    session
                } else {
                    null
                }
            }.filterNotNull().first()
        }
        return requireNotNull(restored) {
            "the active document session was not restored before import"
        }
    }

    fun currentSyncScope(session: DocumentSession? = sessionCoordinator.currentSession()): SyncScope? {
        // Authority is live, even while a 401 StateFlow update is waiting for
        // recomposition. Captured UI values cannot keep an old route admitted.
        val (accountId, rootId) = driveSyncManager.currentSyncAccountRoot() ?: return null
        if (session == null) return null
        if (readySessionToken != session.token || !sessionCoordinator.isCurrentApplied(session.token)) {
            return null
        }
        return SyncScope(accountId, rootId, session.token.documentId)
    }

    fun currentSyncBinding(session: DocumentSession? = sessionCoordinator.currentSession()): SyncBinding? {
        val coordinator = syncCoordinatorRef ?: return null
        val currentScope = currentSyncScope(session)
        coordinator.updateCurrentScope(currentScope)
        val candidate = activeSyncBinding
        return candidate?.takeIf {
            session != null &&
                it.token == session.token &&
                it.scope == currentScope &&
                coordinator.isBindingCurrent(it)
        }
    }

    /**
     * Called while the shared document transaction barrier is held.  The
     * accepted snapshot is only the transition that completed; cleanup must
     * recapture both current authorities so a photo attached after admission
     * is protected from generated-photo GC.
     */
    suspend fun cleanupPhotoContentAfterCanonicalCommit(
        session: DocumentSession,
        acceptedSnapshot: DocumentSnapshotV1
    ) {
        validateSnapshot(acceptedSnapshot)
        val durableState = try {
            // This reads the exact accepted current/previous pair without
            // promoting or mutating recovery state.  The call remains inside
            // the shared document transaction owned by the caller.
            localDocumentRepository.captureDurableSnapshotState(session.target.association)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Throwable) {
            throw PhotoCanonicalRecoveryException(
                "durable snapshot pair could not be read before post-commit photo cleanup",
                error
            )
        }
        val currentDurableSnapshot = durableState.current?.snapshot
            ?: throw PhotoCanonicalRecoveryException(
                "durable current snapshot disappeared before post-commit photo cleanup"
            )
        val currentLiveSnapshot = sessionCoordinator.captureCurrentSnapshotWithinDocumentTransaction(session.token)
            ?: throw PhotoCanonicalRecoveryException(
                "live snapshot became unavailable before post-commit photo cleanup"
            )
        validateSnapshot(currentDurableSnapshot)
        validateSnapshot(currentLiveSnapshot)
        val activeCapturePhotoNames = try {
            CameraCaptureStore(context.filesDir).use { cameraStore ->
                val recovery = cameraStore.recovery()
                val recoveryState = recovery.inspect()
                when (recoveryState.disposition) {
                    CameraCaptureRecoveryDisposition.CORRUPT,
                    CameraCaptureRecoveryDisposition.IO_FAILURE -> throw PhotoCanonicalRecoveryException(
                        "camera recovery evidence could not be verified before photo cleanup",
                        recoveryState.error
                    )
                    else -> {
                        val operation = recoveryState.operation
                        if (operation != null &&
                            operation.documentId == session.token.documentId.value
                        ) {
                            recovery.retainedPublishedPhotoNames()
                        } else {
                            emptySet()
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Throwable) {
            throw PhotoCanonicalRecoveryException(
                "camera recovery evidence could not be read before photo cleanup",
                error
            )
        }
        DocumentPhotoAssetStore(context.filesDir, session.token.documentId).use { store ->
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = currentDurableSnapshot,
                    currentLiveSnapshot = currentLiveSnapshot,
                    previousDurableSnapshot = durableState.previous?.snapshot,
                    retainedPhotoNames = vm.retainedPhotoNamesForPhotoRetention(),
                    activeCapturePhotoNames = activeCapturePhotoNames
                )
            )
        }
        // The old history checkpoint is useful only while this enclosing
        // canonical/photo transaction is still compensatable.
        vm.commitCanonicalReplacementHistory()
    }

    val syncBridge = remember(sessionCoordinator, localDocumentRepository) {
        object : SyncSessionBridge {
            override fun currentSession(scope: SyncScope): DocumentSession? =
                sessionCoordinator.currentSession()?.takeIf { it.token.documentId == scope.documentId }

            override suspend fun captureSnapshot(session: DocumentSession) =
                sessionCoordinator.captureCurrentSnapshot(session.token)

            override suspend fun captureSnapshotWithinDocumentTransaction(session: DocumentSession) =
                sessionCoordinator.captureCurrentSnapshotWithinDocumentTransaction(session.token)

            override suspend fun captureDurableSnapshot(session: DocumentSession) =
                when (val loaded = localDocumentRepository.load(session.target.association)) {
                    is DocumentLoadResult.Loaded -> loaded.snapshot
                    DocumentLoadResult.NotFound -> null
                    is DocumentLoadResult.Failed -> throw IllegalStateException(
                        "previous durable snapshot could not be read: ${loaded.error}"
                    )
                }

            override suspend fun persistSnapshot(
                session: DocumentSession,
                snapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ): DocumentSaveResult = sessionCoordinator.persistCurrentSnapshot(session.token, snapshot)
                ?: DocumentSaveResult.Failed(
                    LocalRepositoryError.InvalidSnapshot("session is no longer current")
                )

            override fun isCurrent(token: DocumentSessionToken): Boolean =
                sessionCoordinator.isCurrent(token)

            override fun isReady(token: DocumentSessionToken): Boolean =
                sessionCoordinator.isCurrentApplied(token)

            override suspend fun hasRequiredPhotoContentForAdmission(
                session: DocumentSession,
                currentDurableSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1,
                currentLiveSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ): Boolean = withContext(Dispatchers.IO) {
                DocumentPhotoAssetStore(context.filesDir, session.token.documentId).use { store ->
                    store.hasRequiredPhotoContent(
                        currentDurableSnapshot,
                        currentLiveSnapshot
                    )
                }
            }

            override suspend fun reconcilePhotoContent(
                session: DocumentSession,
                currentDurableSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1,
                currentLiveSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ) = withContext(Dispatchers.IO) {
                DocumentPhotoAssetStore(context.filesDir, session.token.documentId).use { store ->
                    // This is the active document-open/coordinator boundary:
                    // reconcile any cross-store intent first, then collect
                    // generated orphans against the durable/live authority
                    // union while a live edit is still awaiting persistence.
                    store.reconcilePhotoContent(currentDurableSnapshot, currentLiveSnapshot)
                }
            }

            override suspend fun cleanupPhotoContentAfterCommit(
                session: DocumentSession,
                acceptedSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ) = withContext(Dispatchers.IO) {
                cleanupPhotoContentAfterCanonicalCommit(session, acceptedSnapshot)
            }

            override suspend fun capturePhotoContentForAdmission(
                session: DocumentSession,
                currentDurableSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1,
                currentLiveSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ): PhotoAssetCapture {
                var captured: PhotoAssetCapture? = null
                try {
                    return withContext(Dispatchers.IO) {
                        DocumentPhotoAssetStore(context.filesDir, session.token.documentId).use { store ->
                            store.capturePhotoAssetsForAdmission(currentDurableSnapshot, currentLiveSnapshot)
                                .also { captured = it }
                        }
                    }
                } catch (error: Throwable) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        try { captured?.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    }
                    throw error
                }
            }

            override suspend fun preparePhotoContent(
                session: DocumentSession,
                remote: RemoteSnapshotEnvelope
            ): PhotoContentPreparation = withContext(Dispatchers.IO) {
                try {
                    val photoFiles = validatedPhotoFiles(remote.snapshot, remote.photoFiles)
                    if (photoFiles.isEmpty()) {
                        PhotoContentPreparation(DocumentSaveResult.Saved(session.token.documentId))
                    } else {
                        val transaction = DocumentPhotoAssetStore(context.filesDir, session.token.documentId).use { store ->
                            StagedPhotoContentTransaction.stage(
                                rootDirectory = store.resolver.root,
                                photoFiles = photoFiles,
                                trustedRootDirectory = context.filesDir
                            )
                        }
                        PhotoContentPreparation(
                            result = DocumentSaveResult.Saved(session.token.documentId),
                            transaction = transaction
                        )
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: PhotoCanonicalRecoveryException) {
                    PhotoContentPreparation(
                        result = DocumentSaveResult.Failed(
                            LocalRepositoryError.IoFailure(
                                operation = "prepare remote photo content",
                                path = File(
                                    context.filesDir,
                                    "documents/${session.token.documentId.value}/photos"
                                ).absolutePath,
                                detail = error.message ?: error.toString()
                            )
                        )
                    )
                } catch (error: Stage5ValidationException) {
                    PhotoContentPreparation(
                        result = DocumentSaveResult.Failed(
                            LocalRepositoryError.IoFailure(
                                operation = "prepare remote photo content",
                                path = File(
                                    context.filesDir,
                                    "documents/${session.token.documentId.value}/photos"
                                ).absolutePath,
                                detail = error.message ?: error.toString()
                            )
                        )
                    )
                } catch (error: IOException) {
                    PhotoContentPreparation(
                        result = DocumentSaveResult.Failed(
                            LocalRepositoryError.IoFailure(
                                operation = "prepare remote photo content",
                                path = File(
                                    context.filesDir,
                                    "documents/${session.token.documentId.value}/photos"
                                ).absolutePath,
                                detail = error.message ?: error.toString()
                            )
                        )
                    )
                } catch (error: SecurityException) {
                    PhotoContentPreparation(
                        result = DocumentSaveResult.Failed(
                            LocalRepositoryError.IoFailure(
                                operation = "prepare remote photo content",
                                path = File(
                                    context.filesDir,
                                    "documents/${session.token.documentId.value}/photos"
                                ).absolutePath,
                                detail = error.message ?: error.toString()
                            )
                        )
                    )
                }
            }

            override fun applySnapshotReplace(
                session: DocumentSession,
                snapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ) {
                require(sessionCoordinator.isCurrent(session.token)) { "sync session is no longer current" }
                com.example.myapplication.stage1.applySnapshotReplace(snapshot, vm)
            }

            override suspend fun persistAndApplySnapshot(
                binding: SyncBinding,
                session: DocumentSession,
                snapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ): SnapshotApplyResult = when (
                val result = sessionCoordinator.persistAndApplyCurrentSnapshot(binding.token, snapshot) {
                    sessionCoordinator.currentSession()?.let { currentSyncBinding(it) == binding } == true
                }
            ) {
                SessionSnapshotApplyResult.Applied -> SnapshotApplyResult.Applied
                SessionSnapshotApplyResult.Stale -> SnapshotApplyResult.Stale
                is SessionSnapshotApplyResult.Failed -> SnapshotApplyResult.Failed(result.error)
            }

            override suspend fun persistAndApplySnapshotWithinDocumentTransaction(
                binding: SyncBinding,
                session: DocumentSession,
                snapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ): SnapshotApplyResult = when (
                val result = sessionCoordinator.persistAndApplyCurrentSnapshotWithinDocumentTransaction(
                    binding.token,
                    snapshot
                ) {
                    sessionCoordinator.currentSession()?.let { currentSyncBinding(it) == binding } == true
                }
            ) {
                SessionSnapshotApplyResult.Applied -> SnapshotApplyResult.Applied
                SessionSnapshotApplyResult.Stale -> SnapshotApplyResult.Stale
                is SessionSnapshotApplyResult.Failed -> SnapshotApplyResult.Failed(result.error)
            }

            override suspend fun restoreSnapshotWithinDocumentTransaction(
                binding: SyncBinding,
                session: DocumentSession,
                durableSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1,
                liveSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
            ): SnapshotApplyResult = when (
                val result = sessionCoordinator.restoreSnapshotWithinDocumentTransaction(
                    binding.token,
                    durableSnapshot,
                    liveSnapshot
                )
            ) {
                SessionSnapshotApplyResult.Applied -> SnapshotApplyResult.Applied
                SessionSnapshotApplyResult.Stale -> SnapshotApplyResult.Stale
                is SessionSnapshotApplyResult.Failed -> SnapshotApplyResult.Failed(result.error)
            }

            override fun onConflict(
                binding: SyncBinding,
                remote: com.example.myapplication.stage4.RemoteDocumentMetadata
            ) {
                val currentSession = sessionCoordinator.currentSession()
                if (currentSession == null || currentSyncBinding(currentSession) != binding) return
                remoteUpdatePdfName = remote.displayName
                remoteUpdateSessionToken = binding.token
                remoteUpdateBinding = binding
                syncBlocked = true
                showRemoteUpdateDialog = true
            }

            override fun onPendingAdoption(
                binding: SyncBinding,
                candidate: RemoteAdoptionCandidate
            ) {
                val currentSession = sessionCoordinator.currentSession()
                if (currentSession == null || currentSyncBinding(currentSession) != binding) return
                pendingAdoptionCandidate = candidate
                pendingAdoptionBinding = binding
                showAdoptionDialog = true
            }

            override fun onError(binding: SyncBinding, error: SyncError) {
                val currentSession = sessionCoordinator.currentSession()
                if (currentSession == null || currentSyncBinding(currentSession) != binding) return
                SafeDiagnostics.error(DiagnosticEvent.SYNC_ACTIVITY)
            }
        }
    }
    val syncCoordinator = remember(syncGateway, syncMetadataStore, syncBridge, scope, documentTransactionBarrier) {
        SyncCoordinator(
            gateway = syncGateway,
            metadataStore = syncMetadataStore,
            bridge = syncBridge,
            parentScope = scope,
            dispatcher = Dispatchers.Main.immediate,
            documentTransactionBarrier = documentTransactionBarrier,
            currentScopeProvider = { currentSyncScope(sessionCoordinator.currentSession()) }
        )
    }
    syncCoordinatorRef = syncCoordinator
    syncCoordinatorsByOwner[sessionCoordinator.documentWorkOwner] = syncCoordinator

    fun markDocumentDirty() {
        sessionCoordinator.markDocumentDirty()
        val session = sessionCoordinator.currentSession()
        val binding = currentSyncBinding(session)
        if (binding != null) {
            syncCoordinator.markDirty(binding)
        } else if (session != null && sessionCoordinator.isCurrentApplied(session.token)) {
            // A ready applied session remains locally durable while signed
            // out/offline, but a provisional target must not create a dirty
            // marker for its cleared placeholder.
            syncCoordinator.markDirtyForDocument(session.token.documentId, session.token)
        }
        if (session != null && sessionCoordinator.isCurrentApplied(session.token)) {
            syncTrigger++
        }
    }

    // Every ordinary persisted-domain mutation enters local autosave and the
    // coordinator's single debounced Drive request path.
    fun triggerDebouncedSync() = markDocumentDirty()

    fun triggerImmediateSync(reason: SyncReason = SyncReason.IMMEDIATE) {
        // Local Stage 2/3 durability is independent of Drive availability.
        markDocumentDirty()
        val binding = currentSyncBinding() ?: return
        syncCoordinator.enqueueUpload(binding, reason)
    }

    // One reducer owns PDF/image note and shape transitions. Its effect sink
    // enters the established dirty/local-save/sync path; the reducer itself
    // performs no I/O and emits no effect for rejected mutations.
    val annotationReducer = remember(
        vm,
        activeSessionToken?.documentId,
        activeSessionToken?.sourceCacheKey,
        activeSessionToken?.generation,
        vm.annotationHistoryEpoch()
    ) {
        AnnotationReducer(
            vm,
            effectSink = {
                markDocumentDirty()
                onStage8EffectConsumed?.invoke()
            },
            sessionActivePredicate = {
                val current = sessionCoordinator.currentSession()
                activeSessionToken != null && readySessionToken == activeSessionToken &&
                    current?.token == activeSessionToken && sessionCoordinator.isCurrentApplied(activeSessionToken!!)
            },
            sessionKey = activeSessionToken,
            currentSessionKey = { sessionCoordinator.currentSession()?.token }
        )
    }
    fun undoAnnotation(page: Int) {
        if (!annotationReducer.acceptsCurrentSession()) return
        annotationReducer.undo(page)
    }
    fun redoAnnotation(page: Int) {
        if (!annotationReducer.acceptsCurrentSession()) return
        annotationReducer.redo(page)
    }
    fun canUndoAnnotation(page: Int) = annotationReducer.acceptsCurrentSession() &&
        annotationReducer.canUndo(page)
    fun canRedoAnnotation(page: Int) = annotationReducer.acceptsCurrentSession() &&
        annotationReducer.canRedo(page)
    fun deleteAnnotationItem(page: Int, item: PageItem) {
        when (item) {
            is PageItem.NoteItem -> if (item.ordinal >= 0) {
                annotationReducer.deletePdfNoteAt(page, item.ordinal, item.data)
            } else {
                annotationReducer.deletePdfNote(page, item.data)
            }
            is PageItem.ShapeItem -> annotationReducer.deletePdfShape(page, item.data)
            is PageItem.Path -> annotationReducer.deletePdfPath(page, item.data)
            is PageItem.Measure -> annotationReducer.deleteMeasurement(page, item.data)
            is PageItem.PhotoPinItem -> annotationReducer.deletePhotoPin(page, item.data)
        }
    }

    // Camera ownership is deliberately above the page renderer.  The
    // renderer can be recreated by paging, orientation, or navigation, while
    // this launcher and the operation journal remain registered at the
    // document-owner boundary.
    val cameraOperationMutex = remember { Mutex() }
    var cameraDrainRevision by remember { mutableLongStateOf(0L) }
    var cameraReturnedOperationId by remember { mutableStateOf<String?>(null) }
    var cameraRecoveryOperation by remember { mutableStateOf<CameraCaptureOperationRecord?>(null) }
    var cameraRecoveryMessage by remember { mutableStateOf<String?>(null) }
    // A coordinator is the owner of the monotonic session-generation counter.
    // Host recreation may rebind that coordinator and reset its local counter,
    // so a new coordinator is also an explicit new camera owner.  Within one
    // owner, the durable operation still requires the exact generation.
    val cameraOperationOwnerId = remember(sessionCoordinator) {
        UUID.randomUUID().toString()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // The result payload is only a wake-up/fence.  The journal remains the
        // authority, so a missing or stale intent can never attach a photo.
        cameraReturnedOperationId = result.data?.getStringExtra(CAMERA_CAPTURE_OPERATION_ID_EXTRA)
        cameraDrainRevision++
    }

    fun clearCameraRecoveryPrompt() {
        cameraRecoveryOperation = null
        cameraRecoveryMessage = null
    }

    fun promptCameraRecovery(
        operation: CameraCaptureOperationRecord?,
        message: String
    ) {
        cameraRecoveryOperation = operation
        cameraRecoveryMessage = message
    }

    fun promptCameraRecoveryFor(
        operation: CameraCaptureOperationRecord,
        detail: String? = null
    ) {
        val message = when (operation.status) {
            CameraCaptureOperationStatus.PREPARED ->
                "A camera capture was prepared but was not launched. Discard it before starting another capture."
            CameraCaptureOperationStatus.LAUNCHED ->
                "A camera capture is still awaiting its external result. Keep waiting, or abandon it only after confirming that no camera is open."
            CameraCaptureOperationStatus.RESULT_AVAILABLE,
            CameraCaptureOperationStatus.PROCESSING,
            CameraCaptureOperationStatus.PUBLISHED ->
                detail ?: "A captured photo is ready for recovery. Open the original document to finish attaching it."
            CameraCaptureOperationStatus.RESULT_CANCELLED,
            CameraCaptureOperationStatus.COMMITTED,
            CameraCaptureOperationStatus.DISCARDED ->
                detail ?: "The camera capture remains available for safe recovery."
        }
        promptCameraRecovery(operation, message)
    }

    suspend fun readCameraOperation(): CameraCaptureOperationRecord? =
        stage7Worker.withWorker {
            CameraCaptureStore(context.filesDir).use { store ->
                val maintenance = store.reconcileInterruptedJournalAndSweep()
                if (!maintenance.journal.resolved) {
                    throw com.example.myapplication.stage5.CameraCaptureOperationCorruptException(
                        "Interrupted camera publication remains unresolved"
                    )
                }
                store.readOperation()
            }
        }

    suspend fun cameraOperationOrFallback(
        fallback: CameraCaptureOperationRecord
    ): CameraCaptureOperationRecord {
        return try {
            readCameraOperation() ?: fallback
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The caller is already handling a recovery failure. Keep the
            // operation that triggered it available for the explicit prompt;
            // never let a second journal read failure suppress recovery UI.
            fallback
        }
    }

    /** Cleanup is used only for a terminal operation; LAUNCHED is never aged out. */
    suspend fun discardCameraOperationDurably(operationId: String): Boolean =
        stage7Worker.withWorker {
            CameraCaptureStore(context.filesDir).use { store ->
                val current = store.readOperation()
                if (current == null || current.operationId != operationId) {
                    false
                } else {
                    when (current.status) {
                        CameraCaptureOperationStatus.PREPARED -> store.discardPrepared(operationId)
                        CameraCaptureOperationStatus.LAUNCHED -> store.abandonLaunched(operationId)
                        CameraCaptureOperationStatus.RESULT_CANCELLED,
                        CameraCaptureOperationStatus.RESULT_AVAILABLE,
                        CameraCaptureOperationStatus.PROCESSING,
                        CameraCaptureOperationStatus.PUBLISHED -> store.markDiscarded(operationId)
                        CameraCaptureOperationStatus.DISCARDED,
                        CameraCaptureOperationStatus.COMMITTED -> current
                    }
                    store.cleanup(operationId)
                    true
                }
            }
        }

    /** Cancellation before owner dispatch may safely dispose of PREPARED only. */
    suspend fun discardPreparedCameraOperationIfSafe(operationId: String) {
        withContext(NonCancellable) {
            try {
                stage7Worker.withWorker {
                    CameraCaptureStore(context.filesDir).use { store ->
                        val current = store.readOperation()
                        if (current?.operationId == operationId &&
                            current.status == CameraCaptureOperationStatus.PREPARED
                        ) {
                            store.discardPrepared(operationId)
                            store.cleanup(operationId)
                        }
                    }
                }
            } catch (error: Throwable) {
                // A failed safe-discard leaves the durable operation available
                // for the explicit recovery dialog; never delete by age here.
                SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            }
        }
    }

    suspend fun cleanupCameraTerminalOperation(operationId: String) {
        stage7Worker.withWorker {
            CameraCaptureStore(context.filesDir).use { store ->
                val current = store.readOperation()
                if (current?.operationId == operationId &&
                    (current.status == CameraCaptureOperationStatus.RESULT_CANCELLED ||
                        current.status == CameraCaptureOperationStatus.DISCARDED ||
                        current.status == CameraCaptureOperationStatus.COMMITTED)
                ) {
                    store.cleanup(operationId)
                }
            }
        }
    }

    fun currentReadyCameraSession(): DocumentSession? {
        val session = sessionCoordinator.currentSession() ?: return null
        return session.takeIf {
            activeSessionToken == it.token &&
                readySessionToken == it.token &&
                sessionCoordinator.isCurrentApplied(it.token)
        }
    }

    /**
     * Processes a matching terminal result.  Publication/attachment is fenced
     * by the document barrier; the durable local flush is deliberately outside
     * that barrier because flushCurrent() reacquires it.  A PUBLISHED journal
     * record protects the deterministic target across that short interval.
     */
    suspend fun processCameraOperation(
        operation: CameraCaptureOperationRecord,
        session: DocumentSession
    ) {
        val operationId = operation.operationId
        val documentId = session.token.documentId

        // Validate the live source/permission before any publication or
        // reducer mutation. flushCurrent() re-resolves and fingerprints the
        // source through the canonical save path; a revoked URI grant or a
        // changed PDF therefore leaves the operation recoverable without
        // attaching a photo to the in-memory pin.
        val sourceValidated = try {
            when (sessionCoordinator.flushCurrent()) {
                is DocumentSaveResult.Saved -> true
                else -> false
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            false
        }
        if (!sourceValidated) {
            promptCameraRecoveryFor(
                operation,
                "The document source could not be verified for this camera result. The capture remains retained for recovery."
            )
            return
        }
        currentCoroutineContext().ensureActive()

        val attachedOrPresent = try {
            documentTransactionBarrier.withDocument(documentId) {
                val current = currentReadyCameraSession()
                if (current?.token != session.token) {
                    false
                } else if (selectedPageIndex != operation.pageIndex) {
                    // A result for page A may be delivered while the user is
                    // viewing page B. Keep the durable operation untouched and
                    // make the required page change visible instead of
                    // silently treating the drain as complete.
                    promptCameraRecoveryFor(
                        operation,
                        "A captured photo is ready for page ${operation.pageIndex + 1}. Select that page to finish recovery."
                    )
                    false
                } else {
                    val initialPin = vm.pagePhotoPins[operation.pageIndex]
                        ?.firstOrNull { it.id == operation.pinId }
                    if (initialPin == null) {
                        promptCameraRecoveryFor(
                            operation,
                            "The original photo pin is no longer present. Open its document to restore it, or explicitly discard this capture."
                        )
                        false
                    } else {
                        val loadedOperation = stage7Worker.withWorker {
                            CameraCaptureStore(context.filesDir).use { store ->
                                store.readOperation()
                            }
                        }
                        if (loadedOperation == null || loadedOperation.operationId != operationId) {
                            false
                        } else {
                            val capacityReference = requireNotNull(loadedOperation.publishedPhotoFileName) {
                                "camera operation has no deterministic publication name"
                            }
                            if (!initialPin.imageFileNames.contains(capacityReference) &&
                                !annotationReducer.canAttachPhoto(
                                    operation.pageIndex,
                                    operation.pinId,
                                    capacityReference
                                )
                            ) {
                                SafeDiagnostics.warn(DiagnosticEvent.LIMIT_REACHED)
                                throw com.example.myapplication.stage5.Stage5ValidationException(
                                    "camera photo capacity limit reached before publication"
                                )
                            }
                            var currentOperation = loadedOperation
                            if (currentOperation.status == CameraCaptureOperationStatus.RESULT_AVAILABLE) {
                                currentOperation = stage7Worker.withWorker {
                                    CameraCaptureStore(context.filesDir).use { store ->
                                        store.markProcessing(operationId)
                                    }
                                }
                            }
                            if (currentOperation.status != CameraCaptureOperationStatus.PROCESSING &&
                                currentOperation.status != CameraCaptureOperationStatus.PUBLISHED
                            ) {
                                throw IllegalStateException(
                                    "camera operation cannot be processed from ${currentOperation.status}"
                                )
                            }
                            val reservedReference = requireNotNull(
                                currentOperation.publishedPhotoFileName
                            ) { "camera operation has no deterministic publication name" }
                            val existingPhotoReferences = vm.pagePhotoPins.values
                                .flatMap { pins -> pins.flatMap { it.imageFileNames } }
                                .toSet()

                            // Re-reading the capture through the operation store
                            // verifies ownership and regular-file state before
                            // the bytes enter the canonical document root.
                            stage7Worker.withWorker {
                                CameraCaptureStore(context.filesDir).use { captureStore ->
                                    captureStore.withCaptureInput(operationId) { input ->
                                    DocumentPhotoAssetStore(context.filesDir, documentId).use { store ->
                                        store.publishReservedPhoto(
                                            input = input,
                                            reservedPhotoFileName = reservedReference,
                                            extension = ".jpg",
                                            existingPhotoReferences = existingPhotoReferences
                                        )
                                    }
                                    }
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (currentOperation.status == CameraCaptureOperationStatus.PROCESSING) {
                                // This marker is durable before the reducer is
                                // allowed to create a canonical attachment.
                                currentOperation = stage7Worker.withWorker {
                                    CameraCaptureStore(context.filesDir).use { store ->
                                        store.markPublished(operationId, reservedReference)
                                    }
                                }
                            }
                            val livePin = vm.pagePhotoPins[operation.pageIndex]
                                ?.firstOrNull { it.id == operation.pinId }
                            if (livePin == null) {
                                promptCameraRecoveryFor(
                                    currentOperation,
                                    "The original photo pin was deleted while the camera result was being recovered. The capture is retained for explicit recovery or discard."
                                )
                                false
                            } else if (livePin.imageFileNames.contains(reservedReference)) {
                                // Crash recovery after attach-before-commit is
                                // an exact-reference check, not a second
                                // reducer mutation/history entry.
                                true
                            } else if (annotationReducer.attachPhoto(
                                    operation.pageIndex,
                                    livePin,
                                    reservedReference
                                ).changed
                            ) {
                                true
                            } else {
                                val afterFailure = vm.pagePhotoPins[operation.pageIndex]
                                    ?.firstOrNull { it.id == operation.pinId }
                                if (afterFailure?.imageFileNames?.contains(reservedReference) == true) {
                                    true
                                } else {
                                    throw IllegalStateException(
                                        "camera photo pin could not be updated through the annotation reducer"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            val latest = try {
                readCameraOperation()
            } catch (_: Throwable) {
                operation
            }
            promptCameraRecoveryFor(
                latest ?: operation,
                "The captured photo could not be attached yet. It remains retained for recovery."
            )
            return
        }

        if (!attachedOrPresent) return

        val flushed = try {
            // This call reacquires the document barrier internally; it must not
            // be invoked from the barrier block above.
            sessionCoordinator.flushCurrent()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            null
        }
        if (flushed !is DocumentSaveResult.Saved) {
            promptCameraRecoveryFor(
                cameraOperationOrFallback(operation),
                "The photo was attached in memory, but its canonical document save did not finish. The capture is retained; retry recovery when the document is ready."
            )
            return
        }

        val committed = try {
            withContext(NonCancellable) {
                documentTransactionBarrier.withDocument(documentId) {
                    val current = currentReadyCameraSession()
                    if (current?.token != session.token || selectedPageIndex != operation.pageIndex) {
                        false
                    } else {
                        val livePin = vm.pagePhotoPins[operation.pageIndex]
                            ?.firstOrNull { it.id == operation.pinId }
                        val currentOperation = stage7Worker.withWorker {
                            CameraCaptureStore(context.filesDir).use { store ->
                                store.readOperation()
                            }
                        }
                        val reservedReference = currentOperation
                            ?.publishedPhotoFileName
                        if (currentOperation == null ||
                            livePin == null || reservedReference.isNullOrBlank() ||
                            !livePin.imageFileNames.contains(reservedReference) ||
                            currentOperation.operationId != operationId
                        ) {
                            false
                        } else {
                            if (currentOperation.status == CameraCaptureOperationStatus.PUBLISHED) {
                                stage7Worker.withWorker {
                                    CameraCaptureStore(context.filesDir).use { store ->
                                        store.markCommitted(operationId)
                                        store.cleanup(operationId)
                                    }
                                }
                            } else if (currentOperation.status != CameraCaptureOperationStatus.COMMITTED) {
                                throw IllegalStateException(
                                    "camera operation is not publication-committed after canonical save"
                                )
                            } else {
                                cleanupCameraTerminalOperation(operationId)
                            }
                            // The canonical reference is now durable; dropping
                            // only the volatile publication reservation is safe.
                            stage7Worker.withWorker {
                                DocumentPhotoAssetStore(context.filesDir, documentId).use { store ->
                                    store.releasePhotoPublication(reservedReference)
                                }
                            }
                            true
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            false
        }
        if (committed) {
            clearCameraRecoveryPrompt()
            // Keep the established photo-specific sync admission semantics,
            // but only if the same session still owns the UI when the durable
            // camera commit completes.
            if (currentReadyCameraSession()?.token == session.token) {
                triggerImmediateSync(SyncReason.PHOTO)
            }
        } else {
            promptCameraRecoveryFor(
                cameraOperationOrFallback(operation),
                "The document changed before the camera commit completed. Reopen the original document to finish recovery."
            )
        }
    }

    /** Reads and drains only the durable operation matching the ready source. */
    suspend fun drainCameraOperation(returnedOperationId: String? = null) {
        cameraOperationMutex.withLock {
            val operation = try {
                readCameraOperation()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                promptCameraRecovery(
                    null,
                    "Camera recovery evidence could not be verified. No new capture will be started until it is repaired or removed safely."
                )
                return@withLock
            }
            if (operation == null) {
                if (cameraRecoveryOperation != null) clearCameraRecoveryPrompt()
                return@withLock
            }
            if (returnedOperationId != null && returnedOperationId != operation.operationId) {
                promptCameraRecoveryFor(
                    operation,
                    "A stale camera result was returned. The durable operation remains protected for recovery."
                )
                return@withLock
            }
            when (operation.status) {
                CameraCaptureOperationStatus.COMMITTED,
                CameraCaptureOperationStatus.DISCARDED -> {
                    try {
                        cleanupCameraTerminalOperation(operation.operationId)
                        clearCameraRecoveryPrompt()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                        promptCameraRecoveryFor(
                            operation,
                            "A completed camera operation is retained until its cleanup can be retried safely."
                        )
                    }
                    return@withLock
                }
                CameraCaptureOperationStatus.RESULT_CANCELLED -> {
                    try {
                        cleanupCameraTerminalOperation(operation.operationId)
                        clearCameraRecoveryPrompt()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                        promptCameraRecoveryFor(
                            operation,
                            "The cancelled camera operation is retained until its cleanup can be retried safely."
                        )
                    }
                    return@withLock
                }
                CameraCaptureOperationStatus.PREPARED,
                CameraCaptureOperationStatus.LAUNCHED -> {
                    promptCameraRecoveryFor(operation)
                    return@withLock
                }
                CameraCaptureOperationStatus.RESULT_AVAILABLE,
                CameraCaptureOperationStatus.PROCESSING,
                CameraCaptureOperationStatus.PUBLISHED -> Unit
            }

            val session = currentReadyCameraSession()
            if (session == null) {
                promptCameraRecoveryFor(
                    operation,
                    "A camera result is retained. Open the original document and wait for it to finish loading before recovery."
                )
                return@withLock
            }
            val identity = CameraCaptureStableIdentity(
                documentId = session.token.documentId,
                sourceUri = session.token.sourceUri,
                sourceFingerprint = session.token.sourceFingerprint
            )
            val binding = try {
                stage7Worker.withWorker {
                    CameraCaptureStore(context.filesDir).use { store ->
                        val recovery = store.recovery()
                        recovery.canRebind(operation, identity) to recovery.canApplyToSession(
                            operation = operation,
                            identity = identity,
                            ownerInstanceId = cameraOperationOwnerId,
                            sessionGeneration = session.token.generation
                        )
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                false to false
            }
            val sameStableIdentity = binding.first
            if (!sameStableIdentity) {
                promptCameraRecoveryFor(
                    operation,
                    "A camera result belongs to a different verified document source. It will not be attached here; open the original document to recover it or explicitly discard it."
                )
                return@withLock
            }
            if (!binding.second) {
                promptCameraRecoveryFor(
                    operation,
                    "This camera result belongs to an earlier session generation. It was not attached; reopen the original document after a process restart to recover it, or explicitly discard it."
                )
                return@withLock
            }
            processCameraOperation(operation, session)
        }
    }

    /**
     * Starts a camera operation only after the intended pin is durably saved.
     * Preparation is fenced by the document barrier, and cancellation before
     * the explicit owner dispatch can only discard a still-PREPARED record.
     */
    fun requestCameraCapture(requestPageIndex: Int, requestPinId: String) {
        val requestedToken = activeSessionToken ?: return
        if (requestedToken != readySessionToken ||
            !sessionCoordinator.isCurrentApplied(requestedToken) ||
            requestPageIndex != selectedPageIndex ||
            vm.pagePhotoPins[requestPageIndex]?.none { it.id == requestPinId } != false
        ) return
        if (!annotationReducer.canAttachPhoto(requestPageIndex, requestPinId)) {
            SafeDiagnostics.warn(DiagnosticEvent.LIMIT_REACHED)
            Toast.makeText(
                context,
                context.getString(R.string.camera_photo_limit_reached),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        scope.launch {
            cameraOperationMutex.withLock {
                var operationId: String? = null
                var dispatchConfirmed = false
                try {
                    val existing = readCameraOperation()
                    if (existing != null) {
                        promptCameraRecoveryFor(
                            existing,
                            "Resolve the existing camera capture before starting another one."
                        )
                        return@withLock
                    }
                    when (val flushed = sessionCoordinator.flushCurrent()) {
                        is DocumentSaveResult.Saved -> Unit
                        is DocumentSaveResult.Failed -> {
                            Toast.makeText(
                                context,
                                "The photo pin could not be saved before opening the camera.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@withLock
                        }
                        null -> return@withLock
                    }
                    currentCoroutineContext().ensureActive()
                    val prepared = documentTransactionBarrier.withDocument(requestedToken.documentId) {
                        val current = currentReadyCameraSession()
                        val pinStillPresent = vm.pagePhotoPins[requestPageIndex]
                            ?.any { it.id == requestPinId } == true
                        val pinHasCapacity = annotationReducer.canAttachPhoto(
                            requestPageIndex,
                            requestPinId
                        )
                        if (current?.token != requestedToken ||
                            selectedPageIndex != requestPageIndex ||
                            !pinStillPresent ||
                            !pinHasCapacity
                        ) {
                            null
                        } else {
                            val request = CameraCaptureOperationRequest(
                                processInstanceId = cameraOperationOwnerId,
                                documentId = requestedToken.documentId,
                                sourceUri = requestedToken.sourceUri,
                                sourceFingerprint = requestedToken.sourceFingerprint,
                                sessionGeneration = requestedToken.generation,
                                pageIndex = requestPageIndex,
                                pinId = requestPinId
                            )
                            com.example.myapplication.stage5.prepareCameraOperationOnWorker(
                                request, stage7Worker, rememberPrepared = { operationId = it }
                            ) { frozenRequest ->
                                CameraCaptureStore(context.applicationContext.filesDir).use { store ->
                                    store.prepareOperation(frozenRequest)
                                }
                            }
                        }
                    }
                    if (prepared == null) return@withLock
                    operationId = prepared.operationId
                    currentCoroutineContext().ensureActive()
                    val stillLaunchable = documentTransactionBarrier.withDocument(requestedToken.documentId) {
                        val current = currentReadyCameraSession()
                        current?.token == requestedToken &&
                            selectedPageIndex == requestPageIndex &&
                            vm.pagePhotoPins[requestPageIndex]?.any { it.id == requestPinId } == true &&
                            annotationReducer.canAttachPhoto(requestPageIndex, requestPinId)
                    }
                    if (!stillLaunchable) {
                        discardPreparedCameraOperationIfSafe(operationId!!)
                        return@withLock
                    }
                    currentCoroutineContext().ensureActive()
                    cameraLauncher.launch(
                        CameraCaptureActivity.intentFor(context, operationId!!)
                    )
                    dispatchConfirmed = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (!dispatchConfirmed) operationId?.let { discardPreparedCameraOperationIfSafe(it) }
                    throw cancelled
                } catch (error: Throwable) {
                    if (!dispatchConfirmed) operationId?.let { discardPreparedCameraOperationIfSafe(it) }
                    SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                    Toast.makeText(
                        context,
                        "The camera could not be opened. The capture remains available for recovery if it was dispatched.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun requestCameraRecoveryDiscard(operationId: String) {
        scope.launch {
            cameraOperationMutex.withLock {
                try {
                    if (discardCameraOperationDurably(operationId)) {
                        if (cameraRecoveryOperation?.operationId == operationId) {
                            clearCameraRecoveryPrompt()
                        }
                        cameraDrainRevision++
                    } else {
                        cameraDrainRevision++
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                    promptCameraRecovery(
                        cameraRecoveryOperation,
                        "The camera capture could not be discarded safely. It remains protected for recovery."
                    )
                }
            }
        }
    }

    // The debounce delay is UI-owned, but capture/upload admission remains in
    // the one Stage 4 coordinator entry point.
    LaunchedEffect(syncTrigger, activeSessionToken, activeSyncBinding) {
        val session = sessionCoordinator.currentSession()
            ?: return@LaunchedEffect
        val binding = currentSyncBinding(session) ?: return@LaunchedEffect
        if (syncTrigger <= 0) return@LaunchedEffect
        val workToken = DocumentWorkToken(session.token, queryRevision = syncTrigger.toLong())
        delay(3000)
        if (!sessionCoordinator.accepts(workToken, currentQueryRevision = syncTrigger.toLong())) return@LaunchedEffect
        if (currentSyncBinding(session) != binding) return@LaunchedEffect
        syncCoordinator.enqueueUpload(binding, SyncReason.DEBOUNCED)
    }
    
    suspend fun <T> fenceDriveWorkBeforeIdentityChange(changeAuthority: () -> T): T {
        val previous = activeSyncBinding
        return changeDriveAuthority(
            invalidateSync = { syncCoordinator.invalidateCurrentScope() },
            changeAuthority = {
                activeSyncBinding = null
                changeAuthority()
            },
            joinPreviousWork = {
                driveSyncManager.cancelRootOperationsAndJoin()
                if (previous != null) syncCoordinator.cancelForBindingAndJoin(previous)
            }
        )
    }

    fun installDriveAuthorization(
        generation: Long,
        grant: DriveAuthorizationRequestResult.Granted
    ): Boolean = when (
        val applied = driveSyncManager.installAuthorizedDriveSession(
            expectedGeneration = generation,
            accessToken = grant.accessToken,
            grantedScopes = grant.grantedScopes
        )
    ) {
        is DriveAuthorizationApplyResult.Accepted -> {
            Toast.makeText(
                context,
                context.getString(R.string.signed_in_as, applied.session.identity.email),
                Toast.LENGTH_SHORT
            ).show()
            true
        }
        DriveAuthorizationApplyResult.Stale -> false
        else -> {
            // A partial/broader/stale authorization never becomes a usable
            // Drive session. A stale response cannot clear a newer session.
            driveSyncManager.clearSessionIfCurrent(generation)
            val message = when (applied) {
                DriveAuthorizationApplyResult.MissingDriveFileScope -> R.string.drive_backup_consent_missing
                DriveAuthorizationApplyResult.ContainsUnexpectedDriveScope -> R.string.drive_backup_scope_failed
                else -> R.string.sign_in_failed_generic
            }
            SafeDiagnostics.error(DiagnosticEvent.AUTH_ACTIVITY, facts = {
                DiagnosticFacts(statusCode = when (applied) {
                    DriveAuthorizationApplyResult.MissingDriveFileScope -> 401
                    DriveAuthorizationApplyResult.ContainsUnexpectedDriveScope -> 403
                    else -> 400
                })
            })
            Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
            false
        }
    }

    var driveAuthorizationInFlight by remember { mutableStateOf(false) }
    var driveRootInFlight by remember { mutableStateOf(false) }

    // AuthorizationClient may return an IntentSender when Drive consent is
    // needed. The app-owned resolution contract returns the immutable random
    // operation id as well as provider data. The tracker also binds the exact
    // manager owner and identity, so an old ActivityResult cannot be relabeled
    // with a numerically colliding post-recreation generation/account.
    val driveAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = DriveAuthorizationResolutionContract()
    ) { result ->
        val authority = driveSyncManager.authorizationStatus.value
        val pending = vm.driveAuthorizationResolutionTracker.consume(
            operationId = result.operationId,
            authorityOwner = driveSyncManager.authorizationOwner,
            generation = authority.generation,
            identity = authority.identity
        )
            ?: return@rememberLauncherForActivityResult
        val generation = pending.generation
        scope.launch {
            try {
                if (result.resultCode != Activity.RESULT_OK) {
                    driveSyncManager.clearSessionIfCurrent(generation)
                    Toast.makeText(
                        context,
                        context.getString(R.string.sign_in_cancelled),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val activity = context.findActivity()
                    ?: throw IllegalStateException("Google authorization requires an Activity context")
                val grant = googleCredentialDriveAuth.completeDriveAuthorization(
                    activity,
                    result.providerData
                )
                val refreshed = refreshUnexpectedBackupGrant(grant,
                    clearToken = { googleCredentialDriveAuth.clearAccessToken(activity, it) },
                    requestFresh = { googleCredentialDriveAuth.requestDriveAuthorization(activity, pending.identity) })
                if (refreshed is DriveAuthorizationRequestResult.Granted) installDriveAuthorization(generation, refreshed)
                else {
                    driveSyncManager.clearSessionIfCurrent(generation)
                    Toast.makeText(context, context.getString(R.string.drive_backup_scope_failed), Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                driveSyncManager.clearSessionIfCurrent(generation)
                throw cancelled
            } catch (failure: Exception) {
                driveSyncManager.clearSessionIfCurrent(generation)
                SafeDiagnostics.error(DiagnosticEvent.AUTH_ACTIVITY, error = failure)
                Toast.makeText(context, context.getString(R.string.sign_in_failed_generic), Toast.LENGTH_LONG).show()
            } finally {
                driveAuthorizationInFlight = false
            }
        }
    }

    fun launchExplicitGoogleSignIn() {
        if (driveAuthorizationInFlight) return
        driveAuthorizationInFlight = true
        scope.launch {
            var generation: Long? = null
            var pendingResolution: PendingDriveAuthorizationResolution? = null
            var launchedResolution = false
            try {
                SafeDiagnostics.debug(DiagnosticEvent.AUTH_ACTIVITY)
                val activity = context.findActivity()
                    ?: throw IllegalStateException("Google sign-in requires an Activity context")
                val attempt = fenceDriveWorkBeforeIdentityChange {
                    driveSyncManager.beginAuthenticationAttempt()
                }
                generation = attempt
                val identity = googleCredentialDriveAuth.signIn(activity)
                if (!driveSyncManager.authenticateIfCurrent(attempt, identity)) return@launch
                driveSyncManager.invalidateRejectedAccessTokens { token ->
                    googleCredentialDriveAuth.clearAccessToken(activity, token)
                }
                when (
                    val authorization = googleCredentialDriveAuth
                        .requestDriveAuthorization(activity, identity)
                ) {
                    is DriveAuthorizationRequestResult.Granted -> {
                        installDriveAuthorization(attempt, authorization)
                    }
                    is DriveAuthorizationRequestResult.ResolutionRequired -> {
                        val pending = vm.driveAuthorizationResolutionTracker.begin(
                            authorityOwner = driveSyncManager.authorizationOwner,
                            generation = attempt,
                            identity = identity
                        )
                        pendingResolution = pending
                        driveAuthorizationLauncher.launch(
                            DriveAuthorizationResolutionRequest(
                                operationId = pending.operationId,
                                intentSender = authorization.intentSender
                            )
                        )
                        launchedResolution = true
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                generation?.let(driveSyncManager::clearSessionIfCurrent)
                throw cancelled
            } catch (cancelled: androidx.credentials.exceptions.GetCredentialCancellationException) {
                generation?.let(driveSyncManager::clearSessionIfCurrent)
                Toast.makeText(context, context.getString(R.string.sign_in_cancelled), Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                generation?.let(driveSyncManager::clearSessionIfCurrent)
                SafeDiagnostics.error(DiagnosticEvent.AUTH_ACTIVITY, error = failure)
                Toast.makeText(
                    context,
                    context.getString(R.string.sign_in_failed_generic),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (!launchedResolution) {
                    pendingResolution?.let(vm.driveAuthorizationResolutionTracker::clearIfCurrent)
                    driveAuthorizationInFlight = false
                }
            }
        }
    }

    // Returning users can regain a short-lived Drive token after process
    // restart after a successful previous sign-in. Credential Manager may
    // display its returning-account selector; Drive consent stays explicit.
    LaunchedEffect(Unit) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        if (!googleCredentialDriveAuth.isConfigured || driveSyncManager.isSignedIn() ||
            !driveSyncManager.shouldRestoreSession() ||
            driveAuthorizationInFlight
        ) return@LaunchedEffect
        driveAuthorizationInFlight = true
        var generation: Long? = null
        try {
            val attempt = fenceDriveWorkBeforeIdentityChange {
                driveSyncManager.beginAuthenticationAttempt()
            }
            generation = attempt
            val identity = googleCredentialDriveAuth.restoreAuthorizedIdentity(activity)
                ?: run {
                    // A persisted restore marker with no authorized identity
                    // is stale. Clear it so every restart does not repeat the
                    // same unavailable restoration attempt; explicit sign-in
                    // can establish a fresh marker.
                    driveSyncManager.clearRestoreSessionMarker()
                    return@LaunchedEffect
                }
            if (!driveSyncManager.authenticateIfCurrent(attempt, identity)) return@LaunchedEffect
            driveSyncManager.invalidateRejectedAccessTokens { token ->
                googleCredentialDriveAuth.clearAccessToken(activity, token)
            }
            when (val authorization = googleCredentialDriveAuth.requestDriveAuthorization(activity, identity)) {
                is DriveAuthorizationRequestResult.Granted -> installDriveAuthorization(attempt, authorization)
                is DriveAuthorizationRequestResult.ResolutionRequired -> {
                    // Startup must not unexpectedly launch consent. The next
                    // explicit sign-in retries the same current API flow.
                    driveSyncManager.clearSessionIfCurrent(attempt)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            generation?.let(driveSyncManager::clearSessionIfCurrent)
            throw cancelled
        } catch (failure: Exception) {
            SafeDiagnostics.error(DiagnosticEvent.AUTH_ACTIVITY, error = failure)
            generation?.let(driveSyncManager::clearSessionIfCurrent)
        } finally {
            driveAuthorizationInFlight = false
        }
    }

    LaunchedEffect(activeSessionToken, readySessionToken, isSignedIn, signedInAccountId, backupFolderId, driveAuthorizationStatus.generation) {
        val session = sessionCoordinator.currentSession()
        val scopeForSession = currentSyncScope(session)
        // This is deliberately before the asynchronous cleanup below: route
        // closures cannot use the old account/root epoch during rebind.
        syncCoordinator.updateCurrentScope(scopeForSession)
        val previous = activeSyncBinding
        if (previous != null && (scopeForSession == null || previous.scope != scopeForSession || previous.token != session?.token)) {
            withContext(NonCancellable) {
                syncCoordinator.cancelForBindingAndJoin(previous)
            }
            activeSyncBinding = null
        }
        if (session == null || scopeForSession == null) return@LaunchedEffect
        val binding = syncCoordinator.bind(scopeForSession, session.token) ?: return@LaunchedEffect
        activeSyncBinding = binding
        syncCoordinator.enqueueRemoteCheck(binding, SyncReason.REMOTE_CHECK)
        syncCoordinator.startPeriodic(binding)
    }
    
    // Process restoration re-enters the same coordinator path. There is no
    // second load owner keyed directly to pdfUri; an already established token
    // makes this a no-op after a normal selection.
    LaunchedEffect(sessionCoordinator, pdfUri, initialPdfUri) {
        val restoredUri = pdfUri ?: return@LaunchedEffect
        if (initialPdfUri == null && sessionCoordinator.currentSession() == null) {
            sessionCoordinator.switchTo(restoredUri.toString())
        }
    }

    // Save markups when app is backgrounded or stopped
    // Drive work is owned by the lifecycle-scoped Stage 4 coordinator.
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleFlushOwner = remember(scope, sessionCoordinator, syncCoordinator) {
        com.example.myapplication.stage3.DocumentLifecycleFlushOwner(
            scope = scope,
            flush = flush@{
                val session = sessionCoordinator.currentSession() ?: return@flush
                val token = session.token
                if (!sessionCoordinator.isCurrentApplied(token)) return@flush
                when (sessionCoordinator.flushCurrent()) {
                    is DocumentSaveResult.Saved -> {
                        val binding = currentSyncBinding(session)?.takeIf { it.token == token }
                        if (binding != null) {
                            // Remote work remains cancellable and independently owned.
                            scope.launch {
                                val outcome = syncCoordinator.enqueueUpload(binding, SyncReason.LIFECYCLE).await()
                                if (outcome is SyncOutcome.Failed) {
                                    SafeDiagnostics.error(DiagnosticEvent.SYNC_ACTIVITY)
                                }
                            }
                        }
                    }
                    is DocumentSaveResult.Failed -> SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED)
                    null -> Unit
                }
            },
            onFailure = { SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = it) }
        )
    }
    DisposableEffect(lifecycleOwner, lifecycleFlushOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                lifecycleFlushOwner.request()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A result can be delivered while the host is backgrounded, or a durable
    // operation can first become visible after a session load.  Both paths
    // wake the same journal drain; no renderer callback is required.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                cameraDrainRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(cameraDrainRevision, activeSessionToken, readySessionToken, selectedPageIndex) {
        val returnedOperationId = cameraReturnedOperationId
        drainCameraOperation(returnedOperationId)
        if (cameraReturnedOperationId == returnedOperationId) {
            cameraReturnedOperationId = null
        }
    }

    // A new Activity/coordinator shares the ViewModel, not the old composition's
    // coroutine scope. Its first switch must join this complete handoff before
    // consulting the repository or changing retained annotations/history.
    val retireDocumentHost: suspend () -> Unit = remember(
        lifecycleFlushOwner, syncCoordinator, sessionCoordinator
    ) {
        suspend {
            lifecycleFlushOwner.closeAndJoin()
            syncCoordinator.closeAndJoin()
            // Also flush on a callback rebind, which need not send ON_PAUSE.
            // A failed save keeps the predecessor retryable and blocks loading.
            val saved = sessionCoordinator.flushCurrent()
            check(saved !is DocumentSaveResult.Failed) {
                "Previous document host could not durably flush its annotations"
            }
            sessionCoordinator.closeAndJoin()
        }
    }
    SideEffect {
        documentHost.bind(retireDocumentHost)
        compositionDocumentHosts.add(documentHost)
    }
    val documentHostWorkOwner = sessionCoordinator.documentWorkOwner
    LaunchedEffect(documentHost, documentHostWorkOwner) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                try {
                    documentHost.closeAndJoin()
                    // A completed retirement no longer needs the coordinator
                    // graphs or their captured snapshots. Failed retirement
                    // stays registered so the handoff can retry it.
                    compositionDocumentHosts.remove(documentHost)
                    coordinatorsByOwner.remove(documentHostWorkOwner)
                    syncCoordinatorsByOwner.remove(documentHostWorkOwner)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = error)
                }
            }
        }
    }

    // Join every host created by this composition, including an earlier auth
    // rebind still retiring, before closing its shared OCR registry. Old owners
    // are idempotent and cannot close a newer Activity's coordinator.
    LaunchedEffect(vm, ocrIndex) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                try {
                    val retirements = compositionDocumentHosts.map { owner ->
                        suspend { owner.closeAndJoin() }
                    } + suspend { ocrIndex.closeAndJoin() }
                    runNonCancellableFinalizers(*retirements.toTypedArray())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = error)
                }
            }
        }
    }

    suspend fun resolveProjectSource(sourceUri: String): String = withContext(Dispatchers.IO) {
        val manifest = localDocumentRepository.readManifest()
        check(manifest is com.example.myapplication.stage2.ManifestReadResult.Loaded)
        existingProjectSource(sourceUri, manifest.entries)
    }

    val selectPdf: (Uri, ProjectDrawing?) -> Unit = { uri, projectSelection ->
        scope.launch {
            val grant = if (projectSelection == null && uri.scheme == ContentResolver.SCHEME_CONTENT) try {
                context.contentResolver.takePersistableReadGrant(uri)
            } catch (security: SecurityException) {
                // Some trusted test/local providers grant only a transient read
                // permission; opening the document remains valid for this session.
                SafeDiagnostics.warn(DiagnosticEvent.INPUT_REJECTED)
                null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = error)
                Toast.makeText(context, context.getString(R.string.pdf_open_failed), Toast.LENGTH_LONG).show()
                return@launch
            } else null
            var grantAccepted = false
            try {
                val sourceUri = if (projectSelection != null) {
                    try {
                        resolveProjectSource(uri.toString())
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        Toast.makeText(context, "The drawing association could not be verified. Existing annotations were preserved.", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                } else uri.toString()
                val result = sessionCoordinator.switchTo(sourceUri)
                val openedSession = when (result) {
                    is SwitchResult.Switched -> result.session
                    is SwitchResult.AlreadyActive -> result.session
                    else -> null
                }
                // An already active document can return to its sheet browser
                // immediately. Recent-file and project writes below may take
                // time, but they do not gate navigation to the ready session.
                restoreAlreadyActiveSession(
                    result = result,
                    isCurrent = sessionCoordinator::isCurrent,
                    isReady = { token ->
                        activeSessionToken == token && readySessionToken == token
                    },
                    restoreBrowser = { session ->
                        activeSessionToken = session.token
                        pdfUri = session.token.sourceUri.toUri()
                        currentScreen = Screen.BROWSER
                    }
                )
                if (openedSession != null && sessionCoordinator.isCurrentApplied(openedSession.token)) {
                    // The switch has accepted this exact source association.
                    // Keep the grant even if a later recent-file write fails.
                    grant?.markAccepted()
                    grantAccepted = true
                    val openedAt = System.currentTimeMillis()
                    // Downloads use private UUID paths; their original names remain display metadata.
                    val recentRecord = RecentDocumentRecord.fromAssociation(openedSession.target.association, openedAt)
                        .let { if (projectSelection != null) it.copy(displayName = projectSelection.displayName) else it }
                    val written = withContext(Dispatchers.IO) {
                        recentStore.record(recentRecord)
                    }
                    if (written is RecentDocumentWriteResult.Committed) {
                        when (val loaded = withContext(Dispatchers.IO) { recentStore.read() }) {
                            is RecentDocumentReadResult.Loaded -> { recentFiles = loaded.records; recentLoadFailed = false }
                            is RecentDocumentReadResult.Failed -> recentLoadFailed = true
                        }
                    } else {
                        recentLoadFailed = true
                        Toast.makeText(context, "The drawing opened, but its recent-file entry could not be saved.", Toast.LENGTH_LONG).show()
                    }
                    if (projectSelection != null) {
                        try {
                            projectBrowser.recordOpen(projectSelection, recentRecord)
                            withContext(Dispatchers.IO) { toolSettingsStore.bindDocument(recentRecord.documentId.value, projectSelection.projectId) }
                            toolScopeRevision++
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            Toast.makeText(context, "The drawing opened, but the project recent-file entry could not be saved.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = error)
                Toast.makeText(context, context.getString(R.string.pdf_open_failed), Toast.LENGTH_LONG).show()
            } finally {
                if (grant != null && !grantAccepted) {
                    withContext(NonCancellable) {
                        // A failed or superseded switch may still have created
                        // a durable association. Uncertain inventory retains
                        // its permission rather than breaking a later reopen.
                        val durableUse = try {
                            withContext(Dispatchers.IO) {
                                val manifest = localDocumentRepository.readManifest()
                                val recent = recentStore.read()
                                val manifestUsesUri = when (manifest) {
                                    is com.example.myapplication.stage2.ManifestReadResult.Loaded ->
                                        manifest.entries.any { it.sourceUri == uri.toString() }
                                    is com.example.myapplication.stage2.ManifestReadResult.Failed -> true
                                }
                                val recentUsesUri = when (recent) {
                                    is RecentDocumentReadResult.Loaded ->
                                        recent.records.any { it.sourceUri == uri.toString() }
                                    is RecentDocumentReadResult.Failed -> true
                                }
                                manifestUsesUri || recentUsesUri
                            }
                        } catch (_: Exception) { true }
                        grant.releaseIfUnused(context.contentResolver) { selected ->
                            durableUse || sessionCoordinator.currentSession()
                                ?.token?.sourceUri == selected.toString()
                        }
                    }
                }
            }
        }
    }
    val onPdfSelected: (Uri) -> Unit = { selectPdf(it, null) }

    // Test/qualification entry point still uses the normal document switch
    // transaction and therefore exercises the same browser/viewer path.
    LaunchedEffect(sessionCoordinator, initialPdfUri) {
        if (initialPdfUri != null && sessionCoordinator.currentSession() == null) {
            onPdfSelected(initialPdfUri)
        }
    }

    // Track the last processed search trigger to avoid re-running on recomposition after rotation
    var lastProcessedTrigger by rememberSaveable { mutableIntStateOf(0) }
    
    // Trigger text extraction/highlight only when user explicitly searches (searchTrigger changes)
    // Capture the page index at the time of search to avoid issues with recomposition
    val liveSelectedPageIndex = rememberUpdatedState(selectedPageIndex)
    val liveSearchQueryRevision = rememberUpdatedState(searchTrigger.toLong())
    val currentPageSearchEffectKey = if (searchOnlyCurrentPage) selectedPageIndex else null
    LaunchedEffect(searchTrigger, activeSessionToken, currentPageSearchEffectKey) {
            // Skip if we've already processed this trigger value (prevents re-run after rotation)
            if (searchTrigger <= lastProcessedTrigger) return@LaunchedEffect
            lastProcessedTrigger = searchTrigger
            val queryRevision = searchTrigger.toLong()
            // A new query, including an explicit blank query, retires the old
            // visual result before any replacement work is admitted.
            vm.pageHighlights.clear()
            vm.pageSearchTerms.clear()
            foundCount = 0
            showFoundDialog = false
            if (searchTerm.isBlank()) {
                activeSearchRequestRevision = 0L
                searching = false
                searchDone = 0
                searchTotal = 0
                return@LaunchedEffect
            }
            val session = sessionCoordinator.currentSession() ?: run {
                activeSearchRequestRevision = 0L
                searching = false
                return@LaunchedEffect
            }
            val targetPage = selectedPageIndex // Capture current page
            val query = searchTerm
            val workToken = DocumentWorkToken(
                session = session.token,
                pageIndex = if (searchOnlyCurrentPage) targetPage else null,
                queryRevision = queryRevision
            )
            val acceptsSearch: (DocumentWorkToken) -> Boolean = { candidate ->
                acceptsCurrentPageSearchWork(
                    coordinator = sessionCoordinator,
                    candidate = candidate,
                    currentPageIndex = { liveSelectedPageIndex.value },
                    queryRevision = { liveSearchQueryRevision.value }
                )
            }
            try {
                // Start a new search.  Show progress by resetting counters and toggling the
                // searching flag.  Use the existing PdfSearchEngine so OCR caches are reused.
                if (!acceptsSearch(workToken)) return@LaunchedEffect
                activeSearchRequestRevision = queryRevision
                searching = true
                searchDone = 0
                searchTotal = 0
                val results = if (searchOnlyCurrentPage) {
                    pdfSearchEngine.search(
                        workToken = workToken,
                        query = query,
                        pageCount = 1,
                        startPage = targetPage,
                        cacheNamespace = session.token.sourceCacheKey,
                        isAccepted = acceptsSearch,
                        owner = sessionCoordinator.documentWorkOwner,
                        onProgress = { done, total ->
                            if (acceptsSearch(workToken)) {
                                searchDone = done
                                searchTotal = total
                            }
                        }
                    )
                } else {
                    pdfSearchEngine.search(
                        workToken = workToken,
                        query = query,
                        pageCount = totalPageCount,
                        cacheNamespace = session.token.sourceCacheKey,
                        isAccepted = acceptsSearch,
                        owner = sessionCoordinator.documentWorkOwner,
                        onProgress = { done, total ->
                            if (acceptsSearch(workToken)) {
                                searchDone = done
                                searchTotal = total
                            }
                        }
                    )
                }
                if (!acceptsSearch(workToken)) return@LaunchedEffect
                searching = false
                val totalHits = results.values.sumOf { it.size }
                SafeDiagnostics.debug(DiagnosticEvent.SEARCH_ACTIVITY)
                for ((pageIdx, rects) in results) {
                    vm.pageHighlights[pageIdx] = rects
                    vm.pageSearchTerms[pageIdx] = query
                }
                foundCount = totalHits
                try {
                    Toast.makeText(context, context.getString(R.string.search_found_current, foundCount), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                showFoundDialog = true
                delay(1400)
                if (acceptsSearch(workToken)) {
                    showFoundDialog = false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                SafeDiagnostics.error(DiagnosticEvent.SEARCH_ACTIVITY, error = t)
                if (acceptsSearch(workToken)) {
                    searching = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.search_failed, t.message ?: context.getString(R.string.search_unavailable_message)),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                clearSearchProgressIfOwned(
                    activeRequestRevision = activeSearchRequestRevision,
                    requestRevision = queryRevision
                ) {
                    activeSearchRequestRevision = 0L
                    searching = false
                }
            }
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text(stringResource(R.string.search_current_page_title)) },
            text = {
                Column {
                    OutlinedTextField(value = searchInput, onValueChange = { searchInput = it }, label = { Text(stringResource(R.string.search_term_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.search_current_page_help), style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(onClick = { SafeDiagnostics.debug(DiagnosticEvent.SEARCH_ACTIVITY); searchTerm = searchInput.trim(); searchTrigger++; showSearchDialog = false }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.viewer_search)) }
            },
            dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text(stringResource(R.string.clear_page_cancel)) } }
        )
    }
    // Read the revision so confirm/cancel/context transitions re-evaluate the
    // controller-owned pending target and remove a consumed dialog.
    if (clearDialogRevision >= 0 && stage8Interactions.pendingClearRequest != null) {
        AlertDialog(
            onDismissRequest = { stage8Interactions.cancelClear(); clearDialogRevision++ },
            title = { Text(stringResource(if (pendingClearPhotoTarget != null) R.string.clear_photo_title else R.string.clear_page_title)) },
            text = { Text(stringResource(if (pendingClearPhotoTarget != null) R.string.clear_photo_message else R.string.clear_page_message)) },
            confirmButton = {
                Button(onClick = {
                    // Confirmation is a state transition even when admission
                    // rejects a stale target or the reducer finds no-op data.
                    clearDialogRevision++
                    val token = sessionCoordinator.currentSession()?.token
                    stage8Interactions.confirmClear(token, selectedPageIndex) { confirmedPage ->
                        if (token != null && sessionCoordinator.isCurrentApplied(token)) {
                            val target = pendingClearPhotoTarget
                            if (target == null) annotationReducer.clearPage(confirmedPage)
                            else if (target == activePhotoTarget) vm.pagePhotoPins[confirmedPage]?.firstOrNull { it.id == target.first }?.let {
                                annotationReducer.clearImageAnnotations(confirmedPage, it, target.second)
                            }
                        }
                    }
                }) { Text(stringResource(R.string.clear_page_confirm)) }
            },
            dismissButton = { TextButton(onClick = { stage8Interactions.cancelClear(); clearDialogRevision++ }) { Text(stringResource(R.string.clear_page_cancel)) } }
        )
    }

    if (showFoundDialog) {
        AlertDialog(onDismissRequest = { showFoundDialog = false }, title = { Text(stringResource(R.string.search_results_title)) }, text = { Text(stringResource(R.string.search_matches_found, foundCount)) }, confirmButton = { TextButton(onClick = { showFoundDialog = false }) { Text(stringResource(R.string.search_results_ok)) } })
    }

    // Display a progress bar while a search is running.  Use a determinate bar when we know
    // the number of pages; otherwise fall back to an indeterminate bar.  This bar sits
    // above the rest of the UI to provide immediate feedback during lengthy OCR searches.
    if (searching) {
        val progress = if (searchTotal > 0) searchDone.toFloat() / searchTotal.toFloat() else 0f
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onPdfSelected(it) }
    }
    
    // Picker launchers and their saveable anchors stay with Compose. The
    // operation sequencing and document transaction live in the Stage 6 owner.
    val bundleWorkflowContext = context.applicationContext
    val documentBundleWorkflow = remember(
        bundleWorkflowContext,
        documentBundleService,
        localDocumentRepository,
        documentTransactionBarrier,
        sessionCoordinator,
        stage7Worker
    ) {
        DocumentBundleWorkflow(
            service = documentBundleService,
            transactionBarrier = documentTransactionBarrier,
            host = object : DocumentBundleWorkflowHost {
                override fun hasOpenPdf(): Boolean = pdfUri != null
                override fun currentSession(): DocumentSession? = sessionCoordinator.currentSession()
                override fun activeSessionToken(): DocumentSessionToken? = activeSessionToken
                override fun readySessionToken(): DocumentSessionToken? = readySessionToken
                override fun isCurrentApplied(token: DocumentSessionToken): Boolean =
                    sessionCoordinator.isCurrentApplied(token)

                override suspend fun awaitReadySession(): DocumentSession = awaitReadyStage6Session()

                override suspend fun sourceIdentity(sourceUri: String): DocumentSourceIdentityV1 {
                    val uri = sourceUri.toUri()
                    val name = stage7Worker.withWorker { getFileName(bundleWorkflowContext, uri) }
                    return documentSourceIdentityForSnapshot(uri, name)
                }

                override suspend fun currentSourceFingerprint(sourceUri: String): SourceFingerprint? =
                    withContext(Dispatchers.IO) {
                        fingerprintContentUri(bundleWorkflowContext, sourceUri.toUri())
                    }

                override suspend fun captureCurrentSnapshot(token: DocumentSessionToken): DocumentSnapshotV1? =
                    withContext(Dispatchers.Main.immediate) {
                        sessionCoordinator.captureCurrentSnapshotWithinDocumentTransaction(token)
                    }

                override suspend fun loadDurableSnapshot(association: com.example.myapplication.stage2.DocumentAssociation) =
                    withContext(Dispatchers.IO) { localDocumentRepository.load(association) }

                override suspend fun captureDurableSnapshotState(
                    association: com.example.myapplication.stage2.DocumentAssociation
                ): DocumentDurableSnapshotState = withContext(Dispatchers.IO) {
                    localDocumentRepository.captureDurableSnapshotState(association)
                }

                override suspend fun persistAndApplyCurrentSnapshot(
                    token: DocumentSessionToken,
                    snapshot: DocumentSnapshotV1
                ): SessionSnapshotApplyResult = withContext(Dispatchers.Main.immediate) {
                    sessionCoordinator.persistAndApplyCurrentSnapshotWithinDocumentTransaction(
                        token = token,
                        snapshot = snapshot
                    )
                }

                override suspend fun restoreCurrentSnapshot(
                    token: DocumentSessionToken,
                    durableSnapshot: DocumentSnapshotV1,
                    liveSnapshot: DocumentSnapshotV1
                ): SessionSnapshotApplyResult = withContext(Dispatchers.Main.immediate) {
                    sessionCoordinator.restoreSnapshotWithinDocumentTransaction(
                        token = token,
                        durableSnapshot = durableSnapshot,
                        liveSnapshot = liveSnapshot
                    )
                }

                override suspend fun restoreCurrentSnapshot(
                    token: DocumentSessionToken,
                    durableState: DocumentDurableSnapshotState,
                    liveSnapshot: DocumentSnapshotV1
                ): SessionSnapshotApplyResult = withContext(Dispatchers.Main.immediate) {
                    sessionCoordinator.restoreSnapshotStateWithinDocumentTransaction(
                        token = token,
                        durableState = durableState,
                        liveSnapshot = liveSnapshot
                    )
                }

                override fun openPhotoStore(documentId: com.example.myapplication.stage2.DocumentId): DocumentBundlePhotoStore {
                    val store = DocumentPhotoAssetStore(bundleWorkflowContext.filesDir, documentId)
                    return object : DocumentBundlePhotoStore {
                        override fun capturePhotoAssetsForAdmission(
                            durableSnapshot: DocumentSnapshotV1,
                            liveSnapshot: DocumentSnapshotV1
                        ): BundlePhotoCapture {
                            val capture = store.capturePhotoAssetsForAdmission(durableSnapshot, liveSnapshot)
                            return BundlePhotoCapture(capture.assets) { capture.close() }
                        }

                        override fun reconcilePhotoContent(
                            durableSnapshot: DocumentSnapshotV1,
                            liveSnapshot: DocumentSnapshotV1
                        ) = store.reconcilePhotoContent(durableSnapshot, liveSnapshot)

                        override fun stageImport(photoFiles: PhotoAssetSet) =
                            if (photoFiles.isEmpty()) null else StagedPhotoContentTransaction.stage(
                                store.resolver.root,
                                photoFiles,
                                trustedRootDirectory = bundleWorkflowContext.filesDir
                            )

                        override fun close() = store.close()
                    }
                }

                override suspend fun cleanupAfterCanonicalCommit(
                    session: DocumentSession,
                    acceptedSnapshot: DocumentSnapshotV1
                ) = withContext(Dispatchers.IO) {
                    cleanupPhotoContentAfterCanonicalCommit(session, acceptedSnapshot)
                }

                override fun openBundleInput(uri: String) =
                    bundleWorkflowContext.contentResolver.openInputStream(uri.toUri())

                override fun openBundleOutput(uri: String) =
                    bundleWorkflowContext.contentResolver.openOutputStream(uri.toUri())
            }
        )
    }

    var pendingBundleExportToken by rememberSaveable(
        stateSaver = documentSessionTokenSaver
    ) { mutableStateOf<DocumentSessionToken?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val token = pendingBundleExportToken
        pendingBundleExportToken = null
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.export_cancelled), Toast.LENGTH_SHORT).show()
        } else if (token == null) {
            Toast.makeText(
                context,
                "Save bundle export request expired; reopen the PDF and try again.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    documentBundleWorkflow.export(token, uri.toString())
                    Toast.makeText(context, context.getString(R.string.export_succeeded), Toast.LENGTH_SHORT).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_failed, error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    var importPdfUri by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetPdfUri = importPdfUri?.let(Uri::parse)
        importPdfUri = null
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.import_cancelled), Toast.LENGTH_SHORT).show()
        } else if (targetPdfUri == null) {
            Toast.makeText(
                context,
                "Save bundle import request expired; reopen the PDF and try again.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    when (documentBundleWorkflow.import(targetPdfUri.toString(), uri.toString())) {
                        DocumentBundleImportWorkflowOutcome.OpenPdfRequired ->
                            Toast.makeText(context, context.getString(R.string.open_pdf_before_import), Toast.LENGTH_LONG).show()
                        DocumentBundleImportWorkflowOutcome.Imported ->
                            Toast.makeText(context, "Save bundle imported successfully.", Toast.LENGTH_SHORT).show()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed, error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    // Only a small operation ID crosses saved state. The ViewModel owns one
    // completed private PDF; no annotation graph or bitmap enters the Bundle.
    var pendingPdfExportId by rememberSaveable { mutableStateOf<String?>(null) }
    val pdfExportBusy by vm.pdfExportRequests.busy.collectAsState()
    val pdfExportNotice by vm.pdfExportRequests.notice.collectAsState()
    val exportApplicationContext = context.applicationContext
    LaunchedEffect(vm.pdfExportRequests, exportApplicationContext) {
        try {
            withContext(Dispatchers.IO) {
                vm.pdfExportRequests.configureTemporaryDirectory(exportApplicationContext.cacheDir)
                vm.pdfExportRequests.reconcileTemporaryFiles()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SafeDiagnostics.error(DiagnosticEvent.EXPORT_ACTIVITY, error = error)
        }
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val requestId = pendingPdfExportId
        pendingPdfExportId = null
        vm.pdfExportRequests.complete(requestId, uri?.let { outputUri ->
            { exportApplicationContext.contentResolver.openOutputStream(outputUri) }
        })
    }
    LaunchedEffect(pdfExportNotice) {
        val notice = pdfExportNotice ?: return@LaunchedEffect
        val message = when (notice.outcome) {
            PdfExportOutcome.SUCCEEDED -> R.string.pdf_export_succeeded
            PdfExportOutcome.CANCELLED -> R.string.export_cancelled
            else -> null
        }
        if (message != null) {
            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
            vm.pdfExportRequests.acknowledge(notice.operationId)
        }
    }
    pdfExportNotice?.takeIf {
        it.outcome == PdfExportOutcome.EXPIRED || it.outcome == PdfExportOutcome.FAILED
    }?.let { notice ->
        AlertDialog(
            onDismissRequest = { vm.pdfExportRequests.acknowledge(notice.operationId) },
            title = { Text(stringResource(R.string.pdf_export_dialog_title)) },
            text = { Text(stringResource(if (notice.outcome == PdfExportOutcome.EXPIRED)
                R.string.pdf_export_request_expired else R.string.pdf_export_incomplete)) },
            confirmButton = { TextButton(onClick = { vm.pdfExportRequests.acknowledge(notice.operationId) }) {
                Text(stringResource(android.R.string.ok))
            } }
        )
    }

    val capturePage: () -> Unit = capture@{
        val token = activeSessionToken ?: return@capture
        if (pdfExportBusy || pendingPdfExportId != null || readySessionToken != token ||
            !sessionCoordinator.isCurrentApplied(token)) return@capture
        val requestedPageIndex = selectedPageIndex
        val requestId = vm.pdfExportRequests.begin() ?: return@capture
        Toast.makeText(context, context.getString(R.string.pdf_export_preparing), Toast.LENGTH_SHORT).show()
        // Install the finally block immediately, including when composition is cancelled.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var preparedFile: File? = null
            var photoCapture: PhotoAssetCapture? = null
            var handedToPicker = false
            var failureOutcome = PdfExportOutcome.FAILED
            try {
                documentTransactionBarrier.withDocument(token.documentId) {
                    val session = sessionCoordinator.currentSession()
                    check(session?.token == token && readySessionToken == token &&
                        sessionCoordinator.isCurrentApplied(token)) { "PDF export session expired" }
                    val pageIndex = requestedPageIndex
                    check(pageIndex in 0 until totalPageCount) { "PDF export page is unavailable" }
                    val snapshot = com.example.myapplication.stage1.snapshotFromState(vm,
                        requireNotNull(session).target.association.source)
                    val data = PdfExportData(token, token.sourceUri.toUri(), pageIndex,
                        vm.pagePaths[pageIndex]?.map(DrawnPath::copyForPdfExport).orEmpty(),
                        vm.pageMeasurements[pageIndex]?.map(Measurement::copyForPdfExport).orEmpty(),
                        vm.pageNotes[pageIndex]?.map(Note::copyForPdfExport).orEmpty(),
                        vm.pagePhotoPins[pageIndex]?.map(PhotoPin::copyForPdfExport).orEmpty(),
                        vm.pageShapes[pageIndex]?.map(Shape::copyForPdfExport).orEmpty())
                    stage7Worker.withWorker {
                        val durable = when (val loaded = localDocumentRepository.load(session.target.association)) {
                            is DocumentLoadResult.Loaded -> loaded.snapshot
                            DocumentLoadResult.NotFound -> snapshot
                            is DocumentLoadResult.Failed -> throw IOException("PDF export durable state is unavailable")
                        }
                        DocumentPhotoAssetStore(exportApplicationContext.filesDir, token.documentId).use { store ->
                            photoCapture = store.capturePhotoAssetsForAdmission(durable, snapshot)
                        }
                        vm.pdfExportRequests.configureTemporaryDirectory(exportApplicationContext.cacheDir)
                        val temporaryOwner = vm.pdfExportRequests.temporaryOwnerFor(requestId)
                        val file = vm.pdfExportRequests.createResultFile(requestId).also { preparedFile = it }
                        check(exportPageAsPdf(exportApplicationContext, Uri.fromFile(file), data.sourceUri,
                            data.pageIndex, data.paths, data.measurements, data.notes, data.photoPins,
                            data.shapes, token, stage7Worker, requireNotNull(photoCapture).assets,
                            temporaryOwner, requestId)) {
                            "PDF export preparation failed"
                        }
                        // The frozen PDF now contains all photo bytes. Do not hold a
                        // pool lease or a live document while the external picker waits.
                        photoCapture?.close()
                        photoCapture = null
                    }
                }
                currentCoroutineContext().ensureActive()
                check(vm.pdfExportRequests.prepared(requestId, requireNotNull(preparedFile))) {
                    "PDF export request expired"
                }
                pendingPdfExportId = requestId
                pdfExportLauncher.launch("Construct_Page_${requestedPageIndex + 1}.pdf")
                handedToPicker = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                failureOutcome = PdfExportOutcome.CANCELLED
                throw cancelled
            } catch (error: Exception) {
                SafeDiagnostics.error(DiagnosticEvent.EXPORT_ACTIVITY, error = error)
            } finally {
                if (!handedToPicker) {
                    if (pendingPdfExportId == requestId) pendingPdfExportId = null
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    runNonCancellableFinalizers(
                        { photoCapture?.close() },
                        { if (!handedToPicker) vm.pdfExportRequests.abandon(requestId, failureOutcome) }
                    )
                }
            }
        }
    }

    var isFullScreenImageMode by remember { mutableStateOf(false) }
    
    // Enable immersive mode (hide system bars) when viewing PDF
    val activity = context as? ComponentActivity
    DisposableEffect(currentScreen, isLandscape) {
        if (currentScreen == Screen.VIEWER && activity != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.window.insetsController?.let { controller ->
                    if (isLandscape) {
                        // Landscape: Hide both status bar and navigation bar for full immersion
                        controller.hide(WindowInsets.Type.systemBars())
                    } else {
                        // Portrait: Just hide navigation bar
                        controller.hide(WindowInsets.Type.navigationBars())
                    }
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = if (isLandscape) {
                    // Landscape: Full immersive
                    (android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                } else {
                    // Portrait: Just hide navigation
                    (android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                }
            }
        }
        onDispose {
            // Restore system bars when leaving viewer
            if (activity != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.show(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }
    
    val pageCodes = if (currentScreen == Screen.BROWSER || currentScreen == Screen.VIEWER) {
        rememberPageCodeState(
            context, readySessionToken?.takeIf { it == activeSessionToken }, totalPageCount,
            pdfSearchEngine, sessionCoordinator.documentWorkOwner,
            launchDocumentWork = { token, block -> sessionCoordinator.launchDocumentJob(token, block) }
        ) { token -> sessionCoordinator.isCurrent(token) && sessionCoordinator.isCurrentApplied(token) }
            .also { PageCodeStatus(it, totalPageCount) }
    } else null
    var selectingPageCode by remember(activeSessionToken, selectedPageIndex, currentScreen) { mutableStateOf(false) }
    var toolSettingsScope by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeSessionToken?.documentId, toolScopeRevision) {
        toolSettingsMode = null
        toolSettingsError = false
        toolSettings = com.example.myapplication.stage8.DrawingToolSettings()
        toolSettingsScope = null
        val documentId = activeSessionToken?.documentId?.value
        if (documentId != null) {
            try {
                val (savedScope, savedSettings) = withContext(Dispatchers.IO) {
                    val resolved = toolSettingsStore.scopeForDocument(documentId)
                    resolved to toolSettingsStore.read(resolved)
                }
                toolSettingsScope = savedScope; toolSettings = savedSettings
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { toolSettingsError = true }
        }
    }
    toolSettingsMode?.let { settingsMode ->
        com.example.myapplication.ui.ToolSettingsDialog(settingsMode, toolSettings.style(settingsMode),
            toolSettingsSaving, toolSettingsError, onSave = { style ->
                val capturedScope = toolSettingsScope
                if (capturedScope != null) scope.launch {
                    toolSettingsSaving = true
                    val updated = toolSettings.withStyle(settingsMode, style)
                    try {
                        withContext(Dispatchers.IO) { toolSettingsStore.write(capturedScope, updated) }
                        if (capturedScope == toolSettingsScope) { toolSettings = updated; toolSettingsMode = null; toolSettingsError = false }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { toolSettingsError = true }
                    finally { toolSettingsSaving = false }
                }
            }, onDismiss = { toolSettingsMode = null }, saveEnabled = toolSettingsScope != null)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.options), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.home)) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; projectBrowser.home(); currentScreen = Screen.SELECTOR },
                    icon = { Icon(Icons.Default.Home, null) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.view_pages)) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; currentScreen = Screen.BROWSER },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.search_current_page_menu)) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; searchOnlyCurrentPage = true; showSearchDialog = true },
                    icon = { Icon(Icons.Default.Search, null) }
                )
                if (currentScreen == Screen.VIEWER && !isFullScreenImageMode && pageCodes != null) {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.identify_page_codes)) }, selected = false,
                        onClick = { scope.launch { drawerState.close(); toolMode = ToolMode.PAN; selectingPageCode = true } },
                        icon = { Icon(Icons.Default.DocumentScanner, null) }
                    )
                }
                
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.viewer_screenshot)) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; capturePage() },
                    icon = { Icon(Icons.Default.Screenshot, null) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings)) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; currentScreen = Screen.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) }
                )
            }
        }
    ) {
        // Saved navigation is not proof that this recreated host has loaded.
        // Keep the viewer and its gestures out of the tree until the current
        // verified document/page is ready, just as a browser selection requires.
        val displayedScreen = if (currentScreen == Screen.VIEWER &&
            !acceptsBrowserPageSelection(activeSessionToken, readySessionToken,
                selectedPageIndex, totalPageCount, sessionCoordinator::isCurrent,
                sessionCoordinator::isCurrentApplied)
        ) Screen.BROWSER else currentScreen
        when (displayedScreen) {
            Screen.SELECTOR -> {
                ProjectBrowserScreen(
                    state = projectBrowser,
                    recentFiles = recentFiles,
                    recentLoadFailed = recentLoadFailed,
                    onOpenDrawing = { selection -> selectPdf(selection.uri.toUri(), selection) },
                    onOpenIndividual = onPdfSelected,
                    onPickPdf = { launcher.launch(arrayOf("application/pdf")) },
                    onExport = { sourceUri, name ->
                        scope.launch {
                            try {
                                val source = resolveProjectSource(sourceUri)
                                val session = sessionCoordinator.currentSession()
                                if (session == null || session.token.sourceUri != source ||
                                    activeSessionToken != session.token || readySessionToken != session.token ||
                                    !sessionCoordinator.isCurrentApplied(session.token)
                                ) {
                                    Toast.makeText(context, "Open this PDF before exporting its save bundle.", Toast.LENGTH_LONG).show()
                                } else {
                                    pendingBundleExportToken = session.token
                                    exportLauncher.launch("${name.removeSuffix(".pdf")}_save$SOTAWARE_BUNDLE_EXTENSION")
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { Toast.makeText(context, "The drawing association could not be verified.", Toast.LENGTH_LONG).show() }
                        }
                    },
                    onImport = { sourceUri ->
                        scope.launch {
                            try {
                                importPdfUri = resolveProjectSource(sourceUri)
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { Toast.makeText(context, "The drawing association could not be verified.", Toast.LENGTH_LONG).show() }
                        }
                    },
                    onSettings = { currentScreen = Screen.SETTINGS },
                    googleAuth = googleCredentialDriveAuth,
                    googleIdentity = driveAuthorizationStatus.identity,
                    isUriGrantInUse = { tree ->
                        val durableUse = when (val manifest = localDocumentRepository.readManifest()) {
                            is com.example.myapplication.stage2.ManifestReadResult.Loaded ->
                                manifest.entries.any { sourceUsesTreeGrant(it.sourceUri, tree) }
                            is com.example.myapplication.stage2.ManifestReadResult.Failed -> true
                        }
                        if (durableUse) true else {
                            val activeSource = withContext(Dispatchers.Main.immediate) {
                                sessionCoordinator.currentSession()?.token?.sourceUri ?: pdfUri?.toString()
                            }
                            activeSource?.let { sourceUsesTreeGrant(it, tree) } ?: false
                        }
                    }
                )
            }
            Screen.BROWSER -> {
                val browserReady = readySessionToken != null &&
                    readySessionToken == activeSessionToken &&
                    sessionCoordinator.isCurrent(readySessionToken!!) &&
                    sessionCoordinator.isCurrentApplied(readySessionToken!!)
                BackHandler { currentScreen = Screen.SELECTOR }
                Scaffold(
                    topBar = { 
                        TopAppBar(
                            title = { Text(stringResource(R.string.select_sheet), fontWeight = FontWeight.Bold) },
                            navigationIcon = { 
                                IconButton(onClick = { currentScreen = Screen.SELECTOR }) { 
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_back))
                                } 
                            },
                            actions = {
                                if (browserReady && documentSearchActive) {
                                    IconButton(
                                        onClick = {
                                            // Invalidate before clearing so an
                                            // in-flight job cannot republish.
                                            documentSearchRevision++
                                            documentSearchActive = false
                                            documentSearchTerm = ""
                                            documentSearching = false
                                            pagesWithMatches = emptySet()
                                            documentSearchResults = emptyMap()
                                            vm.pageHighlights.clear()
                                            vm.pageSearchTerms.clear()
                                        }
                                    ) {
                                        Icon(Icons.Default.Clear, stringResource(R.string.clear_search))
                                    }
                                }
                                if (browserReady) {
                                    IconButton(onClick = { showDocumentSearchDialog = true }) {
                                        Icon(Icons.Default.Search, stringResource(R.string.search_document_title))
                                    }
                                }
                            }
                        ) 
                    }
                ) { innerPadding ->
    Box(modifier = Modifier.padding(innerPadding)) {
                        if (browserReady && pdfUri != null) {
                            PdfPageBrowser(
                                uri = pdfUri!!,
                                pageCount = totalPageCount,
                                sessionToken = activeSessionToken,
                                isSessionCurrent = { token ->
                                    token == null ||
                                        (sessionCoordinator.isCurrent(token) && sessionCoordinator.isCurrentApplied(token))
                                },
                                isPageCurrent = { token, page ->
                                    token == null || sessionCoordinator.accepts(
                                        DocumentWorkToken(token, pageIndex = page),
                                        currentPageIndex = selectedPageIndex
                                    )
                                },
                                launchDocumentWork = { token, block -> sessionCoordinator.launchDocumentJob(token, block) },
                                thumbnailCache = vm.thumbnailCache,
                                onThumbnailLoaded = vm::putThumbnail,
                                pagesWithMatches = pagesWithMatches,
                                matchCounts = documentSearchResults,
                                pageCodes = pageCodes?.codes.orEmpty(),
                                modifier = Modifier.fillMaxSize(),
                                onPageSelected = { page ->
                                    if (acceptsBrowserPageSelection(
                                            activeSessionToken = activeSessionToken,
                                            readySessionToken = readySessionToken,
                                            pageIndex = page,
                                            pageCount = totalPageCount,
                                            isCurrent = sessionCoordinator::isCurrent,
                                            isCurrentApplied = sessionCoordinator::isCurrentApplied
                                        )
                                    ) {
                                        selectedPageIndex = page
                                        currentScreen = Screen.VIEWER
                                    }
                                },
                                stage7Worker = stage7Worker
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(16.dp))
                                    Text(stringResource(R.string.loading_document))
                                }
                            }
                        }
                        
                        // Show searching status
                        if (documentSearching) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .align(Alignment.TopCenter)
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.align(Alignment.Center)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Text(
                                            stringResource(R.string.searching_document),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Document search dialog
                if (showDocumentSearchDialog) {
                    AlertDialog(
                        onDismissRequest = { showDocumentSearchDialog = false },
                        title = { Text(stringResource(R.string.search_document_title)) },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = documentSearchInput, 
                                    onValueChange = { documentSearchInput = it }, 
                                    label = { Text(stringResource(R.string.search_term_label)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.search_document_help),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val normalizedInput = documentSearchInput.trim()
                                    documentSearchRevision++
                                    documentSearchTerm = normalizedInput
                                    documentSearchActive = normalizedInput.isNotBlank()
                                    showDocumentSearchDialog = false
                                    documentSearching = false
                                    vm.pageHighlights.clear()
                                    vm.pageSearchTerms.clear()
                                    pagesWithMatches = emptySet()
                                    documentSearchResults = emptyMap()
                                    if (normalizedInput.isNotBlank()) {
                                        showDocumentSearchDialog = false
                                        documentSearching = true
                                        val session = sessionCoordinator.currentSession()
                                        val query = documentSearchTerm
                                        val queryRevision = documentSearchRevision
                                        if (session != null) {
                                            val workToken = DocumentWorkToken(session.token, queryRevision = queryRevision)
                                            val acceptsSearch: (DocumentWorkToken) -> Boolean = { candidate ->
                                                sessionCoordinator.accepts(
                                                    candidate,
                                                    currentQueryRevision = documentSearchRevision
                                                )
                                            }
                                            sessionCoordinator.launchDocumentJob(session.token) {
                                                val results = try {
                                                    pdfSearchEngine.search(
                                                        workToken = workToken,
                                                        query = query,
                                                        pageCount = totalPageCount,
                                                        cacheNamespace = session.token.sourceCacheKey,
                                                        isAccepted = acceptsSearch,
                                                        owner = sessionCoordinator.documentWorkOwner
                                                    )
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (t: Throwable) {
                                                    SafeDiagnostics.error(DiagnosticEvent.SEARCH_ACTIVITY, error = t)
                                                    if (acceptsSearch(workToken)) {
                                                        withContext(Dispatchers.Main.immediate) {
                                                            if (!acceptsSearch(workToken)) return@withContext
                                                            documentSearching = false
                                                            Toast.makeText(
                                                                context,
                                                                context.getString(R.string.document_search_failed, t.message ?: context.getString(R.string.search_unavailable_message)),
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    }
                                                    return@launchDocumentJob
                                                }
                                                if (!acceptsSearch(workToken)) return@launchDocumentJob
                                                withContext(Dispatchers.Main.immediate) {
                                                    if (!acceptsSearch(workToken)) return@withContext
                                                    vm.pageHighlights.clear()
                                                    vm.pageSearchTerms.clear()
                                                    for ((pageIdx, rects) in results) {
                                                        vm.pageHighlights[pageIdx] = rects
                                                        vm.pageSearchTerms[pageIdx] = query
                                                    }
                                                    pagesWithMatches = results.keys.toSet()
                                                    documentSearchResults = results
                                                    documentSearching = false
                                                    val totalHits = results.values.sumOf { it.size }
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.search_found_document, totalHits, results.size),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        } else {
                                            documentSearching = false
                                        }
                                    }
                                }, 
                                shape = RoundedCornerShape(12.dp)
                            ) { 
                                Text(stringResource(R.string.viewer_search))
                            }
                        },
                        dismissButton = { 
                            TextButton(onClick = { showDocumentSearchDialog = false }) { 
                                Text(stringResource(R.string.clear_page_cancel))
                            } 
                        }
                    )
                }
            }
            Screen.VIEWER -> {
                val viewerPointSelection = remember(activeSessionToken, selectedPageIndex, toolMode) {
                    MeasurementPointSelection()
                }
                // The calibration action row occupies the HUD's bottom area.
                // Keep its Cancel/Confirm targets unobstructed until resolved.
                val showViewerHud = !isFullScreenImageMode &&
                    (toolMode != ToolMode.SCALE || viewerPointSelection.secondPoint == null)

                val viewerUri = pdfUri
                val viewerSessionToken = activeSessionToken?.takeIf { token ->
                    readySessionToken == token && sessionCoordinator.isCurrent(token) &&
                        sessionCoordinator.isCurrentApplied(token)
                }
                BackHandler { currentScreen = Screen.BROWSER }
                
                // Determine if this is a tablet-size screen (>= 600dp width)
                val screenWidthDp = configuration.screenWidthDp
                val isTablet = screenWidthDp >= 600
                
                // Format current scale for display
                val currentScaleText = vm.pageScales[selectedPageIndex]?.let { scale ->
                    com.example.myapplication.stage8.formatSourceDistance(72f, scale.pointsPerFoot)
                        ?.let { "1\" = $it" } ?: context.getString(R.string.measurement_out_of_range)
                }
                
                // Loaded by the lifecycle effect above; no provider query in
                // composition.
                val pdfName = viewerPdfName
                
                // In landscape mode, use a simpler layout without top bar
                if (isLandscape) {
                    // Landscape: No Scaffold, just the content with floating controls
                    Box(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Tool rail on the left
                            ToolRail(
                                currentMode = toolMode,
                onModeSelected = { mode ->
                    toolMode = if (toolMode == mode) ToolMode.PAN else mode
                                },
                                canUndo = canUndoAnnotation(selectedPageIndex),
                                canRedo = canRedoAnnotation(selectedPageIndex),
                                onUndo = { undoAnnotation(selectedPageIndex) },
                                onRedo = { redoAnnotation(selectedPageIndex) },
                                onClearPage = { pendingClearPhotoTarget = activePhotoTarget
                                    val clearToken = sessionCoordinator.currentSession()?.token
                                    if (clearToken != null) {
                                        stage8Interactions.requestClear(clearToken, selectedPageIndex)
                                    }
                                    clearDialogRevision++
                                },
                                onToolSettings = { toolSettingsMode = it }, availableModes = ToolMode.entries.filter { !isFullScreenImageMode || it != ToolMode.PHOTO }, isPhoto = isFullScreenImageMode, isVertical = true
                            )
                            
                            // Main canvas area
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                // PDF Canvas with white background
                                Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                                    if (viewerUri != null && viewerSessionToken != null) PdfPageRenderer(
                                        uri = viewerUri,
                                        sessionToken = viewerSessionToken,
                                        isSessionCurrent = { token ->
                                            token == null ||
                                                (sessionCoordinator.isCurrent(token) && sessionCoordinator.isCurrentApplied(token))
                                        },
                                        isPageCurrent = { token, page ->
                                            token == null || sessionCoordinator.accepts(
                                                DocumentWorkToken(token, pageIndex = page),
                                                currentPageIndex = selectedPageIndex
                                            )
                                        },
                                        launchDocumentWork = { token, block -> sessionCoordinator.launchDocumentJob(token, block) },
                                         documentTransactionBarrier = documentTransactionBarrier,
                                         stage7Worker = stage7Worker,
                                         ocrIndex = ocrIndex,
                                         ocrOwner = sessionCoordinator.documentWorkOwner,
                                         onPageCodeRegionSelected = pageCodes?.scan,
                                        selectingPageCode = selectingPageCode, onPageCodeSelectionDismissed = { selectingPageCode = false },
                                        toolSettings = toolSettings, onToolModeSelected = { toolMode = it },
                                         onRequestCameraCapture = { requestedPage, pinId ->
                                             requestCameraCapture(requestedPage, pinId)
                                         },
                                         pageIndex = selectedPageIndex,
                                        mode = toolMode,
                                        pointSelection = viewerPointSelection,
                                        currentScale = vm.pageScales[selectedPageIndex],
                                        paths = vm.pagePaths.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        measurements = vm.pageMeasurements.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        notes = vm.pageNotes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        photoPins = vm.pagePhotoPins.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        shapes = vm.pageShapes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        annotationReducer = annotationReducer,
                                        interactionController = stage8Interactions,
                                        onAnnotationAdded = { toolMode = ToolMode.PAN },
                                        allPagePhotoPins = vm.pagePhotoPins,
                                        searchTerm = searchTerm,
                                        highlightRects = vm.pageHighlights[selectedPageIndex] ?: emptyList(),
                                        onScaleDefined = { pixels, feet, sourceSize ->
                                            val result = com.example.myapplication.stage8.calculatePageScale(pixels, feet)
                                            val scaleValue = (result as? com.example.myapplication.stage8.CalibrationScaleResult.Accepted)
                                                ?.let { PageScale(it.pointsPerFoot) }
                                            val accepted = scaleValue != null &&
                                            annotationReducer.setScale(selectedPageIndex, scaleValue, sourceSize) != AnnotationReducer.Result.Rejected
                                            if (accepted) toolMode = ToolMode.PAN
                                            accepted
                                        },
                                         onDeleteItem = { item -> deleteAnnotationItem(selectedPageIndex, item) },
                                        onFullScreenModeChanged = { isFullScreen -> isFullScreenImageMode = isFullScreen }, onPhotoTargetChanged = { activePhotoTarget = it },
                                          onPhotoAdded = { triggerImmediateSync(SyncReason.PHOTO) }
                                    )
                                }
                                
                                // Floating controls at top
                                FloatingViewerControls(
                                    currentPage = selectedPageIndex,
                                    totalPages = totalPageCount,
                                    onBack = { currentScreen = Screen.BROWSER },
                                    onPreviousPage = { if (selectedPageIndex > 0) selectedPageIndex-- },
                                    onNextPage = { if (selectedPageIndex < totalPageCount - 1) selectedPageIndex++ },
                                    onSearch = { searchOnlyCurrentPage = true; showSearchDialog = true },
                                    onScreenshot = { capturePage() },
                                    onMenu = { scope.launch { drawerState.open() } },
                                    canUndo = canUndoAnnotation(selectedPageIndex),
                                    canRedo = canRedoAnnotation(selectedPageIndex),
                                    onUndo = { undoAnnotation(selectedPageIndex) },
                                    onRedo = { redoAnnotation(selectedPageIndex) },
                                    modifier = Modifier.align(Alignment.TopCenter)
                                )
                                
                                // Instruction banner below floating controls (for non-PAN modes)
                                if (toolMode != ToolMode.PAN && !hintsDisabled) {
                                    InstructionBanner(
                                        mode = toolMode,
                                        hasFirstPoint = viewerPointSelection.hasFirstPoint,
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 56.dp)
                                    )
                                }
                                
                                // HUD overlay in bottom-left corner (hide when viewing full-screen image)
                                if (showViewerHud) {
                                    HudOverlay(
                                        currentPage = selectedPageIndex + 1,
                                        totalPages = totalPageCount,
                                        currentScale = currentScaleText,
                                        currentMode = toolMode,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(12.dp)
                                    )
                                }

                            }
                        }
                    }
                } else {
                    // Portrait: Use Scaffold with top bar and bottom tool rail
                    Scaffold(
                        topBar = {
                            ViewerTopBar(
                                currentPage = selectedPageIndex,
                                totalPages = totalPageCount,
                                pdfName = pdfName,
                                onBack = { currentScreen = Screen.BROWSER },
                                onPreviousPage = { if (selectedPageIndex > 0) selectedPageIndex-- },
                                onNextPage = { if (selectedPageIndex < totalPageCount - 1) selectedPageIndex++ },
                                onSearch = { searchOnlyCurrentPage = true; showSearchDialog = true },
                                onScreenshot = { capturePage() },
                                onMenu = { scope.launch { drawerState.open() } },
                                canUndo = canUndoAnnotation(selectedPageIndex),
                                canRedo = canRedoAnnotation(selectedPageIndex),
                                onUndo = { undoAnnotation(selectedPageIndex) },
                                onRedo = { redoAnnotation(selectedPageIndex) }
                            )
                        },
                        bottomBar = {
                            ToolRail(
                                currentMode = toolMode,
                                onModeSelected = { mode ->
                                    toolMode = if (toolMode == mode) ToolMode.PAN else mode
                                },
                                canUndo = canUndoAnnotation(selectedPageIndex),
                                canRedo = canRedoAnnotation(selectedPageIndex),
                                onUndo = { undoAnnotation(selectedPageIndex) },
                                onRedo = { redoAnnotation(selectedPageIndex) },
                                onClearPage = { pendingClearPhotoTarget = activePhotoTarget
                                    val clearToken = sessionCoordinator.currentSession()?.token
                                    if (clearToken != null) {
                                        stage8Interactions.requestClear(clearToken, selectedPageIndex)
                                    }
                                    clearDialogRevision++
                                },
                                onToolSettings = { toolSettingsMode = it }, availableModes = ToolMode.entries.filter { !isFullScreenImageMode || it != ToolMode.PHOTO }, isPhoto = isFullScreenImageMode, isVertical = false
                            )
                        }
                        ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            // PDF Canvas with white background
                            Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                                if (viewerUri != null && viewerSessionToken != null) PdfPageRenderer(
                                    uri = viewerUri,
                                    sessionToken = viewerSessionToken,
                                    isSessionCurrent = { token ->
                                        token == null ||
                                            (sessionCoordinator.isCurrent(token) && sessionCoordinator.isCurrentApplied(token))
                                    },
                                    isPageCurrent = { token, page ->
                                        token == null || sessionCoordinator.accepts(
                                            DocumentWorkToken(token, pageIndex = page),
                                            currentPageIndex = selectedPageIndex
                                        )
                                    },
                                    launchDocumentWork = { token, block -> sessionCoordinator.launchDocumentJob(token, block) },
                                     documentTransactionBarrier = documentTransactionBarrier,
                                      stage7Worker = stage7Worker,
                                      ocrIndex = ocrIndex,
                                      ocrOwner = sessionCoordinator.documentWorkOwner,
                                      onPageCodeRegionSelected = pageCodes?.scan,
                                        selectingPageCode = selectingPageCode, onPageCodeSelectionDismissed = { selectingPageCode = false },
                                        toolSettings = toolSettings, onToolModeSelected = { toolMode = it },
                                      onRequestCameraCapture = { requestedPage, pinId ->
                                          requestCameraCapture(requestedPage, pinId)
                                      },
                                      pageIndex = selectedPageIndex,
                                    mode = toolMode,
                                    pointSelection = viewerPointSelection,
                                    currentScale = vm.pageScales[selectedPageIndex],
                                    paths = vm.pagePaths.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    measurements = vm.pageMeasurements.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    notes = vm.pageNotes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    photoPins = vm.pagePhotoPins.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    shapes = vm.pageShapes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    annotationReducer = annotationReducer,
                                    interactionController = stage8Interactions,
                                    onAnnotationAdded = { toolMode = ToolMode.PAN },
                                    allPagePhotoPins = vm.pagePhotoPins,
                                    searchTerm = searchTerm,
                                    highlightRects = vm.pageHighlights[selectedPageIndex] ?: emptyList(),
                                    onScaleDefined = { pixels, feet, sourceSize ->
                                        val result = com.example.myapplication.stage8.calculatePageScale(pixels, feet)
                                        val scaleValue = (result as? com.example.myapplication.stage8.CalibrationScaleResult.Accepted)
                                            ?.let { PageScale(it.pointsPerFoot) }
                                        val accepted = scaleValue != null &&
                                            annotationReducer.setScale(selectedPageIndex, scaleValue, sourceSize) != AnnotationReducer.Result.Rejected
                                        if (accepted) toolMode = ToolMode.PAN
                                        accepted
                                    },
                                     onDeleteItem = { item -> deleteAnnotationItem(selectedPageIndex, item) },
                                    onFullScreenModeChanged = { isFullScreen -> isFullScreenImageMode = isFullScreen }, onPhotoTargetChanged = { activePhotoTarget = it },
                                      onPhotoAdded = { triggerImmediateSync(SyncReason.PHOTO) }
                                )
                            }
                            
                            // Instruction banner at top (for non-PAN modes)
                            if (toolMode != ToolMode.PAN && !hintsDisabled) {
                                InstructionBanner(
                                    mode = toolMode,
                                    hasFirstPoint = viewerPointSelection.hasFirstPoint,
                                    modifier = Modifier.align(Alignment.TopCenter)
                                )
                            }
                            
                            // HUD overlay in bottom-left corner (hide when viewing full-screen image)
                            if (showViewerHud) {
                                HudOverlay(
                                    currentPage = selectedPageIndex + 1,
                                    totalPages = totalPageCount,
                                    currentScale = currentScaleText,
                                    currentMode = toolMode,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(12.dp)
                                )
                            }

                        }
                    }
                }
            }
            Screen.SETTINGS -> {
                BackHandler { currentScreen = Screen.SELECTOR }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = { currentScreen = Screen.SELECTOR }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_back))
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Google Drive Backup navigation item
                        Card(
                            onClick = { currentScreen = Screen.DRIVE_SETTINGS },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            "Google Drive Backup",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            if (isSignedIn) 
                                                driveSyncManager.getSignedInEmail() ?: "Signed in"
                                            else 
                                                "Sign in to sync annotations",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Divider()
                        
                        // Remove Hints toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        "Hide Tool Hints",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "Remove instruction banners like 'Tap two points...'",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = hintsDisabled,
                                onCheckedChange = { enabled ->
                                    hintsDisabled = enabled
                                    settingsPrefs.edit().putBoolean("hints_disabled", enabled).apply()
                                }
                            )
                        }
                    }
                }
            }
            
            Screen.DRIVE_SETTINGS -> {
                BackHandler { currentScreen = Screen.SETTINGS }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.google_drive_backup), fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = { currentScreen = Screen.SETTINGS }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_back))
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (isSignedIn) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(stringResource(R.string.signed_in), fontWeight = FontWeight.Bold)
                                                driveSyncManager.getSignedInEmail()?.let {
                                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                TextButton(
                                                    onClick = { launchExplicitGoogleSignIn() },
                                                    enabled = !driveAuthorizationInFlight && !driveRootInFlight
                                                ) {
                                                    Text(stringResource(R.string.switch_google_account))
                                                }
                                                TextButton(
                                                    onClick = {
                                                        scope.launch {
                                                            if (driveAuthorizationInFlight || driveRootInFlight) return@launch
                                                            driveAuthorizationInFlight = true
                                                            try {
                                                                fenceDriveWorkBeforeIdentityChange {
                                                                    driveSyncManager.clearSession()
                                                                }
                                                                googleCredentialDriveAuth.clearCredentialState()
                                                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                                throw cancelled
                                                            } catch (failure: Exception) {
                                                                // Local Drive state is already revoked. Clearing
                                                                // Credential Manager is best effort and never
                                                                // recreates a session on failure.
                                                                SafeDiagnostics.error(DiagnosticEvent.AUTH_ACTIVITY, error = failure)
                                                            } finally {
                                                                driveAuthorizationInFlight = false
                                                            }
                                                            Toast.makeText(context, context.getString(R.string.signed_out), Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    enabled = !driveAuthorizationInFlight && !driveRootInFlight
                                                ) {
                                                    Text(stringResource(R.string.sign_out), color = Color.Red)
                                                }
                                            }
                                        }
                                        
                                        Divider()
                                        
                                        if (backupFolderName != null) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(stringResource(R.string.backup_folder), fontWeight = FontWeight.Bold)
                                                    Text(backupFolderName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = {
                                                    val generation = driveAuthorizationStatus.generation
                                                    scope.launch {
                                                        if (driveRootInFlight) return@launch
                                                        driveRootInFlight = true
                                                        try {
                                                            fenceDriveWorkBeforeIdentityChange {
                                                                driveSyncManager.clearBackupFolder(generation)
                                                            }
                                                        } finally {
                                                            driveRootInFlight = false
                                                        }
                                                    }
                                                }, enabled = !driveRootInFlight && !driveAuthorizationInFlight) {
                                                    Icon(Icons.Default.Clear, stringResource(R.string.clear_folder))
                                                }
                                            }
                                            
                                            if (syncBlocked) {
                                                Text(
                                                    "⚠️ Sync disabled - You chose to keep local changes instead of downloading the backup. Your changes will not sync to prevent overwriting the backup.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.padding(vertical = 8.dp)
                                                )
                                            } else {
                                                Text(
                                                    "Automatic sync every 5 minutes",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            
                                            // Manual sync button
                                            if (pdfUri != null && !syncBlocked) {
                                                Spacer(Modifier.height(8.dp))
                                                OutlinedButton(
                                                    onClick = {
                                                        val requestedSession = sessionCoordinator.currentSession()
                                                        if (requestedSession == null ||
                                                            !sessionCoordinator.isCurrentApplied(requestedSession.token)
                                                        ) return@OutlinedButton
                                                        val requestedBinding = currentSyncBinding(requestedSession)
                                                            ?: return@OutlinedButton
                                                        val requestedName = requestedSession.target.association.source.displayName
                                                            ?: "document.pdf"
                                                        scope.launch {
                                                            if (!sessionCoordinator.isCurrentApplied(requestedBinding.token) ||
                                                                currentSyncBinding(requestedSession) != requestedBinding
                                                            ) return@launch
                                                            Toast.makeText(context, context.getString(R.string.syncing_document, requestedName), Toast.LENGTH_SHORT).show()
                                                            val outcome = syncCoordinator.enqueueUpload(requestedBinding, SyncReason.MANUAL).await()
                                                            if (!sessionCoordinator.isCurrentApplied(requestedBinding.token) ||
                                                                currentSyncBinding(requestedSession) != requestedBinding
                                                            ) return@launch
                                                            if (outcome is SyncOutcome.Uploaded) {
                                                                Toast.makeText(context, context.getString(R.string.sync_complete), Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                Toast.makeText(context, context.getString(R.string.sync_failed), Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(stringResource(R.string.sync_now))
                                                }
                                            }
                                        } else {
                                            Text(
                                                stringResource(R.string.drive_file_backup_help),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                            
                                            Button(
                                                onClick = {
                                                    val generation = driveAuthorizationStatus.generation
                                                    scope.launch {
                                                        if (driveRootInFlight) return@launch
                                                        driveRootInFlight = true
                                                        try {
                                                            val root = driveSyncManager.createRootBackupFolder(generation)
                                                            if (driveSyncManager.authorizationStatus.value.generation != generation) return@launch
                                                            if (root != null && driveSyncManager.setBackupFolder(generation, root.first, root.second)) {
                                                                Toast.makeText(
                                                                    context,
                                                                    context.getString(R.string.backup_folder_set, root.second),
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            } else {
                                                                Toast.makeText(
                                                                    context,
                                                                    context.getString(R.string.folder_create_failed),
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            }
                                                        } catch (_: DriveBackupRootAmbiguityException) {
                                                            if (driveSyncManager.authorizationStatus.value.generation == generation) {
                                                                Toast.makeText(context,
                                                                    context.getString(R.string.backup_root_ambiguous),
                                                                    Toast.LENGTH_LONG).show()
                                                            }
                                                        } finally {
                                                            driveRootInFlight = false
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = !driveRootInFlight && !driveAuthorizationInFlight
                                            ) {
                                                Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.create_sotaware_backup_folder))
                                            }
                                        }
                                    } else {
                                        Text(stringResource(R.string.sign_in_backup_help))
                                        
                                        Button(
                                            onClick = { launchExplicitGoogleSignIn() },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !driveAuthorizationInFlight
                                        ) {
                                            Icon(painterResource(android.R.drawable.ic_menu_upload), null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.sign_in_with_google))
                                        }
                                    }
                                }
                            }
                        }
                        
                        item {
                            Text(
                                "How it works:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "• Each PDF gets its own folder in your backup location\n" +
                                "• All annotations, measurements, notes, and photos are synced\n" +
                                "• Auto-sync every 5 minutes when a PDF is open\n" +
                                "• You'll be notified when updates are available from other users",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Folder browser dialog
        // Update available dialog
        if (showUpdateDialog) {
            AlertDialog(
                onDismissRequest = {
                    showUpdateDialog = false
                    updatePdfName = ""
                    updateSessionToken = null
                    updateBinding = null
                },
                title = { Text(stringResource(R.string.updates_available)) },
                text = {
                    Text(stringResource(R.string.remote_update_message, updatePdfName))
                },
                confirmButton = {
                    TextButton(onClick = {
                        val requestedBinding = updateBinding
                        val requestedName = updatePdfName
                        scope.launch {
                            val activeRequestedToken = requestedBinding?.token ?: return@launch
                            val requestStillActive = showUpdateDialog &&
                                updateSessionToken == activeRequestedToken &&
                                updateBinding == requestedBinding &&
                                updatePdfName == requestedName
                            if (!requestStillActive) return@launch
                            val outcome = if (syncCoordinator.admit(
                                    requestedBinding,
                                    currentSyncScope(sessionCoordinator.currentSession())
                                )
                            ) {
                                syncCoordinator.enqueueRemoteAcceptance(requestedBinding).await()
                            } else {
                                SyncOutcome.StaleSession
                            }
                            val stillCurrent = sessionCoordinator.currentSession()?.let { currentSyncBinding(it) } == requestedBinding
                            if (outcome is SyncOutcome.AppliedRemote && stillCurrent) {
                                syncBlocked = false
                                Toast.makeText(context, context.getString(R.string.updates_downloaded), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.updates_download_failed), Toast.LENGTH_SHORT).show()
                            }
                            if (updateSessionToken == activeRequestedToken && updatePdfName == requestedName) {
                                showUpdateDialog = false
                                updatePdfName = ""
                                updateSessionToken = null
                                updateBinding = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showUpdateDialog = false
                        updatePdfName = ""
                        updateSessionToken = null
                        updateBinding = null
                    }) {
                        Text(stringResource(R.string.later))
                    }
                }
            )
        }

        // A same-source resource found under another device-local UUID is
        // never auto-bound. This dialog is the explicit user-directed link
        // operation; only after stable IDs/properties/fingerprint are
        // re-verified does it offer remote acceptance.
        if (showAdoptionDialog && pendingAdoptionCandidate != null) {
            AlertDialog(
                onDismissRequest = {
                    showAdoptionDialog = false
                    pendingAdoptionCandidate = null
                    pendingAdoptionBinding = null
                },
                title = { Text(stringResource(R.string.link_existing_backup)) },
                text = {
                    Text(
                        "A backup for the same verified source was found under another device. " +
                            "Link it explicitly instead of creating a second document?"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val requestedBinding = pendingAdoptionBinding
                        val requestedCandidate = pendingAdoptionCandidate
                        scope.launch {
                            val session = sessionCoordinator.currentSession()
                            val valid = requestedBinding != null && requestedCandidate != null &&
                                session != null &&
                                sessionCoordinator.isCurrentApplied(session.token) &&
                                currentSyncBinding(session) == requestedBinding &&
                                syncCoordinator.admit(
                                    requestedBinding,
                                    currentSyncScope(session)
                                )
                            if (!valid || requestedBinding == null || requestedCandidate == null) {
                                Toast.makeText(context, context.getString(R.string.backup_not_linked), Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val adopted = syncCoordinator
                                .enqueueAdoptRemote(requestedBinding, requestedCandidate)
                                .await()
                            val accepted = if (adopted is SyncOutcome.Adopted) {
                                syncCoordinator.enqueueRemoteAcceptance(requestedBinding).await()
                            } else adopted
                            if (accepted is SyncOutcome.AppliedRemote) {
                                Toast.makeText(context, context.getString(R.string.backup_linked), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.backup_link_failed), Toast.LENGTH_LONG).show()
                            }
                            val stillCurrent = sessionCoordinator.currentSession()
                                ?.let { currentSyncBinding(it) } == requestedBinding
                            if (adopted is SyncOutcome.Adopted &&
                                accepted !is SyncOutcome.AppliedRemote && stillCurrent
                            ) {
                                // The remote link is durable, but its local apply
                                // still needs an explicit retry. Keep that action
                                // visible while the coordinator blocks uploads.
                                remoteUpdatePdfName = requestedCandidate.displayName
                                remoteUpdateSessionToken = requestedBinding.token
                                remoteUpdateBinding = requestedBinding
                                syncBlocked = true
                                showRemoteUpdateDialog = true
                            }
                            if (pendingAdoptionBinding == requestedBinding &&
                                pendingAdoptionCandidate == requestedCandidate &&
                                (adopted is SyncOutcome.Adopted || !stillCurrent ||
                                    accepted is SyncOutcome.AppliedRemote)
                            ) {
                                showAdoptionDialog = false
                                pendingAdoptionCandidate = null
                                pendingAdoptionBinding = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.link_and_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showAdoptionDialog = false
                        pendingAdoptionCandidate = null
                        pendingAdoptionBinding = null
                    }) { Text(stringResource(R.string.clear_page_cancel)) }
                }
            )
        }
        
        // Dialog for remote updates detected on app startup
        if (showRemoteUpdateDialog) {
            AlertDialog(
                onDismissRequest = {
                    showRemoteUpdateDialog = false
                    remoteUpdatePdfName = ""
                    remoteUpdateSessionToken = null
                    remoteUpdateBinding = null
                },
                title = { Text(stringResource(R.string.remote_changes_detected)) },
                text = {
                    Text(stringResource(R.string.remote_update_since_sync, remoteUpdatePdfName))
                },
                confirmButton = {
                    TextButton(onClick = {
                        val requestedBinding = remoteUpdateBinding
                        val requestedName = remoteUpdatePdfName
                        scope.launch {
                            val activeRequestedToken = requestedBinding?.token ?: return@launch
                            val requestStillActive = showRemoteUpdateDialog &&
                                remoteUpdateSessionToken == activeRequestedToken &&
                                remoteUpdateBinding == requestedBinding &&
                                remoteUpdatePdfName == requestedName
                            if (!requestStillActive) return@launch
                            val outcome = if (syncCoordinator.admit(
                                    requestedBinding,
                                    currentSyncScope(sessionCoordinator.currentSession())
                                )
                            ) {
                                syncCoordinator.enqueueRemoteAcceptance(requestedBinding).await()
                            } else {
                                SyncOutcome.StaleSession
                            }
                            val stillCurrent = sessionCoordinator.currentSession()?.let { currentSyncBinding(it) } == requestedBinding
                            if (outcome is SyncOutcome.AppliedRemote && stillCurrent) {
                                syncBlocked = false
                                Toast.makeText(context, context.getString(R.string.updates_downloaded), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.updates_download_failed), Toast.LENGTH_SHORT).show()
                            }
                            if ((outcome is SyncOutcome.AppliedRemote || !stillCurrent) &&
                                remoteUpdateSessionToken == activeRequestedToken &&
                                remoteUpdatePdfName == requestedName
                            ) {
                                showRemoteUpdateDialog = false
                                remoteUpdatePdfName = ""
                                remoteUpdateSessionToken = null
                                remoteUpdateBinding = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        // The coordinator's persisted Conflict state is the
                        // write barrier; this flag only controls the dialog copy.
                        syncBlocked = true
                        showRemoteUpdateDialog = false
                        remoteUpdatePdfName = ""
                        remoteUpdateSessionToken = null
                        remoteUpdateBinding = null
                        Toast.makeText(context, context.getString(R.string.sync_disabled), Toast.LENGTH_LONG).show()
                    }) {
                        Text(stringResource(R.string.keep_local))
                    }
                }
            )
        }

        if (cameraRecoveryMessage != null) {
            val recoveryOperation = cameraRecoveryOperation
            val canExplicitlyDiscard = recoveryOperation != null &&
                recoveryOperation.status in setOf(
                    CameraCaptureOperationStatus.PREPARED,
                    CameraCaptureOperationStatus.LAUNCHED,
                    CameraCaptureOperationStatus.RESULT_AVAILABLE,
                    CameraCaptureOperationStatus.PROCESSING,
                    CameraCaptureOperationStatus.PUBLISHED,
                    CameraCaptureOperationStatus.RESULT_CANCELLED,
                    CameraCaptureOperationStatus.DISCARDED
                )
            AlertDialog(
                onDismissRequest = ::clearCameraRecoveryPrompt,
                title = { Text("Camera recovery") },
                text = { Text(cameraRecoveryMessage!!) },
                confirmButton = {
                    if (canExplicitlyDiscard && recoveryOperation != null) {
                        TextButton(onClick = {
                            requestCameraRecoveryDiscard(recoveryOperation.operationId)
                        }) {
                            Text(
                                if (recoveryOperation.status == CameraCaptureOperationStatus.LAUNCHED) {
                                    "I confirm the camera is closed"
                                } else {
                                    "Discard capture"
                                }
                            )
                        }
                    } else {
                        TextButton(onClick = ::clearCameraRecoveryPrompt) {
                            Text("Close")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = ::clearCameraRecoveryPrompt) {
                        Text(
                            if (recoveryOperation?.status == CameraCaptureOperationStatus.LAUNCHED) {
                                "Keep waiting"
                            } else {
                                "Later"
                            }
                        )
                    }
                }
            )
        }
    }
}

fun dist(p1: Point, p2: Point) = sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
fun distToSegment(p: Point, a: Point, b: Point): Float {
    val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
    if (l2 == 0f) return sqrt((p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y))
    var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2; t = t.coerceIn(0f, 1f)
    return sqrt((p.x - (a.x + t * dx)) * (p.x - (a.x + t * dx)) + (p.y - (a.y + t * dy)) * (p.y - (a.y + t * dy)))
}

fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && !cursor.isNull(column)) result = cursor.getString(column)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Provider metadata is advisory; URI identity remains usable.
        }
    }
    return result ?: uri.path?.substringAfterLast('/') ?: "Document.pdf"
}

fun getPdfName(context: Context, uri: Uri): String {
    return getFileName(context, uri).removeSuffix(".pdf")
}
