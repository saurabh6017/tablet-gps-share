package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.AppOperatingMode
import com.example.model.ReceiverEngineStatus
import com.example.model.ServerEngineStatus
import com.example.ui.components.TelemetryMiniBadge
import com.example.viewmodel.LocalNetGpsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationRoot(
    viewModel: LocalNetGpsViewModel,
    onRequestRuntimePermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiConfig by viewModel.uiConfig.collectAsStateWithLifecycle()
    val txState by viewModel.transmitterState.collectAsStateWithLifecycle()
    val rxState by viewModel.receiverState.collectAsStateWithLifecycle()

    val isTransmitterLocked = txState.status == ServerEngineStatus.ACTIVE_BROADCASTING ||
        txState.status == ServerEngineStatus.STARTING
    val isReceiverLocked = rxState.status != ReceiverEngineStatus.IDLE &&
        rxState.status != ReceiverEngineStatus.ERROR
    val isSessionLocked = isTransmitterLocked || isReceiverLocked

    // Active mode strictly respects session lock isolation
    val effectiveMode = when {
        isTransmitterLocked -> AppOperatingMode.TRANSMITTER
        isReceiverLocked -> AppOperatingMode.RECEIVER
        else -> uiConfig.operatingMode
    }

    // BackHandler for custom screen state switching
    BackHandler(enabled = !isSessionLocked && effectiveMode == AppOperatingMode.RECEIVER) {
        viewModel.selectOperatingMode(AppOperatingMode.TRANSMITTER)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isExpandedScreen = maxWidth >= 720.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 1060.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Top App Bar + Theme Toggle
                    TopBrandHeader(
                        txActive = isTransmitterLocked,
                        rxActive = isReceiverLocked,
                        darkTheme = uiConfig.darkTheme,
                        onToggleTheme = viewModel::toggleDarkTheme,
                        onRefreshNetwork = viewModel::refreshNetworkInterfaces
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Expandable First-Time Setup & Mock Location Guide
                    ExpandableSetupGuideCard(
                        expanded = uiConfig.isSetupGuideExpanded,
                        onToggleExpand = viewModel::toggleSetupGuideExpanded,
                        onOpenDeveloperOptions = viewModel::openDeveloperOptions
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Dynamic Session Locking & Mode Isolation:
                    // Only show the top mode switcher when NO active service is running.
                    if (!isSessionLocked) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("mode_segmented_control")
                        ) {
                            SegmentedButton(
                                selected = effectiveMode == AppOperatingMode.TRANSMITTER,
                                onClick = { viewModel.selectOperatingMode(AppOperatingMode.TRANSMITTER) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.WifiTethering,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier.testTag("mode_transmitter_tab")
                            ) {
                                Text(
                                    text = stringResource(R.string.mode_transmitter),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            SegmentedButton(
                                selected = effectiveMode == AppOperatingMode.RECEIVER,
                                onClick = { viewModel.selectOperatingMode(AppOperatingMode.RECEIVER) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.TabletAndroid,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier.testTag("mode_receiver_tab")
                            ) {
                                Text(
                                    text = stringResource(R.string.mode_receiver),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    } else {
                        SessionLockedBanner(
                            mode = effectiveMode,
                            modifier = Modifier
                                .padding(bottom = 10.dp)
                                .testTag("session_locked_banner")
                        )
                    }

                    // Runtime Location Permission Banner if not yet granted
                    AnimatedVisibility(visible = !uiConfig.permissionsGranted) {
                        PermissionRequiredBanner(
                            onGrantClick = onRequestRuntimePermissions,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    // Mutually Exclusive Active Dashboard
                    when (effectiveMode) {
                        AppOperatingMode.TRANSMITTER -> {
                            TransmitterScreen(
                                txState = txState,
                                uiConfig = uiConfig,
                                isExpandedScreen = isExpandedScreen,
                                onToggleServer = {
                                    if (!uiConfig.permissionsGranted && !isTransmitterLocked) {
                                        onRequestRuntimePermissions()
                                    } else {
                                        viewModel.toggleTransmitterServer()
                                    }
                                },
                                onPortChange = viewModel::updateTransmitterPortInput,
                                onImproveAltitudeToggle = viewModel::setImproveAltitudeExperimental,
                                onSimToggle = viewModel::setIndoorOrbitalSimulation,
                                onRawNmeaToggle = viewModel::setIncludeRawNmea,
                                onLogFilterSelect = viewModel::setLogFilter,
                                onClearLogs = viewModel::clearPacketLogs,
                                onOpenWireLogDrawer = { viewModel.setTxLogSheetOpen(true) },
                                onDismissWireLogDrawer = { viewModel.setTxLogSheetOpen(false) }
                            )
                        }
                        AppOperatingMode.RECEIVER -> {
                            ReceiverScreen(
                                rxState = rxState,
                                txState = txState,
                                uiConfig = uiConfig,
                                isExpandedScreen = isExpandedScreen,
                                onToggleReceiver = viewModel::toggleReceiverClient,
                                onIpChange = viewModel::updateReceiverIpInput,
                                onPortChange = viewModel::updateReceiverPortInput,
                                onAutoDiscoverToggle = viewModel::setAutoDiscoverEnabled,
                                onApplyPreset = viewModel::applyQuickEndpointPreset,
                                onOpenDeveloperOptions = viewModel::openDeveloperOptions,
                                onLogFilterSelect = viewModel::setLogFilter,
                                onClearLogs = viewModel::clearPacketLogs,
                                onOpenPacketLogSheet = { viewModel.setRxLogSheetOpen(true) },
                                onDismissPacketLogSheet = { viewModel.setRxLogSheetOpen(false) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionLockedBanner(
    mode: AppOperatingMode,
    modifier: Modifier = Modifier
) {
    val accent = if (mode == AppOperatingMode.TRANSMITTER) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val modeLabel = if (mode == AppOperatingMode.TRANSMITTER) {
        "Transmitter Mode Locked • Tap 'Stop GPS Server' to switch modes"
    } else {
        "Receiver Mode Locked • Tap 'Stop Mock Receiver' to switch modes"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Session Mode Locked",
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Expandable Setup & Developer Options Guide card at the top of the app:
 * "📱 First-Time Setup & Mock Location Guide"
 */
@Composable
fun ExpandableSetupGuideCard(
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenDeveloperOptions: (Context) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("setup_guide_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("setup_guide_header"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.setup_guide_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse setup guide" else "Expand setup guide",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                    SetupGuideStepRow(
                        stepNumber = "1",
                        title = "Enable Developer Options",
                        description = "Open Settings → About Phone/Tablet and tap \"Build Number\" (or \"OS/MIUI version\") 7 times until Developer Options is enabled."
                    )

                    SetupGuideStepRow(
                        stepNumber = "2",
                        title = "Select Mock Location App (On Tablet)",
                        description = "Open Developer Options and choose LocalNetGPS under \"Select mock location app\" on your receiving tablet."
                    )

                    OutlinedButton(
                        onClick = { onOpenDeveloperOptions(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("guide_open_dev_options_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Developer Options (Choose LocalNetGPS)")
                    }

                    SetupGuideStepRow(
                        stepNumber = "3",
                        title = "Force Full GNSS Measurements (Critical on Phone)",
                        description = "Turn this toggle ON inside Developer Options on your transmitting phone. It prevents Android from throttling the GPS chip to sleep, maintaining a stable 1 Hz+ broadcast rate indoors."
                    )

                    SetupGuideStepRow(
                        stepNumber = "4",
                        title = "Select GPO Version",
                        description = "Leave this setting Off (it is a low-level chipset debug tool not used for GPS tethering)."
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupGuideStepRow(
    stepNumber: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TopBrandHeader(
    txActive: Boolean,
    rxActive: Boolean,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onRefreshNetwork: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsInputAntenna,
                    contentDescription = "LocalNetGPS Icon",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (txActive) {
                        TelemetryMiniBadge("SERVER ACTIVE", MaterialTheme.colorScheme.primary)
                    }
                    if (rxActive) {
                        TelemetryMiniBadge("RECEIVER ACTIVE", MaterialTheme.colorScheme.secondary)
                    }
                }
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onRefreshNetwork,
                modifier = Modifier.testTag("refresh_network_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh local network interfaces",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.testTag("toggle_theme_button")
            ) {
                Icon(
                    imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle daylight/dark theme",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionRequiredBanner(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = "Location Permission Needed",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Location Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Allow Location & Notifications so LocalNetGPS can stream 1Hz GPS coordinates when the screen is off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ),
                modifier = Modifier.testTag("grant_permissions_button")
            ) {
                Text("Grant")
            }
        }
    }
}
