package com.rajat.pdfviewer.util

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import com.rajat.pdfviewer.R

/**
 * Handles general view styling like background & progress bar
 */
data class ViewerStyle(
    val backgroundColor: Int,
    val progressBarDrawableResId: Int,
) {
    fun applyTo(parentLayout: View, progressBar: ProgressBar) {
        try {
            parentLayout.setBackgroundColor(backgroundColor)
            progressBar.indeterminateDrawable = ContextCompat.getDrawable(
                progressBar.context, progressBarDrawableResId
            )
        } catch (e: Exception) {
            Log.w("ViewerStyle", "Failed to apply style: ${e.localizedMessage}")
        }
    }

    companion object {
        fun from(context: Context): ViewerStyle {
            val typedArray = context.theme.obtainStyledAttributes(
                R.styleable.PdfRendererView
            )

            val backgroundColor = ThemeUtils.getColorFromTypedArray(
                typedArray,
                R.styleable.PdfRendererView_pdfView_backgroundColor,
                ContextCompat.getColor(context, R.color.pdf_viewer_surface)
            )

            val progressBarDrawableResId = ThemeUtils.getResIdFromTypedArray(
                typedArray,
                R.styleable.PdfRendererView_pdfView_progressBar,
                R.drawable.pdf_viewer_progress_circle
            )

            typedArray.recycle()

            return ViewerStyle(
                backgroundColor = backgroundColor,
                progressBarDrawableResId = progressBarDrawableResId
            )
        }
    }
}