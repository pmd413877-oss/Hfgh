package com.example.data.model

data class FloatingSettings(
    val buttonSizeDp: Int = 56,
    val opacityPercent: Int = 90,
    val hideDuringCapture: Boolean = true,
    val vibrateOnCapture: Boolean = true,
    val showFloatingPreview: Boolean = true,
    val countdownDelaySeconds: Int = 0
)
