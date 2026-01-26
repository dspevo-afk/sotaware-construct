package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt

// Use a local debug flag to gate temporary diagnostic logs
private const val DEBUG_LOG = true

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
    NOTE("Note", Icons.Default.StickyNote2)
}

enum class Screen { SELECTOR, BROWSER, VIEWER }

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
    var isBold: Boolean = false
) : Serializable {
    fun copyNote() = Note(x, y, text, fontSize, isBold)
}

data class PageMarkups(
    val paths: List<DrawnPath>,
    val measurements: List<Measurement>,
    val notes: List<Note>
) : Serializable

sealed class HistoryAction : Serializable {
    data class AddPath(val path: DrawnPath) : HistoryAction()
    data class AddMeasurement(val measurement: Measurement) : HistoryAction()
    data class AddNote(val note: Note) : HistoryAction()
    data class DeletePath(val path: DrawnPath) : HistoryAction()
    data class DeleteMeasurement(val measurement: Measurement) : HistoryAction()
    data class DeleteNote(val note: Note) : HistoryAction()
    data class UpdateMeasurement(val old: Measurement, val new: Measurement) : HistoryAction()
    data class UpdateNote(val old: Note, val new: Note) : HistoryAction()
}

sealed class PageItem {
    data class Path(val data: DrawnPath) : PageItem()
    data class Measure(val data: Measurement) : PageItem()
    data class NoteItem(val data: Note) : PageItem()
}

class BlueprintViewModel : ViewModel() {
    val pageScales = mutableStateMapOf<Int, PageScale>()
    val pagePaths = mutableStateMapOf<Int, SnapshotStateList<DrawnPath>>()
    val pageMeasurements = mutableStateMapOf<Int, SnapshotStateList<Measurement>>()
    val pageNotes = mutableStateMapOf<Int, SnapshotStateList<Note>>()
    val pageHistory = mutableStateMapOf<Int, MutableList<HistoryAction>>()
    val pageRedoStack = mutableStateMapOf<Int, MutableList<HistoryAction>>()
    val thumbnailCache = mutableStateMapOf<Int, Bitmap>()
    
    fun clearSession() {
        pageScales.clear()
        pagePaths.clear()
        pageMeasurements.clear()
        pageNotes.clear()
        pageHistory.clear()
        pageRedoStack.clear()
        thumbnailCache.clear()
    }

    fun clearPageMarkups(index: Int) {
        pagePaths[index]?.clear()
        pageMeasurements[index]?.clear()
        pageNotes[index]?.clear()
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
            is HistoryAction.DeletePath -> pagePaths[index]?.add(action.path)
            is HistoryAction.DeleteMeasurement -> pageMeasurements[index]?.add(action.measurement)
            is HistoryAction.DeleteNote -> pageNotes[index]?.add(action.note)
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
            is HistoryAction.DeletePath -> pagePaths[index]?.remove(action.path)
            is HistoryAction.DeleteMeasurement -> pageMeasurements[index]?.remove(action.measurement)
            is HistoryAction.DeleteNote -> pageNotes[index]?.remove(action.note)
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
    
    var pdfUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var currentScreen by rememberSaveable { mutableStateOf(Screen.SELECTOR) }
    var selectedPageIndex by rememberSaveable { mutableIntStateOf(0) }
    var totalPageCount by rememberSaveable { mutableIntStateOf(0) }
    var toolMode by rememberSaveable { mutableStateOf(ToolMode.PAN) }
    var showToolMenu by remember { mutableStateOf(false) }
    
    var recentFiles by remember { mutableStateOf(getRecentFiles(context)) }
    var searchTerm by rememberSaveable { mutableStateOf("") }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var pageHighlights by remember { mutableStateOf<Map<Int, List<RectF>>>(emptyMap()) }
    var foundCount by rememberSaveable { mutableIntStateOf(0) }
    var showFoundDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pdfUri) {
        if (pdfUri != null) {
            val savedScales = loadScalesForPdf(context, pdfUri.toString())
            vm.pageScales.putAll(savedScales)
            // load saved markups if available
            val loaded = loadMarkupsForPdf(context, pdfUri.toString())
            for ((idx, pm) in loaded) {
                vm.pagePaths[idx] = pm.paths.toMutableStateList()
                vm.pageMeasurements[idx] = pm.measurements.toMutableStateList()
                vm.pageNotes[idx] = pm.notes.toMutableStateList()
            }
        }
    }

    // Save markups when app is backgrounded or stopped
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, pdfUri) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if ((event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) && pdfUri != null) {
                saveMarkupsForPdf(context, pdfUri.toString(), vm)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onPdfSelected: (Uri) -> Unit = { uri ->
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = getFileName(context, uri)
            saveRecentFile(context, uri.toString(), name)
            recentFiles = getRecentFiles(context)
            vm.clearSession()
            pdfUri = uri
            // If reopening the same URI (or immediately after clear), explicitly load saved markups
            scope.launch {
                val loaded = loadMarkupsForPdf(context, uri.toString())
                for ((idx, pm) in loaded) {
                    vm.pagePaths[idx] = pm.paths.toMutableStateList()
                    vm.pageMeasurements[idx] = pm.measurements.toMutableStateList()
                    vm.pageNotes[idx] = pm.notes.toMutableStateList()
                }
                Log.d("Blueprint", "Applied loaded markups entries=${loaded.size} after selection")
            }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val renderer = PdfRenderer(pfd)
                totalPageCount = renderer.pageCount
                renderer.close()
                pfd.close()
            }
            currentScreen = Screen.BROWSER
        } catch (e: Exception) { }
    }

    // Trigger text extraction/highlight when searchTerm changes or page/pdf changes
    LaunchedEffect(searchTerm, pdfUri, selectedPageIndex) {
            if (searchTerm.isBlank()) return@LaunchedEffect
            if (pdfUri == null) return@LaunchedEffect
            try {
                val engine = PdfSearchEngine(context)
                val results = try {
                    engine.search(pdfUri!!, searchTerm, totalPageCount)
                } catch (t: Throwable) {
                    Log.e("Blueprint", "PdfSearchEngine.search failed", t)
                    emptyMap<Int, List<RectF>>()
                }
                val total = results.values.sumOf { it.size }
                Log.d("Blueprint", "PdfSearchEngine found total=$total matches pages=${results.keys}")
                pageHighlights = results
                foundCount = total
                try { Toast.makeText(context, "Found ${foundCount} matches", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                showFoundDialog = true
                delay(1400)
                showFoundDialog = false
            } catch (t: Throwable) {
                Log.e("Blueprint", "search LaunchedEffect failed", t)
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
                Button(onClick = { Log.d("Blueprint", "Search dialog confirm term='" + searchInput + "'"); searchTerm = searchInput.trim(); showSearchDialog = false }, shape = RoundedCornerShape(12.dp)) { Text("Search") }
            },
            dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text("Cancel") } }
        )
    }

    if (showFoundDialog) {
        AlertDialog(onDismissRequest = { showFoundDialog = false }, title = { Text("Search Results") }, text = { Text("Found $foundCount Matches") }, confirmButton = { TextButton(onClick = { showFoundDialog = false }) { Text("OK") } })
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onPdfSelected(it) }
    }

    val capturePage: () -> Unit = {
        val uri = pdfUri
        if (uri != null) {
            scope.launch {
                val success = captureFullPage(
                    context, 
                    uri, 
                    selectedPageIndex, 
                    vm.pagePaths[selectedPageIndex] ?: emptyList(), 
                    vm.pageMeasurements[selectedPageIndex] ?: emptyList(),
                    vm.pageNotes[selectedPageIndex] ?: emptyList()
                )
                if (success) Toast.makeText(context, "Screenshot saved to Gallery", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentScreen == Screen.VIEWER,
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
                    onClick = { scope.launch { drawerState.close() }; showSearchDialog = true },
                    icon = { Icon(Icons.Default.Search, null) }
                )
                
                NavigationDrawerItem(
                    label = { Text("Screenshot") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; capturePage() },
                    icon = { Icon(Icons.Default.Screenshot, null) }
                )
            }
        }
    ) {
        when (currentScreen) {
            Screen.SELECTOR -> {
                Scaffold(
                    topBar = { CenterAlignedTopAppBar(title = { Text("SOTAware Construct", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)) },
                    floatingActionButton = { LargeFloatingActionButton(onClick = { launcher.launch(arrayOf("application/pdf")) }, containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp)) { Icon(Icons.Default.Add, null, Modifier.size(36.dp)) } }
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Box(modifier = Modifier.weight(0.45f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(modifier = Modifier.size(160.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = CircleShape) {
                                    Image(painter = painterResource(id = R.drawable.ic_launcher_foreground), contentDescription = "Logo", modifier = Modifier.padding(32.dp).fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
                                }
                                Spacer(Modifier.height(24.dp))
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
                                            ListItem(headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) }, supportingContent = { Text("Blueprint", style = MaterialTheme.typography.bodySmall) }, leadingContent = { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Info, null, Modifier.padding(8.dp)) } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Screen.BROWSER -> {
                BackHandler { currentScreen = Screen.SELECTOR }
                Scaffold(topBar = { TopAppBar(title = { Text("Select Sheet", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = { currentScreen = Screen.SELECTOR }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { innerPadding ->
                    PdfPageBrowser(uri = pdfUri!!, thumbnailCache = vm.thumbnailCache, modifier = Modifier.padding(innerPadding), onPageSelected = { selectedPageIndex = it; currentScreen = Screen.VIEWER })
                }
            }
            Screen.VIEWER -> {
                BackHandler { currentScreen = Screen.BROWSER }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Column { Text("Page ${selectedPageIndex + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(toolMode.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
                            actions = {
                                IconButton(onClick = { vm.undo(selectedPageIndex) }, enabled = vm.canUndo(selectedPageIndex)) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                                IconButton(onClick = { vm.redo(selectedPageIndex) }, enabled = vm.canRedo(selectedPageIndex)) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                                IconButton(onClick = { if (selectedPageIndex > 0) selectedPageIndex-- }, enabled = selectedPageIndex > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                IconButton(onClick = { if (selectedPageIndex < totalPageCount - 1) selectedPageIndex++ }, enabled = selectedPageIndex < totalPageCount - 1) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                                if (!isLandscape) {
                                    Box {
                                        Surface(
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                            onClick = { showToolMenu = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(toolMode.icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(8.dp))
                                                Text(toolMode.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                                Icon(Icons.Default.ArrowDropDown, null)
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = showToolMenu,
                                            onDismissRequest = { showToolMenu = false },
                                            modifier = Modifier.widthIn(min = 200.dp)
                                        ) {
                                            ToolMode.entries.forEach { mode ->
                                                val isSelected = toolMode == mode
                                                DropdownMenuItem(
                                                    text = { Text(mode.label, fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal) },
                                                    onClick = {
                                                        toolMode = if (toolMode == mode) ToolMode.PAN else mode
                                                        showToolMenu = false
                                                    },
                                                    leadingIcon = { Icon(mode.icon, null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                                    trailingIcon = { if (isSelected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                                    colors = if (isSelected) MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary) else MenuDefaults.itemColors()
                                                )
                                            }
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                            DropdownMenuItem(
                                                text = { Text("Clear Markups", color = Color.Red) },
                                                onClick = { vm.clearPageMarkups(selectedPageIndex); showToolMenu = false },
                                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                            }
                        )
                    }
                ) { innerPadding ->
                    Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.White)) {
                            PdfPageRenderer(
                                uri = pdfUri!!, 
                                pageIndex = selectedPageIndex, 
                                mode = toolMode, 
                                currentScale = vm.pageScales[selectedPageIndex],
                                paths = vm.pagePaths.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                measurements = vm.pageMeasurements.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                notes = vm.pageNotes.getOrPut(selectedPageIndex) { mutableStateListOf() },
                                searchTerm = searchTerm,
                                highlightRects = pageHighlights[selectedPageIndex] ?: emptyList(),
                                onScaleDefined = { pixels, feet ->
                                    val newScale = PageScale(pixels / feet)
                                    vm.pageScales[selectedPageIndex] = newScale
                                    saveScaleForPdf(context, pdfUri.toString(), selectedPageIndex, newScale.pixelsPerFoot)
                                    toolMode = ToolMode.PAN
                                },
                                onActionAdded = { action ->
                                    vm.addAction(selectedPageIndex, action)
                                    if (action is HistoryAction.AddMeasurement || action is HistoryAction.AddNote) {
                                        toolMode = ToolMode.PAN
                                    }
                                },
                                onDeleteItem = { item -> vm.deleteItem(selectedPageIndex, item) }
                            )
                        }
                        if (isLandscape) {
                            Surface(modifier = Modifier.width(64.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 4.dp) {
                                Column(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    ToolMode.entries.forEach { mode ->
                                        IconButton(
                                            onClick = { toolMode = if (toolMode == mode) ToolMode.PAN else mode }, 
                                            colors = if (toolMode == mode) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary) else IconButtonDefaults.iconButtonColors()
                                        ) { Icon(mode.icon, mode.label) }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { vm.clearPageMarkups(selectedPageIndex) }) { Icon(Icons.Default.Delete, "Clear", tint = Color.Red.copy(alpha = 0.7f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PdfPageBrowser(uri: Uri, thumbnailCache: SnapshotStateMap<Int, Bitmap>, modifier: Modifier = Modifier, onPageSelected: (Int) -> Unit) {
    val context = LocalContext.current
    val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
    val count = if (pfd != null) PdfRenderer(pfd).use { it.pageCount } else 0
    pfd?.close()
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(count) { index ->
            if (!thumbnailCache.containsKey(index)) {
                LaunchedEffect(index) {
                    withContext(Dispatchers.IO) {
                        val cacheFile = getThumbCacheFile(context, uri, index)
                        if (cacheFile.exists()) { val b = BitmapFactory.decodeFile(cacheFile.absolutePath); if (b != null) { thumbnailCache[index] = b; return@withContext } }
                        val innerPfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                        innerPfd?.use { PdfRenderer(it).use { renderer ->
                            val page = renderer.openPage(index)
                            val b = Bitmap.createBitmap(600, (600 * page.height / page.width), Bitmap.Config.ARGB_8888)
                            Canvas(b).drawColor(android.graphics.Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            thumbnailCache[index] = b
                            page.close()
                            try { FileOutputStream(cacheFile).use { out -> b.compress(Bitmap.CompressFormat.JPEG, 80, out) } } catch (e: Exception) { }
                        } }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPageSelected(index) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)) {
                        thumbnailCache[index]?.let { Image(bitmap = it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
                        ?: Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
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
    pageIndex: Int, 
    mode: ToolMode, 
    currentScale: PageScale?, 
    paths: SnapshotStateList<DrawnPath>, 
    measurements: SnapshotStateList<Measurement>,
    notes: SnapshotStateList<Note>,
    searchTerm: String,
    highlightRects: List<RectF>,
    onScaleDefined: (Float, Float) -> Unit,
    onActionAdded: (HistoryAction) -> Unit,
    onDeleteItem: (PageItem) -> Unit
) {
    val context = LocalContext.current
    val pdfSearchEngine = remember { PdfSearchEngine(context) }
    val textMeasurer = rememberTextMeasurer()
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var scale by rememberSaveable(pageIndex) { mutableStateOf(1f) }
    var offsetX by rememberSaveable(pageIndex) { mutableStateOf(0f) }
    var offsetY by rememberSaveable(pageIndex) { mutableStateOf(0f) }
    
    if (scale.isNaN() || offsetX.isNaN() || offsetY.isNaN()) {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    var firstPoint by rememberSaveable(pageIndex, mode) { mutableStateOf<Point?>(null) }
    var secondPoint by rememberSaveable(pageIndex, mode) { mutableStateOf<Point?>(null) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    val currentStroke = remember { mutableStateListOf<Point>() }
    
    var itemToDelete by remember { mutableStateOf<PageItem?>(null) }
    var selectedItem by remember { mutableStateOf<PageItem?>(null) }
    
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

    DisposableEffect(uri, pageIndex) {
        val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
        if (pfd != null) { PdfRenderer(pfd).use { renderer ->
            val page = renderer.openPage(pageIndex)
            val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            Canvas(b).drawColor(android.graphics.Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap = b
            try { Log.d("Blueprint", "Rendered pageIndex=$pageIndex bmpSize=${b.width}x${b.height}") } catch (_: Exception) {}
            page.close()
        }; pfd.close() }
        onDispose { }
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
    
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Item?") },
            text = { Text("Are you sure you want to remove this markup?") },
            confirmButton = { Button(onClick = { 
                onDeleteItem(itemToDelete!!)
                if (itemToDelete is PageItem.Measure && (itemToDelete as PageItem.Measure).data == selectedMeasurement) selectedMeasurement = null
                if (itemToDelete is PageItem.NoteItem && (itemToDelete as PageItem.NoteItem).data == selectedNote) selectedNote = null
                itemToDelete = null 
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { itemToDelete = null }) { Text("Cancel") } }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        bitmap?.let { b ->
            val bW = b.width.toFloat(); val bH = b.height.toFloat()
            val bitmapAspectRatio = bW / bH
            val screenAspectRatio = w / h
            val (vW, vH) = if (bitmapAspectRatio > screenAspectRatio) w to (w / bitmapAspectRatio) else (h * bitmapAspectRatio) to h
            
            Box(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(pageIndex, mode, w, h) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startTime = System.currentTimeMillis()
                            var dragActive = false
                            var totalPan = Offset.Zero
                            
                            val startPt = Point((down.position.x - w / 2 - offsetX) / scale + bW / 2, (down.position.y - h / 2 - offsetY) / scale + bH / 2)
                            val handleThreshold = 40f / scale

                            if (mode == ToolMode.PAN) {
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
                            } else if (mode == ToolMode.SCALE && firstPoint != null && secondPoint != null) {
                                if (dist(startPt, firstPoint!!) < handleThreshold) calibratePointIdx = 0
                                else if (dist(startPt, secondPoint!!) < handleThreshold) calibratePointIdx = 1
                            }

                            do {
                                val event = awaitPointerEvent()
                                val pointers = event.changes
                                val centroid = event.calculateCentroid()
                                
                                if (draggingPointIdx != -1) {
                                    val change = pointers[0]
                                    val currentPt = Point((change.position.x - w / 2 - offsetX) / scale + bW / 2, (change.position.y - h / 2 - offsetY) / scale + bH / 2)
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
                                    val currentPt = Point((change.position.x - w / 2 - offsetX) / scale + bW / 2, (change.position.y - h / 2 - offsetY) / scale + bH / 2)
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
                                    val currentPt = Point((change.position.x - w / 2 - offsetX) / scale + bW / 2, (change.position.y - h / 2 - offsetY) / scale + bH / 2)
                                    if (calibratePointIdx == 0) firstPoint = currentPt else secondPoint = currentPt
                                    change.consume()
                                    dragActive = true
                                } else if (pointers.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (selectedNoteIdx != -1) {
                                        val cur = notes[selectedNoteIdx].copyNote()
                                        cur.fontSize = (cur.fontSize * zoom).coerceIn(8f, 200f)
                                        notes[selectedNoteIdx] = cur
                                        selectedNote = cur
                                        pointers.forEach { it.consume() }
                                        dragActive = true
                                    } else if (selectedNote != null) {
                                        val idx = notes.indexOfFirst { it === selectedNote }
                                        if (idx != -1) {
                                            val cur = notes[idx].copyNote()
                                            cur.fontSize = (cur.fontSize * zoom).coerceIn(8f, 200f)
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
                                    val pan = event.calculatePan()
                                    totalPan += pan
                                    if (centroid != Offset.Unspecified) {
                                        offsetX = (offsetX + pan.x).coerceIn(-(vW * scale) / 2, (vW * scale) / 2)
                                        offsetY = (offsetY + pan.y).coerceIn(-(vH * scale) / 2, (vH * scale) / 2)
                                    }
                                    pointers.forEach { it.consume() }
                                    if (totalPan.getDistance() > 10f) dragActive = true
                                } else if (pointers.size == 1) {
                                    val change = pointers[0]
                                    if (mode == ToolMode.PEN || mode == ToolMode.HIGHLIGHTER) {
                                        dragActive = true
                                        currentStroke.add(Point((change.position.x - w / 2 - offsetX) / scale + bW / 2, (change.position.y - h / 2 - offsetY) / scale + bH / 2))
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            
                                if (draggingPointIdx != -1) {
                                    if (originalMeasurement != null && selectedMeasurement != null) {
                                        onActionAdded(HistoryAction.UpdateMeasurement(originalMeasurement!!, selectedMeasurement!!.copyMeasurement(p1 = selectedMeasurement!!.p1.copyPoint(), p2 = selectedMeasurement!!.p2.copyPoint())))
                                    }
                                    draggingPointIdx = -1
                                    originalMeasurement = null
                                    isItemDragging = false
                                    if (selectedMeasurement != null) selectedItem = PageItem.Measure(selectedMeasurement!!)
                                } else if (draggingNoteIdx != -1) {
                                    if (originalNote != null && selectedNote != null) {
                                        onActionAdded(HistoryAction.UpdateNote(originalNote!!, selectedNote!!.copyNote()))
                                    }
                                    draggingNoteIdx = -1
                                    originalNote = null
                                    isItemDragging = false
                                    if (selectedNote != null) selectedItem = PageItem.NoteItem(selectedNote!!)
                                
                            } else if (calibratePointIdx != -1) {
                                calibratePointIdx = -1
                            } else if (dragActive && currentStroke.isNotEmpty()) {
                                val newPath = DrawnPath(currentStroke.toList(), if(mode == ToolMode.HIGHLIGHTER) Color.Yellow.toArgb() else Color.Red.toArgb(), if(mode == ToolMode.HIGHLIGHTER) 12f else 2f, mode == ToolMode.HIGHLIGHTER)
                                paths.add(newPath)
                                onActionAdded(HistoryAction.AddPath(newPath))
                                currentStroke.clear()
                            } else if (!dragActive && mode == ToolMode.PAN) {
                                val tapPt = Point((down.position.x - w / 2 - offsetX) / scale + bW / 2, (down.position.y - h / 2 - offsetY) / scale + bH / 2)
                                // Find nearest item and open contextual toolbar (no double-tap / long-press)
                                var found: PageItem? = null
                                val thresholdSegment = 30f / scale
                                for (m in measurements) { if (distToSegment(tapPt, m.p1, m.p2) < thresholdSegment) { found = PageItem.Measure(m); break } }
                                if (found == null) {
                                    for (n in notes) {
                                        val textStyle = TextStyle(fontSize = n.fontSize.sp, fontWeight = if(n.isBold) FontWeight.Bold else FontWeight.Normal)
                                        val textLayoutResult = textMeasurer.measure(n.text, style = textStyle)
                                        val textWidth = textLayoutResult.size.width.toFloat(); val textHeight = textLayoutResult.size.height.toFloat()
                                        val noteRect = Rect(n.x - textWidth/2, n.y - textHeight/2, n.x + textWidth/2, n.y + textHeight/2)
                                        if (noteRect.contains(Offset(tapPt.x, tapPt.y))) { found = PageItem.NoteItem(n); break }
                                    }
                                }
                                if (found == null) {
                                    for (p in paths) {
                                        for (i in 0 until p.points.size - 1) { if (distToSegment(tapPt, p.points[i], p.points[i+1]) < thresholdSegment + (p.strokeWidth / 2f)) { found = PageItem.Path(p); break } }
                                        if (found != null) break
                                    }
                                }
                                selectedItem = found
                                // keep selectedMeasurement/selectedNote for visual handles
                                selectedMeasurement = if (found is PageItem.Measure) found.data else null
                                if (found is PageItem.NoteItem) {
                                    selectedNote = found.data
                                    selectedNoteIdx = notes.indexOf(found.data)
                                } else {
                                    selectedNote = null
                                    selectedNoteIdx = -1
                                }
                            } else if (!dragActive && mode == ToolMode.NOTE) {
                                val tapPt = Point((down.position.x - w / 2 - offsetX) / scale + bW / 2, (down.position.y - h / 2 - offsetY) / scale + bH / 2)
                                notePos = tapPt
                                noteInput = ""
                                showNoteDialog = true
                            } else if (!dragActive && (mode == ToolMode.MEASURE || mode == ToolMode.SCALE)) {
                                val pt = Point((down.position.x - w / 2 - offsetX) / scale + bW / 2, (down.position.y - h / 2 - offsetY) / scale + bH / 2)
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
                    fun toS(p: Point): Offset {
                        val baseScale = if (bW > 0f) (vW / bW) else 1f
                        val compositeScale = baseScale * scale
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

                        if (nMatches) {
                            drawRect(color = Color.Yellow, topLeft = textTopLeft - Offset(8f, 4f), size = Size(textWidth + 16f, textHeight + 8f))
                        }
                        if (n == selectedNote) {
                            val dashedStroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                            drawRect(color = Color.Cyan, topLeft = textTopLeft - Offset(8f, 4f), size = Size(textWidth + 16f, textHeight + 8f), style = dashedStroke)
                        }
                        drawText(textLayoutResult, topLeft = textTopLeft)
                    }

                    
                    
                    // Draw highlight rects returned by text extractor (coords are normalized [0..1] relative to page bitmap)
                    // Optional debug: draw cached OCR line boxes (outline) when enabled
                    val DEBUG_OCR_BOXES = false
                    if (DEBUG_OCR_BOXES) {
                        val pageOcr = pdfSearchEngine.getCachedPageOcr(uri, pageIndex)
                        pageOcr?.boxes?.forEach { ob ->
                            val lpx = ob.rectN.left * bW
                            val tpx = ob.rectN.top * bH
                            val rpx = ob.rectN.right * bW
                            val bpx = ob.rectN.bottom * bH
                            val tl = toS(Point(lpx, tpx))
                            val br = toS(Point(rpx, bpx))
                            val topLeft = Offset(minOf(tl.x, br.x), minOf(tl.y, br.y))
                            val size = Size(kotlin.math.abs(br.x - tl.x), kotlin.math.abs(br.y - tl.y))
                            drawRect(color = Color.Magenta, topLeft = topLeft, size = size, style = Stroke(width = 1f))
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

                // Contextual toolbar for selected items (appears in PAN mode) and follows the item
                if (selectedItem != null && mode == ToolMode.PAN && !isItemDragging) {
                    val toScreen: (Point) -> Offset = { p -> Offset((p.x - bW / 2) * scale + w / 2 + offsetX, (p.y - bH / 2) * scale + h / 2 + offsetY) }
                    val anchor = when (selectedItem) {
                        is PageItem.Measure -> {
                            val m = (selectedItem as PageItem.Measure).data
                            val p1 = toScreen(m.p1); val p2 = toScreen(m.p2)
                            Offset((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
                        }
                        is PageItem.NoteItem -> toScreen((selectedItem as PageItem.NoteItem).data.let { Point(it.x, it.y) })
                        is PageItem.Path -> {
                            val p = (selectedItem as PageItem.Path).data
                            val start = toScreen(p.points.first()); val end = toScreen(p.points.last())
                            Offset((start.x + end.x) / 2, (start.y + end.y) / 2)
                        }
                        else -> Offset.Zero
                    }
                    val density = LocalDensity.current
                    val dx = with(density) { anchor.x.toDp() }
                    val dy = with(density) { anchor.y.toDp() }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Card(modifier = Modifier.offset(dx, dy).shadow(8.dp).wrapContentSize(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors()) {
                            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectedItem is PageItem.NoteItem) {
                                    TextButton(onClick = {
                                        val note = (selectedItem as PageItem.NoteItem).data
                                        editingNote = note
                                        noteInput = note.text
                                        noteIsBold = note.isBold
                                        showNoteDialog = true
                                    }) { Text("Edit") }
                                    TextButton(onClick = { itemToDelete = selectedItem }) { Text("Delete", color = Color.Red) }
                                } else {
                                    TextButton(onClick = { itemToDelete = selectedItem }) { Text("Delete", color = Color.Red) }
                                }
                            }
                        }
                    }
                    
                }
            }
        }
    }
}

suspend fun captureFullPage(context: Context, uri: Uri, pageIndex: Int, paths: List<DrawnPath>, measurements: List<Measurement>, notes: List<Note>): Boolean = withContext(Dispatchers.IO) {
    try {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext false
        val renderer = PdfRenderer(pfd)
        val page = renderer.openPage(pageIndex)
        
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        val paint = android.graphics.Paint().apply { isAntiAlias = true }
        
        paths.forEach { pathData ->
            if (pathData.points.size > 1) {
                paint.color = pathData.colorArgb
                paint.strokeWidth = pathData.strokeWidth
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
            paint.strokeWidth = 4f
            paint.alpha = 255
            canvas.drawLine(m.p1.x, m.p1.y, m.p2.x, m.p2.y, paint)
            canvas.drawCircle(m.p1.x, m.p1.y, 6f, paint)
            canvas.drawCircle(m.p2.x, m.p2.y, 6f, paint)
            
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 24f
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
            
            canvas.drawRect(midX - textWidth / 2 - 10, midY - textHeight / 2 - 5, midX + textWidth / 2 + 10, midY + textHeight / 2 + 5, bgPaint)
            canvas.drawText(m.text, midX - textWidth / 2, midY - (fontMetrics.ascent + fontMetrics.descent) / 2, textPaint)
        }

        notes.forEach { n ->
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = n.fontSize * 2f // Scale up for full resolution
                isFakeBoldText = n.isBold
            }
            val textWidth = textPaint.measureText(n.text)
            val fontMetrics = textPaint.fontMetrics
            canvas.drawText(n.text, n.x - textWidth/2, n.y - (fontMetrics.ascent + fontMetrics.descent)/2, textPaint)
        }
        
        page.close()
        renderer.close()
        pfd.close()
        
        saveBitmapToGallery(context, bitmap, "Construct_Page_${pageIndex + 1}")
    } catch (e: Exception) {
        false
    }
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean {
    val relativeLocation = Environment.DIRECTORY_PICTURES + File.separator + "SOTAwareConstruct"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeLocation)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false

    return try {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
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
fun saveScaleForPdf(context: Context, pdfUri: String, page: Int, pixelsPerFoot: Float) { context.getSharedPreferences("scales", Context.MODE_PRIVATE).edit().putFloat("${pdfUri}_$page", pixelsPerFoot).apply() }
fun loadScalesForPdf(context: Context, pdfUri: String): Map<Int, PageScale> { val prefs = context.getSharedPreferences("scales", Context.MODE_PRIVATE); return prefs.all.filterKeys { it.startsWith(pdfUri) }.mapKeys { it.key.substringAfterLast("_").toInt() }.mapValues { PageScale(it.value as Float) } }
fun getThumbCacheFile(context: Context, uri: Uri, index: Int) = File(File(context.cacheDir, "thumbs/${uri.toString().hashCode()}").apply { if(!exists()) mkdirs() }, "p_$index.jpg")
fun getRecentFiles(context: Context): List<RecentFile> { val set = context.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE).getStringSet("recent_uris", emptySet()) ?: emptySet(); return set.map { val p = it.split("|", limit = 2); RecentFile(p[0], if (p.size > 1) p[1] else "Unknown") }.sortedBy { it.name }.reversed() }
fun saveRecentFile(context: Context, uri: String, name: String) { val prefs = context.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE); val set = prefs.getStringSet("recent_uris", emptySet())?.toMutableSet() ?: mutableSetOf(); set.removeIf { it.startsWith("$uri|") } ; set.add("$uri|$name") ; prefs.edit().putStringSet("recent_uris", set).apply() }
fun getFileName(context: Context, uri: Uri): String { var r: String? = null; if (uri.scheme == "content") context.contentResolver.query(uri, null, null, null, null)?.use { if (it.moveToFirst()) r = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) }; return r ?: uri.path?.substringAfterLast('/') ?: "Document.pdf" }

fun saveMarkupsForPdf(context: Context, pdfUri: String, vm: BlueprintViewModel) {
    try {
        val file = File(context.filesDir, "markups_" + pdfUri.hashCode() + ".bin")
        Log.d("Blueprint", "Saving markups to " + file.absolutePath)
        ObjectOutputStream(FileOutputStream(file)).use { oos ->
            val data = HashMap<Int, PageMarkups>()
            for ((idx, paths) in vm.pagePaths) {
                // Deep-copy into plain serializable java lists to avoid Compose immutable collection types
                val pathsList = ArrayList<DrawnPath>()
                for (p in paths) {
                    val pts = ArrayList<Point>()
                    for (pt in p.points) pts.add(Point(pt.x, pt.y))
                    pathsList.add(DrawnPath(pts, p.colorArgb, p.strokeWidth, p.isHighlighter))
                }

                val measurementsList = ArrayList<Measurement>()
                val msrc = vm.pageMeasurements[idx]
                if (msrc != null) {
                    for (m in msrc) measurementsList.add(Measurement(Point(m.p1.x, m.p1.y), Point(m.p2.x, m.p2.y), m.text))
                }

                val notesList = ArrayList<Note>()
                val nsrc = vm.pageNotes[idx]
                if (nsrc != null) {
                    for (n in nsrc) notesList.add(Note(n.x, n.y, n.text, n.fontSize, n.isBold))
                }

                    data[idx] = PageMarkups(pathsList, measurementsList, notesList)
            }
                oos.writeObject(data)
        }
    } catch (e: Exception) { Log.e("Blueprint", "saveMarkupsForPdf failed", e) }
}

fun loadMarkupsForPdf(context: Context, pdfUri: String): Map<Int, PageMarkups> {
    try {
        val file = File(context.filesDir, "markups_" + pdfUri.hashCode() + ".bin")
        Log.d("Blueprint", "Loading markups from " + file.absolutePath)
        if (!file.exists()) return emptyMap()
        ObjectInputStream(FileInputStream(file)).use { ois ->
            val obj = ois.readObject()
            @Suppress("UNCHECKED_CAST")
            val map = (obj as? Map<Int, PageMarkups>) ?: emptyMap()
            Log.d("Blueprint", "Loaded markups entries=${map.size}")
            return map
        }
    } catch (e: Exception) { Log.e("Blueprint", "loadMarkupsForPdf failed", e); return emptyMap() }
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