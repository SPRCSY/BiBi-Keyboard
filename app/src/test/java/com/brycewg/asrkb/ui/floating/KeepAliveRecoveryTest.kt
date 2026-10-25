package com.brycewg.asrkb.ui.floating

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KeepAliveRecoveryTest {
    private lateinit var app: Application
    private var controller: ServiceController<FloatingKeepAliveService>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs(app).floatingKeepAliveEnabled = false
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    @Test
    fun `system null intent restores foreground notification only when opted in`() {
        Prefs(app).floatingKeepAliveEnabled = true
        val service = createService()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(FloatingKeepAliveService.isRunning)
        assertNotNull(shadowOf(service).lastForegroundNotification)

        Prefs(app).floatingKeepAliveEnabled = false
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        assertFalse(FloatingKeepAliveService.isRunning)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `system recreation with disabled preference never promotes service`() {
        val service = createService()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(FloatingKeepAliveService.isRunning)
        assertNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `lifecycle recovery respects preference and authenticates service request`() {
        KeepAliveRecovery.ensureRunning(app, "ime_shown")
        assertNull(shadowOf(app).nextStartedService)
        Prefs(app).floatingKeepAliveEnabled = true
        KeepAliveRecovery.ensureRunning(app, "ime_shown")
        val request = shadowOf(app).nextStartedService
        assertEquals(FloatingKeepAliveService.ACTION_START, request.action)
        assertEquals(
            FloatingKeepAliveService.getOrCreateCallerToken(app),
            request.getStringExtra(FloatingKeepAliveService.EXTRA_CALLER_TOKEN)
        )
    }

    @Test
    fun `running foreground service is not started again by lifecycle callbacks`() {
        Prefs(app).floatingKeepAliveEnabled = true
        val service = createService()
        service.onStartCommand(null, 0, 1)
        KeepAliveRecovery.ensureRunning(app, "activity_resumed")
        KeepAliveRecovery.ensureRunning(app, "ime_shown")
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `background start denial does not crash input method or claim to be running`() {
        Prefs(app).floatingKeepAliveEnabled = true
        val restrictedContext = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("Foreground service start not allowed")
        }
        KeepAliveRecovery.ensureRunning(restrictedContext, "ime_shown")
        assertFalse(FloatingKeepAliveService.isRunning)
    }

    private fun createService(): FloatingKeepAliveService {
        val created = Robolectric.buildService(FloatingKeepAliveService::class.java).create()
        controller = created
        return created.get()
    }
}
