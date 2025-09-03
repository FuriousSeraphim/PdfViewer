package com.rajat.pdfviewer.util

enum class CacheStrategy {
    MINIMIZE_CACHE,  // Keep only one file at a time
    MAXIMIZE_PERFORMANCE, // Store up to 5 PDFs using LRU eviction
    DISABLE_CACHE // Disable caching
}
