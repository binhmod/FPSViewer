package me.binhmod.fps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/**
 * @author binhmod
 * @date 2026/6/3
 */

public class MainActivity extends Activity {
    private static final int REQ = 100;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            finish();
            return;
        }

        showModeDialog();
    }

    private void showModeDialog() {
        final String[] modes = {"Shizuku", "Root"};

        new AlertDialog.Builder(this)
                .setTitle("Permission:\nhttps://github.com/binhmod/FPSViewer")
                .setCancelable(false)
                .setItems(modes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int mode = which + 1;
                        if (mode == 1) {
                            requestShizuku();
                        } else {
                            startFPS(2);
                        }
                    }
                }).show();
    }

    private void requestShizuku() {
        if (!Shizuku.pingBinder()) {
            new AlertDialog.Builder(this)
                    .setMessage("Shizuku not started!")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            finish();
                        }
                    }).show();
            return;
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startFPS(1);
            return;
        }

        Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    startFPS(1);
                } else {
                    Toast.makeText(MainActivity.this, "Shizuku denied!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        Shizuku.requestPermission(REQ);
    }

    private void startFPS(final int mode) {
        Intent i = new Intent(this, FPSService.class);
        i.putExtra("mode", mode);
        startService(i);
        
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 300);
    }
}
