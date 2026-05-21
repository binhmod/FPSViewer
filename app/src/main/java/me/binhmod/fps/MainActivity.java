package me.binhmod.fps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/**
 * MainActivity — Launcher:
 *  1. Checks SYSTEM_ALERT_WINDOW permission
 *  2. Prompt user to choose mode (Shizuku / Root)
 *  3. Starts FPSService and self-closes
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 101;
    private static final int REQ_SHIZUKU = 100;

    private Shizuku.OnRequestPermissionResultListener shizukuListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Step 1: Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("FPS Viewer needs the 'Display over other apps' permission to function properly.")
                    .setCancelable(false)
                    .setPositiveButton("Grant", (d, w) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivityForResult(intent, REQ_OVERLAY);
                    })
                    .setNegativeButton("Cancel", (d, w) -> finish())
                    .show();
            return;
        }

        showModeDialog();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                showModeDialog();
            } else {
                Toast.makeText(this, "Permission denied. Cannot display overlay.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void showModeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Select Method")
                .setMessage("github.com/binhmod/FPSViewer")
                .setPositiveButton("Shizuku (ADB)", (dialog, which) -> {
                    handleShizuku();
                })
                .setNeutralButton("Root (Magisk/KernelSU)", (dialog, which) -> {
                    startFPSService(2);
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void handleShizuku() {
        // Check if Shizuku service is running
        if (!Shizuku.pingBinder()) {
            new AlertDialog.Builder(this)
                    .setTitle("Shizuku Not Running")
                    .setMessage("Please start Shizuku first.\n\nDownload: shizuku.rikka.app")
                    .setPositiveButton("OK", (d, w) -> finish())
                    .show();
            return;
        }

        // Permission already granted
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startFPSService(1);
            return;
        }

        // Request permission
        shizukuListener = (requestCode, grantResult) -> {
            if (shizukuListener != null) {
                Shizuku.removeRequestPermissionResultListener(shizukuListener);
                shizukuListener = null;
            }

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startFPSService(1);
            } else {
                runOnUiThread(() ->
                        Toast.makeText(this, "Shizuku permission denied!", Toast.LENGTH_SHORT).show()
                );
                finish();
            }
        };

        Shizuku.addRequestPermissionResultListener(shizukuListener);
        Shizuku.requestPermission(REQ_SHIZUKU);
    }

    private void startFPSService(int mode) {
        Intent i = new Intent(this, FPSService.class);
        i.putExtra("mode", mode);
        startService(i);

        // Close after 300ms delay
        getWindow().getDecorView().postDelayed(this::finish, 300);
    }

    @Override
    protected void onDestroy() {
        // Clean up listener to prevent memory leaks if destroyed early
        if (shizukuListener != null) {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
            shizukuListener = null;
        }
        super.onDestroy();
    }
}
