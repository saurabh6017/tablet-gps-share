package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ReceiverRuntimeState
import com.example.data.TransmitterRuntimeState
import com.example.model.ReceiverEngineStatus
import com.example.network.NetworkDiscoveryManager
import com.example.ui.components.TacticalRadarHudHeader
import com.example.ui.components.TelemetryMetricGrid
import com.example.ui.components.TelemetryMiniBadge
import com.example.ui.components.TerminalPacketStreamCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.viewmodel.UiConfigState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    rxState: ReceiverRuntimeState,
    txState: TransmitterRuntimeState,
    uiConfig: UiConfigState,
    isExpandedScreen: Boolean,
    onToggleReceiver: () -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAutoDiscoverToggle: (Boolean) -> Unit,
    onApplyPreset: (String, Int) -> Unit,
    onOpenDeveloperOptions: (Context) -> Unit,
    onLogFilterSelect: (String) -> Unit,
    onClearLogs: () -> Unit,
    onOpenPacketLogSheet: () -> Unit,
    onDismissPacketLogSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isReceiverActive = rxState.status != ReceiverEngineStatus.IDLE &&
        rxState.status != ReceiverEngineStatus.ERROR

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("receiver_dashboard_root"),
            contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Radar & Live Injection Rate Header
        item {
            TacticalRadarHudHeader(
                isActive = isReceiverActive,
                modeTitle = "Tablet Mock GPS Receiver",
                statusBadgeText = rxState.status.label,
                statusDetail = rxState.statusDetail,
                payload = rxState.latestReceivedFix,
                frequencyHz = rxState.measuredRxFrequencyHz,
                accentColor = MaterialTheme.colorScheme.secondary
            )
        }

        // Top: 6 Telemetry Metric Cards arranged in a clean, responsive row/grid
        item {
            TelemetryMetricGrid(
                payload = rxState.latestReceivedFix,
                frequencyHz = rxState.measuredRxFrequencyHz,
                modifier = Modifier.testTag("receiver_top_telemetry_grid")
            )
        }

        // Balanced Two-Column Dashboard:
        // Left Column: Connection Controls | Right Column: System Diagnostics
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val showTwoColumns = isExpandedScreen || maxWidth >= 620.dp
                if (showTwoColumns) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        ReceiverConnectionControlsCard(
                            rxState = rxState,
                            txState = txState,
                            uiConfig = uiConfig,
                            isReceiverActive = isReceiverActive,
                            onToggleReceiver = onToggleReceiver,
                            onIpChange = onIpChange,
                            onPortChange = onPortChange,
                            onAutoDiscoverToggle = onAutoDiscoverToggle,
                            onApplyPreset = onApplyPreset,
                            modifier = Modifier.weight(1f)
                        )

                        ReceiverSystemDiagnosticsCard(
                            rxState = rxState,
                            onOpenDeveloperOptions = onOpenDeveloperOptions,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ReceiverConnectionControlsCard(
                            rxState = rxState,
                            txState = txState,
                            uiConfig = uiConfig,
                            isReceiverActive = isReceiverActive,
                            onToggleReceiver = onToggleReceiver,
                            onIpChange = onIpChange,
                            onPortChange = onPortChange,
                            onAutoDiscoverToggle = onAutoDiscoverToggle,
                            onApplyPreset = onApplyPreset,
                            modifier = Modifier.fillMaxWidth()
                        )

                        ReceiverSystemDiagnosticsCard(
                            rxState = rxState,
                            onOpenDeveloperOptions = onOpenDeveloperOptions,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Bottom Button opening Expandable Raw Packet Stream & Logs Sheet
        item {
            OutlinedButton(
                onClick = onOpenPacketLogSheet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("open_rx_packet_log_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.view_rx_logs_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (uiConfig.isRxLogSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissPacketLogSheet,
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
                    title = "Incoming Packet Stream & Mock Injection Log",
                    logs = rxState.recentRxLog,
                    selectedFilter = uiConfig.selectedLogFilter,
                    onFilterSelected = onLogFilterSelect,
                    onClearLogs = onClearLogs,
                    showMockInjectionBadge = true
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Left Column Card: Connection Controls (Start/Stop Mock Receiver, Server IP and Port inputs,
 * Quick Hotspot buttons, Auto-Discovery toggle).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceiverConnectionControlsCard(
    rxState: ReceiverRuntimeState,
    txState: TransmitterRuntimeState,
    uiConfig: UiConfigState,
    isReceiverActive: Boolean,
    onToggleReceiver: () -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAutoDiscoverToggle: (Boolean) -> Unit,
    onApplyPreset: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "CONNECTION CONTROLS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Primary Start / Stop Mock Receiver Action Button
            Button(
                onClick = onToggleReceiver,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("toggle_receiver_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isReceiverActive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    contentColor = if (isReceiverActive) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onSecondary
                    }
                )
            ) {
                Icon(
                    imageVector = if (isReceiverActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isReceiverActive) {
                        stringResource(R.string.stop_receiver)
                    } else {
                        stringResource(R.string.start_receiver)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            // Server IP & Port Inputs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiConfig.receiverIpInput,
                    onValueChange = onIpChange,
                    enabled = !isReceiverActive,
                    label = { Text(stringResource(R.string.server_ip_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("receiver_ip_input")
                )

                OutlinedTextField(
                    value = uiConfig.receiverPortInput,
                    onValueChange = onPortChange,
                    enabled = !isReceiverActive,
                    label = { Text(stringResource(R.string.server_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .width(112.dp)
                        .testTag("receiver_port_input")
                )
            }

            // Quick Hotspot Buttons & Discovered Servers
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "QUICK HOTSPOT & DISCOVERED SERVERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {
                            onApplyPreset(
                                NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP,
                                NetworkDiscoveryManager.DEFAULT_TCP_PORT
                            )
                        },
                        enabled = !isReceiverActive,
                        label = { Text("Hotspot (192.168.43.1)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.WifiTethering,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("preset_hotspot_chip")
                    )

                    if (txState.detectedGatewayIp != NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP) {
                        AssistChip(
                            onClick = {
                                onApplyPreset(
                                    txState.detectedGatewayIp,
                                    NetworkDiscoveryManager.DEFAULT_TCP_PORT
                                )
                            },
                            enabled = !isReceiverActive,
                            label = { Text("Gateway (${txState.detectedGatewayIp})") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }

                    AssistChip(
                        onClick = {
                            onApplyPreset(
                                "127.0.0.1",
                                NetworkDiscoveryManager.DEFAULT_TCP_PORT
                            )
                        },
                        enabled = !isReceiverActive,
                        label = { Text("Self Loopback (127.0.0.1)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("preset_loopback_chip")
                    )

                    rxState.discoveredServers.forEach { node ->
                        AssistChip(
                            onClick = { onApplyPreset(node.ipAddress, node.tcpPort) },
                            enabled = !isReceiverActive,
                            label = { Text("${node.deviceName} (${node.ipAddress}:${node.tcpPort})") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CellTower,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Auto-Discovery Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatic Phone Discovery",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Automatically finds your phone on the same Wi-Fi or Hotspot network without typing its IP.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = uiConfig.autoDiscoverEnabled,
                    onCheckedChange = onAutoDiscoverToggle,
                    modifier = Modifier.testTag("auto_discover_switch")
                )
            }
        }
    }
}

/**
 * Right Column Card: System Diagnostics (TCP Socket status, Mock Provider injection status,
 * Monotonic clock sync, and live injection rate readout in Hz).
 * Uses responsive full-width rows with weighted columns so text never wraps into vertical single-character columns.
 */
@Composable
private fun ReceiverSystemDiagnosticsCard(
    rxState: ReceiverRuntimeState,
    onOpenDeveloperOptions: (Context) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("receiver_diagnostics_card"),
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = "System Diagnostics",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "SYSTEM DIAGNOSTICS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TelemetryMiniBadge(
                    text = "INJECTED: ${rxState.totalMockLocationsInjected}",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }

            // Live Injection Rate Readout Banner (Hz)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
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
                        contentDescription = "Live Injection Rate",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LIVE INJECTION RATE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f Hz", rxState.measuredRxFrequencyHz),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }

                TelemetryMiniBadge(
                    text = if (rxState.mockProviderRegistered) "ACTIVE MOCK" else "STANDBY",
                    tint = if (rxState.mockProviderRegistered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                )
            }

            // Responsive Diagnostic Rows (no vertical single-char wrapping)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DiagnosticStatusItem(
                    label = "TCP Socket Status",
                    value = "${rxState.targetServerIp}:${rxState.targetServerPort}",
                    isOk = rxState.status == ReceiverEngineStatus.CONNECTED_STREAMING ||
                        rxState.status == ReceiverEngineStatus.MOCK_PERMISSION_REQUIRED
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                DiagnosticStatusItem(
                    label = "Mock Provider Injection",
                    value = if (rxState.mockProviderRegistered) {
                        "GPS_PROVIDER Active & Injecting"
                    } else {
                        "Standby (Select Mock Location App)"
                    },
                    isOk = rxState.mockProviderRegistered
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                DiagnosticStatusItem(
                    label = "Monotonic Clock Sync",
                    value = "elapsedRealtimeNanos() Verified",
                    isOk = true
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                DiagnosticStatusItem(
                    label = "Connection Stability",
                    value = if (rxState.reconnectAttemptCount == 0) {
                        "0 drops (Link Healthy)"
                    } else {
                        "Reconnecting (#${rxState.reconnectAttemptCount})"
                    },
                    isOk = rxState.reconnectAttemptCount == 0
                )
            }

            // Developer Options Button
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Requires choosing LocalNetGPS under 'Select mock location app' in Developer Options.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = { onOpenDeveloperOptions(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_dev_options_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.open_developer_options),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStatusItem(
    label: String,
    value: String,
    isOk: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                contentDescription = null,
                tint = if (isOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = if (isOk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
