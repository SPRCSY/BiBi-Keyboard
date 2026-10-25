package com.brycewg.asrkb.asr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class QnnBuildDefaultsTest {
    @Test
    fun `first QNN launch chooses offline inference and silence stop once`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = Prefs(app)
        prefs.backupAsrEnabled = true
        QnnSenseVoiceRuntime.applyFirstRunDefaults(app)
        assertEquals(AsrVendor.SenseVoice, prefs.asrVendor)
        assertEquals(Prefs.RecordingAutoStopMode.SILENCE, prefs.recordingAutoStopMode)
        assertTrue(isVadAutoStopEnabled(app, prefs))
        assertTrue(prefs.svPseudoStreamEnabled)
        assertFalse(prefs.backupAsrEnabled)
        prefs.recordingAutoStopMode = Prefs.RecordingAutoStopMode.MANUAL
        QnnSenseVoiceRuntime.applyFirstRunDefaults(app)
        assertEquals(Prefs.RecordingAutoStopMode.MANUAL, prefs.recordingAutoStopMode)
    }
}
