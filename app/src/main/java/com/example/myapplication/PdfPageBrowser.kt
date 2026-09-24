package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.example.myapplication.stage7.BitmapSizePlan
import com.example.myapplication.stage7.ByteAwareCachePutResult
import com.example.myapplication.stage7.Stage7CacheKey
import com.example.myapplication.stage7.Stage7OwnedResource
import com.example.myapplication.stage7.Stage7ResourceOwner
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.example.myapplication.stage9.DiagnosticEvent
import com.example.myapplication.stage9.SafeDiagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

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
    pageCodes: Map<Int, String> = emptyMap(),
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
                    Text(pageCodes[index]?.let { "$it · ${index + 1}" } ?: stringResource(R.string.sheet_number, index + 1), Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

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
