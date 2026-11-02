package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TelemetryRepository
import com.example.model.AppOperatingMode
import com.example.model.GpsTelemetryPayload
import com.example.model.NmeaCodec
import com.example.sensor.BarometerQnhManager
import com.example.viewmodel.LocalNetGpsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LocalNetGPS", appName)
    }

    @Test
    fun `serialize and parse compact NDJSON telemetry frame with barometric metadata`() {
        val sample = GpsTelemetryPayload(
            latitude = 37.422065,
            longitude = -122.084089,
            altitude = 112.4,
            speedMetersPerSec = 14.2f,
            bearingDegrees = 274.5f,
            horizontalAccuracyMeters = 1.9f,
            verticalAccuracyMeters = 1.2f,
            satellitesInUse = 14,
            sequenceNumber = 42L,
            providerName = "fused+baro",
            isBarometricAltitude = true,
            barometricPressureHpa = 1000.0f,
            qnhReferenceHpa = 1013.25f
        )
        val wireJson = sample.toCompactJsonLine()
        val parsed = GpsTelemetryPayload.parseWireFrame(wireJson)

        assertNotNull(parsed)
        assertEquals(37.422065, parsed!!.latitude, 1e-6)
        assertEquals(-122.084089, parsed.longitude, 1e-6)
        assertEquals(112.4, parsed.altitude, 1e-3)
        assertEquals(14.2f, parsed.speedMetersPerSec, 1e-3f)
        assertEquals(274.5f, parsed.bearingDegrees, 1e-3f)
        assertEquals(42L, parsed.sequenceNumber)
        assertTrue(parsed.isBarometricAltitude)
        assertEquals(1000.0f, parsed.barometricPressureHpa!!, 1e-2f)
        assertEquals(1013.25f, parsed.qnhReferenceHpa!!, 1e-2f)
        assertTrue(parsed.nmeaGga.startsWith("\$GPGGA,"))
        assertTrue(parsed.nmeaRmc.startsWith("\$GPRMC,"))
    }

    @Test
    fun `generate and parse NMEA 0183 GPGGA sentence with valid XOR checksum`() {
        val sample = GpsTelemetryPayload(
            latitude = 37.774929,
            longitude = -122.419416,
            altitude = 18.4,
            speedMetersPerSec = 8.5f,
            bearingDegrees = 120.0f,
            horizontalAccuracyMeters = 2.5f,
            satellitesInUse = 12,
            sequenceNumber = 7L
        )
        val gga = NmeaCodec.buildGpgga(sample)
        assertTrue(gga.contains("*"))

        val parsedFromNmea = GpsTelemetryPayload.parseWireFrame(gga, fallbackSeq = 7L)
        assertNotNull(parsedFromNmea)
        assertEquals(37.774929, parsedFromNmea!!.latitude, 1e-3)
        assertEquals(-122.419416, parsedFromNmea.longitude, 1e-3)
        assertEquals(18.4, parsedFromNmea.altitude, 0.2)
    }

    @Test
    fun `compute hypsometric altitude from measured pressure and QNH reference`() {
        // At sea level where measuredPressure == qnhHpa, altitude is 0.0m
        val seaLevelAlt = BarometerQnhManager.computeHypsometricAltitude(1013.25, 1013.25)
        assertEquals(0.0, seaLevelAlt, 1e-4)

        // At 1000.0 hPa with standard 1013.25 hPa QNH, altitude is ~110.88m
        val altAt1000Hpa = BarometerQnhManager.computeHypsometricAltitude(1000.0, 1013.25)
        assertEquals(110.88, altAt1000Hpa, 0.5)
    }

    @Test
    fun `session mode lock isolates active Transmitter or Receiver until stopped`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = LocalNetGpsViewModel(app)

        viewModel.stopTransmitterServer()
        viewModel.stopReceiverClient()
        assertFalse(viewModel.isAnySessionLocked())

        // Start GPS Server -> locks to TRANSMITTER and blocks switching to RECEIVER
        viewModel.startTransmitterServer()
        assertTrue(viewModel.isTransmitterSessionActive())
        assertTrue(viewModel.isAnySessionLocked())
        assertEquals(AppOperatingMode.TRANSMITTER, viewModel.uiConfig.value.operatingMode)

        viewModel.selectOperatingMode(AppOperatingMode.RECEIVER)
        assertEquals(AppOperatingMode.TRANSMITTER, viewModel.uiConfig.value.operatingMode)

        // Stop GPS Server -> unlocks mode switching
        viewModel.stopTransmitterServer()
        assertFalse(viewModel.isAnySessionLocked())

        viewModel.selectOperatingMode(AppOperatingMode.RECEIVER)
        assertEquals(AppOperatingMode.RECEIVER, viewModel.uiConfig.value.operatingMode)

        // Start Mock Receiver -> locks to RECEIVER and blocks switching to TRANSMITTER
        viewModel.startReceiverClient()
        assertTrue(viewModel.isReceiverSessionActive())
        assertTrue(viewModel.isAnySessionLocked())

        viewModel.selectOperatingMode(AppOperatingMode.TRANSMITTER)
        assertEquals(AppOperatingMode.RECEIVER, viewModel.uiConfig.value.operatingMode)

        viewModel.stopReceiverClient()
        assertFalse(viewModel.isAnySessionLocked())
    }

    @Test
    fun `experimental altitude toggle falls back gracefully when barometer sensor is unavailable`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = LocalNetGpsViewModel(app)

        viewModel.setImproveAltitudeExperimental(true)
        val txState = TelemetryRepository.transmitterState.value
        assertTrue(txState.improveAltitudeExperimental)
        assertEquals(BarometerQnhManager.FALLBACK_BADGE_TEXT, txState.barometerStatusBadge)
    }
}
