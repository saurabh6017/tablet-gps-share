package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppOperatingMode
import com.example.model.DiscoveredServerNode
import com.example.model.GpsTelemetryPayload
import com.example.model.LocalNetworkInterfaceInfo
import com.example.model.PacketLogEntry
import com.example.model.ReceiverEngineStatus
import com.example.model.ServerEngineStatus
import com.example.network.NetworkDiscoveryManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

private val Context.dataStore by preferencesDataStore(name = "localnetgps_prefs")

data class TransmitterRuntimeState(
    val status: ServerEngineStatus = ServerEngineStatus.IDLE,
    val statusDetail: String = "Ready to broadcast GPS coordinates over Wi-Fi or Hotspot",
    val tcpPort: Int = NetworkDiscoveryManager.DEFAULT_TCP_PORT,
    val localInterfaces: List<LocalNetworkInterfaceInfo> = emptyList(),
    val detectedGatewayIp: String = NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP,
    val connectedClientAddresses: List<String> = emptyList(),
    val latestLocationFix: GpsTelemetryPayload? = null,
    val measuredFrequencyHz: Float = 0f,
    val packetsTransmitted: Long = 0L,
    val bytesTransmitted: Long = 0L,
    val wakeLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
    val isIndoorOrbitalSimEnabled: Boolean = false,
    val includeRawNmeaOnWire: Boolean = false,
    val improveAltitudeExperimental: Boolean = false,
    val barometerAvailable: Boolean = true,
    val barometerUsingFallbackGps: Boolean = false,
    val barometerStatusBadge: String? = null,
    val measuredPressureHpa: Float? = null,
    val cachedQnhHpa: Float? = null,
    val qnhLastUpdatedMs: Long = 0L,
    val recentTxLog: List<PacketLogEntry> = emptyList()
)

data class ReceiverRuntimeState(
    val status: ReceiverEngineStatus = ReceiverEngineStatus.IDLE,
    val statusDetail: String = "Enter phone IP or enable Auto-Discovery to start mock GPS",
    val targetServerIp: String = NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP,
    val targetServerPort: Int = NetworkDiscoveryManager.DEFAULT_TCP_PORT,
    val autoConnectDiscovered: Boolean = true,
    val discoveredServers: List<DiscoveredServerNode> = emptyList(),
    val latestReceivedFix: GpsTelemetryPayload? = null,
    val mockProviderRegistered: Boolean = false,
    val lastMockInjectionTimeMs: Long = 0L,
    val totalMockLocationsInjected: Long = 0L,
    val reconnectAttemptCount: Int = 0,
    val measuredRxFrequencyHz: Float = 0f,
    val wakeLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
    val recentRxLog: List<PacketLogEntry> = emptyList()
)

data class SavedEndpointPreferences(
    val mode: AppOperatingMode = AppOperatingMode.TRANSMITTER,
    val serverIp: String = NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP,
    val serverPort: Int = NetworkDiscoveryManager.DEFAULT_TCP_PORT,
    val autoConnectDiscovery: Boolean = true,
    val includeRawNmea: Boolean = false,
    val improveAltitudeExperimental: Boolean = false
)

/**
 * Process-wide reactive telemetry bus connecting Foreground Services (`GpsServerService`
 * and `GpsClientService`) with the Jetpack Compose UI (`LocalNetGpsViewModel`).
 */
object TelemetryRepository {

    private const val MAX_LOG_ENTRIES = 50
    private val logIdGenerator = AtomicLong(1L)

    private val _transmitterState = MutableStateFlow(TransmitterRuntimeState())
    val transmitterState: StateFlow<TransmitterRuntimeState> = _transmitterState.asStateFlow()

    private val _receiverState = MutableStateFlow(ReceiverRuntimeState())
    val receiverState: StateFlow<ReceiverRuntimeState> = _receiverState.asStateFlow()

    private val KEY_OPERATING_MODE = stringPreferencesKey("operating_mode")
    private val KEY_SERVER_IP = stringPreferencesKey("server_ip")
    private val KEY_SERVER_PORT = intPreferencesKey("server_port")
    private val KEY_AUTO_DISCOVERY = booleanPreferencesKey("auto_discovery")
    private val KEY_INCLUDE_RAW_NMEA = booleanPreferencesKey("include_raw_nmea")
    private val KEY_IMPROVE_ALTITUDE = booleanPreferencesKey("improve_altitude_experimental")

    fun observePreferences(context: Context): Flow<SavedEndpointPreferences> {
        return context.applicationContext.dataStore.data.map { prefs ->
            val modeStr = prefs[KEY_OPERATING_MODE] ?: AppOperatingMode.TRANSMITTER.name
            val mode = runCatching { AppOperatingMode.valueOf(modeStr) }
                .getOrDefault(AppOperatingMode.TRANSMITTER)
            SavedEndpointPreferences(
                mode = mode,
                serverIp = prefs[KEY_SERVER_IP] ?: NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP,
                serverPort = prefs[KEY_SERVER_PORT] ?: NetworkDiscoveryManager.DEFAULT_TCP_PORT,
                autoConnectDiscovery = prefs[KEY_AUTO_DISCOVERY] ?: true,
                includeRawNmea = prefs[KEY_INCLUDE_RAW_NMEA] ?: false,
                improveAltitudeExperimental = prefs[KEY_IMPROVE_ALTITUDE] ?: false
            )
        }
    }

    suspend fun saveOperatingMode(context: Context, mode: AppOperatingMode) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_OPERATING_MODE] = mode.name
        }
    }

    suspend fun saveReceiverEndpoint(
        context: Context,
        serverIp: String,
        serverPort: Int,
        autoConnectDiscovery: Boolean
    ) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_SERVER_IP] = serverIp
            prefs[KEY_SERVER_PORT] = serverPort
            prefs[KEY_AUTO_DISCOVERY] = autoConnectDiscovery
        }
        _receiverState.update {
            it.copy(
                targetServerIp = serverIp,
                targetServerPort = serverPort,
                autoConnectDiscovered = autoConnectDiscovery
            )
        }
    }

    suspend fun saveIncludeRawNmea(context: Context, includeRawNmea: Boolean) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_INCLUDE_RAW_NMEA] = includeRawNmea
        }
        _transmitterState.update { it.copy(includeRawNmeaOnWire = includeRawNmea) }
    }

    suspend fun saveImproveAltitudeExperimental(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY_IMPROVE_ALTITUDE] = enabled
        }
        _transmitterState.update {
            it.copy(
                improveAltitudeExperimental = enabled,
                barometerStatusBadge = if (!enabled) null else it.barometerStatusBadge
            )
        }
    }

    fun updateTransmitterState(transform: (TransmitterRuntimeState) -> TransmitterRuntimeState) {
        _transmitterState.update(transform)
    }

    fun updateReceiverState(transform: (ReceiverRuntimeState) -> ReceiverRuntimeState) {
        _receiverState.update(transform)
    }

    fun appendTransmitterLog(protocolTag: String, rawLine: String) {
        val entry = PacketLogEntry(
            id = logIdGenerator.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            protocolTag = protocolTag,
            rawLine = rawLine,
            mockInjected = false
        )
        _transmitterState.update { state ->
            val updated = (listOf(entry) + state.recentTxLog).take(MAX_LOG_ENTRIES)
            state.copy(recentTxLog = updated)
        }
    }

    fun appendReceiverLog(protocolTag: String, rawLine: String, mockInjected: Boolean) {
        val entry = PacketLogEntry(
            id = logIdGenerator.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            protocolTag = protocolTag,
            rawLine = rawLine,
            mockInjected = mockInjected
        )
        _receiverState.update { state ->
            val updated = (listOf(entry) + state.recentRxLog).take(MAX_LOG_ENTRIES)
            state.copy(recentRxLog = updated)
        }
    }

    fun recordDiscoveredServer(node: DiscoveredServerNode) {
        _receiverState.update { state ->
            val now = System.currentTimeMillis()
            val filtered = state.discoveredServers
                .filter { it.ipAddress != node.ipAddress && (now - it.lastSeenMs) < 15_000L }
            state.copy(
                discoveredServers = (listOf(node) + filtered).sortedByDescending { it.lastSeenMs }
            )
        }
    }

    fun clearLogs() {
        _transmitterState.update { it.copy(recentTxLog = emptyList()) }
        _receiverState.update { it.copy(recentRxLog = emptyList()) }
    }
}
