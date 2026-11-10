package com.example.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.FloatingSettings
import com.example.data.model.ScreenshotItem
import com.example.data.repository.ScreenshotRepository
import com.example.data.repository.SettingsRepository
import com.example.service.FloatingCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val screenshotRepository = ScreenshotRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    val isServiceRunning: StateFlow<Boolean> = FloatingCaptureService.isServiceRunning
    val hasActiveProjection: StateFlow<Boolean> = FloatingCaptureService.hasActiveProjection
    val settings: StateFlow<FloatingSettings> = settingsRepository.settings

    val screenshots: StateFlow<List<ScreenshotItem>> = screenshotRepository.screenshots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedScreenshot = MutableStateFlow<ScreenshotItem?>(null)
    val selectedScreenshot: StateFlow<ScreenshotItem?> = _selectedScreenshot.asStateFlow()

    private val _isOverlayGranted = MutableStateFlow(false)
    val isOverlayGranted: StateFlow<Boolean> = _isOverlayGranted.asStateFlow()

    private val _isNotificationGranted = MutableStateFlow(false)
    val isNotificationGranted: StateFlow<Boolean> = _isNotificationGranted.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            screenshotRepository.loadScreenshots()
        }
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        _isOverlayGranted.value = Settings.canDrawOverlays(context)

        _isNotificationGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun updateSettings(newSettings: FloatingSettings) {
        settingsRepository.updateSettings(newSettings)
    }

    fun selectScreenshot(item: ScreenshotItem?) {
        _selectedScreenshot.value = item
    }

    fun deleteScreenshot(item: ScreenshotItem) {
        viewModelScope.launch {
            screenshotRepository.deleteScreenshot(item)
            if (_selectedScreenshot.value?.id == item.id) {
                _selectedScreenshot.value = null
            }
        }
    }

    fun stopFloatingService(context: Context) {
        val intent = Intent(context, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun triggerQuickCapture(context: Context) {
        val intent = Intent(context, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_CAPTURE
        }
        context.startService(intent)
    }

    fun getOverlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }
}
