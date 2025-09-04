package com.rajat.pdfviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.postDelayed
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.rajat.pdfviewer.R.styleable.PdfRendererView
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_divider
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_enableLoadingForPages
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_enableZoom
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_page_margin
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_page_marginBottom
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_page_marginLeft
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_page_marginRight
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_page_marginTop
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_quality
import com.rajat.pdfviewer.R.styleable.PdfRendererView_pdfView_showDivider
import com.rajat.pdfviewer.util.CacheManager
import com.rajat.pdfviewer.util.CacheStrategy
import com.rajat.pdfviewer.util.FileUtils
import com.rajat.pdfviewer.util.validPositionOr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.content.withStyledAttributes

/**
 * Created by Rajat on 11,July,2020
 */

class PdfRendererView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
): FrameLayout(context, attrs, defStyleAttr) {

    // region Core rendering
    private lateinit var pdfRendererCore: PdfRendererCore
    private lateinit var pdfViewAdapter: PdfViewAdapter
    private var pdfRendererCoreInitialised = false
    // endregion

    // region UI
    private lateinit var recyclerView: PinchZoomRecyclerView
    private var divider: Drawable? = null
    private var pageMargin: Rect = Rect(0, 0, 0, 0)
    // endregion

    // region State
    private var positionToUseForState: Int = NO_POSITION
    private var restoredScrollPosition: Int = NO_POSITION
    private var lastDy: Int = 0
    private var pendingJumpPage: Int = NO_POSITION
    // endregion

    // region Flags
    private var showDivider = true
    var isZoomEnabled = true
        set(value) {
            field = value
            if (::recyclerView.isInitialized) {
                recyclerView.isZoomEnabled = value
            }
        }
    private var enableLoadingForPages = false
    // endregion

    // region Lifecycle + Async
    private val viewJob = SupervisorJob()
    private val viewScope = CoroutineScope(viewJob + Dispatchers.IO)
    // endregion

    var zoomListener: ZoomListener? = null
    var scrollListener: ScrollListener? = null
    var statusListener: StatusCallBack? = null
    var renderQuality: RenderQuality = RenderQuality.NORMAL
        set(value) {
            field = value
            if (::pdfViewAdapter.isInitialized) {
                pdfViewAdapter.renderQuality = value
            }
            if (::recyclerView.isInitialized) {
                recyclerView.renderQuality = value
            }
        }

    // region Public APIs
    fun isZoomedIn(): Boolean = this::recyclerView.isInitialized && recyclerView.isZoomedIn()
    fun getZoomScale(): Float = if (this::recyclerView.isInitialized) recyclerView.getZoomScale() else 1f

    val totalPageCount: Int
        get() {
            return pdfRendererCore.getPageCount()
        }

    /**
     * Clears the cache directory of the application.
     * @param context The application context.
     */
    suspend fun PdfRendererView.clearCache(context: Context) {
        CacheManager.clearCacheDir(context)
    }
    // endregion

    init {
        context.withStyledAttributes(attrs, PdfRendererView, defStyleAttr, 0) {
            renderQuality = RenderQuality(getFloat(PdfRendererView_pdfView_quality, 1f))
            showDivider = getBoolean(PdfRendererView_pdfView_showDivider, true)
            divider = getDrawable(PdfRendererView_pdfView_divider)
            enableLoadingForPages = getBoolean(PdfRendererView_pdfView_enableLoadingForPages, false)
            isZoomEnabled = getBoolean(PdfRendererView_pdfView_enableZoom, true)
            val defaultMargin = getDimensionPixelSize(PdfRendererView_pdfView_page_margin, 0)
            pageMargin.set(
                getDimensionPixelSize(PdfRendererView_pdfView_page_marginLeft, defaultMargin),
                getDimensionPixelSize(PdfRendererView_pdfView_page_marginTop, defaultMargin),
                getDimensionPixelSize(PdfRendererView_pdfView_page_marginRight, defaultMargin),
                getDimensionPixelSize(PdfRendererView_pdfView_page_marginBottom, defaultMargin),
            )
        }
    }

    fun display(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheStrategy: CacheStrategy = CacheStrategy.MAXIMIZE_PERFORMANCE,
    ) {
        viewJob.cancelChildren()
        PdfDownloader(
            coroutineScope = viewScope,
            headers = headers,
            url = url,
            cacheStrategy = cacheStrategy,
            listener = PdfDownloadCallback(
                context = context,
                onStart = { statusListener?.onPdfLoadStart() },
                onProgress = { progress, current, total ->
                    statusListener?.onPdfLoadProgress(progress, current, total)
                },
                onSuccess = {
                    try {
                        display(it, cacheStrategy)
                        statusListener?.onPdfLoadSuccess(it.absolutePath)
                    } catch (e: Exception) {
                        statusListener?.onError(e)
                    }
                },
                onError = { statusListener?.onError(it) }
            )
        ).start()
    }

    fun display(file: File, cacheStrategy: CacheStrategy = CacheStrategy.MAXIMIZE_PERFORMANCE) {
        statusListener?.onPdfRenderStart()
        viewJob.cancelChildren()
        try {
            val renderer = PdfRendererCore.create(
                context = context,
                fileDescriptor = PdfRendererCore.getFileDescriptor(file),
                cacheIdentifier = file.name,
                cacheStrategy = cacheStrategy,
            )
            initializeRenderer(renderer)
            statusListener?.onPdfLoadSuccess(file.absolutePath)
        } catch (e: Exception) {
            statusListener?.onError(e)
        }
    }

    fun display(assetFileName: String, cacheStrategy: CacheStrategy = CacheStrategy.MAXIMIZE_PERFORMANCE) {
        viewJob.cancelChildren()
        viewScope.launch {
            val file = FileUtils.fileFromAsset(context, assetFileName)
            withContext(Dispatchers.Main) {
                display(file, cacheStrategy)
            }
        }
    }

    fun display(uri: Uri, cacheStrategy: CacheStrategy = CacheStrategy.MAXIMIZE_PERFORMANCE) {
        statusListener?.onPdfRenderStart()
        viewJob.cancelChildren()
        try {
            val renderer = PdfRendererCore.create(
                context = context,
                fileDescriptor = this.context.contentResolver.openFileDescriptor(uri, "r") ?: return,
                cacheIdentifier = uri.toString().hashCode().toString(),
                cacheStrategy = cacheStrategy,
            )
            initializeRenderer(renderer)
            statusListener?.onPdfLoadSuccess("uri:$uri")
        } catch (e: Exception) {
            statusListener?.onError(e)
        }
    }

    private fun initializeRenderer(renderer: PdfRendererCore) {
        // If re-initializing, clear old views & adapter
        if (pdfRendererCoreInitialised) {
            viewJob.cancelChildren()
            removeAllViews()
            if (this::recyclerView.isInitialized) {
                recyclerView.adapter = null
            }
        }

        pdfRendererCore = renderer
        pdfRendererCoreInitialised = true

        // Inflate layout first — ensures RecyclerView references are valid
        addView(LayoutInflater.from(this.context).inflate(R.layout.pdf_renderer_view, this, false))

        // Now that layout is added, find RecyclerView and other views
        recyclerView = findViewById(R.id.recyclerView)

        // Now it's safe to create the adapter and assign it
        pdfViewAdapter = PdfViewAdapter(
            context = context,
            renderer = pdfRendererCore,
            parentView = this,
            pageSpacing = pageMargin,
            enableLoadingForPages = enableLoadingForPages,
            renderQuality = renderQuality,
        )

        recyclerView.adapter = pdfViewAdapter
        recyclerView.itemAnimator = DefaultItemAnimator()
        recyclerView.isZoomEnabled = isZoomEnabled
        recyclerView.renderQuality = renderQuality
        if (showDivider) {
            val itemDecoration = DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
            divider?.also {
                itemDecoration.setDrawable(it)
            }
            recyclerView.addItemDecoration(itemDecoration)
        }
        recyclerView.addOnScrollListener(
            PdfPageScrollListener(
                updatePage = { updatePageNumberDisplay(it) },
                schedulePrefetch = { page ->
                    pdfRendererCore.schedulePrefetch(page, recyclerView.width, recyclerView.height, 0)
                }
            )
        )

        recyclerView.postDelayed(500) {
            if (restoredScrollPosition != NO_POSITION) {
                recyclerView.scrollToPosition(restoredScrollPosition)
                restoredScrollPosition = NO_POSITION  // Reset after applying
            }
        }

        recyclerView.zoomChangeListener = { isZoomedIn, scale ->
            zoomListener?.onZoomChanged(isZoomedIn, scale)
        }
        recyclerView.scrolledToTopListener = { isScrolledToTop ->
            scrollListener?.onScroll(isScrolledToTop)
        }
        recyclerView.post {
            statusListener?.onPdfRenderSuccess()
        }

        if (pendingJumpPage != NO_POSITION) {
            jumpToPage(pendingJumpPage)
            pendingJumpPage = NO_POSITION
        }

        // Start preloading cache into memory immediately after setting up adapter and RecyclerView
        preloadCacheIntoMemory()
    }

    fun jumpToPage(pageNumber: Int, smoothScroll: Boolean = true, delayMillis: Long = 150L) {
        if (pageNumber !in 0 until totalPageCount) return
        if (!::recyclerView.isInitialized) {
            pendingJumpPage = pageNumber
            return
        }

        recyclerView.postDelayed(delayMillis) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return@postDelayed
            val adapter = recyclerView.adapter ?: return@postDelayed
            if (adapter.itemCount == 0) return@postDelayed

            if (smoothScroll) {
                layoutManager.smoothScrollToPosition(recyclerView, RecyclerView.State(), pageNumber)
            } else {
                layoutManager.scrollToPositionWithOffset(pageNumber, 0)
            }

            recyclerView.post {
                forceUpdatePageNumber()
            }
        }
    }

    private fun forceUpdatePageNumber() {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager

        val positionToUse = layoutManager.findLastCompletelyVisibleItemPosition()
            .validPositionOr { layoutManager.findLastVisibleItemPosition() }
            .validPositionOr { layoutManager.findFirstCompletelyVisibleItemPosition() }
            .validPositionOr { layoutManager.findFirstVisibleItemPosition() }

        positionToUseForState = positionToUse
        updatePageNumberDisplay(positionToUse)
    }

    private fun updatePageNumberDisplay(position: Int) {
        statusListener?.onPageChanged(position, totalPageCount)
    }

    fun closePdfRender() {
        if (pdfRendererCoreInitialised) {
            pdfRendererCore.closePdfRender()
            pdfRendererCoreInitialised = false
        }
    }

    private suspend fun getBitmapByPage(page: Int): Bitmap? {
        return pdfRendererCore.getBitmapFromCache(page)
    }

    suspend fun getLoadedBitmaps(): List<Bitmap> {
        return (0..<totalPageCount).mapNotNull { page ->
            getBitmapByPage(page)
        }
    }

    private fun preloadCacheIntoMemory() {
        viewScope.launch {
            pdfRendererCore.let { renderer ->
                (0 until renderer.getPageCount()).forEach { pageNo ->
                    renderer.getBitmapFromCache(pageNo)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clear adapter to release ViewHolders
        if (this::recyclerView.isInitialized) {
            recyclerView.adapter = null
        }
        closePdfRender()
        viewJob.cancelChildren()
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val savedState = Bundle()
        savedState.putParcelable("superState", superState)
        if (this::recyclerView.isInitialized) {
            savedState.putInt("scrollPosition", positionToUseForState)
        }
        return savedState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            val superState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                state.getParcelable("superState", Parcelable::class.java)
            } else {
                state.getParcelable("superState")
            }
            super.onRestoreInstanceState(superState)
            restoredScrollPosition = state.getInt("scrollPosition", positionToUseForState)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    fun getScrollDirection(): Int = when {
        lastDy > 0 -> 1   // down/forward
        lastDy < 0 -> -1  // up/backward
        else -> 0         // idle
    }

    // region Interfaces
    interface StatusCallBack {
        fun onPdfLoadStart() {}
        fun onPdfLoadProgress(progress: Int, downloadedBytes: Long, totalBytes: Long?) {}
        fun onPdfLoadSuccess(absolutePath: String) {}
        fun onError(error: Throwable) {}
        fun onPageChanged(currentPage: Int, totalPage: Int) {}
        fun onPdfRenderStart() {}
        fun onPdfRenderSuccess() {}
    }

    interface ZoomListener {
        fun onZoomChanged(isZoomedIn: Boolean, scale: Float)
    }

    interface ScrollListener {
        fun onScroll(isScrolledToTop: Boolean)
    }
}
