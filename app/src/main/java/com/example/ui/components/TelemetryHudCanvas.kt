package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.GpsTelemetryPayload
import com.example.model.PacketLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom Avionics & GNSS Radar Skyplot Canvas HUD displaying live satellite lock,
 * bearing vector, sweeping radar beam, and live cadence (Hz / SATS).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TacticalRadarHudHeader(
    isActive: Boolean,
    modeTitle: String,
    statusBadgeText: String,
    statusDetail: String,
    payload: GpsTelemetryPayload?,
    frequencyHz: Float,
    accentColor: Color,
    extraBadgeText: String? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 3200 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )
    val pulseRadiusFraction by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_ring"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            accentColor.copy(alpha = if (isActive) 0.12f else 0.04f)
                        )
                    )
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: Animated GNSS Skyplot Radar Canvas
            Box(
                modifier = Modifier.size(92.dp),
                contentAlignment = Alignment.Center
            ) {
                val gridColor = MaterialTheme.colorScheme.outline
                val bearingDeg = payload?.bearingDegrees ?: 0f
                val satsCount = payload?.satellitesInUse ?: 0

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = min(size.width, size.height) / 2f - 4.dp.toPx()

                    drawCircle(
                        color = gridColor.copy(alpha = 0.5f),
                        radius = maxRadius,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawCircle(
                        color = gridColor.copy(alpha = 0.35f),
                        radius = maxRadius * 0.65f,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = gridColor.copy(alpha = 0.25f),
                        radius = maxRadius * 0.32f,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    drawLine(
                        color = gridColor.copy(alpha = 0.35f),
                        start = Offset(center.x - maxRadius, center.y),
                        end = Offset(center.x + maxRadius, center.y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = gridColor.copy(alpha = 0.35f),
                        start = Offset(center.x, center.y - maxRadius),
                        end = Offset(center.x, center.y + maxRadius),
                        strokeWidth = 1.dp.toPx()
                    )

                    if (isActive) {
                        drawCircle(
                            color = accentColor.copy(alpha = (1f - pulseRadiusFraction) * 0.45f),
                            radius = maxRadius * pulseRadiusFraction,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        val rad = Math.toRadians(sweepAngle.toDouble())
                        val sweepEnd = Offset(
                            x = center.x + (maxRadius * cos(rad)).toFloat(),
                            y = center.y + (maxRadius * sin(rad)).toFloat()
                        )
                        drawLine(
                            color = accentColor.copy(alpha = 0.7f),
                            start = center,
                            end = sweepEnd,
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    val visibleBlips = satsCount.coerceIn(0, 12)
                    for (i in 0 until visibleBlips) {
                        val satAngleRad = Math.toRadians((i * 33.0) + (payload?.sequenceNumber ?: 0L) * 1.5)
                        val distFactor = 0.32f + ((i % 3) * 0.24f)
                        val sx = center.x + (maxRadius * distFactor * cos(satAngleRad)).toFloat()
                        val sy = center.y + (maxRadius * distFactor * sin(satAngleRad)).toFloat()
                        drawCircle(
                            color = accentColor,
                            radius = 2.8.dp.toPx(),
                            center = Offset(sx, sy)
                        )
                    }

                    if (payload != null) {
                        val headingRad = Math.toRadians((bearingDeg - 90f).toDouble())
                        val tip = Offset(
                            x = center.x + (maxRadius * 0.72f * cos(headingRad)).toFloat(),
                            y = center.y + (maxRadius * 0.72f * sin(headingRad)).toFloat()
                        )
                        val leftWing = Offset(
                            x = center.x + (maxRadius * 0.25f * cos(headingRad + 2.4)).toFloat(),
                            y = center.y + (maxRadius * 0.25f * sin(headingRad + 2.4)).toFloat()
                        )
                        val rightWing = Offset(
                            x = center.x + (maxRadius * 0.25f * cos(headingRad - 2.4)).toFloat(),
                            y = center.y + (maxRadius * 0.25f * sin(headingRad - 2.4)).toFloat()
                        )
                        val arrowPath = Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(leftWing.x, leftWing.y)
                            lineTo(center.x, center.y)
                            lineTo(rightWing.x, rightWing.y)
                            close()
                        }
                        drawPath(
                            path = arrowPath,
                            color = accentColor.copy(alpha = 0.85f)
                        )
                    }

                    drawCircle(
                        color = if (isActive) accentColor else gridColor,
                        radius = 4.dp.toPx(),
                        center = center
                    )
                }
            }

            // Right: Status readout & telemetry summary
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isActive) accentColor else MaterialTheme.colorScheme.outline)
                    )
                    Text(
                        text = statusBadgeText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = modeTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = statusDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TelemetryMiniBadge(
                        text = String.format(Locale.US, "%.1f Hz", frequencyHz),
                        tint = accentColor,
                        modifier = Modifier.testTag("live_frequency_badge")
                    )
                    TelemetryMiniBadge(
                        text = "${payload?.satellitesInUse ?: 0} SATS",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.testTag("live_sats_badge")
                    )
                    if (payload?.isSimulatedTestPattern == true) {
                        TelemetryMiniBadge(
                            text = "SIMULATED ROUTE",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (!extraBadgeText.isNullOrBlank()) {
                        TelemetryMiniBadge(
                            text = extraBadgeText,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryMiniBadge(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

/**
 * 6-Card High-Contrast Telemetry Readout Grid for Latitude, Longitude, Ground Speed,
 * Course Bearing, Altitude, and Accuracy & Rate.
 * Automatically adapts between a 3-column x 2-row wide tablet layout and a 2-column x 3-row phone layout.
 */
@Composable
fun TelemetryMetricGrid(
    payload: GpsTelemetryPayload?,
    frequencyHz: Float,
    modifier: Modifier = Modifier
) {
    val latStr = if (payload != null) String.format(Locale.US, "%.6f°", payload.latitude) else "---.------°"
    val lonStr = if (payload != null) String.format(Locale.US, "%.6f°", payload.longitude) else "---.------°"
    val altStr = if (payload != null) String.format(Locale.US, "%.1f m", payload.altitude) else "--.- m"
    val altSub = when {
        payload == null -> "Sea Level (MSL)"
        payload.isBarometricAltitude && payload.qnhReferenceHpa != null ->
            String.format(Locale.US, "Baro QNH %.1f hPa", payload.qnhReferenceHpa)
        else -> "GPS Elevation (${payload.providerName.uppercase(Locale.US)})"
    }
    val speedStr = if (payload != null) String.format(Locale.US, "%.1f km/h", payload.speedKmh) else "0.0 km/h"
    val speedSub = if (payload != null) String.format(Locale.US, "%.2f m/s • %.1f kn", payload.speedMetersPerSec, payload.speedKnots) else "0.00 m/s"
    val brgStr = if (payload != null) {
        "${String.format(Locale.US, "%05.1f°", payload.bearingDegrees)} ${cardinalDirection(payload.bearingDegrees)}"
    } else {
        "000.0° N"
    }
    val accStr = if (payload != null) String.format(Locale.US, "±%.1f m", payload.horizontalAccuracyMeters) else "±--.- m"
    val accSub = if (payload != null) {
        String.format(Locale.US, "Rate: %.1f Hz • %d SATS (#%d)", frequencyHz, payload.satellitesInUse, payload.sequenceNumber)
    } else {
        String.format(Locale.US, "Rate: %.1f Hz • Waiting for GPS", frequencyHz)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val useThreeColumns = maxWidth >= 660.dp

        if (useThreeColumns) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricReadoutCard(
                        label = "LATITUDE",
                        value = latStr,
                        subValue = if (payload != null) {
                            if (payload.latitude >= 0) "Northern Hemisphere" else "Southern Hemisphere"
                        } else "No Fix",
                        icon = Icons.Default.GpsFixed,
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricReadoutCard(
                        label = "LONGITUDE",
                        value = lonStr,
                        subValue = if (payload != null) {
                            if (payload.longitude >= 0) "Eastern Hemisphere" else "Western Hemisphere"
                        } else "No Fix",
                        icon = Icons.Default.GpsFixed,
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricReadoutCard(
                        label = "GROUND SPEED",
                        value = speedStr,
                        subValue = speedSub,
                        icon = Icons.Default.Speed,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricReadoutCard(
                        label = "COURSE BEARING",
                        value = brgStr,
                        subValue = if (payload != null) "±${String.format(Locale.US, "%.1f°", payload.bearingAccuracyDegrees)} heading" else "True North",
                        icon = Icons.Default.Explore,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricReadoutCard(
                        label = "ALTITUDE (MSL)",
                        value = altStr,
                        subValue = altSub,
                        icon = Icons.Default.Terrain,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricReadoutCard(
                        label = "ACCURACY & RATE",
                        value = accStr,
                        subValue = accSub,
                        icon = Icons.Default.SatelliteAlt,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricReadoutCard(
                        label = "LATITUDE",
                        value = latStr,
                        subValue = if (payload != null) {
                            if (payload.latitude >= 0) "Northern Hemisphere" else "Southern Hemisphere"
                        } else "No Fix",
                        icon = Icons.Default.GpsFixed,
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricReadoutCard(
                        label = "LONGITUDE",
                        value = lonStr,
                        subValue = if (payload != null) {
                            if (payload.longitude >= 0) "Eastern Hemisphere" else "Western Hemisphere"
                        } else "No Fix",
                        icon = Icons.Default.GpsFixed,
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricReadoutCard(
                        label = "GROUND SPEED",
                        value = speedStr,
                        subValue = speedSub,
                        icon = Icons.Default.Speed,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricReadoutCard(
                        label = "COURSE BEARING",
                        value = brgStr,
                        subValue = if (payload != null) "±${String.format(Locale.US, "%.1f°", payload.bearingAccuracyDegrees)} heading" else "True North",
                        icon = Icons.Default.Explore,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricReadoutCard(
                        label = "ALTITUDE (MSL)",
                        value = altStr,
                        subValue = altSub,
                        icon = Icons.Default.Terrain,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricReadoutCard(
                        label = "ACCURACY & RATE",
                        value = accStr,
                        subValue = accSub,
                        icon = Icons.Default.SatelliteAlt,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricReadoutCard(
    label: String,
    value: String,
    subValue: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { clipboard.setText(AnnotatedString(value)) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Live Wire-Protocol Packet Inspector (NDJSON + NMEA-0183 sentences) with protocol filtering,
 * Copy, and Clear buttons for use inside the expandable bottom sheet / Advanced Wire Log drawer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPacketStreamCard(
    title: String,
    logs: List<PacketLogEntry>,
    selectedFilter: String,
    onFilterSelected: (String) -> Unit,
    onClearLogs: () -> Unit,
    showMockInjectionBadge: Boolean,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val filteredLogs = logs.filter {
        selectedFilter == "ALL" || it.protocolTag.equals(selectedFilter, ignoreCase = true)
    }.take(20)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val dump = filteredLogs.joinToString("\n") { it.rawLine }
                            if (dump.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(dump))
                            }
                        },
                        modifier = Modifier.testTag("copy_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy logs",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }
                    OutlinedButton(
                        onClick = onClearLogs,
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear logs",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ALL", "NDJSON", "NMEA").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { onFilterSelected(filter) },
                        label = {
                            Text(
                                text = filter,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("log_filter_$filter")
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No packets logged yet. Start the session to inspect live JSON and NMEA sentences.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    filteredLogs.forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TelemetryMiniBadge(
                                        text = entry.protocolTag,
                                        tint = if (entry.protocolTag == "NMEA") {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                    Text(
                                        text = timeFmt.format(Date(entry.timestampMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (showMockInjectionBadge) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Mock Location status",
                                            tint = if (entry.mockInjected) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            },
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = if (entry.mockInjected) "MOCK INJECTED" else "RECEIVED",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (entry.mockInjected) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                        )
                                    }
                                }
                            }
                            Text(
                                text = entry.rawLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun cardinalDirection(bearingDegrees: Float): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((bearingDegrees % 360f) + 360f) % 360f
    val idx = ((normalized + 22.5f) / 45f).toInt() % 8
    return dirs[idx]
}
