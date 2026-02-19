package com.zebra.rfid.demo.sdksample;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zebra.rfid.api3.ENUM_NEW_KEYLAYOUT_TYPE;
import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.IRFIDLogger;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.RFIDResults;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
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
     * Indicates if the RFID reader is currently busy (e.g., inventory running).
     * Contract:
     *   - Set to true: On INVENTORY_START_EVENT (RFID operation begins).
     *   - Set to false: On INVENTORY_STOP_EVENT (RFID operation ends).
     *   - Only updated by the event handler thread.
     *   - Checked before configuration changes (e.g., setTriggerEnabled, restoreDefaultTriggerConfig).
     *   - If true, configuration is rejected and UI is notified.
     *   - All state changes are logged for debugging.
     */
    private volatile boolean bRfidBusy = false;

    /**
     * Controls transition of trigger event handling from RFID to Barcode mode.
     * Contract:
     *   - Set to true: On INVENTORY_STOP_EVENT in test mode, before switching to barcode.
     *   - Set to false: When switching back to RFID mode (in setTriggerEnabled(true)).
     *   - Checked in event handler for HANDHELD_TRIGGER_EVENT; if true, RFID trigger events are ignored.
     *   - All state changes are logged for debugging.
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

    /**
     * Handles resume event for the activity, reconnecting the reader if needed.
     */
    void onResume() {
        executor.execute(() -> {
            String result = connect();
            if (context != null) {
                context.updateReaderStatus(result, isReaderConnected());
            }
        });
    }

    /**
     * Handles pause event for the activity, disconnecting the reader.
     */
    void onPause() {
        executor.execute(this::disconnect);
    }

    /**
     * Handles destroy event for the activity, disposing resources and shutting down executors.
     */
    void onDestroy() {
        executor.execute(() -> {
            dispose();
            context = null;
        });
        executor.shutdown();
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
        } catch (InvalidUsageException e) {
            Log.e(TAG, CONNECTION_FAILED, e);
            return context != null ? context.getString(R.string.connection_failed, e.getMessage()) : CONNECTION_FAILED;
        } catch (OperationFailureException e) {
            Log.e(TAG, CONNECTION_FAILED, e);
            return context != null ? context.getString(R.string.connection_failed, e.getStatusDescription()) : CONNECTION_FAILED;
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
                Log.d(TAG, "ECRT: Configuration, Default: Subscribe RFID Hardware Trigger Event");

                reader.Events.setTagReadEvent(true);
                reader.Events.setAttachTagDataWithReadEvent(false);
                reader.Events.setReaderDisconnectEvent(true);
                /// ///////////////////////////////////////
                reader.Events.setInventoryStartEvent(true);
                reader.Events.setInventoryStopEvent(true);
                Log.d(TAG, "ECRT: Configuration, subscript RFID Engine Start and Stop Event");
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
     * @return True if successful, false otherwise.
     */
    public boolean restoreDefaultTriggerConfig() {
        resourceLock.lock();
        Log.d(TAG, "### restoreDefaultTriggerConfig");
        try {
            if (reader == null || !reader.isConnected()) return false;
            
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
                return false;
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
                return true;
            } catch (InvalidUsageException | OperationFailureException e) {
                Log.e(TAG, "Exception in restoreDefaultTriggerConfig", e);
            }
            return false;
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
                    Log.v(TAG, "#################################################");
                    Log.v(TAG, "Trigger configuration success: " + mode.name());
                    Log.v(TAG, "#################################################");

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
    private void waitForReaderIdle() throws TimeoutException {
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
        Log.v(TAG, "### upper=" + upper.name() + ", lower=" + lower);
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
            Log.d(TAG, "RFID is busy, inventory request ignored.\r\n Abort!!!!");
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
            Log.e(TAG, CONNECTION_FAILED, e);
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
                if (!bRfidBusy) {
                    Log.i(TAG, "bRfidBusy changed: false -> true (INVENTORY_START_EVENT)");
                }
                bRfidBusy = true;
                if (context != null) context.dismissToast();
            } else if (eventType == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
                if (bRfidBusy) {
                    Log.i(TAG, "bRfidBusy changed: true -> false (INVENTORY_STOP_EVENT)");
                }
                bRfidBusy = false;
                Log.v(TAG, "###5 API Inventory Stop Event, RFID Engine NOT BUSY and Ready for next command....");
                if(context != null && context.getTestStatus()) {
                    if (!bSwitchFromRfidToBarcode) {
                        Log.i(TAG, "bSwitchFromRfidToBarcode changed: false -> true (test mode, switching to barcode)");
                    }
                    bSwitchFromRfidToBarcode = true;
                    //MUST DO This first to prevent trigger debounce
                    subscribeRfidHardwareTriggerEvents(false);
                    context.dismissToast();
                    context.showSnackbar("Pull Trigger: \r\nScan Barcode", false);
                    Log.v(TAG, "###6 testBarcode: switch both Hardware Triggers from RFID to Barcode Test...");
                    testBarcode();
                }
            } else if (eventType == STATUS_EVENT_TYPE.OPERATION_END_SUMMARY_EVENT) {
                Log.d(TAG, "Operation End Summary Event");
            }
            else {
                Log.d(TAG, "Unhandled status event: " + eventType);
            }
        }
    /**
     * Test Mode Documentation:
     *
     * When the application is in test mode (context.getTestStatus() == true):
     *   - On INVENTORY_STOP_EVENT, bSwitchFromRfidToBarcode is set to true and logged.
     *   - subscribeRfidHardwareTriggerEvents(false) is called to prevent RFID logic from handling trigger events.
     *   - The UI is notified to scan a barcode.
     *   - testBarcode() is called, which disables RFID trigger events and sets the hardware trigger to barcode mode.
     *   - When switching back to RFID mode (setTriggerEnabled(true)), bSwitchFromRfidToBarcode is reset to false and logged.
     *
     * This ensures robust and predictable trigger event handling when toggling between RFID and Barcode modes in test scenarios.
     */

        private void handleTriggerEvent(RfidStatusEvents rfidStatusEvents) {
            if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData == null) return;
            Log.v(TAG, "### handleTriggerEvent for hardware trigger event...");
            HANDHELD_TRIGGER_EVENT_TYPE triggerEvent = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
            boolean isPressed = (triggerEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED);

            if (isPressed) {
                if (bRfidBusy) {
                    Log.d(TAG, "Ignored Trigger Press: RFID is already busy.");
                    if (context != null) {
                        context.runOnUiThread(() -> context.showSnackbar("Ignored: RFID Busy", true));
                    }
                } else {
                    Log.v(TAG, "###3 Hardware Trigger Pressed: Starting Inventory...");
                    if (context != null) context.handleTriggerPress(true);
                }
            } else {
                Log.v(TAG, "###4 Hardware Trigger Released: Stopping Inventory...");
                if (context != null) context.handleTriggerPress(false);
            }
        }
    }

    void testBarcode(){
        if (context != null) {
            Log.v(TAG, "###7 testBarcode: switch from RFID to Barcode Trigger");
            Log.v(TAG, "STEP 1: subscribe RFID Hardware Trigger Events");
            subscribeRfidHardwareTriggerEvents(false);
            Log.v(TAG, "STEP 2: Configure the Hardware Trigger as Barcode");
            setTriggerEnabled(false);
        }
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
