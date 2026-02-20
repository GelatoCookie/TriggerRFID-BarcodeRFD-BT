# Summary: Integrated Test Design Flow

This document provides a high-level summary of the state-machine logic used to synchronize RFID and Barcode operations on the Zebra RFD40 reader.

## Core Objective
To enable a seamless transition from an RFID inventory operation to a Barcode scan, followed by a specific RFID tag write/verify sequence, all managed via a single hardware trigger.

## Key State Variables
- **`bRfidBusy`**: Prevents configuration changes while the RFID engine is active (Inventory in progress).
- **`bSwitchFromRfidToBarcode`**: A "guard" flag that blocks RFID trigger event processing during mode transitions to prevent debouncing issues.
- **`Integrated Test Flag`**: Controls the automated progression through the test phases.

## Execution Summary
1. **Initial RFID Read**: Trigger pull/release executes a standard RFID inventory.
2. **Dynamic Transition**: Upon inventory stop, the system immediately disables RFID trigger notifications and reconfigures the hardware trigger to Barcode (SLED_SCAN) mode.
3. **Barcode Input & Targeted Write**: The next trigger pull executes a laser scan. The returned barcode data is then used to trigger a synchronous `writeWait` operation to a specific RFID tag, followed by a `readWait` verification.
4. **Automatic Restoration**: Once the write is verified, the system restores the hardware trigger to RFID mode and resets all guard flags for normal operation.

## Reliability Features
- **Trigger Debouncing**: Guard flags ensure "echo" release events from one mode don't accidentally trigger the other.
- **Synchronous Access**: Uses `writeWait` and `readWait` to bypass standard inventory logic for precise data writing during the test.
- **Busy Protection**: All hardware mode switches are gated by `bRfidBusy` checks.
