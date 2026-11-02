package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.TransmitterRuntimeState
import com.example.model.ServerEngineStatus
import com.example.network.NetworkDiscoveryManager
import com.example.sensor.BarometerQnhManager
import com.example.ui.components.TacticalRadarHudHeader
import com.example.ui.components.TelemetryMetricGrid
import com.example.ui.components.TelemetryMiniBadge
import com.example.ui.components.TerminalPacketStreamCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.viewmodel.UiConfigState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransmitterScreen(
    txState: TransmitterRuntimeState,
    uiConfig: UiConfigState,
    isExpandedScreen: Boolean,
    onToggleServer: () -> Unit,
    onPortChange: (String) -> Unit,
    onImproveAltitudeToggle: (Boolean) -> Unit,
    onSimToggle: (Boolean) -> Unit,
    onRawNmeaToggle: (Boolean) -> Unit,
    onLogFilterSelect: (String) -> Unit,
    onClearLogs: () -> Unit,
    onOpenWireLogDrawer: () -> Unit,
    onDismissWireLogDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isServerRunning = txState.status == ServerEngineStatus.ACTIVE_BROADCASTING ||
        txState.status == ServerEngineStatus.STARTING

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("transmitter_dashboard_root"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TacticalRadarHudHeader(
                isActive = isServerRunning,
                modeTitle = "Phone GPS Transmitter",
                statusBadgeText = txState.status.label,
                statusDetail = txState.statusDetail,
                payload = txState.latestLocationFix,
                frequencyHz = txState.measuredFrequencyHz,
                accentColor = MaterialTheme.colorScheme.primary,
                extraBadgeText = if (txState.improveAltitudeExperimental) {
                    txState.barometerStatusBadge ?: BarometerQnhManager.FALLBACK_BADGE_TEXT
                } else {
                    null
                }
            )
        }

        if (isExpandedScreen) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        TransmitterControlAndNetworkCard(
                            txState = txState,
                            portInput = uiConfig.transmitterPortInput,
                            isServerRunning = isServerRunning,
                            onToggleServer = onToggleServer,
                            onPortChange = onPortChange,
                            onImproveAltitudeToggle = onImproveAltitudeToggle,
                            onSimToggle = onSimToggle,
                            onRawNmeaToggle = onRawNmeaToggle
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1.1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        TelemetryMetricGrid(
                            payload = txState.latestLocationFix,
                            frequencyHz = txState.measuredFrequencyHz
                        )
                    }
                }
            }
        } else {
            item {
                TransmitterControlAndNetworkCard(
                    txState = txState,
                    portInput = uiConfig.transmitterPortInput,
                    isServerRunning = isServerRunning,
                    onToggleServer = onToggleServer,
                    onPortChange = onPortChange,
                    onImproveAltitudeToggle = onImproveAltitudeToggle,
                    onSimToggle = onSimToggle,
                    onRawNmeaToggle = onRawNmeaToggle
                )
            }
            item {
                TelemetryMetricGrid(
                    payload = txState.latestLocationFix,
                    frequencyHz = txState.measuredFrequencyHz
                )
            }
        }

        // Streamlined layout: Outgoing Wire Stream is tucked inside an expandable bottom sheet / drawer
        item {
            OutlinedButton(
                onClick = onOpenWireLogDrawer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("open_tx_wire_log_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "📋 Advanced Wire Log (TCP :${txState.tcpPort} • ${txState.recentTxLog.size} frames)",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (uiConfig.isTxLogSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissWireLogDrawer,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                TerminalPacketStreamCard(
                    title = "Advanced Wire Log (TCP :${txState.tcpPort})",
                    logs = txState.recentTxLog,
                    selectedFilter = uiConfig.selectedLogFilter,
                    onFilterSelected = onLogFilterSelect,
                    onClearLogs = onClearLogs,
                    showMockInjectionBadge = false
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransmitterControlAndNetworkCard(
    txState: TransmitterRuntimeState,
    portInput: String,
    isServerRunning: Boolean,
    onToggleServer: () -> Unit,
    onPortChange: (String) -> Unit,
    onImproveAltitudeToggle: (Boolean) -> Unit,
    onSimToggle: (Boolean) -> Unit,
    onRawNmeaToggle: (Boolean) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Primary Start / Stop GPS Server Button
            Button(
                onClick = onToggleServer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("toggle_transmitter_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServerRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (isServerRunning) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                )
            ) {
                Icon(
                    imageVector = if (isServerRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isServerRunning) {
                        stringResource(R.string.stop_server)
                    } else {
                        stringResource(R.string.start_server)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            // Live Transmission Cadence & Satellite Lock Quality Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Broadcast Cadence",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "LIVE BROADCAST CADENCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.1f Hz • %d SATS",
                                txState.measuredFrequencyHz,
                                txState.latestLocationFix?.satellitesInUse ?: 0
                            ),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                TelemetryMiniBadge(
                    text = if (txState.measuredFrequencyHz >= 0.8f) "LOCK STABLE" else if (isServerRunning) "SAMPLING" else "IDLE",
                    tint = if (txState.measuredFrequencyHz >= 0.8f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
            }

            // Local Device IP & Hotspot Readout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = "Network Interfaces",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = "PHONE IP & HOTSPOT ADDRESS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TelemetryMiniBadge(
                        text = "AUTO-BEACON :${NetworkDiscoveryManager.UDP_DISCOVERY_PORT}",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                if (txState.localInterfaces.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .clickable {
                                clipboard.setText(AnnotatedString(NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP))
                            }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hotspot Gateway: ${NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tap to copy IP address for your tablet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy IP",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    txState.localInterfaces.forEach { iface ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .clickable {
                                    clipboard.setText(AnnotatedString(iface.ipv4Address))
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "${iface.ipv4Address}:${txState.tcpPort}",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    TelemetryMiniBadge(
                                        text = if (iface.isHotspotOrTether) "HOTSPOT" else "WI-FI",
                                        tint = if (iface.isHotspotOrTether) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "Interface ${iface.interfaceName} • Tap to copy IP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy IP address",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // TCP Port & Active Locks Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = onPortChange,
                    enabled = !isServerRunning,
                    label = { Text(stringResource(R.string.server_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .width(128.dp)
                        .testTag("transmitter_port_input")
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TelemetryMiniBadge(
                            text = if (txState.wakeLockHeld) "CPU AWAKE: ON" else "CPU AWAKE: STANDBY",
                            tint = if (txState.wakeLockHeld) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TelemetryMiniBadge(
                            text = if (txState.wifiLockHeld) "FAST WI-FI: ON" else "WI-FI: STANDBY",
                            tint = if (txState.wifiLockHeld) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Connected tablets: ${txState.connectedClientAddresses.size} • Sent: ${txState.packetsTransmitted} fixes (${formatByteCount(txState.bytesTransmitted)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Plain-English Transmitter Settings Toggles
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Improve altitude measurement (experimental)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terrain,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = stringResource(R.string.improve_altitude_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = stringResource(R.string.improve_altitude_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = txState.improveAltitudeExperimental,
                            onCheckedChange = onImproveAltitudeToggle,
                            modifier = Modifier.testTag("improve_altitude_switch")
                        )
                    }

                    if (txState.improveAltitudeExperimental) {
                        val badgeLabel = txState.barometerStatusBadge
                            ?: BarometerQnhManager.FALLBACK_BADGE_TEXT
                        val isFallback = txState.barometerUsingFallbackGps ||
                            !txState.barometerAvailable ||
                            badgeLabel == BarometerQnhManager.FALLBACK_BADGE_TEXT
                        TelemetryMiniBadge(
                            text = badgeLabel,
                            tint = if (isFallback) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.testTag("barometer_status_badge")
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                // 2. Simulate Driving Route (Plain English rename)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.simulate_driving_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.simulate_driving_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = txState.isIndoorOrbitalSimEnabled,
                        onCheckedChange = onSimToggle,
                        modifier = Modifier.testTag("bench_sim_switch")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                // 3. Legacy GPS Output (NMEA) (Plain English rename)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.legacy_nmea_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.legacy_nmea_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = txState.includeRawNmeaOnWire,
                        onCheckedChange = onRawNmeaToggle,
                        modifier = Modifier.testTag("raw_nmea_switch")
                    )
                }
            }
        }
    }
}

private fun formatByteCount(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}
