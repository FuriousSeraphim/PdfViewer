package com.rajat.pdfviewer

class RenderQuality(val multiplier: Float) {
    companion object {
        val NORMAL = RenderQuality(1f)
        val HIGH = RenderQuality(2f)
        val ULTRA = RenderQuality(3f)
    }
}