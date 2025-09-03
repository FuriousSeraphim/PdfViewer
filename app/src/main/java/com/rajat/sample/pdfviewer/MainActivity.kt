package com.rajat.sample.pdfviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.rajat.pdfviewer.PdfRendererView
import com.rajat.pdfviewer.RenderQuality
import com.rajat.pdfviewer.util.CacheStrategy

class MainActivity: AppCompatActivity() {

    // Sample PDF URLs
    private val largePdf = "https://css4.pub/2015/usenix/example.pdf"
    private val largePdf1 = "https://research.nhm.org/pdfs/10840/10840.pdf"
    private val localPdf = "http://192.168.0.72:8001/pw.pdf"
    private val newsletterPdf = "https://css4.pub/2017/newsletter/drylab.pdf"
    private val textbookPdf = "https://css4.pub/2015/textbook/somatosensory.pdf"

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                launchPdfFromUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Inflate layout
        setContentView(R.layout.activity_main)

        // Set Default ActionBar title
        supportActionBar?.title = "PDF Viewer"

        // Setup Click Listeners
        setupViews()
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        filePicker.launch(intent)
    }

    private fun setupViews() {
        val pdfView = findViewById<PdfRendererView>(R.id.pdfView).apply {
            renderQuality = RenderQuality.HIGH
            statusListener = object: PdfRendererView.StatusCallBack {
                override fun onPdfLoadStart() {
                    Log.i("PDF Status", "Loading started")
                }

                override fun onPdfLoadProgress(progress: Int, downloadedBytes: Long, totalBytes: Long?) {
                    Log.i("PDF Status", "Download progress: $progress%")
                }

                override fun onPdfLoadSuccess(absolutePath: String) {
                    Log.i("PDF Status", "Load successful: $absolutePath")
                }

                override fun onError(error: Throwable) {
                    Log.e("PDF Status", "Error loading PDF: ${error.message}")
                }

                override fun onPageChanged(currentPage: Int, totalPage: Int) {
                    Log.i("PDF Status", "Page changed: $currentPage / $totalPage")
                }

                override fun onPdfRenderStart() {
                    Log.d("PDF Status", "Render started")
                }

                override fun onPdfRenderSuccess() {
                    Log.d("PDF Status", "Render successful")
                    // pdfView.jumpToPage(2)
                }
            }
            zoomListener = object: PdfRendererView.ZoomListener {
                override fun onZoomChanged(isZoomedIn: Boolean, scale: Float) {
                    Log.i("PDF Zoom", "Zoomed in: $isZoomedIn, Scale: $scale")
                }
            }
        }

        findViewById<View>(R.id.onlinePdf).setOnClickListener {
            pdfView.initWithUrl(
                url = largePdf1,
                cacheStrategy = CacheStrategy.MINIMIZE_CACHE
            )
        }

        findViewById<View>(R.id.pickPdfButton).setOnClickListener {
            launchFilePicker()
        }

        findViewById<View>(R.id.fromAssets).setOnClickListener {
            pdfView.initWithAsset(assetFileName = "quote.pdf")
        }

        findViewById<View>(R.id.showInView).setOnClickListener {
            pdfView.initWithUrl(
                url = largePdf,
                cacheStrategy = CacheStrategy.MINIMIZE_CACHE
            )
        }
    }

    private fun launchPdfFromUri(uri: Uri) {
        val pdfView = findViewById<PdfRendererView>(R.id.pdfView)
        pdfView.initWithUri(uri)
    }
}