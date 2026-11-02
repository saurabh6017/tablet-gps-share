package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ReceiverRuntimeState
import com.example.data.TelemetryRepository
import com.example.data.TransmitterRuntimeState
import com.example.model.AppOperatingMode
import com.example.model.ReceiverEngineStatus
import com.example.model.ServerEngineStatus
import com.example.network.NetworkDiscoveryManager
import com.example.sensor.BarometerQnhManager
import com.example.service.GpsClientService
import com.example.service.GpsServerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiConfigState(
    val operatingMode: AppOperatingMode = AppOperatingMode.TRANSMITTER,
    val receiverIpInput: String = NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP,
    val receiverPortInput: String = NetworkDiscoveryManager.DEFAULT_TCP_PORT.toString(),
    val transmitterPortInput: String = NetworkDiscoveryManager.DEFAULT_TCP_PORT.toString(),
    val autoDiscoverEnabled: Boolean = true,
    val darkTheme: Boolean = true,
    val selectedLogFilter: String = "ALL",
    val permissionsGranted: Boolean = false,
    val isSetupGuideExpanded: Boolean = false,
    val isTxLogSheetOpen: Boolean = false,
    val isRxLogSheetOpen: Boolean = false
)

class LocalNetGpsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext

    val transmitterState: StateFlow<TransmitterRuntimeState> = TelemetryRepository.transmitterState
    val receiverState: StateFlow<ReceiverRuntimeState> = TelemetryRepository.receiverState

    private val _uiConfig = MutableStateFlow(UiConfigState())
    val uiConfig: StateFlow<UiConfigState> = _uiConfig.asStateFlow()

    private var passiveUdpScanJob: Job? = null
    private var networkRefreshJob: Job? = null
    private var prefsInitialized = false

    init {
        checkBarometerHardwarePresence()
        refreshNetworkInterfaces()
        observeSavedPreferences()
        startPeriodicInterfaceRefresh()
        startPassiveUdpDiscoveryScanner()
    }

    private fun checkBarometerHardwarePresence(): Boolean {
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasBaro = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        TelemetryRepository.updateTransmitterState {
            it.copy(barometerAvailable = hasBaro)
        }
        return hasBaro
    }

    private fun observeSavedPreferences() {
        viewModelScope.launch {
            TelemetryRepository.observePreferences(appContext).collectLatest { prefs ->
                if (!prefsInitialized) {
                    prefsInitialized = true
                    val smartIp = prefs.serverIp.ifBlank {
                        NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP
                    }
                    val hasBaro = checkBarometerHardwarePresence()
                    // Respect active session lock if service is already running
                    val lockedMode = when {
                        isTransmitterSessionActive(transmitterState.value.status) -> AppOperatingMode.TRANSMITTER
                        isReceiverSessionActive(receiverState.value.status) -> AppOperatingMode.RECEIVER
                        else -> prefs.mode
                    }
                    _uiConfig.update {
                        it.copy(
                            operatingMode = lockedMode,
                            receiverIpInput = smartIp,
                            receiverPortInput = prefs.serverPort.toString(),
                            autoDiscoverEnabled = prefs.autoConnectDiscovery
                        )
                    }
                    TelemetryRepository.updateTransmitterState {
                        it.copy(
                            includeRawNmeaOnWire = prefs.includeRawNmea,
                            improveAltitudeExperimental = prefs.improveAltitudeExperimental,
                            barometerAvailable = hasBaro,
                            barometerUsingFallbackGps = prefs.improveAltitudeExperimental && !hasBaro,
                            barometerStatusBadge = if (prefs.improveAltitudeExperimental && !hasBaro) {
                                BarometerQnhManager.FALLBACK_BADGE_TEXT
                            } else {
                                it.barometerStatusBadge
                            }
                        )
                    }
                }
            }
        }
    }

    private fun startPeriodicInterfaceRefresh() {
        networkRefreshJob?.cancel()
        networkRefreshJob = viewModelScope.launch {
            while (isActive) {
                refreshNetworkInterfaces()
                delay(4000L)
            }
        }
    }

    private fun startPassiveUdpDiscoveryScanner() {
        passiveUdpScanJob?.cancel()
        passiveUdpScanJob = NetworkDiscoveryManager.startUdpBeaconListener(
            context = appContext,
            scope = viewModelScope,
            onServerDiscovered = { node ->
                TelemetryRepository.recordDiscoveredServer(node)
                val cfg = _uiConfig.value
                if (cfg.autoDiscoverEnabled &&
                    receiverState.value.status == ReceiverEngineStatus.IDLE &&
                    cfg.receiverIpInput == NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP
                ) {
                    _uiConfig.update {
                        it.copy(
                            receiverIpInput = node.ipAddress,
                            receiverPortInput = node.tcpPort.toString()
                        )
                    }
                }
            }
        )
    }

    fun isTransmitterSessionActive(status: ServerEngineStatus = transmitterState.value.status): Boolean {
        return status == ServerEngineStatus.ACTIVE_BROADCASTING ||
            status == ServerEngineStatus.STARTING
    }

    fun isReceiverSessionActive(status: ReceiverEngineStatus = receiverState.value.status): Boolean {
        return status != ReceiverEngineStatus.IDLE &&
            status != ReceiverEngineStatus.ERROR
    }

    fun isAnySessionLocked(): Boolean {
        return isTransmitterSessionActive() || isReceiverSessionActive()
    }

    fun refreshNetworkInterfaces() {
        val interfaces = NetworkDiscoveryManager.getActiveLocalInterfaces()
        val gateway = NetworkDiscoveryManager.detectGatewayIp(appContext)
        TelemetryRepository.updateTransmitterState {
            it.copy(
                localInterfaces = interfaces,
                detectedGatewayIp = gateway
            )
        }
    }

    fun setPermissionsGranted(granted: Boolean) {
        _uiConfig.update { it.copy(permissionsGranted = granted) }
    }

    /**
     * Switches between Phone (Transmitter) and Tablet (Receiver) mode ONLY when no active session is running.
     */
    fun selectOperatingMode(mode: AppOperatingMode) {
        if (isAnySessionLocked()) {
            return
        }
        _uiConfig.update { it.copy(operatingMode = mode) }
        viewModelScope.launch {
            TelemetryRepository.saveOperatingMode(appContext, mode)
        }
        refreshNetworkInterfaces()
    }

    fun toggleSetupGuideExpanded() {
        _uiConfig.update { it.copy(isSetupGuideExpanded = !it.isSetupGuideExpanded) }
    }

    fun setTxLogSheetOpen(open: Boolean) {
        _uiConfig.update { it.copy(isTxLogSheetOpen = open) }
    }

    fun setRxLogSheetOpen(open: Boolean) {
        _uiConfig.update { it.copy(isRxLogSheetOpen = open) }
    }

    fun updateReceiverIpInput(ip: String) {
        _uiConfig.update { it.copy(receiverIpInput = ip) }
    }

    fun updateReceiverPortInput(portStr: String) {
        val digitsOnly = portStr.filter { it.isDigit() }.take(5)
        _uiConfig.update { it.copy(receiverPortInput = digitsOnly) }
    }

    fun updateTransmitterPortInput(portStr: String) {
        val digitsOnly = portStr.filter { it.isDigit() }.take(5)
        _uiConfig.update { it.copy(transmitterPortInput = digitsOnly) }
    }

    fun setAutoDiscoverEnabled(enabled: Boolean) {
        _uiConfig.update { it.copy(autoDiscoverEnabled = enabled) }
        val port = _uiConfig.value.receiverPortInput.toIntOrNull()
            ?: NetworkDiscoveryManager.DEFAULT_TCP_PORT
        viewModelScope.launch {
            TelemetryRepository.saveReceiverEndpoint(
                context = appContext,
                serverIp = _uiConfig.value.receiverIpInput,
                serverPort = port,
                autoConnectDiscovery = enabled
            )
        }
    }

    fun toggleDarkTheme() {
        _uiConfig.update { it.copy(darkTheme = !it.darkTheme) }
    }

    fun setLogFilter(filter: String) {
        _uiConfig.update { it.copy(selectedLogFilter = filter) }
    }

    fun clearPacketLogs() {
        TelemetryRepository.clearLogs()
    }

    fun applyQuickEndpointPreset(ip: String, port: Int = NetworkDiscoveryManager.DEFAULT_TCP_PORT) {
        _uiConfig.update {
            it.copy(
                receiverIpInput = ip,
                receiverPortInput = port.toString()
            )
        }
    }

    fun toggleTransmitterServer() {
        if (isTransmitterSessionActive()) {
            stopTransmitterServer()
        } else {
            startTransmitterServer()
        }
    }

    fun startTransmitterServer() {
        if (isReceiverSessionActive()) return

        val port = _uiConfig.value.transmitterPortInput.toIntOrNull()
            ?.coerceIn(1024, 65535)
            ?: NetworkDiscoveryManager.DEFAULT_TCP_PORT
        val simEnabled = transmitterState.value.isIndoorOrbitalSimEnabled
        val improveAlt = transmitterState.value.improveAltitudeExperimental

        // Lock UI immediately to Transmitter Mode
        _uiConfig.update { it.copy(operatingMode = AppOperatingMode.TRANSMITTER) }
        TelemetryRepository.updateTransmitterState {
            it.copy(
                status = ServerEngineStatus.STARTING,
                statusDetail = "Starting GPS Server on port :$port...",
                tcpPort = port
            )
        }

        val intent = Intent(appContext, GpsServerService::class.java).apply {
            action = GpsServerService.ACTION_START_SERVER
            putExtra(GpsServerService.EXTRA_TCP_PORT, port)
            putExtra(GpsServerService.EXTRA_SIMULATION_ENABLED, simEnabled)
            putExtra(GpsServerService.EXTRA_IMPROVE_ALTITUDE_ENABLED, improveAlt)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopTransmitterServer() {
        TelemetryRepository.updateTransmitterState {
            it.copy(
                status = ServerEngineStatus.IDLE,
                statusDetail = "GPS Server stopped • Ready to start",
                connectedClientAddresses = emptyList(),
                measuredFrequencyHz = 0f,
                wakeLockHeld = false,
                wifiLockHeld = false
            )
        }
        val intent = Intent(appContext, GpsServerService::class.java).apply {
            action = GpsServerService.ACTION_STOP_SERVER
        }
        appContext.startService(intent)
    }

    fun setIndoorOrbitalSimulation(enabled: Boolean) {
        TelemetryRepository.updateTransmitterState {
            it.copy(isIndoorOrbitalSimEnabled = enabled)
        }
        if (isTransmitterSessionActive()) {
            val intent = Intent(appContext, GpsServerService::class.java).apply {
                action = GpsServerService.ACTION_SET_SIMULATION_MODE
                putExtra(GpsServerService.EXTRA_SIMULATION_ENABLED, enabled)
            }
            appContext.startService(intent)
        }
    }

    fun setIncludeRawNmea(enabled: Boolean) {
        viewModelScope.launch {
            TelemetryRepository.saveIncludeRawNmea(appContext, enabled)
        }
    }

    fun setImproveAltitudeExperimental(enabled: Boolean) {
        val hasBaro = checkBarometerHardwarePresence()
        TelemetryRepository.updateTransmitterState {
            it.copy(
                improveAltitudeExperimental = enabled,
                barometerAvailable = hasBaro,
                barometerUsingFallbackGps = enabled && (!hasBaro || it.cachedQnhHpa == null),
                barometerStatusBadge = when {
                    !enabled -> null
                    !hasBaro -> BarometerQnhManager.FALLBACK_BADGE_TEXT
                    it.measuredPressureHpa != null && it.cachedQnhHpa != null ->
                        String.format(
                            java.util.Locale.US,
                            "Barometer active (%.1f hPa • QNH %.1f hPa)",
                            it.measuredPressureHpa,
                            it.cachedQnhHpa
                        )
                    else -> BarometerQnhManager.FALLBACK_BADGE_TEXT
                }
            )
        }
        viewModelScope.launch {
            TelemetryRepository.saveImproveAltitudeExperimental(appContext, enabled)
        }
        if (isTransmitterSessionActive()) {
            val intent = Intent(appContext, GpsServerService::class.java).apply {
                action = GpsServerService.ACTION_SET_IMPROVE_ALTITUDE
                putExtra(GpsServerService.EXTRA_IMPROVE_ALTITUDE_ENABLED, enabled)
            }
            appContext.startService(intent)
        }
    }

    fun toggleReceiverClient() {
        if (isReceiverSessionActive()) {
            stopReceiverClient()
        } else {
            startReceiverClient()
        }
    }

    fun startReceiverClient() {
        if (isTransmitterSessionActive()) return

        val ip = _uiConfig.value.receiverIpInput.trim()
            .ifEmpty { NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP }
        val port = _uiConfig.value.receiverPortInput.toIntOrNull()
            ?.coerceIn(1, 65535)
            ?: NetworkDiscoveryManager.DEFAULT_TCP_PORT
        val autoDiscover = _uiConfig.value.autoDiscoverEnabled

        // Lock UI immediately to Receiver Mode
        _uiConfig.update { it.copy(operatingMode = AppOperatingMode.RECEIVER) }
        TelemetryRepository.updateReceiverState {
            it.copy(
                status = ReceiverEngineStatus.CONNECTING,
                statusDetail = "Connecting to $ip:$port...",
                targetServerIp = ip,
                targetServerPort = port,
                autoConnectDiscovered = autoDiscover
            )
        }

        viewModelScope.launch {
            TelemetryRepository.saveReceiverEndpoint(appContext, ip, port, autoDiscover)
        }

        val intent = Intent(appContext, GpsClientService::class.java).apply {
            action = GpsClientService.ACTION_START_RECEIVER
            putExtra(GpsClientService.EXTRA_SERVER_IP, ip)
            putExtra(GpsClientService.EXTRA_SERVER_PORT, port)
            putExtra(GpsClientService.EXTRA_AUTO_DISCOVER, autoDiscover)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopReceiverClient() {
        TelemetryRepository.updateReceiverState {
            it.copy(
                status = ReceiverEngineStatus.IDLE,
                statusDetail = "Mock Receiver stopped • Tablet GPS returned to normal",
                mockProviderRegistered = false,
                measuredRxFrequencyHz = 0f,
                wakeLockHeld = false,
                wifiLockHeld = false
            )
        }
        val intent = Intent(appContext, GpsClientService::class.java).apply {
            action = GpsClientService.ACTION_STOP_RECEIVER
        }
        appContext.startService(intent)
    }

    fun openDeveloperOptions(context: Context) {
        try {
            val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(devIntent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        passiveUdpScanJob?.cancel()
        networkRefreshJob?.cancel()
        super.onCleared()
    }
}
