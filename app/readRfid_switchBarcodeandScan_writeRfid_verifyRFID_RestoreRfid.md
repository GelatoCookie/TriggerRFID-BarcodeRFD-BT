# Design Flow: Read RFID -> Switch Barcode & Scan -> Write RFID -> Verify RFID -> Restore RFID

This document describes the state machine and logic flow for the **Integrated Test Mode** (`test_read_write_verify`) implemented in the `RFIDHandler` class.

## Overview

The Integrated Test Mode demonstrates a complex transition between RFID and Barcode hardware trigger modes on the Zebra RFD40 reader. It uses three primary state variables to coordinate events:
- `bRfidBusy`: Tracks if an RFID operation (like inventory) is currently active.
- `bSwitchFromRfidToBarcode`: A flag that blocks RFID trigger handling during the transition to barcode mode.
- `MainActivity.bTest_ReadRfid_ConfigureBarcodeWriteRfidVerify_RestoreRFIDTrigger`: The UI-level flag that enables this specific test logic.

---

## Phase 1: RFID Read (Initial State)

1.  **Activation**: User selects "Test Read-Write-Verify" from the UI menu.
2.  **State**:
    - `bRfidBusy = false`
    - `bSwitchFromRfidToBarcode = false`
    - Integrated Test Flag = `true`
3.  **Action**: User pulls the physical trigger.
4.  **Event**: `handleTriggerEvent` detects `HANDHELD_TRIGGER_PRESSED`.
5.  **Execution**: `performInventory()` is called.
6.  **Busy State**: `INVENTORY_START_EVENT` is received, setting `bRfidBusy = true`.

---

## Phase 2: Transition to Barcode Input

1.  **Action**: User releases the physical trigger.
2.  **Event**: `INVENTORY_STOP_EVENT` is received.
3.  **State Update**:
    - `bRfidBusy` is set to `false`.
    - `bSwitchFromRfidToBarcode` is set to `true`.
4.  **Logic Branch**: Since the integrated test flag is `true`, the following occurs:
    - `subscribeRfidHardwareTriggerEvents(false)`: Disables further RFID trigger notifications from the reader.
    - `testBarcodeInputRfidWriteVerify()`: Initiates the hardware switch.
    - `setTriggerEnabled(false)`: Physically reconfigures the reader's key layout to `SLED_SCAN` (Barcode) mode.
5.  **UI**: User is prompted via Snackbar: "Pull Trigger: Scan Barcode for write tag data input".

---

## Phase 3: Barcode Scan & RFID Write/Verify

1.  **Action**: User pulls the trigger again.
2.  **Execution**: The hardware, now in Barcode mode, performs a laser scan. No RFID trigger events are generated.
3.  **Data Received**: `MainActivity.barcodeData(val)` is called by the Scanner SDK.
4.  **Write Operation**: `RFIDHandler.testWriteTag()` is invoked.
    - It uses `reader.Actions.TagAccess.writeWait()`.
    - This is a **synchronous** access operation that bypasses the inventory trigger logic.
5.  **Verification**: `RFIDHandler.verifyWriteTag()` is invoked.
    - It uses `reader.Actions.TagAccess.readWait()` to confirm the data was written correctly to the EPC memory bank.

---

## Phase 4: Restoration to RFID Mode

1.  **Success**: If verification passes:
    - Integrated Test Flag in `MainActivity` is reset to `false`.
    - `RFIDHandler.setTriggerEnabled(true)` is called.
2.  **Hardware Update**: `setTriggerEnabled(true)` physically reconfigures the reader back to `RFID` mode.
3.  **State Reset**:
    - `bSwitchFromRfidToBarcode` is set to `false`.
    - RFID trigger events are re-subscribed.
4.  **UI**: User is notified: "Test Passed: read, write and verify OK".

---

## Critical Logic Checks

- **Trigger Debounce**: `bSwitchFromRfidToBarcode` and `subscribeRfidHardwareTriggerEvents(false)` are used immediately upon inventory stop to prevent "echo" trigger events from causing accidental RFID restarts while switching modes.
- **Busy Safety**: `setTriggerEnabled` checks `bRfidBusy` to ensure hardware configuration changes don't occur while the RFID engine is active.
- **Synchronous Access**: Using `writeWait` and `readWait` allows for precise, single-tag operations during the period where the hardware trigger is technically assigned to the barcode scanner.
