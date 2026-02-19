
# DesignDoc: RFID Trigger Configuration & Event Subscription

**Release: v1.0.0**

## Summary
- RFIDHandler manages trigger for RFID and barcode modes
- Uses lock and busy-guard for safe, deadlock-free switching

## State Management
- `bRfidBusy`: true on inventory start, false on stop
- `resourceLock`: Ensures mutual exclusion for config
- `waitForReaderIdle()`: Bounded wait, fails fast on timeout

## Methods
- `setTriggerEnabled(boolean)`: Switches trigger mode
- `subscribeRfidHardwareTriggerEvents(boolean)`: Manages event callbacks

## Safety Rules
- Never block indefinitely
- Always release lock in finally
- Always check busy flag before config

## See Also
- `Trigger_Sync_Doc.md` for concurrency
- `design.md` for architecture
