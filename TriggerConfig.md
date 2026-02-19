# RFID Trigger Configuration & Event Subscription

## 1. Overview

The `RFIDHandler` class manages the Zebra RFID reader’s hardware trigger, which can operate in two modes:
- **RFID Mode:** Trigger starts/stops RFID tag inventory.
- **Barcode (Sled Scan) Mode:** Trigger activates the barcode scanner.

The application dynamically reconfigures the hardware key layout and manages event subscriptions to support these modes. This document describes the design and logic for:
- `setTriggerEnabled` (hardware configuration)
- `subscribeRfidHardwareTriggerEvents` (software event masking)

---

## 2. Architecture & Synchronization

### The Challenge

Reconfiguring the hardware trigger (`setKeylayoutType`) requires sending commands to the reader. If the reader is busy (e.g., inventory in progress) or if multiple threads attempt configuration simultaneously, errors or undefined states may occur.

### Synchronization Strategy

To ensure thread safety and hardware stability:
- **resourceLock (`ReentrantLock`):** Only one configuration operation at a time.
- **bRfidBusy (`volatile boolean`):** Blocks configuration commands while the reader is busy.
- **waitForReaderIdle():** Helper to avoid indefinite waits during restore operations.

---

## 3. Key Design Elements

### 3.1 Busy Guard: `bRfidBusy`

- **Purpose:** Indicates if the reader is busy (e.g., inventory running).
- **Set to true:** On `INVENTORY_START_EVENT`.
- **Set to false:** On `INVENTORY_STOP_EVENT`.
- **Usage:** Blocks configuration changes while busy; operations are rejected and UI is notified to retry.
- **waitForReaderIdle():** Polls `bRfidBusy` with a timeout before configuration changes.

### 3.2 Mode Switch Guard: `bSwitchFromRfidToBarcode`

- **Purpose:** Prevents RFID trigger events from being processed during a switch to barcode mode.
- **Set to true:** When inventory stops in test mode, to ignore subsequent RFID trigger events.
- **Set to false:** When switching back to RFID mode.
- **Usage:** If true, RFID trigger events are ignored.

**Sequence Example:**
1. Inventory stops in test mode → `bSwitchFromRfidToBarcode = true`
2. Trigger event occurs → RFID handler ignores it
3. Barcode mode is enabled
4. Switching back to RFID → flag reset

### 3.3 Hardware Trigger Configuration: `setTriggerEnabled(boolean isRfidEnabled)`

- **Acquire Lock:** Prevents concurrent configuration.
- **Check Busy Guard:** Rejects if busy, stops inventory, notifies UI.
- **Determine Mode:** `true` for RFID, `false` for Barcode.
- **Apply Configuration:** Calls `reader.Config.setKeylayoutType`.
- **Align Event Subscription:** Calls `subscribeRfidHardwareTriggerEvents(isRfidEnabled)` on success.
- **Release Lock:** Always releases in a finally block.

### 3.4 Event Subscription: `subscribeRfidHardwareTriggerEvents(boolean enable)`

- **Purpose:** Controls whether the app receives trigger events from the SDK.
- **Enable (true):** App receives trigger callbacks (needed for RFID mode).
- **Disable (false):** App ignores trigger events (used in Barcode mode).
- **Integration:**
  - Switching to Barcode: Unsubscribe events, then reconfigure hardware.
  - Switching to RFID: Reconfigure hardware, then subscribe events.

---

## 4. Integration Workflow

**Switching to Barcode Mode:**
1. Unsubscribe events: `subscribeRfidHardwareTriggerEvents(false)`
2. Reconfigure hardware: `setTriggerEnabled(false)`

**Switching to RFID Mode:**
1. Reconfigure hardware: `setTriggerEnabled(true)`
2. On success, events are automatically subscribed.

---

## 5. Safety Mechanisms

| Mechanism         | Purpose                                                        |
|-------------------|---------------------------------------------------------------|
| ReentrantLock     | Prevents concurrent configuration operations                  |
| waitForReaderIdle | Avoids sending commands while the reader is active            |
| Timeout (~3s)     | Prevents hangs during restore operations                      |
| Try-Catch-Finally | Ensures locks are always released, even on exceptions         |

---

## 6. Deadlock Avoidance

- Never block indefinitely while holding `resourceLock`.
- `waitForReaderIdle()` uses bounded retries and fails fast.
- `bRfidBusy` is volatile and updated directly by status events.
- Always release locks in all code paths.
- Switch order: Unsubscribe events before hardware switch (RFID→Barcode); enable events after hardware switch (Barcode→RFID).

---

## 7. Code Reference

```java
public boolean setTriggerEnabled(boolean isRfidEnabled) {
    resourceLock.lock();
    try {
        if (reader == null || !reader.isConnected() || context == null) return false;
        if (bRfidBusy) {
            stopInventory();
            uiHandler.post(() -> {
                if (context != null) context.showSnackbar(BUSY_RETRY_MESSAGE, false);
            });
            return false;
        }
        ENUM_NEW_KEYLAYOUT_TYPE mode = isRfidEnabled ? 
            ENUM_NEW_KEYLAYOUT_TYPE.RFID : ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN;
        try {
            RFIDResults result = reader.Config.setKeylayoutType(mode, mode);
            if (result == RFIDResults.RFID_API_SUCCESS) {
                subscribeRfidHardwareTriggerEvents(isRfidEnabled);
                if(isRfidEnabled) bSwitchFromRfidToBarcode = false;
                return true;
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            stopInventory();
            uiHandler.post(() -> {
                if (context != null) context.showSnackbar(BUSY_RETRY_MESSAGE, false);
            });
        }
        return false;
    } finally {
        resourceLock.unlock();
    }
}