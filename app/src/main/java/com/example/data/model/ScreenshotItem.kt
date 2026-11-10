package com.example.data.model

import android.net.Uri

data class ScreenshotItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val timestamp: Long,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0
) {
    val formattedSize: String
        get() = when {
            sizeBytes >= 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024f * 1024f))
            sizeBytes >= 1024 -> String.format("%.0f KB", sizeBytes / 1024f)
            else -> "$sizeBytes B"
        }
}
