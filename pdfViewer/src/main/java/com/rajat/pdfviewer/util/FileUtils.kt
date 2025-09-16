package com.rajat.pdfviewer.util

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

internal object FileUtils {
    private const val TAG = "PdfValidator"

    @Throws(IOException::class)
    suspend fun fileFromAsset(context: Context, assetName: String): File = withContext(Dispatchers.IO) {
        val outFile = File(context.cacheDir, assetName)
        if (assetName.contains("/")) {
            outFile.parentFile?.mkdirs()
        }
        copy(context.assets.open(assetName), outFile)
        outFile
    }

    @Throws(IOException::class)
    fun copy(inputStream: InputStream, output: File) {
        inputStream.use { input ->
            FileOutputStream(output).use { outputStream ->
                val buffer = ByteArray(1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
            }
        }
    }

    fun getCachedFileName(url: String): String {
        return CacheHelper.getCacheKey(url) + ".pdf"
    }

    fun writeFile(inputStream: InputStream, file: File, totalLength: Long, onProgress: (Long) -> Unit) {
        FileOutputStream(file).use { outputStream ->
            val data = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int
            while (inputStream.read(data).also { bytesRead = it } != -1) {
                outputStream.write(data, 0, bytesRead)
                totalBytesRead += bytesRead
                try {
                    onProgress(totalBytesRead)
                } catch (e: Exception) {
                    Log.w(TAG, "Progress callback failed: ${e.message}", e)
                }
            }
            outputStream.flush()
        }
    }

    suspend fun isValidPdf(file: File?): Boolean = withContext(Dispatchers.IO) {
        if (file == null || !file.exists() || file.length() < 4) {
            Log.e(TAG, "Validation failed: File is null, does not exist, or is too small.")
            return@withContext false
        }

        return@withContext try {
            FileInputStream(file).use { inputStream ->
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    Log.e(TAG, "Validation failed: Unable to read file content.")
                    return@withContext false
                }

                val pdfContent = String(buffer, Charsets.US_ASCII)
                val pdfIndex = pdfContent.indexOf("%PDF")
                if (pdfIndex == -1) {
                    Log.e(TAG, "Validation failed: `%PDF` signature not found in first 1024 bytes.")
                    return@withContext false
                }

                Log.d(TAG, "PDF signature found at byte offset: $pdfIndex")

                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount <= 0) {
                            Log.e(TAG, "Validation failed: PDF has no pages.")
                            return@withContext false
                        }
                        Log.d(TAG, "Validation successful: PDF is valid with ${renderer.pageCount} pages.")
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Validation failed: ${e.message}", e)
            false
        }
    }

    fun cachedFileNameWithFormat(name: Any, format: String = ".jpg") = "$name$format"
}