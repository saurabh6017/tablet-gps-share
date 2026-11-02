package com.example.service

/**
 * Backwards-compatible alias service delegating directly to [GpsClientService].
 */
class GpsReceiverService : GpsClientService() {
    companion object {
        const val ACTION_START_RECEIVER = GpsClientService.ACTION_START_RECEIVER
        const val ACTION_STOP_RECEIVER = GpsClientService.ACTION_STOP_RECEIVER

        const val EXTRA_SERVER_IP = GpsClientService.EXTRA_SERVER_IP
        const val EXTRA_SERVER_PORT = GpsClientService.EXTRA_SERVER_PORT
        const val EXTRA_AUTO_DISCOVER = GpsClientService.EXTRA_AUTO_DISCOVER
    }
}
