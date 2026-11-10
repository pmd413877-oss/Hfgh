package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.FloatingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<FloatingSettings> = _settings.asStateFlow()

    fun loadSettings(): FloatingSettings {
        return FloatingSettings(
            buttonSizeDp = prefs.getInt(KEY_BUTTON_SIZE, 56),
            opacityPercent = prefs.getInt(KEY_OPACITY, 90),
            hideDuringCapture = prefs.getBoolean(KEY_HIDE_DURING_CAPTURE, true),
            vibrateOnCapture = prefs.getBoolean(KEY_VIBRATE_ON_CAPTURE, true),
            showFloatingPreview = prefs.getBoolean(KEY_SHOW_PREVIEW, true),
            countdownDelaySeconds = prefs.getInt(KEY_COUNTDOWN_DELAY, 0)
        )
    }

    fun updateSettings(newSettings: FloatingSettings) {
        prefs.edit().apply {
            putInt(KEY_BUTTON_SIZE, newSettings.buttonSizeDp)
            putInt(KEY_OPACITY, newSettings.opacityPercent)
            putBoolean(KEY_HIDE_DURING_CAPTURE, newSettings.hideDuringCapture)
            putBoolean(KEY_VIBRATE_ON_CAPTURE, newSettings.vibrateOnCapture)
            putBoolean(KEY_SHOW_PREVIEW, newSettings.showFloatingPreview)
            putInt(KEY_COUNTDOWN_DELAY, newSettings.countdownDelaySeconds)
            apply()
        }
        _settings.value = newSettings
    }

    companion object {
        private const val PREFS_NAME = "snapfloat_settings"
        private const val KEY_BUTTON_SIZE = "button_size"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_HIDE_DURING_CAPTURE = "hide_during_capture"
        private const val KEY_VIBRATE_ON_CAPTURE = "vibrate_on_capture"
        private const val KEY_SHOW_PREVIEW = "show_preview"
        private const val KEY_COUNTDOWN_DELAY = "countdown_delay"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }
}
