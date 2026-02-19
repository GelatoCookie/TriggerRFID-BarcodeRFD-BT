# Trigger Sync & Deadlock-Safe Switching

## Purpose
Safely switch the RFD40 trigger between RFID inventory and barcode scanning modes, avoiding SDK errors and deadlocks.

## Concurrency Model
- **Single worker thread**: Serializes all operations
- **Lock (`resourceLock`)**: Wraps all trigger config methods
- **Busy flag (`bRfidBusy`)**: Tracks RFID operation state

## Busy Guard Logic
- `bRfidBusy = true` on inventory start, `false` on stop
- All config changes check `bRfidBusy` before proceeding
- `waitForReaderIdle()` polls with timeout to ensure idle

## Safe Switch Sequence
- **RFID → Barcode**: Unsubscribe events, then switch hardware
- **Barcode → RFID**: Switch hardware, then subscribe events

## Failure Handling
- On timeout or SDK error, return `false` and release lock
- Never block indefinitely or leave lock held

## Verification
- Press trigger repeatedly during mode switch: app must not freeze
- Confirm trigger events are ignored in barcode mode, resume in RFID mode

## See Also
- `design.md` for architecture
- `README.md` for usage
- `RELEASE_NOTES.md` for release info
