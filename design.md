
# RFID SDK Sample: Design Overview

**Release: v1.0.0**

## Architecture
- **MainActivity**: UI logic, user interaction, and RFID/Barcode event callbacks
- **RFIDHandler**: Manages RFID reader connection, inventory, and trigger events
- **ScannerHandler**: Handles barcode scanning and session management
- **Threading**: Uses ExecutorService for all device operations (off UI thread)
- **Permissions**: Handles Bluetooth/location permissions (Android 12+)

## Trigger Sync & Safety
- **Busy Guard (`bRfidBusy`)**: Prevents mode switching while RFID is active
- **Lock (`resourceLock`)**: Serializes trigger config to avoid SDK overlap
- **Bounded Wait**: `waitForReaderIdle()` ensures no indefinite blocking
- **Safe Switch**: Always unsubscribe events before hardware switch, and vice versa

## Extensibility
- Easily add new reader models or UI features
- All main classes documented for maintainability

## See Also
- `Trigger_Sync_Doc.md` for concurrency and trigger switching
- `README.md` for usage
- `RELEASE_NOTES.md` for release info
