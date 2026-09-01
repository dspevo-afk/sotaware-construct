package com.example.myapplication

import android.graphics.Bitmap
import android.os.Build

/** Reads the platform-reported allocation size without importing Android into the pure Stage 7 policy. */
internal fun actualBitmapAllocationBytes(bitmap: Bitmap): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        bitmap.allocationByteCount.toLong()
    } else {
        bitmap.byteCount.toLong()
    }
