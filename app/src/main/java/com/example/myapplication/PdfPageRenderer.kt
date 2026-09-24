package com.example.myapplication

import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.example.myapplication.stage8.PageCodeRegion
import com.example.myapplication.stage8.PageCodeRegionSelector
import com.example.myapplication.stage8.appearance
import com.example.myapplication.stage8.appearanceMode
import com.example.myapplication.stage8.changeAppearance
import com.example.myapplication.stage8.changeImageAppearance
import com.example.myapplication.stage8.buildMeasurement
import com.example.myapplication.stage8.measurementSourceLength
import com.example.myapplication.stage8.ViewerFling
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.example.myapplication.stage8.ViewerTransform
import com.example.myapplication.stage8.PdfSelectionKey
import com.example.myapplication.stage8.MeasurementPointSelection
import com.example.myapplication.ui.ViewerFloatingControl
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage9.DiagnosticEvent
import com.example.myapplication.stage9.SafeDiagnostics
import com.example.myapplication.stage3.DocumentWorkToken
import com.example.myapplication.stage3.DocumentWorkOwner
import com.example.myapplication.stage8.AnnotationReducer
import com.example.myapplication.stage9b.AnnotationCanvasRendering
import androidx.compose.ui.graphics.nativeCanvas
import com.example.myapplication.stage8.Stage8InteractionController
import com.example.myapplication.stage8.AnnotationGeometry
import com.example.myapplication.stage8.AnnotationSize
import com.example.myapplication.stage8.OcrSelection
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage7.Stage7OwnedResource
import com.example.myapplication.stage7.Stage7ResourceOwner
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.example.myapplication.stage7.Stage7CacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import android.graphics.RectF
import kotlin.math.sqrt

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
    pointSelection: MeasurementPointSelection = remember(sessionToken, pageIndex, mode) { MeasurementPointSelection() },
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
    onScaleDefined: (Float, Float, AnnotationSize) -> Boolean,
    onDeleteItem: (PageItem) -> Unit,
    onFullScreenModeChanged: (Boolean) -> Unit,
    onPhotoAdded: () -> Unit = {},
    onAnnotationAdded: () -> Unit = {},
    onRequestCameraCapture: ((Int, String) -> Unit)? = null,
    onPageRendered: () -> Unit = {},
    onPageCodeRegionSelected: ((PageCodeRegion) -> Unit)? = null,
    selectingPageCode: Boolean = false,
    onPageCodeSelectionDismissed: () -> Unit = {},
    toolSettings: com.example.myapplication.stage8.DrawingToolSettings = com.example.myapplication.stage8.DrawingToolSettings(),
    onToolModeSelected: (ToolMode) -> Unit = {},
    onPhotoTargetChanged: (Pair<String, String>?) -> Unit = {}
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
    val momentumScope = rememberCoroutineScope()
    val pdfFling = remember(sessionToken, pageIndex) { ViewerFling(momentumScope) }
    val latestPageAdmission by rememberUpdatedState(isPageCurrent)
    DisposableEffect(pdfFling, mode, selectingPageCode) { onDispose { pdfFling.stop() } }

    if (scale.isNaN() || offsetX.isNaN() || offsetY.isNaN()) {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    var showScaleDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = pointSelection.hasFirstPoint && !showScaleDialog) { pointSelection.clear() }
    var scaleInput by remember { mutableStateOf("") }
    val currentStroke = remember(uri, sessionToken, pageIndex, mode) { mutableStateListOf<Point>() }
    val polylinePoints = remember(uri, sessionToken, pageIndex, mode) { mutableStateListOf<Point>() }
    BackHandler(enabled = polylinePoints.isNotEmpty()) { polylinePoints.clear() }

    var itemToDelete by remember { mutableStateOf<PageItem?>(null) }
    var appearanceItem by remember(sessionToken, pageIndex) { mutableStateOf<PageItem?>(null) }
    var appearanceError by remember { mutableStateOf(false) }
    appearanceItem?.let { item ->
        com.example.myapplication.ui.ToolSettingsDialog(item.appearanceMode(), item.appearance(), false, appearanceError,
            onSave = { style ->
                if (annotationReducer.changeAppearance(pageIndex, item, style) != AnnotationReducer.Result.Rejected) {
                    appearanceItem = null; appearanceError = false
                } else appearanceError = true
            }, onDismiss = { appearanceItem = null; appearanceError = false })
    }
    val currentMeasurements by rememberUpdatedState(measurements)
    val currentNotes by rememberUpdatedState(notes)
    val currentPaths by rememberUpdatedState(paths)
    val currentPhotoPins by rememberUpdatedState(photoPins)
    val currentShapes by rememberUpdatedState(shapes)
    var selectedKey by remember(sessionToken, pageIndex) { mutableStateOf<PdfSelectionKey?>(null) }
    var selectedItem by object : kotlin.properties.ReadWriteProperty<Any?, PageItem?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): PageItem? =
            selectedKey?.resolve(currentMeasurements, currentNotes, currentPaths, currentPhotoPins, currentShapes)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: PageItem?) {
            selectedKey = value?.let(PdfSelectionKey::from)
        }
    }
    // Reconcile deletion/undo/replace immediately for display and again at gesture admission.
    val resolvedSelection = selectedItem
    LaunchedEffect(selectedKey, resolvedSelection) {
        if (resolvedSelection == null) selectedKey = null
    }
    // Store the screen position where the toolbar should appear (tap location or item's new position after drag)
    var selectionToolbarPos by remember { mutableStateOf(Offset.Zero) }
    // Disambiguation: when multiple items overlap at tap location
    var overlappingItems by remember { mutableStateOf<List<PageItem>>(emptyList()) }
    var showItemPicker by remember { mutableStateOf(false) }

    var measurementDraft by remember { mutableStateOf<Measurement?>(null) }
    var selectedMeasurement by object : kotlin.properties.ReadWriteProperty<Any?, Measurement?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Measurement? {
            val current = (selectedItem as? PageItem.Measure)?.data ?: return null
            return measurementDraft?.takeIf { it.id == current.id } ?: current
        }
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Measurement?) {
            if (value != null) selectedItem = PageItem.Measure(value)
            else if (selectedKey?.kind == PdfSelectionKey.Kind.MEASUREMENT) selectedItem = null
        }
    }
    val selectedMeasurementIndex by object : kotlin.properties.ReadOnlyProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int =
            currentMeasurements.indexOfFirst { it.id == selectedMeasurement?.id }
    }
    var draggingPointIdx by remember { mutableIntStateOf(-1) }
    var originalMeasurement by remember { mutableStateOf<Measurement?>(null) }

    var calibratePointIdx by remember { mutableIntStateOf(-1) }

    var showNoteDialog by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }
    var noteIsBold by remember { mutableStateOf(false) }
    var notePos by remember { mutableStateOf(Point(0f, 0f)) }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var noteDraft by remember { mutableStateOf<Note?>(null) }
    var selectedNote by object : kotlin.properties.ReadWriteProperty<Any?, Note?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Note? {
            val current = (selectedItem as? PageItem.NoteItem)?.data ?: return null
            return noteDraft?.takeIf { it.id == current.id } ?: current
        }
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Note?) {
            if (value != null) selectedItem = PageItem.NoteItem(value)
            else if (selectedKey?.kind == PdfSelectionKey.Kind.NOTE) selectedItem = null
        }
    }
    val selectedNoteIdx by object : kotlin.properties.ReadOnlyProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int =
            currentNotes.indexOfFirst { it.id == selectedNote?.id }
    }
    var draggingNoteIdx by remember { mutableIntStateOf(-1) }
    var isItemDragging by remember { mutableStateOf(false) }
    var originalNote by remember { mutableStateOf<Note?>(null) }

    // Photo pin state
    val selectedPhotoPinId = selectedKey?.takeIf { it.kind == PdfSelectionKey.Kind.PHOTO }?.id
    var selectedPhotoPin by object : kotlin.properties.ReadWriteProperty<Any?, PhotoPin?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): PhotoPin? =
            (selectedItem as? PageItem.PhotoPinItem)?.data
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: PhotoPin?) {
            if (value != null) selectedItem = PageItem.PhotoPinItem(value)
            else if (selectedKey?.kind == PdfSelectionKey.Kind.PHOTO) selectedItem = null
        }
    }
    var showPinImageGallery by remember { mutableStateOf(false) }

    // Shape tool state
    var shapeDraft by remember { mutableStateOf<Shape?>(null) }
    var selectedShape by object : kotlin.properties.ReadWriteProperty<Any?, Shape?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Shape? {
            val current = (selectedItem as? PageItem.ShapeItem)?.data ?: return null
            return shapeDraft?.takeIf { it.id == current.id } ?: current
        }
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Shape?) {
            if (value != null) selectedItem = PageItem.ShapeItem(value)
            else if (selectedKey?.kind == PdfSelectionKey.Kind.SHAPE) selectedItem = null
        }
    }
    var draggingShape by remember { mutableStateOf(false) }
    var originalShape by remember { mutableStateOf<Shape?>(null) }
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
    var selectionRevision by remember(sessionToken, pageIndex) { mutableIntStateOf(0) }
    var selectionJob by remember(sessionToken, pageIndex) { mutableStateOf<Job?>(null) }
    DisposableEffect(sessionToken, pageIndex, mode, selectingPageCode) {
        onDispose { selectionRevision++; selectionJob?.cancel(); selectionJob = null }
    }
    LaunchedEffect(mode, selectingPageCode) {
        if (mode != ToolMode.PAN || selectingPageCode) {
            isTextSelecting = false; showCopyButton = false; selectedOcrBoxes = emptyList()
            textSelectionStartIdx = -1; textSelectionEndIdx = -1
        }
    }
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
        val calibrationStart = pointSelection.firstPoint
        val calibrationEnd = pointSelection.secondPoint
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
            onScaleDefined = { distance, feet -> sourcePageSize?.let { onScaleDefined(distance, feet, it) } ?: false },
            onDismiss = {
                showScaleDialog = false
                pointSelection.firstPoint = null
                pointSelection.secondPoint = null
                scaleInput = ""
            },
            onAccepted = {
                showScaleDialog = false
                pointSelection.firstPoint = null
                pointSelection.secondPoint = null
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
                              fontSizeRatio = toolSettings.style(ToolMode.NOTE).fontSize, colorArgb = toolSettings.style(ToolMode.NOTE).colorArgb
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
                         outcome = annotationReducer.updatePdfNote(pageIndex, old, replacement)
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
                        val fontSizeRatio = toolSettings.style(ToolMode.NOTE).fontSize

                         val newImageNote = PhotoImageNote(
                            x = imageNotePos.x,
                            y = imageNotePos.y,
                            text = imageNoteInput,
                             isBold = imageNoteIsBold,
                            rotation = 0f,
                             fontSizeRatio = fontSizeRatio, colorArgb = toolSettings.style(ToolMode.NOTE).colorArgb
                         )
                         outcome = annotationReducer.addImageNote(pageIndex, selectedPhotoPin!!.id, currentImageFileName!!, newImageNote)
                         if (outcome == AnnotationReducer.Result.Accepted) {
                             onAnnotationAdded()
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

    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(stringResource(R.string.annotation_delete_title)) },
            text = { Text(stringResource(R.string.annotation_delete_message)) },
            confirmButton = { Button(onClick = {
                onDeleteItem(itemToDelete!!)
                if (itemToDelete is PageItem.Measure && (itemToDelete as PageItem.Measure).data == selectedMeasurement) {
                    selectedMeasurement = null

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
        pointSelection.firstPoint = null
        pointSelection.secondPoint = null
        showScaleDialog = false
        scaleInput = ""
        selectedItem = null
        itemToDelete = null
        overlappingItems = emptyList()
        showItemPicker = false
        selectedMeasurement = null

        measurementDraft = null
        draggingPointIdx = -1
        originalMeasurement = null
        calibratePointIdx = -1
        selectedNote = null

        showNoteDialog = false
        noteInput = ""
        noteIsBold = toolSettings.style(ToolMode.NOTE).bold
        editingNote = null
        draggingNoteIdx = -1
        isItemDragging = false
        originalNote = null
        noteDraft = null
        selectedPhotoPin = null
        selectedShape = null
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
    DisposableEffect(sessionToken, pageIndex) { onDispose { onPhotoTargetChanged(null); onFullScreenModeChanged(false) } }
    LaunchedEffect(mode, toolSettings, fullScreenImageFile) {
        if (fullScreenImageFile != null) {
            imageNoteToolMode = when (mode) {
                ToolMode.NOTE -> "place"
                ToolMode.SHAPE -> "shape"
                else -> mode.name.lowercase()
            }
            currentImageShapeType = toolSettings.style(ToolMode.SHAPE).shape
            selectedImageNote = null; selectedImageShape = null
        }
    }
    LaunchedEffect(fullScreenImageFile) {
        pdfFling.stop()
        onFullScreenModeChanged(fullScreenImageFile != null)
        onPhotoTargetChanged(fullScreenImageFile?.let { file -> selectedPhotoPin?.id?.let { it to file } })
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
        DisposableEffect(pdfFling, w, h) { onDispose { pdfFling.stop() } }
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
            fun viewerTransform() = ViewerTransform(bW, bH, w, h, scale, offsetX, offsetY)
            val vW = bW * viewerTransform().fittedScale
            val vH = bH * viewerTransform().fittedScale
            Box(
                modifier = Modifier.fillMaxSize()
                    .testTag(PDF_READY_CANVAS_TAG)
                    .pointerInput(sessionToken, pageIndex, mode, toolSettings, w, h) {
                        awaitEachGesture {
                            try {
                            fun screenToPage(ptX: Float, ptY: Float): Point =
                                viewerTransform().toNormalized(ptX, ptY)
                            fun sourceDistance(p1: Point, p2: Point): Float? {
                                val source = sourcePageSize ?: return null
                                val dx = ((p1.x - p2.x) * source.width).toDouble()
                                val dy = ((p1.y - p2.y) * source.height).toDouble()
                                return sqrt(dx * dx + dy * dy)
                                    .takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
                                    ?.toFloat()
                            }
                            fun pageToScreen(pt: Point): Offset =
                                viewerTransform().toScreen(pt).let { Offset(it.x, it.y) }
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
                            selectionRevision++; selectionJob?.cancel()
                            val gestureSelectionRevision = selectionRevision
                            pdfFling.stop()
                            val velocity = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                            var momentumEligible = mode == ToolMode.PAN
                            if (selectedItem == null) selectedKey = null
                            // Use the pointer event clock so long-press
                            // admission follows actual gesture time even
                            // when input events are delivered in a batch.
                            val startTime = down.uptimeMillis
                            val gestureTransform = viewerTransform()
                            val pointerCompositeScale = gestureTransform.compositeScale
                            var dragActive = false
                            var totalPan = Offset.Zero
                            var longPressTriggered = false
                            var longPressEligible = true
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
                            val handleThreshold = 80f / pointerCompositeScale

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
                                        val startHandleHitRadius = (startBoxHeight * pointerCompositeScale * 2.5f).coerceIn(50f, 120f)
                                        val endHandleHitRadius = (endBoxHeight * pointerCompositeScale * 2.5f).coerceIn(50f, 120f)

                                        if (gestureTransform.hits(Point(startHandleX / bW, startHandleY / bH), down.position.x, down.position.y, startHandleHitRadius)) {
                                            draggingSelectionHandle = "start"
                                            isItemDragging = true
                                            SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                        } else if (gestureTransform.hits(Point(endHandleX / bW, endHandleY / bH), down.position.x, down.position.y, endHandleHitRadius)) {
                                            draggingSelectionHandle = "end"
                                            isItemDragging = true
                                            SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                        }
                                    }
                                }

                                if (selectedMeasurement != null) {
                                         if (gestureTransform.hits(selectedMeasurement!!.p1, down.position.x, down.position.y, 80f)) {
                                        draggingPointIdx = 0
                                        isItemDragging = true
                                        originalMeasurement = selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())
                                     } else if (gestureTransform.hits(selectedMeasurement!!.p2, down.position.x, down.position.y, 80f)) {
                                        draggingPointIdx = 1
                                        isItemDragging = true
                                        originalMeasurement = selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())
                                    }
                                }
                                if (draggingPointIdx == -1 && selectedNote != null) {
                                    if (AnnotationCanvasRendering.containsNote(selectedNote!!,
                                            startPt.x * bW, startPt.y * bH, bW, bH, 4f / pointerCompositeScale)) {
                                        draggingNoteIdx = currentNotes.indexOfFirst { it.id == selectedNote?.id }
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
                            } else if (mode == ToolMode.SCALE && pointSelection.firstPoint != null && pointSelection.secondPoint != null) {
                                if (gestureTransform.hits(pointSelection.firstPoint!!, down.position.x, down.position.y, 80f)) calibratePointIdx = 0
                                else if (gestureTransform.hits(pointSelection.secondPoint!!, down.position.x, down.position.y, 80f)) calibratePointIdx = 1
                            }

                            fun beginTextSelection() {
                                longPressTriggered = true

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
                                val stillCurrent = gestureSelectionRevision == selectionRevision && selectionToken == sessionToken &&
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
                                        positionCopyAffordance(pageOcr, admittedBoxIdx); showCopyButton = true
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
                                            val stillCurrent = gestureSelectionRevision == selectionRevision && selectionToken == sessionToken &&
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
                                        selectionJob = launchDocumentWork(sessionToken, loadOcr)
                                    } else {
                                        selectionJob = coroutineScopeForOcr.launch { loadOcr() }
                                    }
                                }

                            }
                            var lastPointerTime = down.uptimeMillis
                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                             do {
                                 val waitingForLongPress = mode == ToolMode.PAN && !longPressTriggered && longPressEligible &&
                                     !isItemDragging && !isTextSelecting && momentumEligible && totalPan.getDistance() < viewConfiguration.touchSlop
                                 val nextEvent = if (waitingForLongPress) {
                                     withTimeoutOrNull((longPressTimeout - (lastPointerTime - startTime)).coerceAtLeast(1L)) {
                                         awaitPointerEvent()
                                     }
                                 } else awaitPointerEvent()
                                 val event = if (nextEvent == null) {
                                     beginTextSelection()
                                     awaitPointerEvent()
                                 } else nextEvent
                                 lastPointerTime = event.changes.firstOrNull()?.uptimeMillis ?: lastPointerTime
                                 val pointers = event.changes
                                 // Compose adapts ACTION_CANCEL in
                                 // SuspendingPointerInputModifierNodeImpl by dispatching
                                 // consumed all-up changes.  The shared predicate must run
                                 // before this handler consumes any incoming change.
                                 if (event.isIncomingCancellation()) {
                                     gestureCancelled = true
                                     selectionRevision++; selectionJob?.cancel()
                                     break
                                 }
                                 if (pointers.size != 1) momentumEligible = false
                                 if (pointers.size == 1) velocity.addPosition(pointers[0].uptimeMillis, pointers[0].position)
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
                                    val mIdx = selectedMeasurementIndex.takeIf { it in currentMeasurements.indices }
                                        ?: currentMeasurements.indexOfFirst { it === selectedMeasurement }
                                    if (mIdx >= 0) {
                                        val baseMeasurement = measurementDraft ?: currentMeasurements[mIdx]
                                         val draftM = baseMeasurement.copyMeasurement(
                                            p1 = if (draggingPointIdx == 0) currentPt.copyPoint() else baseMeasurement.p1.copyPoint(),
                                            p2 = if (draggingPointIdx == 1) currentPt.copyPoint() else baseMeasurement.p2.copyPoint()
                                        )
                                         val updatedM = if (currentScale != null) {
                                             sourcePageSize?.let { measurementSourceLength(draftM.vertices, it.width, it.height) }?.let { distance ->
                                                 com.example.myapplication.stage8.formatSourceDistance(distance, currentScale.pointsPerFoot)
                                                     ?.let { draftM.copyMeasurement(text = it) }
                                             } ?: baseMeasurement
                                         } else draftM
                                        selectedMeasurement = updatedM
                                        measurementDraft = updatedM
                                    }
                                    change.consume()
                                    dragActive = true
                                } else if ((draggingNoteIdx != -1 || noteGestureActive) && pointers.size < 2) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                     val admittedNote = currentNotes.firstOrNull { it.id == originalNote?.id }
                                         ?: break
                                     val baseNote = noteDraft ?: admittedNote.copyNote()
                                     val delta = change.position - change.previousPosition
                                     val updatedN = baseNote.copy(
                                         x = (baseNote.x + delta.x / (bW * pointerCompositeScale)).coerceIn(0f, 1f),
                                         y = (baseNote.y + delta.y / (bH * pointerCompositeScale)).coerceIn(0f, 1f)
                                     )
                                    noteDraft = updatedN
                                    selectedNote = updatedN

                                    change.consume()
                                    dragActive = true
                                } else if (calibratePointIdx != -1) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    if (calibratePointIdx == 0) pointSelection.firstPoint = currentPt else pointSelection.secondPoint = currentPt
                                    change.consume()
                                    dragActive = true
                                } else if (draggingShape && selectedShape != null && pointers.size < 2) {
                                    val change = pointers[0]
                                    val currentPt = screenToPage(change.position.x, change.position.y)
                                    val idx = currentShapes.indexOfFirst { it.id == selectedShape!!.id }
                                    if (idx != -1) {
                                        val updated = (shapeDraft ?: currentShapes[idx].copyShape()).copy(x = currentPt.x, y = currentPt.y)
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
                                        val idx = currentShapes.indexOfFirst { it.id == selectedShape!!.id }
                                        if (idx != -1) {
                                            // Resize using ratios (0.01 to 1.0 = 1% to 100% of page)
                                            val shape = shapeDraft ?: currentShapes[idx]
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
                                    } else if (selectedNote != null) {
                                        val admittedNote = currentNotes.firstOrNull { it.id == selectedNote?.id }
                                            ?: break
                                        if (originalNote == null) {
                                            draggingNoteIdx = currentNotes.indexOfFirst { it.id == admittedNote.id }
                                            originalNote = admittedNote.copyNote()
                                        }
                                        noteGestureActive = true
                                        val baseNote = noteDraft ?: admittedNote
                                        val cur = baseNote.copy(
                                            fontSizeRatio = (baseNote.fontSizeRatio * zoom).coerceIn(0.005f, 0.25f),
                                            rotation = baseNote.rotation + rotation
                                        )
                                        noteDraft = cur
                                        selectedNote = cur
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else {
                                        val pan = event.calculatePan()
                                        totalPan += pan
                                        if (totalPan.getDistance() >= viewConfiguration.touchSlop) longPressEligible = false
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
                                } else if (pointers.size == 1 && (mode == ToolMode.PAN || (mode == ToolMode.SCALE && pointSelection.firstPoint != null && pointSelection.secondPoint != null))) {
                                    val change = pointers[0]
                                    val currentPos = change.position
                                    val elapsed = event.changes.firstOrNull()?.uptimeMillis?.minus(startTime) ?: 0L

                                    // Debug logging
                                    if (mode == ToolMode.PAN && !longPressTriggered && longPressEligible && !isItemDragging) {
                                        SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                    }

                                    // Check for long press to start text selection (400ms hold without much movement)
                                    if (!longPressTriggered && longPressEligible && mode == ToolMode.PAN && elapsed >= longPressTimeout && totalPan.getDistance() < viewConfiguration.touchSlop && !isItemDragging && momentumEligible) beginTextSelection()

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
                                        if (totalPan.getDistance() >= viewConfiguration.touchSlop) longPressEligible = false
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

                            if (!gestureCancelled && momentumEligible && dragActive && !longPressTriggered &&
                                !textSelectingActive && !ocrSelectionPending && !isItemDragging &&
                                draggingSelectionHandle == null && draggingPointIdx == -1 && draggingNoteIdx == -1 &&
                                !draggingShape && !rotatingShape && !resizingShape) {
                                val release = velocity.calculateVelocity()
                                pdfFling.start(Offset(release.x, release.y),
                                    isCurrent = { latestPageAdmission(sessionToken, pageIndex) },
                                    panBy = { delta ->
                                        val nextX = (offsetX + delta.x).coerceIn(-(vW * scale) / 2, (vW * scale) / 2)
                                        val nextY = (offsetY + delta.y).coerceIn(-(vH * scale) / 2, (vH * scale) / 2)
                                        val moved = nextX != offsetX || nextY != offsetY
                                        offsetX = nextX; offsetY = nextY
                                        moved
                                    })
                            }

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
                                    selectedMeasurement = currentMeasurements.firstOrNull { it.id == selectedMeasurement?.id }

                                    measurementDraft = null
                                    draggingPointIdx = -1
                                    originalMeasurement = null
                                    isItemDragging = false
                                    if (selectedMeasurement != null) {
                                        selectedItem = PageItem.Measure(selectedMeasurement!!)
                                        // Update toolbar position to the measurement's new center
                                        val m = selectedMeasurement!!
                                        val transform = viewerTransform()
                                        val compScale = transform.compositeScale
                                        val imgW = bW * compScale
                                        val imgH = bH * compScale
                                        val imgLeft = transform.left
                                        val imgTop = transform.top
                                         val p1s = Offset(imgLeft + m.p1.x * bW * compScale, imgTop + m.p1.y * bH * compScale)
                                         val p2s = Offset(imgLeft + m.p2.x * bW * compScale, imgTop + m.p2.y * bH * compScale)
                                        selectionToolbarPos = Offset((p1s.x + p2s.x) / 2 + 50f, (p1s.y + p2s.y) / 2)
                                    }
                                } else if (draggingNoteIdx != -1) {
                                    if (originalNote != null && selectedNote != null) {
                                        val updated = selectedNote!!.copyNote()
                                         annotationReducer.updatePdfNote(pageIndex, originalNote!!, updated)
                                    }
                                    draggingNoteIdx = -1
                                    noteGestureActive = false
                                    originalNote = null
                                    noteDraft = null
                                    selectedNote = currentNotes.firstOrNull { it.id == selectedNote?.id }

                                    isItemDragging = false
                                    if (selectedNote != null) {
                                        selectedItem = PageItem.NoteItem(selectedNote!!, selectedNoteIdx)
                                        // Update toolbar position to the note's new position
                                        val n = selectedNote!!
                                        val transform = viewerTransform()
                                        val compScale = transform.compositeScale
                                        val imgW = bW * compScale
                                        val imgH = bH * compScale
                                        val imgLeft = transform.left
                                        val imgTop = transform.top
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
                                selectedShape = currentShapes.firstOrNull { it.id == selectedShape?.id }
                                isItemDragging = false
                                if (selectedShape != null) {
                                    selectedItem = PageItem.ShapeItem(selectedShape!!)
                                    val s = selectedShape!!
                                    val transform = viewerTransform()
                                    val compScale = transform.compositeScale
                                    val imgW = bW * compScale
                                    val imgH = bH * compScale
                                    val imgLeft = transform.left
                                    val imgTop = transform.top
                                     selectionToolbarPos = Offset(imgLeft + s.x * bW * compScale + 50f, imgTop + s.y * bH * compScale)
                                }
                            } else if (calibratePointIdx != -1) {
                                calibratePointIdx = -1
                            } else if (dragActive && currentStroke.isNotEmpty()) {
                                 val newPath = DrawnPath(
                                     currentStroke.toList(),
                                     toolSettings.style(mode).colorArgb,
                                     mode == ToolMode.HIGHLIGHTER,
                                     strokeWidthRatio = toolSettings.style(mode).width
                                 )
                                 annotationReducer.addPdfPath(pageIndex, newPath)
                                currentStroke.clear()
                            } else if (!dragActive && mode == ToolMode.PAN) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                // Find ALL items at tap location for disambiguation
                                val foundItems = mutableListOf<PageItem>()
                                 val thresholdSegment = 60f / pointerCompositeScale

                                // Check currentMeasurements
                                for (m in currentMeasurements) {
                                    if (m.vertices.zipWithNext().any { (a, b) -> distToSegment(Point(tapPt.x * bW, tapPt.y * bH), Point(a.x * bW, a.y * bH), Point(b.x * bW, b.y * bH)) < thresholdSegment }) {
                                        foundItems.add(PageItem.Measure(m))
                                    }
                                }
                                // Check currentNotes
                                for ((noteOrdinal, n) in currentNotes.withIndex()) {
                                    if (AnnotationCanvasRendering.containsNote(n, tapPt.x * bW, tapPt.y * bH,
                                            bW, bH, 4f / pointerCompositeScale)) {
                                        foundItems.add(PageItem.NoteItem(n, noteOrdinal))
                                    }
                                }
                                // Check currentPaths
                                for (p in currentPaths) {
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
                                for (pin in currentPhotoPins) {
                                    val dx = (tapPt.x - pin.x) * bW
                                    val dy = (tapPt.y - pin.y) * bH
                                    if (sqrt(dx*dx + dy*dy) < pinThreshold) {
                                        foundItems.add(PageItem.PhotoPinItem(pin))
                                    }
                                }
                                // Check currentShapes
                                for (s in currentShapes) {
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

                                    measurementDraft = null
                                    selectedNote = null

                                    selectedPhotoPin = null
                                    selectedShape = null
                                } else if (foundItems.size == 1) {
                                    // Single item - select it directly
                                    val found = foundItems.first()
                                    selectedItem = found
                                    showItemPicker = false
                                    overlappingItems = emptyList()
                                    selectedMeasurement = if (found is PageItem.Measure) found.data else null

                                    measurementDraft = null
                                    if (found is PageItem.NoteItem) {
                                        selectedNote = found.data

                                    } else {
                                        selectedNote = null

                                    }
                                    selectedPhotoPin = if (found is PageItem.PhotoPinItem) found.data else null
                                    selectedShape = if (found is PageItem.ShapeItem) found.data else null
                                } else {
                                    // No items found - clear selection (including text selection)
                                    selectedItem = null
                                    showItemPicker = false
                                    overlappingItems = emptyList()
                                    selectedMeasurement = null

                                    measurementDraft = null
                                    selectedNote = null

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
                                 val style = toolSettings.style(ToolMode.SHAPE)
                                 if (annotationReducer.addPdfShape(pageIndex, Shape(tapPt.x, tapPt.y, 0f, style.shape, style.colorArgb,
                                     style.filled, style.width, .1f, .08f)).changed) onAnnotationAdded()
                            } else if (!dragActive && mode == ToolMode.POLYLINE) {
                                if (currentScale == null) Toast.makeText(context, R.string.measure_calibrate_first, Toast.LENGTH_SHORT).show()
                                else if (polylinePoints.size < com.example.myapplication.stage5.Stage5Limits.MAX_PATH_POINTS) polylinePoints.add(screenToPage(down.position.x, down.position.y))
                            } else if (!dragActive && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                                val pt = screenToPage(down.position.x, down.position.y)
                                if (pointSelection.firstPoint == null) pointSelection.firstPoint = pt else if (pointSelection.secondPoint == null) {
                                    pointSelection.secondPoint = pt
                                    if (mode == ToolMode.MEASURE) {
                                         if (currentScale != null) {
                                             sourceDistance(pointSelection.firstPoint!!, pointSelection.secondPoint!!)?.let { distance ->
                                                 val text = com.example.myapplication.stage8.formatSourceDistance(distance, currentScale.pointsPerFoot)
                                                 if (text == null) {
                                                     Toast.makeText(context, R.string.measurement_out_of_range, Toast.LENGTH_SHORT).show()
                                                     return@let
                                                 }
                                                 val newM = Measurement(pointSelection.firstPoint!!, pointSelection.secondPoint!!, text, colorArgb = toolSettings.style(mode).colorArgb, strokeWidthRatio = toolSettings.style(mode).width)
                                                 if (annotationReducer.addMeasurement(pageIndex, newM).changed) {
                                                     onAnnotationAdded()
                                                 }
                                             }
                                        }
                                        pointSelection.firstPoint = null
                                        pointSelection.secondPoint = null
                                    }
                                } else {
                                    if (mode != ToolMode.SCALE) {
                                        pointSelection.firstPoint = pt; pointSelection.secondPoint = null
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
                                selectedMeasurement = currentMeasurements.firstOrNull { it.id == selectedMeasurement?.id }

                                selectedNote = currentNotes.firstOrNull { it.id == selectedNote?.id }

                                selectedShape = currentShapes.firstOrNull { it.id == selectedShape?.id }
                            }
                        }
                    }
            ) {
                Image(bitmap = b.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY), filterQuality = FilterQuality.High)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val transform = viewerTransform()
                    val compositeScale = transform.compositeScale

                    fun toS(p: Point): Offset {
                        return transform.toScreen(p).let { Offset(it.x, it.y) }
                    }
                    currentPaths.forEach { pathData ->
                        if (pathData.points.size > 1) {
                            val path = Path(); path.moveTo(toS(pathData.points[0]).x, toS(pathData.points[0]).y)
                            for (i in 1 until pathData.points.size) { val p = toS(pathData.points[i]); path.lineTo(p.x, p.y) }
                             val strokePx = (pathData.strokeWidthRatio * maxOf(bW, bH) * compositeScale).coerceAtLeast(1f)
                             drawPath(path, Color(pathData.colorArgb), if (pathData.isHighlighter) 0.4f else 1f, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    currentMeasurements.forEachIndexed { measurementIndex, storedMeasurement ->
                        // Gesture edits remain detached drafts until pointer-up;
                        // only the reducer publishes the persisted replacement.
                        val m = if (measurementIndex == selectedMeasurementIndex) {
                            measurementDraft ?: storedMeasurement
                        } else storedMeasurement
                        val p1 = toS(m.p1); val p2 = toS(m.p2)
                        val isSelectedMeasurement = measurementIndex == selectedMeasurementIndex
                        val color = if (isSelectedMeasurement) Color.Cyan else Color(m.colorArgb)
                        m.vertices.zipWithNext().forEach { (a, b) -> drawLine(color, toS(a), toS(b), strokeWidth = m.strokeWidthRatio * maxOf(bW, bH) * compositeScale) }
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
                    currentNotes.forEachIndexed { noteIndex, originalNoteValue ->
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
                            if (selected) Color.Cyan.toArgb() else n.colorArgb)
                    }

                    // Draw photo pins as camera icons
                    // Calculate global pin index across all pages
                    val allPinsSorted = allPagePhotoPins.keys.sorted().flatMap { pageIdx ->
                        allPagePhotoPins[pageIdx]?.map { it to pageIdx } ?: emptyList()
                    }

                    currentPhotoPins.forEach { pin ->
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

                    // Draw currentShapes
                    currentShapes.forEachIndexed { shapeIndex, originalShapeValue ->
                        val shape = if (shapeIndex == currentShapes.indexOfFirst { it.id == originalShape?.id } && shapeDraft != null) shapeDraft!! else originalShapeValue
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
                        drawPath(path, Color(toolSettings.style(mode).colorArgb), if(mode == ToolMode.HIGHLIGHTER) 0.4f else 1f, style = Stroke(toolSettings.style(mode).width * maxOf(bW, bH) * compositeScale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    polylinePoints.zipWithNext().forEach { (a, b) -> drawLine(Color(toolSettings.style(mode).colorArgb), toS(a), toS(b), 3f) }
                    polylinePoints.forEach { drawCircle(Color(toolSettings.style(mode).colorArgb), 5f, toS(it)) }
                    if (pointSelection.firstPoint != null && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                        val p1 = toS(pointSelection.firstPoint!!); drawCircle(Color(0xFFE91E63), 8f, p1)
                        pointSelection.secondPoint?.let { val p2 = toS(it); drawCircle(Color(0xFFE91E63), 8f, p2); drawLine(Color(0xFFE91E63), p1, p2, 4f) }
                    }
                }
                if (pointSelection.firstPoint != null && pointSelection.secondPoint != null && mode == ToolMode.SCALE) {
                    Box(Modifier.fillMaxSize().padding(bottom = 32.dp), Alignment.BottomCenter) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp, modifier = Modifier.clickable { pointSelection.firstPoint = null; pointSelection.secondPoint = null }) {
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
        ViewerFloatingControl(selectionToolbarPos, Modifier.testTag("sotaware.pdf.overlap-picker")) {
            Card(
                            modifier = Modifier.shadow(4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(stringResource(R.string.annotation_select_item), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
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

                                                measurementDraft = null
                                                if (item is PageItem.NoteItem) {
                                                    selectedNote = item.data

                                                } else {
                                                    selectedNote = null

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
        ViewerFloatingControl(selectionToolbarPos, Modifier.testTag("sotaware.pdf.annotation-toolbar")) {
            Card(
                            modifier = Modifier.shadow(4.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedItem !is PageItem.PhotoPinItem) {
                                    IconButton(onClick = { appearanceItem = selectedItem; appearanceError = false }) {
                                        Icon(Icons.Default.Palette, stringResource(R.string.annotation_appearance))
                                    }
                                }
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
    if (mode == ToolMode.POLYLINE && polylinePoints.isNotEmpty()) {
        Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))) {
            TextButton(onClick = { polylinePoints.clear() }) { Text(stringResource(R.string.clear_page_cancel)) }
            TextButton(enabled = polylinePoints.size >= 2, onClick = {
                val dimensions = sourcePageSize
                val measurement = dimensions?.let { buildMeasurement(polylinePoints.toList(), it.width, it.height, currentScale, toolSettings.style(mode)) }
                if (measurement != null && annotationReducer.addMeasurement(pageIndex, measurement).changed) {
                    polylinePoints.clear(); onAnnotationAdded()
                }
            }) { Text(stringResource(R.string.polyline_finish)) }
        }
    }
    if (onPageCodeRegionSelected != null && bitmapOwner != null) {
        if (selectingPageCode) {
            PageCodeRegionSelector(
                transform = {
                    val bitmap = checkNotNull(bitmapOwner).value
                    ViewerTransform(bitmap.width.toFloat(), bitmap.height.toFloat(), w, h, scale, offsetX, offsetY)
                },
                onSelected = { region -> onPageCodeSelectionDismissed(); onPageCodeRegionSelected(region) },
                onCancel = onPageCodeSelectionDismissed
            )
        }
    }
    // Text selection copy button - floating button near selection
    if (showCopyButton && selectedOcrBoxes.isNotEmpty()) {
        ViewerFloatingControl(copyButtonPos, Modifier.testTag("sotaware.pdf.copy-control")) {
            Card(
                modifier = Modifier.shadow(4.dp),
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
    }

    // Full screen image viewer - rendered on top of everything
    var fullScreenBitmapOwner by remember(
        sessionToken?.sourceCacheKey,
        sessionToken?.generation,
        pageIndex,
        selectedPhotoPin?.id,
        fullScreenImageFile
    ) { mutableStateOf<Stage7OwnedResource<Bitmap>?>(null) }

    var fullScreenSourceAspect by remember(sessionToken, pageIndex, fullScreenImageFile) { mutableFloatStateOf(1f) }
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
                        var loadedSourceAspect = 1f
                        stage7Worker.computeAndPublish(
                            compute = {
                                loadPhotoBitmapBlocking(
                                    context,
                                    sessionToken,
                                    fileName,
                                    viewportWidthPx = fullScreenViewport.width,
                                    viewportHeightPx = fullScreenViewport.height,
                                    onSourceAspect = { loadedSourceAspect = it }
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
                                fullScreenSourceAspect = loadedSourceAspect
                                if (previousOwner !== loadedOwner) previousOwner?.close()
                            },
                            reject = { rejectedOwner -> rejectedOwner.close() }
                        )
                    }
                }
            }

                val rotatedBmp = fullScreenBitmapOwner?.value
            if (rotatedBmp != null) {
                val imagePoints = remember(sessionToken, pageIndex, fullScreenImageFile, mode) { mutableStateListOf<Point>() }
                val imageStroke = remember(sessionToken, pageIndex, fullScreenImageFile, mode) { mutableStateListOf<Point>() }
                var selectedImageExtra by remember(sessionToken, pageIndex, fullScreenImageFile, mode) { mutableStateOf<PageItem?>(null) }
                var imageAppearance by remember(sessionToken, pageIndex, fullScreenImageFile) { mutableStateOf<Pair<PhotoPin, PageItem>?>(null) }
                var imageAppearanceError by remember { mutableStateOf(false) }
                var imageCalibrationInput by remember { mutableStateOf("") }
                var showImageCalibration by remember(sessionToken, pageIndex, fullScreenImageFile, mode) { mutableStateOf(false) }
                val imageAspect = fullScreenSourceAspect
                imageAppearance?.let { (pin, item) ->
                    com.example.myapplication.ui.ToolSettingsDialog(item.appearanceMode(), item.appearance(), false, imageAppearanceError,
                        onSave = { style ->
                            val file = fullScreenImageFile
                            if (file != null && annotationReducer.changeImageAppearance(pageIndex, pin, file, item, style) != AnnotationReducer.Result.Rejected) {
                                imageAppearance = null; imageAppearanceError = false; selectedImageExtra = null
                            } else imageAppearanceError = true
                        }, onDismiss = { imageAppearance = null; imageAppearanceError = false })
                }
                if (showImageCalibration) {
                    com.example.myapplication.ui.CalibrationDialog(imageCalibrationInput, measurementSourceLength(imagePoints, 1f, imageAspect),
                        onInputChange = { imageCalibrationInput = it },
                        onScaleDefined = { distance, feet ->
                            val pin = selectedPhotoPin; val file = fullScreenImageFile
                            pin != null && file != null && annotationReducer.setImageScale(pageIndex, pin, file, PageScale(distance / feet), imageAspect) != AnnotationReducer.Result.Rejected
                        }, onDismiss = { showImageCalibration = false; imagePoints.clear() },
                        onAccepted = { showImageCalibration = false; imagePoints.clear(); onToolModeSelected(ToolMode.PAN) })
                }
                var imageScale by remember { mutableStateOf(1f) }
                var imageOffsetX by remember { mutableStateOf(0f) }
                var imageOffsetY by remember { mutableStateOf(0f) }
                val imageFling = remember(sessionToken, pageIndex, fullScreenImageFile) { ViewerFling(momentumScope) }
                DisposableEffect(imageFling, imageNoteToolMode) { onDispose { imageFling.stop() } }
                val density = LocalDensity.current

                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Calculate the base size of the image after ContentScale.Fit is applied
                    // This is the size BEFORE our custom zoom (imageScale) is applied
                    val containerWidthPx = constraints.maxWidth.toFloat()
                    val containerHeightPx = constraints.maxHeight.toFloat()
                    DisposableEffect(imageFling, containerWidthPx, containerHeightPx) { onDispose { imageFling.stop() } }
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

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(sessionToken, pageIndex, mode, toolSettings, imageNoteToolMode, selectedPhotoPinId, fullScreenImageFile, constraints.maxWidth, constraints.maxHeight) {
                                awaitEachGesture {
                                    val gestureIdentity = latestImageGestureIdentity
                                    val gestureMode = latestImageGestureMode
                                    try {
                                        val gesturePinId = gestureIdentity.third.first ?: return@awaitEachGesture
                                        val gestureFile = gestureIdentity.third.second ?: return@awaitEachGesture

                                    val firstDown = awaitFirstDown()
                                    imageFling.stop()
                                    val imageVelocity = VelocityTracker().also { it.addPosition(firstDown.uptimeMillis, firstDown.position) }
                                    val startPos = firstDown.position
                                    fun imagePoint(position: Offset): Point = ViewerTransform(rotatedBmp.width.toFloat(), rotatedBmp.height.toFloat(),
                                        size.width.toFloat(), size.height.toFloat(), imageScale, imageOffsetX, imageOffsetY).toNormalized(position.x, position.y)
                                    imageStroke.clear()
                                    if (gestureMode == "pen" || gestureMode == "highlighter") imageStroke.add(imagePoint(startPos))
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

                                    val currentPin = selectedPhotoPin
                                    val currentFile = fullScreenImageFile
                                    val photoScene = currentPin?.let { pin -> currentFile?.let { com.example.myapplication.stage8.PhotoAnnotationScene.items(pin, it) } }.orEmpty()
                                    fun nearPhotoSegment(a: Point, b: Point): Boolean = distToSegment(
                                        Point(startPos.x - currentImgLeft, startPos.y - currentImgTop),
                                        Point(a.x * currentDisplayedWidth, a.y * currentDisplayedHeight),
                                        Point(b.x * currentDisplayedWidth, b.y * currentDisplayedHeight)) < 24f
                                    val hit = com.example.myapplication.stage8.PhotoAnnotationScene.hit(photoScene) { item ->
                                        when (item) {
                                            is PageItem.NoteItem -> AnnotationCanvasRendering.containsNote(item.data, startPos.x - currentImgLeft,
                                                startPos.y - currentImgTop, currentDisplayedWidth, currentDisplayedHeight, 10f * density.density)
                                            is PageItem.ShapeItem -> AnnotationCanvasRendering.containsShape(item.data, startPos.x - currentImgLeft,
                                                startPos.y - currentImgTop, currentDisplayedWidth, currentDisplayedHeight, 30f)
                                            is PageItem.Measure -> item.data.vertices.zipWithNext().any { (a,b) -> nearPhotoSegment(a,b) }
                                            is PageItem.Path -> item.data.points.zipWithNext().any { (a,b) -> nearPhotoSegment(a,b) }
                                            is PageItem.PhotoPinItem -> false
                                        }
                                    }
                                    val tappedNote = (hit as? PageItem.NoteItem)?.data
                                    val tappedShape = (hit as? PageItem.ShapeItem)?.data
                                    if (imageNoteToolMode == "pan") {
                                        if (tappedNote != null || tappedShape != null) {
                                            selectedImageNote = tappedNote
                                            selectedImageShape = tappedShape
                                        }
                                        if (tappedNote != null) {
                                            draggingImageNote = tappedNote
                                            originalImageNote = tappedNote.copy()
                                            imageNoteDraft = tappedNote.copyImageNote()
                                        }
                                        if (tappedShape != null) {
                                            draggingImageShape = true
                                            originalImageShape = tappedShape.copy()
                                            imageShapeDraft = tappedShape.copyShape()
                                        }
                                    }

                                     do {
                                         val event = awaitPointerEvent()
                                          // Compose adapts ACTION_CANCEL in
                                          // SuspendingPointerInputModifierNodeImpl by dispatching
                                          // consumed all-up changes.  Read that incoming
                                          // consumption before this handler consumes any change.
                                          if (event.isIncomingCancellation()) {
                                              gestureCancelled = true
                                     selectionRevision++; selectionJob?.cancel()
                                              break
                                          }
                                          if (event.changes.size == 1) {
                                              val change = event.changes.single()
                                              imageVelocity.addPosition(change.uptimeMillis, change.position)
                                          }
                                         if (event.changes.size >= 2) {
                                            wasZoom = true
                                            val zoom = event.calculateZoom()
                                            val rotation = event.calculateRotation()
                                            if (selectedImageNote != null) {
                                                // Pinch to resize/rotate a current-format ratio-sized note.
                                                if (originalImageNote == null) originalImageNote = selectedImageNote!!.copyImageNote()
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
                                                if (originalImageShape == null) originalImageShape = selectedImageShape!!.copyShape()
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

                                                if (gestureMode == "pen" || gestureMode == "highlighter") {
                                                    if (imageStroke.size < com.example.myapplication.stage5.Stage5Limits.MAX_PATH_POINTS) imageStroke.add(imagePoint(change.position))
                                                } else if (draggingImageNote != null && gestureMode == "pan") {
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
                                                } else if (gestureMode == "pan") {
                                                    // Pan image
                                                    imageOffsetX = (imageOffsetX + delta.x).coerceIn(-baseImgWidth * imageScale / 2, baseImgWidth * imageScale / 2)
                                                    imageOffsetY = (imageOffsetY + delta.y).coerceIn(-baseImgHeight * imageScale / 2, baseImgHeight * imageScale / 2)
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
                                    if (!gestureCancelled && wasDrag && !wasZoom && !imageDocumentChanged &&
                                        gestureMode == "pan" && tappedNote == null && tappedShape == null) {
                                        val release = imageVelocity.calculateVelocity()
                                        imageFling.start(Offset(release.x, release.y),
                                            isCurrent = { latestImageGestureIdentity == gestureIdentity &&
                                                latestImageGestureMode == gestureMode &&
                                                latestPageAdmission(gestureIdentity.first, gestureIdentity.second) },
                                            panBy = { delta ->
                                                val nextX = (imageOffsetX + delta.x).coerceIn(-baseImgWidth * imageScale / 2, baseImgWidth * imageScale / 2)
                                                val nextY = (imageOffsetY + delta.y).coerceIn(-baseImgHeight * imageScale / 2, baseImgHeight * imageScale / 2)
                                                val moved = nextX != imageOffsetX || nextY != imageOffsetY
                                                imageOffsetX = nextX; imageOffsetY = nextY
                                                moved
                                            })
                                    }
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

                                    if (!gestureCancelled && !wasZoom && (gestureMode == "pen" || gestureMode == "highlighter") && imageStroke.size >= 2 && currentPin != null && currentFile != null) {
                                        val style = toolSettings.style(mode)
                                        annotationReducer.addImagePath(pageIndex, currentPin, currentFile,
                                            DrawnPath(imageStroke.toList(), style.colorArgb, gestureMode == "highlighter", style.width))
                                    }
                                    imageStroke.clear()
                                    // Handle tap (not drag)
                                     if (!gestureCancelled && !wasDrag && !wasZoom) {
                                        if (gestureMode == "measure" || gestureMode == "polyline" || gestureMode == "scale") {
                                            val pin = selectedPhotoPin; val file = fullScreenImageFile
                                            val calibration = pin?.imageScales?.get(file)
                                            if (gestureMode != "scale" && calibration == null) {
                                                Toast.makeText(context, R.string.measure_calibrate_first, Toast.LENGTH_SHORT).show()
                                            } else if (imagePoints.size < com.example.myapplication.stage5.Stage5Limits.MAX_PATH_POINTS) {
                                                imagePoints.add(imagePoint(startPos))
                                                if (imagePoints.size == 2 && gestureMode == "scale") showImageCalibration = true
                                                else if (imagePoints.size == 2 && gestureMode == "measure" && pin != null && file != null) {
                                                    buildMeasurement(imagePoints.toList(), 1f, imageAspect, calibration, toolSettings.style(mode))?.let {
                                                        if (annotationReducer.addImageMeasurement(pageIndex, pin, file, it).changed) { imagePoints.clear(); onAnnotationAdded() }
                                                    }
                                                }
                                            }
                                        } else if (imageNoteToolMode == "place") {
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
                                                imageNoteIsBold = toolSettings.style(ToolMode.NOTE).bold
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
                                                     colorArgb = toolSettings.style(ToolMode.SHAPE).colorArgb,
                                                     isFilled = toolSettings.style(ToolMode.SHAPE).filled,
                                                    strokeWidthRatio = toolSettings.style(ToolMode.SHAPE).width,
                                                    widthRatio = defaultWidthRatio,
                                                    heightRatio = defaultHeightRatio
                                                )

                                                if (selectedPhotoPin != null && fullScreenImageFile != null) {
                                                     if (annotationReducer.addImageShape(pageIndex, selectedPhotoPin!!.id, fullScreenImageFile!!, newShape).changed) {
                                                         selectedImageShape = newShape; onAnnotationAdded()
                                                     }
                                                }
                                                imageNoteToolMode = "pan"
                                            }
                                        } else if (gestureMode == "pan" && tappedNote == null && tappedShape == null) {
                                            selectedImageNote = null; selectedImageShape = null
                                            selectedImageExtra = hit?.takeIf { it is PageItem.Measure || it is PageItem.Path }
                                        } else if (tappedNote != null) {
                                            selectedImageExtra = null
                                            // Tapped on existing note - select it
                                                selectedImageNote = tappedNote
                                            selectedImageShape = null
                                        } else if (tappedShape != null) {
                                            selectedImageExtra = null
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
                                        imageStroke.clear()
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

                        Canvas(Modifier.fillMaxSize().testTag("sotaware.photo.annotations")) {
                            val pin = selectedPhotoPin; val file = fullScreenImageFile
                            val scene = com.example.myapplication.stage8.PhotoAnnotationScene.items(
                                pin?.imagePaths?.get(file).orEmpty(), pin?.imageMeasurements?.get(file).orEmpty(),
                                pin?.imageNotes?.get(file).orEmpty().map { original ->
                                    val note = imageNoteDraft?.takeIf { it.id == original.id } ?: original
                                    if (note.id == selectedImageNote?.id) note.copy(colorArgb = Color.Cyan.toArgb()) else note
                                },
                                pin?.imageShapes?.get(file).orEmpty().map { original ->
                                    val shape = imageShapeDraft?.takeIf { it.id == original.id } ?: original
                                    if (shape.id == selectedImageShape?.id) shape.copy(colorArgb = Color.Cyan.toArgb()) else shape
                                })
                            AnnotationCanvasRendering.drawPhotoScene(drawContext.canvas.nativeCanvas, scene,
                                imgLeft, imgTop, displayedImgWidth, displayedImgHeight)
                            if (imageStroke.isNotEmpty()) {
                                val style = toolSettings.style(mode)
                                AnnotationCanvasRendering.drawPath(drawContext.canvas.nativeCanvas,
                                    DrawnPath(imageStroke.toList(), style.colorArgb, mode == ToolMode.HIGHLIGHTER, style.width), imgLeft, imgTop, displayedImgWidth, displayedImgHeight)
                            }
                            imagePoints.zipWithNext().forEach { (a, b) -> drawLine(Color.Cyan, imageToScreenCoords(a.x, a.y), imageToScreenCoords(b.x, b.y), 3f) }
                            imagePoints.forEach { drawCircle(Color.Cyan, 6f, imageToScreenCoords(it.x, it.y)) }
                        }
                        // Selection handles are transient and are never part of the exported scene.
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
                            IconButton(onClick = { selectedPhotoPin?.let { pin -> selectedImageNote?.let { imageAppearance = pin to PageItem.NoteItem(it) } } }) {
                                Icon(Icons.Default.Palette, stringResource(R.string.annotation_appearance), tint = Color.White)
                            }
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
                            IconButton(onClick = { selectedPhotoPin?.let { pin -> selectedImageShape?.let { imageAppearance = pin to PageItem.ShapeItem(it) } } }) {
                                Icon(Icons.Default.Palette, stringResource(R.string.annotation_appearance), tint = Color.White)
                            }
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

                    if (mode == ToolMode.POLYLINE && imagePoints.isNotEmpty()) {
                        Row(Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface)) {
                            TextButton(onClick = { imagePoints.clear() }) { Text(stringResource(R.string.clear_page_cancel)) }
                            TextButton(enabled = imagePoints.size >= 2, onClick = {
                                val pin = selectedPhotoPin; val file = fullScreenImageFile
                                if (pin != null && file != null) {
                                    buildMeasurement(imagePoints.toList(), 1f, imageAspect, pin.imageScales[file], toolSettings.style(mode))?.let {
                                        if (annotationReducer.addImageMeasurement(pageIndex, pin, file, it).changed) { imagePoints.clear(); onAnnotationAdded() }
                                    }
                                }
                            }) { Text(stringResource(R.string.polyline_finish)) }
                        }
                    }
                    if (mode == ToolMode.PAN && selectedImageExtra != null && selectedImageNote == null && selectedImageShape == null) {
                        Row(Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface)) {
                            IconButton(onClick = { selectedPhotoPin?.let { pin -> selectedImageExtra?.let { imageAppearance = pin to it } } }) {
                                Icon(Icons.Default.Palette, stringResource(R.string.annotation_appearance))
                            }
                            IconButton(onClick = {
                                val pin = selectedPhotoPin; val file = fullScreenImageFile; val item = selectedImageExtra
                                if (pin != null && file != null) {
                                    val replacement = when (item) {
                                        is PageItem.Path -> pin.copy(imagePaths = pin.imagePaths + (file to pin.imagePaths[file].orEmpty().filterNot { it.id == item.data.id }))
                                        is PageItem.Measure -> pin.copy(imageMeasurements = pin.imageMeasurements + (file to pin.imageMeasurements[file].orEmpty().filterNot { it.id == item.data.id }))
                                        else -> pin
                                    }
                                    annotationReducer.updatePhotoPin(pageIndex, pin, replacement, AnnotationReducer.Kind.DELETE)
                                }
                                selectedImageExtra = null
                            }) { Icon(Icons.Default.Delete, stringResource(R.string.annotation_delete_confirm)) }
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
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
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
