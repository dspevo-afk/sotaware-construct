package com.example.myapplication

import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.example.myapplication.stage8.PageCodeRegion
import com.example.myapplication.stage8.PageCodeRegionSelector
import com.example.myapplication.stage8.TextSelectionRendering
import com.example.myapplication.stage8.appearance
import com.example.myapplication.stage8.appearanceMode
import com.example.myapplication.stage8.changeAppearance
import com.example.myapplication.stage8.changeSubDrawingAppearance
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.example.myapplication.stage8.MarkupTarget
import com.example.myapplication.stage8.MarkupMove
import com.example.myapplication.ui.SelectionToolbar
import com.example.myapplication.ui.SelectionToolbarCard
import com.example.myapplication.ui.ToolbarAction
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
import com.example.myapplication.stage7.DisplayViewport
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
import java.util.UUID

internal const val PDF_READY_CANVAS_TAG = "sotaware.pdf.ready-canvas"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // Pin numbering source: page-level pins followed by each sub-drawing's own
    // pins, in crop order - the same order the PDF export numbers them.
    allPagePhotoPins: Map<Int, List<PhotoPin>>,
    searchTerm: String,
    highlightRects: List<RectF>,
    /** The search match the bar is on: the sheet zooms to it and it gets an outline. */
    focusRect: RectF? = null,
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
    // The host's Undo (the same one as its Undo button); used by "Deleted - Undo".
    onUndoAnnotation: ((Int) -> Unit)? = null,
    onPhotoTargetChanged: (Pair<String, String>?) -> Unit = {},
    subDrawings: SnapshotStateList<SubDrawing> = mutableStateListOf(),
    showSubDrawings: Boolean = true,
    // Sheet numbers as the sheet list shows them ("E1"), keyed by page index.
    // A crop's badge carries its sheet number, falling back to the page number.
    pageCodes: Map<Int, String> = emptyMap(),
    onOpenSubDrawing: (String) -> Unit = {},
    // Receives the id of the sub-drawing this viewer has open, or null when it is
    // showing the page. The host's Clear confirm needs that id so Clear inside a
    // sub-drawing clears that crop's own markups and never the sheet's.
    onSubDrawingViewActive: (String?) -> Unit = {},
    onSubDrawingBackRequestActive: (Boolean) -> Unit = {},
    // Incremented by the host when its own Back affordance should leave the
    // open sub-drawing; the crop closes and the page it came from stays open.
    subDrawingExitSignal: Int = 0,
    // The zoom the page is at, passed to creation so a new crop's badge spawns at
    // a size that matches what the user was looking at: a crop drawn while zoomed
    // into a small room gets a small badge on the sheet, not a huge circle.
    // Publishes the dragged crop. [subDrawingId] is the id this viewer generated for it;
    // the host must publish the crop under that exact id so the naming popup can tell this
    // crop from any identical crop already on the page.
    onCreateSubDrawing: (PdfNormalizedRect, String, Float) -> Unit = { _, _, _ -> },
    onDeleteSubDrawing: (String) -> Unit = {},
    // Throws away a crop the user just created and never named (naming popup Cancel).
    onAbandonSubDrawing: (String) -> Unit = { id -> onDeleteSubDrawing(id) },
    // Applies a room name to a sub-drawing ("104", "Kitchen"); null clears it.
    onRenameSubDrawing: (String, String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val viewerColors = com.example.myapplication.ui.theme.LocalViewerColors.current
    val pdfSearchEngine = remember(stage7Worker, ocrIndex) {
        PdfSearchEngine(context, stage7Worker, ocrIndex ?: OcrIndex(context, stage7Worker))
    }
    val textMeasurer = rememberTextMeasurer()
    val annotationDensity = LocalDensity.current.density.coerceAtLeast(0.1f)
    var bitmapOwner by remember(uri, sessionToken, pageIndex) { mutableStateOf<Stage7OwnedResource<Bitmap>?>(null) }
    var sourcePageSize by remember(uri, sessionToken, pageIndex) { mutableStateOf<AnnotationSize?>(null) }
    // A sub-drawing opens as its own virtual page: the same viewer, tool, and
    // markup code runs against the crop bitmap and the sub-drawing's own lists.
    val activeSubDrawingId = rememberSaveable(sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf<String?>(null) }
    val isSubDrawingView = activeSubDrawingId.value != null
    val activeSubDrawing = activeSubDrawingId.value?.let { id -> subDrawings.firstOrNull { it.id == id } }
    // Label of the open crop for the banner: its order among this page's crops.
    val activeSubDrawingOrdinal = remember(activeSubDrawingId.value, subDrawings) {
        activeSubDrawingId.value?.let { id -> subDrawings.indexOfFirst { it.id == id } } ?: -1
    }
    // Observed content of the page's crop list. The list object itself never
    // changes identity when the host adds a crop, so the effects below key on
    // this snapshot instead - keying on the list alone never re-triggers.
    val subDrawingsSignature = subDrawings.size.toString() + ":" +
        subDrawings.joinToString("|") { "${it.id}:${it.name.orEmpty()}" }
    LaunchedEffect(activeSubDrawingId.value, subDrawingsSignature) {
        // A deleted or externally removed sub-drawing returns to its page.
        if (activeSubDrawingId.value != null && activeSubDrawing == null) activeSubDrawingId.value = null
    }
    val currentSubDrawings by rememberUpdatedState(subDrawings.toList())
    // The crop whose page badge is selected (long-press on a badge with the pan
    // tool). Its badge is highlighted in cyan and a pinch resizes only that badge.
    val selectedSubDrawingId = remember { mutableStateOf<String?>(null) }
    // Live badge radius while the selected badge is being pinched; published to
    // the reducer on pointer-up as one undoable transaction.
    var subDrawingBadgeDraft by remember(sessionToken, pageIndex) { mutableStateOf<Float?>(null) }
    // Crop awaiting its room name right after the drag that created it. The
    // host creates crops through its canonical command, which assigns a fresh
    // id, so the popup arms only for the crop this viewer's own drag made -
    // never for an unnamed crop loaded from a saved drawing.
    val pendingCreatedCrop = remember { mutableStateOf<PdfNormalizedRect?>(null) }
    // Identity of the crop this viewer's own drag asked the host to create. The host
    // builds its published crop from a freshly generated id, so the id cannot match;
    // [pendingCreatedCropId] exists only to tell "the crop my drag made" apart from an
    // unnamed crop that arrives from elsewhere (a saved drawing restored, a sync
    // landing), which must never open this viewer's naming popup.
    val pendingCreatedCropId = remember { mutableStateOf<String?>(null) }
    val pendingNameSubDrawingId = remember { mutableStateOf<String?>(null) }
    var subDrawingNameInput by remember(sessionToken, pageIndex) { mutableStateOf("") }
    val currentSubDrawingNameInput by rememberUpdatedState(subDrawingNameInput)
    // Crop whose room name is being edited from inside the sub-drawing.
    var showRenameDialog by remember(sessionToken, pageIndex) { mutableStateOf(false) }
    // The crop the rename dialog names when it was opened from a badge's toolbar on
    // the page; null means the open sub-drawing.
    var renameSubId by remember(sessionToken, pageIndex) { mutableStateOf<String?>(null) }
    LaunchedEffect(subDrawingsSignature) {
        val crop = pendingCreatedCrop.value ?: return@LaunchedEffect
        fun sameCrop(sub: SubDrawing): Boolean =
            sub.left == crop.left && sub.top == crop.top && sub.right == crop.right && sub.bottom == crop.bottom
        // Match the crop this drag published, not just any crop with the same rectangle.
        // A page can already hold an unnamed crop over exactly that area (a saved drawing,
        // a synced one), and matching by area alone armed the popup for *that* crop: it
        // popped up over the new crop's name, Cancel threw away the old crop, and its
        // removal wiped the new crop with it - which is how a finished drag ended with no
        // dialog and no sub-drawing at all. The host keeps the id this viewer generated,
        // so the published crop answers to it and nothing else does.
        val createdId = pendingCreatedCropId.value
        val created = if (createdId != null) subDrawings.firstOrNull { it.id == createdId }
        else subDrawings.lastOrNull { sameCrop(it) && it.name.isNullOrBlank() }
        if (created != null) {
            pendingNameSubDrawingId.value = created.id
            subDrawingNameInput = ""
            pendingCreatedCrop.value = null
            pendingCreatedCropId.value = null
        } else if (subDrawings.none(::sameCrop)) {
            // The crop never arrived (rejected by capacity limits): nothing to name.
            pendingCreatedCrop.value = null
            pendingCreatedCropId.value = null
        }
    }
    // Sheet label for a crop badge: the sheet's own number as the sheet list
    // shows it ("E1"), falling back to the page number when none is set. Plain
    // (not composable) so the page Canvas can paint every badge.
    val badgeSheetLabel = pageCodes[pageIndex] ?: context.getString(R.string.sheet_number, pageIndex + 1)
    // Picker/banner label for one crop: its name when it has one, so a room
    // reads as "104", and its number on this page otherwise.
    fun subDrawingPickerLabel(sub: SubDrawing): String =
        SubDrawingLabels.picker(sub, (subDrawings.indexOfFirst { it.id == sub.id } + 1).coerceAtLeast(1)) {
            index -> context.getString(R.string.sub_drawing_item_numbered, index)
        }
    // Opening a sub-drawing is local to this viewer: the same composable keeps
    // running against the crop bitmap and the sub-drawing's own markup lists.
    fun openSubDrawing(id: String) {
        // Only page-level sub-drawings open, and only from the page view.
        if (!isSubDrawingView && subDrawings.any { it.id == id }) {
            activeSubDrawingId.value = id
            onOpenSubDrawing(id)
        }
    }
    val subScaleForMeasure: PageScale? = if (activeSubDrawing != null)
        activeSubDrawing.scale
            ?: SubDrawingGeometry.inheritedScale(currentScale, activeSubDrawing.widthRatio)
    else currentScale
    val sourceSurfaceSize: AnnotationSize? = if (activeSubDrawing != null) {
        // Source space of the crop: its own share of the page's points. Paired
        // with the inherited page scale this makes a measured length inside the
        // sub-drawing equal the same length measured on the page.
        activeSubDrawing?.let { SubDrawingGeometry.cropSourceSize(sourcePageSize?.width ?: 0f, sourcePageSize?.height ?: 0f, it) }
    } else sourcePageSize
    val currentSourceSurfaceSize by rememberUpdatedState(sourceSurfaceSize)
    // Markup mutations name the surface being shown and the reducer routes them
    // to its storage: the page, the open sub-drawing, or one photo of a pin
    // (wherever that pin is stored). No per-surface branching here.
    val subIdForMarkup = { activeSubDrawing?.id }
    fun markupTarget(): MarkupTarget = subIdForMarkup()?.let { MarkupTarget.SubDrawing(pageIndex, it) } ?: MarkupTarget.Sheet(pageIndex)
    fun photoTarget(pin: PhotoPin, file: String): MarkupTarget.Photo = MarkupTarget.Photo(pageIndex, pin.id, file)
    fun addPathMarkup(path: DrawnPath) = annotationReducer.add(markupTarget(), PageItem.Path(path))
    fun addMeasurementMarkup(measurement: Measurement) = annotationReducer.add(markupTarget(), PageItem.Measure(measurement))
    fun addNoteMarkup(note: Note): AnnotationReducer.Result = annotationReducer.add(markupTarget(), PageItem.NoteItem(note))
    fun updateNoteMarkup(current: Note, replacement: Note, kind: AnnotationReducer.Kind = AnnotationReducer.Kind.UPDATE): AnnotationReducer.Result =
        annotationReducer.update(markupTarget(), PageItem.NoteItem(current), PageItem.NoteItem(replacement), kind)
    fun addShapeMarkup(shape: Shape) = annotationReducer.add(markupTarget(), PageItem.ShapeItem(shape))
    fun updateShapeMarkup(current: Shape, replacement: Shape, kind: AnnotationReducer.Kind): AnnotationReducer.Result =
        annotationReducer.update(markupTarget(), PageItem.ShapeItem(current), PageItem.ShapeItem(replacement), kind)
    fun addPhotoMarkup(pin: PhotoPin): AnnotationReducer.Result = annotationReducer.add(markupTarget(), PageItem.PhotoPinItem(pin))
    fun updatePinMarkup(current: PhotoPin, replacement: PhotoPin, kind: AnnotationReducer.Kind = AnnotationReducer.Kind.UPDATE): AnnotationReducer.Result =
        annotationReducer.update(markupTarget(), PageItem.PhotoPinItem(current), PageItem.PhotoPinItem(replacement), kind)
    fun deletePinMarkup(pin: PhotoPin): AnnotationReducer.Result = annotationReducer.delete(markupTarget(), PageItem.PhotoPinItem(pin))
    fun addImageMarkup(pin: PhotoPin, file: String, item: PageItem): AnnotationReducer.Result =
        annotationReducer.add(photoTarget(pin, file), item)
    fun updateImageMarkup(pin: PhotoPin, file: String, current: PageItem, replacement: PageItem, kind: AnnotationReducer.Kind = AnnotationReducer.Kind.UPDATE): AnnotationReducer.Result =
        annotationReducer.update(photoTarget(pin, file), current, replacement, kind)
    fun deleteImageMarkup(pin: PhotoPin, file: String, item: PageItem): AnnotationReducer.Result =
        annotationReducer.delete(photoTarget(pin, file), item)
    fun setPinImageScale(pin: PhotoPin, file: String, scale: PageScale, aspect: Float?): AnnotationReducer.Result =
        subIdForMarkup()?.let { annotationReducer.setSubDrawingImageScale(pageIndex, it, pin.id, file, scale, aspect) }
            ?: annotationReducer.setImageScale(pageIndex, pin, file, scale, aspect)
    fun changeImageAppearanceMarkup(pin: PhotoPin, file: String, item: PageItem, style: com.example.myapplication.stage8.DrawingToolStyle): AnnotationReducer.Result =
        subIdForMarkup()?.let { annotationReducer.changeSubDrawingImageAppearance(pageIndex, it, pin.id, file, item, style) }
            ?: annotationReducer.changeImageAppearance(pageIndex, pin, file, item, style)

    fun updateMeasurementPoint(index: Int, replacement: Measurement, before: Measurement): AnnotationReducer.Result =
        subIdForMarkup()?.let { annotationReducer.updateSubDrawingMeasurement(pageIndex, it, before, replacement, AnnotationReducer.Kind.MOVE) }
            ?: annotationReducer.updateMeasurementAt(pageIndex, index, replacement, AnnotationReducer.Kind.MOVE, before = before)
    var scale by rememberSaveable(uri.toString(), sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf(1f) }
    var offsetX by rememberSaveable(uri.toString(), sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf(0f) }
    var offsetY by rememberSaveable(uri.toString(), sessionToken?.sourceCacheKey, sessionToken?.generation, pageIndex) { mutableStateOf(0f) }
    var cropBitmapOwner by remember(uri, sessionToken, pageIndex, activeSubDrawingId.value) { mutableStateOf<Stage7OwnedResource<Bitmap>?>(null) }
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


    var appearanceItem by remember(sessionToken, pageIndex) { mutableStateOf<PageItem?>(null) }
    var appearanceError by remember { mutableStateOf(false) }
    appearanceItem?.let { item ->
        com.example.myapplication.ui.ToolSettingsDialog(item.appearanceMode(), item.appearance(), false, appearanceError,
            onSave = { style ->
                val subId = activeSubDrawing?.id
                val accepted = if (subId != null) annotationReducer.changeSubDrawingAppearance(pageIndex, subId, item, style)
                    else annotationReducer.changeAppearance(pageIndex, item, style)
                if (accepted != AnnotationReducer.Result.Rejected) {
                    appearanceItem = null; appearanceError = false
                } else appearanceError = true
            }, onDismiss = { appearanceItem = null; appearanceError = false })
    }
    val currentMeasurements by rememberUpdatedState(activeSubDrawing?.measurements ?: measurements)
    val currentNotes by rememberUpdatedState(activeSubDrawing?.notes ?: notes)
    val currentPaths by rememberUpdatedState(activeSubDrawing?.paths ?: paths)
    val currentPhotoPins by rememberUpdatedState(activeSubDrawing?.photos ?: photoPins)
    val currentShapes by rememberUpdatedState(activeSubDrawing?.shapes ?: shapes)
    var selectedKey by remember(sessionToken, pageIndex) { mutableStateOf<PdfSelectionKey?>(null) }
    var selectedItem by object : kotlin.properties.ReadWriteProperty<Any?, PageItem?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): PageItem? =
            selectedKey?.resolve(
                currentMeasurements, currentNotes, currentPaths, currentPhotoPins, currentShapes,
                if (isSubDrawingView) emptyList() else currentSubDrawings
            )
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
    var subDrawingDraft by remember(sessionToken, pageIndex) { mutableStateOf<PdfNormalizedRect?>(null) }
    val currentSubDrawingDraft by rememberUpdatedState(subDrawingDraft)
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
    // Whole-item move: live drafts while a selected pen line, photo pin or badge
    // follows the finger (a measurement uses measurementDraft); published on
    // release as one MOVE transaction.
    var pathDraft by remember { mutableStateOf<DrawnPath?>(null) }
    var pinDraft by remember { mutableStateOf<PhotoPin?>(null) }
    var badgeCenterDraft by remember(sessionToken, pageIndex) { mutableStateOf<Point?>(null) }
    // Double-tap: the item the last quick tap selected, and when that finger lifted.
    var lastTapKey by remember { mutableStateOf<PdfSelectionKey?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }

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

    /**
     * The one place that clears everything the viewer has selected: the picked item,
     * its per-kind selection, the selected sub-drawing badge, and the drafts each of
     * them can carry. Every site that ends a selection - tapping empty space, tapping
     * a crop to open it, the overlap picker, a long-press picking a badge, and a
     * document or page switch - calls this, so no site can leave one kind selected
     * while clearing the rest.
     */
    /** The overlap-picker icon for one page item. */
    @Composable
    fun annotationIcon(item: PageItem): androidx.compose.ui.graphics.vector.ImageVector = when (item) {
        is PageItem.Measure -> Icons.Default.Straighten
        is PageItem.NoteItem -> Icons.Filled.StickyNote2
        is PageItem.Path -> Icons.Default.Brush
        is PageItem.PhotoPinItem -> Icons.Default.CameraAlt
        is PageItem.SubDrawingItem -> Icons.Default.PictureInPicture
        is PageItem.ShapeItem -> when (item.data.type) {
            ShapeType.RECTANGLE -> Icons.Default.CropSquare
            ShapeType.CIRCLE -> Icons.Default.Circle
            ShapeType.ARROW -> Icons.AutoMirrored.Filled.ArrowForward
            ShapeType.CLOUD -> Icons.Default.Cloud
        }
    }

    /** The overlap-picker label for one page item. */
    @Composable
    fun annotationLabel(item: PageItem): String = when (item) {
        is PageItem.Measure -> stringResource(R.string.annotation_measure)
        is PageItem.NoteItem -> stringResource(R.string.annotation_note)
        is PageItem.Path -> stringResource(if (item.data.isHighlighter) R.string.annotation_highlight else R.string.annotation_drawing)
        is PageItem.PhotoPinItem -> stringResource(R.string.annotation_photo)
        is PageItem.SubDrawingItem -> subDrawingPickerLabel(item.data)
        is PageItem.ShapeItem -> when (item.data.type) {
            ShapeType.RECTANGLE -> stringResource(R.string.shape_rectangle)
            ShapeType.CIRCLE -> stringResource(R.string.shape_circle)
            ShapeType.ARROW -> stringResource(R.string.shape_arrow)
            ShapeType.CLOUD -> stringResource(R.string.shape_cloud)
        }
    }


    /**
     * The one place a tapped or picked item becomes the current selection: the item
     * itself plus exactly the per-kind selection it owns, so a tap and the overlap
     * picker leave the viewer in the same state. A picked badge becomes the selected
     * badge (cyan, pinch resizes it); picking anything else ends a badge selection.
     */
    fun applyPageSelection(item: PageItem) {
        selectedItem = item
        selectedSubDrawingId.value = (item as? PageItem.SubDrawingItem)?.data?.id
        subDrawingBadgeDraft = null
        selectedMeasurement = (item as? PageItem.Measure)?.data
        measurementDraft = null
        selectedNote = (item as? PageItem.NoteItem)?.data
        selectedPhotoPin = (item as? PageItem.PhotoPinItem)?.data
        selectedShape = (item as? PageItem.ShapeItem)?.data
    }


    fun clearPageSelection() {
        selectedItem = null
        selectedMeasurement = null
        measurementDraft = null
        selectedNote = null
        selectedPhotoPin = null
        selectedShape = null
        selectedSubDrawingId.value = null
        subDrawingBadgeDraft = null
    }


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

    // A sub-drawing renders its crop from the PDF at display resolution plus the
    // quality multiplier, so opening one is sharp source rendering, never a
    // stretched copy of the low-resolution page bitmap.
    val subDrawingViewport = remember { mutableStateOf<DisplayViewport?>(null) }
    DisposableEffect(uri, sessionToken, pageIndex, activeSubDrawingId.value) {
        onDispose {
            val previousOwner = cropBitmapOwner
            cropBitmapOwner = null
            previousOwner?.close()
        }
    }
    LaunchedEffect(uri, sessionToken, pageIndex, activeSubDrawingId.value, subDrawingViewport.value) {
        val sub = activeSubDrawingId.value?.let { id -> currentSubDrawings.firstOrNull { it.id == id } }
        val viewport = subDrawingViewport.value
        if (sub == null || viewport == null) {
            val previousOwner = cropBitmapOwner
            cropBitmapOwner = null
            previousOwner?.close()
            return@LaunchedEffect
        }
        val subId = sub.id
        val left = sub.left; val top = sub.top; val right = sub.right; val bottom = sub.bottom
        stage7Worker.computeAndPublish(
            compute = {
                currentCoroutineContext().ensureActive()
                val owner = Stage7ResourceOwner<Bitmap>(::recycleBitmap)
                try {
                    val rendered = PdfBitmapRenderer(context).use {
                        it.renderPageRegionBitmap(
                            uri = uri,
                            pageIndex = pageIndex,
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            onBitmapCreated = owner::own,
                            viewportWidthPx = viewport.width,
                            viewportHeightPx = viewport.height
                        )
                    }
                    if (rendered == null) {
                        owner.close()
                        null
                    } else {
                        currentCoroutineContext().ensureActive()
                        owner.owned(rendered)
                    }
                } catch (error: Throwable) {
                    owner.close()
                    throw error
                }
            },
            acceptsBeforeMain = { isSessionCurrent(sessionToken) },
            acceptsOnMain = {
                isSessionCurrent(sessionToken) && isPageCurrent(sessionToken, pageIndex) &&
                    activeSubDrawingId.value == subId
            },
            publish = { nextOwner ->
                val previousOwner = cropBitmapOwner
                cropBitmapOwner = nextOwner
                if (previousOwner !== nextOwner) previousOwner?.close()
                onPageRendered()
            },
            reject = { rejectedOwner -> rejectedOwner.close() }
        )
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
    // The OCR boxes a long-press selects are rectangles of the page the viewer shows. Inside an
    // open sub-drawing the canvas paints the crop bitmap and maps page points through the crop,
    // so a crop-space box has no mapping there: keeping the selection would show the Copy popup
    // with nothing highlighted under it. Opening a crop therefore ends the text selection;
    // leaving the crop keeps it (boxes and all), and plain page long-press is untouched.
    LaunchedEffect(isSubDrawingView) {
    if (isSubDrawingView) {
            isTextSelecting = false; showCopyButton = false; selectedOcrBoxes = emptyList()
            textSelectionStartIdx = -1; textSelectionEndIdx = -1
            selectionRevision++; selectionJob?.cancel(); selectionJob = null
        }
    }
    LaunchedEffect(mode, selectingPageCode) {
        // Text can be selected in every tool, so a tool change keeps it; drawing a
        // page-code box is the one mode that owns the long-press and clears it.
        if (selectingPageCode) {
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
            // Calibration is always measured in source points; inside a
            // sub-drawing that is the crop's own source size.
            sourceSurfaceSize?.let { source ->
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
            onScaleDefined = { distance, feet ->
                val sub = activeSubDrawing
                if (sub != null) {
                    // Calibrating inside a sub-drawing overrides only that
                    // sub-drawing. `distance` is already crop source points, and
                    // the sub-drawing's stored scale is page-space points per
                    // foot, so no crop factor enters the calculation.
                    val result = com.example.myapplication.stage8.calculatePageScale(distance, feet)
                    val scaleValue = (result as? com.example.myapplication.stage8.CalibrationScaleResult.Accepted)
                        ?.let { PageScale(it.pointsPerFoot) }
                    scaleValue != null && annotationReducer.setSubDrawingScale(pageIndex, sub, scaleValue).changed
                } else sourcePageSize?.let { onScaleDefined(distance, feet, it) } ?: false
            },
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

/** Deletes one item through its owner; true when it was actually removed. */
    fun deleteMarkupItem(item: PageItem): Boolean {
        val subId = activeSubDrawing?.id
        if (subId != null) {
            return item !is PageItem.SubDrawingItem && annotationReducer.delete(markupTarget(), item).changed
        }
        if (item is PageItem.SubDrawingItem) {
            // Deleting the selected crop takes its badge with it and ends the
            // selection, so Undo never restores a badge with nothing selected.
            onDeleteSubDrawing(item.data.id)
            if (selectedSubDrawingId.value == item.data.id) {
                selectedSubDrawingId.value = null
                subDrawingBadgeDraft = null
            }
            return subDrawings.none { it.id == item.data.id }
        }
        val before = PdfSelectionKey.from(item)
        onDeleteItem(item)
        return before.resolve(currentMeasurements, currentNotes, currentPaths, currentPhotoPins, currentShapes, emptyList()) == null
    }

    // Delete happens at once, with a "Deleted - Undo" snackbar that takes it back
    // through the same history as the Undo button. Sheets and photos alike.
    val viewerSnackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.item_deleted)
    val undoLabel = stringResource(R.string.toolbar_undo)
    fun showDeletedWithUndo() {
        snackbarScope.launch {
            viewerSnackbar.currentSnackbarData?.dismiss()
            val result = viewerSnackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                val undo = onUndoAnnotation
                if (undo != null) undo(pageIndex)
                else if (annotationReducer.acceptsCurrentSession()) annotationReducer.undo(pageIndex)
            }
        }
    }
    fun deleteSelected(item: PageItem) {
        val removed = deleteMarkupItem(item)
        clearPageSelection()
        showItemPicker = false
        if (removed) showDeletedWithUndo()
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
                         outcome = addNoteMarkup(newNote)
                          if (outcome == AnnotationReducer.Result.Accepted) {
                              onAnnotationAdded()
                              // Back in Pan with the new note selected, ready to drag or pinch.
                              applyPageSelection(PageItem.NoteItem(newNote))
                          }
                     } else {
                         val old = editingNote!!.copyNote()
                         val replacement = editingNote!!.copy(
                             text = noteInput,
                             isBold = noteIsBold
                         )
                         outcome = updateNoteMarkup(old, replacement)
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
                         outcome = addImageMarkup(selectedPhotoPin!!, currentImageFileName!!, PageItem.NoteItem(newImageNote))
                          if (outcome == AnnotationReducer.Result.Accepted) {
                              onAnnotationAdded()
                              selectedImageNote = newImageNote
                             SafeDiagnostics.debug(DiagnosticEvent.ANNOTATION_ACTIVITY)
                         }
                     } else if (editingImageNote != null) {
                         val old = editingImageNote!!.copyImageNote()
                         val replacement = editingImageNote!!.copy(
                             text = imageNoteInput,
                             isBold = imageNoteIsBold
                         )
                          if (selectedPhotoPin != null && currentImageFileName != null) {
                              outcome = updateImageMarkup(
                                selectedPhotoPin!!, currentImageFileName!!, PageItem.NoteItem(old), PageItem.NoteItem(replacement)
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
        clearPageSelection()
        overlappingItems = emptyList()
        showItemPicker = false

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
    // A sub-drawing is a full-screen surface too: the rail drops the
    // Sub-drawing tool (no nesting) and the page chrome follows the photo viewer.
    // The host also learns which crop is open (null when none): its Clear confirm
    // routes to that crop's own markups, never the sheet's.
    LaunchedEffect(activeSubDrawingId.value, activeSubDrawing?.id) {
        if (isSubDrawingView) pdfFling.stop()
        onSubDrawingViewActive(activeSubDrawing?.id)
        // The photo tool stays available inside a sub-drawing: only an open
        // photo viewer hides it.
        onFullScreenModeChanged(fullScreenImageFile != null)
    }
    LaunchedEffect(subDrawingExitSignal) {
        // The host's own Back affordance (top-left arrow): leave the open crop and
        // stay on its page. It runs without a pointer gesture, so it goes directly
        // to the viewer's exit state - the same transition phone Back uses.
        if (subDrawingExitSignal > 0 && activeSubDrawingId.value != null) {
            pdfFling.stop()
            activeSubDrawingId.value = null
        }
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

        if (isSubDrawingView) {
        // Back returns from a sub-drawing to its full page instead of leaving the viewer.
        BackHandler {
            interactionController.onBack(fullscreen = true) { activeSubDrawingId.value = null }
        }
    }
    // Back ends a selection (an item, a badge, the overlap picker or selected text)
    // before it leaves the sub-drawing or the sheet. Declared after the handler
    // above so it is asked first.
    val hasViewerSelection = selectedKey != null || selectedSubDrawingId.value != null || showItemPicker || isTextSelecting
    BackHandler(enabled = hasViewerSelection) {
        clearPageSelection()
        showItemPicker = false
        overlappingItems = emptyList()
        isTextSelecting = false; showCopyButton = false; selectedOcrBoxes = emptyList()
        textSelectionStartIdx = -1; textSelectionEndIdx = -1
    }

    // Naming a new sub-drawing: Save keeps the crop under the typed room name,
    // Cancel throws the crop away through the abandon command, so Undo never
    // brings a discarded crop back. Keyboard Done saves the name.
    val keyboardController = LocalSoftwareKeyboardController.current
    val namingSub = subDrawings.firstOrNull { it.id == pendingNameSubDrawingId.value }
    if (namingSub != null) {
        com.example.myapplication.ui.SubDrawingOverlay.NameNewCropDialog(
            input = subDrawingNameInput,
            onInputChange = { subDrawingNameInput = it },
            onSave = {
                keyboardController?.hide()
                onRenameSubDrawing(namingSub.id, currentSubDrawingNameInput.trim().takeIf { it.isNotEmpty() })
                pendingNameSubDrawingId.value = null
            },
            onCancel = {
                keyboardController?.hide()
                onAbandonSubDrawing(namingSub.id)
                pendingNameSubDrawingId.value = null
            }
        )
    }

    // Renaming the sub-drawing currently open. Cancel keeps the existing name -
    // nothing here discards the crop, and the hint says so. Keyboard Done saves.
    if (showRenameDialog) {
        val renamingSub = renameSubId?.let { id -> subDrawings.firstOrNull { it.id == id } } ?: activeSubDrawing
        com.example.myapplication.ui.SubDrawingOverlay.RenameCropDialog(
            input = subDrawingNameInput,
            onInputChange = { subDrawingNameInput = it },
            onSave = {
                keyboardController?.hide()
                renamingSub?.let {
                    onRenameSubDrawing(it.id, currentSubDrawingNameInput.trim().takeIf { name -> name.isNotEmpty() })
                }
                showRenameDialog = false
                renameSubId = null
            },
            onCancel = {
                keyboardController?.hide()
                showRenameDialog = false
                renameSubId = null
            }
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
        LaunchedEffect(renderViewport.width, renderViewport.height) {
            subDrawingViewport.value = renderViewport
        }
        LaunchedEffect(
            uri,
            sessionToken,
            pageIndex,
            activeSubDrawingId.value,
            renderViewport.width,
            renderViewport.height
        ) {
            // A sub-drawing is rendered from its own crop plan; the page bitmap
            // is not sampled for it.
            if (activeSubDrawingId.value != null) return@LaunchedEffect
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
        val displayBitmapOwner = if (isSubDrawingView) cropBitmapOwner else bitmapOwner
        displayBitmapOwner?.value?.let { b ->
            val bW = b.width.toFloat(); val bH = b.height.toFloat()
            fun viewerTransform() = ViewerTransform(bW, bH, w, h, scale, offsetX, offsetY)
            val vW = bW * viewerTransform().fittedScale
            val vH = bH * viewerTransform().fittedScale
            Box(
                modifier = Modifier.fillMaxSize()
                    .testTag(PDF_READY_CANVAS_TAG)
                    .pointerInput(sessionToken, pageIndex, mode, toolSettings, w, h, activeSubDrawingId.value, isSubDrawingView) {
                        awaitEachGesture {
                            try {
                            fun screenToPage(ptX: Float, ptY: Float): Point =
                                viewerTransform().toNormalized(ptX, ptY)
                            fun sourceDistance(p1: Point, p2: Point): Float? {
                                val source = currentSourceSurfaceSize ?: return null
                                val dx = ((p1.x - p2.x) * source.width).toDouble()
                                val dy = ((p1.y - p2.y) * source.height).toDouble()
                                return sqrt(dx * dx + dy * dy)
                                    .takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
                                    ?.toFloat()
                            }
                            // The crop a page point lands on, using the same geometry the
                            // canvas paints: a badge's own radius, or the crop's area when
                            // the page is showing rectangles. A long-press therefore picks
                            // up exactly the marker the user is holding.
                            fun subDrawingBadgeAt(pt: Point, pageWidthPx: Float, compositeScale: Float): SubDrawing? =
                                SubDrawingPageMarkers.badgeAt(
                                    subDrawings = currentSubDrawings,
                                    pagePoint = pt,
                                    pageWidthPx = pageWidthPx,
                                    compositeScale = compositeScale,
                                    showRectangles = showSubDrawings
                                )
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
                             var resizingSubDrawingBadge = false
                             var originalSubDrawingBadgeRatio: Float? = null
                            val startPt = screenToPage(down.position.x, down.position.y)
                            // Whole-item move: the original of the selected item the finger
                            // landed on, and where the finger was last seen.
                            var movingItem: PageItem? = null
                            var lastPointerPos = down.position
                            // Long-press text selection works in every tool, as long as one
                            // finger stays within touch slop until the timeout.
                            var maxPointerTravel = 0f
                            var multiTouch = false
                            val calibrationIsComplete = mode == ToolMode.SCALE &&
                                pointSelection.firstPoint != null && pointSelection.secondPoint != null
                            val annotationStartAdmitted = mode == ToolMode.PAN || calibrationIsComplete ||
                                gestureTransform.toNormalizedIfInsidePage(down.position.x, down.position.y) != null

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

                            // Sub-drawing creation: the Sub-drawing tool drags a rectangle
                            // over the page and publishes that crop as a new sub-drawing.
                            var subDrawingDragActive = mode == ToolMode.SUB_DRAWING && !isSubDrawingView
                            var subDrawingDragEnd = startPt
                            if (subDrawingDragActive) subDrawingDraft = SubDrawingPageMarkers.dragRect(startPt, subDrawingDragEnd)

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
                                    // Every vertex drags: both ends and each polyline corner.
                                    // draggingPointIdx is an index into the measurement's vertices.
                                    val m = selectedMeasurement!!
                                    val vertex = m.vertices.indexOfFirst { gestureTransform.hits(it, down.position.x, down.position.y, 80f) }
                                    if (vertex >= 0) {
                                        draggingPointIdx = vertex
                                        isItemDragging = true
                                        originalMeasurement = m.copyMeasurement(p1 = m.p1.copyPoint(), p2 = m.p2.copyPoint())
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
                                // A selected measurement, pen line, photo pin or badge under the
                                // finger follows a drag and moves as one piece.
                                if (draggingPointIdx == -1 && draggingNoteIdx == -1 && !draggingShape && draggingSelectionHandle == null) {
                                    val fingerPx = Point(startPt.x * bW, startPt.y * bH)
                                    val reach = 60f / pointerCompositeScale
                                    fun nearLine(points: List<Point>, extra: Float): Boolean = points.zipWithNext().any { (a, b) ->
                                        distToSegment(fingerPx, Point(a.x * bW, a.y * bH), Point(b.x * bW, b.y * bH)) < reach + extra
                                    }
                                    val selected = selectedItem
                                    movingItem = when {
                                        selected is PageItem.Measure && nearLine(selected.data.vertices, 0f) -> selected
                                        selected is PageItem.Path && nearLine(selected.data.points,
                                            selected.data.strokeWidthRatio * maxOf(bW, bH) / 2f) -> selected
                                        selected is PageItem.PhotoPinItem && run {
                                            val dx = (startPt.x - selected.data.x) * bW
                                            val dy = (startPt.y - selected.data.y) * bH
                                            sqrt(dx * dx + dy * dy) < 100f / pointerCompositeScale
                                        } -> selected
                                        else -> selectedSubDrawingId.value?.takeIf { !isSubDrawingView && !showSubDrawings }
                                            ?.let { id -> subDrawingBadgeAt(startPt, bW, pointerCompositeScale)?.takeIf { it.id == id } }
                                            ?.let { PageItem.SubDrawingItem(it) }
                                    }
                                    if (movingItem != null) isItemDragging = true
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
                                 val waitingForLongPress = !longPressTriggered && longPressEligible &&
                                     !isItemDragging && !isTextSelecting && !multiTouch && calibratePointIdx == -1 &&
                                     maxPointerTravel < viewConfiguration.touchSlop &&
                                     (if (mode == ToolMode.PAN) momentumEligible && totalPan.getDistance() < viewConfiguration.touchSlop
                                      else annotationStartAdmitted)
                                 val nextEvent = if (waitingForLongPress) {
                                     withTimeoutOrNull((longPressTimeout - (lastPointerTime - startTime)).coerceAtLeast(1L)) {
                                         awaitPointerEvent()
                                     }
                                 } else awaitPointerEvent()
                                 val event = if (nextEvent == null) {
                                     // The finger is holding still past the long-press timeout:
                                     // that selects text, in every tool, and never selects an item.
                                     beginTextSelection()
                                     if (textSelectingActive || ocrSelectionPending) {
                                         // In a drawing tool the held finger was text selection,
                                         // not the start of a stroke or a crop: drop both.
                                         currentStroke.clear()
                                         subDrawingDragActive = false
                                         subDrawingDraft = null
                                     }
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
                                 if (pointers.size > 1) multiTouch = true
                                 pointers.firstOrNull()?.let {
                                     maxPointerTravel = maxOf(maxPointerTravel, (it.position - down.position).getDistance())
                                     lastPointerPos = it.position
                                 }
                                 if (pointers.size == 1) velocity.addPosition(pointers[0].uptimeMillis, pointers[0].position)
                                 val centroid = event.calculateCentroid()
                                 // Gutter starts may still become navigation pinch gestures;
                                 // they cannot start a one-pointer annotation or calibration.
                                 if (!annotationStartAdmitted && pointers.size < 2) {
                                     pointers.forEach { it.consume() }
                                     continue
                                 }

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
                                         val draftM = MarkupMove.measurementVertex(baseMeasurement, draggingPointIdx, currentPt) ?: baseMeasurement
                                         val updatedM = if (subScaleForMeasure != null) {
                                             sourceSurfaceSize?.let { measurementSourceLength(draftM.vertices, it.width, it.height) }?.let { distance ->
                                                 com.example.myapplication.stage8.formatSourceDistance(distance, subScaleForMeasure!!.pointsPerFoot)
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
                                } else if (movingItem != null && pointers.size == 1 && !multiTouch) {
                                    // A selected item follows the finger once it passes touch slop,
                                    // so a tap on it still counts as a tap (and a double-tap opens).
                                    val change = pointers[0]
                                    if (dragActive || (change.position - down.position).getDistance() >= viewConfiguration.touchSlop) {
                                        val currentPt = screenToPage(change.position.x, change.position.y)
                                        val dx = currentPt.x - startPt.x
                                        val dy = currentPt.y - startPt.y
                                        when (val original = movingItem) {
                                            is PageItem.Path -> pathDraft = MarkupMove.path(original.data, dx, dy)
                                            is PageItem.Measure -> measurementDraft = MarkupMove.measurement(original.data, dx, dy)
                                            is PageItem.PhotoPinItem -> pinDraft = MarkupMove.pin(original.data, dx, dy)
                                            is PageItem.SubDrawingItem -> badgeCenterDraft =
                                                MarkupMove.point(SubDrawingGeometry.badgeCenter(original.data), dx, dy)
                                            else -> Unit
                                        }
                                        dragActive = true
                                    }
                                    change.consume()
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
                                } else if (subDrawingDragActive && pointers.size == 1) {
                                    val change = pointers[0]
                                    subDrawingDragEnd = screenToPage(change.position.x, change.position.y)
                                    subDrawingDraft = SubDrawingPageMarkers.dragRect(startPt, subDrawingDragEnd)
                                    dragActive = true
                                    longPressEligible = false
                                    change.consume()
                                } else if (pointers.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    val rotation = event.calculateRotation()
                                    // A selected sub-drawing badge owns the pinch: only that
                                    // badge grows or shrinks, and the page does not zoom.
                                    val pinchedBadge = selectedSubDrawingId.value?.takeIf { !isSubDrawingView && !showSubDrawings }
                                        ?.let { id -> currentSubDrawings.firstOrNull { it.id == id } }
                                    if (pinchedBadge != null) {
                                        if (subDrawingBadgeDraft == null) subDrawingBadgeDraft =
                                            pinchedBadge.badgeRatio ?: SubDrawingGeometry.defaultBadgeRatio(pinchedBadge)
                                        if (originalSubDrawingBadgeRatio == null) originalSubDrawingBadgeRatio = subDrawingBadgeDraft
                                        val next = SubDrawingGeometry.badgeRatioOrDefault(subDrawingBadgeDraft!! * zoom)
                                        if (next != subDrawingBadgeDraft) resizingSubDrawingBadge = true
                                        subDrawingBadgeDraft = next
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else if (selectedShape != null) {
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
                                        // Page navigation is not a creation-tool tap on release.
                                        dragActive = true
                                    }
                                } else if (pointers.size == 1 && (mode == ToolMode.PAN || textSelectingActive || ocrSelectionPending ||
                                        (mode == ToolMode.SCALE && pointSelection.firstPoint != null && pointSelection.secondPoint != null))) {
                                    val change = pointers[0]
                                    val currentPos = change.position
                                    val elapsed = event.changes.firstOrNull()?.uptimeMillis?.minus(startTime) ?: 0L

                                    // Debug logging
                                    if (mode == ToolMode.PAN && !longPressTriggered && longPressEligible && !isItemDragging) {
                                        SafeDiagnostics.debug(DiagnosticEvent.OPERATION_STARTED)
                                    }

                                    // A held finger selects text (events delivered in a batch can
                                    // pass the timeout without the timer above firing).
                                    if (!longPressTriggered && longPressEligible && mode == ToolMode.PAN && elapsed >= longPressTimeout && totalPan.getDistance() < viewConfiguration.touchSlop && !isItemDragging && momentumEligible) {
                                        longPressTriggered = true
                                        beginTextSelection()
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
                                } else if (movingItem != null && dragActive) {
                                    // One MOVE transaction for the whole item. The selection is
                                    // keyed by kind and id, so it follows the moved item.
                                    when (val original = movingItem) {
                                        is PageItem.Path -> pathDraft?.let {
                                            annotationReducer.update(markupTarget(), original, PageItem.Path(it), AnnotationReducer.Kind.MOVE) }
                                        is PageItem.Measure -> measurementDraft?.let {
                                            annotationReducer.update(markupTarget(), original, PageItem.Measure(it), AnnotationReducer.Kind.MOVE) }
                                        is PageItem.PhotoPinItem -> pinDraft?.let { updatePinMarkup(original.data, it, AnnotationReducer.Kind.MOVE) }
                                        is PageItem.SubDrawingItem -> badgeCenterDraft?.let {
                                            annotationReducer.moveSubDrawingBadge(pageIndex, original.data.id, it) }
                                        else -> null
                                    }
                                    pathDraft = null; pinDraft = null; badgeCenterDraft = null; measurementDraft = null
                                    isItemDragging = false
                                    selectionToolbarPos = Offset(lastPointerPos.x, lastPointerPos.y)
                                } else if (draggingPointIdx != -1) {
                                    if (originalMeasurement != null && selectedMeasurement != null) {
                                         updateMeasurementPoint(
                                             selectedMeasurementIndex,
                                             selectedMeasurement!!.copyMeasurement(
                                                 p1 = selectedMeasurement!!.p1.copyPoint(),
                                                 p2 = selectedMeasurement!!.p2.copyPoint()
                                             ),
                                             originalMeasurement!!
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
                                        selectionToolbarPos = Offset((p1s.x + p2s.x) / 2, (p1s.y + p2s.y) / 2)
                                    }
                                } else if (draggingNoteIdx != -1) {
                                    if (originalNote != null && selectedNote != null) {
                                        val updated = selectedNote!!.copyNote()
                                         updateNoteMarkup(originalNote!!, updated)
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
                                         selectionToolbarPos = Offset(imgLeft + n.x * bW * compScale, imgTop + n.y * bH * compScale)
                                    }

                            } else if (resizingSubDrawingBadge && selectedSubDrawingId.value != null &&
                                originalSubDrawingBadgeRatio != null && subDrawingBadgeDraft != null) {
                                // One undoable transaction: this badge's size only, so Undo
                                // restores the previous radius and Redo re-applies the new one.
                                annotationReducer.resizeSubDrawingBadge(
                                    pageIndex,
                                    selectedSubDrawingId.value!!,
                                    subDrawingBadgeDraft!!,
                                    AnnotationReducer.Kind.RESIZE
                                )
                            } else if (resizingSubDrawingBadge) {
                                // A pinch that never changed the size records nothing, so
                                // Undo stays on the user's previous step.
                            }
                            if (resizingSubDrawingBadge) {
                                resizingSubDrawingBadge = false
                                originalSubDrawingBadgeRatio = null
                                subDrawingBadgeDraft = null
                                isItemDragging = false
                            }
                            if (longPressTriggered && !resizingSubDrawingBadge &&
                                selectedSubDrawingId.value != null) {
                                // A long-press that only selected a badge: the badge stays
                                // selected so its toolbar shows and the next pinch resizes it.
                                isItemDragging = false
                            }
                            if ((draggingShape || rotatingShape || resizingShape) && selectedShape != null) {
                                // A shape gesture owns the release: its own resize/move/rotate
                                // is published below, and any badge selection ends with it.
                                selectedSubDrawingId.value = null
                                subDrawingBadgeDraft = null
                                if (originalShape != null) {
                                    val updated = selectedShape!!.copyShape()
                                     val kind = when {
                                         rotatingShape -> AnnotationReducer.Kind.ROTATE
                                         resizingShape -> AnnotationReducer.Kind.RESIZE
                                         draggingShape -> AnnotationReducer.Kind.MOVE
                                         else -> AnnotationReducer.Kind.UPDATE
                                     }
                                     updateShapeMarkup(originalShape!!, updated, kind)
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
                                     selectionToolbarPos = Offset(imgLeft + s.x * bW * compScale, imgTop + s.y * bH * compScale)
                                }
                            } else if (calibratePointIdx != -1) {
                                calibratePointIdx = -1
                            } else if (dragActive && subDrawingDragActive) {
                                val crop = SubDrawingGeometry.cropForDrag(startPt, subDrawingDragEnd)
                                subDrawingDraft = null
                                if (crop != null) {
                                    // Remember the crop so its naming popup arms
                                    // once the host has published it. The id is made
                                    // here so the popup can recognise the crop this
                                    // drag published among identical crops on the page.
                                    val createdId = UUID.randomUUID().toString()
                                    pendingCreatedCrop.value = crop
                                    pendingCreatedCropId.value = createdId
                                    // Spawn size comes from the zoom at release: the badge
                                    // the user saw at that zoom is the badge they get.
                                    onCreateSubDrawing(crop, createdId, viewerTransform().compositeScale)
                                }
                            } else if (dragActive && currentStroke.isNotEmpty()) {
                                 val newPath = DrawnPath(
                                     currentStroke.toList(),
                                     toolSettings.style(mode).colorArgb,
                                     mode == ToolMode.HIGHLIGHTER,
                                     strokeWidthRatio = toolSettings.style(mode).width
                                 )
                                 addPathMarkup(newPath)
                                currentStroke.clear()
                            } else if (!dragActive && subDrawingDragActive) {
                                // A Sub-drawing tap with no drag creates nothing; the
                                // creation affordance is the dragged rectangle.
                            } else if (!dragActive && !longPressTriggered && annotationStartAdmitted && mode == ToolMode.PAN) {
                                // A quick tap. A long-press that selected a badge is not a tap:
                                // the badge stays selected (cyan, with its toolbar) and nothing
                                // opens, so the next pinch can resize it.
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
                                // Page-level sub-drawings join the same overlap picker as
                                // every other stacked item, answered by the same geometry the
                                // canvas paints: shown crops answer to a tap anywhere inside
                                // them, in badge-only mode only the badge itself answers.
                                if (!isSubDrawingView) {
                                    SubDrawingPageMarkers.hitItems(
                                        subDrawings = currentSubDrawings,
                                        pagePoint = tapPt,
                                        pageWidthPx = bW,
                                        pageHeightPx = bH,
                                        compositeScale = pointerCompositeScale,
                                        showRectangles = showSubDrawings
                                    ).forEach { sub -> foundItems.add(PageItem.SubDrawingItem(sub)) }
                                }

                                // Store tap position for toolbar
                                selectionToolbarPos = Offset(down.position.x, down.position.y)

                                if (foundItems.size > 1) {
                                    // Multiple items overlap - show picker toolbar
                                    lastTapKey = null
                                    overlappingItems = foundItems
                                    showItemPicker = true
                                    clearPageSelection()
                                } else if (foundItems.size == 1) {
                                    // A tap selects the item. A second quick tap on the same
                                    // badge opens its sub-drawing, on the same pin its photos.
                                    val found = foundItems.first()
                                    showItemPicker = false
                                    overlappingItems = emptyList()
                                    val tapKey = PdfSelectionKey.from(found)
                                    val isDoubleTap = tapKey == lastTapKey &&
                                        down.uptimeMillis - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis
                                    lastTapKey = if (isDoubleTap) null else tapKey
                                    lastTapTime = lastPointerTime
                                    applyPageSelection(found)
                                    if (found is PageItem.SubDrawingItem) {
                                        val badgeScreen = pageToScreen(SubDrawingGeometry.badgeCenter(found.data))
                                        selectionToolbarPos = Offset(badgeScreen.x, badgeScreen.y)
                                    }
                                    if (isDoubleTap) when (found) {
                                        is PageItem.SubDrawingItem -> { clearPageSelection(); openSubDrawing(found.data.id) }
                                        is PageItem.PhotoPinItem -> showPinImageGallery = true
                                        else -> Unit
                                    }
                                } else {
                                    lastTapKey = null
                                    // No items found - clear selection (including text selection)
                                    clearPageSelection()
                                    showItemPicker = false
                                    overlappingItems = emptyList()
                                    // Clear text selection too
                                    if (isTextSelecting) {
                                        isTextSelecting = false
                                        showCopyButton = false
                                        selectedOcrBoxes = emptyList()
                                        textSelectionStartIdx = -1
                                        textSelectionEndIdx = -1
                                    }
                                }
                            } else if (longPressTriggered) {
                                // A long press selects text in every tool; it never places a
                                // note, pin, shape or measuring point.
                            } else if (!dragActive && annotationStartAdmitted && mode == ToolMode.NOTE) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                selectionToolbarPos = down.position
                                notePos = tapPt
                                noteInput = ""
                                showNoteDialog = true
                            } else if (!dragActive && annotationStartAdmitted && mode == ToolMode.PHOTO) {
                                 val tapPt = screenToPage(down.position.x, down.position.y)
                                 val newPin = PhotoPin(tapPt.x, tapPt.y)
                                 if (addPhotoMarkup(newPin).changed) {
                                     onAnnotationAdded()
                                 }
                            } else if (!dragActive && annotationStartAdmitted && mode == ToolMode.SHAPE) {
                                val tapPt = screenToPage(down.position.x, down.position.y)
                                 val style = toolSettings.style(ToolMode.SHAPE)
                                val newShape = Shape(tapPt.x, tapPt.y, 0f, style.shape, style.colorArgb,
                                    style.filled, style.width, .1f, .08f)
                                if (addShapeMarkup(newShape).changed) {
                                    onAnnotationAdded()
                                    // Back in Pan with the new shape selected, ready to drag or pinch.
                                    applyPageSelection(PageItem.ShapeItem(newShape))
                                    selectionToolbarPos = down.position
                                }
                            } else if (!dragActive && annotationStartAdmitted && mode == ToolMode.POLYLINE) {
                                if (subScaleForMeasure == null) Toast.makeText(context, R.string.measure_calibrate_first, Toast.LENGTH_SHORT).show()
                                else if (polylinePoints.size < com.example.myapplication.stage5.Stage5Limits.MAX_PATH_POINTS) polylinePoints.add(screenToPage(down.position.x, down.position.y))
                            } else if (!dragActive && annotationStartAdmitted && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                                val pt = screenToPage(down.position.x, down.position.y)
                                if (pointSelection.firstPoint == null) pointSelection.firstPoint = pt else if (pointSelection.secondPoint == null) {
                                    pointSelection.secondPoint = pt
                                    if (mode == ToolMode.MEASURE) {
                                 if (subScaleForMeasure != null) {
                                             sourceDistance(pointSelection.firstPoint!!, pointSelection.secondPoint!!)?.let { distance ->
                                                 val text = com.example.myapplication.stage8.formatSourceDistance(distance, subScaleForMeasure!!.pointsPerFoot)
                                                 if (text == null) {
                                                     Toast.makeText(context, R.string.measurement_out_of_range, Toast.LENGTH_SHORT).show()
                                                     return@let
                                                 }
                                                 val newM = Measurement(pointSelection.firstPoint!!, pointSelection.secondPoint!!, text, colorArgb = toolSettings.style(mode).colorArgb, strokeWidthRatio = toolSettings.style(mode).width)
                                                 if (addMeasurementMarkup(newM).changed) {
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
                                subDrawingDraft = null
                                pathDraft = null
                                pinDraft = null
                                badgeCenterDraft = null
                                subDrawingBadgeDraft = null
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
                    val selectedPathId = (selectedItem as? PageItem.Path)?.data?.id
                    currentPaths.forEach { storedPath ->
                        val pathData = pathDraft?.takeIf { it.id == storedPath.id } ?: storedPath
                        if (pathData.points.size > 1) {
                            val path = Path(); path.moveTo(toS(pathData.points[0]).x, toS(pathData.points[0]).y)
                            for (i in 1 until pathData.points.size) { val p = toS(pathData.points[i]); path.lineTo(p.x, p.y) }
                             val strokePx = (pathData.strokeWidthRatio * maxOf(bW, bH) * compositeScale).coerceAtLeast(1f)
                             // A selected pen or highlighter line gets a cyan halo under it.
                             if (pathData.id == selectedPathId) drawPath(path, viewerColors.selection, 0.7f,
                                 style = Stroke(strokePx + 12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
                        val color = if (isSelectedMeasurement) viewerColors.selection else Color(m.colorArgb)
                        m.vertices.zipWithNext().forEach { (a, b) -> drawLine(color, toS(a), toS(b), strokeWidth = m.strokeWidthRatio * maxOf(bW, bH) * compositeScale) }
                        drawCircle(color, 6f, p1); drawCircle(color, 6f, p2)
                        if (isSelectedMeasurement) {
                            val boxSize = 40f
                            val dashedStroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                            // A handle on every vertex: each one drags.
                            m.vertices.forEach { v ->
                                drawRect(color = viewerColors.selection, topLeft = toS(v) - Offset(boxSize/2, boxSize/2), size = Size(boxSize, boxSize), style = dashedStroke)
                            }
                        }
                        val mid = Offset((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
                        val mMatches = searchTerm.isNotBlank() && m.text.contains(searchTerm, ignoreCase = true)
                        val bgColor = if (mMatches) viewerColors.searchHit else Color.Black.copy(alpha = 0.7f)
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
                                drawRect(viewerColors.searchHit, topLeft - Offset(8f, 4f), Size(box.width + 16f, box.height + 8f))
                            if (selected) drawRect(viewerColors.selection, topLeft - Offset(8f, 4f),
                                Size(box.width + 16f, box.height + 8f),
                                style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)))
                        }
                        AnnotationCanvasRendering.drawNote(drawContext.canvas.nativeCanvas, n,
                            origin.x, origin.y, surfaceWidth, surfaceHeight,
                            if (selected) viewerColors.selection.toArgb() else n.colorArgb)
                    }

                    // Draw photo pins as camera icons
                    // Calculate global pin index across all pages
                    val allPinsSorted = allPagePhotoPins.keys.sorted().flatMap { pageIdx ->
                        allPagePhotoPins[pageIdx]?.map { it to pageIdx } ?: emptyList()
                    }

                    currentPhotoPins.forEach { storedPin ->
                        val pin = pinDraft?.takeIf { it.id == storedPin.id } ?: storedPin
                        val p = toS(Point(pin.x, pin.y))
                        val isSelected = pin.id == selectedPhotoPin?.id
                        val pinRadius = 20f * scale
                        // Draw pin background circle
                        drawCircle(
                            color = if (isSelected) viewerColors.selection else viewerColors.photoPin,
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
                        drawCircle(color = viewerColors.photoPinBadge, radius = 10f * scale, center = badgeCenter)
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
                        val shapeColor = if (isSelected) viewerColors.selection else Color(shape.colorArgb)

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
                                    color = viewerColors.selection,
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
                                        color = viewerColors.selection,
                                        topLeft = corner - Offset(handleSize / 2, handleSize / 2),
                                        size = Size(handleSize, handleSize),
                                        style = Stroke(width = 2f)
                                    )
                                }
                            }
                        }
                    }

                    // The text-selection highlight is NOT painted here. On a real sheet the selected
                    // words sit in the title block, which is dense vector line work: painting the
                    // selection at this point put it under every later layer - search highlights,
                    // strokes, sub-drawing rectangles and badges - and the user saw the Copy popup
                    // with nothing highlighted under it. It is painted last, at the end of the canvas.

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
                        // toS takes page fractions (0..1), not bitmap pixels: passing pixels drew every
                        // search highlight far off the sheet, so matches were never visible.
                        val tl = toS(Point(leftPx / bW, topPx / bH))
                        val br = toS(Point(rightPx / bW, bottomPx / bH))
                        val rectTopLeft = Offset(minOf(tl.x, br.x), minOf(tl.y, br.y))
                        val rectSize = Size(kotlin.math.abs(br.x - tl.x), kotlin.math.abs(br.y - tl.y))
                        drawRect(color = viewerColors.textHit.copy(alpha = 0.6f), topLeft = rectTopLeft, size = rectSize)
                        if (hr == focusRect) drawRect(viewerColors.selection, rectTopLeft - Offset(4f, 4f),
                            Size(rectSize.width + 8f, rectSize.height + 8f), style = Stroke(3f))
                    }
                    if (currentStroke.size > 1) {
                        val path = Path(); path.moveTo(toS(currentStroke[0]).x, toS(currentStroke[0]).y)
                        for (i in 1 until currentStroke.size) { val p = toS(currentStroke[i]); path.lineTo(p.x, p.y) }
                        drawPath(path, Color(toolSettings.style(mode).colorArgb), if(mode == ToolMode.HIGHLIGHTER) 0.4f else 1f, style = Stroke(toolSettings.style(mode).width * maxOf(bW, bH) * compositeScale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    polylinePoints.zipWithNext().forEach { (a, b) -> drawLine(Color(toolSettings.style(mode).colorArgb), toS(a), toS(b), 3f) }
                    polylinePoints.forEach { drawCircle(Color(toolSettings.style(mode).colorArgb), 5f, toS(it)) }
                    if (pointSelection.firstPoint != null && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                        val p1 = toS(pointSelection.firstPoint!!); drawCircle(viewerColors.pointPick, 8f, p1)
                        pointSelection.secondPoint?.let { val p2 = toS(it); drawCircle(viewerColors.pointPick, 8f, p2); drawLine(viewerColors.pointPick, p1, p2, 4f) }
                    }

                    // Sub-drawings on this page: one marker set at a time - the crop
                    // rectangles in each crop's palette colour, or its round badge. Painting,
                    // sizes, and colours are one owner, SubDrawingPageMarkers, which is also
                    // what the pointer gestures hit-test.
                    if (!isSubDrawingView && currentSubDrawings.isNotEmpty()) {
                        val selectedMarkerId = selectedSubDrawingId.value
                        if (showSubDrawings) {
                            with(SubDrawingPageMarkers) {
                                drawCropRectangles(currentSubDrawings, { p -> toS(p) }, selectedMarkerId)
                            }
                        } else {
                            with(SubDrawingPageMarkers) {
                                drawBadges(
                                    subDrawings = currentSubDrawings,
                                    toScreen = { p -> toS(p) },
                                    pageWidthPx = bW,
                                    compositeScale = compositeScale,
                                    sheetLabel = badgeSheetLabel,
                                    // Live pinch drafts paint the size being dragged; the stored
                                    // radius is what every other badge and pointer-up publishes.
                                    ratioOf = { sub ->
                                        if (sub.id == selectedMarkerId) subDrawingBadgeDraft ?: sub.badgeRatio
                                        else sub.badgeRatio
                                    },
                                    selectedId = selectedMarkerId,
                                    // A badge being dragged paints where the finger has it.
                                    centerOf = { sub ->
                                        if (sub.id == selectedMarkerId) badgeCenterDraft ?: SubDrawingGeometry.badgeCenter(sub)
                                        else SubDrawingGeometry.badgeCenter(sub)
                                    }
                                )
                            }
                        }
                    }
                    // The live creation preview is drawn on its own, outside the marker block
                    // above: a page with no sub-drawings yet has no markers, and Dave's first
                    // drag there showed no box at all - the dashed rectangle must follow the
                    // finger from the first movement on every page.
                    if (!isSubDrawingView) {
                        currentSubDrawingDraft?.let { rect ->
                            with(SubDrawingPageMarkers) { drawCreationDraft(rect, { p -> toS(p) }) }
                        }
                    }

                    // Draw text selection last (blue, like web selection). The Copy popup proves the
                    // words are selected; the highlight must be visible under it, so nothing may be
                    // painted over it - search highlights, strokes and sub-drawing markers all come
                    // first. The page's own OCR boxes are page-space rectangles: inside an open
                    // sub-drawing the canvas paints the crop bitmap, so they are not painted there at
                    // all - see the LaunchedEffect(isSubDrawingView) that ends the selection on
                    // entering a crop.
                    if (selectedOcrBoxes.isNotEmpty() && !isSubDrawingView) {
                        with(TextSelectionRendering) {
                            drawSelectionHighlights(selectedOcrBoxes, bW, bH) { p -> toS(p) }
                            val page = cachedPageOcr
                            if (page != null) {
                                drawSelectionHandles(
                                    page = page,
                                    startIdx = textSelectionStartIdx,
                                    endIdx = textSelectionEndIdx,
                                    bitmapWidthPx = bW,
                                    bitmapHeightPx = bH,
                                    compositeScale = compositeScale,
                                    toScreen = { p -> toS(p) }
                                )
                            }
                        }
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
                    // The picker is the same toolbar: one button per stacked item, then
                    // Close. The picker and a tap select through the same owner.
                    val pickerActions = overlappingItems.map { item ->
                        ToolbarAction(annotationIcon(item), annotationLabel(item), {
                            applyPageSelection(item)
                            showItemPicker = false
                            overlappingItems = emptyList()
                        }, iconTint = (item as? PageItem.SubDrawingItem)?.let { Color(SubDrawingPalette.colorArgb(it.data.colorIndex)) })
                    } + ToolbarAction(Icons.Default.Close, stringResource(R.string.close), {
                        showItemPicker = false
                        overlappingItems = emptyList()
                    })
                    SelectionToolbar(selectionToolbarPos, pickerActions, Modifier.testTag("sotaware.pdf.overlap-picker"),
                        title = stringResource(R.string.select_item_title))
                }

                // Contextual toolbar for selected items (appears in PAN mode) and follows the item
if (selectedItem != null && mode == ToolMode.PAN && !isItemDragging) {
                    val item = selectedItem!!
                    val itemActions = buildList {
                        when (item) {
                            is PageItem.SubDrawingItem -> {
                                add(ToolbarAction(Icons.Default.PictureInPicture, stringResource(R.string.sub_drawing_open), {
                                    clearPageSelection(); openSubDrawing(item.data.id) }))
                                add(ToolbarAction(Icons.Default.DriveFileRenameOutline, stringResource(R.string.toolbar_rename), {
                                    renameSubId = item.data.id
                                    subDrawingNameInput = item.data.name.orEmpty()
                                    showRenameDialog = true
                                }))
                            }
                            is PageItem.PhotoPinItem -> {
                                add(ToolbarAction(Icons.Default.PhotoLibrary, stringResource(R.string.toolbar_photos), { showPinImageGallery = true }))
                                add(ToolbarAction(Icons.Default.AddAPhoto, stringResource(R.string.toolbar_add_photo), {
                                    onRequestCameraCapture?.invoke(pageIndex, item.data.id)
                                }, enabled = onRequestCameraCapture != null))
                            }
                            is PageItem.NoteItem -> add(ToolbarAction(Icons.Default.Edit, stringResource(R.string.edit_action), {
                                val note = item.data
                                editingNote = note
                                noteInput = note.text
                                noteIsBold = note.isBold
                                showNoteDialog = true
                            }))
                            else -> Unit
                        }
                        if (item !is PageItem.PhotoPinItem && item !is PageItem.SubDrawingItem) {
                            add(ToolbarAction(Icons.Default.Palette, stringResource(R.string.toolbar_colour), {
                                appearanceItem = item; appearanceError = false }))
                        }
                        add(ToolbarAction(Icons.Default.Delete, stringResource(R.string.annotation_delete_confirm), { deleteSelected(item) }, destructive = true))
                        add(ToolbarAction(Icons.Default.Close, stringResource(R.string.close), { clearPageSelection() }))
                    }
                    SelectionToolbar(selectionToolbarPos, itemActions, Modifier.testTag("sotaware.pdf.annotation-toolbar"))
                }
            }
        }
if (mode == ToolMode.POLYLINE && polylinePoints.isNotEmpty()) {
        SelectionToolbarCard(listOf(
            ToolbarAction(Icons.Default.Close, stringResource(R.string.clear_page_cancel), { polylinePoints.clear() }),
            ToolbarAction(Icons.Default.Check, stringResource(R.string.polyline_finish), {
                val dimensions = sourceSurfaceSize
                val measurement = dimensions?.let { buildMeasurement(polylinePoints.toList(), it.width, it.height, subScaleForMeasure, toolSettings.style(mode)) }
                if (measurement != null && addMeasurementMarkup(measurement).changed) {
                    polylinePoints.clear(); onAnnotationAdded()
                }
            }, enabled = polylinePoints.size >= 2)
        ), Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    // Search next/previous: bring the current match to the middle, zoomed in enough to read.
    LaunchedEffect(focusRect, bitmapOwner, w, h, isSubDrawingView) {
        val target = focusRect ?: return@LaunchedEffect
        val bitmap = bitmapOwner?.value ?: return@LaunchedEffect
        if (isSubDrawingView || w <= 0f || h <= 0f) return@LaunchedEffect
        val fitted = ViewerTransform(bitmap.width.toFloat(), bitmap.height.toFloat(), w, h).fittedScale
        val nx = ((target.left + target.right) / 2f).coerceIn(0f, 1f)
        val ny = ((target.top + target.bottom) / 2f).coerceIn(0f, 1f)
        val nextScale = scale.coerceIn(3f, 15f)
        val spanX = bitmap.width * fitted * nextScale
        val spanY = bitmap.height * fitted * nextScale
        scale = nextScale
        offsetX = (spanX * (0.5f - nx)).coerceIn(-spanX / 2, spanX / 2)
        offsetY = (spanY * (0.5f - ny)).coerceIn(-spanY / 2, spanY / 2)
    }
    if (onPageCodeRegionSelected != null && bitmapOwner != null) {
        if (selectingPageCode) {
            PageCodeRegionSelector(
                transform = {
                    val bitmap = checkNotNull(bitmapOwner).value
                    ViewerTransform(bitmap.width.toFloat(), bitmap.height.toFloat(), w, h, scale, offsetX, offsetY)
                },
                onViewport = { centroid, pan, zoom ->
                    val bitmap = checkNotNull(bitmapOwner).value
                    val fitted = ViewerTransform(bitmap.width.toFloat(), bitmap.height.toFloat(), w, h, scale, offsetX, offsetY).fittedScale
                    val oldScale = scale
                    val newScale = (scale * zoom).coerceIn(1f, 15f)
                    val relX = centroid.x - w / 2
                    val relY = centroid.y - h / 2
                    val limitX = (bitmap.width * fitted * newScale) / 2
                    val limitY = (bitmap.height * fitted * newScale) / 2
                    offsetX = ((offsetX - relX) * (newScale / oldScale) + relX + pan.x).coerceIn(-limitX, limitX)
                    offsetY = ((offsetY - relY) * (newScale / oldScale) + relY + pan.y).coerceIn(-limitY, limitY)
                    scale = newScale
                },
                onSelected = { region -> onPageCodeSelectionDismissed(); onPageCodeRegionSelected(region) },
                onCancel = onPageCodeSelectionDismissed
            )
        }
    }
    // Text selection copy button - floating button near selection
if (showCopyButton && selectedOcrBoxes.isNotEmpty()) {
        // Selected text uses the same toolbar: Copy and Close, next to the selection.
        fun clearTextSelection() {
            showCopyButton = false
            isTextSelecting = false
            selectedOcrBoxes = emptyList()
            textSelectionStartIdx = -1
            textSelectionEndIdx = -1
        }
        SelectionToolbar(copyButtonPos, listOf(
            ToolbarAction(Icons.Default.ContentCopy, stringResource(R.string.copy), {
                val words = com.example.myapplication.stage8.SelectedTextJoin.fromBoxes(selectedOcrBoxes)
                val selectedText = com.example.myapplication.stage8.SelectedTextJoin.join(words)
                val wordCount = com.example.myapplication.stage8.SelectedTextJoin.wordCount(words)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(context.getString(R.string.selected_text_clip_label), selectedText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, if (wordCount == 1) context.getString(R.string.copied_one_word)
                    else context.getString(R.string.copied_words, wordCount), Toast.LENGTH_SHORT).show()
                clearTextSelection()
            }),
            ToolbarAction(Icons.Default.Close, stringResource(R.string.close), { clearTextSelection() })
        ), Modifier.testTag("sotaware.pdf.copy-control"), placeAbove = false)
    }
    // "Deleted - Undo" over the sheet. The photo viewer has its own host for the
    // same state, since it covers this one.
    SnackbarHost(viewerSnackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    // The sub-drawing's own controls, painted above the page canvas so they are
    // visible and tappable: which room is open (its colour and label), Rename, and
    // Back to page. Declared last among the viewer layers for that reason; the strip
    // itself lives with the other crop screens in ui/SubDrawingOverlay.kt.
    activeSubDrawing?.let { sub ->
        com.example.myapplication.ui.SubDrawingOverlay.OpenCropStrip(
            sub = sub,
            ordinal = (activeSubDrawingOrdinal + 1).coerceAtLeast(1),
            sheetLabel = badgeSheetLabel,
            onRename = {
                subDrawingNameInput = sub.name.orEmpty()
                showRenameDialog = true
            },
            onClose = { activeSubDrawingId.value = null }
        )
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
                            if (file != null && changeImageAppearanceMarkup(pin, file, item, style) != AnnotationReducer.Result.Rejected) {
                                imageAppearance = null; imageAppearanceError = false; selectedImageExtra = null
                            } else imageAppearanceError = true
                        }, onDismiss = { imageAppearance = null; imageAppearanceError = false })
                }
                if (showImageCalibration) {
                    com.example.myapplication.ui.CalibrationDialog(imageCalibrationInput, measurementSourceLength(imagePoints, 1f, imageAspect),
                        onInputChange = { imageCalibrationInput = it },
                        onScaleDefined = { distance, feet ->
                            val pin = selectedPhotoPin; val file = fullScreenImageFile
                            pin != null && file != null && setPinImageScale(pin, file, PageScale(distance / feet), imageAspect) != AnnotationReducer.Result.Rejected
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
                                            is PageItem.SubDrawingItem -> false
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
                                                val newFontSizeRatio = AnnotationGeometry.accumulateRatio(baseFontSizeRatio, zoom, 0.005f, 0.25f) // same range as sheet notes
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
                                                annotationReducer.update(
                                                    MarkupTarget.Photo(gestureIdentity.second, pinId, file),
                                                    PageItem.NoteItem(originalImageNote!!), PageItem.NoteItem(selectedImageNote!!), noteKind
                                                )
                                            } else if (originalImageShape != null && selectedImageShape != null) {
                                                val shapeKind = when {
                                                    imageShapeRotated -> AnnotationReducer.Kind.ROTATE
                                                    imageShapeResized -> AnnotationReducer.Kind.RESIZE
                                                    imageShapeMoved -> AnnotationReducer.Kind.MOVE
                                                    else -> AnnotationReducer.Kind.UPDATE
                                                }
                                                annotationReducer.update(
                                                    MarkupTarget.Photo(gestureIdentity.second, pinId, file),
                                                    PageItem.ShapeItem(originalImageShape!!), PageItem.ShapeItem(selectedImageShape!!), shapeKind
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
                                        addImageMarkup(currentPin, currentFile,
                                            PageItem.Path(
                                                DrawnPath(imageStroke.toList(), style.colorArgb, gestureMode == "highlighter", style.width)
                                            ))
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
                                                        if (addImageMarkup(pin, file, PageItem.Measure(it)).changed) { imagePoints.clear(); onAnnotationAdded() }
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
                                                     if (addImageMarkup(selectedPhotoPin!!, fullScreenImageFile!!, PageItem.ShapeItem(newShape)).changed) {
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
                                    if (note.id == selectedImageNote?.id) note.copy(colorArgb = viewerColors.selection.toArgb()) else note
                                },
                                pin?.imageShapes?.get(file).orEmpty().map { original ->
                                    val shape = imageShapeDraft?.takeIf { it.id == original.id } ?: original
                                    if (shape.id == selectedImageShape?.id) shape.copy(colorArgb = viewerColors.selection.toArgb()) else shape
                                })
                            AnnotationCanvasRendering.drawPhotoScene(drawContext.canvas.nativeCanvas, scene,
                                imgLeft, imgTop, displayedImgWidth, displayedImgHeight)
                            if (imageStroke.isNotEmpty()) {
                                val style = toolSettings.style(mode)
                                AnnotationCanvasRendering.drawPath(drawContext.canvas.nativeCanvas,
                                    DrawnPath(imageStroke.toList(), style.colorArgb, mode == ToolMode.HIGHLIGHTER, style.width), imgLeft, imgTop, displayedImgWidth, displayedImgHeight)
                            }
                            imagePoints.zipWithNext().forEach { (a, b) -> drawLine(viewerColors.selection, imageToScreenCoords(a.x, a.y), imageToScreenCoords(b.x, b.y), 3f) }
                            imagePoints.forEach { drawCircle(viewerColors.selection, 6f, imageToScreenCoords(it.x, it.y)) }
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

                                    val shapeColor = if (shape == selectedImageShape) viewerColors.selection else Color(shape.colorArgb)

                                    rotate(degrees = shape.rotation, pivot = shapeCenter) {


                                        // Draw selection handles when selected
                                        if (shape == selectedImageShape) {
                                            val handleSize = 16f
                                            val dashedStroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                                            drawRect(
                                                color = viewerColors.selection,
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
                                                drawRect(color = viewerColors.selection, topLeft = corner - Offset(handleSize / 2, handleSize / 2), size = Size(handleSize, handleSize), style = Stroke(width = 2f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Toolbar at bottom for selected note (like regular note tool)
if (selectedImageNote != null) {
                        val note = selectedImageNote!!
                        SelectionToolbar(imageToScreenCoords(note.x, note.y), listOf(
                            ToolbarAction(Icons.Default.Edit, stringResource(R.string.edit_action), {
                                editingImageNote = note
                                currentImageFileName = fullScreenImageFile
                                imageNoteInput = note.text
                                imageNoteIsBold = note.isBold
                                showImageNoteDialog = true
                            }, contentDescription = stringResource(R.string.edit_note)),
                            ToolbarAction(Icons.Default.Palette, stringResource(R.string.toolbar_colour), {
                                selectedPhotoPin?.let { pin -> imageAppearance = pin to PageItem.NoteItem(note) }
                            }),
                            ToolbarAction(Icons.Default.Delete, stringResource(R.string.annotation_delete_confirm), {
                                val pin = selectedPhotoPin; val file = fullScreenImageFile
                                if (pin != null && file != null && deleteImageMarkup(pin, file, PageItem.NoteItem(note)).changed) showDeletedWithUndo()
                                selectedImageNote = null
                            }, destructive = true, contentDescription = stringResource(R.string.delete_note)),
                            ToolbarAction(Icons.Default.Close, stringResource(R.string.close), { selectedImageNote = null })
                        ))
                    }

                    // Toolbar for selected shape
if (selectedImageShape != null) {
                        val shape = selectedImageShape!!
                        SelectionToolbar(imageToScreenCoords(shape.x, shape.y), listOf(
                            ToolbarAction(Icons.Default.Palette, stringResource(R.string.toolbar_colour), {
                                selectedPhotoPin?.let { pin -> imageAppearance = pin to PageItem.ShapeItem(shape) }
                            }),
                            ToolbarAction(Icons.Default.Delete, stringResource(R.string.annotation_delete_confirm), {
                                val pin = selectedPhotoPin; val file = fullScreenImageFile
                                if (pin != null && file != null && deleteImageMarkup(pin, file, PageItem.ShapeItem(shape)).changed) showDeletedWithUndo()
                                selectedImageShape = null
                            }, destructive = true, contentDescription = stringResource(R.string.delete_shape)),
                            ToolbarAction(Icons.Default.Close, stringResource(R.string.close), { selectedImageShape = null })
                        ))
                    }

if (mode == ToolMode.POLYLINE && imagePoints.isNotEmpty()) {
                        SelectionToolbarCard(listOf(
                            ToolbarAction(Icons.Default.Close, stringResource(R.string.clear_page_cancel), { imagePoints.clear() }),
                            ToolbarAction(Icons.Default.Check, stringResource(R.string.polyline_finish), {
                                val pin = selectedPhotoPin; val file = fullScreenImageFile
                                if (pin != null && file != null) {
                                    buildMeasurement(imagePoints.toList(), 1f, imageAspect, pin.imageScales[file], toolSettings.style(mode))?.let {
                                        if (addImageMarkup(pin, file, PageItem.Measure(it)).changed) { imagePoints.clear(); onAnnotationAdded() }
                                    }
                                }
                            }, enabled = imagePoints.size >= 2)
                        ), Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
if (mode == ToolMode.PAN && selectedImageExtra != null && selectedImageNote == null && selectedImageShape == null) {
                        val extra = selectedImageExtra!!
                        val extraAnchor = when (extra) {
                            is PageItem.Measure -> extra.data.p1
                            is PageItem.Path -> extra.data.points.firstOrNull()
                            else -> null
                        }?.let { imageToScreenCoords(it.x, it.y) } ?: Offset.Zero
                        SelectionToolbar(extraAnchor, listOf(
                            ToolbarAction(Icons.Default.Palette, stringResource(R.string.toolbar_colour), {
                                selectedPhotoPin?.let { pin -> imageAppearance = pin to extra }
                            }),
                            ToolbarAction(Icons.Default.Delete, stringResource(R.string.annotation_delete_confirm), {
                                val pin = selectedPhotoPin; val file = fullScreenImageFile
                                if (pin != null && file != null && deleteImageMarkup(pin, file, extra).changed) showDeletedWithUndo()
                                selectedImageExtra = null
                            }, destructive = true),
                            ToolbarAction(Icons.Default.Close, stringResource(R.string.close), { selectedImageExtra = null })
                        ))
                    }
                    // Close button, top left
                    IconButton(
                        onClick = {
                            fullScreenImageFile = null
                            showPinImageGallery = true
                            selectedImageNote = null
                            selectedImageShape = null
                            imageNoteToolMode = "pan"
                        },
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                    ) {
                        Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
                    }
                    SnackbarHost(viewerSnackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))

                    // Mode indicator at top
                    if (imageNoteToolMode == "place") {
                        Text(
                            stringResource(R.string.fullscreen_note_placement),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp)
                                .background(viewerColors.overlayScrim, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            color = viewerColors.accentOnDark
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
                                .background(viewerColors.overlayScrim, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            color = viewerColors.accentOnDark
                        )
                    }
                }
        }
    }
}
}
