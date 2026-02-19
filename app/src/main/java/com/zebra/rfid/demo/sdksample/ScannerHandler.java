package com.zebra.rfid.demo.sdksample;

import android.util.Log;
import com.zebra.scannercontrol.DCSScannerInfo;
import com.zebra.scannercontrol.FirmwareUpdateEvent;
import com.zebra.scannercontrol.IDcsSdkApiDelegate;


/**
 * Delegate handler for Zebra Scanner SDK events.
 * <p>
 * Handles scanner appearance, disappearance, session events, and barcode data.
 */
public class ScannerHandler implements IDcsSdkApiDelegate {
    private static final String TAG = "ScannerHandler";
    private final MainActivity context;

    /**
     * Constructs a ScannerHandler with the given activity context.
     * @param context The MainActivity context for UI callbacks.
     */
    public ScannerHandler(MainActivity context) {
        this.context = context;
    }

    /**
     * Called when a scanner appears.
     * @param dcsScannerInfo The scanner info object.
     */
    @Override
    public void dcssdkEventScannerAppeared(DCSScannerInfo dcsScannerInfo) {
        Log.d(TAG, "Scanner appeared: " + dcsScannerInfo.getScannerName());
    }

    /**
     * Called when a scanner disappears.
     * @param i The scanner ID.
     */
    @Override
    public void dcssdkEventScannerDisappeared(int i) {
        Log.d(TAG, "Scanner disappeared, ID: " + i);
        if (context != null) {
            context.setScanButtonEnabled(false);
        }
    }

    /**
     * Called when a communication session is established with a scanner.
     * @param dcsScannerInfo The scanner info object.
     */
    @Override
    public void dcssdkEventCommunicationSessionEstablished(DCSScannerInfo dcsScannerInfo) {
        Log.d(TAG, "Communication session established: " + dcsScannerInfo.getScannerName());
        if(context != null) {
            context.sendToast("Scanner established:\r\n" + dcsScannerInfo.getScannerName());
            context.setScanButtonEnabled(true);
        }
    }

    /**
     * Called when a communication session is terminated with a scanner.
     * @param i The scanner ID.
     */
    @Override
    public void dcssdkEventCommunicationSessionTerminated(int i) {
        Log.d(TAG, "Communication session terminated, ID: " + i);
        if (context != null) {
            context.setScanButtonEnabled(false);
        }
    }

    /**
     * Called when barcode data is received from the scanner.
     * @param barcodeData The barcode data bytes.
     * @param barcodeType The barcode type.
     * @param fromScannerID The scanner ID.
     */
    @Override
    public void dcssdkEventBarcode(byte[] barcodeData, int barcodeType, int fromScannerID) {
        String s = new String(barcodeData);
        if (context != null) {
            Log.v(TAG, "### dcssdkEventBarcode: Received Barcode = " + s);
            context.barcodeData(s);
        }
        Log.d(TAG, "Barcode scanned: " + s);
    }

    /**
     * Called when an image is received from the scanner (not used).
     */
    @Override
    public void dcssdkEventImage(byte[] bytes, int i) {
        Log.v(TAG, "Image event ignored for scanner ID: " + i);
    }
    /**
     * Called when a video is received from the scanner (not used).
     */
    @Override
    public void dcssdkEventVideo(byte[] bytes, int i) {
        Log.v(TAG, "Video event ignored for scanner ID: " + i);
    }
    /**
     * Called when binary data is received from the scanner (not used).
     */
    @Override
    public void dcssdkEventBinaryData(byte[] bytes, int i) {
        Log.v(TAG, "Binary data event ignored for scanner ID: " + i);
    }
    /**
     * Called when a firmware update event occurs (not used).
     */
    @Override
    public void dcssdkEventFirmwareUpdate(FirmwareUpdateEvent firmwareUpdateEvent) {
        Log.v(TAG, "Firmware update event received");
    }
    /**
     * Called when an auxiliary scanner appears (not used).
     */
    @Override
    public void dcssdkEventAuxScannerAppeared(DCSScannerInfo dcsScannerInfo, DCSScannerInfo dcsScannerInfo1) {
        Log.v(TAG, "Aux scanner appeared event received");
    }
}
