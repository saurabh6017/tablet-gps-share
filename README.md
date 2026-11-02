# LocalNetGPS: Wi-Fi GPS Tether & Mock Location Bridge for Android

Turn any Android phone into a high-accuracy GPS broadcaster for Wi-Fi-only tablets and secondary devices over local Wi-Fi or mobile hotspot. 

No root required—LocalNetGPS uses Android's native Mock Location framework to feed live location data directly into Google Maps, OsmAnd, Waze, and other navigation apps.

### ✨ Key Features
- **All-in-One APK:** Switch seamlessly between Transmitter (Phone) and Receiver (Tablet) modes.
- **Zero-Config Streaming:** Fast local TCP/UDP socket broadcasting with hotspot auto-detection.
- **Mock Location Injection:** Injects coordinates directly into system GPS provider with sub-second latency.
- **Barometric QNH Altitude (Experimental):** Auto-calibrates altitude using phone pressure sensors against local airport reference pressure.
- **Session Protection:** Automatic UI lockout while transmitting/receiving to prevent accidental configuration drops.
