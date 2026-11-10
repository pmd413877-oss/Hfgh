package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GuideScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GuideHeader()
        }

        item {
            GuideSectionCard(
                icon = Icons.Default.Security,
                title = "1. Android 14/15 Constraints",
                summary = "Strict Foreground Service type & MediaProjection Callback rules",
                initiallyExpanded = true
            ) {
                Text(
                    text = "Starting in Android 14 (API 34) and Android 15 (API 35), Google introduced strict foreground service security barriers for screen capture:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint(
                    title = "Foreground Service Type:",
                    body = "The service MUST declare android:foregroundServiceType=\"mediaProjection\" in AndroidManifest.xml and request FOREGROUND_SERVICE_MEDIA_PROJECTION permission."
                )
                BulletPoint(
                    title = "Strict Start Sequence:",
                    body = "ServiceCompat.startForeground() with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION must be called BEFORE invoking mediaProjectionManager.getMediaProjection(resultCode, data)."
                )
                BulletPoint(
                    title = "Mandatory Callback Registration:",
                    body = "mediaProjection.registerCallback(callback, handler) MUST be registered before calling createVirtualDisplay(). Calling createVirtualDisplay() without a callback throws an IllegalStateException on Android 14+."
                )
                Spacer(modifier = Modifier.height(8.dp))
                CodeSnippet(
                    """// AndroidManifest.xml
<service
    android:name=".service.FloatingCaptureService"
    android:foregroundServiceType="mediaProjection" />

// FloatingCaptureService.kt
ServiceCompat.startForeground(
    this, NOTIFICATION_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
)
val projection = projectionManager.getMediaProjection(code, data)
projection.registerCallback(object : MediaProjection.Callback() {
    override fun onStop() { stopSelf() }
}, Handler(Looper.getMainLooper()))"""
                )
            }
        }

        item {
            GuideSectionCard(
                icon = Icons.Default.Layers,
                title = "2. Persistent Floating Window",
                summary = "TYPE_APPLICATION_OVERLAY, touch gestures & edge snapping",
                initiallyExpanded = false
            ) {
                Text(
                    text = "The floating window uses WindowManager with TYPE_APPLICATION_OVERLAY. This keeps the button accessible across all apps without blocking touches outside its boundaries.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint(
                    title = "Touch Slop & Click vs Drag:",
                    body = "Tracks hypot(dx, dy) on ACTION_MOVE. If total movement is under ViewConfiguration.get(context).scaledTouchSlop when ACTION_UP fires, it is handled as a Click (capture action). Otherwise, it is treated as a drag."
                )
                BulletPoint(
                    title = "Smooth Magnetic Snap:",
                    body = "On drag release, a ValueAnimator smoothly moves the button to the left or right screen border (params.x) for a clean assistive touch feel."
                )
                Spacer(modifier = Modifier.height(8.dp))
                CodeSnippet(
                    """val params = WindowManager.LayoutParams(
    buttonSizePx, buttonSizePx,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
)
// Snap to nearest screen edge on ACTION_UP
val targetX = if (params.x + width/2 < screenWidth/2) 16 else screenWidth - width - 16
ValueAnimator.ofInt(params.x, targetX).apply {
    duration = 200
    addUpdateListener { windowManager.updateViewLayout(view, params) }
    start()
}"""
                )
            }
        }

        item {
            GuideSectionCard(
                icon = Icons.Default.TouchApp,
                title = "3. Single-Shot Screen Capture",
                summary = "ImageReader, VirtualDisplay & Row Stride Byte Buffer handling",
                initiallyExpanded = false
            ) {
                Text(
                    text = "Continuous VirtualDisplay streaming wastes GPU and battery. SnapFloat creates the VirtualDisplay and ImageReader on-demand for a single frame, then immediately releases them.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint(
                    title = "Row Stride Padding Fix:",
                    body = "Hardware display surfaces often pad image rows to 64-byte or 128-byte alignments. Raw buffers have rowStride >= width * 4. We calculate rowPadding = rowStride - pixelStride * width to avoid slanted or distorted bitmaps."
                )
                BulletPoint(
                    title = "Zero Button Visibility:",
                    body = "Before taking the snapshot, the floating button view is momentarily hidden (visibility = INVISIBLE) with an 80ms delay to ensure the button itself is not captured."
                )
                Spacer(modifier = Modifier.height(8.dp))
                CodeSnippet(
                    """val planes = image.planes
val buffer = planes[0].buffer
val pixelStride = planes[0].pixelStride
val rowStride = planes[0].rowStride
val rowPadding = rowStride - pixelStride * width

val bitmap = Bitmap.createBitmap(
    width + rowPadding / pixelStride, height,
    Bitmap.Config.ARGB_8888
)
bitmap.copyPixelsFromBuffer(buffer)
val finalBitmap = if (rowPadding > 0) {
    Bitmap.createBitmap(bitmap, 0, 0, width, height)
} else bitmap"""
                )
            }
        }

        item {
            GuideSectionCard(
                icon = Icons.Default.Warning,
                title = "4. Permissions & Friction Reduction",
                summary = "Smooth permission requests for Overlay, Notifications, & Capture",
                initiallyExpanded = false
            ) {
                Text(
                    text = "To minimize user friction, permissions are verified sequentially with explanatory guidance:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint(
                    title = "Overlay Permission:",
                    body = "Check Settings.canDrawOverlays(context). If not granted, open Settings.ACTION_MANAGE_OVERLAY_PERMISSION directly to the app's package URI."
                )
                BulletPoint(
                    title = "Notification Permission (API 33+):",
                    body = "POST_NOTIFICATIONS ensures the foreground service notification remains active and provides quick capture/stop actions."
                )
                BulletPoint(
                    title = "MediaProjection Intent:",
                    body = "MediaProjectionManager.createScreenCaptureIntent() is requested via ActivityResultContracts.StartActivityForResult(). In Android 14+, users can select 'Entire screen' or 'Single app'."
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GuideHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Architecture & Implementation Guide",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Complete blueprint for building high-performance floating capture tools compliant with modern Android constraints.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            )
        }
    }
}

@Composable
private fun GuideSectionCard(
    icon: ImageVector,
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun BulletPoint(title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text("• ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CodeSnippet(code: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        color = Color(0xFF0F172A)
    ) {
        Text(
            text = code,
            color = Color(0xFF38BDF8),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}
