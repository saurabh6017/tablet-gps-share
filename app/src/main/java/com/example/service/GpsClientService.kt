package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.TelemetryRepository
import com.example.model.GpsTelemetryPayload
import com.example.model.ReceiverEngineStatus
import com.example.network.NetworkDiscoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.Locale

open class GpsClientService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var locationManager: LocationManager? = null

    private var socketIngestionJob: Job? = null
    private var udpDiscoveryJob: Job? = null
    @Volatile
    private var activeClientSocket: Socket? = null
    @Volatile
    private var isTestProviderRegistered: Boolean = false

    private var targetIp: String = NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP
    private var targetPort: Int = NetworkDiscoveryManager.DEFAULT_TCP_PORT
    private var autoDiscoverEnabled: Boolean = true

    // Moving-window packet timestamp queue for real-time injection frequency (Hz)
    private val rxTimestampsMs = ArrayDeque<Long>(10)

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RECEIVER -> {
                stopReceiverEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                targetIp = intent?.getStringExtra(EXTRA_SERVER_IP)
                    ?.trim()
                    ?.ifEmpty { NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP }
                    ?: NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP
                targetPort = intent?.getIntExtra(
                    EXTRA_SERVER_PORT,
                    NetworkDiscoveryManager.DEFAULT_TCP_PORT
                )?.coerceIn(1, 65535) ?: NetworkDiscoveryManager.DEFAULT_TCP_PORT
                autoDiscoverEnabled = intent?.getBooleanExtra(EXTRA_AUTO_DISCOVER, true) ?: true

                startReceiverEngine()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopReceiverEngine()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startReceiverEngine() {
        val initialNotif = buildNotification(
            "Starting Mock Location Receiver...",
            "Connecting to $targetIp:$targetPort"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (e: Exception) {
            TelemetryRepository.updateReceiverState {
                it.copy(
                    status = ReceiverEngineStatus.ERROR,
                    statusDetail = "Foreground Service start blocked: ${e.localizedMessage}"
                )
            }
            stopSelf()
            return
        }

        acquirePowerAndWifiLocks()
        synchronized(rxTimestampsMs) {
            rxTimestampsMs.clear()
        }

        val providerReady = ensureGpsTestProviderRegistered()

        TelemetryRepository.updateReceiverState {
            it.copy(
                status = if (providerReady) ReceiverEngineStatus.CONNECTING else ReceiverEngineStatus.MOCK_PERMISSION_REQUIRED,
                statusDetail = if (providerReady) {
                    "Mock GPS provider ready • Connecting to $targetIp:$targetPort..."
                } else {
                    "Select LocalNetGPS as Mock Location App in Developer Options (streaming in preview mode)"
                },
                targetServerIp = targetIp,
                targetServerPort = targetPort,
                autoConnectDiscovered = autoDiscoverEnabled,
                mockProviderRegistered = providerReady,
                wakeLockHeld = wakeLock?.isHeld == true,
                wifiLockHeld = wifiLock?.isHeld == true
            )
        }

        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = NetworkDiscoveryManager.startUdpBeaconListener(
            context = this,
            scope = serviceScope,
            onServerDiscovered = { node ->
                TelemetryRepository.recordDiscoveredServer(node)
                val currentStatus = TelemetryRepository.receiverState.value.status
                if (autoDiscoverEnabled &&
                    currentStatus != ReceiverEngineStatus.CONNECTED_STREAMING &&
                    (targetIp != node.ipAddress || targetPort != node.tcpPort)
                ) {
                    targetIp = node.ipAddress
                    targetPort = node.tcpPort
                    TelemetryRepository.updateReceiverState {
                        it.copy(
                            targetServerIp = node.ipAddress,
                            targetServerPort = node.tcpPort,
                            statusDetail = "Auto-discovered ${node.deviceName} at ${node.ipAddress}:${node.tcpPort}"
                        )
                    }
                }
            }
        )

        socketIngestionJob?.cancel()
        socketIngestionJob = serviceScope.launch {
            var reconnectAttempt = 0
            var fallbackSeq = 0L

            while (isActive) {
                val connectHost = targetIp
                val connectPort = targetPort
                try {
                    TelemetryRepository.updateReceiverState {
                        it.copy(
                            status = if (reconnectAttempt == 0) ReceiverEngineStatus.CONNECTING else ReceiverEngineStatus.RECONNECTING,
                            statusDetail = if (reconnectAttempt == 0) {
                                "Connecting TCP socket to $connectHost:$connectPort..."
                            } else {
                                "Reconnecting (#$reconnectAttempt) to $connectHost:$connectPort..."
                            },
                            reconnectAttemptCount = reconnectAttempt
                        )
                    }
                    updateOngoingNotification()

                    val sock = Socket()
                    activeClientSocket = sock
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.soTimeout = 6000
                    sock.connect(InetSocketAddress(connectHost, connectPort), 3500)

                    reconnectAttempt = 0
                    val mockOk = ensureGpsTestProviderRegistered()
                    TelemetryRepository.updateReceiverState {
                        it.copy(
                            status = if (mockOk) {
                                ReceiverEngineStatus.CONNECTED_STREAMING
                            } else {
                                ReceiverEngineStatus.MOCK_PERMISSION_REQUIRED
                            },
                            statusDetail = if (mockOk) {
                                "Connected to $connectHost:$connectPort • Injecting live GPS locations"
                            } else {
                                "Connected to $connectHost:$connectPort • Select LocalNetGPS in Developer Options!"
                            },
                            mockProviderRegistered = mockOk,
                            reconnectAttemptCount = 0
                        )
                    }
                    updateOngoingNotification()

                    val reader = BufferedReader(
                        InputStreamReader(sock.getInputStream(), Charsets.UTF_8)
                    )
                    while (isActive && !sock.isClosed) {
                        val rawLine = reader.readLine() ?: break
                        val trimmed = rawLine.trim()
                        if (trimmed.isEmpty()) continue

                        if (trimmed.startsWith("\$GP") || trimmed.startsWith("\$GN")) {
                            TelemetryRepository.appendReceiverLog(
                                protocolTag = "NMEA",
                                rawLine = trimmed,
                                mockInjected = isTestProviderRegistered
                            )
                            continue
                        }

                        fallbackSeq++
                        val payload = GpsTelemetryPayload.parseWireFrame(trimmed, fallbackSeq)
                        if (payload != null) {
                            val injected = injectMockLocationIntoSystem(payload)
                            updateReceiverMetrics(payload, trimmed, injected)
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    reconnectAttempt++
                    val backoffMs = (1000L * reconnectAttempt.coerceAtMost(4))
                    synchronized(rxTimestampsMs) {
                        rxTimestampsMs.clear()
                    }
                    TelemetryRepository.updateReceiverState {
                        it.copy(
                            status = ReceiverEngineStatus.RECONNECTING,
                            statusDetail = "Connection lost (${e.javaClass.simpleName}) • Retrying $connectHost:$connectPort in ${backoffMs / 1000}s...",
                            reconnectAttemptCount = reconnectAttempt,
                            measuredRxFrequencyHz = 0f
                        )
                    }
                    updateOngoingNotification()
                    delay(backoffMs)
                } finally {
                    try {
                        activeClientSocket?.close()
                    } catch (_: Exception) {
                    }
                    activeClientSocket = null
                }
            }
        }
    }

    private fun stopReceiverEngine() {
        socketIngestionJob?.cancel()
        socketIngestionJob = null
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null

        try {
            activeClientSocket?.close()
        } catch (_: Exception) {
        }
        activeClientSocket = null

        teardownGpsTestProvider()
        releasePowerAndWifiLocks()
        synchronized(rxTimestampsMs) {
            rxTimestampsMs.clear()
        }

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
    }

    @Suppress("DEPRECATION")
    private fun ensureGpsTestProviderRegistered(): Boolean {
        val lm = locationManager ?: return false
        return try {
            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val props = ProviderProperties.Builder()
                    .setHasNetworkRequirement(false)
                    .setHasSatelliteRequirement(true)
                    .setHasCellRequirement(false)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    props
                )
            } else {
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false,
                    true,
                    false,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            }
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            isTestProviderRegistered = true
            true
        } catch (_: SecurityException) {
            isTestProviderRegistered = false
            false
        } catch (_: IllegalArgumentException) {
            return try {
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                isTestProviderRegistered = true
                true
            } catch (_: Exception) {
                isTestProviderRegistered = false
                false
            }
        } catch (_: Exception) {
            isTestProviderRegistered = false
            false
        }
    }

    private fun injectMockLocationIntoSystem(payload: GpsTelemetryPayload): Boolean {
        val lm = locationManager ?: return false
        if (!isTestProviderRegistered) {
            if (!ensureGpsTestProviderRegistered()) {
                return false
            }
        }

        return try {
            val mockLocation = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = payload.latitude
                longitude = payload.longitude
                altitude = payload.altitude
                speed = payload.speedMetersPerSec
                bearing = payload.bearingDegrees
                accuracy = payload.horizontalAccuracyMeters.coerceAtLeast(1.0f)
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = payload.verticalAccuracyMeters.coerceAtLeast(1.0f)
                    speedAccuracyMetersPerSecond = payload.speedAccuracyMps.coerceAtLeast(0.05f)
                    bearingAccuracyDegrees = payload.bearingAccuracyDegrees.coerceAtLeast(0.5f)
                }

                extras = Bundle().apply {
                    putInt("satellites", payload.satellitesInUse)
                    putLong("localnetgps_seq", payload.sequenceNumber)
                    putBoolean("baro_calibrated", payload.isBarometricAltitude)
                }
            }

            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, mockLocation)
            true
        } catch (_: SecurityException) {
            isTestProviderRegistered = false
            false
        } catch (_: Exception) {
            isTestProviderRegistered = false
            false
        }
    }

    private fun teardownGpsTestProvider() {
        val lm = locationManager ?: return
        try {
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        } catch (_: Exception) {
        }
        try {
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
        }
        isTestProviderRegistered = false
    }

    private fun recordRxAndCalculateMovingWindowHz(nowMs: Long): Float {
        synchronized(rxTimestampsMs) {
            rxTimestampsMs.addLast(nowMs)
            while (rxTimestampsMs.size > 8) {
                rxTimestampsMs.removeFirst()
            }
            if (rxTimestampsMs.size < 2) {
                return 1.0f
            }
            val oldest = rxTimestampsMs.first()
            val newest = rxTimestampsMs.last()
            val spanMs = (newest - oldest).coerceAtLeast(100L)
            val intervals = rxTimestampsMs.size - 1
            val rawHz = (intervals * 1000f) / spanMs.toFloat()
            return rawHz.coerceIn(0.1f, 20.0f)
        }
    }

    private fun updateReceiverMetrics(
        payload: GpsTelemetryPayload,
        rawLine: String,
        injected: Boolean
    ) {
        val now = System.currentTimeMillis()
        val movingWindowHz = recordRxAndCalculateMovingWindowHz(now)

        TelemetryRepository.appendReceiverLog(
            protocolTag = "NDJSON",
            rawLine = rawLine,
            mockInjected = injected
        )

        TelemetryRepository.updateReceiverState { state ->
            state.copy(
                status = if (injected) {
                    ReceiverEngineStatus.CONNECTED_STREAMING
                } else {
                    ReceiverEngineStatus.MOCK_PERMISSION_REQUIRED
                },
                statusDetail = if (injected) {
                    "Streaming from $targetIp:$targetPort • Mock Location Injected (#${payload.sequenceNumber})"
                } else {
                    "Receiving (#${payload.sequenceNumber}) • Set LocalNetGPS as Mock Location App in Developer Options"
                },
                latestReceivedFix = payload,
                mockProviderRegistered = injected,
                lastMockInjectionTimeMs = if (injected) now else state.lastMockInjectionTimeMs,
                totalMockLocationsInjected = if (injected) state.totalMockLocationsInjected + 1L else state.totalMockLocationsInjected,
                measuredRxFrequencyHz = movingWindowHz,
                wakeLockHeld = wakeLock?.isHeld == true,
                wifiLockHeld = wifiLock?.isHeld == true
            )
        }
        updateOngoingNotification()
    }

    @Suppress("DEPRECATION")
    private fun acquirePowerAndWifiLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LocalNetGPS::ReceiverWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        } catch (_: Exception) {
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wm != null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "LocalNetGPS::ReceiverWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releasePowerAndWifiLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_receiver_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing LAN/Hotspot Mock Location Receiver stream"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun updateOngoingNotification() {
        val state = TelemetryRepository.receiverState.value
        val fix = state.latestReceivedFix
        val title = when (state.status) {
            ReceiverEngineStatus.CONNECTED_STREAMING -> "Mock GPS Active • $targetIp:$targetPort"
            ReceiverEngineStatus.MOCK_PERMISSION_REQUIRED -> "Mock Permission Needed • $targetIp:$targetPort"
            ReceiverEngineStatus.RECONNECTING -> "Reconnecting (#${state.reconnectAttemptCount}) to $targetIp:$targetPort"
            else -> "Receiver Active • $targetIp:$targetPort"
        }
        val body = if (fix != null) {
            String.format(
                Locale.US,
                "Injected #%d: %.5f, %.5f • %.1f km/h (%.1f Hz)",
                state.totalMockLocationsInjected,
                fix.latitude,
                fix.longitude,
                fix.speedKmh,
                state.measuredRxFrequencyHz
            )
        } else {
            state.statusDetail
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(title, body))
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            200,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, this::class.java).apply {
            action = ACTION_STOP_RECEIVER
        }
        val stopPending = PendingIntent.getService(
            this,
            201,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop_service),
                stopPending
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "localnetgps_receiver_channel"
        private const val NOTIFICATION_ID = 4102

        const val ACTION_START_RECEIVER = "com.example.action.START_RECEIVER"
        const val ACTION_STOP_RECEIVER = "com.example.action.STOP_RECEIVER"

        const val EXTRA_SERVER_IP = "extra_server_ip"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_AUTO_DISCOVER = "extra_auto_discover"
    }
}
