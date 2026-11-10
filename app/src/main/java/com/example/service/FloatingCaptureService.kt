package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.data.model.FloatingSettings
import com.example.data.repository.ScreenshotRepository
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

class FloatingCaptureService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var windowManager: WindowManager
    private var screenCaptureManager: ScreenCaptureManager? = null
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var screenshotRepository: ScreenshotRepository

    private var floatingView: FrameLayout? = null
    private var previewOverlayView: LinearLayout? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var isCapturing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository.getInstance(this)
        screenshotRepository = ScreenshotRepository.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopFloatingService()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE -> {
                triggerScreenshot()
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                // Start Foreground with mediaProjection type on Android 14+ (API 34+)
                val notification = buildForegroundNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                if (resultCode != 0 && resultData != null) {
                    initMediaProjection(resultCode, resultData)
                }

                showFloatingWidget()
                _isServiceRunning.value = true
            }
        }

        return START_STICKY
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection != null) {
                screenCaptureManager = ScreenCaptureManager(this, projection) {
                    Log.d(TAG, "ScreenCapture session invalidated")
                    _hasActiveProjection.value = false
                    stopFloatingService()
                }
                _hasActiveProjection.value = true
                Log.d(TAG, "MediaProjection successfully initialized in Foreground Service")
            } else {
                Log.e(TAG, "MediaProjection is null")
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                stopFloatingService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            Toast.makeText(this, "Error initializing capture: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            stopFloatingService()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWidget() {
        if (floatingView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted")
            Toast.makeText(this, "Overlay permission is required for floating button", Toast.LENGTH_LONG).show()
            return
        }

        val settings = settingsRepository.settings.value
        val buttonSizePx = dpToPx(settings.buttonSizeDp)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            buttonSizePx,
            buttonSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = getScreenHeight() / 3
        }
        windowLayoutParams = params

        // Root container
        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // Circular Button Background with glowing border
        val button = FrameLayout(this).apply {
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F172A")) // Slate 900
                setStroke(dpToPx(2.5f), Color.parseColor("#00E5FF")) // Cyan glow
            }
            background = bgDrawable
            alpha = settings.opacityPercent / 100f
            elevation = dpToPx(8f).toFloat()
        }

        // Camera / Shutter Icon in Center
        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(Color.parseColor("#00E5FF"))
            val iconPadding = dpToPx(12f)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        }
        button.addView(iconView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        root.addView(button, FrameLayout.LayoutParams(buttonSizePx, buttonSizePx))

        // Touch listener for dragging and snapping
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        root.setOnTouchListener { view, event ->
            val curParams = windowLayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = curParams.x
                    initialY = curParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    button.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        curParams.x = initialX + dx
                        curParams.y = initialY + dy
                        windowManager.updateViewLayout(view, curParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    val totalDistance = hypot(
                        (event.rawX - initialTouchX).toDouble(),
                        (event.rawY - initialTouchY).toDouble()
                    )
                    if (totalDistance < touchSlop) {
                        // Click action!
                        triggerScreenshot()
                    } else {
                        // Drag released - Snap to edge (left or right)
                        snapToScreenEdge(curParams, view, buttonSizePx)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(root, params)
            floatingView = root
        } catch (e: Exception) {
            Log.e(TAG, "Error adding floating view", e)
        }
    }

    private fun snapToScreenEdge(params: WindowManager.LayoutParams, view: View, buttonWidth: Int) {
        val screenWidth = getScreenWidth()
        val targetX = if (params.x + buttonWidth / 2 < screenWidth / 2) {
            dpToPx(12f) // Snap to left
        } else {
            screenWidth - buttonWidth - dpToPx(12f) // Snap to right
        }

        val startX = params.x
        val animator = ValueAnimator.ofInt(startX, targetX)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            params.x = anim.animatedValue as Int
            try {
                windowManager.updateViewLayout(view, params)
            } catch (ignored: Exception) {}
        }
        animator.start()
    }

    fun triggerScreenshot() {
        if (isCapturing) return
        val captureManager = screenCaptureManager
        if (captureManager == null) {
            Toast.makeText(this, "Screen capture not ready. Open SnapFloat to enable.", Toast.LENGTH_SHORT).show()
            return
        }

        isCapturing = true
        val settings = settingsRepository.settings.value

        serviceScope.launch {
            try {
                // Handle optional countdown delay
                if (settings.countdownDelaySeconds > 0) {
                    for (i in settings.countdownDelaySeconds downTo 1) {
                        Toast.makeText(this@FloatingCaptureService, "Capturing in $i...", Toast.LENGTH_SHORT).show()
                        delay(1000)
                    }
                }

                // Hide floating window before capture so it doesn't appear in the screenshot
                if (settings.hideDuringCapture) {
                    floatingView?.visibility = View.INVISIBLE
                    // Wait 80ms for WindowManager to update surface composition
                    delay(80)
                }

                // Capture screen frame
                val bitmap = captureManager.captureScreen()

                // Restore floating window visibility
                floatingView?.visibility = View.VISIBLE

                if (bitmap != null) {
                    handleCaptureSuccess(bitmap, settings)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingCaptureService, "Capture failed or timed out", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during screen capture", e)
                floatingView?.visibility = View.VISIBLE
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingCaptureService, "Capture error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isCapturing = false
            }
        }
    }

    private suspend fun handleCaptureSuccess(bitmap: Bitmap, settings: FloatingSettings) {
        // Haptic Feedback
        if (settings.vibrateOnCapture) {
            triggerVibration()
        }

        // Save Bitmap to MediaStore / Storage
        val uri = screenshotRepository.saveBitmap(bitmap)

        withContext(Dispatchers.Main) {
            if (uri != null) {
                if (settings.showFloatingPreview) {
                    showQuickPreviewOverlay(bitmap, uri)
                } else {
                    Toast.makeText(this@FloatingCaptureService, "Screenshot saved to Pictures/SnapFloat", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@FloatingCaptureService, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error", e)
        }
    }

    private fun showQuickPreviewOverlay(bitmap: Bitmap, uri: Uri) {
        removePreviewOverlay()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val previewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(48f)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(16f), dpToPx(8f))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B")) // Slate 800
                cornerRadius = dpToPx(24f).toFloat()
                setStroke(dpToPx(1.5f), Color.parseColor("#38BDF8"))
            }
            background = bg
            elevation = dpToPx(12f).toFloat()
        }

        // Mini preview image
        val thumbnailView = ImageView(this).apply {
            val thumbWidth = dpToPx(36f)
            val thumbHeight = dpToPx(48f)
            layoutParams = LinearLayout.LayoutParams(thumbWidth, thumbHeight).apply {
                marginEnd = dpToPx(12f)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            val cornerBg = GradientDrawable().apply {
                cornerRadius = dpToPx(6f).toFloat()
            }
            background = cornerBg
            clipToOutline = true
        }
        card.addView(thumbnailView)

        // Text & Share Button
        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "Screenshot Saved"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subText = TextView(this).apply {
            text = "Tap to share or view"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }
        textLayout.addView(titleText)
        textLayout.addView(subText)
        card.addView(textLayout)

        // Share Icon
        val shareIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_share)
            setColorFilter(Color.parseColor("#00E5FF"))
            val iconPadding = dpToPx(8f)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            layoutParams = LinearLayout.LayoutParams(dpToPx(36f), dpToPx(36f)).apply {
                marginStart = dpToPx(16f)
            }
        }
        card.addView(shareIcon)

        card.setOnClickListener {
            // Share screenshot
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Screenshot").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            removePreviewOverlay()
        }

        try {
            windowManager.addView(card, previewParams)
            previewOverlayView = card
            // Auto dismiss preview after 3.5 seconds
            mainHandler.postDelayed({
                removePreviewOverlay()
            }, 3500)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing preview overlay", e)
        }
    }

    private fun removePreviewOverlay() {
        previewOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {}
            previewOverlayView = null
        }
    }

    private fun stopFloatingService() {
        removeFloatingWidget()
        removePreviewOverlay()
        screenCaptureManager?.stop()
        screenCaptureManager = null
        _isServiceRunning.value = false
        _hasActiveProjection.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeFloatingWidget() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {}
            floatingView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingWidget()
        removePreviewOverlay()
        screenCaptureManager?.stop()
        _isServiceRunning.value = false
        _hasActiveProjection.value = false
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val captureIntent = Intent(this, FloatingCaptureService::class.java).apply {
            action = ACTION_CAPTURE
        }
        val capturePendingIntent = PendingIntent.getService(
            this,
            1,
            captureIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FloatingCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnapFloat is Active")
            .setContentText("Tap floating button or notification to snap screenshot")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_camera, "Capture Now", capturePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SnapFloat Quick Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground notification while SnapFloat floating button is enabled"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels
        }
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.heightPixels
        }
    }

    companion object {
        const val TAG = "FloatingCaptureService"
        const val CHANNEL_ID = "snapfloat_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_CAPTURE = "com.example.service.ACTION_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _hasActiveProjection = MutableStateFlow(false)
        val hasActiveProjection: StateFlow<Boolean> = _hasActiveProjection.asStateFlow()
    }
}
