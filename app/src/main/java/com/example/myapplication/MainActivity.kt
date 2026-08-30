package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfDocument
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import android.util.Log
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
import com.example.myapplication.stage1.snapshotFromLegacyPageData
import com.example.myapplication.stage2.AndroidLegacyPersistenceSource
import com.example.myapplication.stage2.DocumentDurableSnapshotState
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.LegacyMigrationResult
import com.example.myapplication.stage2.fingerprintContentUri
import com.example.myapplication.stage2.migrateLegacy
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage3.AndroidDocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.DocumentWorkToken
import com.example.myapplication.stage3.SessionSnapshotApplyResult
import com.example.myapplication.stage3.restoreAlreadyActiveSession
import com.example.myapplication.stage3.SwitchFailure
import com.example.myapplication.stage3.SwitchFailureStage
import com.example.myapplication.stage3.SwitchResult
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
import com.example.myapplication.stage4.runSyncCoordinatorLifecycleFinalizer
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.readBoundedUtf8
import com.example.myapplication.stage5.readReferencedPhotos
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validatePhotoSet
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

// Use a local debug flag to gate temporary diagnostic logs
private const val DEBUG_LOG = false

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize pdfbox-android resource loader so bundled glyphlist/resources are available
        try { PDFBoxResourceLoader.init(applicationContext) } catch (t: Throwable) { Log.e("Blueprint", "PDFBoxResourceLoader.init failed", t) }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BlueprintApp()
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

data class PageScale(val pixelsPerFoot: Float) : Serializable
data class RecentFile(val uri: String, val name: String) : Serializable

data class Point(var x: Float, var y: Float) : Serializable {
    fun copyPoint() = Point(x, y)
}
data class DrawnPath(
    val points: List<Point>,
    val colorArgb: Int,
    val strokeWidth: Float,
    val isHighlighter: Boolean
) : Serializable

data class Measurement(
    val p1: Point,
    val p2: Point,
    var text: String
) : Serializable {
    fun copyMeasurement(p1: Point = this.p1, p2: Point = this.p2, text: String = this.text) = Measurement(p1, p2, text)
}

data class Note(
    var x: Float,
    var y: Float,
    var text: String,
    var fontSize: Float = 16f,
    var isBold: Boolean = false,
    var rotation: Float = 0f
) : Serializable {
    fun copyNote() = Note(x, y, text, fontSize, isBold, rotation)
}

data class Shape(
    var x: Float,           // Center X position (in page/bitmap coordinates)
    var y: Float,           // Center Y position (in page/bitmap coordinates)
    var width: Float,       // Legacy: Width in screen pixels (for backwards compat)
    var height: Float,      // Legacy: Height in screen pixels (for backwards compat)
    var rotation: Float,    // Rotation in degrees
    val type: ShapeType,
    val colorArgb: Int,
    val strokeWidth: Float, // Legacy: absolute dp value (for backwards compat)
    val isFilled: Boolean = false,
    val strokeWidthRatio: Float = 0.005f, // Stroke width as ratio of page max dimension
    val widthRatio: Float = 0f,  // Width as ratio of page width (0 = use legacy width)
    val heightRatio: Float = 0f, // Height as ratio of page height (0 = use legacy height)
    val id: String = java.util.UUID.randomUUID().toString()
) : Serializable {
    fun copyShape() = Shape(x, y, width, height, rotation, type, colorArgb, strokeWidth, isFilled, strokeWidthRatio, widthRatio, heightRatio, id)
}

data class PhotoPin(
    var x: Float,
    var y: Float,
    val id: String = java.util.UUID.randomUUID().toString(),
    val imageFileNames: MutableList<String> = mutableListOf(),
    val imageNotes: MutableMap<String, MutableList<PhotoImageNote>> = mutableMapOf(),
    val imageShapes: MutableMap<String, MutableList<Shape>> = mutableMapOf()
) : Serializable {
    fun copyPin() = PhotoPin(x, y, id, imageFileNames.toMutableList(), imageNotes.mapValues { it.value.toMutableList() }.toMutableMap(), imageShapes.mapValues { it.value.map { s -> s.copy() }.toMutableList() }.toMutableMap())
}

data class PhotoImageNote(
    var x: Float, // Position relative to image (0.0 to 1.0)
    var y: Float, // Position relative to image (0.0 to 1.0)
    var text: String,
    var fontSize: Float = 16f, // Legacy: absolute sp value (for backwards compat)
    var isBold: Boolean = false,
    var rotation: Float = 0f,
    var fontSizeRatio: Float = 0f, // Font size as ratio of original image height (0.0 to 1.0)
    val id: String = java.util.UUID.randomUUID().toString()
) : Serializable {
    fun copyImageNote() = PhotoImageNote(x, y, text, fontSize, isBold, rotation, fontSizeRatio, id)
}

private fun photoBytesFor(
    context: Context,
    sessionToken: DocumentSessionToken?,
    reference: String
): ByteArray? {
    validatePhotoFileName(reference)
    val documentId = sessionToken?.documentId ?: return null
    return DocumentPhotoAssetStore(context.filesDir, documentId).use { store ->
        runCatching {
            if (store.resolveForRead(reference) == null) {
                // Explicit compatibility claim only; the legacy global file is
                // never returned or consumed as the active document's asset.
                store.migrateLegacyPhoto(reference, context.filesDir)
            }
            store.read(reference)
        }.getOrNull()
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
    data class Legacy(val pageData: Map<Int, PageData>) : ParsedSaveFile
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

sealed class HistoryAction : Serializable {
    data class AddPath(val path: DrawnPath) : HistoryAction()
    data class AddMeasurement(val measurement: Measurement) : HistoryAction()
    data class AddNote(val note: Note) : HistoryAction()
    data class AddPhotoPin(val pin: PhotoPin) : HistoryAction()
    data class AddShape(val shape: Shape) : HistoryAction()
    data class DeletePath(val path: DrawnPath) : HistoryAction()
    data class DeleteMeasurement(val measurement: Measurement) : HistoryAction()
    data class DeleteNote(val note: Note) : HistoryAction()
    data class DeletePhotoPin(val pin: PhotoPin) : HistoryAction()
    data class DeleteShape(val shape: Shape) : HistoryAction()
    data class UpdateMeasurement(val old: Measurement, val new: Measurement) : HistoryAction()
    data class UpdateNote(val old: Note, val new: Note) : HistoryAction()
    data class UpdateShape(val old: Shape, val new: Shape) : HistoryAction()
}

sealed class PageItem {
    data class Path(val data: DrawnPath) : PageItem()
    data class Measure(val data: Measurement) : PageItem()
    data class NoteItem(val data: Note) : PageItem()
    data class PhotoPinItem(val data: PhotoPin) : PageItem()
    data class ShapeItem(val data: Shape) : PageItem()
}

class BlueprintViewModel : ViewModel() {
    val pageScales = mutableStateMapOf<Int, PageScale>()
    val pagePaths = mutableStateMapOf<Int, SnapshotStateList<DrawnPath>>()
    val pageMeasurements = mutableStateMapOf<Int, SnapshotStateList<Measurement>>()
    val pageNotes = mutableStateMapOf<Int, SnapshotStateList<Note>>()
    val pagePhotoPins = mutableStateMapOf<Int, SnapshotStateList<PhotoPin>>()
    val pageShapes = mutableStateMapOf<Int, SnapshotStateList<Shape>>()
    val pageHistory = mutableStateMapOf<Int, MutableList<HistoryAction>>()
    val pageRedoStack = mutableStateMapOf<Int, MutableList<HistoryAction>>()
    // Memory thumbnails are keyed by verified source identity and page, not
    // by page index alone; a stale A thumbnail must never appear for B.
    val thumbnailCache = mutableStateMapOf<String, Bitmap>()
    // Search highlights per page (survives rotation)
    val pageHighlights = mutableStateMapOf<Int, List<RectF>>()
    val pageSearchTerms = mutableStateMapOf<Int, String>()
    
    fun clearSession() {
        pageScales.clear()
        pagePaths.clear()
        pageMeasurements.clear()
        pageNotes.clear()
        pagePhotoPins.clear()
        pageShapes.clear()
        pageHistory.clear()
        pageRedoStack.clear()
        thumbnailCache.clear()
        pageHighlights.clear()
        pageSearchTerms.clear()
    }

    fun clearPageMarkups(index: Int) {
        pagePaths[index]?.clear()
        pageMeasurements[index]?.clear()
        pageNotes[index]?.clear()
        pagePhotoPins[index]?.clear()
        pageShapes[index]?.clear()
        pageHistory[index]?.clear()
        pageRedoStack[index]?.clear()
    }

    fun addAction(index: Int, action: HistoryAction) {
        pageHistory.getOrPut(index) { mutableListOf() }.add(action)
        pageRedoStack[index]?.clear()
    }

    fun undo(index: Int) {
        val history = pageHistory[index] ?: return
        if (history.isEmpty()) return
        val action = history.removeAt(history.size - 1)
        pageRedoStack.getOrPut(index) { mutableListOf() }.add(action)
        
        when (action) {
            is HistoryAction.AddPath -> pagePaths[index]?.remove(action.path)
            is HistoryAction.AddMeasurement -> pageMeasurements[index]?.remove(action.measurement)
            is HistoryAction.AddNote -> pageNotes[index]?.remove(action.note)
            is HistoryAction.AddPhotoPin -> pagePhotoPins[index]?.remove(action.pin)
            is HistoryAction.AddShape -> pageShapes[index]?.remove(action.shape)
            is HistoryAction.DeletePath -> pagePaths[index]?.add(action.path)
            is HistoryAction.DeleteMeasurement -> pageMeasurements[index]?.add(action.measurement)
            is HistoryAction.DeleteNote -> pageNotes[index]?.add(action.note)
            is HistoryAction.DeletePhotoPin -> pagePhotoPins[index]?.add(action.pin)
            is HistoryAction.DeleteShape -> pageShapes[index]?.add(action.shape)
            is HistoryAction.UpdateMeasurement -> {
                val list = pageMeasurements[index]
                val idx = list?.indexOf(action.new) ?: -1
                if (idx != -1) list!![idx] = action.old
            }
            is HistoryAction.UpdateNote -> {
                val list = pageNotes[index]
                val idx = list?.indexOf(action.new) ?: -1
                if (idx != -1) list!![idx] = action.old
            }
            is HistoryAction.UpdateShape -> {
                val list = pageShapes[index]
                val idx = list?.indexOfFirst { it.id == action.new.id } ?: -1
                if (idx != -1) list!![idx] = action.old
            }
        }
    }

    fun redo(index: Int) {
        val redoStack = pageRedoStack[index] ?: return
        if (redoStack.isEmpty()) return
        val action = redoStack.removeAt(redoStack.size - 1)
        pageHistory.getOrPut(index) { mutableListOf() }.add(action)
        
        when (action) {
            is HistoryAction.AddPath -> pagePaths[index]?.add(action.path)
            is HistoryAction.AddMeasurement -> pageMeasurements[index]?.add(action.measurement)
            is HistoryAction.AddNote -> pageNotes[index]?.add(action.note)
            is HistoryAction.AddPhotoPin -> pagePhotoPins[index]?.add(action.pin)
            is HistoryAction.AddShape -> pageShapes[index]?.add(action.shape)
            is HistoryAction.DeletePath -> pagePaths[index]?.remove(action.path)
            is HistoryAction.DeleteMeasurement -> pageMeasurements[index]?.remove(action.measurement)
            is HistoryAction.DeleteNote -> pageNotes[index]?.remove(action.note)
            is HistoryAction.DeletePhotoPin -> pagePhotoPins[index]?.remove(action.pin)
            is HistoryAction.DeleteShape -> pageShapes[index]?.remove(action.shape)
            is HistoryAction.UpdateMeasurement -> {
                val list = pageMeasurements[index]
                val idx = list?.indexOf(action.old) ?: -1
                if (idx != -1) list!![idx] = action.new
            }
            is HistoryAction.UpdateNote -> {
                val list = pageNotes[index]
                val idx = list?.indexOf(action.old) ?: -1
                if (idx != -1) list!![idx] = action.new
            }
            is HistoryAction.UpdateShape -> {
                val list = pageShapes[index]
                val idx = list?.indexOfFirst { it.id == action.old.id } ?: -1
                if (idx != -1) list!![idx] = action.new
            }
        }
    }
    
    fun canUndo(index: Int) = (pageHistory[index]?.size ?: 0) > 0
    fun canRedo(index: Int) = (pageRedoStack[index]?.size ?: 0) > 0

    fun deleteItem(index: Int, item: PageItem) {
        when (item) {
            is PageItem.Path -> {
                pagePaths[index]?.remove(item.data)
                addAction(index, HistoryAction.DeletePath(item.data))
            }
            is PageItem.Measure -> {
                pageMeasurements[index]?.remove(item.data)
                addAction(index, HistoryAction.DeleteMeasurement(item.data))
            }
            is PageItem.NoteItem -> {
                pageNotes[index]?.remove(item.data)
                addAction(index, HistoryAction.DeleteNote(item.data))
            }
            is PageItem.PhotoPinItem -> {
                pagePhotoPins[index]?.remove(item.data)
                addAction(index, HistoryAction.DeletePhotoPin(item.data))
            }
            is PageItem.ShapeItem -> {
                pageShapes[index]?.remove(item.data)
                addAction(index, HistoryAction.DeleteShape(item.data))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlueprintApp(vm: BlueprintViewModel = viewModel()) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val localDocumentRepository = remember(context) { LocalDocumentRepository(context) }
    val legacyPersistenceSource = remember(context) { AndroidLegacyPersistenceSource(context) }
    val documentBundleService = remember(context) {
        DocumentBundleService(stagingDirectory = context.cacheDir)
    }
    
    var pdfUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var currentScreen by rememberSaveable { mutableStateOf(Screen.SELECTOR) }
    var selectedPageIndex by rememberSaveable { mutableIntStateOf(0) }
    var totalPageCount by rememberSaveable { mutableIntStateOf(0) }
    var toolMode by rememberSaveable { mutableStateOf(ToolMode.PAN) }
    var showToolMenu by remember { mutableStateOf(false) }
    
    var recentFiles by remember { mutableStateOf(getRecentFiles(context)) }
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

    // Create a single PdfSearchEngine instance scoped to this Composable.  Reusing the
    // engine ensures OCR caches persist across searches and avoids repeatedly loading
    // native libraries.  We also track search progress so the UI can show feedback.
    val pdfSearchEngine = remember { PdfSearchEngine(context) }
    val ocrIndex = remember { OcrIndex(context) }
    var searching by remember { mutableStateOf(false) }
    var searchDone by remember { mutableIntStateOf(0) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var ocrCachingProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }  // (done, total)
    
    // Google Drive sync state
    val driveSyncManager = remember { DriveSyncManager(context) }
    val syncMetadataStore = remember(context) { FileSyncMetadataStore(context) }
    val syncGateway = remember(driveSyncManager) {
        DynamicDriveGateway { driveSyncManager.stage4Gateway() }
    }
    var isSignedIn by remember { mutableStateOf(driveSyncManager.isSignedIn()) }
    var signedInAccountId by remember { mutableStateOf(driveSyncManager.getSignedInEmail()) }
    var backupFolderName by remember { mutableStateOf(driveSyncManager.getBackupFolderName()) }
    var backupFolderId by remember { mutableStateOf(driveSyncManager.getBackupFolderIdForSync()) }
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
    var showFolderBrowser by remember { mutableStateOf(false) }
    var browseFolders by remember { mutableStateOf<List<DriveSyncManager.DriveFolder>>(emptyList()) }
    var currentBrowseFolderId by remember { mutableStateOf("root") }
    var currentBrowseFolderName by remember { mutableStateOf("My Drive") }
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
    var readySessionToken by remember { mutableStateOf<DocumentSessionToken?>(null) }
    var activeSyncBinding by remember { mutableStateOf<SyncBinding?>(null) }
    // One shared per-document barrier is the cross-stage transaction boundary
    // for switching/autosave and remote acceptance.
    val documentTransactionBarrier = remember { DocumentTransactionBarrier() }
    var coordinatorRef: DocumentSwitchCoordinator? = null
    var syncCoordinatorRef: SyncCoordinator? = null

    val startDocumentBackgroundWork: (DocumentSession) -> Unit = { session ->
        readySessionToken = session.token
        if (isSignedIn && !signedInAccountId.isNullOrBlank() && !backupFolderId.isNullOrBlank()) {
            syncCoordinatorRef?.updateCurrentScope(
                SyncScope(signedInAccountId!!, backupFolderId!!, session.token.documentId)
            )
        } else {
            syncCoordinatorRef?.updateCurrentScope(null)
        }
        val coordinator = coordinatorRef
        if (coordinator != null) {
            val uri = session.token.sourceUri.toUri()
            coordinator.launchDocumentJob(session.token) {
                try {
                    ocrIndex.preCacheDocument(uri, session.token.sourceCacheKey) { done, total ->
                        if (coordinator.isCurrent(session.token)) {
                            ocrCachingProgress = done to total
                        }
                    }
                } finally {
                    if (coordinator.isCurrent(session.token)) ocrCachingProgress = null
                }
            }
        }
    }

    val documentCallbacks = remember(
        vm,
        context,
        localDocumentRepository,
        legacyPersistenceSource,
        syncMetadataStore,
        isSignedIn,
        signedInAccountId,
        backupFolderId
    ) {
        AndroidDocumentSessionCallbacks.withDefaultPageLoader(
            context = context,
            viewModel = vm,
            repository = localDocumentRepository,
            legacySource = legacyPersistenceSource,
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
                searching = false
                ocrCachingProgress = null
            },
            onPageCount = { session, count ->
                if (coordinatorRef?.isCurrent(session.token) == true) {
                    totalPageCount = count
                }
            },
            onRecovered = {
                Toast.makeText(context, "Recovered the previous complete local snapshot.", Toast.LENGTH_LONG).show()
            },
            onFailure = { failure ->
                scope.launch(Dispatchers.Main.immediate) {
                    Log.e("Blueprint", "Document switch failed: ${failure.stage}: ${failure.detail}", failure.cause)
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
            cancelAndJoinWork = { session ->
                // The Stage 4 coordinator owns all Drive work. Switching
                // fences the exact binding synchronously, then cancels and
                // joins its worker before the Stage 3 token is replaced.
                syncCoordinatorRef?.cancelForSessionAndJoin(session.token)
            },
            resumeWork = startDocumentBackgroundWork,
            photoRecoveryMetadataIdentity = { association ->
                val accountId = signedInAccountId
                val rootId = backupFolderId
                if (!isSignedIn || accountId.isNullOrBlank() || rootId.isNullOrBlank()) {
                    null
                } else {
                    val metadataScope = SyncScope(accountId, rootId, association.documentId)
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
    val sessionCoordinator = remember(documentCallbacks, scope, documentTransactionBarrier) {
        DocumentSwitchCoordinator(
            callbacks = documentCallbacks,
            parentScope = scope,
            coordinatorDispatcher = Dispatchers.Main.immediate,
            transactionBarrier = documentTransactionBarrier
        )
    }
    coordinatorRef = sessionCoordinator

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
        if (!isSignedIn || signedInAccountId.isNullOrBlank() || backupFolderId.isNullOrBlank() || session == null) {
            return null
        }
        if (readySessionToken != session.token || !sessionCoordinator.isCurrentApplied(session.token)) {
            return null
        }
        return SyncScope(signedInAccountId!!, backupFolderId!!, session.token.documentId)
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
        acceptedSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
    ) {
        validateSnapshot(acceptedSnapshot)
        val currentDurableSnapshot = when (val loaded = localDocumentRepository.load(session.target.association)) {
            is DocumentLoadResult.Loaded -> loaded.snapshot
            DocumentLoadResult.NotFound -> throw PhotoCanonicalRecoveryException(
                "durable snapshot disappeared before post-commit photo cleanup"
            )
            is DocumentLoadResult.Failed -> throw PhotoCanonicalRecoveryException(
                "durable snapshot could not be read before post-commit photo cleanup",
                IllegalStateException(loaded.error.toString())
            )
        }
        val currentLiveSnapshot = sessionCoordinator.captureCurrentSnapshotWithinDocumentTransaction(session.token)
            ?: throw PhotoCanonicalRecoveryException(
                "live snapshot became unavailable before post-commit photo cleanup"
            )
        validateSnapshot(currentDurableSnapshot)
        validateSnapshot(currentLiveSnapshot)
        DocumentPhotoAssetStore(context.filesDir, session.token.documentId).use { store ->
            store.cleanupAfterCanonicalCommit(currentDurableSnapshot, currentLiveSnapshot)
        }
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
            ): Map<String, ByteArray> = withContext(Dispatchers.IO) {
                DocumentPhotoAssetStore(context.filesDir, session.token.documentId).use { store ->
                    store.reconcilePhotoContent(currentDurableSnapshot, currentLiveSnapshot)
                    store.readPhotoContentForAdmission(snapshot = currentLiveSnapshot)
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
                Log.e("Blueprint", "Drive synchronization failed for ${binding.scope.documentId}: ${error.detail}", error.cause)
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
    
    // Try to restore Google Sign-In session on launch
    LaunchedEffect(Unit) {
        isSignedIn = driveSyncManager.tryRestoreSession()
        signedInAccountId = driveSyncManager.getSignedInEmail()
        backupFolderId = driveSyncManager.getBackupFolderIdForSync()
        backupFolderName = driveSyncManager.getBackupFolderName()
    }

    LaunchedEffect(activeSessionToken, readySessionToken, isSignedIn, signedInAccountId, backupFolderId) {
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
    
    // Google Sign-In launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("GoogleSignIn", "Result code: ${result.resultCode}")
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                Log.d("GoogleSignIn", "Account: ${account.email}")
                syncCoordinator.invalidateCurrentScope()
                driveSyncManager.initializeDriveService(account)
                isSignedIn = true
                signedInAccountId = account.email
                Toast.makeText(context, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Log.e("GoogleSignIn", "Sign in failed with code: ${e.statusCode}", e)
                Toast.makeText(context, "Sign in failed (code ${e.statusCode}): ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.d("GoogleSignIn", "Sign in cancelled or failed")
            Toast.makeText(context, "Sign in cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Process restoration re-enters the same coordinator path. There is no
    // second load owner keyed directly to pdfUri; an already established token
    // makes this a no-op after a normal selection.
    LaunchedEffect(pdfUri) {
        val restoredUri = pdfUri ?: return@LaunchedEffect
        if (sessionCoordinator.currentSession() == null) {
            sessionCoordinator.switchTo(restoredUri.toString())
        }
    }

    // Save markups when app is backgrounded or stopped
    // Drive work is owned by the lifecycle-scoped Stage 4 coordinator.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, pdfUri, activeSyncBinding) {
        val bindingForObserver = activeSyncBinding
        val sessionForObserver = sessionCoordinator.currentSession()
        val tokenForObserver = sessionForObserver?.token
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Keep the local Stage 3 flush alive through composition
                // disposal. Remote upload is still conditional on its typed
                // Saved result below; NonCancellable does not turn failure
                // or cancellation into success.
                scope.launch(NonCancellable) {
                    val token = tokenForObserver ?: return@launch
                    if (!sessionCoordinator.isCurrentApplied(token)) return@launch
                    // Local durability is unconditional. Drive is only a
                    // second step after the actual Stage 3 flush succeeds.
                    when (val flushed = sessionCoordinator.flushCurrent()) {
                        is DocumentSaveResult.Saved -> {
                            val binding = bindingForObserver?.takeIf {
                                it.token == token && currentSyncBinding(sessionForObserver) == it
                            }
                            if (binding != null) {
                                val outcome = syncCoordinator.enqueueUpload(binding, SyncReason.LIFECYCLE).await()
                                if (outcome is SyncOutcome.Failed) {
                                    Log.e("Blueprint", "Lifecycle synchronization failed: ${outcome.error.detail}")
                                }
                            }
                        }
                        is DocumentSaveResult.Failed -> {
                            Log.e("Blueprint", "Lifecycle local flush failed: ${flushed.error}")
                        }
                        null -> Unit
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(syncCoordinator, sessionCoordinator) {
        try {
            awaitCancellation()
        } finally {
            runSyncCoordinatorLifecycleFinalizer(syncCoordinator) {
                sessionCoordinator.close()
            }
        }
    }

    val onPdfSelected: (Uri) -> Unit = { uri ->
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = getFileName(context, uri)
            saveRecentFile(context, uri.toString(), name)
            recentFiles = getRecentFiles(context)
            scope.launch {
                val result = sessionCoordinator.switchTo(uri.toString())
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
            Log.e("Blueprint", "Failed to request document switch", e)
            Toast.makeText(context, "The PDF could not be opened.", Toast.LENGTH_LONG).show()
        }
    }

    // Track the last processed search trigger to avoid re-running on recomposition after rotation
    var lastProcessedTrigger by rememberSaveable { mutableIntStateOf(0) }
    
    // Trigger text extraction/highlight only when user explicitly searches (searchTrigger changes)
    // Capture the page index at the time of search to avoid issues with recomposition
    LaunchedEffect(searchTrigger, activeSessionToken) {
            // Skip if we've already processed this trigger value (prevents re-run after rotation)
            if (searchTrigger <= lastProcessedTrigger) return@LaunchedEffect
            if (searchTerm.isBlank()) return@LaunchedEffect
            val session = sessionCoordinator.currentSession() ?: return@LaunchedEffect
            val currentUri = pdfUri ?: return@LaunchedEffect
            lastProcessedTrigger = searchTrigger // Mark as processed
            val targetPage = selectedPageIndex // Capture current page
            val query = searchTerm
            val workToken = DocumentWorkToken(
                session = session.token,
                pageIndex = if (searchOnlyCurrentPage) targetPage else null,
                queryRevision = searchTrigger.toLong()
            )
            try {
                // Start a new search.  Show progress by resetting counters and toggling the
                // searching flag.  Use the existing PdfSearchEngine so OCR caches are reused.
                searching = true
                searchDone = 0
                searchTotal = 0
                val results = try {
                    if (searchOnlyCurrentPage) {
                        pdfSearchEngine.search(currentUri, query, 1, targetPage, cacheNamespace = session.token.sourceCacheKey) { done, total ->
                            if (sessionCoordinator.accepts(workToken, selectedPageIndex, searchTrigger.toLong())) {
                                searchDone = done
                                searchTotal = total
                            }
                        }
                    } else {
                        pdfSearchEngine.search(currentUri, query, totalPageCount, cacheNamespace = session.token.sourceCacheKey) { done, total ->
                            if (sessionCoordinator.accepts(workToken, selectedPageIndex, searchTrigger.toLong())) {
                                searchDone = done
                                searchTotal = total
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.e("Blueprint", "PdfSearchEngine.search failed", t)
                    emptyMap<Int, List<RectF>>()
                }
                if (!sessionCoordinator.accepts(workToken, selectedPageIndex, searchTrigger.toLong())) return@LaunchedEffect
                searching = false
                val totalHits = results.values.sumOf { it.size }
                Log.d("Blueprint", "PdfSearchEngine found total=$totalHits matches pages=${results.keys}")
                vm.pageHighlights.clear()
                vm.pageSearchTerms.clear()
                for ((pageIdx, rects) in results) {
                    vm.pageHighlights[pageIdx] = rects
                    vm.pageSearchTerms[pageIdx] = query
                }
                foundCount = totalHits
                try {
                    Toast.makeText(context, "Found ${foundCount} matches", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                showFoundDialog = true
                delay(1400)
                if (sessionCoordinator.accepts(workToken, selectedPageIndex, searchTrigger.toLong())) {
                    showFoundDialog = false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e("Blueprint", "search LaunchedEffect failed", t)
                if (sessionCoordinator.accepts(workToken, selectedPageIndex, searchTrigger.toLong())) {
                    searching = false
                }
            }
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("Search Current Page") },
            text = {
                Column {
                    OutlinedTextField(value = searchInput, onValueChange = { searchInput = it }, label = { Text("Search term") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Matches are highlighted yellow on the page until a new search is run.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(onClick = { Log.d("Blueprint", "Search dialog confirm term='" + searchInput + "'"); searchTerm = searchInput.trim(); searchTrigger++; showSearchDialog = false }, shape = RoundedCornerShape(12.dp)) { Text("Search") }
            },
            dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text("Cancel") } }
        )
    }

    if (showFoundDialog) {
        AlertDialog(onDismissRequest = { showFoundDialog = false }, title = { Text("Search Results") }, text = { Text("Found $foundCount Matches") }, confirmButton = { TextButton(onClick = { showFoundDialog = false }) { Text("OK") } })
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
    // bundles; the import reader still recognizes the legacy V0 JSON format.
    var pendingBundleExportToken by rememberSaveable(
        stateSaver = documentSessionTokenSaver
    ) { mutableStateOf<DocumentSessionToken?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val token = pendingBundleExportToken
        pendingBundleExportToken = null
        if (uri == null) {
            Toast.makeText(context, "Save bundle export cancelled.", Toast.LENGTH_SHORT).show()
        } else if (token == null) {
            Toast.makeText(
                context,
                "Save bundle export request expired; reopen the PDF and try again.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            scope.launch {
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
                                store.readReferencedPhotos(snapshot)
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
                    Toast.makeText(context, "Save bundle exported successfully", Toast.LENGTH_SHORT).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    var importPdfUri by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetPdfUri = importPdfUri?.let(Uri::parse)
        importPdfUri = null
        if (uri == null) {
            Toast.makeText(context, "Save bundle import cancelled.", Toast.LENGTH_SHORT).show()
        } else if (targetPdfUri == null) {
            Toast.makeText(
                context,
                "Save bundle import request expired; reopen the PDF and try again.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val saveFileUri = uri
            scope.launch {
                try {
                    if (sessionCoordinator.currentSession() == null && pdfUri == null) {
                        Toast.makeText(context, "Open the PDF before importing a save file.", Toast.LENGTH_LONG).show()
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
                    val selectedSource = documentSourceIdentityForSnapshot(
                        targetPdfUri,
                        getFileName(context, targetPdfUri)
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
                        val input = context.contentResolver.openInputStream(saveFileUri)
                            ?: error("could not read the save file")
                        input.use { raw ->
                            val pushback = PushbackInputStream(raw, 4)
                            val prefix = ByteArray(4)
                            var prefixSize = 0
                            var zeroReads = 0
                            while (prefixSize < prefix.size) {
                                val count = pushback.read(prefix, prefixSize, prefix.size - prefixSize)
                                if (count < 0) break
                                if (count == 0) {
                                    zeroReads++
                                    if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                                        error("save file stream did not make progress")
                                    }
                                    continue
                                }
                                zeroReads = 0
                                prefixSize += count
                            }
                            if (prefixSize > 0) pushback.unread(prefix, 0, prefixSize)
                            if (DocumentBundleService.looksLikeZip(prefix.copyOf(prefixSize))) {
                                ParsedSaveFile.Bundle(documentBundleService.readBundleCancellable(pushback))
                            } else {
                                ParsedSaveFile.Legacy(
                                    driveSyncManager.deserializePageData(readBoundedUtf8(pushback))
                                )
                            }
                        }
                    }

                    val binding = if (parsedSaveFile is ParsedSaveFile.Legacy) {
                        val capturedBinding = activeSyncBinding
                        if (capturedBinding == null) {
                            // Legacy local import remains available while
                            // signed out/offline; it has no remote step.
                            null
                        } else {
                            val currentScope = currentSyncScope(session)
                            require(currentScope != null && syncCoordinator.admit(capturedBinding, currentScope)) {
                                "the synchronization scope changed during import"
                            }
                            syncCoordinator.currentImportBindingOrNull(capturedBinding, session.token)
                        }
                    } else {
                        // A local .sotaware acceptance must not advance Drive
                        // metadata or enqueue an upload as a side effect.
                        null
                    }

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

                        is ParsedSaveFile.Legacy -> {
                            val importedSnapshot = withContext(Dispatchers.IO) {
                                snapshotFromLegacyPageData(parsedSaveFile.pageData, selectedSource).also(::validateSnapshot)
                            }
                            // Keep the document barrier across the complete
                            // legacy transaction, but run photo migration,
                            // validation, filesystem work, and cleanup on IO.
                            val applied = withVerifiedStage6ImportDocument(
                                transactionBarrier = documentTransactionBarrier,
                                documentId = session.token.documentId,
                                sessionSourceUri = session.token.sourceUri,
                                associationDocumentId = association.documentId,
                                associationSourceUri = association.source.sourceUri,
                                targetSourceUri = importedSnapshot.source.sourceUri,
                                sessionSourceFingerprint = session.token.sourceFingerprint,
                                associationSourceFingerprint = association.sourceFingerprint,
                                targetSourceFingerprint = fingerprint,
                                currentSourceFingerprint = {
                                    fingerprintContentUri(context, targetPdfUri)
                                }
                            ) {
                                    val currentLiveSnapshot = withContext(Dispatchers.Main.immediate) {
                                        sessionCoordinator
                                            .captureCurrentSnapshotWithinDocumentTransaction(session.token)
                                            ?: error("current canonical snapshot became unavailable during import")
                                    }
                                    val currentDurableSnapshot = when (val loaded = localDocumentRepository.load(association)) {
                                        is DocumentLoadResult.Loaded -> loaded.snapshot
                                        DocumentLoadResult.NotFound -> null
                                        is DocumentLoadResult.Failed -> throw DocumentBundleException(
                                            "current durable snapshot could not be read during import",
                                            IllegalStateException(loaded.error.toString())
                                        )
                                    }
                                    val previousCanonicalSnapshot = currentDurableSnapshot ?: currentLiveSnapshot
                                    DocumentPhotoAssetStore(
                                        context.filesDir,
                                        session.token.documentId
                                    ).use { store ->
                                        store.reconcilePhotoContent(previousCanonicalSnapshot, currentLiveSnapshot)
                                        val result = store.withMigratedLegacyPhotos(
                                            snapshot = importedSnapshot,
                                            legacyRoot = context.filesDir,
                                            previousCanonicalSnapshot = previousCanonicalSnapshot,
                                            previousLiveCanonicalSnapshot = currentLiveSnapshot,
                                            commitResult = { result -> result is SessionSnapshotApplyResult.Applied },
                                            canonicalRollbackProven = {
                                                val durableRestored = when (
                                                    val loaded = localDocumentRepository.load(association)
                                                ) {
                                                    is DocumentLoadResult.Loaded -> loaded.snapshot == previousCanonicalSnapshot
                                                    DocumentLoadResult.NotFound,
                                                    is DocumentLoadResult.Failed -> false
                                                }
                                                val liveRestored = withContext(Dispatchers.Main.immediate) {
                                                    sessionCoordinator
                                                        .captureCurrentSnapshotWithinDocumentTransaction(session.token)
                                                        ?.let { it == currentLiveSnapshot }
                                                        ?: false
                                                }
                                                durableRestored && liveRestored
                                            }
                                        ) { migratedPhotos ->
                                            validatePhotoSet(importedSnapshot, migratedPhotos)
                                            withContext(Dispatchers.Main.immediate) {
                                                sessionCoordinator.importCurrentSnapshotWithinDocumentTransaction(
                                                    token = session.token,
                                                    snapshot = importedSnapshot,
                                                    currentSourceFingerprint = fingerprint,
                                                    isBindingCurrent = {
                                                        binding == null || syncCoordinator.isBindingCurrent(binding)
                                                    }
                                                )
                                            }
                                        }
                                        if (result is SessionSnapshotApplyResult.Applied) {
                                            cleanupPhotoContentAfterCanonicalCommit(session, importedSnapshot)
                                        }
                                        result
                                }
                            }
                            when (applied) {
                                SessionSnapshotApplyResult.Applied -> {
                                    if (binding == null) {
                                        Toast.makeText(
                                            context,
                                            "Legacy save file imported locally; Drive synchronization is unavailable.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else when (val outcome = syncCoordinator.enqueueUpload(binding, SyncReason.IMPORT).await()) {
                                        is SyncOutcome.Uploaded -> Toast.makeText(
                                            context,
                                            "Legacy save file imported and synchronized successfully.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        SyncOutcome.BlockedByConflict,
                                        is SyncOutcome.RemoteConflict -> Toast.makeText(
                                            context,
                                            "Legacy save file import was not synchronized because Drive reported a conflict.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        SyncOutcome.Stale,
                                        SyncOutcome.StaleSession,
                                        SyncOutcome.Canceled -> Toast.makeText(
                                            context,
                                            "Legacy save file import was not completed because synchronization became stale or was canceled.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        is SyncOutcome.Failed -> Toast.makeText(
                                            context,
                                            "Legacy save file import was not synchronized: ${outcome.error.detail}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        else -> Toast.makeText(
                                            context,
                                            "Legacy save file import was not synchronized.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                SessionSnapshotApplyResult.Stale -> error("the active document changed during import")
                                is SessionSnapshotApplyResult.Failed -> error(
                                    "canonical import save/apply failed: ${applied.error}"
                                )
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    // PDF export launcher - allows user to choose save location
    var pendingPdfExportData by remember { mutableStateOf<PdfExportData?>(null) }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { outputUri ->
            pendingPdfExportData?.let { exportData ->
                scope.launch {
                    val success = exportPageAsPdf(
                        context,
                        outputUri,
                        exportData.sourceUri,
                        exportData.pageIndex,
                        exportData.paths,
                        exportData.measurements,
                        exportData.notes,
                        exportData.photoPins,
                        exportData.shapes,
                        activeSessionToken
                    )
                    if (success) Toast.makeText(context, "PDF exported successfully", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(context, "Failed to export PDF", Toast.LENGTH_SHORT).show()
                }
            }
            pendingPdfExportData = null
        }
    }

    val capturePage: () -> Unit = {
        val uri = pdfUri
        if (uri != null) {
            // Prepare data for export and launch file picker
            pendingPdfExportData = PdfExportData(
                sourceUri = uri,
                pageIndex = selectedPageIndex,
                paths = vm.pagePaths[selectedPageIndex]?.toList() ?: emptyList(),
                measurements = vm.pageMeasurements[selectedPageIndex]?.toList() ?: emptyList(),
                notes = vm.pageNotes[selectedPageIndex]?.toList() ?: emptyList(),
                photoPins = vm.pagePhotoPins[selectedPageIndex]?.toList() ?: emptyList(),
                shapes = vm.pageShapes[selectedPageIndex]?.toList() ?: emptyList()
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
                Text("Options", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                NavigationDrawerItem(
                    label = { Text("Home") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; currentScreen = Screen.SELECTOR },
                    icon = { Icon(Icons.Default.Home, null) }
                )
                NavigationDrawerItem(
                    label = { Text("View Pages") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; currentScreen = Screen.BROWSER },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                )
                NavigationDrawerItem(
                    label = { Text("Search current page") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; searchOnlyCurrentPage = true; showSearchDialog = true },
                    icon = { Icon(Icons.Default.Search, null) }
                )
                
                NavigationDrawerItem(
                    label = { Text("Screenshot") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; capturePage() },
                    icon = { Icon(Icons.Default.Screenshot, null) }
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; currentScreen = Screen.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) }
                )
            }
        }
    ) {
        when (currentScreen) {
            Screen.SELECTOR -> {
                Scaffold(
                    topBar = { 
                        CenterAlignedTopAppBar(
                            title = { Text("SOTAware Construct", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }, 
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                            actions = {
                                IconButton(onClick = { currentScreen = Screen.SETTINGS }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        ) 
                    },
                    floatingActionButton = { LargeFloatingActionButton(onClick = { launcher.launch(arrayOf("application/pdf")) }, containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp)) { Icon(Icons.Default.Add, null, Modifier.size(36.dp)) } }
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Box(modifier = Modifier.weight(0.45f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_icon), 
                                    contentDescription = "Logo", 
                                    modifier = Modifier.size(280.dp),
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text("Digital Field Plans", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Surface(modifier = Modifier.weight(0.55f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text("Recent Drawings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                if (recentFiles.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No recent drawings found.\nTap + to start.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(recentFiles) { file ->
                                        Card(onClick = { onPdfSelected(Uri.parse(file.uri)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                                            ListItem(
                                                headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) }, 
                                                supportingContent = { Text("Blueprint", style = MaterialTheme.typography.bodySmall) }, 
                                                leadingContent = { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Info, null, Modifier.padding(8.dp)) } },
                                                trailingContent = {
                                                    Box {
                                                        IconButton(onClick = { expandedMenuUri = file.uri }) {
                                                            Icon(Icons.Default.MoreVert, "Options")
                                                        }
                                                        DropdownMenu(
                                                            expanded = expandedMenuUri == file.uri,
                                                            onDismissRequest = { expandedMenuUri = null }
                                                        ) {
                                                            DropdownMenuItem(
                                                                text = { Text("Export Save File") },
                                                                onClick = {
                                                                    val session = sessionCoordinator.currentSession()
                                                                    if (session == null ||
                                                                        session.token.sourceUri != file.uri ||
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
                                                                            "${file.name.removeSuffix(".pdf")}_save$SOTAWARE_BUNDLE_EXTENSION"
                                                                        )
                                                                    }
                                                                    expandedMenuUri = null
                                                                },
                                                                leadingIcon = { Icon(Icons.Default.Share, null) }
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text("Load Save File") },
                                                                onClick = {
                                                                    importPdfUri = file.uri
                                                                    importLauncher.launch(
                                                                        arrayOf("application/zip", "application/json", "application/octet-stream")
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
                    sessionCoordinator.isCurrent(readySessionToken!!)
                BackHandler { currentScreen = Screen.SELECTOR }
                Scaffold(
                    topBar = { 
                        TopAppBar(
                            title = { Text("Select Sheet", fontWeight = FontWeight.Bold) }, 
                            navigationIcon = { 
                                IconButton(onClick = { currentScreen = Screen.SELECTOR }) { 
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") 
                                } 
                            },
                            actions = {
                                if (browserReady && documentSearchActive) {
                                    IconButton(
                                        onClick = {
                                            documentSearchActive = false
                                            documentSearchTerm = ""
                                            documentSearching = false
                                            pagesWithMatches = emptySet()
                                            documentSearchResults = emptyMap()
                                            vm.pageHighlights.clear()
                                            vm.pageSearchTerms.clear()
                                        }
                                    ) {
                                        Icon(Icons.Default.Clear, "Clear Search")
                                    }
                                }
                                if (browserReady) {
                                    IconButton(onClick = { showDocumentSearchDialog = true }) {
                                        Icon(Icons.Default.Search, "Search Document")
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
                                sessionToken = activeSessionToken,
                                isSessionCurrent = { token ->
                                    token == null ||
                                        (sessionCoordinator.isCurrent(token) && sessionCoordinator.isCurrentApplied(token))
                                },
                                thumbnailCache = vm.thumbnailCache,
                                pagesWithMatches = pagesWithMatches,
                                matchCounts = documentSearchResults,
                                modifier = Modifier.fillMaxSize(),
                                onPageSelected = { selectedPageIndex = it; currentScreen = Screen.VIEWER }
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(16.dp))
                                    Text("Loading document…")
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
                                            "Searching Document...",
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
                        title = { Text("Search Document") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = documentSearchInput, 
                                    onValueChange = { documentSearchInput = it }, 
                                    label = { Text("Search term") }, 
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Searches all pages. Results will be highlighted on each page.", 
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (documentSearchInput.isNotBlank()) {
                                        documentSearchTerm = documentSearchInput.trim()
                                        documentSearchActive = true
                                        showDocumentSearchDialog = false
                                        documentSearching = true
                                        val session = sessionCoordinator.currentSession()
                                        val query = documentSearchTerm
                                        documentSearchRevision++
                                        val queryRevision = documentSearchRevision
                                        if (session != null) {
                                            val workToken = DocumentWorkToken(session.token, queryRevision = queryRevision)
                                            sessionCoordinator.launchDocumentJob(session.token) {
                                                val searchEngine = PdfSearchEngine(context)
                                                val results = try {
                                                    searchEngine.search(
                                                        session.token.sourceUri.toUri(),
                                                        query,
                                                        totalPageCount,
                                                        cacheNamespace = session.token.sourceCacheKey
                                                    )
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (t: Throwable) {
                                                    emptyMap<Int, List<RectF>>()
                                                }
                                                if (!sessionCoordinator.accepts(workToken, currentQueryRevision = documentSearchRevision)) return@launchDocumentJob
                                                withContext(Dispatchers.Main.immediate) {
                                                    if (!sessionCoordinator.accepts(workToken, currentQueryRevision = documentSearchRevision)) return@withContext
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
                                                        "Found $totalHits matches across ${results.size} pages",
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
                                Text("Search") 
                            }
                        },
                        dismissButton = { 
                            TextButton(onClick = { showDocumentSearchDialog = false }) { 
                                Text("Cancel") 
                            } 
                        }
                    )
                }
            }
            Screen.VIEWER -> {
                BackHandler { currentScreen = Screen.BROWSER }
                
                // Determine if this is a tablet-size screen (>= 600dp width)
                val screenWidthDp = configuration.screenWidthDp
                val isTablet = screenWidthDp >= 600
                
                // Format current scale for display
                val currentScaleText = vm.pageScales[selectedPageIndex]?.let { scale ->
                    "1\" = ${formatFeet(1f / scale.pixelsPerFoot * 72f)}" // Approximate at 72 dpi
                }
                
                // Get PDF name for top bar
                val pdfName = remember(pdfUri) { 
                    pdfUri?.let { getFileName(context, it).removeSuffix(".pdf") } ?: "Document"
                }
                
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
                                canUndo = vm.canUndo(selectedPageIndex),
                                canRedo = vm.canRedo(selectedPageIndex),
                                onUndo = { vm.undo(selectedPageIndex); triggerDebouncedSync() },
                                onRedo = { vm.redo(selectedPageIndex); triggerDebouncedSync() },
                                onClearPage = { vm.clearPageMarkups(selectedPageIndex); triggerDebouncedSync() },
                                isVertical = true
                            )
                            
                            // Main canvas area
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                // PDF Canvas with white background
                                Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                                    PdfPageRenderer(
                                        uri = pdfUri!!, 
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
                                        documentTransactionBarrier = documentTransactionBarrier,
                                        pageIndex = selectedPageIndex, 
                                        mode = toolMode, 
                                        currentScale = vm.pageScales[selectedPageIndex],
                                        paths = vm.pagePaths.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        measurements = vm.pageMeasurements.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        notes = vm.pageNotes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        photoPins = vm.pagePhotoPins.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        shapes = vm.pageShapes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                        allPagePhotoPins = vm.pagePhotoPins,
                                        searchTerm = searchTerm,
                                        highlightRects = vm.pageHighlights[selectedPageIndex] ?: emptyList(),
                                        onScaleDefined = { pixels, feet ->
                                            val newScale = PageScale(pixels / feet)
                                            vm.pageScales[selectedPageIndex] = newScale
                                            triggerDebouncedSync()
                                            toolMode = ToolMode.PAN
                                        },
                                        onActionAdded = { action ->
                                            vm.addAction(selectedPageIndex, action)
                                            if (action is HistoryAction.AddMeasurement || action is HistoryAction.AddNote || action is HistoryAction.AddPhotoPin || action is HistoryAction.AddShape) {
                                                toolMode = ToolMode.PAN
                                            }
                                            // Photos sync immediately, others are debounced
                                            if (action is HistoryAction.AddPhotoPin) {
                                                triggerImmediateSync(SyncReason.PHOTO)
                                            } else {
                                                triggerDebouncedSync()
                                            }
                                        },
                                        onDeleteItem = { item -> vm.deleteItem(selectedPageIndex, item); triggerDebouncedSync() },
                                        onFullScreenModeChanged = { isFullScreen -> isFullScreenImageMode = isFullScreen },
                                        onPhotoAdded = { triggerImmediateSync(SyncReason.PHOTO) },
                                        onDocumentChanged = { triggerDebouncedSync() }
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
                                    canUndo = vm.canUndo(selectedPageIndex),
                                    canRedo = vm.canRedo(selectedPageIndex),
                                    onUndo = { vm.undo(selectedPageIndex); triggerDebouncedSync() },
                                    onRedo = { vm.redo(selectedPageIndex); triggerDebouncedSync() },
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
                                canUndo = vm.canUndo(selectedPageIndex),
                                canRedo = vm.canRedo(selectedPageIndex),
                                onUndo = { vm.undo(selectedPageIndex); triggerDebouncedSync() },
                                onRedo = { vm.redo(selectedPageIndex); triggerDebouncedSync() }
                            )
                        },
                        bottomBar = {
                            ToolRail(
                                currentMode = toolMode,
                                onModeSelected = { mode -> 
                                    toolMode = if (toolMode == mode) ToolMode.PAN else mode 
                                },
                                canUndo = vm.canUndo(selectedPageIndex),
                                canRedo = vm.canRedo(selectedPageIndex),
                                onUndo = { vm.undo(selectedPageIndex); triggerDebouncedSync() },
                                onRedo = { vm.redo(selectedPageIndex); triggerDebouncedSync() },
                                onClearPage = { vm.clearPageMarkups(selectedPageIndex); triggerDebouncedSync() },
                                isVertical = false
                            )
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            // PDF Canvas with white background
                            Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                                PdfPageRenderer(
                                    uri = pdfUri!!, 
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
                                    documentTransactionBarrier = documentTransactionBarrier,
                                    pageIndex = selectedPageIndex, 
                                    mode = toolMode, 
                                    currentScale = vm.pageScales[selectedPageIndex],
                                    paths = vm.pagePaths.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    measurements = vm.pageMeasurements.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    notes = vm.pageNotes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    photoPins = vm.pagePhotoPins.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    shapes = vm.pageShapes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                    allPagePhotoPins = vm.pagePhotoPins,
                                    searchTerm = searchTerm,
                                    highlightRects = vm.pageHighlights[selectedPageIndex] ?: emptyList(),
                                    onScaleDefined = { pixels, feet ->
                                        val newScale = PageScale(pixels / feet)
                                        vm.pageScales[selectedPageIndex] = newScale
                                        triggerDebouncedSync()
                                        toolMode = ToolMode.PAN
                                    },
                                    onActionAdded = { action ->
                                        vm.addAction(selectedPageIndex, action)
                                        if (action is HistoryAction.AddMeasurement || action is HistoryAction.AddNote || action is HistoryAction.AddPhotoPin || action is HistoryAction.AddShape) {
                                            toolMode = ToolMode.PAN
                                        }
                                            // Photos sync immediately, others are debounced
                                            if (action is HistoryAction.AddPhotoPin) {
                                                triggerImmediateSync(SyncReason.PHOTO)
                                            } else {
                                            triggerDebouncedSync()
                                        }
                                    },
                                    onDeleteItem = { item -> vm.deleteItem(selectedPageIndex, item); triggerDebouncedSync() },
                                    onFullScreenModeChanged = { isFullScreen -> isFullScreenImageMode = isFullScreen },
                                    onPhotoAdded = { triggerImmediateSync(SyncReason.PHOTO) },
                                    onDocumentChanged = { triggerDebouncedSync() }
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
                        }
                    }
                }
            }
            Screen.SETTINGS -> {
                BackHandler { currentScreen = Screen.SELECTOR }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Settings", fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = { currentScreen = Screen.SELECTOR }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                            title = { Text("Google Drive Backup", fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = { currentScreen = Screen.SETTINGS }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                                                Text("Signed in", fontWeight = FontWeight.Bold)
                                                driveSyncManager.getSignedInEmail()?.let {
                                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    syncCoordinator.invalidateCurrentScope()
                                                    activeSyncBinding = null
                                                    scope.launch {
                                                        val googleSignInClient = GoogleSignIn.getClient(context, driveSyncManager.getSignInOptions())
                                                        googleSignInClient.signOut().await()
                                                        driveSyncManager.clearSession()
                                                        isSignedIn = false
                                                        signedInAccountId = null
                                                        backupFolderId = null
                                                        backupFolderName = null
                                                        Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("Sign Out", color = Color.Red)
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
                                                    Text("Backup Folder", fontWeight = FontWeight.Bold)
                                                    Text(backupFolderName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = {
                                                    syncCoordinator.invalidateCurrentScope()
                                                    activeSyncBinding = null
                                                    driveSyncManager.clearBackupFolder()
                                                    backupFolderName = null
                                                    backupFolderId = null
                                                }) {
                                                    Icon(Icons.Default.Clear, "Clear folder")
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
                                                            Toast.makeText(context, "Syncing '$requestedName'...", Toast.LENGTH_SHORT).show()
                                                            val outcome = syncCoordinator.enqueueUpload(requestedBinding, SyncReason.MANUAL).await()
                                                            if (!sessionCoordinator.isCurrentApplied(requestedBinding.token) ||
                                                                currentSyncBinding(requestedSession) != requestedBinding
                                                            ) return@launch
                                                            if (outcome is SyncOutcome.Uploaded) {
                                                                Toast.makeText(context, "Sync complete!", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                Toast.makeText(context, "Sync failed - check logs", Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Sync Now")
                                                }
                                            }
                                        } else {
                                            Text(
                                                "Choose where to store your backups in Google Drive:",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                            
                                            Button(
                                                onClick = {
                                                    // Open folder browser
                                                    scope.launch {
                                                        loadingFolders = true
                                                        currentBrowseFolderId = "root"
                                                        currentBrowseFolderName = "My Drive"
                                                        folderBrowseStack = emptyList()
                                                        browseFolders = driveSyncManager.listFolders("root")
                                                        loadingFolders = false
                                                        showFolderBrowser = true
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Browse Google Drive")
                                            }
                                        }
                                    } else {
                                        Text("Sign in to enable automatic backup to Google Drive")
                                        
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        Log.d("GoogleSignIn", "Starting sign-in flow")
                                                        val signInOptions = driveSyncManager.getSignInOptions()
                                                        val googleSignInClient = GoogleSignIn.getClient(context, signInOptions)
                                                        
                                                        // First try silent sign-in
                                                        val account = try {
                                                            googleSignInClient.silentSignIn().await()
                                                        } catch (e: Exception) {
                                                            Log.d("GoogleSignIn", "Silent sign-in failed, launching interactive flow")
                                                            null
                                                        }
                                                        
                                                        if (account != null) {
                                                            Log.d("GoogleSignIn", "Silent sign-in successful: ${account.email}")
                                                            syncCoordinator.invalidateCurrentScope()
                                                            activeSyncBinding = null
                                                            driveSyncManager.initializeDriveService(account)
                                                            isSignedIn = true
                                                            signedInAccountId = account.email
                                                            Toast.makeText(context, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            // Sign out first to force account picker
                                                            googleSignInClient.signOut().await()
                                                            val signInIntent = googleSignInClient.signInIntent
                                                            signInLauncher.launch(signInIntent)
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("GoogleSignIn", "Error starting sign-in", e)
                                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(painterResource(android.R.drawable.ic_menu_upload), null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Sign in with Google")
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
                        Text("Select Backup Folder")
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
                                            currentBrowseFolderName = "My Drive"
                                            folderBrowseStack = emptyList()
                                            browseFolders = driveSyncManager.listFolders("root")
                                            loadingFolders = false
                                        }
                                    }
                                },
                                label = { Text("My Drive") },
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
                                            currentBrowseFolderName = "Shared Drives"
                                            folderBrowseStack = emptyList()
                                            currentSharedDriveId = null
                                            sharedDrives = driveSyncManager.listSharedDrives()
                                            browseFolders = sharedDrives
                                            loadingFolders = false
                                        }
                                    }
                                },
                                label = { Text("Shared Drives") },
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
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            } else {
                                Spacer(Modifier.width(48.dp))
                            }
                            
                            // Create new folder button
                            IconButton(onClick = { 
                                newFolderName = ""
                                showCreateFolderDialog = true 
                            }) {
                                Icon(Icons.Default.CreateNewFolder, "Create Folder")
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
                            driveSyncManager.setBackupFolder(currentBrowseFolderId, currentBrowseFolderName)
                            backupFolderName = currentBrowseFolderName
                            backupFolderId = currentBrowseFolderId
                            showFolderBrowser = false
                            Toast.makeText(context, "Backup folder set to: $currentBrowseFolderName", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Select This Folder")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFolderBrowser = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        // Create folder dialog
        if (showCreateFolderDialog) {
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text("Create New Folder") },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Folder name") },
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
                                        Toast.makeText(context, "Created folder: ${newFolder.name}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to create folder", Toast.LENGTH_SHORT).show()
                                    }
                                    showCreateFolderDialog = false
                                }
                            }
                        },
                        enabled = newFolderName.isNotBlank()
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text("Cancel")
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
                title = { Text("Updates Available") },
                text = {
                    Text("Changes have been made to \"$updatePdfName\" from another device. Would you like to download the latest version?")
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
                                Toast.makeText(context, "Updates downloaded successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to download updates", Toast.LENGTH_SHORT).show()
                            }
                            if (updateSessionToken == activeRequestedToken && updatePdfName == requestedName) {
                                showUpdateDialog = false
                                updatePdfName = ""
                                updateSessionToken = null
                                updateBinding = null
                            }
                        }
                    }) {
                        Text("Download")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showUpdateDialog = false
                        updatePdfName = ""
                        updateSessionToken = null
                        updateBinding = null
                    }) {
                        Text("Later")
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
                title = { Text("Link existing backup?") },
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
                                Toast.makeText(context, "The document changed; the backup was not linked.", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val adopted = syncCoordinator
                                .enqueueAdoptRemote(requestedBinding, requestedCandidate)
                                .await()
                            val accepted = if (adopted is SyncOutcome.Adopted) {
                                syncCoordinator.enqueueRemoteAcceptance(requestedBinding).await()
                            } else adopted
                            if (accepted is SyncOutcome.AppliedRemote) {
                                Toast.makeText(context, "Backup linked and downloaded.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Backup link was not completed.", Toast.LENGTH_LONG).show()
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
                        Text("Link and download")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showAdoptionDialog = false
                        pendingAdoptionCandidate = null
                        pendingAdoptionBinding = null
                    }) { Text("Cancel") }
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
                title = { Text("Remote Changes Detected") },
                text = {
                    Text("\"$remoteUpdatePdfName\" has been updated in Google Drive since your last sync. Would you like to download the latest version?")
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
                                Toast.makeText(context, "Updates downloaded successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to download updates", Toast.LENGTH_SHORT).show()
                            }
                            if (remoteUpdateSessionToken == activeRequestedToken && remoteUpdatePdfName == requestedName) {
                                showRemoteUpdateDialog = false
                                remoteUpdatePdfName = ""
                                remoteUpdateSessionToken = null
                                remoteUpdateBinding = null
                            }
                        }
                    }) {
                        Text("Download")
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
                        Toast.makeText(context, "Sync disabled - download backup to re-enable", Toast.LENGTH_LONG).show()
                    }) {
                        Text("Keep Local")
                    }
                }
            )
        }
    }
}

@Composable
fun PdfPageBrowser(
    uri: Uri, 
    sessionToken: DocumentSessionToken? = null,
    isSessionCurrent: (DocumentSessionToken?) -> Boolean = { true },
    thumbnailCache: SnapshotStateMap<String, Bitmap>,
    pagesWithMatches: Set<Int> = emptySet(),
    matchCounts: Map<Int, List<RectF>> = emptyMap(),
    modifier: Modifier = Modifier, 
    onPageSelected: (Int) -> Unit
) {
    val context = LocalContext.current
    val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
    val count = if (pfd != null) PdfRenderer(pfd).use { it.pageCount } else 0
    pfd?.close()
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(count) { index ->
            val cacheIdentity = sessionToken?.sourceCacheKey ?: uri.toString()
            val memoryKey = "$cacheIdentity|$index"
            if (!thumbnailCache.containsKey(memoryKey)) {
                LaunchedEffect(uri, sessionToken, index) {
                    withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        if (!isSessionCurrent(sessionToken)) return@withContext
                        val cacheFile = getThumbCacheFile(context, uri, index, cacheIdentity)
                        if (cacheFile.exists()) {
                            val b = BitmapFactory.decodeFile(cacheFile.absolutePath)
                            currentCoroutineContext().ensureActive()
                            if (b != null && isSessionCurrent(sessionToken)) {
                                thumbnailCache[memoryKey] = b
                                return@withContext
                            }
                            b?.recycle()
                        }
                        val innerPfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                        innerPfd?.use { PdfRenderer(it).use { renderer ->
                            currentCoroutineContext().ensureActive()
                            val page = renderer.openPage(index)
                            val b = Bitmap.createBitmap(600, (600 * page.height / page.width), Bitmap.Config.ARGB_8888)
                            Canvas(b).drawColor(android.graphics.Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            currentCoroutineContext().ensureActive()
                            if (!isSessionCurrent(sessionToken)) {
                                b.recycle()
                                page.close()
                                return@use
                            }
                            thumbnailCache[memoryKey] = b
                            page.close()
                            try { FileOutputStream(cacheFile).use { out -> b.compress(Bitmap.CompressFormat.JPEG, 80, out) } } catch (e: Exception) { }
                        } }
                    }
                }
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
                        thumbnailCache[memoryKey]?.let { Image(bitmap = it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
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
                    Text("SHEET ${index + 1}", Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun PdfPageRenderer(
    uri: Uri, 
    sessionToken: DocumentSessionToken? = null,
    isSessionCurrent: (DocumentSessionToken?) -> Boolean = { true },
    isPageCurrent: (DocumentSessionToken?, Int) -> Boolean = { token, _ -> isSessionCurrent(token) },
    launchDocumentWork: ((DocumentSessionToken, suspend () -> Unit) -> Job)? = null,
    documentTransactionBarrier: DocumentTransactionBarrier,
    pageIndex: Int, 
    mode: ToolMode, 
    currentScale: PageScale?, 
    paths: SnapshotStateList<DrawnPath>, 
    measurements: SnapshotStateList<Measurement>,
    notes: SnapshotStateList<Note>,
    photoPins: SnapshotStateList<PhotoPin>,
    shapes: SnapshotStateList<Shape>,
    allPagePhotoPins: SnapshotStateMap<Int, SnapshotStateList<PhotoPin>>,
    searchTerm: String,
    highlightRects: List<RectF>,
    onScaleDefined: (Float, Float) -> Unit,
    onActionAdded: (HistoryAction) -> Unit,
    onDeleteItem: (PageItem) -> Unit,
    onFullScreenModeChanged: (Boolean) -> Unit,
    onPhotoAdded: () -> Unit = {},
    onDocumentChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val pdfSearchEngine = remember { PdfSearchEngine(context) }
    val textMeasurer = rememberTextMeasurer()
    var bitmap by remember(uri, sessionToken, pageIndex) { mutableStateOf<Bitmap?>(null) }
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
    val currentStroke = remember { mutableStateListOf<Point>() }
    
    var itemToDelete by remember { mutableStateOf<PageItem?>(null) }
    var selectedItem by remember { mutableStateOf<PageItem?>(null) }
    // Store the screen position where the toolbar should appear (tap location or item's new position after drag)
    var selectionToolbarPos by remember { mutableStateOf(Offset.Zero) }
    // Disambiguation: when multiple items overlap at tap location
    var overlappingItems by remember { mutableStateOf<List<PageItem>>(emptyList()) }
    var showItemPicker by remember { mutableStateOf(false) }
    
    var selectedMeasurement by remember { mutableStateOf<Measurement?>(null) }
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

    // Photo pin state
    var selectedPhotoPin by remember { mutableStateOf<PhotoPin?>(null) }
    var showPinImageGallery by remember { mutableStateOf(false) }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPhotoCaptureFile by remember { mutableStateOf<File?>(null) }
    var pendingPhotoDocumentId by remember { mutableStateOf<DocumentId?>(null) }
    var pendingPhotoSessionToken by remember { mutableStateOf<DocumentSessionToken?>(null) }
    var pendingPhotoPageIndex by remember { mutableIntStateOf(-1) }
    var pendingPhotoPinId by remember { mutableStateOf<String?>(null) }
    
    // Shape tool state
    var selectedShape by remember { mutableStateOf<Shape?>(null) }
    var showShapeDialog by remember { mutableStateOf(false) }
    var currentShapeType by remember { mutableStateOf(ShapeType.RECTANGLE) }
    var draggingShape by remember { mutableStateOf(false) }
    var originalShape by remember { mutableStateOf<Shape?>(null) }
    var resizingShape by remember { mutableStateOf(false) }
    var rotatingShape by remember { mutableStateOf(false) }
    var shapeInitialPinchDistance by remember { mutableFloatStateOf(0f) }
    var shapeInitialWidth by remember { mutableFloatStateOf(0f) }
    var shapeInitialHeight by remember { mutableFloatStateOf(0f) }
    
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
    var noteUpdateTrigger by remember { mutableIntStateOf(0) } // Force recomposition during drag/resize
    var currentImageOriginalHeight by remember { mutableFloatStateOf(0f) } // Original bitmap height for ratio calculations
    var currentImageDensity by remember { mutableFloatStateOf(2.5f) } // Density when note was created
    
    // Image shape tool state
    var selectedImageShape by remember { mutableStateOf<Shape?>(null) }
    var draggingImageShape by remember { mutableStateOf(false) }
    var resizingImageShape by remember { mutableStateOf(false) }
    var originalImageShape by remember { mutableStateOf<Shape?>(null) }
    var currentImageShapeType by remember { mutableStateOf(ShapeType.RECTANGLE) }
    
    // Text selection state (long-press to select, like web) - reset on page change
    var isTextSelecting by remember(sessionToken, pageIndex) { mutableStateOf(false) }
    var textSelectionStartIdx by remember(sessionToken, pageIndex) { mutableIntStateOf(-1) }
    var textSelectionEndIdx by remember(sessionToken, pageIndex) { mutableIntStateOf(-1) }
    var selectedOcrBoxes by remember(sessionToken, pageIndex) { mutableStateOf<List<OcrBox>>(emptyList()) }
    var showCopyButton by remember(sessionToken, pageIndex) { mutableStateOf(false) }
    var copyButtonPos by remember(sessionToken, pageIndex) { mutableStateOf(Offset.Zero) }
    var cachedPageOcr by remember(sessionToken, pageIndex) { mutableStateOf<PageOcr?>(null) }
    val coroutineScopeForOcr = rememberCoroutineScope()
    val cameraScope = rememberCoroutineScope()
    var draggingSelectionHandle by remember(sessionToken, pageIndex) { mutableStateOf<String?>(null) } // "start" or "end" or null
    
    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val callbackSessionToken = pendingPhotoSessionToken
        val callbackPageIndex = pendingPhotoPageIndex
        val callbackPinId = pendingPhotoPinId
        val callbackUri = pendingPhotoUri
        val callbackCaptureFile = pendingPhotoCaptureFile
        val callbackDocumentId = pendingPhotoDocumentId ?: callbackSessionToken?.documentId
        val callbackPin = selectedPhotoPin

        cameraScope.launch {
            if (callbackDocumentId == null) {
                Log.e("Blueprint", "Camera result had no document identity")
                return@launch
            }

            var publishedFileName: String? = null
            var attached = false
            try {
                // Camera publication, live attachment, reservation release,
                // and failure cleanup share the same document barrier as
                // post-commit authority capture/GC.  This closes the stale
                // snapshot -> attach -> destructive-cleanup interleaving.
                documentTransactionBarrier.withDocument(callbackDocumentId) {
                    try {
                        val requestStillBelongsToThisPage =
                            callbackSessionToken == sessionToken &&
                                callbackPageIndex == pageIndex &&
                                callbackPinId == selectedPhotoPin?.id &&
                                isSessionCurrent(callbackSessionToken)
                        if (success && requestStillBelongsToThisPage && callbackUri != null && callbackPin != null) {
                            if (callbackPin.imageFileNames.size >= Stage5Limits.MAX_PHOTOS_PER_PIN) {
                                throw Stage5ValidationException("photo pin has reached its photo limit")
                            }
                            val referencedPhotoCount = allPagePhotoPins.values.sumOf { pins ->
                                pins.sumOf { pin -> pin.imageFileNames.size }
                            }
                            if (referencedPhotoCount >= Stage5Limits.MAX_TOTAL_PHOTOS) {
                                throw Stage5ValidationException("document has reached its photo limit")
                            }
                            require(sessionToken?.documentId == callbackDocumentId) {
                                "camera photo session identity changed before publication"
                            }
                            val existingPhotoReferences = allPagePhotoPins.values
                                .flatMap { pins -> pins.flatMap { pin -> pin.imageFileNames } }
                                .toSet()
                            val fileName = context.contentResolver.openInputStream(callbackUri)?.use { input ->
                                DocumentPhotoAssetStore(context.filesDir, callbackDocumentId).use { store ->
                                    PhotoDocumentCriticalSections.withLock(store.resolver.root.toPath()) {
                                        val published = store.publishNewPhoto(input, ".jpg", existingPhotoReferences)
                                        publishedFileName = published
                                        callbackPin.imageFileNames.add(published)
                                        attached = true
                                        // Keep the reservation only through
                                        // publication and live attachment.
                                        store.releasePhotoPublication(published)
                                        published
                                    }
                                }
                            } ?: throw IOException("camera source stream is unavailable")
                            Log.d("Blueprint", "Photo saved: $fileName for pin ${callbackPin.id}")
                        }
                    } catch (e: Exception) {
                        if (attached && publishedFileName != null) {
                            callbackPin?.imageFileNames?.remove(publishedFileName)
                            attached = false
                        }
                        Log.e("Blueprint", "Failed to save photo", e)
                    } finally {
                        if (publishedFileName != null && !attached) {
                            runCatching {
                                DocumentPhotoAssetStore(context.filesDir, callbackDocumentId).use { store ->
                                    store.cleanup(publishedFileName!!)
                                }
                            }.onFailure { cleanupError ->
                                Log.e("Blueprint", "Failed to clean up unreferenced camera photo", cleanupError)
                            }
                        }
                        if (callbackCaptureFile != null) {
                            runCatching {
                                DocumentPhotoAssetStore(context.filesDir, callbackDocumentId).use { store ->
                                    store.discardCaptureFile(callbackCaptureFile)
                                }
                            }.onFailure { cleanupError ->
                                Log.e("Blueprint", "Failed to clean up temporary camera photo", cleanupError)
                            }
                        }
                    }
                }
            } finally {
                if (pendingPhotoCaptureFile == callbackCaptureFile || pendingPhotoUri == callbackUri) {
                    pendingPhotoCaptureFile = null
                    pendingPhotoDocumentId = null
                    pendingPhotoUri = null
                    pendingPhotoSessionToken = null
                    pendingPhotoPageIndex = -1
                    pendingPhotoPinId = null
                }
                if (attached && publishedFileName != null) {
                    onDocumentChanged()
                    onPhotoAdded()
                }
            }
        }
    }
    DisposableEffect(uri, sessionToken, pageIndex) {
        val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
        if (pfd != null) { PdfRenderer(pfd).use { renderer ->
            val page = renderer.openPage(pageIndex)
            val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            Canvas(b).drawColor(android.graphics.Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (isSessionCurrent(sessionToken)) bitmap = b else b.recycle()
            try { Log.d("Blueprint", "Rendered pageIndex=$pageIndex bmpSize=${b.width}x${b.height}") } catch (_: Exception) {}
            page.close()
        }; pfd.close() }
        onDispose { bitmap?.let { if (!it.isRecycled) it.recycle() }; bitmap = null }
    }

    if (showScaleDialog) {
        AlertDialog(onDismissRequest = { showScaleDialog = false }, title = { Text("Calibrate Scale") }, text = { Column { Text("Enter distance:"); OutlinedTextField(value = scaleInput, onValueChange = { scaleInput = it }, label = { Text("Distance (ft)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) } },
            confirmButton = { Button(onClick = { val feet = parseDistance(scaleInput); val pixels = if (firstPoint != null && secondPoint != null) sqrt((firstPoint!!.x - secondPoint!!.x).let { it * it } + (firstPoint!!.y - secondPoint!!.y).let { it * it }) else 0f; if (feet > 0) onScaleDefined(pixels, feet); showScaleDialog = false ; firstPoint = null; secondPoint = null }, shape = RoundedCornerShape(12.dp)) { Text("Set Scale") } }
        )
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false; editingNote = null },
            title = { Text(if (editingNote == null) "Add Note" else "Edit Note") },
            text = {
                Column {
                    OutlinedTextField(value = noteInput, onValueChange = { noteInput = it }, label = { Text("Note text") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = noteIsBold, onCheckedChange = { noteIsBold = it })
                        Text("Bold")
                    }
                    Text("Pinch selected note to resize", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (editingNote == null) {
                        val newNote = Note(notePos.x, notePos.y, noteInput, 16f, noteIsBold)
                        notes.add(newNote)
                        onActionAdded(HistoryAction.AddNote(newNote))
                    } else {
                        val old = editingNote!!.copyNote()
                        editingNote!!.text = noteInput
                        editingNote!!.isBold = noteIsBold
                        onActionAdded(HistoryAction.UpdateNote(old, editingNote!!.copyNote()))
                    }
                    showNoteDialog = false
                    editingNote = null
                }) { Text("Save") }
            }
        )
    }
    
    // Image note dialog  
    if (showImageNoteDialog) {
        AlertDialog(
            onDismissRequest = { showImageNoteDialog = false; editingImageNote = null },
            title = { Text(if (editingImageNote == null) "Add Image Note" else "Edit Image Note") },
            text = {
                Column {
                    OutlinedTextField(value = imageNoteInput, onValueChange = { imageNoteInput = it }, label = { Text("Note text") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = imageNoteIsBold, onCheckedChange = { imageNoteIsBold = it })
                        Text("Bold")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (editingImageNote == null && currentImageFileName != null && selectedPhotoPin != null) {
                        // Use fixed percentage of image height for device independence
                        // 2% of image height is a readable default font size
                        val fontSizeRatio = 0.02f
                        
                        val newImageNote = PhotoImageNote(
                            x = imageNotePos.x,
                            y = imageNotePos.y,
                            text = imageNoteInput,
                            fontSize = 16f, // Legacy field
                            isBold = imageNoteIsBold,
                            rotation = 0f,
                            fontSizeRatio = fontSizeRatio
                        )
                        val notes = selectedPhotoPin!!.imageNotes.getOrPut(currentImageFileName!!) { mutableListOf() }
                        notes.add(newImageNote)
                        Log.d("Blueprint", "Added image note: text='${imageNoteInput}' pos=(${imageNotePos.x}, ${imageNotePos.y}) fontSizeRatio=$fontSizeRatio to file=$currentImageFileName")
                        Log.d("Blueprint", "Total notes for this image: ${notes.size}")
                        onDocumentChanged()
                    } else if (editingImageNote != null) {
                        editingImageNote!!.text = imageNoteInput
                        editingImageNote!!.isBold = imageNoteIsBold
                        Log.d("Blueprint", "Edited image note: text='${imageNoteInput}'")
                        onDocumentChanged()
                    }
                    showImageNoteDialog = false
                    editingImageNote = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showImageNoteDialog = false; editingImageNote = null }) { Text("Cancel") } }
        )
    }
    
    // Shape selection dialog - store page dimensions for ratio calculation
    var shapePos by remember { mutableStateOf(Point(0f, 0f)) }
    var shapePageWidth by remember { mutableFloatStateOf(1f) }
    var shapePageHeight by remember { mutableFloatStateOf(1f) }
    if (showShapeDialog) {
        AlertDialog(
            onDismissRequest = { showShapeDialog = false },
            title = { Text("Select Shape") },
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
                                        width = shapePageWidth * defaultWidthRatio,  // Legacy: actual pixels for backwards compat
                                        height = shapePageHeight * defaultHeightRatio,
                                        rotation = 0f,
                                        type = shapeType,
                                        colorArgb = Color.Red.toArgb(),
                                        strokeWidth = 4f,
                                        isFilled = false,
                                        strokeWidthRatio = 0.003f,  // 0.3% of page max dimension
                                        widthRatio = defaultWidthRatio,
                                        heightRatio = defaultHeightRatio
                                    )
                                    shapes.add(newShape)
                                    onActionAdded(HistoryAction.AddShape(newShape))
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
                                contentDescription = shapeType.name,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = shapeType.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showShapeDialog = false }) { Text("Cancel") } }
        )
    }
    
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Item?") },
            text = { Text("Are you sure you want to remove this markup?") },
            confirmButton = { Button(onClick = { 
                onDeleteItem(itemToDelete!!)
                if (itemToDelete is PageItem.Measure && (itemToDelete as PageItem.Measure).data == selectedMeasurement) selectedMeasurement = null
                if (itemToDelete is PageItem.NoteItem && (itemToDelete as PageItem.NoteItem).data == selectedNote) selectedNote = null
                if (itemToDelete is PageItem.PhotoPinItem && (itemToDelete as PageItem.PhotoPinItem).data == selectedPhotoPin) selectedPhotoPin = null
                if (itemToDelete is PageItem.ShapeItem && (itemToDelete as PageItem.ShapeItem).data == selectedShape) selectedShape = null
                itemToDelete = null
                selectedItem = null
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { itemToDelete = null }) { Text("Cancel") } }
        )
    }
    
    // Photo pin image gallery dialog
    var fullScreenImageFile by rememberSaveable(sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf<String?>(null) }

    // Selection, gallery, and in-progress gesture state is document-scoped UI
    // state. Reset it whenever the session or page changes so A's selected
    // photo/note cannot be applied to B after a transactional switch.
    LaunchedEffect(sessionToken, pageIndex) {
        firstPoint = null
        secondPoint = null
        showScaleDialog = false
        scaleInput = ""
        selectedItem = null
        itemToDelete = null
        overlappingItems = emptyList()
        showItemPicker = false
        selectedMeasurement = null
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
        selectedPhotoPin = null
        selectedShape = null
        showShapeDialog = false
        draggingShape = false
        originalShape = null
        resizingShape = false
        rotatingShape = false
        shapeInitialPinchDistance = 0f
        shapeInitialWidth = 0f
        shapeInitialHeight = 0f
        editingImageNote = null
        selectedImageNote = null
        selectedImageShape = null
        draggingImageNote = null
        draggingImageShape = false
        showImageNoteDialog = false
        showPinImageGallery = false
        pendingPhotoUri = null
        val staleCaptureFile = pendingPhotoCaptureFile
        val staleCaptureDocumentId = pendingPhotoDocumentId
        if (staleCaptureFile != null && staleCaptureDocumentId != null) {
            runCatching {
                DocumentPhotoAssetStore(context.filesDir, staleCaptureDocumentId).use { store ->
                    store.discardCaptureFile(staleCaptureFile)
                }
            }.onFailure { cleanupError ->
                Log.e("Blueprint", "Failed to clean up stale camera photo", cleanupError)
            }
        }
        pendingPhotoCaptureFile = null
        pendingPhotoDocumentId = null
        pendingPhotoSessionToken = null
        pendingPhotoPageIndex = -1
        pendingPhotoPinId = null
        fullScreenImageFile = null
    }
    
    // Notify parent when fullscreen mode changes
    LaunchedEffect(fullScreenImageFile) {
        onFullScreenModeChanged(fullScreenImageFile != null)
    }
    
    if (showPinImageGallery && selectedPhotoPin != null) {
        AlertDialog(
            onDismissRequest = { showPinImageGallery = false },
            title = { Text("Photos (${selectedPhotoPin!!.imageFileNames.size})") },
            text = {
                if (selectedPhotoPin!!.imageFileNames.isEmpty()) {
                    Text("No photos yet. Tap 'Add Photo' to take one.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedPhotoPin!!.imageFileNames.size) { idx ->
                            val fileName = selectedPhotoPin!!.imageFileNames[idx]
                            val photoBytes = runCatching { photoBytesFor(context, sessionToken, fileName) }.getOrNull()
                            if (photoBytes != null) {
                                // Load bitmap with EXIF rotation applied for thumbnails too
                                val rotatedBmp = remember(fileName) {
                                    val originalBmp = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                                    if (originalBmp != null) {
                                        try {
                                            val exif = ByteArrayInputStream(photoBytes).use { ExifInterface(it) }
                                            val orientation = exif.getAttributeInt(
                                                ExifInterface.TAG_ORIENTATION,
                                                ExifInterface.ORIENTATION_NORMAL
                                            )
                                            val matrix = Matrix()
                                            when (orientation) {
                                                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                                                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                                                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                                            }
                                            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                                                Bitmap.createBitmap(originalBmp, 0, 0, originalBmp.width, originalBmp.height, matrix, true)
                                            } else {
                                                originalBmp
                                            }
                                        } catch (e: Exception) {
                                            originalBmp
                                        }
                                    } else null
                                }
                                if (rotatedBmp != null) {
                                    Image(
                                        bitmap = rotatedBmp.asImageBitmap(),
                                        contentDescription = "Photo $idx",
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { 
                                                fullScreenImageFile = fileName
                                                showPinImageGallery = false
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPinImageGallery = false }) { Text("Close") } }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        bitmap?.let { b ->
            val bW = b.width.toFloat(); val bH = b.height.toFloat()
            val bitmapAspectRatio = bW / bH
            val screenAspectRatio = w / h
            val (vW, vH) = if (bitmapAspectRatio > screenAspectRatio) w to (w / bitmapAspectRatio) else (h * bitmapAspectRatio) to h
            
            Box(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(sessionToken, pageIndex, mode, w, h) {
                        awaitEachGesture {
                            fun screenToPage(ptX: Float, ptY: Float): Point {
                                val baseScale = if (bW > 0f) (vW / bW) else 1f
                                val compositeScale = baseScale * scale
                                val imgW = bW * compositeScale
                                val imgH = bH * compositeScale
                                val imgLeft = w / 2 + offsetX - imgW / 2
                                val imgTop = h / 2 + offsetY - imgH / 2
                                val x = (ptX - imgLeft) / compositeScale
                                val y = (ptY - imgTop) / compositeScale
                                return Point(x, y)
                            }
                            fun pageToScreen(pt: Point): Offset {
                                val baseScale = if (bW > 0f) (vW / bW) else 1f
                                val compositeScale = baseScale * scale
                                val imgW = bW * compositeScale
                                val imgH = bH * compositeScale
                                val imgLeft = w / 2 + offsetX - imgW / 2
                                val imgTop = h / 2 + offsetY - imgH / 2
                                return Offset(imgLeft + pt.x * compositeScale, imgTop + pt.y * compositeScale)
                            }
                            val down = awaitFirstDown()
                            val startTime = System.currentTimeMillis()
                            var dragActive = false
                            var totalPan = Offset.Zero
                            var longPressTriggered = false
                            var textSelectingActive = false
                            
                            val startPt = screenToPage(down.position.x, down.position.y)
                            
                            // Helper to find OcrBox at a page position
                            fun findOcrBoxAtPosition(pagePt: Point, pageOcr: PageOcr?): Int {
                                if (pageOcr == null) return -1
                                for ((idx, box) in pageOcr.boxes.withIndex()) {
                                    val left = box.rectN.left * bW
                                    val top = box.rectN.top * bH
                                    val right = box.rectN.right * bW
                                    val bottom = box.rectN.bottom * bH
                                    if (pagePt.x >= left && pagePt.x <= right && pagePt.y >= top && pagePt.y <= bottom) {
                                        return idx
                                    }
                                }
                                return -1
                            }
                            val handleThreshold = 80f / scale  // Larger hitbox for easier grabbing

                            if (mode == ToolMode.PAN) {
                                // Check if tapping on text selection handles first
                                if (isTextSelecting && selectedOcrBoxes.isNotEmpty() && cachedPageOcr != null) {
                                    val startBoxIdx = minOf(textSelectionStartIdx, textSelectionEndIdx)
                                    val endBoxIdx = maxOf(textSelectionStartIdx, textSelectionEndIdx)
                                    if (startBoxIdx >= 0 && endBoxIdx < cachedPageOcr!!.boxes.size) {
                                        val startBox = cachedPageOcr!!.boxes[startBoxIdx]
                                        val endBox = cachedPageOcr!!.boxes[endBoxIdx]
                                        
                                        // Start handle position (left edge, bottom of first box)
                                        val startHandleX = startBox.rectN.left * bW
                                        val startHandleY = startBox.rectN.bottom * bH
                                        
                                        // End handle position (right edge, bottom of last box)
                                        val endHandleX = endBox.rectN.right * bW
                                        val endHandleY = endBox.rectN.bottom * bH
                                        
                                        // Scale hit radius based on text height, with generous minimum for usability
                                        val startBoxHeight = (startBox.rectN.bottom - startBox.rectN.top) * bH
                                        val endBoxHeight = (endBox.rectN.bottom - endBox.rectN.top) * bH
                                        val startHandleHitRadius = (startBoxHeight * 2.5f).coerceIn(50f / scale, 120f / scale)
                                        val endHandleHitRadius = (endBoxHeight * 2.5f).coerceIn(50f / scale, 120f / scale)
                                        
                                        if (dist(startPt, Point(startHandleX, startHandleY)) < startHandleHitRadius) {
                                            draggingSelectionHandle = "start"
                                            isItemDragging = true
                                            Log.d("Blueprint", "Started dragging START handle")
                                        } else if (dist(startPt, Point(endHandleX, endHandleY)) < endHandleHitRadius) {
                                            draggingSelectionHandle = "end"
                                            isItemDragging = true
                                            Log.d("Blueprint", "Started dragging END handle")
                                        }
                                    }
                                }
                                
                                if (selectedMeasurement != null) {
                                    if (dist(startPt, selectedMeasurement!!.p1) < handleThreshold) {
                                        draggingPointIdx = 0
                                        isItemDragging = true
                                        originalMeasurement = selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())
                                    } else if (dist(startPt, selectedMeasurement!!.p2) < handleThreshold) {
                                        draggingPointIdx = 1
                                        isItemDragging = true
                                        originalMeasurement = selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())
                                    }
                                }
                                if (draggingPointIdx == -1 && selectedNote != null) {
                                    val textStyle = TextStyle(fontSize = selectedNote!!.fontSize.sp, fontWeight = if(selectedNote!!.isBold) FontWeight.Bold else FontWeight.Normal)
                                    val textLayoutResult = textMeasurer.measure(selectedNote!!.text, style = textStyle)
                                    val textWidth = textLayoutResult.size.width.toFloat(); val textHeight = textLayoutResult.size.height.toFloat()
                                    val noteRect = Rect(selectedNote!!.x - textWidth/2, selectedNote!!.y - textHeight/2, selectedNote!!.x + textWidth/2, selectedNote!!.y + textHeight/2)
                                    if (noteRect.contains(Offset(startPt.x, startPt.y))) {
                                        draggingNoteIdx = notes.indexOf(selectedNote)
                                        isItemDragging = true
                                        originalNote = selectedNote!!.copyNote()
                                    }
                                }
                                // Check for shape dragging
                                if (draggingPointIdx == -1 && draggingNoteIdx == -1 && selectedShape != null) {
                                    val s = selectedShape!!
                                    val halfW = s.width / 2
                                    val halfH = s.height / 2
                                    
                                    // Allow dragging from anywhere inside the shape bounds
                                    val dx = startPt.x - s.x
                                    val dy = startPt.y - s.y
                                    
                                    if (kotlin.math.abs(dx) <= halfW + 30f && kotlin.math.abs(dy) <= halfH + 30f) {
                                        draggingShape = true
                                        isItemDragging = true
                                        originalShape = s.copy()
                                    }
                                }
                            } else if (mode == ToolMode.SCALE && firstPoint != null && secondPoint != null) {
                                if (dist(startPt, firstPoint!!) < handleThreshold) calibratePointIdx = 0
                                else if (dist(startPt, secondPoint!!) < handleThreshold) calibratePointIdx = 1
                            }

                            do {
                                val event = awaitPointerEvent()
                                val pointers = event.changes
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
                                    val mIdx = measurements.indexOf(selectedMeasurement)
                                    if (mIdx != -1) {
                                        val updatedM = measurements[mIdx].copyMeasurement(
                                            p1 = if (draggingPointIdx == 0) currentPt.copyPoint() else measurements[mIdx].p1.copyPoint(),
                                            p2 = if (draggingPointIdx == 1) currentPt.copyPoint() else measurements[mIdx].p2.copyPoint()
                                        )
                                        if (currentScale != null) {
                                            val dx = updatedM.p1.x - updatedM.p2.x
                                            val dy = updatedM.p1.y - updatedM.p2.y
                                            updatedM.text = formatFeet(sqrt(dx*dx + dy*dy) / currentScale.pixelsPerFoot)
                                        }
                                        measurements[mIdx] = updatedM
                                        selectedMeasurement = updatedM
                                    }
                                    change.consume()
                                    dragActive = true
                                } else if (draggingNoteIdx != -1) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    val updatedN = notes[draggingNoteIdx].copyNote()
                                    updatedN.x = currentPt.x
                                    updatedN.y = currentPt.y
                                    notes[draggingNoteIdx] = updatedN
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
                                } else if (draggingShape && selectedShape != null) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    val idx = shapes.indexOfFirst { it.id == selectedShape!!.id }
                                    if (idx != -1) {
                                        // Replace element in list to trigger recomposition
                                        val updated = shapes[idx].copy(x = currentPt.x, y = currentPt.y)
                                        shapes[idx] = updated
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
                                            val shape = shapes[idx]
                                            val newWidthRatio = (shape.widthRatio * zoom).coerceIn(0.01f, 1f)
                                            val newHeightRatio = (shape.heightRatio * zoom).coerceIn(0.01f, 1f)
                                            val newRotation = shape.rotation + rotation
                                            val updated = shape.copy(widthRatio = newWidthRatio, heightRatio = newHeightRatio, rotation = newRotation)
                                            shapes[idx] = updated
                                            selectedShape = updated
                                            resizingShape = true
                                        }
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else if (selectedNoteIdx != -1) {
                                        val cur = notes[selectedNoteIdx].copyNote()
                                        cur.fontSize = (cur.fontSize * zoom).coerceIn(8f, 200f)
                                        cur.rotation = cur.rotation + rotation
                                        notes[selectedNoteIdx] = cur
                                        selectedNote = cur
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else if (selectedNote != null) {
                                        val idx = notes.indexOfFirst { it === selectedNote }
                                        if (idx != -1) {
                                            val cur = notes[idx].copyNote()
                                            cur.fontSize = (cur.fontSize * zoom).coerceIn(8f, 200f)
                                            cur.rotation = cur.rotation + rotation
                                            notes[idx] = cur
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
                                    val elapsed = System.currentTimeMillis() - startTime
                                    
                                    // Debug logging
                                    if (mode == ToolMode.PAN && !longPressTriggered && !isItemDragging) {
                                        Log.d("Blueprint", "PAN gesture: elapsed=${elapsed}ms, totalPan=${totalPan.getDistance()}, isItemDragging=$isItemDragging")
                                    }
                                    
                                    // Check for long press to start text selection (400ms hold without much movement)
                                    if (!longPressTriggered && mode == ToolMode.PAN && elapsed > 400 && totalPan.getDistance() < 15f && !isItemDragging) {
                                        Log.d("Blueprint", "Long press detected! elapsed=${elapsed}ms")
                                        // Try to load OCR data and find text at this position
                                        val cacheNamespace = sessionToken?.sourceCacheKey ?: uri.toString()
                                        val pageOcr = cachedPageOcr ?: pdfSearchEngine.getCachedPageOcr(uri, pageIndex, cacheNamespace)
                                        Log.d("Blueprint", "pageOcr cached: ${pageOcr != null}, boxes: ${pageOcr?.boxes?.size ?: 0}")
                                        if (pageOcr != null) {
                                            cachedPageOcr = pageOcr
                                            val boxIdx = findOcrBoxAtPosition(startPt, pageOcr)
                                            Log.d("Blueprint", "Finding box at startPt=(${ startPt.x}, ${startPt.y}), found boxIdx=$boxIdx")
                                            if (boxIdx != -1) {
                                                longPressTriggered = true
                                                textSelectingActive = true
                                                isTextSelecting = true
                                                textSelectionStartIdx = boxIdx
                                                textSelectionEndIdx = boxIdx
                                                selectedOcrBoxes = listOf(pageOcr.boxes[boxIdx])
                                                showCopyButton = false
                                                Log.d("Blueprint", "Text selection started at box: ${pageOcr.boxes[boxIdx].text}")
                                            } else {
                                                longPressTriggered = true // Don't keep checking
                                                Log.d("Blueprint", "No OCR box found at position")
                                            }
                                        } else {
                                            // OCR not cached, trigger loading in background
                                            longPressTriggered = true // prevent re-triggering
                                            Log.d("Blueprint", "OCR not cached, triggering background load")
                                            val loadOcr: suspend () -> Unit = {
                                                try {
                                                    val loaded = pdfSearchEngine.loadPageOcr(uri, pageIndex, cacheNamespace)
                                                    if (isPageCurrent(sessionToken, pageIndex)) {
                                                        cachedPageOcr = loaded
                                                        Log.d("Blueprint", "OCR loaded for page $pageIndex")
                                                    }
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (e: Exception) {
                                                    Log.e("Blueprint", "Failed to load OCR", e)
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
                                    } else if (!textSelectingActive) {
                                        val pan = event.calculatePan()
                                        totalPan += pan
                                        if (centroid != Offset.Unspecified) {
                                            offsetX = (offsetX + pan.x).coerceIn(-(vW * scale) / 2, (vW * scale) / 2)
                                            offsetY = (offsetY + pan.y).coerceIn(-(vH * scale) / 2, (vH * scale) / 2)
                                        }
                                        pointers.forEach { it.consume() }
                                        if (totalPan.getDistance() > 10f) dragActive = true
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
                            
                                // Handle selection handle release
                                if (draggingSelectionHandle != null) {
                                    draggingSelectionHandle = null
                                    isItemDragging = false
                                    // Update copy button position
                                    if (cachedPageOcr != null && selectedOcrBoxes.isNotEmpty()) {
                                        val endIdx = maxOf(textSelectionStartIdx, textSelectionEndIdx)
                                        if (endIdx >= 0 && endIdx < cachedPageOcr!!.boxes.size) {
                                            val lastBox = cachedPageOcr!!.boxes[endIdx]
                                            val boxRight = lastBox.rectN.right * bW
                                            val boxBottom = lastBox.rectN.bottom * bH
                                            val screenPos = pageToScreen(Point(boxRight, boxBottom))
                                            copyButtonPos = Offset(screenPos.x + 10f, screenPos.y + 10f)
                                        }
                                    }
                                    Log.d("Blueprint", "Handle drag complete, selection: ${selectedOcrBoxes.size} words")
                                } else if (textSelectingActive && selectedOcrBoxes.isNotEmpty()) {
                                    // Handle text selection release - show copy button
                                    isTextSelecting = true
                                    showCopyButton = true
                                    // Position copy button near the end of selection
                                    if (cachedPageOcr != null && textSelectionEndIdx >= 0 && textSelectionEndIdx < cachedPageOcr!!.boxes.size) {
                                        val lastBox = cachedPageOcr!!.boxes[textSelectionEndIdx]
                                        val boxRight = lastBox.rectN.right * bW
                                        val boxBottom = lastBox.rectN.bottom * bH
                                        val screenPos = pageToScreen(Point(boxRight, boxBottom))
                                        copyButtonPos = Offset(screenPos.x + 10f, screenPos.y + 10f)
                                    }
                                    Log.d("Blueprint", "Text selection complete: ${selectedOcrBoxes.size} words selected")
                                } else if (draggingPointIdx != -1) {
                                    if (originalMeasurement != null && selectedMeasurement != null) {
                                        onActionAdded(HistoryAction.UpdateMeasurement(originalMeasurement!!, selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())))
                                    }
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
                                        val p1s = Offset(imgLeft + m.p1.x * compScale, imgTop + m.p1.y * compScale)
                                        val p2s = Offset(imgLeft + m.p2.x * compScale, imgTop + m.p2.y * compScale)
                                        selectionToolbarPos = Offset((p1s.x + p2s.x) / 2 + 50f, (p1s.y + p2s.y) / 2)
                                    }
                                } else if (draggingNoteIdx != -1) {
                                    if (originalNote != null && selectedNote != null) {
                                        onActionAdded(HistoryAction.UpdateNote(originalNote!!, selectedNote!!.copyNote()))
                                    }
                                    draggingNoteIdx = -1
                                    originalNote = null
                                    isItemDragging = false
                                    if (selectedNote != null) {
                                        selectedItem = PageItem.NoteItem(selectedNote!!)
                                        // Update toolbar position to the note's new position
                                        val n = selectedNote!!
                                        val baseScale = if (bW > 0f) (vW / bW) else 1f
                                        val compScale = baseScale * scale
                                        val imgW = bW * compScale
                                        val imgH = bH * compScale
                                        val imgLeft = w / 2 + offsetX - imgW / 2
                                        val imgTop = h / 2 + offsetY - imgH / 2
                                        selectionToolbarPos = Offset(imgLeft + n.x * compScale + 50f, imgTop + n.y * compScale)
                                    }
                                
                            } else if ((draggingShape || rotatingShape || resizingShape) && selectedShape != null) {
                                if (originalShape != null) {
                                    onActionAdded(HistoryAction.UpdateShape(originalShape!!, selectedShape!!.copy()))
                                }
                                draggingShape = false
                                rotatingShape = false
                                resizingShape = false
                                originalShape = null
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
                                    selectionToolbarPos = Offset(imgLeft + s.x * compScale + 50f, imgTop + s.y * compScale)
                                }
                            } else if (calibratePointIdx != -1) {
                                calibratePointIdx = -1
                            } else if (dragActive && currentStroke.isNotEmpty()) {
                                val newPath = DrawnPath(currentStroke.toList(), if(mode == ToolMode.HIGHLIGHTER) Color.Yellow.toArgb() else Color.Red.toArgb(), if(mode == ToolMode.HIGHLIGHTER) 12f else 2f, mode == ToolMode.HIGHLIGHTER)
                                paths.add(newPath)
                                onActionAdded(HistoryAction.AddPath(newPath))
                                currentStroke.clear()
                            } else if (!dragActive && mode == ToolMode.PAN) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                // Find ALL items at tap location for disambiguation
                                val foundItems = mutableListOf<PageItem>()
                                val thresholdSegment = 60f / scale
                                
                                // Check measurements
                                for (m in measurements) { 
                                    if (distToSegment(tapPt, m.p1, m.p2) < thresholdSegment) { 
                                        foundItems.add(PageItem.Measure(m))
                                    } 
                                }
                                // Check notes
                                for (n in notes) {
                                    val textStyle = TextStyle(fontSize = n.fontSize.sp, fontWeight = if(n.isBold) FontWeight.Bold else FontWeight.Normal)
                                    val textLayoutResult = textMeasurer.measure(n.text, style = textStyle)
                                    val textWidth = textLayoutResult.size.width.toFloat(); val textHeight = textLayoutResult.size.height.toFloat()
                                    val noteRect = Rect(n.x - textWidth/2, n.y - textHeight/2, n.x + textWidth/2, n.y + textHeight/2)
                                    if (noteRect.contains(Offset(tapPt.x, tapPt.y))) { 
                                        foundItems.add(PageItem.NoteItem(n))
                                    }
                                }
                                // Check paths
                                for (p in paths) {
                                    var pathHit = false
                                    for (i in 0 until p.points.size - 1) { 
                                        if (distToSegment(tapPt, p.points[i], p.points[i+1]) < thresholdSegment + (p.strokeWidth / 2f)) { 
                                            pathHit = true
                                            break 
                                        } 
                                    }
                                    if (pathHit) foundItems.add(PageItem.Path(p))
                                }
                                // Check photo pins
                                val pinThreshold = 100f / scale
                                for (pin in photoPins) {
                                    val dx = tapPt.x - pin.x
                                    val dy = tapPt.y - pin.y
                                    if (sqrt(dx*dx + dy*dy) < pinThreshold) { 
                                        foundItems.add(PageItem.PhotoPinItem(pin))
                                    }
                                }
                                // Check shapes
                                for (s in shapes) {
                                    val dx = tapPt.x - s.x
                                    val dy = tapPt.y - s.y
                                    val halfW = s.width / 2
                                    val halfH = s.height / 2
                                    if (kotlin.math.abs(dx) <= halfW + 40f && kotlin.math.abs(dy) <= halfH + 40f) {
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
                                    if (found is PageItem.NoteItem) {
                                        selectedNote = found.data
                                        selectedNoteIdx = notes.indexOf(found.data)
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
                                photoPins.add(newPin)
                                onActionAdded(HistoryAction.AddPhotoPin(newPin))
                            } else if (!dragActive && mode == ToolMode.SHAPE) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                shapePos = tapPt
                                shapePageWidth = bW
                                shapePageHeight = bH
                                showShapeDialog = true
                            } else if (!dragActive && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                                val pt = screenToPage(down.position.x, down.position.y)
                                if (firstPoint == null) firstPoint = pt else if (secondPoint == null) { 
                                    secondPoint = pt
                                    if (mode == ToolMode.MEASURE) {
                                        if (currentScale != null) {
                                            val dx = firstPoint!!.x - secondPoint!!.x
                                            val dy = firstPoint!!.y - secondPoint!!.y
                                            val dist = sqrt(dx * dx + dy * dy)
                                            val text = formatFeet(sqrt(dx*dx + dy*dy) / currentScale.pixelsPerFoot)
                                            val newM = Measurement(firstPoint!!, secondPoint!!, text)
                                            measurements.add(newM)
                                            onActionAdded(HistoryAction.AddMeasurement(newM))
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
                        return Offset(imgLeft + p.x * compositeScale, imgTop + p.y * compositeScale)
                    }
                    paths.forEach { pathData ->
                        if (pathData.points.size > 1) {
                            val path = Path(); path.moveTo(toS(pathData.points[0]).x, toS(pathData.points[0]).y)
                            for (i in 1 until pathData.points.size) { val p = toS(pathData.points[i]); path.lineTo(p.x, p.y) }
                            drawPath(path, Color(pathData.colorArgb), if (pathData.isHighlighter) 0.4f else 1f, style = Stroke(pathData.strokeWidth * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    measurements.forEach { m ->
                        val p1 = toS(m.p1); val p2 = toS(m.p2)
                        val color = if (m == selectedMeasurement) Color.Cyan else Color(0xFFE91E63)
                        drawLine(color, p1, p2, strokeWidth = 4f)
                        drawCircle(color, 6f, p1); drawCircle(color, 6f, p2)
                        if (m == selectedMeasurement) {
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
                    notes.forEach { n ->
                        val p = toS(Point(n.x, n.y))
                        val nMatches = searchTerm.isNotBlank() && n.text.contains(searchTerm, ignoreCase = true)
                        val txtColor = if (n == selectedNote) Color.Cyan else Color.Black
                        val textStyle = TextStyle(fontSize = (n.fontSize * scale).sp, fontWeight = if(n.isBold) FontWeight.Bold else FontWeight.Normal, color = txtColor)
                        val textLayoutResult = textMeasurer.measure(n.text, style = textStyle)
                        val textWidth = textLayoutResult.size.width.toFloat(); val textHeight = textLayoutResult.size.height.toFloat()
                        val textTopLeft = p - Offset(textWidth / 2f, textHeight / 2f)

                        rotate(degrees = n.rotation, pivot = p) {
                            if (nMatches) {
                                drawRect(color = Color.Yellow, topLeft = textTopLeft - Offset(8f, 4f), size = Size(textWidth + 16f, textHeight + 8f))
                            }
                            if (n == selectedNote) {
                                val dashedStroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                                drawRect(color = Color.Cyan, topLeft = textTopLeft - Offset(8f, 4f), size = Size(textWidth + 16f, textHeight + 8f), style = dashedStroke)
                            }
                            drawText(textLayoutResult, topLeft = textTopLeft)
                        }
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
                    shapes.forEach { shape ->
                        val center = toS(Point(shape.x, shape.y))
                        // Use ratio-based dimensions if available, otherwise fall back to legacy
                        val pageMaxDim = maxOf(bW, bH)
                        val actualWidth = if (shape.widthRatio > 0f) shape.widthRatio * bW else shape.width
                        val actualHeight = if (shape.heightRatio > 0f) shape.heightRatio * bH else shape.height
                        val actualStrokeWidth = if (shape.strokeWidthRatio > 0f) shape.strokeWidthRatio * pageMaxDim else shape.strokeWidth
                        // Scale by compositeScale (baseScale * scale) to match position transformation
                        val scaledWidth = actualWidth * compositeScale
                        val scaledHeight = actualHeight * compositeScale
                        val scaledStroke = actualStrokeWidth * compositeScale
                        val isSelected = shape == selectedShape
                        val shapeColor = if (isSelected) Color.Cyan else Color(shape.colorArgb)
                        
                        // Debug: show what percentage of page the shape covers
                        val widthPercent = actualWidth / bW * 100
                        Log.d("Blueprint", "Shape CANVAS: widthRatio=${shape.widthRatio}, bW=$bW, actualW=$actualWidth (${widthPercent}% of page), compositeScale=$compositeScale, scaledW=$scaledWidth")
                        
                        rotate(degrees = shape.rotation, pivot = center) {
                            when (shape.type) {
                                ShapeType.RECTANGLE -> {
                                    drawRect(
                                        color = shapeColor,
                                        topLeft = center - Offset(scaledWidth / 2, scaledHeight / 2),
                                        size = Size(scaledWidth, scaledHeight),
                                        style = if (shape.isFilled) Fill else Stroke(width = scaledStroke)
                                    )
                                }
                                ShapeType.CIRCLE -> {
                                    drawOval(
                                        color = shapeColor,
                                        topLeft = center - Offset(scaledWidth / 2, scaledHeight / 2),
                                        size = Size(scaledWidth, scaledHeight),
                                        style = if (shape.isFilled) Fill else Stroke(width = scaledStroke)
                                    )
                                }
                                ShapeType.ARROW -> {
                                    // Draw arrow line from left to right
                                    val halfW = scaledWidth / 2
                                    val arrowHeadLength = minOf(halfW * 0.3f, 30f * scale)
                                    
                                    // Main line
                                    drawLine(
                                        color = shapeColor,
                                        start = center - Offset(halfW, 0f),
                                        end = center + Offset(halfW, 0f),
                                        strokeWidth = scaledStroke
                                    )
                                    
                                    // Arrow head lines
                                    val headOffset = scaledHeight * 0.3f
                                    drawLine(
                                        color = shapeColor,
                                        start = center + Offset(halfW, 0f),
                                        end = center + Offset(halfW - arrowHeadLength, -headOffset),
                                        strokeWidth = scaledStroke
                                    )
                                    drawLine(
                                        color = shapeColor,
                                        start = center + Offset(halfW, 0f),
                                        end = center + Offset(halfW - arrowHeadLength, headOffset),
                                        strokeWidth = scaledStroke
                                    )
                                }
                                ShapeType.CLOUD -> {
                                    // Draw cloud shape as a rounded bumpy outline
                                    val cloudPath = Path()
                                    val numBumps = 12
                                    val halfW = scaledWidth / 2
                                    val halfH = scaledHeight / 2
                                    
                                    // Create cloud outline using cubic bezier curves
                                    for (i in 0 until numBumps) {
                                        val angle = (i.toFloat() / numBumps) * 2f * Math.PI.toFloat()
                                        val nextAngle = ((i + 1).toFloat() / numBumps) * 2f * Math.PI.toFloat()
                                        
                                        // Vary the radius to create bumpy effect
                                        val bumpOffset = if (i % 2 == 0) 0.15f else 0f
                                        val r1 = 1f + bumpOffset
                                        val r2 = 1f + (if ((i + 1) % 2 == 0) 0.15f else 0f)
                                        
                                        val x1 = center.x + halfW * r1 * kotlin.math.cos(angle)
                                        val y1 = center.y + halfH * r1 * kotlin.math.sin(angle)
                                        val x2 = center.x + halfW * r2 * kotlin.math.cos(nextAngle)
                                        val y2 = center.y + halfH * r2 * kotlin.math.sin(nextAngle)
                                        
                                        if (i == 0) {
                                            cloudPath.moveTo(x1, y1)
                                        }
                                        
                                        // Create outward bump
                                        val midAngle = (angle + nextAngle) / 2
                                        val bumpRadius = 1.25f
                                        val ctrlX = center.x + halfW * bumpRadius * kotlin.math.cos(midAngle)
                                        val ctrlY = center.y + halfH * bumpRadius * kotlin.math.sin(midAngle)
                                        
                                        cloudPath.quadraticBezierTo(ctrlX, ctrlY, x2, y2)
                                    }
                                    cloudPath.close()
                                    
                                    drawPath(
                                        path = cloudPath,
                                        color = shapeColor,
                                        style = if (shape.isFilled) Fill else Stroke(width = scaledStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                }
                            }
                            
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
                            val lpx = box.rectN.left * bW
                            val tpx = box.rectN.top * bH
                            val rpx = box.rectN.right * bW
                            val bpx = box.rectN.bottom * bH
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
                                
                                // Calculate text height in screen pixels for scaling
                                val startBoxHeight = (startBox.rectN.bottom - startBox.rectN.top) * bH * compositeScale
                                val endBoxHeight = (endBox.rectN.bottom - endBox.rectN.top) * bH * compositeScale
                                
                                val handleColor = Color(0xFF2196F3)
                                
                                // Start handle (left side of first box, bottom)
                                val startHandleRadius = (startBoxHeight * 0.5f).coerceIn(6f, 20f)
                                val startStemHeight = startBoxHeight * 0.8f
                                val startHandlePos = toS(Point(startBox.rectN.left * bW, startBox.rectN.bottom * bH))
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
                                val endHandlePos = toS(Point(endBox.rectN.right * bW, endBox.rectN.bottom * bH))
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
                            Log.d("Blueprint", "highlightRects bounds norm min=($minL,$minT) max=($maxR,$maxB)")
                        } catch (_: Exception) {}
                    }

                    for (hr in highlightRects) {
                        // hr is normalized (0..1) relative to page bitmap: convert to bitmap pixels then expand by 25% and map to screen
                        val rawLeftPx = hr.left * bW
                        val rawTopPx = hr.top * bH
                        val rawRightPx = hr.right * bW
                        val rawBottomPx = hr.bottom * bH
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
                                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp, modifier = Modifier.clickable { showScaleDialog = true }) {
                                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Check, null, tint = Color.White); Spacer(Modifier.width(12.dp))
                                    Text("Confirm Calibration", color = Color.White, fontWeight = FontWeight.Bold)
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
                                Text("Select item:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
                                            is PageItem.Measure -> "Measure"
                                            is PageItem.NoteItem -> "Note"
                                            is PageItem.Path -> if (item.data.isHighlighter) "Highlight" else "Drawing"
                                            is PageItem.PhotoPinItem -> "Photo"
                                            is PageItem.ShapeItem -> item.data.type.name.lowercase().replaceFirstChar { it.uppercase() }
                                        }
                                        
                                        Surface(
                                            modifier = Modifier.clickable {
                                                selectedItem = item
                                                showItemPicker = false
                                                overlappingItems = emptyList()
                                                selectedMeasurement = if (item is PageItem.Measure) item.data else null
                                                if (item is PageItem.NoteItem) {
                                                    selectedNote = item.data
                                                    selectedNoteIdx = notes.indexOf(item.data)
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
                                            Icon(Icons.Default.Close, contentDescription = "Cancel", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("Cancel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = { itemToDelete = selectedItem },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color.Red)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Delete", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                                    }
                                } else if (selectedItem is PageItem.PhotoPinItem) {
                                    TextButton(
                                        onClick = {
                                            val documentId = sessionToken?.documentId ?: return@TextButton
                                            if (selectedPhotoPin?.imageFileNames?.size ?: 0 >= Stage5Limits.MAX_PHOTOS_PER_PIN) {
                                                Log.w("Blueprint", "Photo pin has reached its photo limit")
                                                return@TextButton
                                            }
                                            val referencedPhotoCount = allPagePhotoPins.values.sumOf { pins ->
                                                pins.sumOf { pin -> pin.imageFileNames.size }
                                            }
                                            if (referencedPhotoCount >= Stage5Limits.MAX_TOTAL_PHOTOS) {
                                                Log.w("Blueprint", "Document has reached its photo limit")
                                                return@TextButton
                                            }
                                            var captureFile: File? = null
                                            try {
                                                captureFile = DocumentPhotoAssetStore(context.filesDir, documentId).use { store ->
                                                    store.newCaptureFile()
                                                }
                                                val photoUri = androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    captureFile
                                                )
                                                pendingPhotoCaptureFile = captureFile
                                                pendingPhotoDocumentId = documentId
                                                pendingPhotoUri = photoUri
                                                pendingPhotoSessionToken = sessionToken
                                                pendingPhotoPageIndex = pageIndex
                                                pendingPhotoPinId = selectedPhotoPin?.id
                                                cameraLauncher.launch(photoUri)
                                            } catch (error: Throwable) {
                                                captureFile?.let { file ->
                                                    runCatching {
                                                        DocumentPhotoAssetStore(context.filesDir, documentId).use { store ->
                                                            store.discardCaptureFile(file)
                                                        }
                                                    }
                                                }
                                                Log.e("Blueprint", "Failed to prepare camera photo", error)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.AddAPhoto, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Add", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = { showPinImageGallery = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("View", style = MaterialTheme.typography.labelSmall)
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
                                        Text("Delete", color = Color.Red, style = MaterialTheme.typography.labelSmall)
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
                            val clip = ClipData.newPlainText("Selected Text", selectedText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied: $selectedText", Toast.LENGTH_SHORT).show()
                            
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
                            Text("Copy", color = Color.White, style = MaterialTheme.typography.labelMedium)
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
                            contentDescription = "Cancel",
                            Modifier.padding(6.dp).size(16.dp), 
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    
    // Full screen image viewer - rendered on top of everything
    if (fullScreenImageFile != null) {
        val photoBytes = runCatching { photoBytesFor(context, sessionToken, fullScreenImageFile!!) }.getOrNull()
        if (photoBytes != null) {
            // Load bitmap with EXIF rotation applied
            val rotatedBmp = remember(sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex, fullScreenImageFile) {
                val originalBmp = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                if (originalBmp != null) {
                    try {
                        val exif = ByteArrayInputStream(photoBytes).use { ExifInterface(it) }
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        val matrix = Matrix()
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                        }
                        if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                            Bitmap.createBitmap(originalBmp, 0, 0, originalBmp.width, originalBmp.height, matrix, true)
                        } else {
                            originalBmp
                        }
                    } catch (e: Exception) {
                        originalBmp
                    }
                } else null
            }
            if (rotatedBmp != null) {
                var imageScale by remember { mutableStateOf(1f) }
                var imageOffsetX by remember { mutableStateOf(0f) }
                var imageOffsetY by remember { mutableStateOf(0f) }
                val density = LocalDensity.current
                
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
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
                        if (selectedPhotoPin == null || fullScreenImageFile == null) return null
                        val imageNotes = selectedPhotoPin!!.imageNotes[fullScreenImageFile!!] ?: return null
                        
                        for (note in imageNotes) {
                            val notePos = imageToScreenCoords(note.x, note.y)
                            // Calculate text dimensions using fontSizeRatio (same as display)
                            val fontSizePx = if (note.fontSizeRatio > 0) {
                                note.fontSizeRatio * displayedImgHeight
                            } else {
                                note.fontSize * density.density * imageScale
                            }
                            val estimatedWidth = note.text.length * fontSizePx * 0.6f
                            val estimatedHeight = fontSizePx * 1.2f
                            
                            // Check if tap is within text bounds (top-left positioned)
                            val padding = 10f * density.density
                            if (screenX >= notePos.x - padding && screenX <= notePos.x + estimatedWidth + padding &&
                                screenY >= notePos.y - padding && screenY <= notePos.y + estimatedHeight + padding) {
                                return note
                            }
                        }
                        return null
                    }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(sessionToken, imageNoteToolMode, selectedPhotoPin, fullScreenImageFile) {
                                awaitEachGesture {
                                    val firstDown = awaitFirstDown()
                                    val startPos = firstDown.position
                                    var wasDrag = false
                                    var wasZoom = false
                                    var imageDocumentChanged = false
                                    
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
                                                val noteX = currentImgLeft + note.x * currentDisplayedWidth
                                                val noteY = currentImgTop + note.y * currentDisplayedHeight
                                                
                                                // Use fontSizeRatio for proper scaling (same as display)
                                                val fontSizePx = if (note.fontSizeRatio > 0) {
                                                    note.fontSizeRatio * currentDisplayedHeight
                                                } else {
                                                    note.fontSize * density.density * imageScale
                                                }
                                                
                                                // Estimate text dimensions: ~0.6 * fontSize per character width, fontSize * 1.2 for height
                                                val estimatedTextWidth = note.text.length * fontSizePx * 0.6f
                                                val estimatedTextHeight = fontSizePx * 1.2f
                                                
                                                // Check if tap is within text bounding box (with some padding)
                                                val padding = 10f * density.density
                                                if (startPos.x >= noteX - padding && 
                                                    startPos.x <= noteX + estimatedTextWidth + padding &&
                                                    startPos.y >= noteY - padding && 
                                                    startPos.y <= noteY + estimatedTextHeight + padding) {
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
                                    }
                                    
                                    // Check if tapped on a shape
                                    var tappedShape: Shape? = null
                                    if (selectedPhotoPin != null && fullScreenImageFile != null && imageNoteToolMode != "place") {
                                        val imageShapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!] ?: emptyList()
                                        for (shape in imageShapes) {
                                            val shapeX = currentImgLeft + shape.x * currentDisplayedWidth
                                            val shapeY = currentImgTop + shape.y * currentDisplayedHeight
                                            val scaledHalfW = (shape.width * currentDisplayedWidth) / 2
                                            val scaledHalfH = (shape.height * currentDisplayedHeight) / 2
                                            
                                            val dx = startPos.x - shapeX
                                            val dy = startPos.y - shapeY
                                            if (kotlin.math.abs(dx) <= scaledHalfW + 30f && kotlin.math.abs(dy) <= scaledHalfH + 30f) {
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
                                    }
                                    
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.size >= 2) {
                                            wasZoom = true
                                            val zoom = event.calculateZoom()
                                            val rotation = event.calculateRotation()
                                            if (selectedImageNote != null) {
                                                // Pinch to resize/rotate note - update fontSizeRatio for device independence
                                                val newFontSizeRatio = (selectedImageNote!!.fontSizeRatio * zoom).coerceIn(0.01f, 0.2f)
                                                selectedImageNote!!.fontSizeRatio = newFontSizeRatio
                                                selectedImageNote!!.rotation += rotation
                                                imageDocumentChanged = true
                                                noteUpdateTrigger++ // Force recomposition for live update
                                            } else if (selectedImageShape != null) {
                                                // Pinch to resize/rotate shape
                                                val idx = selectedPhotoPin?.imageShapes?.get(fullScreenImageFile)?.indexOfFirst { it.id == selectedImageShape!!.id } ?: -1
                                                if (idx != -1) {
                                                    val shapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!]!!
                                                    val newWidthRatio = (shapes[idx].widthRatio * zoom).coerceIn(0.01f, 1f)
                                                    val newHeightRatio = (shapes[idx].heightRatio * zoom).coerceIn(0.01f, 1f)
                                                    val newRotation = shapes[idx].rotation + rotation
                                                    val updated = shapes[idx].copy(widthRatio = newWidthRatio, heightRatio = newHeightRatio, rotation = newRotation)
                                                    shapes[idx] = updated
                                                    selectedImageShape = updated
                                                    imageDocumentChanged = true
                                                    resizingImageShape = true
                                                }
                                                noteUpdateTrigger++
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
                                                    draggingImageNote!!.x += delta.x / dragDisplayedWidth
                                                    draggingImageNote!!.y += delta.y / dragDisplayedHeight
                                                    // Clamp to image bounds
                                                    draggingImageNote!!.x = draggingImageNote!!.x.coerceIn(0f, 1f)
                                                    draggingImageNote!!.y = draggingImageNote!!.y.coerceIn(0f, 1f)
                                                    imageDocumentChanged = true
                                                    noteUpdateTrigger++ // Force recomposition for live update
                                                } else if (draggingImageShape && selectedImageShape != null) {
                                                    // Move the shape
                                                    val dragFitScale = minOf(size.width.toFloat() / rotatedBmp.width, size.height.toFloat() / rotatedBmp.height)
                                                    val dragDisplayedWidth = rotatedBmp.width * dragFitScale * imageScale
                                                    val dragDisplayedHeight = rotatedBmp.height * dragFitScale * imageScale
                                                    val idx = selectedPhotoPin?.imageShapes?.get(fullScreenImageFile)?.indexOfFirst { it.id == selectedImageShape!!.id } ?: -1
                                                    if (idx != -1) {
                                                        val shapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!]!!
                                                        val newX = (shapes[idx].x + delta.x / dragDisplayedWidth).coerceIn(0f, 1f)
                                                        val newY = (shapes[idx].y + delta.y / dragDisplayedHeight).coerceIn(0f, 1f)
                                                        val updated = shapes[idx].copy(x = newX, y = newY)
                                                        shapes[idx] = updated
                                                        selectedImageShape = updated
                                                        imageDocumentChanged = true
                                                    }
                                                    noteUpdateTrigger++
                                                } else {
                                                    // Pan image
                                                    imageOffsetX += delta.x
                                                    imageOffsetY += delta.y
                                                }
                                                change.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    if (imageDocumentChanged) onDocumentChanged()

                                    // Handle tap (not drag)
                                    if (!wasDrag && !wasZoom) {
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
                                                currentImageOriginalHeight = placeDisplayedHeight
                                                currentImageDensity = density.density
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
                                                    width = defaultWidthRatio, // Legacy: percentage of image width
                                                    height = defaultHeightRatio, // Legacy: percentage of image height
                                                    rotation = 0f,
                                                    type = currentImageShapeType,
                                                    colorArgb = android.graphics.Color.RED,
                                                    strokeWidth = 4f, // Legacy field
                                                    isFilled = false,
                                                    strokeWidthRatio = strokeWidthRatio,
                                                    widthRatio = defaultWidthRatio,
                                                    heightRatio = defaultHeightRatio
                                                )
                                                
                                                if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                                    if (!selectedPhotoPin!!.imageShapes.containsKey(fullScreenImageFile!!)) {
                                                        selectedPhotoPin!!.imageShapes[fullScreenImageFile!!] = mutableListOf()
                                                    }
                                                    selectedPhotoPin!!.imageShapes[fullScreenImageFile!!]!!.add(newShape)
                                                    selectedImageShape = newShape
                                                    noteUpdateTrigger++
                                                    onDocumentChanged()
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
                                    
                                    draggingImageNote = null
                                    draggingImageShape = false
                                    resizingImageShape = false
                                }
                            }
                    ) {
                        // The image
                        Image(
                            bitmap = rotatedBmp.asImageBitmap(),
                            contentDescription = "Full screen photo",
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
                        val noteTextMeasurer = rememberTextMeasurer()
                        if (selectedPhotoPin != null && fullScreenImageFile != null) {
                            // Read trigger to force recomposition
                            val updateTrigger = noteUpdateTrigger
                            val imageNotes = selectedPhotoPin!!.imageNotes[fullScreenImageFile!!] ?: emptyList()
                            val currentSelectedNote = selectedImageNote  // Capture for recomposition
                            
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                // Reference updateTrigger inside Canvas to trigger redraws
                                @Suppress("UNUSED_VARIABLE")
                                val triggerRedraw = updateTrigger + (currentSelectedNote?.hashCode() ?: 0)
                                imageNotes.forEach { imageNote ->
                                    val noteScreenPos = imageToScreenCoords(imageNote.x, imageNote.y)
                                    
                                    // Calculate where note is ON the image (pixels from image top-left)
                                    val noteOnImgX = imageNote.x * displayedImgWidth
                                    val noteOnImgY = imageNote.y * displayedImgHeight
                                    Log.d("Blueprint", "ImageNote CANVAS: relPos=(${imageNote.x}, ${imageNote.y}), screenPos=(${noteScreenPos.x}, ${noteScreenPos.y}), imgBounds=(left=$imgLeft, top=$imgTop, w=$displayedImgWidth, h=$displayedImgHeight), onImg=($noteOnImgX, $noteOnImgY)")
                                    
                                    val isSelected = selectedImageNote == imageNote
                                    
                                    // Use fontSizeRatio if available (new format), otherwise fall back to legacy fontSize
                                    // displayedImgHeight already includes imageScale, so no need to multiply again
                                    val fontSizePx = if (imageNote.fontSizeRatio > 0) {
                                        imageNote.fontSizeRatio * displayedImgHeight
                                    } else {
                                        imageNote.fontSize * density.density * imageScale
                                    }
                                    val fontSizeSp = with(density) { fontSizePx.toSp() }
                                    
                                    // Use Compose text measuring and drawing
                                    val noteColor = if (isSelected) Color.Cyan else Color.Yellow
                                    val textStyle = TextStyle(
                                        fontSize = fontSizeSp,
                                        fontWeight = if (imageNote.isBold) FontWeight.Bold else FontWeight.Normal,
                                        color = noteColor,
                                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(2f, 2f), 4f)
                                    )
                                    val textLayoutResult = noteTextMeasurer.measure(imageNote.text, style = textStyle)
                                    
                                    // Calculate text center for rotation pivot
                                    val textWidth = textLayoutResult.size.width.toFloat()
                                    val textHeight = textLayoutResult.size.height.toFloat()
                                    val textCenter = Offset(
                                        noteScreenPos.x + textWidth / 2f,
                                        noteScreenPos.y + textHeight / 2f
                                    )
                                    
                                    rotate(degrees = imageNote.rotation, pivot = textCenter) {
                                        // Draw text with top-left at noteScreenPos
                                        drawText(textLayoutResult, topLeft = noteScreenPos)
                                    }
                                }
                            }
                        }
                        
                        // Draw shapes on image
                        if (selectedPhotoPin != null && fullScreenImageFile != null) {
                            val imageShapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!] ?: emptyList()
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                imageShapes.forEach { shape ->
                                    val shapeCenter = imageToScreenCoords(shape.x, shape.y)
                                    // Use widthRatio/heightRatio for device-independent sizing
                                    val scaledWidth = if (shape.widthRatio > 0f) shape.widthRatio * displayedImgWidth else shape.width * displayedImgWidth
                                    val scaledHeight = if (shape.heightRatio > 0f) shape.heightRatio * displayedImgHeight else shape.height * displayedImgHeight
                                    
                                    // Use strokeWidthRatio if available (new format), otherwise fall back to legacy strokeWidth
                                    val strokeWidthPx = if (shape.strokeWidthRatio > 0) {
                                        shape.strokeWidthRatio * displayedImgHeight
                                    } else {
                                        shape.strokeWidth * density.density * imageScale
                                    }
                                    
                                    val shapeColor = if (shape == selectedImageShape) Color.Cyan else Color(shape.colorArgb)
                                    
                                    rotate(degrees = shape.rotation, pivot = shapeCenter) {
                                        when (shape.type) {
                                            ShapeType.RECTANGLE -> {
                                                drawRect(
                                                    color = shapeColor,
                                                    topLeft = shapeCenter - Offset(scaledWidth / 2, scaledHeight / 2),
                                                    size = Size(scaledWidth, scaledHeight),
                                                    style = if (shape.isFilled) Fill else Stroke(width = strokeWidthPx)
                                                )
                                            }
                                            ShapeType.CIRCLE -> {
                                                drawOval(
                                                    color = shapeColor,
                                                    topLeft = shapeCenter - Offset(scaledWidth / 2, scaledHeight / 2),
                                                    size = Size(scaledWidth, scaledHeight),
                                                    style = if (shape.isFilled) Fill else Stroke(width = strokeWidthPx)
                                                )
                                            }
                                            ShapeType.ARROW -> {
                                                // Draw arrow line from left to right (matching blueprint style)
                                                val halfW = scaledWidth / 2
                                                val arrowHeadLength = minOf(halfW * 0.3f, 30f * imageScale)
                                                
                                                // Main line
                                                drawLine(
                                                    color = shapeColor,
                                                    start = shapeCenter - Offset(halfW, 0f),
                                                    end = shapeCenter + Offset(halfW, 0f),
                                                    strokeWidth = strokeWidthPx
                                                )
                                                
                                                // Arrow head lines
                                                val headOffset = scaledHeight * 0.3f
                                                drawLine(
                                                    color = shapeColor,
                                                    start = shapeCenter + Offset(halfW, 0f),
                                                    end = shapeCenter + Offset(halfW - arrowHeadLength, -headOffset),
                                                    strokeWidth = strokeWidthPx
                                                )
                                                drawLine(
                                                    color = shapeColor,
                                                    start = shapeCenter + Offset(halfW, 0f),
                                                    end = shapeCenter + Offset(halfW - arrowHeadLength, headOffset),
                                                    strokeWidth = strokeWidthPx
                                                )
                                            }
                                            ShapeType.CLOUD -> {
                                                // Draw cloud shape as a rounded bumpy outline (matching blueprint style)
                                                val cloudPath = Path()
                                                val numBumps = 12
                                                val halfW = scaledWidth / 2
                                                val halfH = scaledHeight / 2
                                                
                                                // Create cloud outline using cubic bezier curves
                                                for (i in 0 until numBumps) {
                                                    val angle = (i.toFloat() / numBumps) * 2f * Math.PI.toFloat()
                                                    val nextAngle = ((i + 1).toFloat() / numBumps) * 2f * Math.PI.toFloat()
                                                    
                                                    // Vary the radius to create bumpy effect
                                                    val bumpOffset = if (i % 2 == 0) 0.15f else 0f
                                                    val r1 = 1f + bumpOffset
                                                    val r2 = 1f + (if ((i + 1) % 2 == 0) 0.15f else 0f)
                                                    
                                                    val x1 = shapeCenter.x + halfW * r1 * kotlin.math.cos(angle)
                                                    val y1 = shapeCenter.y + halfH * r1 * kotlin.math.sin(angle)
                                                    val x2 = shapeCenter.x + halfW * r2 * kotlin.math.cos(nextAngle)
                                                    val y2 = shapeCenter.y + halfH * r2 * kotlin.math.sin(nextAngle)
                                                    
                                                    if (i == 0) {
                                                        cloudPath.moveTo(x1, y1)
                                                    }
                                                    
                                                    // Create outward bump
                                                    val midAngle = (angle + nextAngle) / 2
                                                    val bumpRadius = 1.25f
                                                    val ctrlX = shapeCenter.x + halfW * bumpRadius * kotlin.math.cos(midAngle)
                                                    val ctrlY = shapeCenter.y + halfH * bumpRadius * kotlin.math.sin(midAngle)
                                                    
                                                    cloudPath.quadraticBezierTo(ctrlX, ctrlY, x2, y2)
                                                }
                                                cloudPath.close()
                                                
                                                drawPath(
                                                    path = cloudPath,
                                                    color = shapeColor,
                                                    style = if (shape.isFilled) Fill else Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                                )
                                            }
                                        }
                                        
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
                                Icon(Icons.Default.Edit, "Edit Note", tint = Color.White)
                            }
                            // Delete button
                            IconButton(
                                onClick = {
                                    if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                        val notes = selectedPhotoPin!!.imageNotes[fullScreenImageFile!!]
                                        notes?.remove(selectedImageNote)
                                        selectedImageNote = null
                                        onDocumentChanged()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, "Delete Note", tint = Color.Red)
                            }
                            // Info text
                            Text("Drag to move • Pinch to resize/rotate", color = Color.Gray, fontSize = 12.sp)
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
                                        val shapes = selectedPhotoPin!!.imageShapes[fullScreenImageFile!!]
                                        shapes?.removeIf { it.id == selectedImageShape!!.id }
                                        selectedImageShape = null
                                        noteUpdateTrigger++
                                        onDocumentChanged()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, "Delete Shape", tint = Color.Red)
                            }
                            Text("Drag to move • Pinch to resize/rotate", color = Color.Gray, fontSize = 12.sp)
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
                                                shapeType.name,
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
                                        "Add Note", 
                                        tint = if (imageNoteToolMode == "place") Color.Cyan else Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text("Note", color = if (imageNoteToolMode == "place") Color.Cyan else Color.White, fontSize = 12.sp)
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
                                        "Add Shape", 
                                        tint = if (imageNoteToolMode == "shape") Color.Cyan else Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text("Shape", color = if (imageNoteToolMode == "shape") Color.Cyan else Color.White, fontSize = 12.sp)
                                }
                                
                                Spacer(Modifier.width(16.dp))
                                
                                IconButton(
                                    onClick = { 
                                        imageScale = 1f
                                        imageOffsetX = 0f
                                        imageOffsetY = 0f
                                    }
                                ) {
                                    Icon(Icons.Default.CenterFocusStrong, "Reset Zoom", tint = Color.White)
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
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                    
                    // Mode indicator at top
                    if (imageNoteToolMode == "place") {
                        Text(
                            "Tap on image to place note",
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
                            "Tap on image to place ${currentImageShapeType.name.lowercase()}",
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
    photoSessionToken: DocumentSessionToken? = null
): Boolean = withContext(Dispatchers.IO) {
    try {
        val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return@withContext false
        val renderer = PdfRenderer(pfd)
        val page = renderer.openPage(pageIndex)
        
        // Get page dimensions
        val pageWidth = page.width
        val pageHeight = page.height
        
        // Calculate scale factor to make markups visible
        // Assume typical viewing is at ~100% where 1px = 1 point
        // For large blueprints, we need to scale up markups proportionally
        // Scale stroke widths and text sizes based on page size
        // Reference: 800px is typical phone screen, so a 3200px blueprint needs 4x thicker strokes
        val markupScale = maxOf(pageWidth, pageHeight) / 400f
        
        Log.d("Blueprint", "Export: page=${pageWidth}x${pageHeight}, markupScale=$markupScale")
        
        // Store original page dimensions for photo pages
        val originalPageWidth = page.width
        val originalPageHeight = page.height
        
        // Create a bitmap at original resolution (72 DPI)
        val bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        
        // Render PDF at original resolution (no matrix = 1:1)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        val paint = android.graphics.Paint().apply { isAntiAlias = true }
        
        // Draw all markups on the bitmap (coordinates are in page units, canvas scale handles the rest)
        paths.forEach { pathData ->
            if (pathData.points.size > 1) {
                paint.color = pathData.colorArgb
                paint.strokeWidth = pathData.strokeWidth * markupScale
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.strokeJoin = android.graphics.Paint.Join.ROUND
                if (pathData.isHighlighter) paint.alpha = 100
                
                val path = android.graphics.Path()
                path.moveTo(pathData.points[0].x, pathData.points[0].y)
                for (i in 1 until pathData.points.size) path.lineTo(pathData.points[i].x, pathData.points[i].y)
                canvas.drawPath(path, paint)
            }
        }
        
        measurements.forEach { m ->
            paint.color = 0xFFE91E63.toInt()
            paint.strokeWidth = 2f * markupScale  // Reduced from 4f to 2f (50% less)
            paint.alpha = 255
            canvas.drawLine(m.p1.x, m.p1.y, m.p2.x, m.p2.y, paint)
            canvas.drawCircle(m.p1.x, m.p1.y, 3f * markupScale, paint)  // Reduced from 6f to 3f
            canvas.drawCircle(m.p2.x, m.p2.y, 3f * markupScale, paint)  // Reduced from 6f to 3f
            
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 24f * markupScale
                isFakeBoldText = true
            }
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                alpha = 180
            }
            
            val textWidth = textPaint.measureText(m.text)
            val fontMetrics = textPaint.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top
            val midX = (m.p1.x + m.p2.x) / 2
            val midY = (m.p1.y + m.p2.y) / 2
            
            val padding = 10f * markupScale
            canvas.drawRect(midX - textWidth / 2 - padding, midY - textHeight / 2 - padding/2, midX + textWidth / 2 + padding, midY + textHeight / 2 + padding/2, bgPaint)
            canvas.drawText(m.text, midX - textWidth / 2, midY - (fontMetrics.ascent + fontMetrics.descent) / 2, textPaint)
        }

        notes.forEach { n ->
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = n.fontSize * markupScale
                isFakeBoldText = n.isBold
            }
            val textWidth = textPaint.measureText(n.text)
            val fontMetrics = textPaint.fontMetrics
            
            canvas.save()
            canvas.rotate(n.rotation, n.x, n.y)
            canvas.drawText(n.text, n.x - textWidth/2, n.y - (fontMetrics.ascent + fontMetrics.descent)/2, textPaint)
            canvas.restore()
        }
        
        // Draw photo pins with pin numbers
        photoPins.forEachIndexed { pinIndex, pin ->
            val pinRadius = 7.5f * markupScale  // Reduced from 15f to 7.5f (another 50%)
            val iconPaint = android.graphics.Paint().apply {
                color = 0xFF4CAF50.toInt()
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val borderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 0.75f * markupScale  // Reduced from 1.5f to 0.75f
                isAntiAlias = true
            }
            
            // Draw circle for photo pin icon
            canvas.drawCircle(pin.x, pin.y, pinRadius, iconPaint)
            canvas.drawCircle(pin.x, pin.y, pinRadius, borderPaint)
            
            // Draw pin number inside circle
            val numberPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 7f * markupScale  // Reduced from 14f to 7f (another 50%)
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val numberMetrics = numberPaint.fontMetrics
            canvas.drawText("${pinIndex + 1}", pin.x, pin.y - (numberMetrics.ascent + numberMetrics.descent) / 2, numberPaint)
        }
        
        // Draw shapes using ratio-based dimensions
        // Shapes store ratios relative to page size - draw at actual page dimensions
        val pageMaxDim = maxOf(pageWidth, pageHeight).toFloat()
        
        shapes.forEach { shape ->
            // Calculate dimensions: ratios are relative to page dimensions
            val actualWidth = if (shape.widthRatio > 0f) shape.widthRatio * pageWidth else shape.width
            val actualHeight = if (shape.heightRatio > 0f) shape.heightRatio * pageHeight else shape.height
            val actualStrokeWidth = if (shape.strokeWidthRatio > 0f) shape.strokeWidthRatio * pageMaxDim else shape.strokeWidth
            
            // Debug: show what percentage of page the shape covers
            val widthPercent = actualWidth / pageWidth * 100
            val heightPercent = actualHeight / pageHeight * 100
            Log.d("Blueprint", "Shape export: widthRatio=${shape.widthRatio}, pageW=$pageWidth, actualW=$actualWidth (${widthPercent}% of page), pos=(${shape.x}, ${shape.y})")
            
            val shapePaint = android.graphics.Paint().apply {
                color = shape.colorArgb
                strokeWidth = actualStrokeWidth
                style = if (shape.isFilled) android.graphics.Paint.Style.FILL_AND_STROKE else android.graphics.Paint.Style.STROKE
                isAntiAlias = true
            }
            
            canvas.save()
            canvas.rotate(shape.rotation, shape.x, shape.y)
            
            when (shape.type) {
                ShapeType.RECTANGLE -> {
                    canvas.drawRect(
                        shape.x - actualWidth / 2,
                        shape.y - actualHeight / 2,
                        shape.x + actualWidth / 2,
                        shape.y + actualHeight / 2,
                        shapePaint
                    )
                }
                ShapeType.CIRCLE -> {
                    canvas.drawOval(
                        shape.x - actualWidth / 2,
                        shape.y - actualHeight / 2,
                        shape.x + actualWidth / 2,
                        shape.y + actualHeight / 2,
                        shapePaint
                    )
                }
                ShapeType.ARROW -> {
                    // Draw arrow line from left to right
                    val halfW = actualWidth / 2
                    val halfH = actualHeight / 2
                    val arrowHeadLength = minOf(halfW * 0.3f, actualStrokeWidth * 10f)
                    
                    // Main line
                    canvas.drawLine(shape.x - halfW, shape.y, shape.x + halfW, shape.y, shapePaint)
                    
                    // Arrow head
                    val arrowPath = android.graphics.Path()
                    arrowPath.moveTo(shape.x + halfW, shape.y)
                    arrowPath.lineTo(shape.x + halfW - arrowHeadLength, shape.y - halfH * 0.5f)
                    arrowPath.moveTo(shape.x + halfW, shape.y)
                    arrowPath.lineTo(shape.x + halfW - arrowHeadLength, shape.y + halfH * 0.5f)
                    canvas.drawPath(arrowPath, shapePaint)
                }
                ShapeType.CLOUD -> {
                    // Draw cloud shape as a rounded bumpy outline (matching on-screen style)
                    val cloudPath = android.graphics.Path()
                    val numBumps = 12
                    val halfW = actualWidth / 2
                    val halfH = actualHeight / 2
                    
                    // Create cloud outline using quadratic bezier curves
                    for (i in 0 until numBumps) {
                        val angle = (i.toFloat() / numBumps) * 2f * Math.PI.toFloat()
                        val nextAngle = ((i + 1).toFloat() / numBumps) * 2f * Math.PI.toFloat()
                        
                        // Vary the radius to create bumpy effect
                        val bumpOffset = if (i % 2 == 0) 0.15f else 0f
                        val r1 = 1f + bumpOffset
                        val r2 = 1f + (if ((i + 1) % 2 == 0) 0.15f else 0f)
                        
                        val x1 = shape.x + halfW * r1 * kotlin.math.cos(angle)
                        val y1 = shape.y + halfH * r1 * kotlin.math.sin(angle)
                        val x2 = shape.x + halfW * r2 * kotlin.math.cos(nextAngle)
                        val y2 = shape.y + halfH * r2 * kotlin.math.sin(nextAngle)
                        
                        if (i == 0) {
                            cloudPath.moveTo(x1, y1)
                        }
                        
                        // Create outward bump
                        val midAngle = (angle + nextAngle) / 2
                        val bumpRadius = 1.25f
                        val ctrlX = shape.x + halfW * bumpRadius * kotlin.math.cos(midAngle)
                        val ctrlY = shape.y + halfH * bumpRadius * kotlin.math.sin(midAngle)
                        
                        cloudPath.quadTo(ctrlX, ctrlY, x2, y2)
                    }
                    cloudPath.close()
                    shapePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    shapePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    canvas.drawPath(cloudPath, shapePaint)
                }
            }
            canvas.restore()
        }
        
        page.close()
        renderer.close()
        pfd.close()
        
        // Create PDF document
        val pdfDocument = PdfDocument()
        
        // Page 1: Blueprint with markups
        val blueprintPageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
        val blueprintPage = pdfDocument.startPage(blueprintPageInfo)
        blueprintPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
        pdfDocument.finishPage(blueprintPage)
        
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
            if (pin.imageFileNames.isNotEmpty()) {
                val pageInfo = PdfDocument.PageInfo.Builder(photoPageWidth, photoPageHeight, pdfPageNumber).create()
                val photoPage = pdfDocument.startPage(pageInfo)
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
                    val photoBytes = runCatching { photoBytesFor(context, photoSessionToken, fileName) }.getOrNull()
                    if (photoBytes != null) {
                        try {
                            val originalBitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                            if (originalBitmap != null) {
                                // Apply EXIF rotation to match app display
                                val rotatedBitmap = try {
                                    val exif = ByteArrayInputStream(photoBytes).use { ExifInterface(it) }
                                    val orientation = exif.getAttributeInt(
                                        ExifInterface.TAG_ORIENTATION,
                                        ExifInterface.ORIENTATION_NORMAL
                                    )
                                    val matrix = Matrix()
                                    when (orientation) {
                                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                                    }
                                    if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                                        Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
                                    } else {
                                        originalBitmap
                                    }
                                } catch (e: Exception) {
                                    originalBitmap
                                }
                                
                                // Scale image to fit
                                val scale = minOf(
                                    imageWidth.toFloat() / rotatedBitmap.width,
                                    maxImageHeight.toFloat() / rotatedBitmap.height
                                )
                                val imgWidth = (rotatedBitmap.width * scale).toInt()
                                val imgHeight = (rotatedBitmap.height * scale).toInt()
                                
                                val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, imgWidth, imgHeight, true)
                                photoCanvas.drawBitmap(scaledBitmap, currentX, currentY, null)
                                
                                // Draw any notes on the image
                                val imageNotes = pin.imageNotes[fileName]
                                if (imageNotes != null) {
                                    imageNotes.forEach { note ->
                                        // Position is stored as relative coords (0.0 to 1.0) from image top-left
                                        // Note: note.x and note.y are relative to the ORIGINAL image dimensions
                                        // We need to apply them to the scaled/rendered image
                                        val noteX = currentX + (note.x * imgWidth)
                                        val noteY = currentY + (note.y * imgHeight)
                                        
                                        // Calculate where note is ON the image (pixels from image top-left)
                                        val noteOnImgX = note.x * imgWidth
                                        val noteOnImgY = note.y * imgHeight
                                        Log.d("Blueprint", "ImageNote EXPORT: relPos=(${note.x}, ${note.y}), absPos=($noteX, $noteY), imgBounds=(x=$currentX, y=$currentY, w=$imgWidth, h=$imgHeight), onImg=($noteOnImgX, $noteOnImgY)")
                                        
                                        // Use fontSizeRatio if available (new format), otherwise fall back to legacy fontSize
                                        val noteTextSize = if (note.fontSizeRatio > 0) {
                                            // fontSizeRatio is font size relative to original image height
                                            // Apply same ratio to rendered image height
                                            note.fontSizeRatio * imgHeight
                                        } else {
                                            // Legacy: assume 800px reference display height
                                            (note.fontSize / 800f) * imgHeight
                                        }
                                        
                                        // Yellow text with transparent background (like in the app)
                                        val noteTextPaint = android.graphics.Paint().apply {
                                            color = 0xFFFFEB3B.toInt() // Yellow
                                            textSize = noteTextSize.coerceAtLeast(8f)
                                            isFakeBoldText = note.isBold
                                            isAntiAlias = true
                                            // Add shadow effect like the app
                                            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                                        }
                                        val noteMetrics = noteTextPaint.fontMetrics
                                        
                                        photoCanvas.save()
                                        photoCanvas.rotate(note.rotation, noteX, noteY)
                                        // Draw from top-left position (like app's offset() does)
                                        // drawText y is baseline, so offset by -ascent to position top at noteY
                                        photoCanvas.drawText(note.text, noteX, noteY - noteMetrics.ascent, noteTextPaint)
                                        photoCanvas.restore()
                                    }
                                }
                                
                                // Draw any shapes on the image
                                val imageShapes = pin.imageShapes[fileName]
                                if (imageShapes != null) {
                                    imageShapes.forEach { shape ->
                                        // shape.x, shape.y are the CENTER of the shape in relative coords (0.0 to 1.0)
                                        val shapeCenterX = currentX + (shape.x * imgWidth)
                                        val shapeCenterY = currentY + (shape.y * imgHeight)
                                        // Use widthRatio/heightRatio for device-independent sizing
                                        val shapeW = if (shape.widthRatio > 0f) shape.widthRatio * imgWidth else shape.width * imgWidth
                                        val shapeH = if (shape.heightRatio > 0f) shape.heightRatio * imgHeight else shape.height * imgHeight
                                        // Calculate top-left from center
                                        val shapeLeft = shapeCenterX - shapeW / 2
                                        val shapeTop = shapeCenterY - shapeH / 2
                                        
                                        // Use strokeWidthRatio if available (new format), otherwise fall back to legacy strokeWidth
                                        val scaledStroke = if (shape.strokeWidthRatio > 0) {
                                            // strokeWidthRatio is stroke width relative to original image height
                                            (shape.strokeWidthRatio * imgHeight).coerceAtLeast(1f)
                                        } else {
                                            // Legacy: assume 800px reference display height
                                            ((shape.strokeWidth / 800f) * imgHeight).coerceAtLeast(1f)
                                        }
                                        
                                        val shapePaint = android.graphics.Paint().apply {
                                            color = shape.colorArgb
                                            strokeWidth = scaledStroke
                                            style = if (shape.isFilled) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
                                            isAntiAlias = true
                                        }
                                        
                                        photoCanvas.save()
                                        photoCanvas.rotate(shape.rotation, shapeCenterX, shapeCenterY)
                                        
                                        when (shape.type) {
                                            ShapeType.RECTANGLE -> {
                                                photoCanvas.drawRect(shapeLeft, shapeTop, shapeLeft + shapeW, shapeTop + shapeH, shapePaint)
                                            }
                                            ShapeType.CIRCLE -> {
                                                photoCanvas.drawOval(shapeLeft, shapeTop, shapeLeft + shapeW, shapeTop + shapeH, shapePaint)
                                            }
                                            ShapeType.ARROW -> {
                                                // Draw arrow line from left to right (matching blueprint style)
                                                val halfW = shapeW / 2
                                                val arrowHeadLength = minOf(halfW * 0.3f, 30f)
                                                
                                                // Main line
                                                photoCanvas.drawLine(
                                                    shapeCenterX - halfW, shapeCenterY,
                                                    shapeCenterX + halfW, shapeCenterY,
                                                    shapePaint
                                                )
                                                
                                                // Arrow head lines
                                                val headOffset = shapeH * 0.3f
                                                photoCanvas.drawLine(
                                                    shapeCenterX + halfW, shapeCenterY,
                                                    shapeCenterX + halfW - arrowHeadLength, shapeCenterY - headOffset,
                                                    shapePaint
                                                )
                                                photoCanvas.drawLine(
                                                    shapeCenterX + halfW, shapeCenterY,
                                                    shapeCenterX + halfW - arrowHeadLength, shapeCenterY + headOffset,
                                                    shapePaint
                                                )
                                            }
                                            ShapeType.CLOUD -> {
                                                // Draw cloud shape as a rounded bumpy outline (matching blueprint style)
                                                val cloudPath = android.graphics.Path()
                                                val numBumps = 12
                                                val halfW = shapeW / 2
                                                val halfH = shapeH / 2
                                                
                                                // Create cloud outline using quadratic bezier curves
                                                for (i in 0 until numBumps) {
                                                    val angle = (i.toFloat() / numBumps) * 2f * Math.PI.toFloat()
                                                    val nextAngle = ((i + 1).toFloat() / numBumps) * 2f * Math.PI.toFloat()
                                                    
                                                    // Vary the radius to create bumpy effect
                                                    val bumpOffset = if (i % 2 == 0) 0.15f else 0f
                                                    val r1 = 1f + bumpOffset
                                                    val r2 = 1f + (if ((i + 1) % 2 == 0) 0.15f else 0f)
                                                    
                                                    val x1 = shapeCenterX + halfW * r1 * kotlin.math.cos(angle)
                                                    val y1 = shapeCenterY + halfH * r1 * kotlin.math.sin(angle)
                                                    val x2 = shapeCenterX + halfW * r2 * kotlin.math.cos(nextAngle)
                                                    val y2 = shapeCenterY + halfH * r2 * kotlin.math.sin(nextAngle)
                                                    
                                                    if (i == 0) {
                                                        cloudPath.moveTo(x1, y1)
                                                    }
                                                    
                                                    // Create outward bump
                                                    val midAngle = (angle + nextAngle) / 2
                                                    val bumpRadius = 1.25f
                                                    val ctrlX = shapeCenterX + halfW * bumpRadius * kotlin.math.cos(midAngle)
                                                    val ctrlY = shapeCenterY + halfH * bumpRadius * kotlin.math.sin(midAngle)
                                                    
                                                    cloudPath.quadTo(ctrlX, ctrlY, x2, y2)
                                                }
                                                cloudPath.close()
                                                shapePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                                shapePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                                photoCanvas.drawPath(cloudPath, shapePaint)
                                            }
                                        }
                                        
                                        photoCanvas.restore()
                                    }
                                }
                                
                                scaledBitmap.recycle()
                                if (rotatedBitmap !== originalBitmap) {
                                    rotatedBitmap.recycle()
                                }
                                originalBitmap.recycle()
                                
                                imagesInRow++
                                if (imagesInRow >= imagesPerRow) {
                                    imagesInRow = 0
                                    currentX = margin.toFloat()
                                    currentY += maxImageHeight + 10
                                } else {
                                    currentX += imageWidth + 10
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Blueprint", "Failed to load image: $fileName", e)
                        }
                    }
                }
                
                pdfDocument.finishPage(photoPage)
                pdfPageNumber++
            }
        }
        
        // Write PDF to output stream
        context.contentResolver.openOutputStream(outputUri)?.use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        
        true
    } catch (e: Exception) {
        Log.e("Blueprint", "exportPageAsPdf failed", e)
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

private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

private fun normRect(l: Float, t: Float, r: Float, b: Float): RectF {
    var left = clamp01(l)
    var top = clamp01(t)
    var right = clamp01(r)
    var bottom = clamp01(b)
    if (left > right) {
        val tmp = left; left = right; right = tmp
    }
    if (top > bottom) {
        val tmp = top; top = bottom; bottom = tmp
    }
    return RectF(left, top, right, bottom)
}

// Simple small LRU cache for OCR elements per (uri + pageIndex)
private val ocrCache = object : LinkedHashMap<String, List<Pair<String, RectF>>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, RectF>>>?): Boolean {
        return size > 10
    }
}
fun parseDistance(input: String): Float {
    return try {
        if (input.contains("'")) { val f = input.substringBefore("'").trim().toFloatOrNull() ?: 0f; val i = input.substringAfter("'").replace("\"", "").trim().toFloatOrNull() ?: 0f; f + (i / 12f) }
        else input.toFloatOrNull() ?: 0f
    } catch (e: Exception) { 0f }
}
fun formatFeet(feet: Float): String { val f = feet.toInt(); val i = ((feet - f) * 12).toInt(); return if (f > 0) "$f' $i\"" else "$i\"" }
/** Stage 0 characterization/migration input only; canonical saves use LocalDocumentRepository. */
@Deprecated("Legacy scale preference input only; do not use for normal document persistence")
fun saveScaleForPdf(context: Context, pdfUri: String, page: Int, pixelsPerFoot: Float) { context.getSharedPreferences("scales", Context.MODE_PRIVATE).edit().putFloat("${pdfUri}_$page", pixelsPerFoot).apply() }
/** Stage 0 characterization/migration input only; canonical loads use LocalDocumentRepository. */
@Deprecated("Legacy scale preference input only; do not use for normal document persistence")
fun loadScalesForPdf(context: Context, pdfUri: String): Map<Int, PageScale> { val prefs = context.getSharedPreferences("scales", Context.MODE_PRIVATE); return prefs.all.filterKeys { it.startsWith(pdfUri) }.mapKeys { it.key.substringAfterLast("_").toInt() }.mapValues { PageScale(it.value as Float) } }
fun getThumbCacheFile(
    context: Context,
    uri: Uri,
    index: Int,
    cacheIdentity: String = uri.toString()
): File {
    val key = MessageDigest.getInstance("SHA-256")
        .digest(cacheIdentity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return File(File(context.cacheDir, "thumbs/$key").apply { if (!exists()) mkdirs() }, "p_$index.jpg")
}
fun getRecentFiles(context: Context): List<RecentFile> { val set = context.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE).getStringSet("recent_uris", emptySet()) ?: emptySet(); return set.map { val p = it.split("|", limit = 2); RecentFile(p[0], if (p.size > 1) p[1] else "Unknown") }.sortedBy { it.name }.reversed() }
fun saveRecentFile(context: Context, uri: String, name: String) { val prefs = context.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE); val set = prefs.getStringSet("recent_uris", emptySet())?.toMutableSet() ?: mutableSetOf(); set.removeIf { it.startsWith("$uri|") } ; set.add("$uri|$name") ; prefs.edit().putStringSet("recent_uris", set).apply() }
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
        } catch (_: Exception) {
            // Provider metadata is advisory; URI identity remains usable.
        }
    }
    return result ?: uri.path?.substringAfterLast('/') ?: "Document.pdf"
}

fun getPdfName(context: Context, uri: Uri): String {
    return getFileName(context, uri).removeSuffix(".pdf")
}

suspend fun extractTextRectsForPage(context: Context, uri: Uri, pageIndex: Int, search: String): List<RectF> = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: return@withContext emptyList<RectF>()
    var convertedFromPdf: List<RectF>? = null
    try {
        PDDocument.load(input).use { doc ->
            val numPages = try { doc.numberOfPages } catch (e: Exception) { return@use emptyList<RectF>() }
            if (pageIndex < 0 || pageIndex >= numPages) return@use emptyList<RectF>()
            val page = doc.getPage(pageIndex)
            // Capture the rotation of the page (0, 90, 180, 270).  This is needed to properly map
            // extracted PDF coordinates into the onscreen coordinate system.  PDPage#getRotation()
            // returns an Integer so we coerce to Int here.  If rotation is null or invalid it
            // defaults to 0.
            val pageRotation = try {
                val rotVal = page.rotation
                if (rotVal == null) 0 else rotVal
            } catch (e: Exception) { 0 }
            val mediaBox = page.mediaBox
            val pageWidthPts = mediaBox.width
            val pageHeightPts = mediaBox.height

            val positions = ArrayList<TextPosition>()
            val sb = StringBuilder()

            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                    if (text != null && textPositions != null) {
                        sb.append(text)
                        positions.addAll(textPositions)
                    }
                }
            }
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            try { stripper.getText(doc) } catch (e: Exception) { }

            val fullText = sb.toString()
            val posBuilder = StringBuilder()
            for (tp in positions) {
                try { posBuilder.append(tp.getUnicode()) } catch (e: Exception) { posBuilder.append('?') }
            }
            val posText = posBuilder.toString()
            val preview = if (posText.length > 200) posText.substring(0, 200).replace('\n',' ') + "..." else posText.replace('\n',' ')
            Log.d("Blueprint", "extractText page=$pageIndex posTextLen=${posText.length} positions=${positions.size} preview='${preview}' fullTextLen=${fullText.length}")
            if (search.isBlank()) return@use emptyList<RectF>()
            val lower = posText.lowercase()
            val term = search.lowercase()
            var idx = lower.indexOf(term)
            Log.d("Blueprint", "search term='${search}' contains=${lower.contains(term)} (posText)")
            if (positions.isEmpty()) {
                Log.d("Blueprint", "no TextPosition entries extracted for page=$pageIndex; embedded text likely unavailable")
            }
            if (idx < 0) Log.d("Blueprint", "no embedded-text match for '$search' on page=$pageIndex")
            val rects = ArrayList<RectF>()
            while (idx >= 0) {
                val start = idx
                val end = idx + term.length - 1
                if (start >= positions.size) break
                val safeEnd = end.coerceAtMost(positions.size - 1)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
                for (i in start..safeEnd) {
                    val tp = positions[i]
                    val x = try { tp.getXDirAdj().toFloat() } catch (e: Exception) { continue }
                    val y = try { tp.getYDirAdj().toFloat() } catch (e: Exception) { continue }
                    val w = try { tp.getWidthDirAdj().toFloat() } catch (e: Exception) { 0f }
                    val h = try { tp.getHeightDir().toFloat() } catch (e: Exception) { 0f }
                    minX = minOf(minX, x)
                    minY = minOf(minY, y - h)
                    maxX = maxOf(maxX, x + w)
                    maxY = maxOf(maxY, y)
                }
                if (minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
                    // When extracting coordinates from TextPosition we use getXDirAdj()/getYDirAdj() which
                    // report positions in PDF points with 0,0 at the top‑left of the page and Y increasing
                    // downwards. The height returned by getHeightDir() represents the full height of
                    // the glyph bounding box.  Therefore the top of the bounding box is at (y - h) and the
                    // bottom is at y.  We already tracked minY = min(minY, y - h) and maxY = max(maxY, y), so
                    // (minX,minY,maxX,maxY) represent the rectangle in the page coordinate system with
                    // origin at the top‑left.  We should not invert the Y axis here.  Instead we keep
                    // the rectangle as‑is and convert it directly to normalized coordinates below.
                    val matched = try { posText.substring(start, safeEnd + 1).replace('\n', ' ') } catch (e: Exception) { "" }
                    rects.add(RectF(minX, minY, maxX, maxY))
                }
                idx = lower.indexOf(term, idx + 1)
            }
            if (rects.isNotEmpty()) {
                // Convert PDF-point rects to page pixel coordinates so renderer can draw them
                try {
                    val pfdConv = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfdConv != null) {
                        pfdConv.use { pfd ->
                            PdfRenderer(pfd).use { rendererConv ->
                                val pageConv = rendererConv.openPage(pageIndex)
                                val pagePxW = pageConv.width.toFloat()
                                val pagePxH = pageConv.height.toFloat()
                                val converted = ArrayList<RectF>()
                                for (r in rects) {
                                    // Convert PDF-point rects (r.left/top/right/bottom) to normalized coordinates.
                                    // PDF extraction yields coordinates with (0,0) at top-left of the page.
                                    val leftNorm = r.left / pageWidthPts
                                    val topNorm = r.top / pageHeightPts
                                    val rightNorm = r.right / pageWidthPts
                                    val bottomNorm = r.bottom / pageHeightPts
                                    // Adjust for page rotation.  PdfRenderer automatically rotates pages when
                                    // rendering, so we must rotate the normalized rects to match.  The mapping
                                    // below transforms coordinates for the standard rotations: 90, 180, 270 degrees.
                                    var nl = leftNorm
                                    var nt = topNorm
                                    var nr = rightNorm
                                    var nb = bottomNorm
                                    when (pageRotation) {
                                        90 -> {
                                            // swap x/y and invert x axis: (x,y,w,h) -> (y,1-x-w,w,h)
                                            nl = topNorm
                                            nt = 1f - rightNorm
                                            nr = bottomNorm
                                            nb = 1f - leftNorm
                                        }
                                        180 -> {
                                            // invert both axes: (x,y,w,h) -> (1-x-w,1-y-h,w,h)
                                            nl = 1f - rightNorm
                                            nt = 1f - bottomNorm
                                            nr = 1f - leftNorm
                                            nb = 1f - topNorm
                                        }
                                        270 -> {
                                            // swap x/y and invert y axis: (x,y,w,h) -> (1-y-h,x,h,w)
                                            nl = 1f - bottomNorm
                                            nt = leftNorm
                                            nr = 1f - topNorm
                                            nb = rightNorm
                                        }
                                        else -> {
                                            // no rotation
                                        }
                                    }
                                    converted.add(normRect(nl, nt, nr, nb))
                                }
                                pageConv.close()
                                convertedFromPdf = converted
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("Blueprint", "Failed converting pdf rects to pixels", t)
                }
                
            }
        }
    } catch (t: Throwable) {
        Log.e("Blueprint", "pdfbox processing failed, falling back to OCR", t)
    }

    // If we obtained converted rects from embedded extraction, return them now
    val _converted = convertedFromPdf
    if (_converted != null) {
        val clean = ArrayList<RectF>()
        for (r in _converted) {
            val vals = listOf(r.left, r.top, r.right, r.bottom)
            if (vals.any { !it.isFinite() || it < -0.1f || it > 1.5f }) {
                if (DEBUG_LOG) Log.d("Blueprint", "Dropping suspicious embedded rect=$r")
                continue
            }
            clean.add(normRect(r.left, r.top, r.right, r.bottom))
        }
        return@withContext clean
    }

    // OCR fallback: render page to a bitmap and run ML Kit text recognition (cached)
    try {
        val key = uri.toString() + "_" + pageIndex
        val cached = synchronized(ocrCache) { ocrCache[key] }
        if (cached != null) {
            val filtered = ArrayList<RectF>()
            if (search.isBlank()) {
                for (p in cached) filtered.add(p.second)
            } else {
                for (p in cached) if (p.first.contains(search, ignoreCase = true)) filtered.add(p.second)
            }
            return@withContext filtered
        }

        val pfd2 = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()
        val bmp: Bitmap
        try {
            pfd2.use { pfd ->
                PdfRenderer(pfd).use { renderer2 ->
                    val page2 = renderer2.openPage(pageIndex)
                    val scale = 2
                    val bmpW = page2.width * scale
                    val bmpH = page2.height * scale
                    bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                    page2.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page2.close()
                }
            }
        } catch (t: Throwable) {
            Log.e("Blueprint", "OCR render failed", t)
            return@withContext emptyList()
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bmp, 0)
        val result = try { recognizer.process(image).await() } catch (t: Throwable) { Log.e("Blueprint", "MLKit recognition failed", t); return@withContext emptyList() }

        val elements = ArrayList<Pair<String, RectF>>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text ?: continue
                    val bb = element.boundingBox ?: line.boundingBox ?: block.boundingBox
                    if (bb == null) continue
                    val l = bb.left.toFloat() / bmp.width.toFloat()
                    val t = bb.top.toFloat() / bmp.height.toFloat()
                    val r = bb.right.toFloat() / bmp.width.toFloat()
                    val b = bb.bottom.toFloat() / bmp.height.toFloat()
                    val nr = normRect(l, t, r, b)
                    elements.add(Pair(text, nr))
                }
            }
        }

        synchronized(ocrCache) { ocrCache[key] = elements }

        val out = ArrayList<RectF>()
        if (search.isBlank()) {
            for (p in elements) out.add(p.second)
        } else {
            for (p in elements) if (p.first.contains(search, ignoreCase = true)) out.add(p.second)
        }

        val clean = ArrayList<RectF>()
        for (r in out) {
            val vals = listOf(r.left, r.top, r.right, r.bottom)
            if (vals.any { !it.isFinite() || it < -0.1f || it > 1.5f }) {
                if (DEBUG_LOG) Log.d("Blueprint", "Dropping suspicious OCR rect=$r")
                continue
            }
            clean.add(normRect(r.left, r.top, r.right, r.bottom))
        }
        return@withContext clean
    } catch (t: Throwable) {
        Log.e("Blueprint", "OCR fallback failed", t)
        return@withContext emptyList()
    }
}
