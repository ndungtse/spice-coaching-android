package com.medtroniclabs.microcoaching.ui.document

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * In-app PDF preview backed by the platform [android.graphics.pdf.PdfRenderer]
 * (API 21+, zero bundled native libraries).
 *
 * Previously backed by the pdfium-based `android-pdf-viewer` fork, which shipped
 * ~7.7 MB/ABI of native `.so` files (libpdfium, libicuuc, chromium libc++/zlib).
 * `PdfRenderer` is part of the OS, so removing that dependency drops those libs
 * from the SDK with no runtime download.
 *
 * The PDF is supplied as an already-downloaded local [file] (resolved by
 * [DocumentPreviewActivity] via the durable `AssetCache`, so it works offline).
 * `PdfRenderer` gives us per-page [Bitmap]s only — scroll, zoom and page-jump are
 * built here in Compose:
 *  - Vertical page scroll via [LazyColumn]; each page rendered fit-width, lazily,
 *    as it enters the viewport (bounded memory — see [PdfDocumentSession]).
 *  - Pinch + double-tap zoom via a `graphicsLayer` transform over the list.
 *    (`PdfRenderer` cannot re-rasterise on zoom the way pdfium did, so pages are
 *    rendered at container width and the transform upscales — mild softening at
 *    high zoom is the accepted trade-off for dropping the native dependency.)
 *  - Initial page jump from [startPage] (1-indexed) via the list's initial index.
 *  - Single-page mode: when [selectedPage] (1-indexed) is supplied, ONLY that page
 *    is rendered — the rest of the document is neither shown nor reachable (no page
 *    nav, no scroll target). [startPage] is ignored in this mode; pinch / double-tap
 *    zoom still applies, and a page taller than the viewport scrolls vertically.
 *  - Floating bottom-right control overlay: page indicator + up / down nav.
 *  - Failure path (corrupt / password-protected PDF — `PdfRenderer` is stricter
 *    than pdfium): "Could not open document" + an "Open in browser" CTA that fires
 *    [externalFallback].
 */
@Composable
internal fun PdfPagerScreen(
    file: File,
    startPage: Int?,
    externalFallback: () -> Unit,
    modifier: Modifier = Modifier,
    selectedPage: Int? = null,
) {
    // Open the document off the main thread; PdfRenderer's constructor parses the
    // file structure and can throw on a corrupt / protected PDF.
    val openState by produceState<PdfOpenState>(PdfOpenState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfDocumentSession(file) }
                .fold(
                    onSuccess = { PdfOpenState.Ready(it) },
                    onFailure = {
                        Log.w(TAG, "PdfRenderer open failed: ${it.message}")
                        PdfOpenState.Failed
                    },
                )
        }
    }

    val current = openState

    // Close the renderer + file descriptor when this session leaves composition
    // (or is replaced because [file] changed). Capture the session for THIS effect
    // instance — do NOT re-read `openState` inside onDispose: on the Loading→Ready
    // transition the old (Loading-keyed) effect disposes, and re-reading would see
    // the new Ready value and close the just-opened session (IllegalStateException:
    // Already closed on the next getPageCount).
    DisposableEffect(current) {
        val session = (current as? PdfOpenState.Ready)?.session
        onDispose { session?.close() }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFFEEEEEE))) {
        when {
            // "Unavailable" is reserved for a genuine OPEN failure (corrupt /
            // protected PDF, missing file). Per-page render hiccups degrade to a
            // blank page slot — they must NOT tear down the whole viewer.
            current is PdfOpenState.Failed ->
                PdfFailure(externalFallback, Modifier.align(Alignment.Center))

            current is PdfOpenState.Ready ->
                if (selectedPage != null) {
                    PdfSinglePage(session = current.session, selectedPage = selectedPage)
                } else {
                    PdfBodyWithOverlay(session = current.session, startPage = startPage)
                }

            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/** Async open outcome for the document session. */
private sealed interface PdfOpenState {
    object Loading : PdfOpenState
    object Failed : PdfOpenState
    data class Ready(val session: PdfDocumentSession) : PdfOpenState
}

/**
 * Owns a [PdfRenderer] over a local file. `PdfRenderer` is **not** thread-safe and
 * permits only one open page at a time, so every access is serialised behind a
 * single monitor and run on [Dispatchers.IO]. Callers must [close] when done.
 */
private class PdfDocumentSession(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    // PdfRenderer is not thread-safe and allows only one open page at a time. A
    // single monitor serialises every render AND close(), so close() (invoked on
    // the main thread at dispose — e.g. the user backs out of a large doc mid-
    // render) can never race a render in flight on IO and crash the native
    // renderer with a page still open.
    private val lock = Any()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /**
     * Renders page [index] to a white-backed ARGB bitmap [targetWidthPx] wide,
     * height derived from the page's aspect ratio. Returns null if the session
     * was already closed. White fill is required — `PdfRenderer` renders onto a
     * transparent surface, so an un-filled bitmap shows page text over the grey
     * backdrop.
     */
    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (closed) return@withContext null
                renderer.openPage(index).use { page ->
                    val width = targetWidthPx.coerceAtLeast(1)
                    val ratio = page.height.toFloat() / page.width.toFloat()
                    val height = (width * ratio).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }.onFailure { Log.w(TAG, "renderer.close threw: ${it.message}") }
            runCatching { pfd.close() }.onFailure { Log.w(TAG, "pfd.close threw: ${it.message}") }
        }
    }
}

/**
 * Hosts the scrolling page list + the floating page-nav overlay. Page/zoom state is
 * hoisted here so the overlay buttons and the gesture handlers can share it.
 */
@Composable
private fun PdfBodyWithOverlay(
    session: PdfDocumentSession,
    startPage: Int?,
) {
    val pageCount = session.pageCount
    val lastIndex = (pageCount - 1).coerceAtLeast(0)
    val initialIndex = ((startPage ?: 1) - 1).coerceIn(0, lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // Deep-link to the cited page. `initialFirstVisibleItemIndex` sets it up-front, but
    // pages are declared with placeholder heights and render asynchronously; snapping
    // once via scrollToItem after the list is laid out reliably lands on the target
    // (scrollToItem anchors by index, independent of not-yet-resolved page heights).
    LaunchedEffect(session, initialIndex) {
        Log.i(TAG, "PDF viewer: startPage=$startPage pageCount=$pageCount initialIndex=$initialIndex")
        if (initialIndex > 0) listState.scrollToItem(initialIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pdfZoom(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pageCount) { index ->
                PdfPageItem(session = session, index = index)
            }
        }

        if (pageCount > 0) {
            PageNavOverlay(
                currentPage = currentPage,
                pageCount = pageCount,
                onPrev = {
                    scope.launch { listState.animateScrollToItem((currentPage - 1).coerceAtLeast(0)) }
                },
                onNext = {
                    scope.launch { listState.animateScrollToItem((currentPage + 1).coerceAtMost(lastIndex)) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
}

/**
 * Single-page mode: renders ONLY [selectedPage] (1-indexed) and nothing else — no
 * page-nav overlay and no scroll target for the rest of the document, so the picked
 * page is the only thing the user can see or reach. The full document stays
 * accessible only via the caller's external ("Open in browser") fallback.
 *
 * A single-item [LazyColumn] is reused (rather than a bare Box) so a page taller than
 * the viewport scrolls vertically and the async render / bounded-memory behaviour of
 * [PdfPageItem] carries over unchanged.
 */
@Composable
private fun PdfSinglePage(
    session: PdfDocumentSession,
    selectedPage: Int,
) {
    val lastIndex = (session.pageCount - 1).coerceAtLeast(0)
    val index = (selectedPage - 1).coerceIn(0, lastIndex)

    LaunchedEffect(session, index) {
        Log.i(TAG, "PDF viewer (single page): selectedPage=$selectedPage pageCount=${session.pageCount} index=$index")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pdfZoom(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (session.pageCount > 0) {
            item { PdfPageItem(session = session, index = index) }
        }
    }
}

/**
 * Interactive zoom/pan shared by the full-document and single-page bodies. At 1× the
 * list scrolls normally; zoomed, the pan offset shifts the content. Bitmaps are
 * rasterised at container width, so the transform upscales beyond 1× (accepted
 * softening — see class KDoc). Double-tap toggles 1×↔2×; pinch spans 1×–3×.
 */
@Composable
private fun Modifier.pdfZoom(): Modifier {
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    zoom = if (zoom > 1f) 1f else 2f
                    offset = Offset.Zero
                },
            )
        }
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                zoom = (zoom * gestureZoom).coerceIn(1f, 3f)
                offset = if (zoom > 1f) offset + pan else Offset.Zero
            }
        }
        .graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = offset.x
            translationY = offset.y
        }
}

/**
 * Renders a single page fit-width. The bitmap is produced lazily once the item's
 * width is known and dropped when the item leaves the list (LazyColumn disposes
 * off-screen items), so peak memory is bounded to the visible window rather than
 * the whole document (source PDFs can run to ~168 pages).
 */
@Composable
private fun PdfPageItem(
    session: PdfDocumentSession,
    index: Int,
) {
    var widthPx by remember { mutableStateOf(0) }

    // The placeholder (A4 aspect) is rendered UNCONDITIONALLY so every item has a
    // real height from the very first layout pass. Gating content behind
    // `widthPx > 0` made all items 0-height until measured, which defeated the
    // initial page-jump: scrollToItem / initialFirstVisibleItemIndex had no extent
    // to scroll within, so once heights materialised the list snapped back to page 1.
    // `widthPx` is needed only to rasterise the bitmap at the right pixel width —
    // the placeholder's height comes from fillMaxWidth + aspectRatio, which layout
    // resolves without it.
    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        val bitmap by produceState<Bitmap?>(initialValue = null, index, widthPx) {
            if (widthPx <= 0) return@produceState
            // CancellationException (incl. Compose's "coroutine scope left the
            // composition" when the item recomposes or scrolls off) MUST propagate
            // — it is normal, not a render failure. Only a genuine render error
            // degrades this one page to a blank slot.
            value = try {
                session.renderPage(index, widthPx)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "page $index render failed: ${e.message}")
                null
            }
        }

        val bmp = bitmap
        if (bmp != null) {
            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 1.414f)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun PageNavOverlay(
    currentPage: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onPrev,
                enabled = currentPage > 0,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.chat_source_page_prev),
                )
            }
            Text(
                // 1-indexed for the user — "47 / 168" reads more naturally than "46 / 168".
                text = "${currentPage + 1} / $pageCount",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(
                onClick = onNext,
                enabled = currentPage < pageCount - 1,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_source_page_next),
                )
            }
        }
    }
}

@Composable
private fun PdfFailure(externalFallback: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.chat_source_unavailable),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = externalFallback) {
            Text(stringResource(R.string.chat_source_open_externally))
        }
    }
}

private const val TAG = "PdfPagerScreen"
