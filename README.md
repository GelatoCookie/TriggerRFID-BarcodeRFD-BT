
# Zebra RFID SDK Sample Application

**Release: v1.1.0**

This project demonstrates integration with the Zebra RFID API3 SDK and barcode scanning libraries for Android. It provides a reference for connecting to Zebra RFID readers, performing inventory operations, and handling barcode scans in a modern Android environment.

## Features
- Modern UI notifications (Snackbars)
- Connect/disconnect Zebra RFID readers via Bluetooth
- Real-time RFID inventory and tag display
- Barcode scanning support
- Hardware trigger reconfiguration (RFID/Barcode)
- Thread-safe, deadlock-free trigger switching

## Quick Start
1. Clone the repository and open in Android Studio (Flamingo or newer)
2. Place Zebra .aar libraries in `app/libs`
3. Build and deploy to an Android device (API 28+)
4. Use `build_deploy_launch.sh` for automated build/deploy

## Usage
- Tap status to connect to a reader
- Start/stop inventory with UI or hardware trigger
- Scan barcodes using the trigger in barcode mode
- View discovered tags and RSSI in the app

## Documentation
- See `design.md` for architecture
- See `Trigger_Sync_Doc.md` for trigger sync and concurrency
- See `RELEASE_NOTES.md` and `history.md` for release info
- See tag v1.1.0 for latest release

## License
See LICENSE for details.
