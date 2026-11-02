package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.example.model.DiscoveredServerNode
import com.example.model.LocalNetworkInterfaceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Inspects local LAN / Wi-Fi / Mobile Hotspot interfaces and manages zero-config
 * UDP Broadcast Discovery on port 5556 so Receiver tablets discover Transmitter phones automatically.
 */
object NetworkDiscoveryManager {

    const val DEFAULT_TCP_PORT = 5555
    const val UDP_DISCOVERY_PORT = 5556
    const val DEFAULT_HOTSPOT_GATEWAY_IP = "192.168.43.1"
    private const val BEACON_PREFIX = "LOCALNETGPS_BEACON"

    /**
     * Enumerates all non-loopback IPv4 interfaces, prioritizing Wi-Fi Hotspot (ap0, swlan0, wlan1)
     * and Wi-Fi LAN (wlan0) interfaces.
     */
    fun getActiveLocalInterfaces(): List<LocalNetworkInterfaceInfo> {
        val results = mutableListOf<LocalNetworkInterfaceInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val isHotspotOrTether = name.startsWith("ap") ||
                    name.startsWith("swlan") ||
                    name.startsWith("rndis") ||
                    name.contains("tether") ||
                    name.contains("p2p")

                for (addr in nif.interfaceAddresses) {
                    val inet = addr.address
                    if (inet is Inet4Address && !inet.isLoopbackAddress) {
                        val ip = inet.hostAddress ?: continue
                        val bcast = addr.broadcast?.hostAddress ?: deriveSubnetBroadcast(ip)
                        results.add(
                            LocalNetworkInterfaceInfo(
                                interfaceName = nif.name,
                                ipv4Address = ip,
                                broadcastAddress = bcast,
                                isHotspotOrTether = isHotspotOrTether || ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.")
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore interface enumeration exceptions on restricted networks
        }

        return results.sortedWith(
            compareByDescending<LocalNetworkInterfaceInfo> { it.isHotspotOrTether }
                .thenByDescending { it.interfaceName.startsWith("wlan") }
        )
    }

    /**
     * Detects the upstream Wi-Fi / Hotspot gateway IP (e.g., when a Receiver Tablet is connected
     * to a Transmitter Phone's Mobile Hotspot). Falls back to 192.168.43.1.
     */
    @Suppress("DEPRECATION")
    fun detectGatewayIp(context: Context): String {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val linkProps = if (activeNet != null) cm.getLinkProperties(activeNet) else null
            if (linkProps != null) {
                for (route in linkProps.routes) {
                    val gw = route.gateway
                    if (gw is Inet4Address && !gw.isLoopbackAddress && gw.hostAddress != "0.0.0.0") {
                        val host = gw.hostAddress
                        if (!host.isNullOrBlank()) {
                            return host
                        }
                    }
                }
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val gwIp = intToIpv4(dhcp.gateway)
                if (gwIp != "0.0.0.0") return gwIp
            }
        } catch (_: Exception) {
        }
        return DEFAULT_HOTSPOT_GATEWAY_IP
    }

    private fun intToIpv4(ipInt: Int): String {
        return "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
    }

    private fun deriveSubnetBroadcast(ipv4: String): String {
        val parts = ipv4.split(".")
        return if (parts.size == 4) {
            "${parts[0]}.${parts[1]}.${parts[2]}.255"
        } else {
            "255.255.255.255"
        }
    }

    /**
     * Periodically broadcasts a UDP discovery beacon across all local interfaces and 255.255.255.255
     * so Receiver devices can discover the Transmitter without manual IP entry.
     */
    fun startUdpBeaconBroadcaster(
        scope: CoroutineScope,
        tcpPortProvider: () -> Int,
        sequenceProvider: () -> Long
    ): Job {
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().replace("|", "-")
        return scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                }
                while (isActive) {
                    val interfaces = getActiveLocalInterfaces()
                    val tcpPort = tcpPortProvider()
                    val seq = sequenceProvider()

                    val targets = mutableSetOf<Pair<String, String>>()
                    for (iface in interfaces) {
                        targets.add(iface.ipv4Address to iface.broadcastAddress)
                        targets.add(iface.ipv4Address to "255.255.255.255")
                    }
                    if (targets.isEmpty()) {
                        targets.add(DEFAULT_HOTSPOT_GATEWAY_IP to "255.255.255.255")
                    }

                    for ((sourceIp, broadcastIp) in targets) {
                        try {
                            val payload = "$BEACON_PREFIX|$sourceIp|$tcpPort|$deviceModel|$seq"
                            val bytes = payload.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(
                                bytes,
                                bytes.size,
                                InetAddress.getByName(broadcastIp),
                                UDP_DISCOVERY_PORT
                            )
                            socket.send(packet)
                        } catch (_: Exception) {
                        }
                    }
                    delay(1500L)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Listens for UDP discovery beacons on port 5556 while holding a WifiManager.MulticastLock
     * so Android's Wi-Fi chipset passes broadcast UDP frames to user space.
     */
    fun startUdpBeaconListener(
        context: Context,
        scope: CoroutineScope,
        onServerDiscovered: (DiscoveredServerNode) -> Unit
    ): Job {
        return scope.launch(Dispatchers.IO) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifiManager?.createMulticastLock("LocalNetGPS:UdpDiscoveryLock")?.apply {
                setReferenceCounted(false)
                try {
                    acquire()
                } catch (_: Exception) {
                }
            }

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2500
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                val buffer = ByteArray(512)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (text.startsWith("$BEACON_PREFIX|")) {
                            val parts = text.split("|")
                            if (parts.size >= 5) {
                                val advertisedIp = parts[1].ifBlank {
                                    packet.address?.hostAddress ?: DEFAULT_HOTSPOT_GATEWAY_IP
                                }
                                val senderIp = packet.address?.hostAddress ?: advertisedIp
                                val resolvedIp = if (advertisedIp == "0.0.0.0") senderIp else advertisedIp
                                val port = parts[2].toIntOrNull() ?: DEFAULT_TCP_PORT
                                val deviceName = parts[3]
                                val seq = parts[4].toLongOrNull() ?: 0L

                                onServerDiscovered(
                                    DiscoveredServerNode(
                                        ipAddress = resolvedIp,
                                        tcpPort = port,
                                        deviceName = deviceName,
                                        lastSeenMs = System.currentTimeMillis(),
                                        latestSequence = seq
                                    )
                                )
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal timeout to re-check coroutine cancellation
                    } catch (_: Exception) {
                        delay(500L)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                try {
                    if (multicastLock?.isHeld == true) {
                        multicastLock.release()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
