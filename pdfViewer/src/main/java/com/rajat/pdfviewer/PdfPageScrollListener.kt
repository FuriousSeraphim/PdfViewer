package com.rajat.pdfviewer

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rajat.pdfviewer.util.validPositionOr

internal class PdfPageScrollListener(
    private val updatePage: (Int) -> Unit,
    private val schedulePrefetch: (Int) -> Unit,
): RecyclerView.OnScrollListener() {

    private var lastDisplayedPage = RecyclerView.NO_POSITION

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

        val pageToShow = when {
            dy > 0 -> {
                layoutManager.findLastCompletelyVisibleItemPosition()
                    .validPositionOr { layoutManager.findLastVisibleItemPosition() }
                    .validPositionOr { layoutManager.findFirstVisibleItemPosition() }
            }
            dy < 0 -> {
                layoutManager.findFirstCompletelyVisibleItemPosition()
                    .validPositionOr { layoutManager.findFirstVisibleItemPosition() }
                    .validPositionOr { layoutManager.findLastVisibleItemPosition() }
            }
            else -> {
                layoutManager.findFirstVisibleItemPosition()
            }
        }

        if (pageToShow != lastDisplayedPage && pageToShow != RecyclerView.NO_POSITION) {
            updatePage(pageToShow)
            lastDisplayedPage = pageToShow
        }
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastVisibleItemPosition()
            schedulePrefetch((first + last) / 2)
        }
    }
}
