package com.zebra.rfid.demo.sdksample;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.zebra.rfid.api3.TagData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Main Activity for the RFID Sample application.
 */
public class MainActivity extends AppCompatActivity implements RFIDHandler.ResponseHandlerInterface {

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean bTestWriteResult = false;
    /**
     * Enables or disables the scan button.
     * @param enabled True to enable, false to disable.
     */
    public void setScanButtonEnabled(boolean enabled) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (btnScan != null) {
                btnScan.setEnabled(enabled);
            }
        });
    }

    private void toggleInventoryButtons(boolean isRunning) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (btnStart != null) btnStart.setEnabled(!isRunning);
            if (btnStop != null) btnStop.setEnabled(isRunning);
        });
    }
    private static final String TAG = "RFID_SAMPLE MainActivity ";
    /**
     * List of tag IDs detected by the RFID reader.
     */
    private final ArrayList<String> tagList = new ArrayList<>();

    /**
     * Set of unique tag IDs detected by the RFID reader.
     */
    private final HashSet<String> tagSet = new HashSet<>();

    /**
     * Handler for RFID operations and responses.
     */
    private RFIDHandler rfidHandler;

    /**
     * Request code for Bluetooth permission.
     */
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 100;

    // UI Components
    private TextView statusTextViewRFID;
    private ListView tagListView;
    private ArrayAdapter<String> tagAdapter;
    private Button btnStart;
    private Button btnStop;
    private Button btnScan;
    private TextView scanResultText;
    private ProgressBar progressBar;
    private View rootLayout;

    private boolean bTestTriggerConfig = false;

    private boolean bTest_ReadRfid_ConfigureBarcodeWriteRfidVerify_RestoreRFIDTrigger = false;

    private boolean bPassed_SwitchTrigger = false;
    private boolean bPassed_Read_Write_Verify = false;


    /**
     * Reference to the currently displayed snackbar for programmatic dismissal.
     */
    private Snackbar activeSnackbar;
    private final Handler autoDismissHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /**
         * Called when the activity is starting. Initializes UI and RFID handler.
         * @param savedInstanceState If the activity is being re-initialized after previously being shut down, this contains the data it most recently supplied.
         */
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupUI();
        rfidHandler = new RFIDHandler();
        checkPermissionsAndInit();
    }

    public boolean getTestStatus(){
        return bTestTriggerConfig;
    }
    public boolean getIntegratedTestStatus(){ return bTest_ReadRfid_ConfigureBarcodeWriteRfidVerify_RestoreRFIDTrigger; }

    /**
     * Consolidates UI initialization and setup.
     */
    private void setupUI() {
        bTestTriggerConfig = false;
        bTest_ReadRfid_ConfigureBarcodeWriteRfidVerify_RestoreRFIDTrigger = false;
        bPassed_SwitchTrigger = false;
        bPassed_Read_Write_Verify = false;
        String appName = getString(R.string.app_name);
        try {
            setTitle(appName + " (" + com.zebra.rfid.api3.BuildConfig.VERSION_NAME + ")");
        } catch (Exception e) {
            /* Exception intentionally ignored for compatibility with Java 8. */
            setTitle(appName);
        }

        rootLayout = findViewById(R.id.coordinatorLayout);
        statusTextViewRFID = findViewById(R.id.textViewStatusrfid);
        if (statusTextViewRFID != null) {
            statusTextViewRFID.setOnClickListener(v -> {
                if (rfidHandler != null) {
                    rfidHandler.toggleConnection();
                }
            });
        }

        tagListView = findViewById(R.id.tag_list);
        tagAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, tagList);
        if (tagListView != null) {
            tagListView.setAdapter(tagAdapter);
        }

        btnStart = findViewById(R.id.StartButton);
        btnStop = findViewById(R.id.btnStop);
        btnScan = findViewById(R.id.scan);
        scanResultText = findViewById(R.id.scanResult);
        progressBar = findViewById(R.id.progressBar);

        if (btnStart != null) btnStart.setEnabled(false);
        if (btnStop != null) btnStop.setEnabled(false);
        if (btnScan != null) {
            btnScan.setEnabled(false);
            btnScan.setOnClickListener(v -> {
                if (!checkReaderHealthy()) {
                    showSnackbar("SKIP!!!\nRFID Busy", true);
                }
                else {
                    scanCode();
                }
            });
        }
    }

    public void updateReaderStatus(String status, boolean isConnected) {
        /**
         * Updates the reader status on the UI.
         * @param status The status message to display.
         * @param isConnected Whether the reader is connected.
         */
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || status == null) return;
            if (statusTextViewRFID != null) {
                statusTextViewRFID.setText(status);
                int color = isConnected ? R.color.status_connected : R.color.status_disconnected;
                statusTextViewRFID.setTextColor(ContextCompat.getColor(this, color));
            }
            if (btnStart != null) {
                btnStart.setEnabled(isConnected);
            }
            showProgress(status.contains(getString(R.string.connecting)));
        });
    }

    public void updateIntegratedTestStatus(String status){
        if (statusTextViewRFID != null) {
            if(bPassed_Read_Write_Verify)
                statusTextViewRFID.setText("Passed: Read Write Verify");
            else if (bPassed_SwitchTrigger)
                statusTextViewRFID.setText("Passed: Switch Hardware Trigger:\nRFID and Barcode");
            else
                statusTextViewRFID.setText(status);

            statusTextViewRFID.setTextColor(ContextCompat.getColor(this, R.color.purple_200));
        }
    }

    private void showProgress(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void checkPermissionsAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                        BLUETOOTH_PERMISSION_REQUEST_CODE);
            } else {
                rfidHandler.onCreate(this);
            }
        } else {
            rfidHandler.onCreate(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (rfidHandler != null) rfidHandler.onCreate(this);
            } else {
                showSnackbar(getString(R.string.bluetooth_permissions_not_granted), true);
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    private boolean checkReaderHealthy(){
        return rfidHandler != null && rfidHandler.isReaderConnected() && !rfidHandler.isbRfidBusy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (rfidHandler == null) return super.onOptionsItemSelected(item);

        if (!checkReaderHealthy()) {
            showSnackbar("SKIP!!!\nRFID Busy", true);
            return false;
        } 
        if (id == R.id.trigger_rfid_rfid) {
            rfidHandler.setTriggerEnabled(true);
            showSnackbar("RFID Triggers Enabled", true);
            return true;
        } else if (id == R.id.trigger_barcode_barcode) {
            rfidHandler.setTriggerEnabled(false);
            showSnackbar("Barcode Triggers Enabled", true);
            return true;
        } else if (id == R.id.Default) {
            rfidHandler.restoreDefaultTriggerConfig();
            showSnackbar("Default Trigger Settings", true);
            return true;
        } else if (id == R.id.auto) {
            bTestTriggerConfig = false;
            Log.i(TAG, "==>3 Test Trigger Switch -- RFID Trigger Enabled");
            Log.v(TAG, "###1 ECRT: RFID Trigger Enabled");
            bTestTriggerConfig = true;
            Log.i(TAG, "==>4 Pull Trigger for RFID Operation");
            showSnackbar("Pull Trigger:\nRFID Operation\n\nBarcode Trigger Disabled", false);
            return true;
        } else if (id == R.id.test_read_write_verify){
            bPassed_Read_Write_Verify = false;
            updateIntegratedTestStatus("Test Read RFID\nConfigure Trigger for Barcode Input\nWriteRFID-ReadBackVerify\nRestoreRFID-Trigger");
            Log.i(TAG, "***1 Test Read RFID, Configure Trigger for Barcode Input-WriteRFID-ReadBackVerify-RestoreRFID-Trigger");
            bTest_ReadRfid_ConfigureBarcodeWriteRfidVerify_RestoreRFIDTrigger = true;
            Log.i(TAG, "***2 Pull Trigger for RFID Operation");
            showSnackbar("Pull Trigger:\nRead RFID Tag\n\nBarcode Trigger Disabled", false);
        }
        return super.onOptionsItemSelected(item);
    }

    public void StartInventory(View view) {
        /**
         * Starts RFID inventory when the start button is pressed.
         * @param view The view that triggered this method.
         */
        toggleInventoryButtons(true);
        clearTagData();
        if (rfidHandler != null) rfidHandler.performInventory();
    }

    private void clearTagData() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            tagSet.clear();
            tagList.clear();
            if (tagAdapter != null) {
                tagAdapter.notifyDataSetChanged();
            }
        });
    }

    public void scanCode() {
        /**
         * Initiates barcode scanning when the scan button is pressed.
         */
        if (rfidHandler != null) rfidHandler.scanCode();
    }

    public void StopInventory(View view) {
        /**
         * Stops RFID inventory when the stop button is pressed.
         * @param view The view that triggered this method.
         */
        toggleInventoryButtons(false);
        if (rfidHandler != null) rfidHandler.stopInventory();
    }

    @SuppressLint("SetTextI18n")
    public void handleTagdata(TagData[] tagData) {
        /**
         * Handles new tag data received from the RFID reader.
         * @param tagData Array of TagData objects.
         */
        if (tagData == null || tagData.length == 0) return;

        final ArrayList<String> newTags = collectNewTags(tagData);
        if (!newTags.isEmpty()) {
            final int totalUniqueTags = tagSet.size();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateTagListUI(newTags);
                updateStatusTextWithUniqueTags(totalUniqueTags);
            });
        }
    }

    private ArrayList<String> collectNewTags(TagData[] tagData) {
        ArrayList<String> newTags = new ArrayList<>();
        for (TagData tag : tagData) {
            if (tag == null) continue;
            String tagId = tag.getTagID();
            if (tagId != null && !tagSet.contains(tagId)) {
                tagSet.add(tagId);
                newTags.add(tagId + " (RSSI: " + tag.getPeakRSSI() + ")");
            }
        }
        return newTags;
    }

    private void updateTagListUI(ArrayList<String> newTags) {
        tagList.addAll(0, newTags);
        if (tagAdapter != null) {
            tagAdapter.notifyDataSetChanged();
        }
    }

    private void updateStatusTextWithUniqueTags(int totalUniqueTags) {
        if (statusTextViewRFID != null && statusTextViewRFID.getText() != null) {
            String statusStr = statusTextViewRFID.getText().toString();
            if (statusStr.contains(getString(R.string.connected))) {
                String[] parts = statusStr.split("\n");
                String currentStatus = parts.length > 0 ? parts[0] : statusStr;
                statusTextViewRFID.setText(currentStatus + "\n" + getString(R.string.unique_tags, totalUniqueTags));
            }
        }
    }

    public void handleTriggerPress(boolean pressed) {
        /**
         * Handles trigger press events from the RFID reader.
         * @param pressed True if trigger is pressed, false otherwise.
         */
        toggleInventoryButtons(pressed);
        if (pressed) {
            clearTagData();
            if (rfidHandler != null && !rfidHandler.isbRfidBusy()) rfidHandler.performInventory();
        } else {
            if (rfidHandler != null && rfidHandler.isbRfidBusy()) rfidHandler.stopInventory();
        }
    }

    @Override
    public void barcodeData(String val) {
        /**
         * Displays barcode data received from the scanner.
         * @param val The barcode value.
         */
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (scanResultText != null) {
                Log.d(TAG, "barcodeData = " + val);
                scanResultText.setText(getString(R.string.scan_result_label, val != null ? val : ""));
                if(bTestTriggerConfig) {
                    Log.i(TAG, "==>12 Received Barcode and Restore to RFID");

                    sendToast("Restore to RFID");
                    Log.v(TAG, "###8  Restore to RFID and Re-configure both Triggers back to RFID");
                    Log.i(TAG, "==>13 SET subscribeRfidHardwareTriggerEvents");
                    rfidHandler.subscribeRfidHardwareTriggerEvents(true);
                    Log.i(TAG, "==>14 SET RFID HW Trigger Config setTriggerEnabled");
                    if (rfidHandler.setTriggerEnabled(true)){
                        updateIntegratedTestStatus("Retore Trigger Configure to RFID Only");
                        bTestTriggerConfig = true;
                    }
                    bTestTriggerConfig = false;
                    Log.i(TAG, "==>15 Wait for RFID Trigger and Start RFID Inventory");
                }
                else if (bTest_ReadRfid_ConfigureBarcodeWriteRfidVerify_RestoreRFIDTrigger){
                    uiHandler.post(() -> {
                        sendToast("Use Barcode Data Input to Write EPC to RFID");
                    });
                    bTestWriteResult = false;
                    bTestWriteResult = rfidHandler.testWriteTag();
                    if(bTestWriteResult) {
                        uiHandler.post(() -> {
                            showSnackbar("Done Write, Read back to verify RFID tag written status: \n1. Success restore RFID Trigger\n2. Fail: Retey Bacode Scan and Write RFID", true);
                            updateIntegratedTestStatus("Write OK");
                        });

                        if (rfidHandler.verifyWriteTag()) {
                            bTest_ReadRfid_ConfigureBarcodeWriteRfidVerify_RestoreRFIDTrigger = false;
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            if (!rfidHandler.isbRfidBusy()) {
                                uiHandler.post(() -> {
                                    showSnackbar("Restore to RFID", true);
                                    updateIntegratedTestStatus("Test Passed: read, write and verify OK");
                                    rfidHandler.subscribeRfidHardwareTriggerEvents(false);
                                    Log.i(TAG, "*** SET RFID HW Trigger Config setTriggerEnabled");
                                    rfidHandler.setTriggerEnabled(true);
                                    bPassed_Read_Write_Verify = true;
                                });
                            }
                        } else{
                            showSnackbar("Fail: Retry Bacode Scan and Write RFID", false);
                        }
                    }else{
                        uiHandler.post(() -> {
                            showSnackbar("Fail: Retry Bacode Scan and Write RFID", false);
                        });
                    }
                }
            }
        });
    }

    @Override
    public void sendToast(String val) {
        /**
         * Shows a modern snackbar message on the UI.
         * @param val The message to display.
         */
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            showSnackbar(val, true);
        });
    }

    @Override
    public void dismissToast() {
        /**
         * Dismisses the current snackbar programmatically.
         */
        runOnUiThread(() -> {
            if (activeSnackbar != null && activeSnackbar.isShown()) {
                activeSnackbar.dismiss();
            }
        });
    }

    /**
     * Displays a modern, large, centered Snackbar window that fits all text with an X icon for dismissal.
     * @param message The message to display.
     * @param bAutoDisappear If true, the Snackbar auto-dismisses after 3 seconds.
     */
    @Override
    public void showSnackbar(String message, boolean bAutoDisappear) {
        runOnUiThread(() -> {
            if (rootLayout == null || message == null) return;
            
            if (activeSnackbar != null && activeSnackbar.isShown()) {
                activeSnackbar.dismiss();
            }
            autoDismissHandler.removeCallbacksAndMessages(null);

            activeSnackbar = Snackbar.make(rootLayout, message, bAutoDisappear ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_INDEFINITE);
            
            View snackbarView = activeSnackbar.getView();
            
            // Glassmorphism effect with larger rounded corners
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(40f);
            // If message is for barcode scan, use yellow, else default
            if (message != null && (
                message.toLowerCase().contains("rfid operation") ||
                message.toLowerCase().contains("restore to rfid")
            )) {
                shape.setColor(Color.parseColor("#2196F3")); // Blue
                shape.setStroke(3, Color.parseColor("#1976D2"));
            } else {
                shape.setColor(Color.parseColor("#EE1A1A1A")); // Slightly more opaque
                shape.setStroke(3, Color.parseColor("#44FFFFFF"));
            }
            snackbarView.setBackground(shape);

            // Positioning logic for "Large Window" effect
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxWidth = (int) (dm.widthPixels * 0.85); // 85% of screen width

            ViewGroup.LayoutParams baseParams = snackbarView.getLayoutParams();
            if (baseParams instanceof CoordinatorLayout.LayoutParams) {
                CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) baseParams;
                params.gravity = Gravity.CENTER;
                params.width = maxWidth;
                snackbarView.setLayoutParams(params);
            } else if (baseParams instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) baseParams;
                params.gravity = Gravity.CENTER;
                params.width = maxWidth;
                snackbarView.setLayoutParams(params);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                snackbarView.setElevation(30f);
            }

            @SuppressLint("RestrictedApi") Snackbar.SnackbarLayout layout = (Snackbar.SnackbarLayout) snackbarView;
            
            // Adjust text view to fit all text and look "Large"
            TextView textView = layout.findViewById(com.google.android.material.R.id.snackbar_text);
            textView.setMaxLines(20); // Allow many lines
            textView.setTextSize(20); // Larger font
            textView.setPadding(32, 48, 32, 48); // More breathing room
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textView.setTextColor(Color.WHITE);
            textView.setGravity(Gravity.CENTER);
            textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);

            // Hide default action button
            Button actionButton = layout.findViewById(com.google.android.material.R.id.snackbar_action);
            if (actionButton != null) {
                actionButton.setVisibility(View.GONE);
            }

            // Add custom X icon for dismissal
            ImageView closeIcon = new ImageView(this);
            closeIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            closeIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            closeIcon.setPadding(20, 20, 20, 20);
            closeIcon.setOnClickListener(v -> dismissToast());

            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            closeParams.gravity = Gravity.TOP | Gravity.END;
            closeParams.setMargins(0, 10, 10, 0);
            layout.addView(closeIcon, closeParams);

            activeSnackbar.show();
        });
    }
}
