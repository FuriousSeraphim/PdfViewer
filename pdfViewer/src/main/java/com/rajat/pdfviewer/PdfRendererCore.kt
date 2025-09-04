package com.rajat.pdfviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import com.rajat.pdfviewer.util.BitmapPool
import com.rajat.pdfviewer.util.CacheManager
import com.rajat.pdfviewer.util.CacheStrategy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

internal class PdfRendererCore private constructor(
    private val fileDescriptor: ParcelFileDescriptor,
    private val cacheManager: CacheManager,
    private val pdfRenderer: PdfRenderer,
) {

    private var isRendererOpen = true
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val renderLock = Mutex()
    private val pageCount = AtomicInteger(-1)

    private val openPages = ConcurrentHashMap<Int, PdfRenderer.Page>()
    private val renderJobs = ConcurrentHashMap<Int, Job>()
    private val pageDimensionCache = mutableMapOf<Int, Size>()
    private var prefetchJob: Job? = null

    companion object {
        internal val prefetchDistance: Int = 2

        fun create(
            context: Context,
            fileDescriptor: ParcelFileDescriptor,
            cacheIdentifier: String,
            cacheStrategy: CacheStrategy,
        ): PdfRendererCore {
            val pdfRenderer = PdfRenderer(fileDescriptor)
            val manager = CacheManager(context, cacheIdentifier, cacheStrategy)
            val core = PdfRendererCore(fileDescriptor, manager, pdfRenderer)
            // core.preloadPageDimensions()
            return core
        }

        fun getFileDescriptor(file: File): ParcelFileDescriptor {
            val safeFile = File(sanitizeFilePath(file.path))
            return ParcelFileDescriptor.open(safeFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        fun getCacheIdentifierFromFile(file: File): String = file.name.toString()

        private fun sanitizeFilePath(filePath: String): String {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val path = Paths.get(filePath)
                    if (Files.exists(path)) filePath else ""
                } else filePath
            } catch (e: Exception) {
                ""
            }
        }

        private const val LOG_TAG = "PdfRendererCore"
        private const val METRICS_TAG = "PdfRendererCore_Metrics"
    }

    fun getPageCount(): Int = pageCount.get().takeIf { isRendererOpen } ?: 0

    suspend fun getBitmapFromCache(pageNo: Int): Bitmap? = cacheManager.getBitmapFromCache(pageNo)

    private suspend fun addBitmapToMemoryCache(pageNo: Int, bitmap: Bitmap) = cacheManager.addBitmapToCache(pageNo, bitmap)

    private suspend fun pageExistInCache(pageNo: Int): Boolean = cacheManager.pageExistsInCache(pageNo)

    fun renderPage(pageNumber: Int, size: Size, onBitmapReady: (pageNumber: Int, bitmap: Bitmap?) -> Unit) {
        if (pageNumber < 0 || pageNumber >= getPageCount()) {
            debugLog(METRICS_TAG) { "⚠️ Skipped invalid render for page $pageNumber" }
            onBitmapReady(pageNumber, null)
            return
        }

        scope.launch {
            val cachedBitmap = cacheManager.getBitmapFromCache(pageNumber)
            if (cachedBitmap != null) {
                withContext(Dispatchers.Main) {
                    onBitmapReady(pageNumber, cachedBitmap)
                    debugLog(LOG_TAG) { "Page $pageNumber loaded from cache" }
                }
                return@launch
            }

            if (renderJobs[pageNumber]?.isActive == true) return@launch
            renderJobs[pageNumber]?.cancel()
            renderJobs[pageNumber] = launch {
                var renderedBitmap: Bitmap? = null

                renderLock.withLock {
                    val pdfPage = openPageSafely(pageNumber) ?: return@withLock

                    val bitmap = BitmapPool.runCatching { getBitmap(width = size.width, height = size.height) }.getOrNull()
                    if (bitmap == null) {
                        debugLog(LOG_TAG) { "Failed to obtain bitmap for page $pageNumber" }
                        return@withLock
                    }

                    try {
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        addBitmapToMemoryCache(pageNumber, bitmap)
                        renderedBitmap = bitmap
                    } catch (e: Exception) {
                        debugLog(LOG_TAG, e) { "Error rendering page $pageNumber: ${e.message}" }
                        BitmapPool.recycleBitmap(bitmap)
                    }
                }

                withContext(Dispatchers.Main) {
                    onBitmapReady(pageNumber, renderedBitmap)
                }
            }
        }
    }

    fun preloadPageDimensions() {
        scope.launch {
            for (pageNo in 0 until getPageCount()) {
                if (!pageDimensionCache.containsKey(pageNo)) {
                    withPdfPage(pageNo) { page ->
                        pageDimensionCache[pageNo] = Size(page.width, page.height)
                    }
                }
            }
        }
    }

    fun schedulePrefetch(currentPage: Int, width: Int, height: Int, direction: Int) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            delay(100)
            prefetchPagesAround(currentPage, width, height, direction)
        }
    }

    private suspend fun prefetchPagesAround(currentPage: Int, fallbackWidth: Int, fallbackHeight: Int, direction: Int) {
        val range = when (direction) {
            1 -> (currentPage + 1)..(currentPage + prefetchDistance)
            -1 -> (currentPage - prefetchDistance)..<currentPage
            else -> (currentPage - prefetchDistance)..(currentPage + prefetchDistance)
        }
        val sortedPages = range
            .filter { it in 0 until getPageCount() }
            .filter { !pageExistInCache(it) }
            .sortedBy { abs(it - currentPage) } // prefer pages close to current page

        sortedPages.forEach { pageNo ->
            if (renderJobs[pageNo]?.isActive != true) {
                renderJobs[pageNo]?.cancel()
                renderJobs[pageNo] = scope.launch {
                    val size = withPdfPage(pageNo) { page ->
                        Size(page.width, page.height)
                    } ?: Size(fallbackWidth, fallbackHeight)

                    val aspectRatio = size.width.toFloat() / size.height.toFloat()
                    val height = (fallbackWidth / aspectRatio).toInt()

                    renderPage(pageNo, Size(fallbackWidth, maxOf(1, height))) { _, _ -> }
                }
            }
        }
    }

    fun getPageDimensionsAsync(pageNo: Int, callback: (Size) -> Unit) {
        pageDimensionCache[pageNo]?.let {
            callback(it)
            return
        }

        scope.launch {
            val size = withPdfPage(pageNo) { page ->
                Size(page.width, page.height).also { pageDimensionCache[pageNo] = it }
            } ?: Size(1, 1)

            withContext(Dispatchers.Main) {
                callback(size)
            }
        }
    }

    private suspend fun <T> withPdfPage(pageNo: Int, block: (PdfRenderer.Page) -> T): T? =
        withContext(Dispatchers.IO) {
            renderLock.withLock {
                if (!isRendererOpen) return@withContext null
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    closeAllOpenPages()
                }
                try {
                    pdfRenderer.openPage(pageNo).use(block)
                } catch (e: Exception) {
                    debugLog(LOG_TAG, e) { "withPdfPage error: ${e.message}" }
                    null
                }
            }
        }

    private fun openPageSafely(pageNo: Int): PdfRenderer.Page? {
        if (!isRendererOpen) return null
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) closeAllOpenPages()

        openPages[pageNo]?.let { return it }

        return try {
            val page = pdfRenderer.openPage(pageNo)
            openPages[pageNo] = page
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && openPages.size > 5) {
                openPages.keys.minOrNull()?.let { openPages.remove(it)?.close() }
            }
            page
        } catch (e: Exception) {
            debugLog(LOG_TAG, e) { "Error opening page $pageNo: ${e.message}" }
            null
        }
    }

    private fun closeAllOpenPages() {
        val iterator = openPages.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            try {
                entry.value.close()
            } catch (e: IllegalStateException) {
                debugLog(LOG_TAG, e) { "Page ${entry.key} was already closed" }
            } finally {
                iterator.remove()
            }
        }
    }

    fun closePdfRender() {
        if (!isRendererOpen) return

        debugLog(LOG_TAG) { "Closing PdfRenderer and releasing resources." }

        scope.coroutineContext.cancelChildren()
        closeAllOpenPages()

        runCatching { pdfRenderer.close() }
            .onFailure { debugLog(LOG_TAG, it) { "Error closing PdfRenderer: ${it.message}" } }

        runCatching { fileDescriptor.close() }
            .onFailure { debugLog(LOG_TAG, it) { "Error closing file descriptor: ${it.message}" } }

        isRendererOpen = false
    }

    internal inline fun debugLog(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message(), throwable)
        }
    }
}