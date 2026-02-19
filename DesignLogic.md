
# RFIDHandler Trigger State Management: Design Logic

**Release: v1.0.0**

## Overview
This document describes the contract and logic for managing the trigger state transitions between RFID and Barcode modes in the `RFIDHandler` class. It focuses on the use of the `bRfidBusy` and `bSwitchFromRfidToBarcode` flags, and the correct sequence for event subscription and hardware configuration.

---

## 1. bRfidBusy (Busy Guard)

**Purpose:**
- Indicates if the RFID reader is currently busy (e.g., performing inventory or other operations).

**Contract:**
- **Set to true:**
  - On `INVENTORY_START_EVENT` (RFID operation begins).
- **Set to false:**
  - On `INVENTORY_STOP_EVENT` (RFID operation ends).
- **Threading:**
  - Only updated by the event handler thread.
- **Usage:**
  - Checked before configuration changes (e.g., `setTriggerEnabled`, `restoreDefaultTriggerConfig`).
  - If true, configuration is rejected and the UI is notified.
- **Debugging:**
  - All state changes are logged for traceability.

---

## 2. bSwitchFromRfidToBarcode (Mode Switch Guard)

**Purpose:**
- Controls transition of trigger event handling from RFID to Barcode mode.

**Contract:**
- **Set to true:**
  - On `INVENTORY_STOP_EVENT` in test mode, before switching to barcode.
- **Set to false:**
  - When switching back to RFID mode (in `setTriggerEnabled(true)`).
- **Usage:**
  - Checked in the event handler for `HANDHELD_TRIGGER_EVENT`; if true, RFID trigger events are ignored.
- **Debugging:**
  - All state changes are logged for traceability.

---

## 3. Event Subscription and Hardware Configuration

**subscribeRfidHardwareTriggerEvents(boolean enable):**
- Enables or disables the app’s receipt of `HANDHELD_TRIGGER_EVENT` notifications.
- Called in `setTriggerEnabled` and `testBarcode` to align software event handling with the current hardware mode.

**setTriggerEnabled(boolean isRfidEnabled):**
- Physically reconfigures the reader’s hardware trigger behavior.
- Uses `resourceLock` to ensure mutual exclusion.
- Checks `bRfidBusy` and aborts if busy.
- Sets the trigger mode based on `isRfidEnabled`.
- Calls `subscribeRfidHardwareTriggerEvents` after successful configuration.
- Resets `bSwitchFromRfidToBarcode` when switching back to RFID.

---

## 4. Test Mode Logic

When the application is in test mode (`context.getTestStatus() == true`):
- On `INVENTORY_STOP_EVENT`, `bSwitchFromRfidToBarcode` is set to true and logged.
- `subscribeRfidHardwareTriggerEvents(false)` is called to prevent RFID logic from handling trigger events.
- The UI is notified to scan a barcode.
- `testBarcode()` is called, which disables RFID trigger events and sets the hardware trigger to barcode mode.
- When switching back to RFID mode (`setTriggerEnabled(true)`), `bSwitchFromRfidToBarcode` is reset to false and logged.

---

## 5. Sequence Summary

### RFID → Barcode (Test Mode)
1. On `INVENTORY_STOP_EVENT` (in test mode):
    - Set `bSwitchFromRfidToBarcode = true` (log change)
    - Call `subscribeRfidHardwareTriggerEvents(false)`
    - Notify UI to scan barcode
    - Call `testBarcode()`
2. In `testBarcode()`:
    - Call `subscribeRfidHardwareTriggerEvents(false)`
    - Call `setTriggerEnabled(false)`

### Barcode → RFID
1. Call `setTriggerEnabled(true)`
    - On success, call `subscribeRfidHardwareTriggerEvents(true)`
    - Set `bSwitchFromRfidToBarcode = false` (log change)

---

## 6. Logging
- Every state change of `bRfidBusy` and `bSwitchFromRfidToBarcode` is logged with the previous and new value, and the reason for the change.
- This provides a clear trace for debugging and understanding trigger state transitions.

---

## 7. Thread Safety
- Both flags are declared `volatile` to ensure visibility across threads.
- All configuration changes are protected by `resourceLock` to prevent race conditions.

---

## 8. Rationale
This design ensures robust, deadlock-free, and predictable trigger event handling when toggling between RFID and Barcode modes, especially in concurrent and test scenarios.
