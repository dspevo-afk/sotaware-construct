package com.example.myapplication

import android.graphics.RectF

// OCR model types
data class OcrBox(val text: String, val rectN: RectF)
data class PageOcr(val pageIndex: Int, val boxes: List<OcrBox>)
