package com.example.model

import android.location.Location
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

enum class AppOperatingMode {
    TRANSMITTER,
    RECEIVER
}

enum class ServerEngineStatus(val label: String) {
    IDLE("STANDBY"),
    STARTING("STARTING SERVER"),
    ACTIVE_BROADCASTING("LIVE BROADCASTING"),
    ERROR("SERVER FAULT")
}

enum class ReceiverEngineStatus(val label: String) {
    IDLE("STANDBY"),
    SEARCHING_LAN("SEARCHING NETWORK"),
    CONNECTING("CONNECTING TCP"),
    CONNECTED_STREAMING("RECEIVING & INJECTING"),
    RECONNECTING("CONNECTION LOST • RETRYING"),
    MOCK_PERMISSION_REQUIRED("MOCK LOCATION APP REQUIRED"),
    ERROR("RECEIVER FAULT")
}

data class LocalNetworkInterfaceInfo(
    val interfaceName: String,
    val ipv4Address: String,
    val broadcastAddress: String,
    val isHotspotOrTether: Boolean
)

data class DiscoveredServerNode(
    val ipAddress: String,
    val tcpPort: Int,
    val deviceName: String,
    val lastSeenMs: Long,
    val latestSequence: Long
)

data class PacketLogEntry(
    val id: Long,
    val timestampMs: Long,
    val protocolTag: String,
    val rawLine: String,
    val mockInjected: Boolean
)

/**
 * Unified high-precision GNSS payload carrying both rich JSON metrics and
 * standard NMEA-0183 ($GPGGA / $GPRMC) sentences for maximum interoperability.
 */
data class GpsTelemetryPayload(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedMetersPerSec: Float,
    val bearingDegrees: Float,
    val horizontalAccuracyMeters: Float,
    val verticalAccuracyMeters: Float = 2.5f,
    val speedAccuracyMps: Float = 0.2f,
    val bearingAccuracyDegrees: Float = 2.0f,
    val satellitesInUse: Int = 12,
    val timestampUtcMs: Long = System.currentTimeMillis(),
    val sequenceNumber: Long = 0L,
    val providerName: String = "fused",
    val isSimulatedTestPattern: Boolean = false,
    val isBarometricAltitude: Boolean = false,
    val barometricPressureHpa: Float? = null,
    val qnhReferenceHpa: Float? = null,
    val nmeaGga: String = "",
    val nmeaRmc: String = ""
) {
    val speedKmh: Float
        get() = speedMetersPerSec * 3.6f

    val speedKnots: Float
        get() = speedMetersPerSec * 1.943844f

    fun toCompactJsonLine(): String {
        val gga = nmeaGga.ifEmpty { NmeaCodec.buildGpgga(this) }
        val rmc = nmeaRmc.ifEmpty { NmeaCodec.buildGprmc(this) }
        val json = JSONObject().apply {
            put("type", "LOCALNETGPS_FIX")
            put("seq", sequenceNumber)
            put("lat", latitude)
            put("lon", longitude)
            put("alt", altitude)
            put("spd", speedMetersPerSec.toDouble())
            put("brg", bearingDegrees.toDouble())
            put("acc", horizontalAccuracyMeters.toDouble())
            put("vAcc", verticalAccuracyMeters.toDouble())
            put("sAcc", speedAccuracyMps.toDouble())
            put("bAcc", bearingAccuracyDegrees.toDouble())
            put("sats", satellitesInUse)
            put("ts", timestampUtcMs)
            put("prov", providerName)
            put("sim", isSimulatedTestPattern)
            put("baro", isBarometricAltitude)
            if (barometricPressureHpa != null) {
                put("pHpa", barometricPressureHpa.toDouble())
            }
            if (qnhReferenceHpa != null) {
                put("qnh", qnhReferenceHpa.toDouble())
            }
            put("gga", gga)
            put("rmc", rmc)
        }
        return json.toString()
    }

    companion object {
        fun fromAndroidLocation(
            location: Location,
            sequenceNumber: Long,
            satellitesOverride: Int? = null,
            isSimulated: Boolean = false,
            calibratedBaroAltitudeMeters: Double? = null,
            measuredPressureHpa: Float? = null,
            qnhReferenceHpa: Float? = null
        ): GpsTelemetryPayload {
            val satsFromExtras = location.extras?.getInt("satellites", 0) ?: 0
            val resolvedSats = when {
                satellitesOverride != null && satellitesOverride > 0 -> satellitesOverride
                satsFromExtras > 0 -> satsFromExtras
                else -> 11
            }
            val vAcc = if (calibratedBaroAltitudeMeters != null) {
                1.2f
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters
            } else {
                (location.accuracy * 1.25f).coerceAtLeast(1.5f)
            }
            val sAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond
            } else {
                0.15f
            }
            val bAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
                location.bearingAccuracyDegrees
            } else {
                1.5f
            }

            val resolvedAltitude = calibratedBaroAltitudeMeters ?: location.altitude
            val usingBaro = calibratedBaroAltitudeMeters != null

            val draft = GpsTelemetryPayload(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = resolvedAltitude,
                speedMetersPerSec = location.speed,
                bearingDegrees = location.bearing,
                horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy else 3.0f,
                verticalAccuracyMeters = vAcc,
                speedAccuracyMps = sAcc,
                bearingAccuracyDegrees = bAcc,
                satellitesInUse = resolvedSats,
                timestampUtcMs = if (location.time > 0L) location.time else System.currentTimeMillis(),
                sequenceNumber = sequenceNumber,
                providerName = if (usingBaro) "${location.provider ?: "fused"}+baro" else (location.provider ?: "fused"),
                isSimulatedTestPattern = isSimulated,
                isBarometricAltitude = usingBaro,
                barometricPressureHpa = measuredPressureHpa,
                qnhReferenceHpa = qnhReferenceHpa
            )
            return draft.copy(
                nmeaGga = NmeaCodec.buildGpgga(draft),
                nmeaRmc = NmeaCodec.buildGprmc(draft)
            )
        }

        /**
         * Parses either our compact NDJSON line or raw NMEA-0183 ($GPGGA / $GPRMC) lines.
         */
        fun parseWireFrame(line: String, fallbackSeq: Long = 0L): GpsTelemetryPayload? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null

            return try {
                if (trimmed.startsWith("{")) {
                    val obj = JSONObject(trimmed)
                    val lat = obj.optDouble("lat", Double.NaN)
                    val lon = obj.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) return null

                    val pHpa = if (obj.has("pHpa")) obj.optDouble("pHpa").toFloat() else null
                    val qnh = if (obj.has("qnh")) obj.optDouble("qnh").toFloat() else null

                    val draft = GpsTelemetryPayload(
                        latitude = lat,
                        longitude = lon,
                        altitude = obj.optDouble("alt", 0.0),
                        speedMetersPerSec = obj.optDouble("spd", 0.0).toFloat(),
                        bearingDegrees = obj.optDouble("brg", 0.0).toFloat(),
                        horizontalAccuracyMeters = obj.optDouble("acc", 3.5).toFloat(),
                        verticalAccuracyMeters = obj.optDouble("vAcc", 4.0).toFloat(),
                        speedAccuracyMps = obj.optDouble("sAcc", 0.2).toFloat(),
                        bearingAccuracyDegrees = obj.optDouble("bAcc", 2.0).toFloat(),
                        satellitesInUse = obj.optInt("sats", 10),
                        timestampUtcMs = obj.optLong("ts", System.currentTimeMillis()),
                        sequenceNumber = obj.optLong("seq", fallbackSeq),
                        providerName = obj.optString("prov", "lan_stream"),
                        isSimulatedTestPattern = obj.optBoolean("sim", false),
                        isBarometricAltitude = obj.optBoolean("baro", false),
                        barometricPressureHpa = pHpa,
                        qnhReferenceHpa = qnh,
                        nmeaGga = obj.optString("gga", ""),
                        nmeaRmc = obj.optString("rmc", "")
                    )
                    draft.copy(
                        nmeaGga = draft.nmeaGga.ifEmpty { NmeaCodec.buildGpgga(draft) },
                        nmeaRmc = draft.nmeaRmc.ifEmpty { NmeaCodec.buildGprmc(draft) }
                    )
                } else if (trimmed.startsWith("\$GP") || trimmed.startsWith("\$GN")) {
                    NmeaCodec.parseNmeaSentence(trimmed, fallbackSeq)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Generates and parses compliant NMEA-0183 sentences ($GPGGA and $GPRMC) with XOR checksums.
 */
object NmeaCodec {

    fun buildGpgga(payload: GpsTelemetryPayload): String {
        val timeFmt = SimpleDateFormat("HHmmss.SS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utcTimeStr = timeFmt.format(Date(payload.timestampUtcMs))
        val latNmea = toNmeaCoord(payload.latitude, isLatitude = true)
        val latHem = if (payload.latitude >= 0.0) "N" else "S"
        val lonNmea = toNmeaCoord(payload.longitude, isLatitude = false)
        val lonHem = if (payload.longitude >= 0.0) "E" else "W"
        val fixQuality = 1 // GPS Fix
        val sats = payload.satellitesInUse.coerceIn(3, 32)
        val hdop = String.format(Locale.US, "%.1f", (payload.horizontalAccuracyMeters / 2.5f).coerceIn(0.6f, 9.9f))
        val alt = String.format(Locale.US, "%.1f", payload.altitude)

        val body = "GPGGA,$utcTimeStr,$latNmea,$latHem,$lonNmea,$lonHem,$fixQuality,${String.format(Locale.US, "%02d", sats)},$hdop,$alt,M,0.0,M,,"
        return "\$$body*${computeChecksum(body)}"
    }

    fun buildGprmc(payload: GpsTelemetryPayload): String {
        val timeFmt = SimpleDateFormat("HHmmss.SS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateFmt = SimpleDateFormat("ddMMyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = Date(payload.timestampUtcMs)
        val utcTimeStr = timeFmt.format(date)
        val utcDateStr = dateFmt.format(date)
        val latNmea = toNmeaCoord(payload.latitude, isLatitude = true)
        val latHem = if (payload.latitude >= 0.0) "N" else "S"
        val lonNmea = toNmeaCoord(payload.longitude, isLatitude = false)
        val lonHem = if (payload.longitude >= 0.0) "E" else "W"
        val knots = String.format(Locale.US, "%.2f", payload.speedKnots)
        val course = String.format(Locale.US, "%.1f", payload.bearingDegrees)

        val body = "GPRMC,$utcTimeStr,A,$latNmea,$latHem,$lonNmea,$lonHem,$knots,$course,$utcDateStr,,,A"
        return "\$$body*${computeChecksum(body)}"
    }

    fun parseNmeaSentence(sentence: String, sequenceNumber: Long): GpsTelemetryPayload? {
        val clean = sentence.trim().removePrefix("$").substringBefore("*")
        val tokens = clean.split(",")
        if (tokens.size < 10) return null

        val type = tokens[0].uppercase(Locale.US)
        return when {
            type.endsWith("GGA") -> {
                val lat = fromNmeaCoord(tokens[2], tokens[3]) ?: return null
                val lon = fromNmeaCoord(tokens[4], tokens[5]) ?: return null
                val sats = tokens.getOrNull(7)?.toIntOrNull() ?: 8
                val hdop = tokens.getOrNull(8)?.toFloatOrNull() ?: 1.2f
                val alt = tokens.getOrNull(9)?.toDoubleOrNull() ?: 0.0
                GpsTelemetryPayload(
                    latitude = lat,
                    longitude = lon,
                    altitude = alt,
                    speedMetersPerSec = 0f,
                    bearingDegrees = 0f,
                    horizontalAccuracyMeters = (hdop * 2.5f).coerceAtLeast(1.5f),
                    satellitesInUse = sats,
                    timestampUtcMs = System.currentTimeMillis(),
                    sequenceNumber = sequenceNumber,
                    providerName = "nmea_gga",
                    nmeaGga = sentence.trim()
                )
            }
            type.endsWith("RMC") -> {
                if (tokens.getOrNull(2) != "A") return null
                val lat = fromNmeaCoord(tokens[3], tokens[4]) ?: return null
                val lon = fromNmeaCoord(tokens[5], tokens[6]) ?: return null
                val knots = tokens.getOrNull(7)?.toFloatOrNull() ?: 0f
                val bearing = tokens.getOrNull(8)?.toFloatOrNull() ?: 0f
                GpsTelemetryPayload(
                    latitude = lat,
                    longitude = lon,
                    altitude = 0.0,
                    speedMetersPerSec = knots / 1.943844f,
                    bearingDegrees = bearing,
                    horizontalAccuracyMeters = 3.0f,
                    satellitesInUse = 10,
                    timestampUtcMs = System.currentTimeMillis(),
                    sequenceNumber = sequenceNumber,
                    providerName = "nmea_rmc",
                    nmeaRmc = sentence.trim()
                )
            }
            else -> null
        }
    }

    private fun toNmeaCoord(decimalDegrees: Double, isLatitude: Boolean): String {
        val absVal = abs(decimalDegrees)
        val degrees = floor(absVal).toInt()
        val minutes = (absVal - degrees) * 60.0
        return if (isLatitude) {
            String.format(Locale.US, "%02d%07.4f", degrees, minutes)
        } else {
            String.format(Locale.US, "%03d%07.4f", degrees, minutes)
        }
    }

    private fun fromNmeaCoord(raw: String, hemisphere: String): Double? {
        if (raw.isBlank()) return null
        val dotIdx = raw.indexOf('.')
        if (dotIdx < 3) return null
        val degEnd = dotIdx - 2
        val degrees = raw.substring(0, degEnd).toDoubleOrNull() ?: return null
        val minutes = raw.substring(degEnd).toDoubleOrNull() ?: return null
        val decimal = degrees + (minutes / 60.0)
        return if (hemisphere.equals("S", ignoreCase = true) || hemisphere.equals("W", ignoreCase = true)) {
            -decimal
        } else {
            decimal
        }
    }

    fun computeChecksum(bodyWithoutDollarOrAsterisk: String): String {
        var xor = 0
        for (ch in bodyWithoutDollarOrAsterisk) {
            xor = xor xor ch.code
        }
        return String.format(Locale.US, "%02X", xor)
    }
}
