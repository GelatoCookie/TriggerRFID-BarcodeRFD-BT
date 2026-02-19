
# Trigger Event Handling Design

**Release: v1.0.0**

## Overview
The RFIDHandler manages the Zebra RFID reader's hardware trigger for two modes:
- **RFID Mode**: Trigger starts/stops RFID inventory
- **Barcode Mode**: Trigger activates barcode scanner

## Synchronization & Safety
- **resourceLock**: Ensures only one config at a time
- **bRfidBusy**: Prevents config changes while inventory is active
- **waitForReaderIdle()**: Bounded wait for safe switching

## Core Methods
- `setTriggerEnabled(boolean)`: Switches trigger mode, guarded by lock and busy flag
- `subscribeRfidHardwareTriggerEvents(boolean)`: Enables/disables trigger event callbacks

## Safe Switch Workflow
- Unsubscribe events before switching to barcode
- Switch hardware before subscribing events for RFID

## See Also
- `Trigger_Sync_Doc.md` for concurrency
- `design.md` for architecture
