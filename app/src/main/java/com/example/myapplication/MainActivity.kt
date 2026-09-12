package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.ToolRail
import com.example.myapplication.ui.ToolOptionsSheet
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
import com.example.myapplication.stage9.DriveAuthorizationResolutionTracker
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
import com.example.myapplication.stage9b.AnnotationCanvasRendering
import androidx.compose.ui.graphics.nativeCanvas
import com.example.myapplication.stage9b.RecentDocumentRecord
import com.example.myapplication.stage9b.RecentDocumentReadResult
import com.example.myapplication.stage9b.RecentDocumentWriteResult
import com.example.myapplication.stage9b.SharedPreferencesRecentDocumentStore
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
import com.example.myapplication.stage6.BundleExportInput
import com.example.myapplication.stage6.BundleImportResult
import com.example.myapplication.stage6.DecodedDocumentBundle
import com.example.myapplication.stage6.DocumentBundleException
import com.example.myapplication.stage6.DocumentBundleImportHost
import com.example.myapplication.stage6.DocumentBundleService
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_EXTENSION
import com.example.myapplication.stage6.VerifiedBundleTarget
import com.example.myapplication.stage6.verifyBundleExportSourceFingerprint
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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.FileOutputStream
import java.io.IOException
import java.io.Serializable
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.util.UUID
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix

// Use a local debug flag to gate temporary diagnostic logs
private const val DEBUG_LOG = false
const val STAGE8_INITIAL_PDF_URI_EXTRA = "com.sotaware.construct.stage8.INITIAL_PDF_URI"

/**
 * Compose's pointer-input cancellation adaptation is not an Android
 * ACTION_CANCEL PointerEvent.  SuspendingPointerInputModifierNodeImpl copies
 * the last pressed changes into a synthetic all-up event and marks those
 * changes initially consumed before dispatching it.  A real UP is delivered
 * from the MotionEvent path and is not initially consumed.  Read that
 * incoming consumption before this handler consumes any change of its own.
 */
private fun androidx.compose.ui.input.pointer.PointerEvent.isIncomingCancellation(): Boolean {
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
    }.toMutableMap()
)

private fun photoBytesFor(
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

private fun recycleBitmap(bitmap: Bitmap) {
    runCatching {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private fun decodeCachedBitmapBounded(
    file: File,
    target: BitmapSizePlan
): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val sampling = BitmapBudgetPolicy.photoDecodePlan(
        sourceWidthPx = bounds.outWidth,
        sourceHeightPx = bounds.outHeight,
        viewportWidthPx = target.width,
        viewportHeightPx = target.height,
        qualityMultiplier = 1.0
    ) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampling.inSampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
        inMutable = false
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    return try {
        val actual = if (decoded.config == Bitmap.Config.ARGB_8888) {
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = decoded.width,
                heightPx = decoded.height,
                actualAllocationBytes = actualBitmapAllocationBytes(decoded)
            )
        } else {
            null
        }
        val withinTarget = decoded.width <= sampling.target.width &&
            decoded.height <= sampling.target.height
        if (actual == null || !withinTarget) {
            recycleBitmap(decoded)
            null
        } else {
            decoded
        }
    } catch (error: Throwable) {
        recycleBitmap(decoded)
        throw error
    }
}

/**
 * Bounds-decodes a photo for the measured viewport, applies the existing EXIF
 * display transform, and keeps the transform peak within the Stage 7 policy.
 * This function is deliberately blocking; callers must invoke it through the
 * Stage 7 worker boundary.
 */
private fun decodePhotoBitmapWithExif(
    photoBytes: ByteArray,
    viewportWidthPx: Int? = null,
    viewportHeightPx: Int? = null
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

private fun loadPhotoBitmapBlocking(
    context: Context,
    sessionToken: DocumentSessionToken?,
    reference: String,
    viewportWidthPx: Int? = null,
    viewportHeightPx: Int? = null
): Stage7OwnedResource<Bitmap>? {
    return photoBytesFor(context, sessionToken, reference)?.let { photoBytes ->
        decodePhotoBitmapWithExif(photoBytes, viewportWidthPx, viewportHeightPx)
    }
}

data class PageMarkups(
    val paths: List<DrawnPath>,
    val measurements: List<Measurement>,
    val notes: List<Note>,
    val photoPins: List<PhotoPin> = emptyList(),
    val shapes: List<Shape> = emptyList()
) : Serializable

data class PdfExportData(
    val sessionToken: DocumentSessionToken,
    val sourceUri: Uri,
    val pageIndex: Int,
    val paths: List<DrawnPath>,
    val measurements: List<Measurement>,
    val notes: List<Note>,
    val photoPins: List<PhotoPin>,
    val shapes: List<Shape>
)

private sealed interface ParsedSaveFile {
    data class Bundle(val decoded: DecodedDocumentBundle) : ParsedSaveFile
}

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

/**
 * Revalidates the selected PDF after a bundle has been parsed and while the
 * document barrier is held.  The caller-supplied session/association and
 * verified target revisions all remain authorities; a missing or changed
 * source fails closed before any bundle state or photo bytes are published.
 */
fun verifyBundleImportSourceFingerprint(
    sessionSourceFingerprint: SourceFingerprint?,
    associationSourceFingerprint: SourceFingerprint?,
    targetSourceFingerprint: SourceFingerprint,
    currentSourceFingerprint: SourceFingerprint?
): SourceFingerprint {
    val verified = currentSourceFingerprint
        ?: throw DocumentBundleException(
            "the active PDF source could not be fingerprinted during bundle import"
        )
    if (sessionSourceFingerprint != verified) {
        throw DocumentBundleException(
            "the active PDF source revision changed during bundle import"
        )
    }
    if (associationSourceFingerprint != verified) {
        throw DocumentBundleException(
            "the document association source revision changed during bundle import"
        )
    }
    if (targetSourceFingerprint != verified) {
        throw DocumentBundleException(
            "the verified import target source revision changed during bundle import"
        )
    }
    return verified
}

/**
 * The shared Stage 6 import boundary used by both the current bundle and V0
 * paths.  Identity/revision admission, the document barrier, and the fresh
 * source read all precede the caller's staging, canonical apply, or photo
 * publication body.  The body remains injectable so the JVM tests can drive
 * the same production ordering without instantiating the Compose callback.
 */
internal suspend fun <T> withVerifiedStage6ImportDocument(
    transactionBarrier: DocumentTransactionBarrier,
    documentId: DocumentId,
    sessionSourceUri: String,
    associationDocumentId: DocumentId,
    associationSourceUri: String,
    targetSourceUri: String,
    sessionSourceFingerprint: SourceFingerprint?,
    associationSourceFingerprint: SourceFingerprint?,
    targetSourceFingerprint: SourceFingerprint,
    currentSourceFingerprint: suspend () -> SourceFingerprint?,
    block: suspend () -> T
): T {
    require(associationDocumentId == documentId) {
        "the save file resolved to a different document identity"
    }
    require(sessionSourceUri == associationSourceUri) {
        "the document association source identity changed during import"
    }
    require(associationSourceUri == targetSourceUri) {
        "the save file targets a different source identity"
    }
    return withContext(Dispatchers.IO) {
        transactionBarrier.withDocument(documentId) {
            val barrierSourceFingerprint = withContext(Dispatchers.IO) {
                currentSourceFingerprint()
            }
            verifyBundleImportSourceFingerprint(
                sessionSourceFingerprint = sessionSourceFingerprint,
                associationSourceFingerprint = associationSourceFingerprint,
                targetSourceFingerprint = targetSourceFingerprint,
                currentSourceFingerprint = barrierSourceFingerprint
            )
            block()
        }
    }
}

sealed class PageItem {
    data class Path(val data: DrawnPath) : PageItem()
    data class Measure(val data: Measurement) : PageItem()
    data class NoteItem(val data: Note, val ordinal: Int = -1) : PageItem()
    data class PhotoPinItem(val data: PhotoPin) : PageItem()
    data class ShapeItem(val data: Shape) : PageItem()
}

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
    var showToolMenu by remember { mutableStateOf(false) }
    val stage8Interactions = remember { Stage8InteractionController() }
    var clearDialogRevision by remember { mutableIntStateOf(0) }
    
    val recentStore = remember(context) { SharedPreferencesRecentDocumentStore(context) }
    var recentFiles by remember { mutableStateOf<List<RecentDocumentRecord>>(emptyList()) }
    var recentLoadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(recentStore) {
        when (val loaded = withContext(Dispatchers.IO) { recentStore.read() }) {
            is RecentDocumentReadResult.Loaded -> { recentFiles = loaded.records; recentLoadFailed = false }
            is RecentDocumentReadResult.Failed -> { recentLoadFailed = true; SafeDiagnostics.warn(DiagnosticEvent.INPUT_REJECTED) }
        }
    }
    var expandedMenuUri by remember { mutableStateOf<String?>(null) }  // Track which menu is open
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
    // No active route opens this legacy browser under drive.file. Keep it
    // unreachable until a separately qualified Google Picker migration.
    var showFolderBrowser by remember { mutableStateOf(false) }
    var browseFolders by remember { mutableStateOf<List<DriveSyncManager.DriveFolder>>(emptyList()) }
    var currentBrowseFolderId by remember { mutableStateOf("root") }
    var currentBrowseFolderName by remember { mutableStateOf(context.getString(R.string.my_drive)) }
    var folderBrowseStack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loadingFolders by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var browsingSharedDrives by remember { mutableStateOf(false) }
    var currentSharedDriveId by remember { mutableStateOf<String?>(null) }
    var sharedDrives by remember { mutableStateOf<List<DriveSyncManager.DriveFolder>>(emptyList()) }
    
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
        val restored = withTimeoutOrNull(15_000L) {
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
            CameraCaptureStore(context.filesDir).use { store -> store.readOperation() }
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
                            CameraCaptureStore(context.filesDir).use { store ->
                                store.prepareOperation(
                                    CameraCaptureOperationRequest(
                                        processInstanceId = cameraOperationOwnerId,
                                        documentId = requestedToken.documentId,
                                        sourceUri = requestedToken.sourceUri,
                                        sourceFingerprint = requestedToken.sourceFingerprint,
                                        sessionGeneration = requestedToken.generation,
                                        pageIndex = requestPageIndex,
                                        pinId = requestPinId
                                    )
                                )
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
            SafeDiagnostics.error(DiagnosticEvent.AUTH_ACTIVITY)
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
                if (!installDriveAuthorization(generation, grant)) {
                    Toast.makeText(context, context.getString(R.string.sign_in_failed_generic), Toast.LENGTH_LONG).show()
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
                        if (!installDriveAuthorization(attempt, authorization)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.sign_in_failed_generic),
                                Toast.LENGTH_LONG
                            ).show()
                        }
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
    LaunchedEffect(documentHost) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                try {
                    documentHost.closeAndJoin()
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

    val onPdfSelected: (Uri) -> Unit = { uri ->
        try {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (security: SecurityException) {
                // Some trusted test/local providers grant only a transient read
                // permission; opening the document remains valid for this session.
                SafeDiagnostics.warn(DiagnosticEvent.INPUT_REJECTED)
            }
            scope.launch {
                val result = sessionCoordinator.switchTo(uri.toString())
                val openedSession = when (result) {
                    is SwitchResult.Switched -> result.session
                    is SwitchResult.AlreadyActive -> result.session
                    else -> null
                }
                if (openedSession != null && sessionCoordinator.isCurrentApplied(openedSession.token)) {
                    val openedAt = System.currentTimeMillis()
                    val written = withContext(Dispatchers.IO) {
                        recentStore.record(RecentDocumentRecord.fromAssociation(openedSession.target.association, openedAt))
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
                }
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
            }
        } catch (e: Exception) { 
            SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = e)
            Toast.makeText(context, context.getString(R.string.pdf_open_failed), Toast.LENGTH_LONG).show()
        }
    }

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
            title = { Text(stringResource(R.string.clear_page_title)) },
            text = { Text(stringResource(R.string.clear_page_message)) },
            confirmButton = {
                Button(onClick = {
                    // Confirmation is a state transition even when admission
                    // rejects a stale target or the reducer finds no-op data.
                    clearDialogRevision++
                    val token = sessionCoordinator.currentSession()?.token
                    stage8Interactions.confirmClear(token, selectedPageIndex) { confirmedPage ->
                        if (token != null && sessionCoordinator.isCurrentApplied(token)) annotationReducer.clearPage(confirmedPage)
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
    
    // Export/import save files. New files are self-contained .sotaware ZIP
    // bundles; unsupported retired formats are rejected without changing them.
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
            // SAF resumes across IO; UI state and notifications require Main,
            // independently of the composition scope's frame interceptor.
            scope.launch(Dispatchers.Main.immediate) {
                var exportCapture: PhotoAssetCapture? = null
                try {
                    val exportInput = documentTransactionBarrier.withDocument(token.documentId) {
                        val session = sessionCoordinator.currentSession()
                        require(
                            session?.token == token &&
                                activeSessionToken == token &&
                                readySessionToken == token &&
                                sessionCoordinator.isCurrentApplied(token)
                        ) {
                            "the active document session changed before export"
                        }
                        val currentSourceUri = token.sourceUri.toUri()
                        val fingerprintBeforeCapture = withContext(Dispatchers.IO) {
                            fingerprintContentUri(context, currentSourceUri)
                        }
                        require(token.sourceFingerprint == fingerprintBeforeCapture) {
                            "the active PDF source revision changed before export"
                        }
                        val snapshot = sessionCoordinator
                            .captureCurrentSnapshotWithinDocumentTransaction(token)
                            ?: error("current canonical snapshot became unavailable during export")
                        val verifiedFingerprint = withContext(Dispatchers.IO) {
                            fingerprintContentUri(context, currentSourceUri)
                        }
                        val sourceFingerprint = verifyBundleExportSourceFingerprint(
                            sessionSourceUri = token.sourceUri,
                            sessionSourceFingerprint = token.sourceFingerprint,
                            snapshot = snapshot,
                            currentSourceFingerprint = verifiedFingerprint
                        )
                        val photoFiles = withContext(Dispatchers.IO) {
                            DocumentPhotoAssetStore(context.filesDir, token.documentId).use { store ->
                                val durable = when (val loaded = localDocumentRepository.load(requireNotNull(session).target.association)) {
                                    is DocumentLoadResult.Loaded -> loaded.snapshot
                                    DocumentLoadResult.NotFound -> snapshot
                                    is DocumentLoadResult.Failed -> throw DocumentBundleException("durable state unavailable during export")
                                }
                                store.capturePhotoAssetsForAdmission(durable, snapshot)
                                    .also { exportCapture = it }.assets
                            }
                        }
                        BundleExportInput(
                            exportedDocumentId = token.documentId,
                            source = snapshot.source,
                            sourceFingerprint = sourceFingerprint,
                            snapshot = snapshot,
                            photoFiles = photoFiles
                        )
                    }
                    withContext(Dispatchers.IO) {
                        documentBundleService.writeBundleAndCloseCancellable(
                            openOutput = { context.contentResolver.openOutputStream(uri) },
                            input = exportInput
                        )
                    }
                    Toast.makeText(context, context.getString(R.string.export_succeeded), Toast.LENGTH_SHORT).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { exportCapture?.close() }
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
            val saveFileUri = uri
            scope.launch(Dispatchers.Main.immediate) {
                var importedBundle: DecodedDocumentBundle? = null
                try {
                    if (sessionCoordinator.currentSession() == null && pdfUri == null) {
                        Toast.makeText(context, context.getString(R.string.open_pdf_before_import), Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val session = awaitReadyStage6Session()
                    require(
                        activeSessionToken == session.token &&
                            readySessionToken == session.token &&
                            sessionCoordinator.isCurrentApplied(session.token)
                    ) {
                        "the active document session is not ready for import"
                    }
                    val selectedSourceName = stage7Worker.withWorker {
                        getFileName(context, targetPdfUri)
                    }
                    val selectedSource = documentSourceIdentityForSnapshot(
                        targetPdfUri,
                        selectedSourceName
                    )
                    val fingerprint = withContext(Dispatchers.IO) {
                        requireNotNull(fingerprintContentUri(context, targetPdfUri)) {
                            "the current PDF source could not be fingerprinted"
                        }
                    }
                    require(session.token.sourceUri == selectedSource.sourceUri) {
                        "the save file targets a different PDF than the active session"
                    }
                    require(session.token.sourceFingerprint == fingerprint) {
                        "the active PDF source revision no longer matches this import"
                    }
                    val association = session.target.association
                    require(association.documentId == session.token.documentId) {
                        "the save file resolved to a different document identity"
                    }
                    require(association.source.sourceUri == selectedSource.sourceUri) {
                        "the save file targets a different source identity"
                    }
                    require(association.sourceFingerprint == session.token.sourceFingerprint) {
                        "the document association source revision changed during import"
                    }

                    val parsedSaveFile = withContext(Dispatchers.IO) {
                        ParsedSaveFile.Bundle(documentBundleService.readBundleFromCancellable {
                            context.contentResolver.openInputStream(saveFileUri)
                        }.also { importedBundle = it })
                    }
                    // Current bundle import is local and never advances Drive metadata implicitly.

                    when (parsedSaveFile) {
                        is ParsedSaveFile.Bundle -> {
                            val rebound = withContext(Dispatchers.IO) {
                                documentBundleService.rebindToVerifiedTarget(
                                    parsedSaveFile.decoded,
                                    VerifiedBundleTarget(
                                        documentId = session.token.documentId,
                                        source = association.source,
                                        sourceFingerprint = fingerprint
                                    )
                                )
                            }
                            // Hold the same document barrier while moving the
                            // bounded Stage 6 transaction to IO. Coordinator
                            // callbacks that read/publish Compose state switch
                            // explicitly to Main.immediate below.
                            val applied = withVerifiedStage6ImportDocument(
                                transactionBarrier = documentTransactionBarrier,
                                documentId = session.token.documentId,
                                sessionSourceUri = session.token.sourceUri,
                                associationDocumentId = association.documentId,
                                associationSourceUri = association.source.sourceUri,
                                targetSourceUri = rebound.snapshot.source.sourceUri,
                                sessionSourceFingerprint = session.token.sourceFingerprint,
                                associationSourceFingerprint = association.sourceFingerprint,
                                targetSourceFingerprint = rebound.target.sourceFingerprint,
                                currentSourceFingerprint = {
                                    fingerprintContentUri(context, targetPdfUri)
                                }
                            ) {
                                val host = object : DocumentBundleImportHost {
                                    override val documentId: DocumentId = session.token.documentId

                                    override suspend fun captureCurrentLiveSnapshot() =
                                        withContext(Dispatchers.Main.immediate) {
                                            sessionCoordinator.captureCurrentSnapshotWithinDocumentTransaction(session.token)
                                                ?: error("current canonical snapshot became unavailable during bundle import")
                                        }

                                    override suspend fun captureCurrentDurableSnapshot() = withContext(Dispatchers.IO) {
                                        when (val loaded = localDocumentRepository.load(association)) {
                                            is DocumentLoadResult.Loaded -> loaded.snapshot
                                            DocumentLoadResult.NotFound -> null
                                            is DocumentLoadResult.Failed -> throw DocumentBundleException(
                                                "current durable snapshot could not be read during bundle import",
                                                IllegalStateException(loaded.error.toString())
                                            )
                                        }
                                    }

                                    override suspend fun captureCurrentDurableState(): DocumentDurableSnapshotState =
                                        withContext(Dispatchers.IO) {
                                            localDocumentRepository.captureDurableSnapshotState(association)
                                        }

                                    override suspend fun persistAndApply(snapshot: com.example.myapplication.stage1.DocumentSnapshotV1) =
                                        withContext(Dispatchers.Main.immediate) {
                                            sessionCoordinator.persistAndApplyCurrentSnapshotWithinDocumentTransaction(
                                                token = session.token,
                                                snapshot = snapshot
                                            )
                                        }

                                    override suspend fun restore(
                                        durableSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1,
                                        liveSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
                                    ) = withContext(Dispatchers.Main.immediate) {
                                        sessionCoordinator.restoreSnapshotWithinDocumentTransaction(
                                            token = session.token,
                                            durableSnapshot = durableSnapshot,
                                            liveSnapshot = liveSnapshot
                                        )
                                    }

                                    override suspend fun restore(
                                        durableState: DocumentDurableSnapshotState,
                                        liveSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
                                    ) = withContext(Dispatchers.Main.immediate) {
                                        sessionCoordinator.restoreSnapshotStateWithinDocumentTransaction(
                                            token = session.token,
                                            durableState = durableState,
                                            liveSnapshot = liveSnapshot
                                        )
                                    }
                                }
                                DocumentPhotoAssetStore(
                                    context.filesDir,
                                    session.token.documentId
                                ).use { store ->
                                    val currentLive = host.captureCurrentLiveSnapshot()
                                    val currentDurable = host.captureCurrentDurableSnapshot() ?: currentLive
                                    store.reconcilePhotoContent(currentDurable, currentLive)
                                    val photoTransaction = if (rebound.photoFiles.isEmpty()) {
                                        null
                                    } else {
                                        StagedPhotoContentTransaction.stage(
                                            store.resolver.root,
                                            rebound.photoFiles,
                                            trustedRootDirectory = context.filesDir
                                        )
                                    }
                                    val result = documentBundleService
                                        .applyReboundBundleWithinDocumentTransaction(
                                            bundle = rebound,
                                            host = host,
                                            photoTransaction = photoTransaction
                                        )
                                    if (result is BundleImportResult.Applied) {
                                        withContext(Dispatchers.IO) {
                                            cleanupPhotoContentAfterCanonicalCommit(session, rebound.snapshot)
                                        }
                                    }
                                    result
                                }
                            }
                            when (applied) {
                                BundleImportResult.Applied -> Toast.makeText(
                                    context,
                                    "Save bundle imported successfully.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                BundleImportResult.Stale -> error("the active document changed during bundle import")
                                is BundleImportResult.Failed -> throw applied.cause
                            }
                        }


                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { importedBundle?.close() }
                }
            }
        }
    }
    
    // PDF export launcher - allows user to choose save location
    var pendingPdfExportData by remember { mutableStateOf<PdfExportData?>(null) }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { outputUri ->
            pendingPdfExportData?.let { exportData ->
                sessionCoordinator.launchDocumentJob(exportData.sessionToken) {
                    try {
                        val success = exportPageAsPdf(
                            context = context,
                            outputUri = outputUri,
                            sourceUri = exportData.sourceUri,
                            pageIndex = exportData.pageIndex,
                            paths = exportData.paths,
                            measurements = exportData.measurements,
                            notes = exportData.notes,
                            photoPins = exportData.photoPins,
                            shapes = exportData.shapes,
                            photoSessionToken = exportData.sessionToken,
                            stage7Worker = stage7Worker
                        )
                        if (!sessionCoordinator.isCurrentApplied(exportData.sessionToken)) {
                            return@launchDocumentJob
                        }
                        stage7Worker.withMain {
                            if (sessionCoordinator.isCurrentApplied(exportData.sessionToken)) {
                                if (success) {
                                    Toast.makeText(context, context.getString(R.string.pdf_export_succeeded), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.pdf_export_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (sessionCoordinator.isCurrentApplied(exportData.sessionToken)) {
                            stage7Worker.withMain {
                                if (sessionCoordinator.isCurrentApplied(exportData.sessionToken)) {
                                    Toast.makeText(context, context.getString(R.string.pdf_export_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        SafeDiagnostics.error(DiagnosticEvent.EXPORT_ACTIVITY, error = error)
                    }
                }
            }
            pendingPdfExportData = null
        }
    }

    val capturePage: () -> Unit = {
        val uri = pdfUri
        val token = activeSessionToken
        val session = sessionCoordinator.currentSession()
        val canCapture = uri != null && token != null &&
            session?.token == token &&
            readySessionToken == token &&
            sessionCoordinator.isCurrentApplied(token) &&
            uri.toString() == token.sourceUri
        if (canCapture) {
            // Prepare data for export and launch file picker
            pendingPdfExportData = PdfExportData(
                sessionToken = token!!,
                sourceUri = uri!!,
                pageIndex = selectedPageIndex,
                paths = vm.pagePaths[selectedPageIndex]
                    ?.map(DrawnPath::copyForPdfExport)
                    ?: emptyList(),
                measurements = vm.pageMeasurements[selectedPageIndex]
                    ?.map(Measurement::copyForPdfExport)
                    ?: emptyList(),
                notes = vm.pageNotes[selectedPageIndex]
                    ?.map(Note::copyForPdfExport)
                    ?: emptyList(),
                photoPins = vm.pagePhotoPins[selectedPageIndex]
                    ?.map(PhotoPin::copyForPdfExport)
                    ?: emptyList(),
                shapes = vm.pageShapes[selectedPageIndex]
                    ?.map(Shape::copyForPdfExport)
                    ?: emptyList()
            )
            pdfExportLauncher.launch("Construct_Page_${selectedPageIndex + 1}.pdf")
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
                    onClick = { scope.launch { drawerState.close() }; currentScreen = Screen.SELECTOR },
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
                Scaffold(
                    topBar = { 
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                            actions = {
                                IconButton(onClick = { currentScreen = Screen.SETTINGS }) {
                                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                                }
                            }
                        ) 
                    },
                    floatingActionButton = { LargeFloatingActionButton(onClick = { launcher.launch(arrayOf("application/pdf")) }, containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp)) { Icon(Icons.Default.Add, stringResource(R.string.open_pdf), Modifier.size(36.dp)) } }
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Box(modifier = Modifier.weight(0.45f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_icon), 
                                    contentDescription = stringResource(R.string.logo),
                                    modifier = Modifier.size(280.dp),
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.digital_field_plans), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Surface(modifier = Modifier.weight(0.55f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(stringResource(R.string.recent_drawings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                if (recentLoadFailed) Text("Recent drawings are unavailable; saved drawing data has not been changed.")
                                else if (recentFiles.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_recent_drawings), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(recentFiles, key = { it.documentId.value + ":" + it.sourceUri }) { file ->
                                        Card(onClick = { onPdfSelected(Uri.parse(file.sourceUri)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                                            ListItem(
                                                headlineContent = { Text((file.displayName ?: "Drawing"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
                                                supportingContent = { Text(stringResource(R.string.blueprint), style = MaterialTheme.typography.bodySmall) },
                                                leadingContent = { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Info, null, Modifier.padding(8.dp)) } },
                                                trailingContent = {
                                                    Box {
                                                        IconButton(onClick = { expandedMenuUri = file.sourceUri }) {
                                                            Icon(Icons.Default.MoreVert, stringResource(R.string.options))
                                                        }
                                                        DropdownMenu(
                                                            expanded = expandedMenuUri == file.sourceUri,
                                                            onDismissRequest = { expandedMenuUri = null }
                                                        ) {
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.export_save_file)) },
                                                                onClick = {
                                                                    val session = sessionCoordinator.currentSession()
                                                                    if (session == null ||
                                                                        session.token.sourceUri != file.sourceUri ||
                                                                        activeSessionToken != session.token ||
                                                                        readySessionToken != session.token ||
                                                                        !sessionCoordinator.isCurrentApplied(session.token)
                                                                    ) {
                                                                        Toast.makeText(
                                                                            context,
                                                                            "Open this PDF before exporting its save bundle.",
                                                                            Toast.LENGTH_LONG
                                                                        ).show()
                                                                    } else {
                                                                        pendingBundleExportToken = session.token
                                                                        exportLauncher.launch(
                                                                            "${(file.displayName ?: "Drawing").removeSuffix(".pdf")}_save$SOTAWARE_BUNDLE_EXTENSION"
                                                                        )
                                                                    }
                                                                    expandedMenuUri = null
                                                                },
                                                                leadingIcon = { Icon(Icons.Default.Share, null) }
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.load_save_file)) },
                                                                onClick = {
                                                                    importPdfUri = file.sourceUri
                                                                    importLauncher.launch(
                                                                        arrayOf("application/zip", "application/octet-stream")
                                                                    )
                                                                    expandedMenuUri = null
                                                                },
                                                                leadingIcon = { Icon(Icons.Default.Download, null) }
                                                            )
                                                        }
                                                    }
                                                },
                                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                    "1\" = ${formatFeet(1f / scale.pointsPerFoot * 72f)}" // PDF source coordinates use 72 points per inch
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
                    showToolMenu = stage8Interactions.selectMode(toolMode)
                                },
                                canUndo = canUndoAnnotation(selectedPageIndex),
                                canRedo = canRedoAnnotation(selectedPageIndex),
                                onUndo = { undoAnnotation(selectedPageIndex) },
                                onRedo = { redoAnnotation(selectedPageIndex) },
                                onClearPage = {
                                    val clearToken = sessionCoordinator.currentSession()?.token
                                    if (clearToken != null) {
                                        stage8Interactions.requestClear(clearToken, selectedPageIndex)
                                    }
                                    clearDialogRevision++
                                },
                                isVertical = true
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
                                         onRequestCameraCapture = { requestedPage, pinId ->
                                             requestCameraCapture(requestedPage, pinId)
                                         },
                                         pageIndex = selectedPageIndex,
                                        mode = toolMode, 
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
                                        onScaleDefined = { pixels, feet ->
                                            val result = com.example.myapplication.stage8.calculatePageScale(pixels, feet)
                                            val scaleValue = (result as? com.example.myapplication.stage8.CalibrationScaleResult.Accepted)
                                                ?.let { PageScale(it.pointsPerFoot) }
                                            val accepted = scaleValue != null &&
                                            annotationReducer.setScale(selectedPageIndex, scaleValue) != AnnotationReducer.Result.Rejected
                                            if (accepted) toolMode = ToolMode.PAN
                                            accepted
                                        },
                                         onDeleteItem = { item -> deleteAnnotationItem(selectedPageIndex, item) },
                                        onFullScreenModeChanged = { isFullScreen -> isFullScreenImageMode = isFullScreen },
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
                                        hasFirstPoint = false,
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 56.dp)
                                    )
                                }
                                
                                // HUD overlay in bottom-left corner (hide when viewing full-screen image)
                                if (!isFullScreenImageMode) {
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
                                ToolOptionsSheet(
                                    currentMode = toolMode, isVisible = showToolMenu,
                                    isTablet = true, currentScale = currentScaleText,
                                    onDismiss = { stage8Interactions.dismissOptions(); showToolMenu = false },
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
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
                                    showToolMenu = stage8Interactions.selectMode(toolMode)
                                },
                                canUndo = canUndoAnnotation(selectedPageIndex),
                                canRedo = canRedoAnnotation(selectedPageIndex),
                                onUndo = { undoAnnotation(selectedPageIndex) },
                                onRedo = { redoAnnotation(selectedPageIndex) },
                                onClearPage = {
                                    val clearToken = sessionCoordinator.currentSession()?.token
                                    if (clearToken != null) {
                                        stage8Interactions.requestClear(clearToken, selectedPageIndex)
                                    }
                                    clearDialogRevision++
                                },
                                isVertical = false
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
                                      onRequestCameraCapture = { requestedPage, pinId ->
                                          requestCameraCapture(requestedPage, pinId)
                                      },
                                      pageIndex = selectedPageIndex,
                                    mode = toolMode,
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
                                    onScaleDefined = { pixels, feet ->
                                        val result = com.example.myapplication.stage8.calculatePageScale(pixels, feet)
                                        val scaleValue = (result as? com.example.myapplication.stage8.CalibrationScaleResult.Accepted)
                                            ?.let { PageScale(it.pointsPerFoot) }
                                        val accepted = scaleValue != null &&
                                            annotationReducer.setScale(selectedPageIndex, scaleValue) != AnnotationReducer.Result.Rejected
                                        if (accepted) toolMode = ToolMode.PAN
                                        accepted
                                    },
                                     onDeleteItem = { item -> deleteAnnotationItem(selectedPageIndex, item) },
                                    onFullScreenModeChanged = { isFullScreen -> isFullScreenImageMode = isFullScreen },
                                      onPhotoAdded = { triggerImmediateSync(SyncReason.PHOTO) }
                                )
                            }
                            
                            // Instruction banner at top (for non-PAN modes)
                            if (toolMode != ToolMode.PAN && !hintsDisabled) {
                                InstructionBanner(
                                    mode = toolMode,
                                    hasFirstPoint = false,
                                    modifier = Modifier.align(Alignment.TopCenter)
                                )
                            }
                            
                            // HUD overlay in bottom-left corner (hide when viewing full-screen image)
                            if (!isFullScreenImageMode) {
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
                            ToolOptionsSheet(
                                currentMode = toolMode, isVisible = showToolMenu,
                                isTablet = false, currentScale = currentScaleText,
                                onDismiss = { stage8Interactions.dismissOptions(); showToolMenu = false },
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
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
        if (showFolderBrowser) {
            AlertDialog(
                onDismissRequest = { showFolderBrowser = false },
                title = { 
                    Column {
                        Text(stringResource(R.string.select_backup_folder))
                        Text(
                            currentBrowseFolderName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.heightIn(max = 450.dp)) {
                        // Drive type selector tabs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilterChip(
                                selected = !browsingSharedDrives,
                                onClick = {
                                    if (browsingSharedDrives) {
                                        browsingSharedDrives = false
                                        currentSharedDriveId = null
                                        scope.launch {
                                            loadingFolders = true
                                            currentBrowseFolderId = "root"
                                            currentBrowseFolderName = context.getString(R.string.my_drive)
                                            folderBrowseStack = emptyList()
                                            browseFolders = driveSyncManager.listFolders("root")
                                            loadingFolders = false
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.my_drive)) },
                                leadingIcon = if (!browsingSharedDrives) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                } else null
                            )
                            FilterChip(
                                selected = browsingSharedDrives,
                                onClick = {
                                    if (!browsingSharedDrives) {
                                        browsingSharedDrives = true
                                        scope.launch {
                                            loadingFolders = true
                                            currentBrowseFolderName = context.getString(R.string.shared_drives)
                                            folderBrowseStack = emptyList()
                                            currentSharedDriveId = null
                                            sharedDrives = driveSyncManager.listSharedDrives()
                                            browseFolders = sharedDrives
                                            loadingFolders = false
                                        }
                                    }
                                },
                                        label = { Text(stringResource(R.string.shared_drives)) },
                                leadingIcon = if (browsingSharedDrives) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // Navigation row
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Back button
                            if (folderBrowseStack.isNotEmpty()) {
                                IconButton(onClick = {
                                    scope.launch {
                                        val (parentId, parentName) = folderBrowseStack.last()
                                        folderBrowseStack = folderBrowseStack.dropLast(1)
                                        loadingFolders = true
                                        currentBrowseFolderId = parentId
                                        currentBrowseFolderName = parentName
                                        
                                        browseFolders = if (browsingSharedDrives && currentSharedDriveId != null) {
                                            if (folderBrowseStack.isEmpty()) {
                                                // Going back to shared drives list
                                                currentSharedDriveId = null
                                                sharedDrives
                                            } else {
                                                driveSyncManager.listFoldersInSharedDrive(currentSharedDriveId!!, parentId)
                                            }
                                        } else {
                                            driveSyncManager.listFolders(parentId)
                                        }
                                        loadingFolders = false
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_back))
                                }
                            } else {
                                Spacer(Modifier.width(48.dp))
                            }
                            
                            // Create new folder button
                            IconButton(onClick = { 
                                newFolderName = ""
                                showCreateFolderDialog = true 
                            }) {
                                Icon(Icons.Default.CreateNewFolder, stringResource(R.string.create_folder))
                            }
                        }
                        
                        Divider()
                        
                        if (loadingFolders) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (browseFolders.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (browsingSharedDrives && currentSharedDriveId == null) 
                                        "No shared drives found" 
                                    else 
                                        "No folders found", 
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(browseFolders) { folder ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                // Navigate into folder or shared drive
                                                scope.launch {
                                                    folderBrowseStack = folderBrowseStack + Pair(currentBrowseFolderId, currentBrowseFolderName)
                                                    loadingFolders = true
                                                    currentBrowseFolderId = folder.id
                                                    currentBrowseFolderName = folder.name
                                                    
                                                    browseFolders = if (browsingSharedDrives) {
                                                        if (folder.isSharedDrive) {
                                                            // Entering a shared drive
                                                            currentSharedDriveId = folder.id
                                                            driveSyncManager.listFoldersInSharedDrive(folder.id, null)
                                                        } else {
                                                            // Navigating within a shared drive
                                                            driveSyncManager.listFoldersInSharedDrive(currentSharedDriveId!!, folder.id)
                                                        }
                                                    } else {
                                                        driveSyncManager.listFolders(folder.id)
                                                    }
                                                    loadingFolders = false
                                                }
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (folder.isSharedDrive) Icons.Default.FolderShared else Icons.Default.Folder, 
                                            null, 
                                            tint = if (folder.isSharedDrive) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(folder.name, modifier = Modifier.weight(1f))
                                        Icon(
                                            Icons.Default.KeyboardArrowRight, 
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Divider()
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    // Only show select button when we're in a folder (not at shared drives list level)
                    if (!browsingSharedDrives || currentSharedDriveId != null || currentBrowseFolderId == "root") {
                        Button(onClick = {
                            val currentSession = sessionCoordinator.currentSession()
                            val selectedScope = currentSyncScope(currentSession)
                                ?.copy(backupRootId = currentBrowseFolderId)
                            if (selectedScope != null) syncCoordinator.updateCurrentScope(selectedScope)
                            driveSyncManager.setBackupFolder(driveAuthorizationStatus.generation, currentBrowseFolderId, currentBrowseFolderName)
                            showFolderBrowser = false
                            Toast.makeText(context, context.getString(R.string.backup_folder_set, currentBrowseFolderName), Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.select_this_folder))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFolderBrowser = false }) {
                        Text(stringResource(R.string.clear_page_cancel))
                    }
                }
            )
        }
        
        // Create folder dialog
        if (showCreateFolderDialog) {
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text(stringResource(R.string.create_new_folder)) },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text(stringResource(R.string.folder_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                scope.launch {
                                    val newFolder = if (browsingSharedDrives && currentSharedDriveId != null) {
                                        driveSyncManager.createFolderInSharedDrive(newFolderName, currentSharedDriveId!!, currentBrowseFolderId)
                                    } else {
                                        driveSyncManager.createFolder(newFolderName, currentBrowseFolderId)
                                    }
                                    
                                    if (newFolder != null) {
                                        // Refresh folder list
                                        browseFolders = if (browsingSharedDrives && currentSharedDriveId != null) {
                                            driveSyncManager.listFoldersInSharedDrive(currentSharedDriveId!!, currentBrowseFolderId)
                                        } else {
                                            driveSyncManager.listFolders(currentBrowseFolderId)
                                        }
                                        Toast.makeText(context, context.getString(R.string.folder_created, newFolder.name), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.folder_create_failed), Toast.LENGTH_SHORT).show()
                                    }
                                    showCreateFolderDialog = false
                                }
                            }
                        },
                        enabled = newFolderName.isNotBlank()
                    ) {
                        Text(stringResource(R.string.create))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text(stringResource(R.string.clear_page_cancel))
                    }
                }
            )
        }
        
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
                            if (pendingAdoptionBinding == requestedBinding &&
                                pendingAdoptionCandidate == requestedCandidate
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
                            if (remoteUpdateSessionToken == activeRequestedToken && remoteUpdatePdfName == requestedName) {
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

@Composable
fun PdfPageBrowser(
    uri: Uri, 
    pageCount: Int,
    sessionToken: DocumentSessionToken? = null,
    isSessionCurrent: (DocumentSessionToken?) -> Boolean = { true },
    isPageCurrent: (DocumentSessionToken?, Int) -> Boolean = { token, _ -> isSessionCurrent(token) },
    launchDocumentWork: ((DocumentSessionToken, suspend () -> Unit) -> Job)? = null,
    thumbnailCache: Stage7BitmapCache,
    onThumbnailLoaded: ((Stage7CacheKey<String>, Stage7OwnedResource<Bitmap>) -> ByteAwareCachePutResult)? = null,
    pagesWithMatches: Set<Int> = emptySet(),
    matchCounts: Map<Int, List<RectF>> = emptyMap(),
    modifier: Modifier = Modifier, 
    onPageSelected: (Int) -> Unit,
    stage7Worker: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary()
) {
    val context = LocalContext.current
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(pageCount) { index ->
            val cacheIdentity = sessionToken?.sourceCacheKey ?: uri.toString()
            val cacheKey = Stage7CacheKey(cacheIdentity, index.toString())
            val cachedThumbnail = thumbnailCache.entries[cacheKey]
            if (cachedThumbnail == null) {
                LaunchedEffect(uri, sessionToken, index) {
                    val loadThumbnail: suspend () -> Unit = {
                        stage7Worker.computeAndPublish(
                            compute = {
                                val owner = Stage7ResourceOwner<Bitmap>(::recycleBitmap)
                                try {
                                    val cacheFile = getThumbCacheFile(context, index, cacheIdentity)
                                    val cached = if (cacheFile.exists()) {
                                        val cachedTarget = BitmapBudgetPolicy.bitmapPlan(
                                            BitmapBudgetPolicy.THUMBNAIL_TARGET_WIDTH_PX,
                                            BitmapBudgetPolicy.THUMBNAIL_TARGET_WIDTH_PX
                                        )
                                        owner.ownedCreatedOrNull {
                                            cachedTarget?.let { target ->
                                                decodeCachedBitmapBounded(cacheFile, target)
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                    val loaded = cached ?: run {
                                        val descriptor = try {
                                            context.contentResolver.openFileDescriptor(uri, "r")
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            null
                                        }
                                        if (descriptor == null) {
                                            null
                                        } else {
                                            descriptor.use { pfd ->
                                                PdfRenderer(pfd).use { renderer ->
                                                    if (index < 0 || index >= renderer.pageCount) {
                                                        null
                                                    } else {
                                                        val page = renderer.openPage(index)
                                                        try {
                                                            val thumbnailPlan = BitmapBudgetPolicy.pdfThumbnailPlan(
                                                                pageWidthPx = page.width,
                                                                pageHeightPx = page.height
                                                            )
                                                            if (thumbnailPlan == null) {
                                                                null
                                                            } else {
                                                                val ownedBitmap = owner.ownedCreated {
                                                                    Bitmap.createBitmap(
                                                                        thumbnailPlan.width,
                                                                        thumbnailPlan.height,
                                                                        Bitmap.Config.ARGB_8888
                                                                    )
                                                                }
                                                                val actual = if (ownedBitmap.value.config == Bitmap.Config.ARGB_8888) {
                                                                    BitmapBudgetPolicy.actualAllocationPlan(
                                                                        widthPx = ownedBitmap.value.width,
                                                                        heightPx = ownedBitmap.value.height,
                                                                        actualAllocationBytes = actualBitmapAllocationBytes(ownedBitmap.value)
                                                                    )
                                                                } else {
                                                                    null
                                                                }
                                                                if (actual == null ||
                                                                    ownedBitmap.value.width != thumbnailPlan.width ||
                                                                    ownedBitmap.value.height != thumbnailPlan.height
                                                                ) {
                                                                    owner.close()
                                                                    null
                                                                } else {
                                                                    Canvas(ownedBitmap.value).drawColor(android.graphics.Color.WHITE)
                                                                    page.render(
                                                                        ownedBitmap.value,
                                                                        null,
                                                                        null,
                                                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                                                    )
                                                                    currentCoroutineContext().ensureActive()
                                                                    try {
                                                                        FileOutputStream(cacheFile).use { out ->
                                                                            ownedBitmap.value.compress(
                                                                                Bitmap.CompressFormat.JPEG,
                                                                                80,
                                                                                out
                                                                            )
                                                                        }
                                                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                                        throw cancelled
                                                                    } catch (_: Exception) {
                                                                        // The in-memory thumbnail remains usable if disk caching fails.
                                                                    }
                                                                    currentCoroutineContext().ensureActive()
                                                                    ownedBitmap
                                                                }
                                                            }
                                                        } finally {
                                                            page.close()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (loaded == null) {
                                        owner.close()
                                    } else {
                                        currentCoroutineContext().ensureActive()
                                    }
                                    loaded
                                } catch (error: Throwable) {
                                    owner.close()
                                    throw error
                                }
                            },
                            acceptsBeforeMain = { isSessionCurrent(sessionToken) },
                            acceptsOnMain = {
                                // Browser thumbnails are document-wide. The
                                // viewer's selected-page predicate belongs
                                // only to PdfPageRenderer and must not discard
                                // valid off-screen page thumbnails.
                                isSessionCurrent(sessionToken)
                            },
                            publish = { bitmap ->
                                val admission = onThumbnailLoaded?.invoke(cacheKey, bitmap)
                                    ?: thumbnailCache.putOwned(cacheKey, bitmap)
                                check(admission.accepted) {
                                    "thumbnail cache admission rejected: $admission"
                                }
                            },
                            reject = { rejectedBitmap -> rejectedBitmap.close() }
                        )
                    }

                    var documentJob: Job? = null
                    try {
                        if (sessionToken != null && launchDocumentWork != null) {
                            documentJob = launchDocumentWork(sessionToken, loadThumbnail)
                            documentJob?.join()
                        } else {
                            loadThumbnail()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        SafeDiagnostics.error(DiagnosticEvent.RENDER_ACTIVITY, error = error)
                    } finally {
                        documentJob?.let { job ->
                            if (job.isActive) {
                                withContext(NonCancellable) {
                                    job.cancel()
                                    job.join()
                                }
                            }
                        }
                    }
                }
            }
            val displayLease = remember(cacheKey, cachedThumbnail) {
                cachedThumbnail?.let { thumbnailCache.acquire(cacheKey) }
            }
            DisposableEffect(displayLease) {
                onDispose { displayLease?.close() }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPageSelected(index) }
                    .then(
                        if (pagesWithMatches.contains(index)) 
                            Modifier.shadow(8.dp, RoundedCornerShape(12.dp), spotColor = Color.Yellow)
                        else Modifier
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (pagesWithMatches.contains(index)) 
                        Color.Yellow.copy(alpha = 0.3f) 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                border = if (pagesWithMatches.contains(index)) 
                    androidx.compose.foundation.BorderStroke(3.dp, Color.Yellow) 
                else null
            ) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)) {
                        displayLease?.value?.let { bitmap -> Image(bitmap = bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
                        ?: Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }

                        // Show match count badge if there are matches
                        val matchCount = matchCounts[index]?.size ?: 0
                        if (matchCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.Yellow
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "$matchCount",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.sheet_number, index + 1), Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

internal const val PDF_READY_CANVAS_TAG = "sotaware.pdf.ready-canvas"

@Composable
fun PdfPageRenderer(
    uri: Uri, 
    sessionToken: DocumentSessionToken,
    isSessionCurrent: (DocumentSessionToken?) -> Boolean,
    isPageCurrent: (DocumentSessionToken?, Int) -> Boolean,
    launchDocumentWork: ((DocumentSessionToken, suspend () -> Unit) -> Job)? = null,
    documentTransactionBarrier: DocumentTransactionBarrier,
    stage7Worker: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary(),
    ocrIndex: OcrIndex? = null,
    ocrOwner: DocumentWorkOwner? = null,
    pageIndex: Int, 
    mode: ToolMode, 
    currentScale: PageScale?, 
    paths: SnapshotStateList<DrawnPath>, 
    measurements: SnapshotStateList<Measurement>,
    notes: SnapshotStateList<Note>,
    photoPins: SnapshotStateList<PhotoPin>,
    shapes: SnapshotStateList<Shape>,
    annotationReducer: AnnotationReducer,
    interactionController: Stage8InteractionController = Stage8InteractionController(),
    allPagePhotoPins: SnapshotStateMap<Int, SnapshotStateList<PhotoPin>>,
    searchTerm: String,
    highlightRects: List<RectF>,
    onScaleDefined: (Float, Float) -> Boolean,
    onDeleteItem: (PageItem) -> Unit,
    onFullScreenModeChanged: (Boolean) -> Unit,
    onPhotoAdded: () -> Unit = {},
    onAnnotationAdded: () -> Unit = {},
    onRequestCameraCapture: ((Int, String) -> Unit)? = null,
    onPageRendered: () -> Unit = {}
) {
    val context = LocalContext.current
    val pdfSearchEngine = remember(stage7Worker, ocrIndex) {
        PdfSearchEngine(context, stage7Worker, ocrIndex ?: OcrIndex(context, stage7Worker))
    }
    val textMeasurer = rememberTextMeasurer()
    val annotationDensity = LocalDensity.current.density.coerceAtLeast(0.1f)
    var bitmapOwner by remember(uri, sessionToken, pageIndex) { mutableStateOf<Stage7OwnedResource<Bitmap>?>(null) }
    var sourcePageSize by remember(uri, sessionToken, pageIndex) { mutableStateOf<AnnotationSize?>(null) }
    var scale by rememberSaveable(uri.toString(), sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf(1f) }
    var offsetX by rememberSaveable(uri.toString(), sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf(0f) }
    var offsetY by rememberSaveable(uri.toString(), sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf(0f) }
    
    if (scale.isNaN() || offsetX.isNaN() || offsetY.isNaN()) {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    var firstPoint by rememberSaveable(sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex, mode) { mutableStateOf<Point?>(null) }
    var secondPoint by rememberSaveable(sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex, mode) { mutableStateOf<Point?>(null) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    val currentStroke = remember(uri, sessionToken, pageIndex, mode) { mutableStateListOf<Point>() }
    
    var itemToDelete by remember { mutableStateOf<PageItem?>(null) }
    var selectedItem by remember { mutableStateOf<PageItem?>(null) }
    // Store the screen position where the toolbar should appear (tap location or item's new position after drag)
    var selectionToolbarPos by remember { mutableStateOf(Offset.Zero) }
    // Disambiguation: when multiple items overlap at tap location
    var overlappingItems by remember { mutableStateOf<List<PageItem>>(emptyList()) }
    var showItemPicker by remember { mutableStateOf(false) }
    
    var selectedMeasurement by remember { mutableStateOf<Measurement?>(null) }
    var selectedMeasurementIndex by remember { mutableIntStateOf(-1) }
    var measurementDraft by remember { mutableStateOf<Measurement?>(null) }
    var draggingPointIdx by remember { mutableIntStateOf(-1) } 
    var originalMeasurement by remember { mutableStateOf<Measurement?>(null) }
    
    var calibratePointIdx by remember { mutableIntStateOf(-1) }

    var showNoteDialog by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }
    var noteIsBold by remember { mutableStateOf(false) }
    var notePos by remember { mutableStateOf(Point(0f, 0f)) }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    var selectedNoteIdx by remember { mutableIntStateOf(-1) }
    var draggingNoteIdx by remember { mutableIntStateOf(-1) }
    var isItemDragging by remember { mutableStateOf(false) }
    var originalNote by remember { mutableStateOf<Note?>(null) }
    var noteDraft by remember { mutableStateOf<Note?>(null) }

    // Photo pin state
    var selectedPhotoPinId by remember(sessionToken, pageIndex) { mutableStateOf<String?>(null) }
    val currentPhotoPins by rememberUpdatedState(photoPins)
    var selectedPhotoPin by object : kotlin.properties.ReadWriteProperty<Any?, PhotoPin?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): PhotoPin? =
            currentPhotoPins.firstOrNull { it.id == selectedPhotoPinId }
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: PhotoPin?) {
            selectedPhotoPinId = value?.id
        }
    }
    var showPinImageGallery by remember { mutableStateOf(false) }
    
    // Shape tool state
    var selectedShape by remember { mutableStateOf<Shape?>(null) }
    var showShapeDialog by remember { mutableStateOf(false) }
    var currentShapeType by remember { mutableStateOf(ShapeType.RECTANGLE) }
    var draggingShape by remember { mutableStateOf(false) }
    var originalShape by remember { mutableStateOf<Shape?>(null) }
    var shapeDraft by remember { mutableStateOf<Shape?>(null) }
    var resizingShape by remember { mutableStateOf(false) }
    var rotatingShape by remember { mutableStateOf(false) }
    var shapeInitialPinchDistance by remember { mutableFloatStateOf(0f) }
    
    // Image note state
    var showImageNoteDialog by remember { mutableStateOf(false) }
    var imageNoteInput by remember { mutableStateOf("") }
    var imageNoteIsBold by remember { mutableStateOf(false) }
    var imageNotePos by remember { mutableStateOf(Offset.Zero) }
    var editingImageNote by remember { mutableStateOf<PhotoImageNote?>(null) }
    var currentImageFileName by remember { mutableStateOf<String?>(null) }
    var selectedImageNote by remember { mutableStateOf<PhotoImageNote?>(null) }
    var draggingImageNote by remember { mutableStateOf<PhotoImageNote?>(null) }
    var imageNoteToolMode by remember { mutableStateOf("pan") } // "pan", "place", "select", "shape"
    var originalImageNote by remember { mutableStateOf<PhotoImageNote?>(null) }
    var imageNoteDraft by remember { mutableStateOf<PhotoImageNote?>(null) }
    
    // Image shape tool state
    var selectedImageShape by remember { mutableStateOf<Shape?>(null) }
    var draggingImageShape by remember { mutableStateOf(false) }
    var resizingImageShape by remember { mutableStateOf(false) }
    var originalImageShape by remember { mutableStateOf<Shape?>(null) }
    var imageShapeDraft by remember { mutableStateOf<Shape?>(null) }
    var currentImageShapeType by remember { mutableStateOf(ShapeType.RECTANGLE) }

    // Calibration must use PDF source points, not the sampled display bitmap.
    // Query the page dimensions on the worker side and fence publication to the
    // same document/page session as the renderer.
    LaunchedEffect(uri, sessionToken, pageIndex) {
        sourcePageSize = null
        val resolved = try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
                try {
                    PdfRenderer(descriptor).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return@use null
                        renderer.openPage(pageIndex).use { page ->
                            AnnotationSize(page.width.toFloat(), page.height.toFloat())
                        }
                    }
                } finally {
                    // PdfRenderer does not own the descriptor on every Android
                    // implementation; close the descriptor explicitly here.
                    try { descriptor.close() } catch (_: Throwable) { }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.RENDER_ACTIVITY, error = error)
            null
        }
        if (isSessionCurrent(sessionToken) && isPageCurrent(sessionToken, pageIndex)) {
            sourcePageSize = resolved
        }
    }
    
    // Text selection state (long-press to select, like web) - reset on page change
    var isTextSelecting by remember(sessionToken, pageIndex) { mutableStateOf(false) }
    var textSelectionStartIdx by remember(sessionToken, pageIndex) { mutableIntStateOf(-1) }
    var textSelectionEndIdx by remember(sessionToken, pageIndex) { mutableIntStateOf(-1) }
    var selectedOcrBoxes by remember(sessionToken, pageIndex) { mutableStateOf<List<OcrBox>>(emptyList()) }
    var showCopyButton by remember(sessionToken, pageIndex) { mutableStateOf(false) }
    var copyButtonPos by remember(sessionToken, pageIndex) { mutableStateOf(Offset.Zero) }
    var cachedPageOcr by remember(sessionToken, pageIndex) { mutableStateOf<PageOcr?>(null) }
    val coroutineScopeForOcr = rememberCoroutineScope()
    var draggingSelectionHandle by remember(sessionToken, pageIndex) { mutableStateOf<String?>(null) } // "start" or "end" or null
    
    DisposableEffect(uri, sessionToken, pageIndex) {
        onDispose {
            val previousOwner = bitmapOwner
            bitmapOwner = null
            previousOwner?.close()
        }
    }

    if (showScaleDialog) {
        val calibrationStart = firstPoint
        val calibrationEnd = secondPoint
        val calibrationDistance = if (calibrationStart != null && calibrationEnd != null) {
            sourcePageSize?.let { source ->
                val dx = ((calibrationStart.x - calibrationEnd.x) * source.width).toDouble()
                val dy = ((calibrationStart.y - calibrationEnd.y) * source.height).toDouble()
                kotlin.math.sqrt(dx * dx + dy * dy)
                    .takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
                    ?.toFloat()
            }
        } else null
        com.example.myapplication.ui.CalibrationDialog(
            input = scaleInput,
            pixelDistance = calibrationDistance,
            onInputChange = { scaleInput = it },
            onScaleDefined = onScaleDefined,
            onDismiss = {
                showScaleDialog = false
                firstPoint = null
                secondPoint = null
                scaleInput = ""
            },
            onAccepted = {
                showScaleDialog = false
                firstPoint = null
                secondPoint = null
                scaleInput = ""
            }
        )
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false; editingNote = null },
            title = { Text(stringResource(if (editingNote == null) R.string.add_note else R.string.edit_note)) },
            text = {
                Column {
                    OutlinedTextField(value = noteInput, onValueChange = { noteInput = it }, label = { Text(stringResource(R.string.annotation_note_text_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = noteIsBold, onCheckedChange = { noteIsBold = it })
                        Text(stringResource(R.string.annotation_bold))
                    }
                    Text(stringResource(R.string.annotation_note_gesture_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            },
             confirmButton = {
                 Button(onClick = {
                     var outcome = AnnotationReducer.Result.Rejected
                     if (editingNote == null) {
                          val newNote = Note(
                              x = notePos.x,
                              y = notePos.y,
                              text = noteInput,
                              isBold = noteIsBold,
                              fontSizeRatio = 0.02f
                          )
                         outcome = annotationReducer.addPdfNote(pageIndex, newNote)
                         if (outcome == AnnotationReducer.Result.Accepted) {
                             onAnnotationAdded()
                         }
                     } else {
                         val old = editingNote!!.copyNote()
                         val replacement = editingNote!!.copy(
                             text = noteInput,
                             isBold = noteIsBold
                         )
                         outcome = annotationReducer.updatePdfNoteAt(pageIndex, selectedNoteIdx, replacement, before = old)
                         if (outcome == AnnotationReducer.Result.Accepted) {
                             selectedNote = replacement
                             selectedItem = PageItem.NoteItem(replacement, selectedNoteIdx)
                         }
                     }
                     // Rejected input stays editable; valid unchanged saves close.
                     // Only accepted mutations record history and publish effects.
                     if (outcome != AnnotationReducer.Result.Rejected) {
                         showNoteDialog = false
                         editingNote = null
                     }
                 }) { Text(stringResource(R.string.save)) }
            }
        )
    }
    
    // Image note dialog  
    if (showImageNoteDialog) {
        AlertDialog(
            onDismissRequest = { showImageNoteDialog = false; editingImageNote = null },
            title = { Text(stringResource(if (editingImageNote == null) R.string.image_note_add_title else R.string.image_note_edit_title)) },
            text = {
                Column {
                    OutlinedTextField(value = imageNoteInput, onValueChange = { imageNoteInput = it }, label = { Text(stringResource(R.string.annotation_note_text_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = imageNoteIsBold, onCheckedChange = { imageNoteIsBold = it })
                        Text(stringResource(R.string.annotation_bold))
                    }
                }
            },
             confirmButton = {
                 Button(onClick = {
                     var outcome = AnnotationReducer.Result.Rejected
                     if (editingImageNote == null && currentImageFileName != null && selectedPhotoPin != null) {
                        // Use fixed percentage of image height for device independence
                        // 2% of image height is a readable default font size
                        val fontSizeRatio = 0.02f
                        
                         val newImageNote = PhotoImageNote(
                            x = imageNotePos.x,
                            y = imageNotePos.y,
                            text = imageNoteInput,
                             isBold = imageNoteIsBold,
                            rotation = 0f,
                             fontSizeRatio = fontSizeRatio
                         )
                         outcome = annotationReducer.addImageNote(pageIndex, selectedPhotoPin!!.id, currentImageFileName!!, newImageNote)
                         if (outcome == AnnotationReducer.Result.Accepted) {
                             SafeDiagnostics.debug(DiagnosticEvent.ANNOTATION_ACTIVITY)
                         }
                     } else if (editingImageNote != null) {
                         val old = editingImageNote!!.copyImageNote()
                         val replacement = editingImageNote!!.copy(
                             text = imageNoteInput,
                             isBold = imageNoteIsBold
                         )
                          if (selectedPhotoPin != null && currentImageFileName != null) {
                              outcome = annotationReducer.updateImageNote(
                                  pageIndex, selectedPhotoPin!!.id, currentImageFileName!!, old, replacement
                              )
                              if (outcome != AnnotationReducer.Result.Rejected) selectedImageNote = replacement
                          }
                     }
                     if (outcome != AnnotationReducer.Result.Rejected) {
                         showImageNoteDialog = false
                         editingImageNote = null
                     }
                 }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showImageNoteDialog = false; editingImageNote = null }) { Text(stringResource(R.string.clear_page_cancel)) } }
        )
    }
    
    // Shape selection dialog
    var shapePos by remember { mutableStateOf(Point(0f, 0f)) }
    if (showShapeDialog) {
        AlertDialog(
            onDismissRequest = { showShapeDialog = false },
            title = { Text(stringResource(R.string.shape_select_title)) },
            text = {
                Column {
                    ShapeType.entries.forEach { shapeType ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentShapeType = shapeType
                                    // Create the shape at tap position using ratio-based sizing
                                    // Default size: 10% of page width, 8% of page height
                                    val defaultWidthRatio = 0.10f
                                    val defaultHeightRatio = 0.08f
                                    val newShape = Shape(
                                        x = shapePos.x,
                                        y = shapePos.y,
                                                     rotation = 0f,
                                                     type = shapeType,
                                                     colorArgb = Color.Red.toArgb(),
                                                     isFilled = false,
                                        strokeWidthRatio = 0.003f,  // 0.3% of page max dimension
                                        widthRatio = defaultWidthRatio,
                                        heightRatio = defaultHeightRatio
                                    )
                                     if (annotationReducer.addPdfShape(pageIndex, newShape).changed) {
                                         onAnnotationAdded()
                                     }
                                    showShapeDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = when (shapeType) {
                                ShapeType.RECTANGLE -> Icons.Default.CropSquare
                                ShapeType.CIRCLE -> Icons.Default.Circle
                                ShapeType.ARROW -> Icons.Default.ArrowForward
                                ShapeType.CLOUD -> Icons.Default.Cloud
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = when (shapeType) {
                                    ShapeType.RECTANGLE -> stringResource(R.string.shape_rectangle)
                                    ShapeType.CIRCLE -> stringResource(R.string.shape_circle)
                                    ShapeType.ARROW -> stringResource(R.string.shape_arrow)
                                    ShapeType.CLOUD -> stringResource(R.string.shape_cloud)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = when (shapeType) {
                                    ShapeType.RECTANGLE -> stringResource(R.string.shape_rectangle)
                                    ShapeType.CIRCLE -> stringResource(R.string.shape_circle)
                                    ShapeType.ARROW -> stringResource(R.string.shape_arrow)
                                    ShapeType.CLOUD -> stringResource(R.string.shape_cloud)
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showShapeDialog = false }) { Text(stringResource(R.string.clear_page_cancel)) } }
        )
    }
    
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(stringResource(R.string.annotation_delete_title)) },
            text = { Text(stringResource(R.string.annotation_delete_message)) },
            confirmButton = { Button(onClick = { 
                onDeleteItem(itemToDelete!!)
                if (itemToDelete is PageItem.Measure && (itemToDelete as PageItem.Measure).data == selectedMeasurement) {
                    selectedMeasurement = null
                    selectedMeasurementIndex = -1
                    measurementDraft = null
                }
                if (itemToDelete is PageItem.NoteItem && (itemToDelete as PageItem.NoteItem).data == selectedNote) selectedNote = null
                if (itemToDelete is PageItem.PhotoPinItem && (itemToDelete as PageItem.PhotoPinItem).data == selectedPhotoPin) selectedPhotoPin = null
                if (itemToDelete is PageItem.ShapeItem && (itemToDelete as PageItem.ShapeItem).data == selectedShape) selectedShape = null
                itemToDelete = null
                selectedItem = null
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text(stringResource(R.string.annotation_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { itemToDelete = null }) { Text(stringResource(R.string.clear_page_cancel)) } }
        )
    }
    
    // Photo pin image gallery dialog
    var fullScreenImageFile by rememberSaveable(sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf<String?>(null) }
    val galleryBitmapCache = remember(
        sessionToken?.sourceCacheKey,
        sessionToken?.generation,
        pageIndex,
        selectedPhotoPin?.id,
        showPinImageGallery
    ) { Stage7BitmapCache() }

    DisposableEffect(galleryBitmapCache) {
        onDispose { galleryBitmapCache.close() }
    }

    // Selection, gallery, and in-progress gesture state is document-scoped UI
    // state. Reset it whenever the session or page changes so A's selected
    // photo/note cannot be applied to B after a transactional switch.
    LaunchedEffect(sessionToken, pageIndex) {
        currentStroke.clear()
        firstPoint = null
        secondPoint = null
        showScaleDialog = false
        scaleInput = ""
        selectedItem = null
        itemToDelete = null
        overlappingItems = emptyList()
        showItemPicker = false
        selectedMeasurement = null
        selectedMeasurementIndex = -1
        measurementDraft = null
        draggingPointIdx = -1
        originalMeasurement = null
        calibratePointIdx = -1
        selectedNote = null
        selectedNoteIdx = -1
        showNoteDialog = false
        noteInput = ""
        noteIsBold = false
        editingNote = null
        draggingNoteIdx = -1
        isItemDragging = false
        originalNote = null
        noteDraft = null
        selectedPhotoPin = null
        selectedShape = null
        showShapeDialog = false
        draggingShape = false
        originalShape = null
        shapeDraft = null
        resizingShape = false
        rotatingShape = false
        shapeInitialPinchDistance = 0f
        editingImageNote = null
        selectedImageNote = null
        selectedImageShape = null
        draggingImageNote = null
        imageNoteDraft = null
        originalImageNote = null
        draggingImageShape = false
        imageShapeDraft = null
        originalImageShape = null
        showImageNoteDialog = false
        showPinImageGallery = false
        fullScreenImageFile = null
    }
    
    // Notify parent when fullscreen mode changes
    LaunchedEffect(fullScreenImageFile) {
        onFullScreenModeChanged(fullScreenImageFile != null)
    }
    
    if (showPinImageGallery && selectedPhotoPin != null) {
        AlertDialog(
            onDismissRequest = { showPinImageGallery = false },
            title = { Text(stringResource(R.string.photo_gallery_title, selectedPhotoPin!!.imageFileNames.size)) },
            text = {
                if (selectedPhotoPin!!.imageFileNames.isEmpty()) {
                    Text(stringResource(R.string.photo_gallery_empty))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedPhotoPin!!.imageFileNames.size) { idx ->
                            val fileName = selectedPhotoPin!!.imageFileNames[idx]
                            val pinId = selectedPhotoPin?.id
                            val galleryCacheKey = Stage7CacheKey(
                                sessionToken?.sourceCacheKey ?: uri.toString(),
                                "$pageIndex|${pinId.orEmpty()}|$fileName"
                            )
                            BoxWithConstraints(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                val galleryViewport = BitmapBudgetPolicy.displayViewport(
                                    widthPx = constraints.maxWidth,
                                    heightPx = constraints.maxHeight
                                )
                                if (galleryViewport != null) {
                                    LaunchedEffect(
                                        sessionToken,
                                        pageIndex,
                                        pinId,
                                        showPinImageGallery,
                                        fileName,
                                        galleryViewport.width,
                                        galleryViewport.height
                                    ) {
                                        if (!showPinImageGallery || sessionToken == null || pinId == null) return@LaunchedEffect
                                        stage7Worker.computeAndPublish(
                                            compute = {
                                                loadPhotoBitmapBlocking(
                                                    context,
                                                    sessionToken,
                                                    fileName,
                                                    viewportWidthPx = galleryViewport.width,
                                                    viewportHeightPx = galleryViewport.height
                                                )
                                            },
                                            acceptsBeforeMain = { isSessionCurrent(sessionToken) },
                                            acceptsOnMain = {
                                                showPinImageGallery &&
                                                    selectedPhotoPin?.id == pinId &&
                                                    isSessionCurrent(sessionToken) &&
                                                    isPageCurrent(sessionToken, pageIndex)
                                            },
                                            publish = { loadedOwner ->
                                                val admission = galleryBitmapCache.putOwned(galleryCacheKey, loadedOwner)
                                                check(admission.accepted) {
                                                    "gallery bitmap cache admission rejected: $admission"
                                                }
                                            },
                                            reject = { rejectedOwner -> rejectedOwner.close() }
                                        )
                                    }
                                }
                                val loadedBitmap = galleryBitmapCache.entries[galleryCacheKey]
                                val displayLease = remember(galleryCacheKey, loadedBitmap) {
                                    loadedBitmap?.let { galleryBitmapCache.acquire(galleryCacheKey) }
                                }
                                DisposableEffect(displayLease) {
                                    onDispose { displayLease?.close() }
                                }
                                if (displayLease != null) {
                                    Image(
                                        bitmap = displayLease.value.asImageBitmap(),
                                        contentDescription = stringResource(R.string.photo_index, idx),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable {
                                                fullScreenImageFile = fileName
                                                showPinImageGallery = false
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPinImageGallery = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val renderViewport = BitmapBudgetPolicy.displayViewport(
            widthPx = constraints.maxWidth,
            heightPx = constraints.maxHeight
        )
        if (renderViewport != null) {
        LaunchedEffect(
            uri,
            sessionToken,
            pageIndex,
            renderViewport.width,
            renderViewport.height
        ) {
            val loadPage: suspend () -> Unit = {
                stage7Worker.computeAndPublish(
                    compute = {
                        currentCoroutineContext().ensureActive()
                        val owner = Stage7ResourceOwner<Bitmap>(::recycleBitmap)
                        try {
                            val rendered = PdfBitmapRenderer(context).use {
                                it.renderPageBitmap(
                                    uri = uri,
                                    pageIndex = pageIndex,
                                    scaleFactor = 1,
                                    onBitmapCreated = owner::own,
                                    viewportWidthPx = renderViewport.width,
                                    viewportHeightPx = renderViewport.height
                                )
                            }
                            if (rendered == null) {
                                owner.close()
                                null
                            } else {
                                currentCoroutineContext().ensureActive()
                                val loaded = owner.owned(rendered)
                                SafeDiagnostics.debug(DiagnosticEvent.RENDER_ACTIVITY)
                                loaded
                            }
                        } catch (error: Throwable) {
                            owner.close()
                            throw error
                        }
                    },
                    acceptsBeforeMain = { isSessionCurrent(sessionToken) },
                    acceptsOnMain = {
                        isSessionCurrent(sessionToken) && isPageCurrent(sessionToken, pageIndex)
                    },
                    publish = { nextOwner ->
                        val previousOwner = bitmapOwner
                        bitmapOwner = nextOwner
                        if (previousOwner !== nextOwner) previousOwner?.close()
                        onPageRendered()
                    },
                    reject = { rejectedOwner -> rejectedOwner.close() }
                )
            }

            var documentJob: Job? = null
            try {
                if (sessionToken != null && launchDocumentWork != null) {
                    documentJob = launchDocumentWork(sessionToken, loadPage)
                    documentJob?.join()
                } else {
                    loadPage()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeDiagnostics.error(DiagnosticEvent.RENDER_ACTIVITY, error = error)
            } finally {
                documentJob?.let { job ->
                    if (job.isActive) {
                        withContext(NonCancellable) {
                            job.cancel()
                            job.join()
                        }
                    }
                }
            }
        }
        }
        bitmapOwner?.value?.let { b ->
            val bW = b.width.toFloat(); val bH = b.height.toFloat()
            val bitmapAspectRatio = bW / bH
            val screenAspectRatio = w / h
            val (vW, vH) = if (bitmapAspectRatio > screenAspectRatio) w to (w / bitmapAspectRatio) else (h * bitmapAspectRatio) to h
            
            Box(
                modifier = Modifier.fillMaxSize()
                    .testTag(PDF_READY_CANVAS_TAG)
                    .pointerInput(sessionToken, pageIndex, mode, w, h) {
                        awaitEachGesture {
                            try {
                            fun screenToPage(ptX: Float, ptY: Float): Point {
                                val baseScale = if (bW > 0f) (vW / bW) else 1f
                                val compositeScale = baseScale * scale
                                val imgW = bW * compositeScale
                                val imgH = bH * compositeScale
                                val imgLeft = w / 2 + offsetX - imgW / 2
                                val imgTop = h / 2 + offsetY - imgH / 2
                                // Persist normalized coordinates relative to the
                                // visible (crop/rotation-resolved) bitmap. View
                                // zoom, pan, density, and sampling never enter
                                // the committed annotation value.
                                val x = ((ptX - imgLeft) / compositeScale / bW).coerceIn(0f, 1f)
                                val y = ((ptY - imgTop) / compositeScale / bH).coerceIn(0f, 1f)
                                return Point(x, y)
                            }
                            fun sourceDistance(p1: Point, p2: Point): Float? {
                                val source = sourcePageSize ?: return null
                                val dx = ((p1.x - p2.x) * source.width).toDouble()
                                val dy = ((p1.y - p2.y) * source.height).toDouble()
                                return sqrt(dx * dx + dy * dy)
                                    .takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
                                    ?.toFloat()
                            }
                            fun pageToScreen(pt: Point): Offset {
                                val baseScale = if (bW > 0f) (vW / bW) else 1f
                                val compositeScale = baseScale * scale
                                val imgW = bW * compositeScale
                                val imgH = bH * compositeScale
                                val imgLeft = w / 2 + offsetX - imgW / 2
                                val imgTop = h / 2 + offsetY - imgH / 2
                                return Offset(imgLeft + pt.x * bW * compositeScale, imgTop + pt.y * bH * compositeScale)
                            }
                            fun positionCopyAffordance(pageOcr: PageOcr, endIndex: Int) {
                                if (endIndex !in pageOcr.boxes.indices) return
                                PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                    pageOcr.boxes[endIndex].rectN,
                                    bW,
                                    bH
                                )?.let { bitmapRect ->
                                     val screenPos = pageToScreen(Point(bitmapRect.right / bW, bitmapRect.bottom / bH))
                                    copyButtonPos = Offset(screenPos.x + 10f, screenPos.y + 10f)
                                }
                            }
                            val down = awaitFirstDown()
                            // Use the pointer event clock so long-press
                            // admission follows actual gesture time even
                            // when input events are delivered in a batch.
                            val startTime = down.uptimeMillis
                            val pointerBaseScale = if (bW > 0f) (vW / bW) else 1f
                            val pointerCompositeScale = pointerBaseScale * scale
                            var dragActive = false
                            var totalPan = Offset.Zero
                            var longPressTriggered = false
                            var textSelectingActive = false
                             var ocrSelectionPending = false
                             var noteGestureActive = false
                             var gestureCancelled = false
                            
                            val startPt = screenToPage(down.position.x, down.position.y)
                            
                            // Helper to find OcrBox at a page position
                            fun findOcrBoxAtPosition(pagePt: Point, pageOcr: PageOcr?): Int {
                                if (pageOcr == null) return -1
                                for ((idx, box) in pageOcr.boxes.withIndex()) {
                                    val rect = box.rectN
                                    if (pagePt.x >= rect.left && pagePt.x <= rect.right &&
                                        pagePt.y >= rect.top && pagePt.y <= rect.bottom) {
                                        return idx
                                    }
                                }
                                return -1
                            }
                            val handleThreshold = 80f / scale  // Larger hitbox for easier grabbing
                            val normalizedHandleThreshold = handleThreshold / maxOf(bW, bH)

                            if (mode == ToolMode.PAN) {
                                // Check if tapping on text selection handles first
                                if (isTextSelecting && selectedOcrBoxes.isNotEmpty() && cachedPageOcr != null) {
                                    val startBoxIdx = minOf(textSelectionStartIdx, textSelectionEndIdx)
                                    val endBoxIdx = maxOf(textSelectionStartIdx, textSelectionEndIdx)
                                    if (startBoxIdx >= 0 && endBoxIdx < cachedPageOcr!!.boxes.size) {
                                        val startBox = cachedPageOcr!!.boxes[startBoxIdx]
                                        val endBox = cachedPageOcr!!.boxes[endBoxIdx]
                                        val startRect = PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                            startBox.rectN,
                                            bW,
                                            bH
                                        )
                                        val endRect = PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                            endBox.rectN,
                                            bW,
                                            bH
                                        )
                                        if (startRect == null || endRect == null) {
                                            return@awaitEachGesture
                                        }
                                        
                                        // Start handle position (left edge, bottom of first box)
                                        val startHandleX = startRect.left
                                        val startHandleY = startRect.bottom
                                        
                                        // End handle position (right edge, bottom of last box)
                                        val endHandleX = endRect.right
                                        val endHandleY = endRect.bottom
                                        
                                        // Scale hit radius based on text height, with generous minimum for usability
                                        val startBoxHeight = startRect.bottom - startRect.top
                                        val endBoxHeight = endRect.bottom - endRect.top
                                        val startHandleHitRadius = (startBoxHeight * 2.5f).coerceIn(50f / scale, 120f / scale)
                                        val endHandleHitRadius = (endBoxHeight * 2.5f).coerceIn(50f / scale, 120f / scale)
                                        
                                        if (dist(Point(startPt.x * bW, startPt.y * bH), Point(startHandleX, startHandleY)) < startHandleHitRadius) {
                                            draggingSelectionHandle = "start"
                                            isItemDragging = true
                                            SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                        } else if (dist(Point(startPt.x * bW, startPt.y * bH), Point(endHandleX, endHandleY)) < endHandleHitRadius) {
                                            draggingSelectionHandle = "end"
                                            isItemDragging = true
                                            SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                        }
                                    }
                                }
                                
                                if (selectedMeasurement != null) {
                                         if (dist(Point(startPt.x * bW, startPt.y * bH), Point(selectedMeasurement!!.p1.x * bW, selectedMeasurement!!.p1.y * bH)) < handleThreshold) {
                                        draggingPointIdx = 0
                                        isItemDragging = true
                                        originalMeasurement = selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())
                                     } else if (dist(Point(startPt.x * bW, startPt.y * bH), Point(selectedMeasurement!!.p2.x * bW, selectedMeasurement!!.p2.y * bH)) < handleThreshold) {
                                        draggingPointIdx = 1
                                        isItemDragging = true
                                        originalMeasurement = selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())
                                    }
                                }
                                if (draggingPointIdx == -1 && selectedNote != null) {
                                    if (AnnotationCanvasRendering.containsNote(selectedNote!!,
                                            startPt.x * bW, startPt.y * bH, bW, bH, 4f / pointerCompositeScale)) {
                                        draggingNoteIdx = selectedNoteIdx.takeIf { it >= 0 }
                                            ?: notes.indexOfFirst { it.id == selectedNote?.id }
                                        isItemDragging = true
                                        noteGestureActive = draggingNoteIdx >= 0
                                        originalNote = selectedNote!!.copyNote()
                                        noteDraft = selectedNote!!.copyNote()
                                    }
                                }
                                // Check for shape dragging
                                if (draggingPointIdx == -1 && draggingNoteIdx == -1 && selectedShape != null) {
                                    val s = selectedShape!!
                                    if (AnnotationCanvasRendering.containsShape(s, startPt.x * bW, startPt.y * bH,
                                            bW, bH, handleThreshold)) {
                                        draggingShape = true
                                        isItemDragging = true
                                        originalShape = s.copy()
                                        shapeDraft = s.copyShape()
                                    }
                                }
                            } else if (mode == ToolMode.SCALE && firstPoint != null && secondPoint != null) {
                                if (dist(startPt, firstPoint!!) < normalizedHandleThreshold) calibratePointIdx = 0
                                else if (dist(startPt, secondPoint!!) < normalizedHandleThreshold) calibratePointIdx = 1
                            }

                             do {
                                 val event = awaitPointerEvent()
                                 val pointers = event.changes
                                 // Compose adapts ACTION_CANCEL in
                                 // SuspendingPointerInputModifierNodeImpl by dispatching
                                 // consumed all-up changes.  The shared predicate must run
                                 // before this handler consumes any incoming change.
                                 if (event.isIncomingCancellation()) {
                                     gestureCancelled = true
                                     break
                                 }
                                 val centroid = event.calculateCentroid()
                                
                                if (draggingSelectionHandle != null && cachedPageOcr != null) {
                                    // Dragging text selection handle
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    val newBoxIdx = findOcrBoxAtPosition(currentPt, cachedPageOcr)
                                    
                                    if (newBoxIdx != -1) {
                                        if (draggingSelectionHandle == "start") {
                                            textSelectionStartIdx = newBoxIdx
                                        } else {
                                            textSelectionEndIdx = newBoxIdx
                                        }
                                        // Update selected boxes
                                        val startIdx = minOf(textSelectionStartIdx, textSelectionEndIdx)
                                        val endIdx = maxOf(textSelectionStartIdx, textSelectionEndIdx)
                                        if (startIdx >= 0 && endIdx < cachedPageOcr!!.boxes.size) {
                                            selectedOcrBoxes = cachedPageOcr!!.boxes.subList(startIdx, endIdx + 1)
                                        }
                                    }
                                    change.consume()
                                    dragActive = true
                                } else if (draggingPointIdx != -1) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    val mIdx = selectedMeasurementIndex.takeIf { it in measurements.indices }
                                        ?: measurements.indexOfFirst { it === selectedMeasurement }
                                    if (mIdx >= 0) {
                                        val baseMeasurement = measurementDraft ?: measurements[mIdx]
                                         val draftM = baseMeasurement.copyMeasurement(
                                            p1 = if (draggingPointIdx == 0) currentPt.copyPoint() else baseMeasurement.p1.copyPoint(),
                                            p2 = if (draggingPointIdx == 1) currentPt.copyPoint() else baseMeasurement.p2.copyPoint()
                                        )
                                         val updatedM = if (currentScale != null) {
                                             sourceDistance(draftM.p1, draftM.p2)?.let { distance ->
                                                 draftM.copyMeasurement(text = formatFeet(distance / currentScale.pointsPerFoot))
                                             } ?: draftM
                                         } else draftM
                                        selectedMeasurement = updatedM
                                        measurementDraft = updatedM
                                    }
                                    change.consume()
                                    dragActive = true
                                } else if (draggingNoteIdx != -1 || noteGestureActive) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                     val updatedN = (noteDraft ?: notes[draggingNoteIdx].copyNote()).copy(
                                         x = currentPt.x,
                                         y = currentPt.y
                                     )
                                    noteDraft = updatedN
                                    selectedNote = updatedN
                                    selectedNoteIdx = draggingNoteIdx
                                    change.consume()
                                    dragActive = true
                                } else if (calibratePointIdx != -1) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    if (calibratePointIdx == 0) firstPoint = currentPt else secondPoint = currentPt
                                    change.consume()
                                    dragActive = true
                                } else if (draggingShape && selectedShape != null && pointers.size < 2) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    val idx = shapes.indexOfFirst { it.id == selectedShape!!.id }
                                    if (idx != -1) {
                                        val updated = (shapeDraft ?: shapes[idx].copyShape()).copy(x = currentPt.x, y = currentPt.y)
                                        shapeDraft = updated
                                        selectedShape = updated
                                    }
                                    change.consume()
                                    dragActive = true
                            } else if (pointers.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    val rotation = event.calculateRotation()
                                    // Check if resizing/rotating selected shape
                                    if (selectedShape != null) {
                                        val idx = shapes.indexOfFirst { it.id == selectedShape!!.id }
                                        if (idx != -1) {
                                            // Resize using ratios (0.01 to 1.0 = 1% to 100% of page)
                                            val shape = shapeDraft ?: shapes[idx]
                                            val resolvedSize = AnnotationGeometry.resolvePageSize(
                                                bW, bH, shape.widthRatio, shape.heightRatio
                                            )
                                            val widthRatio = (resolvedSize.width / bW).coerceIn(0.01f, 1f)
                                            val heightRatio = (resolvedSize.height / bH).coerceIn(0.01f, 1f)
                                            val newWidthRatio = (widthRatio * zoom).coerceIn(0.01f, 1f)
                                            val newHeightRatio = (heightRatio * zoom).coerceIn(0.01f, 1f)
                                            val newRotation = shape.rotation + rotation
                                            val updated = shape.copy(widthRatio = newWidthRatio, heightRatio = newHeightRatio, rotation = newRotation)
                                            shapeDraft = updated
                                            selectedShape = updated
                                            resizingShape = true
                                            if (kotlin.math.abs(rotation) > 0.01f) rotatingShape = true
                                        }
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else if (selectedNoteIdx != -1) {
                                        if (draggingNoteIdx < 0 && selectedNoteIdx in notes.indices) {
                                            draggingNoteIdx = selectedNoteIdx
                                            originalNote = notes[selectedNoteIdx].copyNote()
                                            noteDraft = notes[selectedNoteIdx].copyNote()
                                        }
                                        noteGestureActive = draggingNoteIdx >= 0
                                         val baseNote = noteDraft ?: notes[selectedNoteIdx]
                                         val cur = baseNote.copy(
                                             fontSizeRatio = (baseNote.fontSizeRatio * zoom).coerceIn(0.005f, 0.25f),
                                             rotation = baseNote.rotation + rotation
                                         )
                                        noteDraft = cur
                                        selectedNote = cur
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else if (selectedNote != null) {
                                        val idx = notes.indexOfFirst { it.id == selectedNote?.id }
                                        if (idx != -1) {
                                            if (draggingNoteIdx < 0) {
                                                draggingNoteIdx = idx
                                                originalNote = notes[idx].copyNote()
                                            }
                                            noteGestureActive = true
                                             val baseNote = noteDraft ?: notes[idx]
                                             val cur = baseNote.copy(
                                                 fontSizeRatio = (baseNote.fontSizeRatio * zoom).coerceIn(0.005f, 0.25f),
                                                 rotation = baseNote.rotation + rotation
                                             )
                                            noteDraft = cur
                                            selectedNote = cur
                                        }
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else {
                                        val pan = event.calculatePan()
                                        totalPan += pan
                                        if (centroid != Offset.Unspecified) {
                                            val oldScale = scale
                                            val newScale = (scale * zoom).coerceIn(1f, 15f)
                                            val relCentroidX = centroid.x - w / 2
                                            val relCentroidY = centroid.y - h / 2
                                            offsetX = (offsetX - relCentroidX) * (newScale / oldScale) + relCentroidX + pan.x
                                            offsetY = (offsetY - relCentroidY) * (newScale / oldScale) + relCentroidY + pan.y
                                            scale = newScale
                                            val limitX = (vW * scale) / 2
                                            val limitY = (vH * scale) / 2
                                            offsetX = offsetX.coerceIn(-limitX, limitX)
                                            offsetY = offsetY.coerceIn(-limitY, limitY)
                                        }
                                        pointers.forEach { it.consume() }
                                    }
                                } else if (pointers.size == 1 && (mode == ToolMode.PAN || (mode == ToolMode.SCALE && firstPoint != null && secondPoint != null))) {
                                    val change = pointers[0]
                                    val currentPos = change.position
                                    val elapsed = event.changes.firstOrNull()?.uptimeMillis?.minus(startTime) ?: 0L
                                    
                                    // Debug logging
                                    if (mode == ToolMode.PAN && !longPressTriggered && !isItemDragging) {
                                        SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                    }
                                    
                                    // Check for long press to start text selection (400ms hold without much movement)
                                    if (!longPressTriggered && mode == ToolMode.PAN && elapsed > 400 && totalPan.getDistance() < 15f && !isItemDragging) {
                                        SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                        // Try to load OCR data and find text at this position
                                        val cacheNamespace = sessionToken?.sourceCacheKey ?: uri.toString()
                                        val pageOcr = cachedPageOcr ?: if (sessionToken != null) {
                                            val pageWorkToken = DocumentWorkToken(
                                                sessionToken,
                                                pageIndex = pageIndex
                                            )
                                            pdfSearchEngine.getCachedPageOcr(
                                                token = sessionToken,
                                                pageIndex = pageIndex,
                                                cacheNamespace = cacheNamespace,
                                                isAccepted = { candidate ->
                                                    candidate == pageWorkToken && isPageCurrent(sessionToken, pageIndex)
                                                }
                                            )
                                        } else {
                                            null
                                        }
                                        SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                        val selectionToken = sessionToken
                                        val selectionPage = pageIndex
                                        val stillCurrent = selectionToken == sessionToken &&
                                            (selectionToken == null || isPageCurrent(selectionToken, selectionPage))
                                        if (pageOcr != null && stillCurrent) {
                                            cachedPageOcr = pageOcr
                                            val boxIdx = findOcrBoxAtPosition(startPt, pageOcr)
                                            val admittedBoxIdx = OcrSelection.admitLoadedSelection(
                                                selectionToken,
                                                sessionToken,
                                                selectionPage,
                                                pageIndex,
                                                boxIdx,
                                                pageOcr.boxes.size
                                            )?.startIndex
                                            SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                            if (admittedBoxIdx != null) {
                                                longPressTriggered = true
                                                textSelectingActive = true
                                                isTextSelecting = true
                                                textSelectionStartIdx = admittedBoxIdx
                                                textSelectionEndIdx = admittedBoxIdx
                                                selectedOcrBoxes = listOf(pageOcr.boxes[admittedBoxIdx])
                                                showCopyButton = false
                                                SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                            } else {
                                                longPressTriggered = true // Don't keep checking
                                                SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                            }
                                        } else {
                                            // OCR not cached, trigger loading in background
                                            longPressTriggered = true // prevent re-triggering
                                            // Keep this gesture in the text-selection lane while OCR is
                                            // loading. Without this admission marker the same pointer
                                            // event falls through to PAN and the later result has no
                                            // production selection/Copy affordance to complete.
                                            ocrSelectionPending = true
                                            SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                            val selectionPoint = startPt.copyPoint()
                                            val loadOcr: suspend () -> Unit = {
                                                try {
                                                    val loaded = if (selectionToken != null) {
                                                        val pageWorkToken = DocumentWorkToken(
                                                            selectionToken,
                                                            pageIndex = selectionPage
                                                        )
                                                        pdfSearchEngine.getOrBuildPageOcr(
                                                            token = selectionToken,
                                                            pageIndex = selectionPage,
                                                            cacheNamespace = cacheNamespace,
                                                            owner = ocrOwner,
                                                            isAccepted = { candidate ->
                                                                candidate == pageWorkToken && isPageCurrent(selectionToken, selectionPage)
                                                            }
                                                        )
                                                    } else {
                                                        null
                                                    }
                                                    val stillCurrent = selectionToken == sessionToken &&
                                                        (selectionToken == null || isPageCurrent(selectionToken, selectionPage))
                                                    if (stillCurrent && loaded != null) {
                                                        cachedPageOcr = loaded
                                                        val boxIdx = findOcrBoxAtPosition(selectionPoint, loaded)
                                                        val admittedBoxIdx = OcrSelection.admitLoadedSelection(
                                                            selectionToken, sessionToken,
                                                            selectionPage, pageIndex,
                                                            boxIdx, loaded.boxes.size
                                                        )?.startIndex
                                                        if (admittedBoxIdx != null) {
                                                            textSelectingActive = true
                                                            ocrSelectionPending = false
                                                            isTextSelecting = true
                                                            textSelectionStartIdx = admittedBoxIdx
                                                            textSelectionEndIdx = admittedBoxIdx
                                                            selectedOcrBoxes = listOf(loaded.boxes[admittedBoxIdx])
                                                            positionCopyAffordance(loaded, admittedBoxIdx)
                                                            showCopyButton = true
                                                            SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                                        }
                                                        SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                                    }
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (e: Exception) {
                                                    SafeDiagnostics.error(DiagnosticEvent.OCR_ACTIVITY, error = e)
                                                }
                                            }
                                            if (sessionToken != null && launchDocumentWork != null) {
                                                launchDocumentWork(sessionToken, loadOcr)
                                            } else {
                                                coroutineScopeForOcr.launch { loadOcr() }
                                            }
                                        }
                                    }
                                    
                                    // If text selecting, extend selection based on current drag position
                                    if (textSelectingActive && cachedPageOcr != null) {
                                        val currentPt = screenToPage(currentPos.x, currentPos.y)
                                        val currentBoxIdx = findOcrBoxAtPosition(currentPt, cachedPageOcr)
                                        if (currentBoxIdx != -1 && currentBoxIdx != textSelectionEndIdx) {
                                            textSelectionEndIdx = currentBoxIdx
                                            val startIdx = minOf(textSelectionStartIdx, textSelectionEndIdx)
                                            val endIdx = maxOf(textSelectionStartIdx, textSelectionEndIdx)
                                            selectedOcrBoxes = cachedPageOcr!!.boxes.subList(startIdx, endIdx + 1)
                                        }
                                        change.consume()
                                        dragActive = true
                                    } else if (!textSelectingActive && !ocrSelectionPending) {
                                        val pan = event.calculatePan()
                                        totalPan += pan
                                        if (centroid != Offset.Unspecified) {
                                            offsetX = (offsetX + pan.x).coerceIn(-(vW * scale) / 2, (vW * scale) / 2)
                                            offsetY = (offsetY + pan.y).coerceIn(-(vH * scale) / 2, (vH * scale) / 2)
                                        }
                                        pointers.forEach { it.consume() }
                                        if (totalPan.getDistance() > 10f) dragActive = true
                                    } else if (ocrSelectionPending) {
                                        // OCR completion owns this gesture; do not pan while the
                                        // captured touch is awaiting the bounded selection result.
                                        change.consume()
                                        dragActive = true
                                    }
                                } else if (pointers.size == 1) {
                                    val change = pointers[0]
                                    if (mode == ToolMode.PEN || mode == ToolMode.HIGHLIGHTER) {
                                        dragActive = true
                                        currentStroke.add(screenToPage(change.position.x, change.position.y))
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            
                                if (!gestureCancelled) {
                                // Handle selection handle release
                                if (draggingSelectionHandle != null) {
                                    draggingSelectionHandle = null
                                    isItemDragging = false
                                    // Update copy button position
                                    if (cachedPageOcr != null && selectedOcrBoxes.isNotEmpty()) {
                                        val endIdx = maxOf(textSelectionStartIdx, textSelectionEndIdx)
                                        if (endIdx >= 0 && endIdx < cachedPageOcr!!.boxes.size) {
                                            val lastBox = cachedPageOcr!!.boxes[endIdx]
                                            PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                                lastBox.rectN,
                                                bW,
                                                bH
                                            )?.let { bitmapRect ->
                                             val screenPos = pageToScreen(Point(bitmapRect.right / bW, bitmapRect.bottom / bH))
                                                copyButtonPos = Offset(screenPos.x + 10f, screenPos.y + 10f)
                                            }
                                        }
                                    }
                                    SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                } else if (textSelectingActive && selectedOcrBoxes.isNotEmpty()) {
                                    // Handle text selection release - show copy button
                                    isTextSelecting = true
                                    showCopyButton = true
                                    // Position copy button near the end of selection
                                        if (cachedPageOcr != null && textSelectionEndIdx >= 0 && textSelectionEndIdx < cachedPageOcr!!.boxes.size) {
                                            val lastBox = cachedPageOcr!!.boxes[textSelectionEndIdx]
                                            PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                                lastBox.rectN,
                                                bW,
                                                bH
                                            )?.let { bitmapRect ->
                                                 val screenPos = pageToScreen(Point(bitmapRect.right / bW, bitmapRect.bottom / bH))
                                                copyButtonPos = Offset(screenPos.x + 10f, screenPos.y + 10f)
                                            }
                                        }
                                    SafeDiagnostics.debug(DiagnosticEvent.OCR_ACTIVITY)
                                } else if (draggingPointIdx != -1) {
                                    if (originalMeasurement != null && selectedMeasurement != null) {
                                         annotationReducer.updateMeasurementAt(
                                                 pageIndex, selectedMeasurementIndex,
                                                selectedMeasurement!!.copyMeasurement(
                                                    p1 = selectedMeasurement!!.p1.copyPoint(),
                                                    p2 = selectedMeasurement!!.p2.copyPoint()
                                                ),
                                                AnnotationReducer.Kind.MOVE,
                                                before = originalMeasurement!!
                                             )
                                    }
                                    selectedMeasurement = measurements.firstOrNull { it.id == selectedMeasurement?.id }
                                    selectedMeasurementIndex = measurements.indexOfFirst { it.id == selectedMeasurement?.id }
                                    measurementDraft = null
                                    draggingPointIdx = -1
                                    originalMeasurement = null
                                    isItemDragging = false
                                    if (selectedMeasurement != null) {
                                        selectedItem = PageItem.Measure(selectedMeasurement!!)
                                        // Update toolbar position to the measurement's new center
                                        val m = selectedMeasurement!!
                                        val baseScale = if (bW > 0f) (vW / bW) else 1f
                                        val compScale = baseScale * scale
                                        val imgW = bW * compScale
                                        val imgH = bH * compScale
                                        val imgLeft = w / 2 + offsetX - imgW / 2
                                        val imgTop = h / 2 + offsetY - imgH / 2
                                         val p1s = Offset(imgLeft + m.p1.x * bW * compScale, imgTop + m.p1.y * bH * compScale)
                                         val p2s = Offset(imgLeft + m.p2.x * bW * compScale, imgTop + m.p2.y * bH * compScale)
                                        selectionToolbarPos = Offset((p1s.x + p2s.x) / 2 + 50f, (p1s.y + p2s.y) / 2)
                                    }
                                } else if (draggingNoteIdx != -1) {
                                    if (originalNote != null && selectedNote != null) {
                                        val updated = selectedNote!!.copyNote()
                                         annotationReducer.updatePdfNoteAt(pageIndex, draggingNoteIdx, updated, before = originalNote!!)
                                    }
                                    draggingNoteIdx = -1
                                    noteGestureActive = false
                                    originalNote = null
                                    noteDraft = null
                                    selectedNote = notes.firstOrNull { it.id == selectedNote?.id }
                                    selectedNoteIdx = notes.indexOfFirst { it.id == selectedNote?.id }
                                    isItemDragging = false
                                    if (selectedNote != null) {
                                        selectedItem = PageItem.NoteItem(selectedNote!!, selectedNoteIdx)
                                        // Update toolbar position to the note's new position
                                        val n = selectedNote!!
                                        val baseScale = if (bW > 0f) (vW / bW) else 1f
                                        val compScale = baseScale * scale
                                        val imgW = bW * compScale
                                        val imgH = bH * compScale
                                        val imgLeft = w / 2 + offsetX - imgW / 2
                                        val imgTop = h / 2 + offsetY - imgH / 2
                                         selectionToolbarPos = Offset(imgLeft + n.x * bW * compScale + 50f, imgTop + n.y * bH * compScale)
                                    }
                                
                            } else if ((draggingShape || rotatingShape || resizingShape) && selectedShape != null) {
                                if (originalShape != null) {
                                    val updated = selectedShape!!.copyShape()
                                     val kind = when {
                                         rotatingShape -> AnnotationReducer.Kind.ROTATE
                                         resizingShape -> AnnotationReducer.Kind.RESIZE
                                         draggingShape -> AnnotationReducer.Kind.MOVE
                                         else -> AnnotationReducer.Kind.UPDATE
                                     }
                                     annotationReducer.updatePdfShape(pageIndex, originalShape!!, updated, kind)
                                }
                                draggingShape = false
                                rotatingShape = false
                                resizingShape = false
                                originalShape = null
                                shapeDraft = null
                                selectedShape = shapes.firstOrNull { it.id == selectedShape?.id }
                                isItemDragging = false
                                if (selectedShape != null) {
                                    selectedItem = PageItem.ShapeItem(selectedShape!!)
                                    val s = selectedShape!!
                                    val baseScale = if (bW > 0f) (vW / bW) else 1f
                                    val compScale = baseScale * scale
                                    val imgW = bW * compScale
                                    val imgH = bH * compScale
                                    val imgLeft = w / 2 + offsetX - imgW / 2
                                    val imgTop = h / 2 + offsetY - imgH / 2
                                     selectionToolbarPos = Offset(imgLeft + s.x * bW * compScale + 50f, imgTop + s.y * bH * compScale)
                                }
                            } else if (calibratePointIdx != -1) {
                                calibratePointIdx = -1
                            } else if (dragActive && currentStroke.isNotEmpty()) {
                                 val newPath = DrawnPath(
                                     currentStroke.toList(),
                                     if(mode == ToolMode.HIGHLIGHTER) Color.Yellow.toArgb() else Color.Red.toArgb(),
                                     mode == ToolMode.HIGHLIGHTER,
                                     strokeWidthRatio = if (mode == ToolMode.HIGHLIGHTER) 0.01f else 0.003f
                                 )
                                 annotationReducer.addPdfPath(pageIndex, newPath)
                                currentStroke.clear()
                            } else if (!dragActive && mode == ToolMode.PAN) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                // Find ALL items at tap location for disambiguation
                                val foundItems = mutableListOf<PageItem>()
                                 val thresholdSegment = 60f / pointerCompositeScale
                                
                                // Check measurements
                                for (m in measurements) { 
                                    if (distToSegment(Point(tapPt.x * bW, tapPt.y * bH), Point(m.p1.x * bW, m.p1.y * bH), Point(m.p2.x * bW, m.p2.y * bH)) < thresholdSegment) {
                                        foundItems.add(PageItem.Measure(m))
                                    } 
                                }
                                // Check notes
                                for ((noteOrdinal, n) in notes.withIndex()) {
                                    if (AnnotationCanvasRendering.containsNote(n, tapPt.x * bW, tapPt.y * bH,
                                            bW, bH, 4f / pointerCompositeScale)) {
                                        foundItems.add(PageItem.NoteItem(n, noteOrdinal))
                                    }
                                }
                                // Check paths
                                for (p in paths) {
                                    var pathHit = false
                                    for (i in 0 until p.points.size - 1) { 
                                         if (distToSegment(Point(tapPt.x * bW, tapPt.y * bH), Point(p.points[i].x * bW, p.points[i].y * bH), Point(p.points[i+1].x * bW, p.points[i+1].y * bH)) < thresholdSegment + (p.strokeWidthRatio * maxOf(bW, bH) / 2f)) {
                                            pathHit = true
                                            break 
                                        } 
                                    }
                                    if (pathHit) foundItems.add(PageItem.Path(p))
                                }
                                // Check photo pins
                                 val pinThreshold = 100f / pointerCompositeScale
                                for (pin in photoPins) {
                                    val dx = (tapPt.x - pin.x) * bW
                                    val dy = (tapPt.y - pin.y) * bH
                                    if (sqrt(dx*dx + dy*dy) < pinThreshold) { 
                                        foundItems.add(PageItem.PhotoPinItem(pin))
                                    }
                                }
                                // Check shapes
                                for (s in shapes) {
                                    if (AnnotationCanvasRendering.containsShape(s, tapPt.x * bW, tapPt.y * bH,
                                            bW, bH, 40f / pointerCompositeScale)) {
                                        foundItems.add(PageItem.ShapeItem(s))
                                    }
                                }
                                
                                // Store tap position for toolbar
                                selectionToolbarPos = Offset(down.position.x + 50f, down.position.y)
                                
                                if (foundItems.size > 1) {
                                    // Multiple items overlap - show picker toolbar
                                    overlappingItems = foundItems
                                    showItemPicker = true
                                    selectedItem = null
                                    selectedMeasurement = null
                                    selectedMeasurementIndex = -1
                                    measurementDraft = null
                                    selectedNote = null
                                    selectedNoteIdx = -1
                                    selectedPhotoPin = null
                                    selectedShape = null
                                } else if (foundItems.size == 1) {
                                    // Single item - select it directly
                                    val found = foundItems.first()
                                    selectedItem = found
                                    showItemPicker = false
                                    overlappingItems = emptyList()
                                    selectedMeasurement = if (found is PageItem.Measure) found.data else null
                                    selectedMeasurementIndex = if (found is PageItem.Measure) measurements.indexOf(found.data) else -1
                                    measurementDraft = null
                                    if (found is PageItem.NoteItem) {
                                        selectedNote = found.data
                                        selectedNoteIdx = found.ordinal.takeIf { it >= 0 }
                                            ?: notes.indexOfFirst { it === found.data }
                                    } else {
                                        selectedNote = null
                                        selectedNoteIdx = -1
                                    }
                                    selectedPhotoPin = if (found is PageItem.PhotoPinItem) found.data else null
                                    selectedShape = if (found is PageItem.ShapeItem) found.data else null
                                } else {
                                    // No items found - clear selection (including text selection)
                                    selectedItem = null
                                    showItemPicker = false
                                    overlappingItems = emptyList()
                                    selectedMeasurement = null
                                    selectedMeasurementIndex = -1
                                    measurementDraft = null
                                    selectedNote = null
                                    selectedNoteIdx = -1
                                    selectedPhotoPin = null
                                    selectedShape = null
                                    // Clear text selection too
                                    if (isTextSelecting) {
                                        isTextSelecting = false
                                        showCopyButton = false
                                        selectedOcrBoxes = emptyList()
                                        textSelectionStartIdx = -1
                                        textSelectionEndIdx = -1
                                    }
                                }
                            } else if (!dragActive && mode == ToolMode.NOTE) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                notePos = tapPt
                                noteInput = ""
                                showNoteDialog = true
                            } else if (!dragActive && mode == ToolMode.PHOTO) {
                                 val tapPt = screenToPage(down.position.x, down.position.y)
                                 val newPin = PhotoPin(tapPt.x, tapPt.y)
                                 if (annotationReducer.addPhotoPin(pageIndex, newPin).changed) {
                                     onAnnotationAdded()
                                 }
                            } else if (!dragActive && mode == ToolMode.SHAPE) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                shapePos = tapPt
                                 showShapeDialog = true
                            } else if (!dragActive && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                                val pt = screenToPage(down.position.x, down.position.y)
                                if (firstPoint == null) firstPoint = pt else if (secondPoint == null) { 
                                    secondPoint = pt
                                    if (mode == ToolMode.MEASURE) {
                                         if (currentScale != null) {
                                             sourceDistance(firstPoint!!, secondPoint!!)?.let { distance ->
                                                 val text = formatFeet(distance / currentScale.pointsPerFoot)
                                                 val newM = Measurement(firstPoint!!, secondPoint!!, text)
                                                 if (annotationReducer.addMeasurement(pageIndex, newM).changed) {
                                                     onAnnotationAdded()
                                                 }
                                             }
                                        }
                                        firstPoint = null
                                        secondPoint = null
                                    }
                                } else { 
                                    if (mode != ToolMode.SCALE) {
                                        firstPoint = pt; secondPoint = null 
                                    }
                                }
                                }
                             }

                             } finally {
                                // Cancellation, resize, tool changes and normal release
                                // all discard transient points; none belong to the next gesture.
                                currentStroke.clear()
                                measurementDraft = null
                                noteDraft = null
                                shapeDraft = null
                                originalMeasurement = null
                                originalNote = null
                                originalShape = null
                                draggingPointIdx = -1
                                draggingNoteIdx = -1
                                draggingShape = false
                                rotatingShape = false
                                resizingShape = false
                                isItemDragging = false
                                selectedMeasurement = measurements.firstOrNull { it.id == selectedMeasurement?.id }
                                selectedMeasurementIndex = measurements.indexOfFirst { it.id == selectedMeasurement?.id }
                                selectedNote = notes.firstOrNull { it.id == selectedNote?.id }
                                selectedNoteIdx = notes.indexOfFirst { it.id == selectedNote?.id }
                                selectedShape = shapes.firstOrNull { it.id == selectedShape?.id }
                            }
                        }
                    }
            ) {
                Image(bitmap = b.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY), filterQuality = FilterQuality.High)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Calculate base scale: ratio of view size to bitmap size
                    val baseScale = if (bW > 0f) (vW / bW) else 1f
                    val compositeScale = baseScale * scale
                    
                    fun toS(p: Point): Offset {
                        val imgW = bW * compositeScale
                        val imgH = bH * compositeScale
                        val imgLeft = size.width / 2 + offsetX - imgW / 2
                        val imgTop = size.height / 2 + offsetY - imgH / 2
                        return Offset(imgLeft + p.x * bW * compositeScale, imgTop + p.y * bH * compositeScale)
                    }
                    paths.forEach { pathData ->
                        if (pathData.points.size > 1) {
                            val path = Path(); path.moveTo(toS(pathData.points[0]).x, toS(pathData.points[0]).y)
                            for (i in 1 until pathData.points.size) { val p = toS(pathData.points[i]); path.lineTo(p.x, p.y) }
                             val strokePx = (pathData.strokeWidthRatio * maxOf(bW, bH) * compositeScale).coerceAtLeast(1f)
                             drawPath(path, Color(pathData.colorArgb), if (pathData.isHighlighter) 0.4f else 1f, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    measurements.forEachIndexed { measurementIndex, storedMeasurement ->
                        // Gesture edits remain detached drafts until pointer-up;
                        // only the reducer publishes the persisted replacement.
                        val m = if (measurementIndex == selectedMeasurementIndex) {
                            measurementDraft ?: storedMeasurement
                        } else storedMeasurement
                        val p1 = toS(m.p1); val p2 = toS(m.p2)
                        val isSelectedMeasurement = measurementIndex == selectedMeasurementIndex
                        val color = if (isSelectedMeasurement) Color.Cyan else Color(0xFFE91E63)
                        drawLine(color, p1, p2, strokeWidth = 4f)
                        drawCircle(color, 6f, p1); drawCircle(color, 6f, p2)
                        if (isSelectedMeasurement) {
                            val boxSize = 40f
                            val dashedStroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                            drawRect(color = Color.Cyan, topLeft = p1 - Offset(boxSize/2, boxSize/2), size = Size(boxSize, boxSize), style = dashedStroke)
                            drawRect(color = Color.Cyan, topLeft = p2 - Offset(boxSize/2, boxSize/2), size = Size(boxSize, boxSize), style = dashedStroke)
                        }
                        val mid = Offset((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
                        val mMatches = searchTerm.isNotBlank() && m.text.contains(searchTerm, ignoreCase = true)
                        val bgColor = if (mMatches) Color.Yellow else Color.Black.copy(alpha = 0.7f)
                        val txtColor = if (mMatches) Color.Black else Color.White
                        val textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = txtColor)
                        val textLayoutResult = textMeasurer.measure(m.text, style = textStyle)
                        val textWidth = textLayoutResult.size.width.toFloat(); val textHeight = textLayoutResult.size.height.toFloat()
                        val textTopLeft = mid - Offset(textWidth / 2f, textHeight / 2f)
                        drawRect(color = bgColor, topLeft = textTopLeft - Offset(8f, 4f), size = Size(textWidth + 16f, textHeight + 8f))
                        drawText(textLayoutResult, topLeft = textTopLeft)
                    }
                    notes.forEachIndexed { noteIndex, originalNoteValue ->
                        val n = if (noteIndex == draggingNoteIdx) noteDraft ?: originalNoteValue else originalNoteValue
                        val origin = toS(Point(0f, 0f))
                        val surfaceWidth = bW * compositeScale
                        val surfaceHeight = bH * compositeScale
                        val bounds = AnnotationCanvasRendering.noteBounds(n, surfaceWidth, surfaceHeight)
                        val p = toS(Point(n.x, n.y))
                        val selected = n.id == selectedNote?.id
                        rotate(degrees = n.rotation, pivot = p) {
                            val topLeft = origin + Offset(bounds.left, bounds.top)
                            val box = Size(bounds.width(), bounds.height())
                            if (searchTerm.isNotBlank() && n.text.contains(searchTerm, ignoreCase = true))
                                drawRect(Color.Yellow, topLeft - Offset(8f, 4f), Size(box.width + 16f, box.height + 8f))
                            if (selected) drawRect(Color.Cyan, topLeft - Offset(8f, 4f),
                                Size(box.width + 16f, box.height + 8f),
                                style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)))
                        }
                        AnnotationCanvasRendering.drawNote(drawContext.canvas.nativeCanvas, n,
                            origin.x, origin.y, surfaceWidth, surfaceHeight,
                            if (selected) Color.Cyan.toArgb() else Color.Black.toArgb())
                    }

                    // Draw photo pins as camera icons
                    // Calculate global pin index across all pages
                    val allPinsSorted = allPagePhotoPins.keys.sorted().flatMap { pageIdx ->
                        allPagePhotoPins[pageIdx]?.map { it to pageIdx } ?: emptyList()
                    }
                    
                    photoPins.forEach { pin ->
                        val p = toS(Point(pin.x, pin.y))
                        val isSelected = pin == selectedPhotoPin
                        val pinRadius = 20f * scale
                        // Draw pin background circle
                        drawCircle(
                            color = if (isSelected) Color.Cyan else Color(0xFF1976D2),
                            radius = pinRadius,
                            center = p
                        )
                        // Draw camera icon (simplified as a small rectangle)
                        drawCircle(
                            color = Color.White,
                            radius = pinRadius * 0.5f,
                            center = p
                        )
                        // Show pin number badge with global index
                        val globalIndex = allPinsSorted.indexOfFirst { it.first.id == pin.id }
                        val pinNumber = if (globalIndex >= 0) (globalIndex + 1).toString() else "?" // 1-based numbering
                        val badgeCenter = p + Offset(pinRadius * 0.7f, -pinRadius * 0.7f)
                        drawCircle(color = Color.Red, radius = 10f * scale, center = badgeCenter)
                        val countStyle = TextStyle(fontSize = (8f * scale).sp, fontWeight = FontWeight.Bold, color = Color.White)
                        val countLayout = textMeasurer.measure(pinNumber, style = countStyle)
                        drawText(countLayout, topLeft = badgeCenter - Offset(countLayout.size.width / 2f, countLayout.size.height / 2f))
                    }

                    // Draw shapes
                    shapes.forEachIndexed { shapeIndex, originalShapeValue ->
                        val shape = if (shapeIndex == shapes.indexOfFirst { it.id == originalShape?.id } && shapeDraft != null) shapeDraft!! else originalShapeValue
                        val center = toS(Point(shape.x, shape.y))
                        // Current-format dimensions are ratio-based and independent of
                        // the legacy absolute fields.
                        val pageMaxDim = maxOf(bW, bH)
                        val pageShapeSize = AnnotationGeometry.resolvePageSize(bW, bH, shape.widthRatio, shape.heightRatio)
                        val actualWidth = pageShapeSize.width
                        val actualHeight = pageShapeSize.height
                         val actualStrokeWidth = shape.strokeWidthRatio * pageMaxDim
                        // Scale by compositeScale (baseScale * scale) to match position transformation
                        val scaledWidth = actualWidth * compositeScale
                        val scaledHeight = actualHeight * compositeScale
                        val scaledStroke = actualStrokeWidth * compositeScale
                        val isSelected = shape == selectedShape
                        val shapeColor = if (isSelected) Color.Cyan else Color(shape.colorArgb)
                        
                        // Debug: show what percentage of page the shape covers
                        val widthPercent = actualWidth / bW * 100
                        SafeDiagnostics.debug(DiagnosticEvent.ANNOTATION_ACTIVITY)
                        
                        val shapeOrigin = toS(Point(0f, 0f))
                        AnnotationCanvasRendering.drawShape(drawContext.canvas.nativeCanvas,
                            shape.copy(colorArgb = shapeColor.toArgb()), shapeOrigin.x, shapeOrigin.y,
                            bW * compositeScale, bH * compositeScale)
                        rotate(degrees = shape.rotation, pivot = center) {

                            
                            // Draw selection handles if selected
                            if (isSelected) {
                                val handleSize = 12f * scale
                                val dashedStroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                                
                                // Bounding box
                                drawRect(
                                    color = Color.Cyan,
                                    topLeft = center - Offset(scaledWidth / 2, scaledHeight / 2),
                                    size = Size(scaledWidth, scaledHeight),
                                    style = dashedStroke
                                )
                                
                                // Corner resize handles
                                val corners = listOf(
                                    center - Offset(scaledWidth / 2, scaledHeight / 2),
                                    center + Offset(scaledWidth / 2, -scaledHeight / 2),
                                    center + Offset(scaledWidth / 2, scaledHeight / 2),
                                    center + Offset(-scaledWidth / 2, scaledHeight / 2)
                                )
                                corners.forEach { corner ->
                                    drawRect(
                                        color = Color.White,
                                        topLeft = corner - Offset(handleSize / 2, handleSize / 2),
                                        size = Size(handleSize, handleSize)
                                    )
                                    drawRect(
                                        color = Color.Cyan,
                                        topLeft = corner - Offset(handleSize / 2, handleSize / 2),
                                        size = Size(handleSize, handleSize),
                                        style = Stroke(width = 2f)
                                    )
                                }
                            }
                        }
                    }
                    
                    // Draw text selection highlights (blue, like web selection)
                    if (selectedOcrBoxes.isNotEmpty()) {
                        for (box in selectedOcrBoxes) {
                            val bitmapRect = PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                box.rectN,
                                bW,
                                bH
                            ) ?: continue
                            val lpx = bitmapRect.left
                            val tpx = bitmapRect.top
                            val rpx = bitmapRect.right
                            val bpx = bitmapRect.bottom
                            // Expand slightly for better visibility
                            val expandH = 1.2f
                            val centerY = (tpx + bpx) / 2f
                            val halfH = (bpx - tpx) / 2f * expandH
                            val tl = toS(Point(lpx, centerY - halfH))
                            val br = toS(Point(rpx, centerY + halfH))
                            val topLeft = Offset(minOf(tl.x, br.x), minOf(tl.y, br.y))
                            val rectSize = Size(kotlin.math.abs(br.x - tl.x), kotlin.math.abs(br.y - tl.y))
                            drawRect(color = Color(0xFF2196F3).copy(alpha = 0.4f), topLeft = topLeft, size = rectSize)
                        }
                        
                        // Draw selection handles (teardrop shape at start and end)
                        if (cachedPageOcr != null && textSelectionStartIdx >= 0 && textSelectionEndIdx >= 0) {
                            val startIdx = minOf(textSelectionStartIdx, textSelectionEndIdx)
                            val endIdx = maxOf(textSelectionStartIdx, textSelectionEndIdx)
                            
                            if (startIdx < cachedPageOcr!!.boxes.size && endIdx < cachedPageOcr!!.boxes.size) {
                                val startBox = cachedPageOcr!!.boxes[startIdx]
                                val endBox = cachedPageOcr!!.boxes[endIdx]
                                val startRect = PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                    startBox.rectN,
                                    bW,
                                    bH
                                )
                                val endRect = PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
                                    endBox.rectN,
                                    bW,
                                    bH
                                )
                                if (startRect == null || endRect == null) {
                                    return@Canvas
                                }
                                
                                // Calculate text height in screen pixels for scaling
                                val startBoxHeight = (startRect.bottom - startRect.top) * compositeScale
                                val endBoxHeight = (endRect.bottom - endRect.top) * compositeScale
                                
                                val handleColor = Color(0xFF2196F3)
                                
                                // Start handle (left side of first box, bottom)
                                val startHandleRadius = (startBoxHeight * 0.5f).coerceIn(6f, 20f)
                                val startStemHeight = startBoxHeight * 0.8f
                                val startHandlePos = toS(Point(startRect.left, startRect.bottom))
                                // Draw stem (line going up)
                                drawLine(
                                    color = handleColor,
                                    start = startHandlePos,
                                    end = startHandlePos - Offset(0f, startStemHeight),
                                    strokeWidth = (startHandleRadius * 0.25f).coerceIn(2f, 4f)
                                )
                                // Draw circle at bottom
                                drawCircle(
                                    color = handleColor,
                                    radius = startHandleRadius,
                                    center = startHandlePos + Offset(0f, startHandleRadius * 0.5f)
                                )
                                // White inner circle for contrast
                                drawCircle(
                                    color = Color.White,
                                    radius = startHandleRadius * 0.35f,
                                    center = startHandlePos + Offset(0f, startHandleRadius * 0.5f)
                                )
                                
                                // End handle (right side of last box, bottom)
                                val endHandleRadius = (endBoxHeight * 0.5f).coerceIn(6f, 20f)
                                val endStemHeight = endBoxHeight * 0.8f
                                val endHandlePos = toS(Point(endRect.right, endRect.bottom))
                                // Draw stem
                                drawLine(
                                    color = handleColor,
                                    start = endHandlePos,
                                    end = endHandlePos - Offset(0f, endStemHeight),
                                    strokeWidth = (endHandleRadius * 0.25f).coerceIn(2f, 4f)
                                )
                                // Draw circle at bottom
                                drawCircle(
                                    color = handleColor,
                                    radius = endHandleRadius,
                                    center = endHandlePos + Offset(0f, endHandleRadius * 0.5f)
                                )
                                // White inner circle
                                drawCircle(
                                    color = Color.White,
                                    radius = endHandleRadius * 0.35f,
                                    center = endHandlePos + Offset(0f, endHandleRadius * 0.5f)
                                )
                            }
                        }
                    }
                    
                    if (highlightRects.isNotEmpty() && DEBUG_LOG) {
                        try {
                            val minL = highlightRects.minOf { it.left }
                            val minT = highlightRects.minOf { it.top }
                            val maxR = highlightRects.maxOf { it.right }
                            val maxB = highlightRects.maxOf { it.bottom }
                            SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                        } catch (_: Exception) {}
                    }

                    for (hr in highlightRects) {
                        // hr is normalized (0..1) relative to page bitmap: convert to bitmap pixels then expand by 25% and map to screen
                        val bitmapRect = PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(hr, bW, bH)
                            ?: continue
                        val rawLeftPx = bitmapRect.left
                        val rawTopPx = bitmapRect.top
                        val rawRightPx = bitmapRect.right
                        val rawBottomPx = bitmapRect.bottom
                        val expandW = 1.0f
                        val expandH = 1.30f
                        val centerX = (rawLeftPx + rawRightPx) / 2f
                        val centerY = (rawTopPx + rawBottomPx) / 2f
                        val halfW = (rawRightPx - rawLeftPx) / 2f * expandW
                        val halfH = (rawBottomPx - rawTopPx) / 2f * expandH
                        val leftPx = centerX - halfW
                        val topPx = centerY - halfH
                        val rightPx = centerX + halfW
                        val bottomPx = centerY + halfH
                        val tl = toS(Point(leftPx, topPx))
                        val br = toS(Point(rightPx, bottomPx))
                        val rectTopLeft = Offset(minOf(tl.x, br.x), minOf(tl.y, br.y))
                        val rectSize = Size(kotlin.math.abs(br.x - tl.x), kotlin.math.abs(br.y - tl.y))
                        drawRect(color = Color(0xFFFFA500).copy(alpha = 0.6f), topLeft = rectTopLeft, size = rectSize)
                    }
                    if (currentStroke.size > 1) {
                        val path = Path(); path.moveTo(toS(currentStroke[0]).x, toS(currentStroke[0]).y)
                        for (i in 1 until currentStroke.size) { val p = toS(currentStroke[i]); path.lineTo(p.x, p.y) }
                        drawPath(path, if(mode == ToolMode.HIGHLIGHTER) Color.Yellow else Color.Red, if(mode == ToolMode.HIGHLIGHTER) 0.4f else 1f, style = Stroke((if(mode == ToolMode.HIGHLIGHTER) 12f else 2f) * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    if (firstPoint != null && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                        val p1 = toS(firstPoint!!); drawCircle(Color(0xFFE91E63), 8f, p1)
                        secondPoint?.let { val p2 = toS(it); drawCircle(Color(0xFFE91E63), 8f, p2); drawLine(Color(0xFFE91E63), p1, p2, 4f) }
                    }
                }
                if (firstPoint != null && secondPoint != null && mode == ToolMode.SCALE) {
                    Box(Modifier.fillMaxSize().padding(bottom = 32.dp), Alignment.BottomCenter) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp, modifier = Modifier.clickable { firstPoint = null; secondPoint = null }) {
                                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.clear_page_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp, modifier = Modifier.clickable { showScaleDialog = true }) {
                                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Check, null, tint = Color.White); Spacer(Modifier.width(12.dp))
                                    Text(stringResource(R.string.confirm_calibration), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Item picker toolbar - appears when tapping overlapping items
                if (showItemPicker && overlappingItems.isNotEmpty() && mode == ToolMode.PAN) {
                    val density = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
                    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
                    
                    val toolbarWidth = with(density) { (overlappingItems.size * 48 + 16).dp.toPx() }
                    val toolbarHeight = with(density) { 56.dp.toPx() }
                    
                    val adjustedX = selectionToolbarPos.x.coerceIn(0f, (screenWidthPx - toolbarWidth).coerceAtLeast(0f))
                    val adjustedY = if (selectionToolbarPos.y + toolbarHeight > screenHeightPx) {
                        (selectionToolbarPos.y - toolbarHeight - with(density) { 16.dp.toPx() }).coerceAtLeast(0f)
                    } else {
                        selectionToolbarPos.y
                    }
                    
                    val dx = with(density) { adjustedX.toDp() }
                    val dy = with(density) { adjustedY.toDp() }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Card(
                            modifier = Modifier.offset(dx, dy).shadow(4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(stringResource(R.string.annotation_select_item), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    overlappingItems.forEach { item ->
                                        val icon = when (item) {
                                            is PageItem.Measure -> Icons.Default.Straighten
                                            is PageItem.NoteItem -> Icons.Filled.StickyNote2
                                            is PageItem.Path -> Icons.Default.Brush
                                            is PageItem.PhotoPinItem -> Icons.Default.CameraAlt
                                            is PageItem.ShapeItem -> when (item.data.type) {
                                                ShapeType.RECTANGLE -> Icons.Default.CropSquare
                                                ShapeType.CIRCLE -> Icons.Default.Circle
                                                ShapeType.ARROW -> Icons.AutoMirrored.Filled.ArrowForward
                                                ShapeType.CLOUD -> Icons.Default.Cloud
                                            }
                                        }
                                        val label = when (item) {
                                            is PageItem.Measure -> stringResource(R.string.annotation_measure)
                                            is PageItem.NoteItem -> stringResource(R.string.annotation_note)
                                            is PageItem.Path -> stringResource(if (item.data.isHighlighter) R.string.annotation_highlight else R.string.annotation_drawing)
                                            is PageItem.PhotoPinItem -> stringResource(R.string.annotation_photo)
                                            is PageItem.ShapeItem -> when (item.data.type) {
                                                ShapeType.RECTANGLE -> stringResource(R.string.shape_rectangle)
                                                ShapeType.CIRCLE -> stringResource(R.string.shape_circle)
                                                ShapeType.ARROW -> stringResource(R.string.shape_arrow)
                                                ShapeType.CLOUD -> stringResource(R.string.shape_cloud)
                                            }
                                        }
                                        
                                        Surface(
                                            modifier = Modifier.clickable {
                                                selectedItem = item
                                                showItemPicker = false
                                                overlappingItems = emptyList()
                                                selectedMeasurement = if (item is PageItem.Measure) item.data else null
                                                selectedMeasurementIndex = if (item is PageItem.Measure) measurements.indexOf(item.data) else -1
                                                measurementDraft = null
                                                if (item is PageItem.NoteItem) {
                                                    selectedNote = item.data
                                                    selectedNoteIdx = item.ordinal.takeIf { it >= 0 }
                                                        ?: notes.indexOfFirst { it === item.data }
                                                } else {
                                                    selectedNote = null
                                                    selectedNoteIdx = -1
                                                }
                                                selectedPhotoPin = if (item is PageItem.PhotoPinItem) item.data else null
                                                selectedShape = if (item is PageItem.ShapeItem) item.data else null
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Column(
                                                Modifier.padding(8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(icon, contentDescription = label, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            }
                                        }
                                    }
                                    // Cancel button
                                    Surface(
                                        modifier = Modifier.clickable {
                                            showItemPicker = false
                                            overlappingItems = emptyList()
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Column(
                                            Modifier.padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_page_cancel), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(stringResource(R.string.clear_page_cancel), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Contextual toolbar for selected items (appears in PAN mode) and follows the item
                if (selectedItem != null && mode == ToolMode.PAN && !isItemDragging) {
                    // Use the stored toolbar position (tap location or updated after drag)
                    val density = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
                    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
                    
                    // Estimate toolbar size for boundary checking
                    val toolbarWidth = with(density) { 200.dp.toPx() }
                    val toolbarHeight = with(density) { 48.dp.toPx() }
                    
                    // Adjust position to keep toolbar on screen
                    val adjustedX = selectionToolbarPos.x.coerceIn(0f, (screenWidthPx - toolbarWidth).coerceAtLeast(0f))
                    val adjustedY = if (selectionToolbarPos.y + toolbarHeight > screenHeightPx) {
                        (selectionToolbarPos.y - toolbarHeight - with(density) { 16.dp.toPx() }).coerceAtLeast(0f)
                    } else {
                        selectionToolbarPos.y
                    }
                    
                    val dx = with(density) { adjustedX.toDp() }
                    val dy = with(density) { adjustedY.toDp() }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Card(
                            modifier = Modifier.offset(dx, dy).shadow(4.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedItem is PageItem.NoteItem) {
                                    TextButton(
                                        onClick = {
                                            val note = (selectedItem as PageItem.NoteItem).data
                                            editingNote = note
                                            noteInput = note.text
                                            noteIsBold = note.isBold
                                            showNoteDialog = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.edit_action), style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = { itemToDelete = selectedItem },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color.Red)
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.annotation_delete_confirm), color = Color.Red, style = MaterialTheme.typography.labelSmall)
                                    }
                                } else if (selectedItem is PageItem.PhotoPinItem) {
                                    TextButton(
                                        onClick = {
                                            val requestPinId = selectedPhotoPin?.id
                                                ?: return@TextButton
                                            onRequestCameraCapture?.invoke(pageIndex, requestPinId)
                                        },
                                        enabled = onRequestCameraCapture != null,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.AddAPhoto, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.add_photo), style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = { showPinImageGallery = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.view_photos), style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = { itemToDelete = selectedItem },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color.Red)
                                    }
                                } else {
                                    TextButton(
                                        onClick = { itemToDelete = selectedItem },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color.Red)
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.annotation_delete_confirm), color = Color.Red, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    
                }
            }
        }
    }
    
    // Text selection copy button - floating button near selection
    if (showCopyButton && selectedOcrBoxes.isNotEmpty()) {
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        
        // Keep button on screen
        val btnWidth = with(density) { 80.dp.toPx() }
        val btnHeight = with(density) { 40.dp.toPx() }
        val adjustedX = copyButtonPos.x.coerceIn(0f, (screenWidthPx - btnWidth).coerceAtLeast(0f))
        val adjustedY = copyButtonPos.y.coerceIn(0f, (screenHeightPx - btnHeight).coerceAtLeast(0f))
        val dx = with(density) { adjustedX.toDp() }
        val dy = with(density) { adjustedY.toDp() }
        
        Box(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.offset(dx, dy).shadow(4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Copy button
                    Surface(
                        modifier = Modifier.clickable {
                            val selectedText = selectedOcrBoxes.joinToString(" ") { it.text }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(context.getString(R.string.selected_text_clip_label), selectedText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.copied_text, selectedText), Toast.LENGTH_SHORT).show()
                            
                            // Clear selection
                            showCopyButton = false
                            isTextSelecting = false
                            selectedOcrBoxes = emptyList()
                            textSelectionStartIdx = -1
                            textSelectionEndIdx = -1
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.copy), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    
                    // Cancel button
                    Surface(
                        modifier = Modifier.clickable {
                            showCopyButton = false
                            isTextSelecting = false
                            selectedOcrBoxes = emptyList()
                            textSelectionStartIdx = -1
                            textSelectionEndIdx = -1
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            Icons.Default.Close, 
                            contentDescription = stringResource(R.string.clear_page_cancel),
                            Modifier.padding(6.dp).size(16.dp), 
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    
    // Full screen image viewer - rendered on top of everything
    var fullScreenBitmapOwner by remember(
        sessionToken?.sourceCacheKey,
        sessionToken?.generation,
        pageIndex,
        selectedPhotoPin?.id,
        fullScreenImageFile
    ) { mutableStateOf<Stage7OwnedResource<Bitmap>?>(null) }

    DisposableEffect(sessionToken, pageIndex, selectedPhotoPin?.id, fullScreenImageFile) {
        onDispose {
            val owner = fullScreenBitmapOwner
            fullScreenBitmapOwner = null
            owner?.close()
        }
    }

    val latestImageGestureIdentity by rememberUpdatedState(
        Triple(sessionToken, pageIndex, selectedPhotoPinId to fullScreenImageFile)
    )
    val latestImageGestureMode by rememberUpdatedState(imageNoteToolMode)
    if (fullScreenImageFile != null) {
        // Consume system Back in the image viewer using the same transition as
        // the explicit close affordance; the document viewer remains open.
        BackHandler {
            interactionController.onBack(fullscreen = true) {
                fullScreenImageFile = null
                showPinImageGallery = true
                selectedImageNote = null
                selectedImageShape = null
                imageNoteToolMode = "pan"
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val fullScreenViewport = BitmapBudgetPolicy.displayViewport(
                widthPx = constraints.maxWidth,
                heightPx = constraints.maxHeight
            )
            if (fullScreenViewport != null) {
                LaunchedEffect(
                    sessionToken,
                    pageIndex,
                    selectedPhotoPin?.id,
                    fullScreenImageFile,
                    fullScreenViewport.width,
                    fullScreenViewport.height
                ) {
                    val fileName = fullScreenImageFile
                    val pinId = selectedPhotoPin?.id
                    if (fileName != null && pinId != null && sessionToken != null) {
                        stage7Worker.computeAndPublish(
                            compute = {
                                loadPhotoBitmapBlocking(
                                    context,
                                    sessionToken,
                                    fileName,
                                    viewportWidthPx = fullScreenViewport.width,
                                    viewportHeightPx = fullScreenViewport.height
                                )
                            },
                            acceptsBeforeMain = { isSessionCurrent(sessionToken) },
                            acceptsOnMain = {
                                fullScreenImageFile == fileName &&
                                    selectedPhotoPin?.id == pinId &&
                                    isSessionCurrent(sessionToken) &&
                                    isPageCurrent(sessionToken, pageIndex)
                            },
                            publish = { loadedOwner ->
                                val previousOwner = fullScreenBitmapOwner
                                fullScreenBitmapOwner = loadedOwner
                                if (previousOwner !== loadedOwner) previousOwner?.close()
                            },
                            reject = { rejectedOwner -> rejectedOwner.close() }
                        )
                    }
                }
            }

                val rotatedBmp = fullScreenBitmapOwner?.value
            if (rotatedBmp != null) {
                var imageScale by remember { mutableStateOf(1f) }
                var imageOffsetX by remember { mutableStateOf(0f) }
                var imageOffsetY by remember { mutableStateOf(0f) }
                val density = LocalDensity.current
                
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Calculate the base size of the image after ContentScale.Fit is applied
                    // This is the size BEFORE our custom zoom (imageScale) is applied
                    val containerWidthPx = constraints.maxWidth.toFloat()
                    val containerHeightPx = constraints.maxHeight.toFloat()
                    val bmpWidth = rotatedBmp.width.toFloat()
                    val bmpHeight = rotatedBmp.height.toFloat()
                    
                    // ContentScale.Fit scales uniformly to fit within container
                    val fitScale = minOf(containerWidthPx / bmpWidth, containerHeightPx / bmpHeight)
                    val baseImgWidth = bmpWidth * fitScale
                    val baseImgHeight = bmpHeight * fitScale
                    
                    // Actual displayed size with our zoom applied
                    val displayedImgWidth = baseImgWidth * imageScale
                    val displayedImgHeight = baseImgHeight * imageScale
                    
                    // Center of container
                    val centerX = containerWidthPx / 2f
                    val centerY = containerHeightPx / 2f
                    
                    // Image bounds (top-left corner)
                    val imgLeft = centerX + imageOffsetX - displayedImgWidth / 2f
                    val imgTop = centerY + imageOffsetY - displayedImgHeight / 2f
                    
                    // Helper function to convert screen position to relative image coordinates (0.0 to 1.0)
                    fun screenToImageCoords(screenX: Float, screenY: Float): Offset? {
                        // Check if within image bounds
                        if (screenX >= imgLeft && screenX <= imgLeft + displayedImgWidth &&
                            screenY >= imgTop && screenY <= imgTop + displayedImgHeight) {
                            val relX = (screenX - imgLeft) / displayedImgWidth
                            val relY = (screenY - imgTop) / displayedImgHeight
                            return Offset(relX, relY)
                        }
                        return null
                    }
                    
                    // Helper function to convert relative image coords to screen position
                    fun imageToScreenCoords(relX: Float, relY: Float): Offset {
                        val screenX = imgLeft + relX * displayedImgWidth
                        val screenY = imgTop + relY * displayedImgHeight
                        return Offset(screenX, screenY)
                    }
                    
                    // Helper function to find note at screen position
                    fun findNoteAt(screenX: Float, screenY: Float): PhotoImageNote? {
                        val pin = selectedPhotoPin ?: return null
                        val fileName = fullScreenImageFile ?: return null
                        return pin.imageNotes[fileName].orEmpty().lastOrNull { note ->
                            AnnotationCanvasRendering.containsNote(note, screenX - imgLeft, screenY - imgTop,
                                displayedImgWidth, displayedImgHeight, 10f * density.density)
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(sessionToken, pageIndex, imageNoteToolMode, selectedPhotoPinId, fullScreenImageFile, constraints.maxWidth, constraints.maxHeight) {
                                awaitEachGesture {
                                    val gestureIdentity = latestImageGestureIdentity
                                    val gestureMode = latestImageGestureMode
                                    try {
                                        val gesturePinId = gestureIdentity.third.first ?: return@awaitEachGesture
                                        val gestureFile = gestureIdentity.third.second ?: return@awaitEachGesture

                                    val firstDown = awaitFirstDown()
                                    val startPos = firstDown.position
                                     var wasDrag = false
                                     var wasZoom = false
                                     var gestureCancelled = false
                                     var imageDocumentChanged = false
                                    var imageNoteMoved = false
                                    var imageNoteResized = false
                                    var imageNoteRotated = false
                                    var imageShapeMoved = false
                                    var imageShapeResized = false
                                    var imageShapeRotated = false
                                    
                                    // Calculate current image bounds for hit testing
                                    val currentFitScale = minOf(size.width.toFloat() / rotatedBmp.width, size.height.toFloat() / rotatedBmp.height)
                                    val currentBaseWidth = rotatedBmp.width * currentFitScale
                                    val currentBaseHeight = rotatedBmp.height * currentFitScale
                                    val currentDisplayedWidth = currentBaseWidth * imageScale
                                    val currentDisplayedHeight = currentBaseHeight * imageScale
                                    val currentCenterX = size.width / 2f
                                    val currentCenterY = size.height / 2f
                                    val currentImgLeft = currentCenterX + imageOffsetX - currentDisplayedWidth / 2f
                                    val currentImgTop = currentCenterY + imageOffsetY - currentDisplayedHeight / 2f
                                    
                                    // Check if we tapped on a note
                                    var tappedNote: PhotoImageNote? = null
                                    if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                        val imageNotes = selectedPhotoPin!!.imageNotes[fullScreenImageFile!!]
                                        if (imageNotes != null) {
                                            // Calculate text dimensions for hit testing
                                            for (note in imageNotes) {
                                                if (AnnotationCanvasRendering.containsNote(note,
                                                        startPos.x - currentImgLeft, startPos.y - currentImgTop,
                                                        currentDisplayedWidth, currentDisplayedHeight, 10f * density.density)) {
                                                    tappedNote = note
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    
                                    if (tappedNote != null && imageNoteToolMode != "place" && imageNoteToolMode != "shape") {
                                        selectedImageNote = tappedNote
                                        selectedImageShape = null
                                        draggingImageNote = tappedNote
                                        originalImageNote = tappedNote.copy()
                                        imageNoteDraft = tappedNote.copyImageNote()
                                    }
                                    
                                    // Check if tapped on a shape
                                    var tappedShape: Shape? = null
                                    if (selectedPhotoPin != null && fullScreenImageFile != null && imageNoteToolMode != "place") {
                                        val imageShapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!] ?: emptyList()
                                        for (shape in imageShapes) {
                                            if (AnnotationCanvasRendering.containsShape(shape,
                                                    startPos.x - currentImgLeft, startPos.y - currentImgTop,
                                                    currentDisplayedWidth, currentDisplayedHeight, 30f)) {
                                                tappedShape = shape
                                                break
                                            }
                                        }
                                    }
                                    
                                    if (tappedShape != null && imageNoteToolMode != "place" && imageNoteToolMode != "shape" && tappedNote == null) {
                                        selectedImageShape = tappedShape
                                        selectedImageNote = null
                                        draggingImageShape = true
                                        originalImageShape = tappedShape.copy()
                                        imageShapeDraft = tappedShape.copyShape()
                                    }
                                    
                                     do {
                                         val event = awaitPointerEvent()
                                          // Compose adapts ACTION_CANCEL in
                                          // SuspendingPointerInputModifierNodeImpl by dispatching
                                          // consumed all-up changes.  Read that incoming
                                          // consumption before this handler consumes any change.
                                          if (event.isIncomingCancellation()) {
                                              gestureCancelled = true
                                              break
                                          }
                                         if (event.changes.size >= 2) {
                                            wasZoom = true
                                            val zoom = event.calculateZoom()
                                            val rotation = event.calculateRotation()
                                            if (selectedImageNote != null) {
                                                // Pinch to resize/rotate a current-format ratio-sized note.
                                                 val draft = (imageNoteDraft ?: selectedImageNote!!.copyImageNote()).copyImageNote()
                                                val baseFontSizeRatio = draft.fontSizeRatio
                                                val newFontSizeRatio = AnnotationGeometry.accumulateRatio(baseFontSizeRatio, zoom, 0.01f, 0.2f)
                                                 val updatedDraft = draft.copy(
                                                     fontSizeRatio = newFontSizeRatio,
                                                     rotation = draft.rotation + rotation
                                                 )
                                                imageNoteResized = true
                                                if (rotation != 0f) imageNoteRotated = true
                                                 imageNoteDraft = updatedDraft
                                                 selectedImageNote = updatedDraft
                                                imageDocumentChanged = true
                                            } else if (selectedImageShape != null) {
                                                // Pinch to resize/rotate shape
                                                val idx = selectedPhotoPin?.imageShapes?.get(fullScreenImageFile)?.indexOfFirst { it.id == selectedImageShape!!.id } ?: -1
                                                if (idx != -1) {
                                                    val shapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!]!!
                                                    val shapeDraftValue = imageShapeDraft ?: shapes[idx]
                                                    val resolvedShapeSize = AnnotationGeometry.resolveImageSize(
                                                        currentDisplayedWidth, currentDisplayedHeight,
                                                        shapeDraftValue.widthRatio, shapeDraftValue.heightRatio
                                                    )
                                                    val baseWidthRatio = (resolvedShapeSize.width / currentDisplayedWidth).coerceIn(0.01f, 1f)
                                                    val baseHeightRatio = (resolvedShapeSize.height / currentDisplayedHeight).coerceIn(0.01f, 1f)
                                                    val newWidthRatio = AnnotationGeometry.accumulateRatio(baseWidthRatio, zoom)
                                                    val newHeightRatio = AnnotationGeometry.accumulateRatio(baseHeightRatio, zoom)
                                                    val newRotation = shapeDraftValue.rotation + rotation
                                                    val updated = shapeDraftValue.copy(widthRatio = newWidthRatio, heightRatio = newHeightRatio, rotation = newRotation)
                                                    imageShapeDraft = updated
                                                    selectedImageShape = updated
                                                    imageDocumentChanged = true
                                                    resizingImageShape = true
                                                    imageShapeResized = true
                                                    if (rotation != 0f) imageShapeRotated = true
                                                }
                                            } else {
                                                // Zoom/pan image
                                                imageScale = (imageScale * zoom).coerceIn(0.5f, 5f)
                                                val pan = event.calculatePan()
                                                imageOffsetX += pan.x
                                                imageOffsetY += pan.y
                                            }
                                            event.changes.forEach { it.consume() }
                                        } else if (event.changes.size == 1) {
                                            val change = event.changes[0]
                                            if (change.pressed) {
                                                val delta = change.position - change.previousPosition
                                                if (delta.getDistance() > 2f) wasDrag = true
                                                
                                                if (draggingImageNote != null) {
                                                    // Move the note - calculate current displayed size for delta conversion
                                                    val dragFitScale = minOf(size.width.toFloat() / rotatedBmp.width, size.height.toFloat() / rotatedBmp.height)
                                                    val dragDisplayedWidth = rotatedBmp.width * dragFitScale * imageScale
                                                    val dragDisplayedHeight = rotatedBmp.height * dragFitScale * imageScale
                                                     val draft = (imageNoteDraft ?: draggingImageNote!!.copyImageNote()).copyImageNote()
                                                    val accumulated = AnnotationGeometry.accumulateNormalizedDelta(
                                                        com.example.myapplication.stage8.AnnotationPoint(draft.x, draft.y),
                                                        delta.x, delta.y, dragDisplayedWidth, dragDisplayedHeight
                                                    )
                                                     val updatedDraft = draft.copy(x = accumulated.x, y = accumulated.y)
                                                     imageNoteDraft = updatedDraft
                                                     selectedImageNote = updatedDraft
                                                    imageDocumentChanged = true
                                                    imageNoteMoved = true
                                                } else if (draggingImageShape && selectedImageShape != null) {
                                                    // Move the shape
                                                    val dragFitScale = minOf(size.width.toFloat() / rotatedBmp.width, size.height.toFloat() / rotatedBmp.height)
                                                    val dragDisplayedWidth = rotatedBmp.width * dragFitScale * imageScale
                                                    val dragDisplayedHeight = rotatedBmp.height * dragFitScale * imageScale
                                                    val idx = selectedPhotoPin?.imageShapes?.get(fullScreenImageFile)?.indexOfFirst { it.id == selectedImageShape!!.id } ?: -1
                                                    if (idx != -1) {
                                                        val shapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!]!!
                                                        val shapeDraftValue = imageShapeDraft ?: shapes[idx]
                                                        val newX = (shapeDraftValue.x + delta.x / dragDisplayedWidth).coerceIn(0f, 1f)
                                                        val newY = (shapeDraftValue.y + delta.y / dragDisplayedHeight).coerceIn(0f, 1f)
                                                        val updated = shapeDraftValue.copy(x = newX, y = newY)
                                                        imageShapeDraft = updated
                                                        selectedImageShape = updated
                                                        imageDocumentChanged = true
                                                        imageShapeMoved = true
                                                    }
                                                } else {
                                                    // Pan image
                                                    imageOffsetX += delta.x
                                                    imageOffsetY += delta.y
                                                }
                                                change.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    if (latestImageGestureIdentity != gestureIdentity ||
                                        latestImageGestureMode != gestureMode ||
                                        !isSessionCurrent(gestureIdentity.first) ||
                                        !isPageCurrent(gestureIdentity.first, gestureIdentity.second)
                                    ) return@awaitEachGesture
                                     if (!gestureCancelled && imageDocumentChanged) {
                                         if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                            val pinId = gesturePinId
                                            val file = gestureFile
                                            if (originalImageNote != null && selectedImageNote != null) {
                                                val noteKind = when {
                                                    imageNoteRotated -> AnnotationReducer.Kind.ROTATE
                                                    imageNoteResized -> AnnotationReducer.Kind.RESIZE
                                                    imageNoteMoved -> AnnotationReducer.Kind.MOVE
                                                    else -> AnnotationReducer.Kind.UPDATE
                                                }
                                                annotationReducer.updateImageNote(
                                                    gestureIdentity.second, pinId, file,
                                                    originalImageNote!!, selectedImageNote!!, noteKind
                                                )
                                            } else if (originalImageShape != null && selectedImageShape != null) {
                                                val shapeKind = when {
                                                    imageShapeRotated -> AnnotationReducer.Kind.ROTATE
                                                    imageShapeResized -> AnnotationReducer.Kind.RESIZE
                                                    imageShapeMoved -> AnnotationReducer.Kind.MOVE
                                                    else -> AnnotationReducer.Kind.UPDATE
                                                }
                                                annotationReducer.updateImageShape(
                                                    gestureIdentity.second, pinId, file,
                                                    originalImageShape!!, selectedImageShape!!, shapeKind
                                                )
                                            }
                                         }
                                        // Discard previews in favor of the authoritative result,
                                        // including rejected stale or invalid gestures.
                                        selectedImageNote = selectedPhotoPin?.imageNotes?.get(fullScreenImageFile)
                                            ?.firstOrNull { it.id == selectedImageNote?.id }
                                        selectedImageShape = selectedPhotoPin?.imageShapes?.get(fullScreenImageFile)
                                            ?.firstOrNull { it.id == selectedImageShape?.id }
                                        imageNoteDraft = null
                                        imageShapeDraft = null
                                        originalImageNote = null
                                        originalImageShape = null
                                    }

                                    // Handle tap (not drag)
                                     if (!gestureCancelled && !wasDrag && !wasZoom) {
                                        if (imageNoteToolMode == "place") {
                                            // Place new note at tap location - recalculate bounds
                                            val placeFitScale = minOf(size.width.toFloat() / rotatedBmp.width, size.height.toFloat() / rotatedBmp.height)
                                            val placeDisplayedWidth = rotatedBmp.width * placeFitScale * imageScale
                                            val placeDisplayedHeight = rotatedBmp.height * placeFitScale * imageScale
                                            val placeCenterX = size.width / 2f
                                            val placeCenterY = size.height / 2f
                                            val placeImgLeft = placeCenterX + imageOffsetX - placeDisplayedWidth / 2f
                                            val placeImgTop = placeCenterY + imageOffsetY - placeDisplayedHeight / 2f
                                            
                                            if (startPos.x >= placeImgLeft && startPos.x <= placeImgLeft + placeDisplayedWidth &&
                                                startPos.y >= placeImgTop && startPos.y <= placeImgTop + placeDisplayedHeight) {
                                                val relX = (startPos.x - placeImgLeft) / placeDisplayedWidth
                                                val relY = (startPos.y - placeImgTop) / placeDisplayedHeight
                                                currentImageFileName = fullScreenImageFile
                                                // Use DISPLAYED image height (not original bitmap) for ratio calculation
                                                imageNotePos = Offset(relX, relY)
                                                imageNoteInput = ""
                                                imageNoteIsBold = false
                                                editingImageNote = null
                                                showImageNoteDialog = true
                                                imageNoteToolMode = "pan"
                                            }
                                        } else if (imageNoteToolMode == "shape") {
                                            // Place new shape at tap location
                                            val placeFitScale = minOf(size.width.toFloat() / rotatedBmp.width, size.height.toFloat() / rotatedBmp.height)
                                            val placeDisplayedWidth = rotatedBmp.width * placeFitScale * imageScale
                                            val placeDisplayedHeight = rotatedBmp.height * placeFitScale * imageScale
                                            val placeCenterX = size.width / 2f
                                            val placeCenterY = size.height / 2f
                                            val placeImgLeft = placeCenterX + imageOffsetX - placeDisplayedWidth / 2f
                                            val placeImgTop = placeCenterY + imageOffsetY - placeDisplayedHeight / 2f
                                            
                                            if (startPos.x >= placeImgLeft && startPos.x <= placeImgLeft + placeDisplayedWidth &&
                                                startPos.y >= placeImgTop && startPos.y <= placeImgTop + placeDisplayedHeight) {
                                                val relX = (startPos.x - placeImgLeft) / placeDisplayedWidth
                                                val relY = (startPos.y - placeImgTop) / placeDisplayedHeight
                                                
                                                // Use fixed percentage of image height for device independence
                                                // 0.5% of image height is a visible default stroke width
                                                val strokeWidthRatio = 0.005f
                                                
                                                // Different default sizes based on shape type
                                                val (defaultWidthRatio, defaultHeightRatio) = when (currentImageShapeType) {
                                                    ShapeType.ARROW -> Pair(0.20f, 0.08f)  // Arrows are wide and short
                                                    ShapeType.CLOUD -> Pair(0.20f, 0.12f)  // Clouds are wide
                                                    else -> Pair(0.15f, 0.15f)  // Rectangles/circles are square by default
                                                }
                                                
                                                // Create new shape with relative coordinates
                                                val newShape = Shape(
                                                    x = relX,
                                                    y = relY,
                                                     rotation = 0f,
                                                     type = currentImageShapeType,
                                                     colorArgb = android.graphics.Color.RED,
                                                     isFilled = false,
                                                    strokeWidthRatio = strokeWidthRatio,
                                                    widthRatio = defaultWidthRatio,
                                                    heightRatio = defaultHeightRatio
                                                )
                                                
                                                if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                                     if (annotationReducer.addImageShape(pageIndex, selectedPhotoPin!!.id, fullScreenImageFile!!, newShape).changed) {
                                                         selectedImageShape = newShape
                                                     }
                                                }
                                                imageNoteToolMode = "pan"
                                            }
                                        } else if (tappedNote != null) {
                                            // Tapped on existing note - select it
                                                selectedImageNote = tappedNote
                                            selectedImageShape = null
                                        } else if (tappedShape != null) {
                                            // Tapped on existing shape - select it
                                            selectedImageShape = tappedShape
                                            selectedImageNote = null
                                        } else {
                                            // Tapped elsewhere - deselect
                                            selectedImageNote = null
                                            selectedImageShape = null
                                        }
                                    }
                                    


                                    } finally {
                                        // Cancellation and target changes cannot carry a draft
                                        // or remembered drag into the next photo gesture.
                                        draggingImageNote = null
                                        draggingImageShape = false
                                        resizingImageShape = false
                                        imageNoteDraft = null
                                        imageShapeDraft = null
                                        originalImageNote = null
                                        originalImageShape = null
                                        if (latestImageGestureIdentity == gestureIdentity) {
                                            selectedImageNote = selectedPhotoPin?.imageNotes?.get(fullScreenImageFile)
                                                ?.firstOrNull { it.id == selectedImageNote?.id }
                                            selectedImageShape = selectedPhotoPin?.imageShapes?.get(fullScreenImageFile)
                                                ?.firstOrNull { it.id == selectedImageShape?.id }
                                        } else {
                                            selectedImageNote = null
                                            selectedImageShape = null
                                        }
                                    }
                                }
                            }
                    ) {
                        // The image
                        Image(
                            bitmap = rotatedBmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.fullscreen_photo),
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = imageScale,
                                    scaleY = imageScale,
                                    translationX = imageOffsetX,
                                    translationY = imageOffsetY
                                ),
                            contentScale = ContentScale.Fit
                        )
                        
                        // Display image notes as overlays - use Canvas for precise positioning like shapes
                        if (selectedPhotoPin != null && fullScreenImageFile != null) {
                            val imageNotes = selectedPhotoPin!!.imageNotes[fullScreenImageFile!!] ?: emptyList()
                            val currentSelectedNote = selectedImageNote  // Capture for recomposition
                            
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                imageNotes.forEach { originalImageNoteValue ->
                                    val imageNote = if (imageNoteDraft?.id == originalImageNoteValue.id) imageNoteDraft!! else originalImageNoteValue
                                    AnnotationCanvasRendering.drawNote(drawContext.canvas.nativeCanvas, imageNote,
                                        imgLeft, imgTop, displayedImgWidth, displayedImgHeight,
                                        if (imageNote.id == currentSelectedNote?.id) Color.Cyan.toArgb() else Color.Yellow.toArgb())
                                }
                            }
                        }
                        
                        // Draw shapes on image
                        if (selectedPhotoPin != null && fullScreenImageFile != null) {
                            val imageShapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!] ?: emptyList()
                            Canvas(modifier = Modifier.fillMaxSize()) {
                            imageShapes.forEach { originalImageShapeValue ->
                                val shape = if (imageShapeDraft?.id == originalImageShapeValue.id) imageShapeDraft!! else originalImageShapeValue
                                    val shapeCenter = imageToScreenCoords(shape.x, shape.y)
                                    // Use widthRatio/heightRatio for device-independent sizing
                                    val imageShapeSize = AnnotationGeometry.resolveImageSize(
                                        displayedImgWidth, displayedImgHeight,
                                        shape.widthRatio, shape.heightRatio
                                    )
                                    val scaledWidth = imageShapeSize.width
                                    val scaledHeight = imageShapeSize.height
                                    
                                    val strokeWidthPx = shape.strokeWidthRatio * maxOf(displayedImgWidth, displayedImgHeight)
                                    
                                    val shapeColor = if (shape == selectedImageShape) Color.Cyan else Color(shape.colorArgb)
                                    
                                    AnnotationCanvasRendering.drawShape(drawContext.canvas.nativeCanvas,
                                        shape.copy(colorArgb = shapeColor.toArgb()),
                                        imgLeft, imgTop, displayedImgWidth, displayedImgHeight)
                                    rotate(degrees = shape.rotation, pivot = shapeCenter) {

                                        
                                        // Draw selection handles when selected
                                        if (shape == selectedImageShape) {
                                            val handleSize = 16f
                                            val dashedStroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                                            drawRect(
                                                color = Color.Cyan,
                                                topLeft = shapeCenter - Offset(scaledWidth / 2 + 4f, scaledHeight / 2 + 4f),
                                                size = Size(scaledWidth + 8f, scaledHeight + 8f),
                                                style = dashedStroke
                                            )
                                            val corners = listOf(
                                                shapeCenter + Offset(-scaledWidth / 2, -scaledHeight / 2),
                                                shapeCenter + Offset(scaledWidth / 2, -scaledHeight / 2),
                                                shapeCenter + Offset(scaledWidth / 2, scaledHeight / 2),
                                                shapeCenter + Offset(-scaledWidth / 2, scaledHeight / 2)
                                            )
                                            corners.forEach { corner ->
                                                drawRect(color = Color.White, topLeft = corner - Offset(handleSize / 2, handleSize / 2), size = Size(handleSize, handleSize))
                                                drawRect(color = Color.Cyan, topLeft = corner - Offset(handleSize / 2, handleSize / 2), size = Size(handleSize, handleSize), style = Stroke(width = 2f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Toolbar at bottom for selected note (like regular note tool)
                    if (selectedImageNote != null) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Edit button
                            IconButton(
                                onClick = {
                                    editingImageNote = selectedImageNote
                                    currentImageFileName = fullScreenImageFile
                                    imageNoteInput = selectedImageNote!!.text
                                    imageNoteIsBold = selectedImageNote!!.isBold
                                    showImageNoteDialog = true
                                }
                            ) {
                                Icon(Icons.Default.Edit, stringResource(R.string.edit_note), tint = Color.White)
                            }
                            // Delete button
                            IconButton(
                                onClick = {
                                    if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                        val toDelete = selectedImageNote
                                         if (toDelete != null) {
                                             annotationReducer.deleteImageNote(pageIndex, selectedPhotoPin!!.id, fullScreenImageFile!!, toDelete)
                                         }
                                        selectedImageNote = null
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, stringResource(R.string.delete_note), tint = Color.Red)
                            }
                            // Info text
                            Text(stringResource(R.string.fullscreen_gesture_help), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                    
                    // Toolbar for selected shape
                    if (selectedImageShape != null) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Delete button
                            IconButton(
                                onClick = {
                                    if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                        val toDelete = selectedImageShape
                                         if (toDelete != null) {
                                             annotationReducer.deleteImageShape(pageIndex, selectedPhotoPin!!.id, fullScreenImageFile!!, toDelete)
                                         }
                                        selectedImageShape = null
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, stringResource(R.string.delete_shape), tint = Color.Red)
                            }
                            Text(stringResource(R.string.fullscreen_gesture_help), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                    
                    // Add Note/Shape toolbar at bottom when nothing selected
                    if (selectedImageNote == null && selectedImageShape == null) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Shape type selector (when in shape mode)
                            if (imageNoteToolMode == "shape") {
                                Row(
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    ShapeType.entries.forEach { shapeType ->
                                        val icon = when (shapeType) {
                                            ShapeType.RECTANGLE -> Icons.Default.CropSquare
                                            ShapeType.CIRCLE -> Icons.Default.Circle
                                            ShapeType.ARROW -> Icons.AutoMirrored.Filled.ArrowForward
                                            ShapeType.CLOUD -> Icons.Default.Cloud
                                        }
                                        IconButton(
                                            onClick = { currentImageShapeType = shapeType }
                                        ) {
                                            Icon(
                                                icon,
                                                when (shapeType) {
                                                    ShapeType.RECTANGLE -> stringResource(R.string.shape_rectangle)
                                                    ShapeType.CIRCLE -> stringResource(R.string.shape_circle)
                                                    ShapeType.ARROW -> stringResource(R.string.shape_arrow)
                                                    ShapeType.CLOUD -> stringResource(R.string.shape_cloud)
                                                },
                                                tint = if (currentImageShapeType == shapeType) Color.Cyan else Color.White
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            Row(
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Note button - entire row is clickable
                                Row(
                                    modifier = Modifier
                                        .clickable { imageNoteToolMode = "place" }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.StickyNote2, 
                                        stringResource(R.string.add_note),
                                        tint = if (imageNoteToolMode == "place") Color.Cyan else Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(stringResource(R.string.fullscreen_note_label), color = if (imageNoteToolMode == "place") Color.Cyan else Color.White, fontSize = 12.sp)
                                }
                                
                                Spacer(Modifier.width(8.dp))
                                
                                // Shape button - entire row is clickable
                                Row(
                                    modifier = Modifier
                                        .clickable { imageNoteToolMode = "shape" }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Category, 
                                        stringResource(R.string.add_shape),
                                        tint = if (imageNoteToolMode == "shape") Color.Cyan else Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(stringResource(R.string.fullscreen_shape_label), color = if (imageNoteToolMode == "shape") Color.Cyan else Color.White, fontSize = 12.sp)
                                }
                                
                                Spacer(Modifier.width(16.dp))
                                
                                IconButton(
                                    onClick = { 
                                        imageScale = 1f
                                        imageOffsetX = 0f
                                        imageOffsetY = 0f
                                    }
                                ) {
                                    Icon(Icons.Default.CenterFocusStrong, stringResource(R.string.reset_zoom), tint = Color.White)
                                }
                            }
                        }
                    }
                    
                    // Close button in top right
                    IconButton(
                        onClick = { 
                            fullScreenImageFile = null
                            showPinImageGallery = true
                            selectedImageNote = null
                            selectedImageShape = null
                            imageNoteToolMode = "pan"
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
                    }
                    
                    // Mode indicator at top
                    if (imageNoteToolMode == "place") {
                        Text(
                            stringResource(R.string.fullscreen_note_placement),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            color = Color.Cyan
                        )
                    }
                    if (imageNoteToolMode == "shape") {
                        Text(
                            stringResource(
                                R.string.fullscreen_shape_placement,
                                when (currentImageShapeType) {
                                    ShapeType.RECTANGLE -> stringResource(R.string.shape_rectangle)
                                    ShapeType.CIRCLE -> stringResource(R.string.shape_circle)
                                    ShapeType.ARROW -> stringResource(R.string.shape_arrow)
                                    ShapeType.CLOUD -> stringResource(R.string.shape_cloud)
                                }.lowercase()
                            ),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            color = Color.Cyan
                        )
                    }
                }
        }
    }
}
}

/**
 * Draws text using Android's multiline layout while preserving a center
 * anchor.  The caller supplies source-target units; no display density or
 * sampled bitmap size is consulted here.
 */


suspend fun exportPageAsPdf(
    context: Context,
    outputUri: Uri,
    sourceUri: Uri,
    pageIndex: Int,
    paths: List<DrawnPath>,
    measurements: List<Measurement>,
    notes: List<Note>,
    photoPins: List<PhotoPin>,
    shapes: List<Shape> = emptyList(),
    photoSessionToken: DocumentSessionToken,
    stage7Worker: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary()
): Boolean = stage7Worker.withWorker {
    try {
        currentCoroutineContext().ensureActive()
        require(sourceUri.toString() == photoSessionToken.sourceUri) {
            "export source does not match its captured document session"
        }
        val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return@withWorker false
        try {
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(pageIndex)
                try {
        
                    // Get page dimensions
                    val pageWidth = page.width
                    val pageHeight = page.height
        
        SafeDiagnostics.debug(DiagnosticEvent.EXPORT_ACTIVITY)
        
        // Store original page dimensions for photo pages
        val originalPageWidth = page.width
        val originalPageHeight = page.height
        
        val bitmapPlan = BitmapBudgetPolicy.pdfRenderPlan(
            pageWidthPx = pageWidth,
            pageHeightPx = pageHeight,
            scaleFactor = 1
        ) ?: throw IOException("PDF export page exceeds the bitmap budget")
        val bitmapOwner = Stage7ResourceOwner<Bitmap>(::recycleBitmap)
        val bitmap = try {
            // Register immediately after allocation. The owner exists before
            // allocation so a failed registration cannot leak the bitmap.
            bitmapOwner.ownCreated {
                Bitmap.createBitmap(bitmapPlan.width, bitmapPlan.height, Bitmap.Config.ARGB_8888)
            }
        } catch (error: Throwable) {
            bitmapOwner.close()
            throw error
        }
        try {
            val actual = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                BitmapBudgetPolicy.actualAllocationPlan(
                    widthPx = bitmap.width,
                    heightPx = bitmap.height,
                    actualAllocationBytes = actualBitmapAllocationBytes(bitmap)
                )
            } else {
                null
            }
            if (actual == null || bitmap.width != bitmapPlan.width || bitmap.height != bitmapPlan.height) {
                throw IOException("PDF export bitmap allocation exceeds the bitmap budget")
            }
        } catch (error: Throwable) {
            bitmapOwner.close()
            throw error
        }
        try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        
        // Render PDF at original resolution (no matrix = 1:1)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        currentCoroutineContext().ensureActive()
        val exportContext = currentCoroutineContext()
        val markupScaleX = bitmap.width.toFloat() / pageWidth.toFloat()
        val markupScaleY = bitmap.height.toFloat() / pageHeight.toFloat()
        // PdfRenderer scales the page to the bounded bitmap. Apply the same
        // explicit transform to page-unit markup so it stays aligned when a
        // large export page is reduced.
        canvas.save()
        canvas.scale(markupScaleX, markupScaleY)
        
        val paint = android.graphics.Paint().apply { isAntiAlias = true }
        
        // Draw all markups from normalized visible-surface coordinates. The
        // canvas scale only maps source PDF points to the bounded export bitmap.
        val exportMaxDim = maxOf(pageWidth, pageHeight).toFloat()
        paths.forEach { pathData ->
            exportContext.ensureActive()
            if (pathData.points.size > 1) {
                paint.color = pathData.colorArgb
                paint.strokeWidth = pathData.strokeWidthRatio * exportMaxDim
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.strokeJoin = android.graphics.Paint.Join.ROUND
                if (pathData.isHighlighter) paint.alpha = 100
                
                val path = android.graphics.Path()
                path.moveTo(pathData.points[0].x * pageWidth, pathData.points[0].y * pageHeight)
                for (i in 1 until pathData.points.size) path.lineTo(pathData.points[i].x * pageWidth, pathData.points[i].y * pageHeight)
                canvas.drawPath(path, paint)
            }
        }
        
        measurements.forEach { m ->
            exportContext.ensureActive()
            paint.color = 0xFFE91E63.toInt()
            paint.strokeWidth = (0.003f * exportMaxDim).coerceAtLeast(1f)
            paint.alpha = 255
            val m1x = m.p1.x * pageWidth; val m1y = m.p1.y * pageHeight
            val m2x = m.p2.x * pageWidth; val m2y = m.p2.y * pageHeight
            canvas.drawLine(m1x, m1y, m2x, m2y, paint)
            val endpointRadius = (0.006f * exportMaxDim).coerceAtLeast(1f)
            canvas.drawCircle(m1x, m1y, endpointRadius, paint)
            canvas.drawCircle(m2x, m2y, endpointRadius, paint)
            
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = (0.02f * pageHeight).coerceAtLeast(1f)
                isFakeBoldText = true
            }
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                alpha = 180
            }
            
            val textWidth = textPaint.measureText(m.text)
            val fontMetrics = textPaint.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top
            val midX = (m1x + m2x) / 2
            val midY = (m1y + m2y) / 2
            
            val padding = (0.01f * pageHeight).coerceAtLeast(1f)
            canvas.drawRect(midX - textWidth / 2 - padding, midY - textHeight / 2 - padding/2, midX + textWidth / 2 + padding, midY + textHeight / 2 + padding/2, bgPaint)
            canvas.drawText(m.text, midX - textWidth / 2, midY - (fontMetrics.ascent + fontMetrics.descent) / 2, textPaint)
        }

        notes.forEach { n ->
            exportContext.ensureActive()
            AnnotationCanvasRendering.drawNote(canvas, n, 0f, 0f,
                pageWidth.toFloat(), pageHeight.toFloat(), android.graphics.Color.BLACK)
        }
        
        // Draw photo pins with pin numbers
        photoPins.forEachIndexed { pinIndex, pin ->
            exportContext.ensureActive()
            val pinRadius = (0.0125f * exportMaxDim).coerceAtLeast(2f)
            val iconPaint = android.graphics.Paint().apply {
                color = 0xFF4CAF50.toInt()
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val borderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (0.0015f * exportMaxDim).coerceAtLeast(1f)
                isAntiAlias = true
            }
            
            // Draw circle for photo pin icon
            val pinX = pin.x * pageWidth; val pinY = pin.y * pageHeight
            canvas.drawCircle(pinX, pinY, pinRadius, iconPaint)
            canvas.drawCircle(pinX, pinY, pinRadius, borderPaint)
            
            // Draw pin number inside circle
            val numberPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = (0.012f * pageHeight).coerceAtLeast(1f)
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val numberMetrics = numberPaint.fontMetrics
            canvas.drawText("${pinIndex + 1}", pinX, pinY - (numberMetrics.ascent + numberMetrics.descent) / 2, numberPaint)
        }
        
        // Draw shapes using ratio-based dimensions
        // Shapes store ratios relative to page size - draw at actual page dimensions
        val pageMaxDim = maxOf(pageWidth, pageHeight).toFloat()
        
        shapes.forEach { shape ->
            exportContext.ensureActive()
            AnnotationCanvasRendering.drawShape(canvas, shape, 0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
        }
        canvas.restore()
        
        // Create PDF document
        val pdfDocument = PdfDocument()
        try {
        
        // Page 1: Blueprint with markups
        val blueprintPageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
        val blueprintPage = pdfDocument.startPage(blueprintPageInfo)
        try {
            exportContext.ensureActive()
            blueprintPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            pdfDocument.finishPage(blueprintPage)
        }
        
        // Use ORIGINAL (unscaled) page dimensions for photo pages
        // This ensures content appears at correct size when PDF is viewed/printed
        val photoPageWidth = originalPageWidth
        val photoPageHeight = originalPageHeight
        val margin = 20
        val contentWidth = photoPageWidth - (margin * 2)
        val contentHeight = photoPageHeight - (margin * 2)
        
                // Add pages for each pin's photos
        var pdfPageNumber = 2
        photoPins.forEachIndexed { pinIndex, pin ->
            exportContext.ensureActive()
            if (pin.imageFileNames.isNotEmpty()) {
                val pageInfo = PdfDocument.PageInfo.Builder(photoPageWidth, photoPageHeight, pdfPageNumber).create()
                val photoPage = pdfDocument.startPage(pageInfo)
                try {
                exportContext.ensureActive()
                val photoCanvas = photoPage.canvas
                photoCanvas.drawColor(android.graphics.Color.WHITE)
                
                // Draw header - scale text size based on page size
                val headerTextSize = (photoPageHeight / 30f).coerceIn(18f, 36f)
                val headerPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = headerTextSize
                    isFakeBoldText = true
                }
                val headerY = margin + headerTextSize
                photoCanvas.drawText("Pin ${pinIndex + 1} - Photos", margin.toFloat(), headerY, headerPaint)
                
                // Calculate layout for images
                val imageCount = pin.imageFileNames.size
                val imagesPerRow = if (imageCount <= 1) 1 else 2
                val rows = (imageCount + imagesPerRow - 1) / imagesPerRow
                val imageWidth = (contentWidth - (if (imagesPerRow > 1) 10 else 0)) / imagesPerRow
                val availableHeight = contentHeight - (headerTextSize + margin)
                val maxImageHeight = (availableHeight / rows - 10).toInt()
                
                var currentY = headerY + margin
                var currentX = margin.toFloat()
                var imagesInRow = 0
                
                pin.imageFileNames.forEachIndexed { imgIndex, fileName ->
                    exportContext.ensureActive()
                    val photoBytes = try {
                        photoBytesFor(context, photoSessionToken, fileName)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw IOException("Required photo bytes are unavailable: $fileName", error)
                    }
                    if (photoBytes == null) {
                        throw IOException("Required photo bytes are unavailable: $fileName")
                    }
                    val photoOwner = decodePhotoBitmapWithExif(
                        photoBytes = photoBytes,
                        viewportWidthPx = imageWidth,
                        viewportHeightPx = maxImageHeight
                    ) ?: throw IOException("Required photo could not be decoded within the bitmap budget: $fileName")
                    try {
                        val rotatedBitmap = photoOwner.value
                        val fitScale = minOf(
                            imageWidth.toDouble() / rotatedBitmap.width.toDouble(),
                            maxImageHeight.toDouble() / rotatedBitmap.height.toDouble()
                        )
                        if (!fitScale.isFinite() || fitScale <= 0.0 || imageWidth <= 0 || maxImageHeight <= 0) {
                            throw IOException("Photo export layout is invalid: $fileName")
                        }
                        val imgWidth = (rotatedBitmap.width.toDouble() * fitScale)
                            .toLong()
                            .coerceIn(1L, imageWidth.toLong())
                            .toInt()
                        val imgHeight = (rotatedBitmap.height.toDouble() * fitScale)
                            .toLong()
                            .coerceIn(1L, maxImageHeight.toLong())
                            .toInt()

                        // Draw directly into the PDF canvas. This preserves
                        // the existing relative annotation coordinates while
                        // avoiding a second full-sized scaled bitmap.
                        photoCanvas.drawBitmap(
                            rotatedBitmap,
                            null,
                            RectF(
                                currentX,
                                currentY,
                                currentX + imgWidth,
                                currentY + imgHeight
                            ),
                            null
                        )
                        exportContext.ensureActive()
                                
                                // Draw any notes on the image
                                val imageNotes = pin.imageNotes[fileName]
                                if (imageNotes != null) {
                                    imageNotes.forEach { note ->
                                        AnnotationCanvasRendering.drawNote(photoCanvas, note, currentX, currentY,
                                            imgWidth.toFloat(), imgHeight.toFloat(), android.graphics.Color.YELLOW)
                                    }
                                }
                                
                                // Draw any shapes on the image
                                val imageShapes = pin.imageShapes[fileName]
                                if (imageShapes != null) {
                                    imageShapes.forEach { shape ->
                                        AnnotationCanvasRendering.drawShape(photoCanvas, shape, currentX, currentY,
                                            imgWidth.toFloat(), imgHeight.toFloat())
                                    }
                                }
                                
                                imagesInRow++
                                if (imagesInRow >= imagesPerRow) {
                                    imagesInRow = 0
                                    currentX = margin.toFloat()
                                    currentY += maxImageHeight + 10
                                } else {
                                    currentX += imageWidth + 10
                                }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        throw IOException("Required photo export failed: $fileName", e)
                        } finally {
                            photoOwner.close()
                        }
                }
                
                } finally {
                    pdfDocument.finishPage(photoPage)
                }
                pdfPageNumber++
            } else {
                Unit
            }
        }
        
        // Write PDF to output stream
        exportContext.ensureActive()
        val output = context.contentResolver.openOutputStream(outputUri)
            ?: return@withWorker false
        output.use { out ->
            exportContext.ensureActive()
            pdfDocument.writeTo(out)
        }
        true
        } finally {
            pdfDocument.close()
        }
        } finally {
            bitmapOwner.close()
        }
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        SafeDiagnostics.error(DiagnosticEvent.EXPORT_ACTIVITY, error = e)
        false
    }
}

fun dist(p1: Point, p2: Point) = sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
fun distToSegment(p: Point, a: Point, b: Point): Float {
    val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
    if (l2 == 0f) return sqrt((p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y))
    var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2; t = t.coerceIn(0f, 1f)
    return sqrt((p.x - (a.x + t * dx)) * (p.x - (a.x + t * dx)) + (p.y - (a.y + t * dy)) * (p.y - (a.y + t * dy)))
}

fun formatFeet(feet: Float): String { val f = feet.toInt(); val i = ((feet - f) * 12).toInt(); return if (f > 0) "$f' $i\"" else "$i\"" }
private fun getThumbCacheFile(
    context: Context,
    index: Int,
    cacheIdentity: String
): File {
    val key = MessageDigest.getInstance("SHA-256")
        .digest(cacheIdentity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return File(File(context.cacheDir, "thumbs/$key").apply { if (!exists()) mkdirs() }, "p_$index.jpg")
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
