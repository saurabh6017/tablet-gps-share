package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.data.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.pow

/**
 * Manages hardware barometric pressure (`Sensor.TYPE_PRESSURE`), fetches and caches
 * local sea-level pressure ($QNH$ in hPa) for 30 minutes from keyless weather/aviation APIs,
 * and computes calibrated Mean Sea Level (MSL) altitude using the international hypsometric formula.
 */
class BarometerQnhManager(
    context: Context,
    private val scope: CoroutineScope
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val pressureSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    @Volatile
    private var isListening: Boolean = false

    @Volatile
    private var latestMeasuredPressureHpa: Float? = null

    @Volatile
    private var cachedQnhHpa: Float? = null

    @Volatile
    private var lastQnhFetchTimeMs: Long = 0L

    @Volatile
    private var lastQnhLat: Double = Double.NaN

    @Volatile
    private var lastQnhLon: Double = Double.NaN

    @Volatile
    private var lastNetworkFetchFailed: Boolean = false

    private val isFetchingQnh = AtomicBoolean(false)
    private var activeFetchJob: Job? = null

    fun hasBarometerHardware(): Boolean = pressureSensor != null

    /**
     * Starts listening to `Sensor.TYPE_PRESSURE` when "Improve altitude measurement (experimental)" is enabled.
     */
    fun start() {
        if (pressureSensor == null) {
            isListening = false
            TelemetryRepository.updateTransmitterState {
                it.copy(
                    barometerAvailable = false,
                    barometerUsingFallbackGps = true,
                    barometerStatusBadge = FALLBACK_BADGE_TEXT,
                    measuredPressureHpa = null
                )
            }
            return
        }

        if (!isListening) {
            val registered = try {
                sensorManager?.registerListener(
                    this,
                    pressureSensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                ) ?: false
            } catch (_: Exception) {
                false
            }

            isListening = registered
            if (!registered) {
                TelemetryRepository.updateTransmitterState {
                    it.copy(
                        barometerAvailable = false,
                        barometerUsingFallbackGps = true,
                        barometerStatusBadge = FALLBACK_BADGE_TEXT,
                        measuredPressureHpa = null
                    )
                }
            } else {
                TelemetryRepository.updateTransmitterState {
                    it.copy(
                        barometerAvailable = true,
                        barometerUsingFallbackGps = cachedQnhHpa == null,
                        barometerStatusBadge = buildStatusBadge(
                            hasSensor = true,
                            pressureHpa = latestMeasuredPressureHpa,
                            qnhHpa = cachedQnhHpa,
                            networkFailed = lastNetworkFetchFailed
                        )
                    )
                }
            }
        }
    }

    /**
     * Unregisters the hardware barometer listener when experimental altitude mode or server stops.
     */
    fun stop() {
        if (isListening) {
            try {
                sensorManager?.unregisterListener(this)
            } catch (_: Exception) {
            }
            isListening = false
        }
        activeFetchJob?.cancel()
        activeFetchJob = null
        isFetchingQnh.set(false)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PRESSURE) return
        val rawHpa = event.values.firstOrNull() ?: return
        if (rawHpa <= 100f || rawHpa >= 1200f) return

        val prev = latestMeasuredPressureHpa
        val smoothed = if (prev == null) rawHpa else (prev * 0.75f + rawHpa * 0.25f)
        latestMeasuredPressureHpa = smoothed

        TelemetryRepository.updateTransmitterState {
            it.copy(
                barometerAvailable = true,
                measuredPressureHpa = smoothed,
                barometerUsingFallbackGps = cachedQnhHpa == null,
                barometerStatusBadge = buildStatusBadge(
                    hasSensor = true,
                    pressureHpa = smoothed,
                    qnhHpa = cachedQnhHpa,
                    networkFailed = lastNetworkFetchFailed
                )
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Evaluates current GPS coordinates, triggers a 30-minute cached QNH fetch if needed,
     * and returns a calibrated barometric MSL altitude (or null to gracefully fall back to GPS altitude).
     */
    fun resolveAltitudeCalibration(
        latitude: Double,
        longitude: Double
    ): BarometricCalibrationResult {
        if (!hasBarometerHardware() || !isListening) {
            TelemetryRepository.updateTransmitterState {
                it.copy(
                    barometerAvailable = false,
                    barometerUsingFallbackGps = true,
                    barometerStatusBadge = FALLBACK_BADGE_TEXT
                )
            }
            return BarometricCalibrationResult(
                calibratedAltitudeMeters = null,
                measuredPressureHpa = null,
                qnhHpa = null,
                usingFallbackGps = true,
                statusBadge = FALLBACK_BADGE_TEXT
            )
        }

        triggerQnhFetchIfNeeded(latitude, longitude)

        val pressure = latestMeasuredPressureHpa
        val qnh = cachedQnhHpa

        return if (pressure != null && qnh != null && qnh > 800f) {
            val calibratedAlt = computeHypsometricAltitude(
                measuredPressureHpa = pressure.toDouble(),
                qnhHpa = qnh.toDouble()
            )
            val badge = String.format(
                Locale.US,
                "Barometer active (%.1f hPa • QNH %.1f hPa)",
                pressure,
                qnh
            )
            TelemetryRepository.updateTransmitterState {
                it.copy(
                    barometerAvailable = true,
                    barometerUsingFallbackGps = false,
                    barometerStatusBadge = badge,
                    measuredPressureHpa = pressure,
                    cachedQnhHpa = qnh
                )
            }
            BarometricCalibrationResult(
                calibratedAltitudeMeters = calibratedAlt,
                measuredPressureHpa = pressure,
                qnhHpa = qnh,
                usingFallbackGps = false,
                statusBadge = badge
            )
        } else {
            TelemetryRepository.updateTransmitterState {
                it.copy(
                    barometerUsingFallbackGps = true,
                    barometerStatusBadge = FALLBACK_BADGE_TEXT
                )
            }
            BarometricCalibrationResult(
                calibratedAltitudeMeters = null,
                measuredPressureHpa = pressure,
                qnhHpa = qnh,
                usingFallbackGps = true,
                statusBadge = FALLBACK_BADGE_TEXT
            )
        }
    }

    private fun triggerQnhFetchIfNeeded(latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        val cacheValid = cachedQnhHpa != null &&
            (now - lastQnhFetchTimeMs) < QNH_CACHE_DURATION_MS &&
            !lastQnhLat.isNaN() &&
            !lastQnhLon.isNaN() &&
            abs(latitude - lastQnhLat) < 0.25 &&
            abs(longitude - lastQnhLon) < 0.25

        if (cacheValid) return
        if (lastNetworkFetchFailed && (now - lastQnhFetchTimeMs) < RETRY_COOLDOWN_MS) return
        if (!isFetchingQnh.compareAndSet(false, true)) return

        activeFetchJob = scope.launch(Dispatchers.IO) {
            try {
                val fetchedQnh = fetchOpenMeteoQnhHpa(latitude, longitude)
                    ?: fetchAviationWeatherMetarQnhHpa(latitude, longitude)

                val finishTime = System.currentTimeMillis()
                lastQnhFetchTimeMs = finishTime
                if (fetchedQnh != null && fetchedQnh in 850f..1090f) {
                    cachedQnhHpa = fetchedQnh
                    lastQnhLat = latitude
                    lastQnhLon = longitude
                    lastNetworkFetchFailed = false

                    TelemetryRepository.updateTransmitterState {
                        it.copy(
                            cachedQnhHpa = fetchedQnh,
                            qnhLastUpdatedMs = finishTime,
                            barometerUsingFallbackGps = latestMeasuredPressureHpa == null,
                            barometerStatusBadge = buildStatusBadge(
                                hasSensor = hasBarometerHardware(),
                                pressureHpa = latestMeasuredPressureHpa,
                                qnhHpa = fetchedQnh,
                                networkFailed = false
                            )
                        )
                    }
                } else {
                    lastNetworkFetchFailed = true
                    if (cachedQnhHpa == null) {
                        TelemetryRepository.updateTransmitterState {
                            it.copy(
                                barometerUsingFallbackGps = true,
                                barometerStatusBadge = FALLBACK_BADGE_TEXT
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                lastNetworkFetchFailed = true
                lastQnhFetchTimeMs = System.currentTimeMillis()
                if (cachedQnhHpa == null) {
                    TelemetryRepository.updateTransmitterState {
                        it.copy(
                            barometerUsingFallbackGps = true,
                            barometerStatusBadge = FALLBACK_BADGE_TEXT
                        )
                    }
                }
            } finally {
                isFetchingQnh.set(false)
            }
        }
    }

    /**
     * Queries Open-Meteo's keyless forecast endpoint for current Mean Sea Level pressure (`pressure_msl`).
     */
    private fun fetchOpenMeteoQnhHpa(lat: Double, lon: Double): Float? {
        val urlStr = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=pressure_msl,surface_pressure",
            lat,
            lon
        )
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LocalNetGPS-Android/1.0")
            }
            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val root = JSONObject(body)
                val current = root.optJSONObject("current") ?: return null
                val msl = current.optDouble("pressure_msl", Double.NaN)
                if (!msl.isNaN() && msl > 800.0) {
                    msl.toFloat()
                } else {
                    val surf = current.optDouble("surface_pressure", Double.NaN)
                    if (!surf.isNaN() && surf > 800.0) surf.toFloat() else null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Backup keyless query to AviationWeather.gov METAR API for nearest station altimeter QNH (in hPa).
     */
    private fun fetchAviationWeatherMetarQnhHpa(lat: Double, lon: Double): Float? {
        val minLat = (lat - 0.6).coerceIn(-89.9, 89.9)
        val maxLat = (lat + 0.6).coerceIn(-89.9, 89.9)
        val minLon = (lon - 0.6).coerceIn(-179.9, 179.9)
        val maxLon = (lon + 0.6).coerceIn(-179.9, 179.9)
        val urlStr = String.format(
            Locale.US,
            "https://aviationweather.gov/api/data/metar?bbox=%.2f,%.2f,%.2f,%.2f&format=json",
            minLat,
            minLon,
            maxLat,
            maxLon
        )
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LocalNetGPS-Android/1.0")
            }
            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    val altim = first.optDouble("altim", Double.NaN)
                    if (!altim.isNaN() && altim > 800.0) altim.toFloat() else null
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildStatusBadge(
        hasSensor: Boolean,
        pressureHpa: Float?,
        qnhHpa: Float?,
        networkFailed: Boolean
    ): String {
        if (!hasSensor || (networkFailed && qnhHpa == null) || pressureHpa == null || qnhHpa == null) {
            return FALLBACK_BADGE_TEXT
        }
        return String.format(
            Locale.US,
            "Barometer active (%.1f hPa • QNH %.1f hPa)",
            pressureHpa,
            qnhHpa
        )
    }

    data class BarometricCalibrationResult(
        val calibratedAltitudeMeters: Double?,
        val measuredPressureHpa: Float?,
        val qnhHpa: Float?,
        val usingFallbackGps: Boolean,
        val statusBadge: String
    )

    companion object {
        const val FALLBACK_BADGE_TEXT = "Barometer unavailable (using GPS)"
        const val QNH_CACHE_DURATION_MS = 30L * 60L * 1000L // 30 minutes
        private const val RETRY_COOLDOWN_MS = 30_000L

        /**
         * Standard International Barometric Formula:
         * Altitude = 44330.0 * (1.0 - (measuredPressureHpa / qnhHpa)^0.190294957)
         */
        fun computeHypsometricAltitude(measuredPressureHpa: Double, qnhHpa: Double): Double {
            if (measuredPressureHpa <= 0.0 || qnhHpa <= 0.0) return 0.0
            return 44330.0 * (1.0 - (measuredPressureHpa / qnhHpa).pow(0.190294957))
        }
    }
}
