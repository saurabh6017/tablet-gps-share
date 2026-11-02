package com.example.service

/**
 * Backwards-compatible alias service delegating directly to [GpsServerService].
 */
class GpsTransmitterService : GpsServerService() {
    companion object {
        const val ACTION_START_SERVER = GpsServerService.ACTION_START_SERVER
        const val ACTION_STOP_SERVER = GpsServerService.ACTION_STOP_SERVER
        const val ACTION_SET_SIMULATION_MODE = GpsServerService.ACTION_SET_SIMULATION_MODE
        const val ACTION_SET_IMPROVE_ALTITUDE = GpsServerService.ACTION_SET_IMPROVE_ALTITUDE

        const val EXTRA_TCP_PORT = GpsServerService.EXTRA_TCP_PORT
        const val EXTRA_SIMULATION_ENABLED = GpsServerService.EXTRA_SIMULATION_ENABLED
        const val EXTRA_IMPROVE_ALTITUDE_ENABLED = GpsServerService.EXTRA_IMPROVE_ALTITUDE_ENABLED
    }
}
