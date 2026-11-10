package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onSessionStopped: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProjectionActive = true

    init {
        // Android 14+ (API 34+) REQUIRES registering callback before createVirtualDisplay
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection session stopped by system")
                isProjectionActive = false
                onSessionStopped()
            }
        }, mainHandler)
    }

    suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (!isProjectionActive) {
            Log.e(TAG, "Cannot capture: MediaProjection is no longer active")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        val width: Int
        val height: Int
        val densityDpi: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            densityDpi = context.resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            densityDpi = metrics.densityDpi
        }

        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val reader = imageReader

            reader.setOnImageAvailableListener({ ir ->
                try {
                    val image = ir.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val tempBitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        tempBitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val finalBitmap = if (rowPadding > 0) {
                            val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                            tempBitmap.recycle()
                            cropped
                        } else {
                            tempBitmap
                        }

                        // Tear down resources immediately after 1 single frame
                        reader.setOnImageAvailableListener(null, null)
                        virtualDisplay?.release()
                        reader.close()

                        if (continuation.isActive) {
                            continuation.resume(finalBitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring image", e)
                    try {
                        reader.setOnImageAvailableListener(null, null)
                        virtualDisplay?.release()
                        reader.close()
                    } catch (ignored: Exception) {}
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }, mainHandler)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "SnapFloat_Capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )

            continuation.invokeOnCancellation {
                try {
                    reader.setOnImageAvailableListener(null, null)
                    virtualDisplay?.release()
                    reader.close()
                } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
            try {
                imageReader?.close()
                virtualDisplay?.release()
            } catch (ignored: Exception) {}
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }

    fun stop() {
        isProjectionActive = false
        try {
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
