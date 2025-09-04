package com.rajat.pdfviewer

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.rajat.pdfviewer.util.BitmapPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PdfViewAdapter(
    private val context: Context,
    private val renderer: PdfRendererCore,
    private val parentView: PdfRendererView,
    private val pageSpacing: Rect,
    private val enableLoadingForPages: Boolean,
    renderQuality: RenderQuality,
): RecyclerView.Adapter<PdfViewAdapter.PdfPageViewHolder>() {

    var renderQuality: RenderQuality = renderQuality
        set(value) {
            field = value
            if (value != field) {
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
        return PdfPageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false))
    }

    override fun getItemCount(): Int = renderer.getPageCount()

    override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: PdfPageViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    inner class PdfPageViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        private val pageView = itemView.findViewById<ImageView>(R.id.pageView)
        private val progressBar = itemView.findViewById<View>(R.id.progressBar)

        private var currentBoundPage: Int = -1
        private var hasRealBitmap: Boolean = false
        private val fallbackHandler = Handler(Looper.getMainLooper())
        private val mainScope = MainScope()

        private val DEBUG_LOGS_ENABLED = true

        fun bind(position: Int) {
            cancelJobs()

            currentBoundPage = position
            hasRealBitmap = false

            val displayWidth = pageView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels

            pageView.setImageBitmap(null)

            progressBar.isVisible = enableLoadingForPages

            mainScope.launch {
                val cached = withContext(Dispatchers.IO) {
                    renderer.getBitmapFromCache(position)
                }

                if (cached != null && currentBoundPage == position) {
                    if (DEBUG_LOGS_ENABLED) Log.d("PdfViewAdapter", "✅ Loaded page $position from cache")
                    val aspectRatio = cached.width.coerceAtLeast(1).toFloat() / cached.height.coerceAtLeast(1).toFloat()
                    updateLayoutParams((displayWidth / aspectRatio).toInt())
                    pageView.setImageBitmap(cached)
                    hasRealBitmap = true
                    applyFadeInAnimation(pageView)
                    progressBar.isGone = true
                    return@launch
                }

                renderer.getPageDimensionsAsync(position) { size ->
                    if (currentBoundPage != position) return@getPageDimensionsAsync

                    val aspectRatio = size.width.coerceAtLeast(1).toFloat() / size.height.coerceAtLeast(1)
                    val height = (displayWidth / aspectRatio).toInt()
                    updateLayoutParams(height)

                    val bitmapWidth = (displayWidth * renderQuality.multiplier).toInt()
                    val bitmapHeight = (height * renderQuality.multiplier).toInt()
                    renderAndApplyBitmap(position, bitmapWidth, bitmapHeight)
                }
            }

            startPersistentFallbackRender(position)
        }

        fun unbind() {
            cancelJobs()
            pageView.setImageDrawable(null)
            hasRealBitmap = false
        }

        fun cancelJobs() {
            mainScope.coroutineContext.cancelChildren()
        }

        private fun renderAndApplyBitmap(page: Int, width: Int, height: Int) {
            renderer.renderPage(page, Size(width, maxOf(1, height))) { pageNumber, rendered ->
                mainScope.launch {
                    if (rendered != null && currentBoundPage == pageNumber) {
                        if (DEBUG_LOGS_ENABLED) Log.d("PdfViewAdapter", "✅ Render complete for page $pageNumber")
                        pageView.setImageBitmap(rendered)
                        hasRealBitmap = true
                        applyFadeInAnimation(pageView)
                        progressBar.isGone = true

                        val fallbackHeight = pageView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

                        renderer.schedulePrefetch(
                            currentPage = pageNumber,
                            width = width,
                            height = fallbackHeight,
                            direction = parentView.getScrollDirection()
                        )
                    } else {
                        retryRenderOnce(page, width, height)
                    }
                }
            }
        }

        private fun retryRenderOnce(page: Int, width: Int, height: Int) {
            renderer.renderPage(page, Size(width, maxOf(1, height))) { pageNumber, rendered ->
                mainScope.launch {
                    if (rendered != null && pageNumber == currentBoundPage && !hasRealBitmap) {
                        if (DEBUG_LOGS_ENABLED) Log.d("PdfViewAdapter", "🔁 Retry success for page $pageNumber")
                        pageView.setImageBitmap(rendered)
                        hasRealBitmap = true
                        applyFadeInAnimation(pageView)
                        progressBar.isGone = true
                    }
                }
            }
        }

        private fun startPersistentFallbackRender(page: Int, retries: Int = 10, delayMs: Long = 200L) {
            var attempt = 0

            lateinit var task: Runnable
            task = object: Runnable {
                override fun run() {
                    if (currentBoundPage != page || hasRealBitmap) return

                    mainScope.launch {
                        val cached = withContext(Dispatchers.IO) {
                            renderer.getBitmapFromCache(page)
                        }

                        if (cached != null && currentBoundPage == page) {
                            if (DEBUG_LOGS_ENABLED) Log.d("PdfViewAdapter", "🕒 Fallback applied for page $page on attempt $attempt")
                            pageView.setImageBitmap(cached)
                            hasRealBitmap = true
                            applyFadeInAnimation(pageView)
                            progressBar.isGone = true
                        } else {
                            attempt++
                            if (attempt < retries) {
                                fallbackHandler.postDelayed(task, delayMs)
                            }
                        }
                    }
                }
            }

            fallbackHandler.postDelayed(task, delayMs)
        }

        private fun updateLayoutParams(height: Int) {
            itemView.updateLayoutParams {
                this.height = height
                if (this is ViewGroup.MarginLayoutParams) {
                    setMargins(pageSpacing.left, pageSpacing.top, pageSpacing.right, pageSpacing.bottom)
                }
            }
        }

        private fun applyFadeInAnimation(view: View) {
            view.startAnimation(AlphaAnimation(0F, 1F).apply {
                interpolator = LinearInterpolator()
                duration = 300
            })
        }
    }
}