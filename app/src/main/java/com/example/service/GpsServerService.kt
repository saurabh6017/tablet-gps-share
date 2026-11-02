package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.TelemetryRepository
import com.example.model.GpsTelemetryPayload
import com.example.model.NmeaCodec
import com.example.model.ServerEngineStatus
import com.example.network.NetworkDiscoveryManager
import com.example.sensor.BarometerQnhManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin

open class GpsServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationManager: LocationManager? = null
    private var locationCallback: LocationCallback? = null
    private var fallbackLocationListener: LocationListener? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private lateinit var barometerQnhManager: BarometerQnhManager

    private var serverSocket: ServerSocket? = null
    private var serverAcceptJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var orbitalSimJob: Job? = null

    private data class ConnectedClientSession(
        val socket: Socket,
        val writer: BufferedWriter,
        val remoteLabel: String
    )

    private val connectedClients = CopyOnWriteArrayList<ConnectedClientSession>()
    private val sequenceCounter = AtomicLong(0L)
    private val activeSatellitesInUse = AtomicInteger(11)

    // Moving-window packet timestamp queue for real-time transmission cadence (Hz)
    private val packetTimestampsMs = ArrayDeque<Long>(10)
    private var configuredTcpPort: Int = NetworkDiscoveryManager.DEFAULT_TCP_PORT

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        barometerQnhManager = BarometerQnhManager(this, serviceScope)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                stopTransmitterEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_SIMULATION_MODE -> {
                val enabled = intent.getBooleanExtra(EXTRA_SIMULATION_ENABLED, false)
                TelemetryRepository.updateTransmitterState {
                    it.copy(isIndoorOrbitalSimEnabled = enabled)
                }
                if (TelemetryRepository.transmitterState.value.status == ServerEngineStatus.ACTIVE_BROADCASTING) {
                    if (enabled) startIndoorOrbitalSimLoop() else stopIndoorOrbitalSimLoop()
                }
                return START_STICKY
            }
            ACTION_SET_IMPROVE_ALTITUDE -> {
                val enabled = intent.getBooleanExtra(EXTRA_IMPROVE_ALTITUDE_ENABLED, false)
                TelemetryRepository.updateTransmitterState {
                    it.copy(improveAltitudeExperimental = enabled)
                }
                if (enabled) {
                    barometerQnhManager.start()
                    TelemetryRepository.transmitterState.value.latestLocationFix?.let { fix ->
                        barometerQnhManager.resolveAltitudeCalibration(fix.latitude, fix.longitude)
                    }
                } else {
                    barometerQnhManager.stop()
                    TelemetryRepository.updateTransmitterState {
                        it.copy(
                            barometerStatusBadge = null,
                            barometerUsingFallbackGps = false
                        )
                    }
                }
                return START_STICKY
            }
            else -> {
                val requestedPort = intent?.getIntExtra(
                    EXTRA_TCP_PORT,
                    NetworkDiscoveryManager.DEFAULT_TCP_PORT
                ) ?: NetworkDiscoveryManager.DEFAULT_TCP_PORT
                val simEnabled = intent?.getBooleanExtra(
                    EXTRA_SIMULATION_ENABLED,
                    TelemetryRepository.transmitterState.value.isIndoorOrbitalSimEnabled
                ) ?: false
                val improveAlt = intent?.getBooleanExtra(
                    EXTRA_IMPROVE_ALTITUDE_ENABLED,
                    TelemetryRepository.transmitterState.value.improveAltitudeExperimental
                ) ?: TelemetryRepository.transmitterState.value.improveAltitudeExperimental

                configuredTcpPort = requestedPort.coerceIn(1024, 65535)
                startTransmitterEngine(configuredTcpPort, simEnabled, improveAlt)
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTransmitterEngine()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTransmitterEngine(
        port: Int,
        enableOrbitalSim: Boolean,
        enableImproveAltitude: Boolean
    ) {
        if (!hasLocationPermission()) {
            TelemetryRepository.updateTransmitterState {
                it.copy(
                    status = ServerEngineStatus.ERROR,
                    statusDetail = "Location permission required to start GPS server"
                )
            }
            stopSelf()
            return
        }

        val initialNotification = buildNotification(
            "Starting GPS Server on port $port...",
            "Acquiring background wake locks & satellite fix"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            TelemetryRepository.updateTransmitterState {
                it.copy(
                    status = ServerEngineStatus.ERROR,
                    statusDetail = "Foreground Service start blocked: ${e.localizedMessage}"
                )
            }
            stopSelf()
            return
        }

        acquirePowerAndWifiLocks()
        synchronized(packetTimestampsMs) {
            packetTimestampsMs.clear()
        }

        if (enableImproveAltitude) {
            barometerQnhManager.start()
        } else {
            barometerQnhManager.stop()
        }

        val interfaces = NetworkDiscoveryManager.getActiveLocalInterfaces()
        val gatewayIp = NetworkDiscoveryManager.detectGatewayIp(this)

        TelemetryRepository.updateTransmitterState {
            it.copy(
                status = ServerEngineStatus.STARTING,
                statusDetail = "Opening server port :$port and locking onto satellites...",
                tcpPort = port,
                localInterfaces = interfaces,
                detectedGatewayIp = gatewayIp,
                wakeLockHeld = wakeLock?.isHeld == true,
                wifiLockHeld = wifiLock?.isHeld == true,
                isIndoorOrbitalSimEnabled = enableOrbitalSim,
                improveAltitudeExperimental = enableImproveAltitude,
                barometerAvailable = barometerQnhManager.hasBarometerHardware()
            )
        }

        startTcpServerSocket(port)
        startGnssAndFusedSampling()
        if (enableOrbitalSim) {
            startIndoorOrbitalSimLoop()
        }

        udpBeaconJob?.cancel()
        udpBeaconJob = NetworkDiscoveryManager.startUdpBeaconBroadcaster(
            scope = serviceScope,
            tcpPortProvider = { configuredTcpPort },
            sequenceProvider = { sequenceCounter.get() }
        )
    }

    private fun stopTransmitterEngine() {
        udpBeaconJob?.cancel()
        udpBeaconJob = null
        stopIndoorOrbitalSimLoop()
        stopGnssAndFusedSampling()
        barometerQnhManager.stop()
        closeAllSockets()
        releasePowerAndWifiLocks()
        synchronized(packetTimestampsMs) {
            packetTimestampsMs.clear()
        }

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
    }

    @Suppress("DEPRECATION")
    private fun acquirePowerAndWifiLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LocalNetGPS::TransmitterWakeLock"
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
                wifiLock = wm.createWifiLock(lockMode, "LocalNetGPS::TransmitterWifiLock").apply {
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

    private fun startTcpServerSocket(port: Int) {
        serverAcceptJob?.cancel()
        serverAcceptJob = serviceScope.launch {
            try {
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }

                val srv = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port), 16)
                }
                serverSocket = srv

                val primaryIp = NetworkDiscoveryManager.getActiveLocalInterfaces()
                    .firstOrNull()?.ipv4Address ?: NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP

                TelemetryRepository.updateTransmitterState {
                    it.copy(
                        status = ServerEngineStatus.ACTIVE_BROADCASTING,
                        statusDetail = "Broadcasting live on $primaryIp:$port (Auto-Discovery Active)",
                        localInterfaces = NetworkDiscoveryManager.getActiveLocalInterfaces()
                    )
                }
                updateOngoingNotification()

                while (isActive && !srv.isClosed) {
                    val clientSocket = srv.accept()
                    clientSocket.tcpNoDelay = true
                    clientSocket.keepAlive = true
                    val remoteLabel = "${clientSocket.inetAddress?.hostAddress ?: "tablet"}:${clientSocket.port}"
                    val writer = BufferedWriter(
                        OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8)
                    )
                    val session = ConnectedClientSession(clientSocket, writer, remoteLabel)
                    connectedClients.add(session)
                    syncClientListToRepository()

                    TelemetryRepository.transmitterState.value.latestLocationFix?.let { latest ->
                        sendPayloadToSingleClient(session, latest)
                    }
                    updateOngoingNotification()
                }
            } catch (e: Exception) {
                if (isActive) {
                    TelemetryRepository.updateTransmitterState {
                        it.copy(
                            status = ServerEngineStatus.ERROR,
                            statusDetail = "Unable to open port :$port (${e.localizedMessage})"
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGnssAndFusedSampling() {
        if (!hasLocationPermission()) return

        try {
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var used = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) used++
                    }
                    if (used > 0) {
                        activeSatellitesInUse.set(used)
                    }
                }
            }
            gnssStatusCallback = callback
            locationManager?.registerGnssStatusCallback(
                callback,
                android.os.Handler(Looper.getMainLooper())
            )
        } catch (_: Exception) {
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(1000L)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onHardwareLocationAcquired(loc)
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            )
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null && TelemetryRepository.transmitterState.value.latestLocationFix == null) {
                    onHardwareLocationAcquired(lastLoc)
                }
            }
        } catch (_: Exception) {
        }

        try {
            val directListener = LocationListener { loc ->
                onHardwareLocationAcquired(loc)
            }
            fallbackLocationListener = directListener
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    directListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun stopGnssAndFusedSampling() {
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (_: Exception) {
        }
        try {
            fallbackLocationListener?.let { locationManager?.removeUpdates(it) }
        } catch (_: Exception) {
        }
        try {
            gnssStatusCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
        } catch (_: Exception) {
        }
        locationCallback = null
        fallbackLocationListener = null
        gnssStatusCallback = null
    }

    private fun onHardwareLocationAcquired(location: Location) {
        if (TelemetryRepository.transmitterState.value.isIndoorOrbitalSimEnabled) return
        val seq = sequenceCounter.incrementAndGet()

        val useBaro = TelemetryRepository.transmitterState.value.improveAltitudeExperimental
        val baroResult = if (useBaro) {
            barometerQnhManager.resolveAltitudeCalibration(location.latitude, location.longitude)
        } else {
            null
        }

        val payload = GpsTelemetryPayload.fromAndroidLocation(
            location = location,
            sequenceNumber = seq,
            satellitesOverride = activeSatellitesInUse.get(),
            isSimulated = false,
            calibratedBaroAltitudeMeters = baroResult?.calibratedAltitudeMeters,
            measuredPressureHpa = baroResult?.measuredPressureHpa,
            qnhReferenceHpa = baroResult?.qnhHpa
        )
        dispatchTelemetryPayload(payload)
    }

    /**
     * Simulate Driving Route: produces a realistic continuous 1Hz car movement loop
     * for testing indoors without physical satellite signal.
     */
    private fun startIndoorOrbitalSimLoop() {
        orbitalSimJob?.cancel()
        orbitalSimJob = serviceScope.launch {
            var angleRad = 0.0
            val centerLat = 37.422065
            val centerLon = -122.084089
            val radiusDeg = 0.0022

            while (isActive && TelemetryRepository.transmitterState.value.isIndoorOrbitalSimEnabled) {
                val seq = sequenceCounter.incrementAndGet()
                val lat = centerLat + radiusDeg * sin(angleRad)
                val lon = centerLon + radiusDeg * cos(angleRad)
                val heading = ((Math.toDegrees(angleRad) + 90.0) % 360.0).toFloat()
                val speedMps = 14.8f + (sin(angleRad * 2.0) * 2.4).toFloat()

                val useBaro = TelemetryRepository.transmitterState.value.improveAltitudeExperimental
                val baroResult = if (useBaro) {
                    barometerQnhManager.resolveAltitudeCalibration(lat, lon)
                } else {
                    null
                }
                val simulatedGpsAlt = 34.5 + sin(angleRad) * 3.2
                val resolvedAlt = baroResult?.calibratedAltitudeMeters ?: simulatedGpsAlt

                val draft = GpsTelemetryPayload(
                    latitude = lat,
                    longitude = lon,
                    altitude = resolvedAlt,
                    speedMetersPerSec = speedMps,
                    bearingDegrees = if (heading < 0f) heading + 360f else heading,
                    horizontalAccuracyMeters = 1.8f,
                    verticalAccuracyMeters = if (baroResult?.calibratedAltitudeMeters != null) 1.2f else 2.4f,
                    speedAccuracyMps = 0.12f,
                    bearingAccuracyDegrees = 1.1f,
                    satellitesInUse = 15,
                    timestampUtcMs = System.currentTimeMillis(),
                    sequenceNumber = seq,
                    providerName = if (baroResult?.calibratedAltitudeMeters != null) "sim_route+baro" else "sim_route",
                    isSimulatedTestPattern = true,
                    isBarometricAltitude = baroResult?.calibratedAltitudeMeters != null,
                    barometricPressureHpa = baroResult?.measuredPressureHpa,
                    qnhReferenceHpa = baroResult?.qnhHpa
                )
                val fullPayload = draft.copy(
                    nmeaGga = NmeaCodec.buildGpgga(draft),
                    nmeaRmc = NmeaCodec.buildGprmc(draft)
                )
                dispatchTelemetryPayload(fullPayload)
                angleRad += 0.055
                delay(1000L)
            }
        }
    }

    private fun stopIndoorOrbitalSimLoop() {
        orbitalSimJob?.cancel()
        orbitalSimJob = null
    }

    /**
     * Computes moving-window transmission frequency (Hz) from recent packet intervals.
     */
    private fun recordPacketAndCalculateMovingWindowHz(nowMs: Long): Float {
        synchronized(packetTimestampsMs) {
            packetTimestampsMs.addLast(nowMs)
            while (packetTimestampsMs.size > 8) {
                packetTimestampsMs.removeFirst()
            }
            if (packetTimestampsMs.size < 2) {
                return 1.0f
            }
            val oldest = packetTimestampsMs.first()
            val newest = packetTimestampsMs.last()
            val spanMs = (newest - oldest).coerceAtLeast(100L)
            val intervals = packetTimestampsMs.size - 1
            val rawHz = (intervals * 1000f) / spanMs.toFloat()
            return rawHz.coerceIn(0.1f, 20.0f)
        }
    }

    private fun dispatchTelemetryPayload(payload: GpsTelemetryPayload) {
        val now = System.currentTimeMillis()
        val movingWindowHz = recordPacketAndCalculateMovingWindowHz(now)

        val jsonLine = payload.toCompactJsonLine()
        val includeRawNmea = TelemetryRepository.transmitterState.value.includeRawNmeaOnWire

        serviceScope.launch {
            var bytesSentThisCycle = 0L
            val deadSessions = mutableListOf<ConnectedClientSession>()

            for (session in connectedClients) {
                try {
                    synchronized(session.writer) {
                        session.writer.write(jsonLine)
                        session.writer.write("\n")
                        bytesSentThisCycle += jsonLine.length + 1
                        if (includeRawNmea) {
                            session.writer.write(payload.nmeaGga)
                            session.writer.write("\n")
                            session.writer.write(payload.nmeaRmc)
                            session.writer.write("\n")
                            bytesSentThisCycle += payload.nmeaGga.length + payload.nmeaRmc.length + 2
                        }
                        session.writer.flush()
                    }
                } catch (_: Exception) {
                    deadSessions.add(session)
                }
            }

            if (deadSessions.isNotEmpty()) {
                for (dead in deadSessions) {
                    try {
                        dead.socket.close()
                    } catch (_: Exception) {
                    }
                    connectedClients.remove(dead)
                }
                syncClientListToRepository()
            }

            TelemetryRepository.appendTransmitterLog("NDJSON", jsonLine)
            if (includeRawNmea) {
                TelemetryRepository.appendTransmitterLog("NMEA", payload.nmeaGga)
            }

            val interfaces = NetworkDiscoveryManager.getActiveLocalInterfaces()
            TelemetryRepository.updateTransmitterState { state ->
                state.copy(
                    latestLocationFix = payload,
                    measuredFrequencyHz = movingWindowHz,
                    packetsTransmitted = state.packetsTransmitted + 1L,
                    bytesTransmitted = state.bytesTransmitted + bytesSentThisCycle,
                    localInterfaces = interfaces,
                    wakeLockHeld = wakeLock?.isHeld == true,
                    wifiLockHeld = wifiLock?.isHeld == true
                )
            }
            updateOngoingNotification()
        }
    }

    private fun sendPayloadToSingleClient(
        session: ConnectedClientSession,
        payload: GpsTelemetryPayload
    ) {
        serviceScope.launch {
            try {
                val jsonLine = payload.toCompactJsonLine()
                synchronized(session.writer) {
                    session.writer.write(jsonLine)
                    session.writer.write("\n")
                    session.writer.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun syncClientListToRepository() {
        val list = connectedClients.map { it.remoteLabel }
        TelemetryRepository.updateTransmitterState {
            it.copy(connectedClientAddresses = list)
        }
    }

    private fun closeAllSockets() {
        serverAcceptJob?.cancel()
        serverAcceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        for (session in connectedClients) {
            try {
                session.socket.close()
            } catch (_: Exception) {
            }
        }
        connectedClients.clear()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_transmitter_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing high-precision GPS Server transmission over LAN/Hotspot"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun updateOngoingNotification() {
        val state = TelemetryRepository.transmitterState.value
        val fix = state.latestLocationFix
        val primaryIp = state.localInterfaces.firstOrNull()?.ipv4Address
            ?: NetworkDiscoveryManager.DEFAULT_HOTSPOT_GATEWAY_IP
        val title = "GPS Server Active ($primaryIp:${state.tcpPort}) • ${state.connectedClientAddresses.size} tablet(s)"
        val body = if (fix != null) {
            String.format(
                Locale.US,
                "Fix %.5f, %.5f • %.1f km/h • %.1f Hz (#%d)",
                fix.latitude,
                fix.longitude,
                fix.speedKmh,
                state.measuredFrequencyHz,
                fix.sequenceNumber
            )
        } else {
            "Waiting for satellite GPS signal (or enable Simulate Driving Route)..."
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
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, this::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
        private const val CHANNEL_ID = "localnetgps_transmitter_channel"
        private const val NOTIFICATION_ID = 4101

        const val ACTION_START_SERVER = "com.example.action.START_TRANSMITTER"
        const val ACTION_STOP_SERVER = "com.example.action.STOP_TRANSMITTER"
        const val ACTION_SET_SIMULATION_MODE = "com.example.action.SET_SIMULATION_MODE"
        const val ACTION_SET_IMPROVE_ALTITUDE = "com.example.action.SET_IMPROVE_ALTITUDE"

        const val EXTRA_TCP_PORT = "extra_tcp_port"
        const val EXTRA_SIMULATION_ENABLED = "extra_simulation_enabled"
        const val EXTRA_IMPROVE_ALTITUDE_ENABLED = "extra_improve_altitude_enabled"
    }
}
