package com.zebra.rfid.demo.sdksample;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zebra.rfid.api3.ENUM_NEW_KEYLAYOUT_TYPE;
import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.IRFIDLogger;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.MEMORY_BANK;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.RFIDResults;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.TagAccess;
import com.zebra.rfid.api3.TagData;
import com.zebra.scannercontrol.DCSSDKDefs;
import com.zebra.scannercontrol.DCSScannerInfo;
import com.zebra.scannercontrol.SDKHandler;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Handler class for RFID operations, connection management, and event processing.
 * <p>
 * This class manages the lifecycle of the RFID reader, handles inventory and scanner operations,
 * and provides callback interfaces for UI updates.
 */
class RFIDHandler implements Readers.RFIDReaderEventHandler {
    private static final String CONNECTION_FAILED = "Connection failed";
    private static final String TAG = "RFID_SAMPLE";
    private static final String BUSY_RETRY_MESSAGE = "BUSY and Retry Set Trigger Again!!!";
    private Readers readers;
    private ArrayList<ReaderDevice> availableRFIDReaderList;
    private RFIDReader reader;
    private EventHandler eventHandler;
    private MainActivity context;
    private SDKHandler sdkHandler;
    private ScannerHandler scannerHandler;
    private ArrayList<DCSScannerInfo> scannerList;
    private int scannerID;
    private static final String READER_NAME = "RFD4031-G10B700-WR";
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int connectionTimer = 0;

    /**
     * DESIGN FLOW: test_read_write_verify (Integrated Test Mode)
     *
     * This mode demonstrates a complex state machine transition between RFID and Barcode hardware modes.
     *
     * Phase 1: RFID Read (Initial State)
     *   - User initiates test via UI (MainActivity sets its internal integrated test flag).
     *   - bRfidBusy = false, bSwitchFromRfidToBarcode = false.
     *   - User pulls trigger -> RFID Inventory starts (bRfidBusy = true).
     *   - User releases trigger -> RFID Inventory stops.
     *
     * Phase 2: Transition to Barcode Input
     *   - On INVENTORY_STOP_EVENT:
     *     a. bRfidBusy becomes false.
     *     b. bSwitchFromRfidToBarcode is set to true (blocks further RFID trigger events).
     *     c. subscribeRfidHardwareTriggerEvents(false) is called.
     *     d. testBarcodeInputRfidWriteVerify() is invoked, which calls setTriggerEnabled(false).
     *     e. setTriggerEnabled(false) physically reconfigures the reader's hardware trigger to SLED_SCAN (Barcode) mode.
     *
     * Phase 3: Barcode Scan & RFID Write/Verify
     *   - User pulls trigger -> Hardware executes Barcode Scan (no RFID event generated).
     *   - MainActivity.barcodeData() receives the result.
     *   - RFIDHandler.testWriteTag() is called to write the scanned data (or test data) to a tag.
     *     - Note: This uses writeWait(), which is a synchronous access operation.
     *   - RFIDHandler.verifyWriteTag() is called to read back and confirm the write.
     *
     * Phase 4: Restoration
     *   - If write/verify succeeds:
     *     a. MainActivity resets its integrated test flag.
     *     b. RFIDHandler.setTriggerEnabled(true) is called.
     *     c. setTriggerEnabled(true) physically reconfigures the trigger back to RFID mode.
     *     d. bSwitchFromRfidToBarcode is reset to false, allowing RFID trigger events again.
     */

    /**
     * Indicates if the RFID reader is currently busy (e.g., inventory running).
     * Contract:
     *   - Set to true: On INVENTORY_START_EVENT (RFID operation begins).
     *   - Set to false: On INVENTORY_STOP_EVENT (RFID operation ends).
     *   - Checked before configuration changes (e.g., setTriggerEnabled).
     */
    private volatile boolean bRfidBusy = false;

    /**
     * Controls transition of trigger event handling from RFID to Barcode mode.
     * Contract:
     *   - Set to true: On INVENTORY_STOP_EVENT in test mode, before switching to barcode.
     *   - Set to false: When switching back to RFID mode (in setTriggerEnabled(true)).
     *   - Checked in event handler for HANDHELD_TRIGGER_EVENT; if true, RFID trigger events are ignored.
     */
    private volatile boolean bSwitchFromRfidToBarcode = false;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (context != null) {
                context.updateReaderStatus(context.getString(R.string.connecting) + "... " + connectionTimer++ + "s", false);
                uiHandler.postDelayed(this, 1000);
            }
        }
    };
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    /**
     * Lock to synchronize access to trigger configuration and other shared resources.
     */
    private final ReentrantLock resourceLock = new ReentrantLock();

    /**
     * Initializes the handler and SDK with the provided activity context.
     * @param activity The MainActivity context for UI and resource access.
     */
    void onCreate(MainActivity activity) {
        context = activity;
        scannerList = new ArrayList<>();
        scannerHandler = new ScannerHandler(activity);
        initSdk();
    }

    /**
     * Checks if the RFID reader is currently connected.
     * @return True if connected, false otherwise.
     */
    public boolean isReaderConnected() {
        return reader != null && reader.isConnected();
    }

    /**
     * Toggles the connection state of the RFID reader (connect/disconnect).
     */
    public void toggleConnection() {
        if (isReaderConnected()) {
            executor.execute(this::disconnect);
        } else {
            connectReader();
        }
    }

    private void initSdk() {
        Log.d(TAG, "initSdk");
        if (readers == null) {
            executor.execute(this::findAndHandleAvailableReaders);
        } else {
            connectReader();
        }
    }

    private void findAndHandleAvailableReaders() {
        InvalidUsageException exception = null;
        try {
            availableRFIDReaderList = findAvailableReadersAcrossTransports();
        } catch (InvalidUsageException e) {
            exception = e;
        }
        if (context != null) {
            final InvalidUsageException finalException = exception;
            context.runOnUiThread(() -> handleAvailableReadersResult(finalException));
        }
    }

    private void handleAvailableReadersResult(InvalidUsageException exception) {
        if (context == null) return;
        if (exception != null) {
            handleReaderInitializationFailure(context.getString(R.string.failed_to_get_available_readers) + "\n" + exception.getInfo(), context.getString(R.string.failed_to_get_readers));
        } else if (availableRFIDReaderList.isEmpty()) {
            handleReaderInitializationFailure(context.getString(R.string.no_available_readers_to_proceed), context.getString(R.string.no_readers_found));
        } else {
            connectReader();
        }
    
    }

    private ArrayList<ReaderDevice> findAvailableReadersAcrossTransports() throws InvalidUsageException {
        ENUM_TRANSPORT[] transports = {
                ENUM_TRANSPORT.SERVICE_USB,
                ENUM_TRANSPORT.RE_SERIAL,
                ENUM_TRANSPORT.RE_USB,
                ENUM_TRANSPORT.BLUETOOTH,
                ENUM_TRANSPORT.ALL
        };
        ArrayList<ReaderDevice> foundReaders = new ArrayList<>();
        for (int i = 0; i < transports.length; i++) {
            ENUM_TRANSPORT transport = transports[i];
            if (i == 0) {
                readers = new Readers(context, transport);
            } else {
                readers.setTransport(transport);
            }
            Log.d(TAG, "ECRT: #" + (i + 1) + " Getting Available Readers in " + transport.name());
            ArrayList<ReaderDevice> list = readers.GetAvailableRFIDReaderList();
            if (list != null && !list.isEmpty()) {
                foundReaders = new ArrayList<>(list);
                break;
            }
        }
        return foundReaders;
    }

    private void handleReaderInitializationFailure(String toastMessage, String statusMessage) {
        if (context != null) {
            context.sendToast(toastMessage);
            context.updateReaderStatus(statusMessage, false);
        }
        if (readers != null) {
            readers.Dispose();
            readers = null;
        }
    }

    private void connectReader() {
        executor.execute(() -> {
            if (context != null) {
                context.updateReaderStatus(context.getString(R.string.connecting) + "...", false);
            }
            synchronized (RFIDHandler.this) {
                handleConnectionStatus();
            }
        });
    }

    private void handleConnectionStatus() {
        if (!isReaderConnected()) {
            getAvailableReader();
            String result = getConnectionResultString();
            if (context != null) {
                context.updateReaderStatus(result, isReaderConnected());
            }
        } else {
            if (context != null && reader != null) {
                context.updateReaderStatus(context.getString(R.string.connected) + ": " + reader.getHostName(), true);
            }
        }
    }

    private String getConnectionResultString() {
        if (reader != null) {
            return connect();
        } else if (context != null) {
            return context.getString(R.string.failed_to_find_reader);
        } else {
            return "Failed to find reader";
        }
    }

    private synchronized void getAvailableReader() {
        if (readers != null) {
            Readers.attach(this);
            try {
                ArrayList<ReaderDevice> availableReaders = readers.GetAvailableRFIDReaderList();
                if (availableReaders != null && !availableReaders.isEmpty()) {
                    availableRFIDReaderList = new ArrayList<>(availableReaders);
                    reader = selectReaderFromList(availableRFIDReaderList);
                }
            } catch (InvalidUsageException e) {
                Log.e(TAG, "Error getting available readers", e);
            }
        }
    }

    private RFIDReader selectReaderFromList(ArrayList<ReaderDevice> deviceList) {
        if (deviceList.size() == 1) {
            return deviceList.get(0).getRFIDReader();
        }
        for (ReaderDevice device : deviceList) {
            if (device != null && device.getName() != null && device.getName().startsWith(READER_NAME)) {
                return device.getRFIDReader();
            }
        }
        return null;
    }

    @Override
    public void RFIDReaderAppeared(ReaderDevice readerDevice) {
        connectReader();
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
        if (context != null && readerDevice != null) context.sendToast(context.getString(R.string.rfid_reader_disappeared, readerDevice.getName()));
        synchronized (RFIDHandler.this) {
            if (reader != null && readerDevice != null && readerDevice.getName().equals(reader.getHostName())) {
                executor.execute(this::disconnect);
            }
        }
    }

    private synchronized String connect() {
        if (reader == null) {
            return context != null ? context.getString(R.string.disconnected) : "Disconnected";
        }
        try {
            if (!reader.isConnected()) {
                return connectAndConfigureReader();
            } else {
                return getConnectedStatus();
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            Log.e(TAG, CONNECTION_FAILED, e);
            String error = (e instanceof InvalidUsageException) ? ((InvalidUsageException)e).getInfo() : ((OperationFailureException)e).getStatusDescription();
            return context != null ? context.getString(R.string.connection_failed, error) : CONNECTION_FAILED;
        }
    }

    private String connectAndConfigureReader() throws InvalidUsageException, OperationFailureException {
        connectionTimer = 0;
        bRfidBusy = false;
        bSwitchFromRfidToBarcode = false;
        uiHandler.post(timerRunnable);
        long startTime = System.currentTimeMillis();
        try {
            reader.connect();
        } finally {
            uiHandler.removeCallbacks(timerRunnable);
        }
        long duration = System.currentTimeMillis() - startTime;
        configureReader();
        if (reader.isConnected()) {
            return context != null ? context.getString(R.string.connected) + ": " + reader.getHostName() + " (" + duration + " ms)" : "Connected";
        }
        return context != null ? context.getString(R.string.disconnected) : "Disconnected";
    }

    private String getConnectedStatus() {
        return context != null ? context.getString(R.string.connected) + ": " + reader.getHostName() : "Connected";
    }

    private void configureReader() {
        if (reader != null && reader.isConnected()) {
            IRFIDLogger.getLogger("SDKSampleApp").EnableDebugLogs(true);
            try {
                if (eventHandler == null) eventHandler = new EventHandler();
                reader.Events.addEventsListener(eventHandler);

                /// ///////////////////////////////////////
                reader.Events.setHandheldEvent(true);
                Log.i(TAG, "==>1 Configuration, Default: Subscribe RFID Hardware Trigger Event");

                reader.Events.setTagReadEvent(true);
                reader.Events.setAttachTagDataWithReadEvent(false);
                reader.Events.setReaderDisconnectEvent(true);
                /// ///////////////////////////////////////
                reader.Events.setInventoryStartEvent(true);
                reader.Events.setInventoryStopEvent(true);
                Log.i(TAG, "==>2 Configuration, subscribe RFID Engine Start and Stop Event");
                reader.Events.setOperationEndSummaryEvent(true);
                setupScannerSdk();
                restoreDefaultTriggerConfig();
            } catch (InvalidUsageException | OperationFailureException e) {
                Log.e(TAG, "Configuration failed", e);
            }
            Log.d(TAG, "ECRT: Configuration successful, RFID SDK Version = " + com.zebra.rfid.api3.BuildConfig.VERSION_NAME);
        }
    }

    /**
     * Controls whether the application receives HANDHELD_TRIGGER_EVENT notifications.
     * @param enable True to enable notifications, false to ignore.
     */
    public void subscribeRfidHardwareTriggerEvents(boolean enable){
        if(reader != null && reader.isConnected()) {
            Log.v(TAG, "### subscribeRfidTriggerEvents: rfid=" + enable);
            reader.Events.setHandheldEvent(enable);
        }
    }

    /**
     * Restores the default trigger configuration, waiting for reader idle if necessary.
     */
    public void restoreDefaultTriggerConfig() {
        resourceLock.lock();
        Log.d(TAG, "### restoreDefaultTriggerConfig");
        try {
            if (reader == null || !reader.isConnected()) return;
            
            try {
                waitForReaderIdle();
            } catch (TimeoutException e) {
                Log.e(TAG, "restoreDefaultTriggerConfig failed: " + e.getMessage());
                if(bRfidBusy){
                    stopInventory();
                }
                uiHandler.post(() -> {
                    if (context != null) context.showSnackbar(BUSY_RETRY_MESSAGE, false);
                });
                return;
            }
            try {
                Log.v(TAG, "### Before Restore...");
                reader.Config.getKeylayoutType();
                ENUM_NEW_KEYLAYOUT_TYPE upperTriggerValue = reader.Config.getUpperTriggerValue();
                ENUM_NEW_KEYLAYOUT_TYPE lowerTriggerValue = reader.Config.getLowerTriggerValue();
                logTriggerValues(upperTriggerValue, lowerTriggerValue);
                /// /////////////////////////////////////////////////////////////////////////////////
                reader.Config.setKeylayoutType(ENUM_NEW_KEYLAYOUT_TYPE.RFID, ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN);

                ENUM_NEW_KEYLAYOUT_TYPE upperTriggerValue2 = reader.Config.getUpperTriggerValue();
                ENUM_NEW_KEYLAYOUT_TYPE lowerTriggerValue2 = reader.Config.getLowerTriggerValue();
                Log.v(TAG, "### After Restore...");
                subscribeRfidHardwareTriggerEvents(true);//Enableq RFID Trigger Event by default after restore default trigger configuration
                logTriggerValues(upperTriggerValue2, lowerTriggerValue2);
            } catch (InvalidUsageException | OperationFailureException e) {
                Log.e(TAG, "Exception in restoreDefaultTriggerConfig", e);
            }
        } finally {
            resourceLock.unlock();
        }
    }

    /**
     * Physically reconfigures the reader's hardware trigger behavior.
     * @param isRfidEnabled True for RFID mode, false for Barcode (SLED_SCAN) mode.
     * @return True if configuration successful, false otherwise.
     */
    public boolean setTriggerEnabled(boolean isRfidEnabled) {
        resourceLock.lock();
        try {
            if (reader == null || !reader.isConnected() || context == null) return false;

            // Design Doc: If bRfidBusy is true, operation is rejected immediately.
            if (bRfidBusy) {
                Log.e(TAG, "setTriggerEnabled failed: Reader is busy (bRfidBusy=true)");
                stopInventory();
                uiHandler.post(() -> {
                    if (context != null) context.showSnackbar(BUSY_RETRY_MESSAGE, false);
                });
                return false;
            }

            Log.d(TAG, "### setTriggerEnabled: rfid=" + isRfidEnabled);

            ENUM_NEW_KEYLAYOUT_TYPE mode = isRfidEnabled ? ENUM_NEW_KEYLAYOUT_TYPE.RFID : ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN;
            try {
                Log.v(TAG, "### before setTriggerEnabled: rfid=" + isRfidEnabled);
                RFIDResults result = reader.Config.setKeylayoutType(mode, mode);

                ENUM_NEW_KEYLAYOUT_TYPE upperTriggerValue2 = reader.Config.getUpperTriggerValue();
                ENUM_NEW_KEYLAYOUT_TYPE lowerTriggerValue2 = reader.Config.getLowerTriggerValue();
                Log.v(TAG, "### After setTriggerEnabled...");
                logTriggerValues(upperTriggerValue2, lowerTriggerValue2);

                Log.v(TAG, "### after setTriggerEnabled: rfid=" + isRfidEnabled);
                if (result == RFIDResults.RFID_API_SUCCESS) {
                    Log.v(TAG, "Trigger configuration success: " + mode.name());

                    // Design Doc: On success, calls subscribeRfidTriggerEvents(isRfidEnabled)
                    subscribeRfidHardwareTriggerEvents(isRfidEnabled);

                    if(isRfidEnabled) {
                        if (bSwitchFromRfidToBarcode) {
                            Log.i(TAG, "bSwitchFromRfidToBarcode changed: true -> false (switching back to RFID mode)");
                        }
                        bSwitchFromRfidToBarcode = false;
                    }
                    return true;
                } else {
                    Log.e(TAG, "Trigger configuration failed: " + result.toString());
                }
            } catch (InvalidUsageException | OperationFailureException e) {
                stopInventory();
                uiHandler.post(() -> {
                    if (context != null) context.showSnackbar(BUSY_RETRY_MESSAGE, false);
                });
                Log.e(TAG, "Exception in setTriggerEnabled", e);
            }
            return false;
        } finally {
            resourceLock.unlock();
        }
    }

    /**
     * Waits for the bRfidBusy flag to be false, indicating the reader is idle.
     * @throws TimeoutException if the reader remains busy after the timeout period.
     */
    public void waitForReaderIdle() throws TimeoutException {
        Log.d(TAG, "Waiting for reader idle...");
        for (int i = 0; i < 10; i++) {
            if (!bRfidBusy) {
                Log.d(TAG, "Reader is idle at loop = " + i);
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Interrupted while waiting for reader idle");
            }
        }
        throw new TimeoutException("Reader is busy: Timeout waiting for idle state");
    }

    public void setupScannerSdk() {
        if (context == null) return;
        initializeSdkHandlerIfNeeded();
        refreshScannerList();
        establishScannerSessionIfNeeded();
    }

    private void initializeSdkHandlerIfNeeded() {
        if (sdkHandler == null) {
            sdkHandler = new SDKHandler(context);
            sdkHandler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC);
            sdkHandler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_NORMAL);
            sdkHandler.dcssdkSetDelegate(scannerHandler);
            int notificationsMask = DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value |
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value |
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value |
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value |
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value;
            sdkHandler.dcssdkSubsribeForEvents(notificationsMask);
        }
    }

    private void refreshScannerList() {
        ArrayList<DCSScannerInfo> availableScanners = (ArrayList<DCSScannerInfo>) sdkHandler.dcssdkGetAvailableScannersList();
        if (scannerList != null) {
            scannerList.clear();
        } else {
            scannerList = new ArrayList<>();
        }
        if (availableScanners != null) {
            scannerList.addAll(availableScanners);
        }
    }

    private void establishScannerSessionIfNeeded() {
        if (reader != null && reader.isConnected()) {
            String hostName = reader.getHostName();
            for (DCSScannerInfo device : scannerList) {
                if (device != null && device.getScannerName() != null && hostName != null && device.getScannerName().contains(hostName)) {
                    try {
                        sdkHandler.dcssdkEstablishCommunicationSession(device.getScannerID());
                        scannerID = device.getScannerID();
                    } catch (Exception e) {
                        Log.e(TAG, "Error establishing scanner session", e);
                    }
                }
            }
        }
    }

    private synchronized void disconnect() {
        try {
            if (reader != null) {
                if (eventHandler != null) reader.Events.removeEventsListener(eventHandler);
                if (sdkHandler != null) {
                    sdkHandler.dcssdkTerminateCommunicationSession(scannerID);
                }
                reader.disconnect();
                if (context != null)
                    context.updateReaderStatus(context.getString(R.string.disconnected), false);
                reader = null;
                sdkHandler = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during disconnect", e);
        }
    }

    private void logTriggerValues(ENUM_NEW_KEYLAYOUT_TYPE upper, ENUM_NEW_KEYLAYOUT_TYPE lower) {
        Log.v(TAG, "logTriggerValues upper=" + (upper != null ? upper.name() : "null") + ", lower=" + (lower != null ? lower.name() : "null"));
    }

    private synchronized void dispose() {
        disconnect();
        try {
            if (readers != null) {
                readers.Dispose();
                readers = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during dispose", e);
        }
    }

    /**
     * Starts RFID inventory operation if the reader is not busy.
     */
    synchronized void performInventory() {
        if(bRfidBusy) {
            Log.d(TAG, "RFID is busy, inventory request ignored. Abort!");
            stopInventory();
            return;
        }
        try {
            if (reader != null && reader.isConnected()) reader.Actions.Inventory.perform();
        } catch (InvalidUsageException | OperationFailureException e) {
            Log.e(TAG, "Error performing inventory", e);
        }
    }

    /**
     * Stops RFID inventory operation if the reader is connected.
     */
    synchronized void stopInventory() {
        try {
            if (reader != null && reader.isConnected()) reader.Actions.Inventory.stop();
        } catch (InvalidUsageException | OperationFailureException e) {
            Log.e(TAG, "Error stopping inventory", e);
        }
    }

    /**
     * Initiates a scan code operation using the scanner SDK.
     */
    public void scanCode() {
        String inXml = "<inArgs><scannerID>" + scannerID + "</scannerID></inArgs>";
        executor.execute(() -> executeCommand(DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_DEVICE_PULL_TRIGGER, inXml, scannerID));
    }

    private void executeCommand(DCSSDKDefs.DCSSDK_COMMAND_OPCODE opCode, String inXML, int scannerID) {
        if (sdkHandler != null) {
            sdkHandler.dcssdkExecuteCommandOpCodeInXMLForScanner(opCode, inXML, new StringBuilder(), scannerID);
        }
    }

    public boolean isbRfidBusy() {
        return bRfidBusy;
    }

    /**
     * Event handler for RFID read and status events.
     */
    public class EventHandler implements RfidEventsListener {
        @Override
        public void eventReadNotify(RfidReadEvents e) {
            RFIDReader localReader = reader;
            if (localReader == null) return;
            try {
                TagData[] myTags = localReader.Actions.getReadTags(100);
                if (myTags != null && context != null) {
                    executor.execute(() -> {
                        if (context != null) context.handleTagdata(myTags);
                    });
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error in eventReadNotify", ex);
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
            if (rfidStatusEvents == null || rfidStatusEvents.StatusEventData == null) return;
            STATUS_EVENT_TYPE eventType = rfidStatusEvents.StatusEventData.getStatusEventType();
            if (eventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                if(bSwitchFromRfidToBarcode){
                    Log.v(TAG, "### IGNORE ALL RFID TRIGGER (bSwitchFromRfidToBarcode=true)");
                    return;
                }
                handleTriggerEvent(rfidStatusEvents);
            } else if (eventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                executor.execute(() -> {
                    disconnect();
                    dispose();
                });
            } else if (eventType == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
                bRfidBusy = true;
                Log.i(TAG, "bRfidBusy set to true (INVENTORY_START_EVENT)");
                if (context != null) context.dismissToast();
            } else if (eventType == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
                bRfidBusy = false;
                Log.i(TAG, "bRfidBusy set to false (INVENTORY_STOP_EVENT)");
                if(context != null && context.getTestStatus()) {
                    bSwitchFromRfidToBarcode = true;
                    subscribeRfidHardwareTriggerEvents(false);
                    context.dismissToast();
                    context.showSnackbar("Pull Trigger: \nScan Barcode", false);
                    testBarcode();
                }
                else if(context != null && context.getIntegratedTestStatus()){
                    bSwitchFromRfidToBarcode = true;
                    subscribeRfidHardwareTriggerEvents(false);
                    context.showSnackbar("Pull Trigger: \nScan Barcode for write tag data input", false);
                    testBarcodeInputRfidWriteVerify();
                }
            } else if (eventType == STATUS_EVENT_TYPE.OPERATION_END_SUMMARY_EVENT) {
                Log.d(TAG, "Operation End Summary Event");
            }
        }

        private void handleTriggerEvent(RfidStatusEvents rfidStatusEvents) {
            if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData == null) return;
            HANDHELD_TRIGGER_EVENT_TYPE triggerEvent = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
            boolean isPressed = (triggerEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED);

            if (isPressed) {
                if (bRfidBusy) {
                    if (context != null) {
                        context.runOnUiThread(() -> context.showSnackbar("Ignored: RFID Busy", true));
                    }
                } else {
                    if (context != null) context.handleTriggerPress(true);
                }
            } else {
                if (context != null) context.handleTriggerPress(false);
            }
        }
    }

    void testBarcode(){
        subscribeRfidHardwareTriggerEvents(false);
        setTriggerEnabled(false);
    }

    void testBarcodeInputRfidWriteVerify(){
        subscribeRfidHardwareTriggerEvents(false);
        setTriggerEnabled(false);
    }

    public boolean testWriteTag() {
        String writeData = "111122223333444455556666";
        String sourceEPC = "111122223333444455556666";
        boolean isSuccess = false;
        if (bRfidBusy || reader == null)
            return false;
        try {
            TagAccess.WriteAccessParams writeAccessParams = new TagAccess().new WriteAccessParams();
            writeAccessParams.setAccessPassword(0);
            writeAccessParams.setMemoryBank(MEMORY_BANK.MEMORY_BANK_EPC);
            writeAccessParams.setOffset(2); // start writing from word offset 0
            writeAccessParams.setWriteData(writeData);
            writeAccessParams.setWriteDataLength(writeData.length() / 4);
            writeAccessParams.setWriteRetries(3);
            TagData tagData = new TagData();
            reader.Actions.TagAccess.writeWait(sourceEPC, writeAccessParams, null, tagData, false, false);
            if(tagData.getNumberOfWords() == 6){
                Log.d(TAG, "ECRT: Test Passed: write");
                isSuccess = true;
            }
            else{
                Log.d(TAG, "ECRT: Test Failed: Partial Written = " + tagData.getNumberOfWords());
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            Log.e(TAG, "writeTag failed", e);
        }
        return isSuccess;
    }

    public boolean verifyWriteTag() {
        if (context == null || reader == null) return false;
        Log.i(TAG, "*** verifyWriteTag: reading back to verify");
        context.updateIntegratedTestStatus("Verify...");
        TagAccess.ReadAccessParams readAccessParams = new TagAccess().new ReadAccessParams();
        readAccessParams.setAccessPassword(0);
        readAccessParams.setMemoryBank(MEMORY_BANK.MEMORY_BANK_EPC);
        readAccessParams.setOffset(2);
        String sourceEPC = "111122223333444455556666";

        try {
            TagData tagData = reader.Actions.TagAccess.readWait(sourceEPC, readAccessParams, null, false);
            if(tagData != null && "111122223333444455556666".equals(tagData.getTagID())) {
                Log.d(TAG, "ECRT: Test Passed: Verify");
                context.showSnackbar("TEST PASSED", false);
                return true;
            } else {
                Log.d(TAG, "ECRT: Test Failed: Verify");
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            Log.e(TAG, "verifyWriteTag failed", e);
        }
        return false;
    }

    /**
     * Interface for UI callback methods to handle tag data, trigger events, barcode data, and toasts.
     */
    interface ResponseHandlerInterface {
        void barcodeData(String val);
        void sendToast(String val);
        void dismissToast();
        void showSnackbar(String message, boolean bAutoDisappear);
    }
}
