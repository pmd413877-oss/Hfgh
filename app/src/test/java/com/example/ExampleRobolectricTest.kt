package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.FloatingSettings
import com.example.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches SnapFloat`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SnapFloat", appName)
    }

    @Test
    fun `settings repository loads default values and updates correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository.getInstance(context)

        val initial = repo.loadSettings()
        assertNotNull(initial)
        assertEquals(56, initial.buttonSizeDp)
        assertTrue(initial.hideDuringCapture)

        val updated = initial.copy(buttonSizeDp = 64, opacityPercent = 75, hideDuringCapture = false)
        repo.updateSettings(updated)

        val reloaded = repo.loadSettings()
        assertEquals(64, reloaded.buttonSizeDp)
        assertEquals(75, reloaded.opacityPercent)
        assertEquals(false, reloaded.hideDuringCapture)
    }
}
