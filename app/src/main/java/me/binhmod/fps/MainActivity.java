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
 *  1. Kiểm tra SYSTEM_ALERT_WINDOW permission
 *  2. Hỏi mode (Shizuku / Root)
 *  3. Start FPSService rồi tự đóng
 *
 * @author binhmod
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 101;
    private static final int REQ_SHIZUKU = 100;

    // Listener giữ reference để remove sau
    private Shizuku.OnRequestPermissionResultListener shizukuListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Bước 1: Kiểm tra overlay permission
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Cần quyền hiển thị trên màn hình")
                    .setMessage("FPS Viewer cần quyền 'Hiển thị trên ứng dụng khác' để hoạt động.")
                    .setCancelable(false)
                    .setPositiveButton("Cấp quyền", (d, w) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivityForResult(intent, REQ_OVERLAY);
                    })
                    .setNegativeButton("Hủy", (d, w) -> finish())
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
                Toast.makeText(this, "Chưa cấp quyền, không thể hiển thị overlay.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // ─── Mode dialog ────────────────────────────────────────────
    private void showModeDialog() {
    new AlertDialog.Builder(this)
            .setTitle("Chọn phương thức")
            .setMessage("github.com/binhmod/FPSViewer")

            // Nút 1: Shizuku
            .setPositiveButton("Shizuku (ADB)", (dialog, which) -> {
                handleShizuku();
            })

            // Nút 2: Root
            .setNeutralButton("Root (Magisk/KernelSU)", (dialog, which) -> {
                startFPSService(2);
            })

            // Nút 3: Hủy
            .setNegativeButton("Hủy", (d, w) -> finish())

            .setCancelable(false)
            .show();
}

    // ─── Shizuku flow ───────────────────────────────────────────
    private void handleShizuku() {
        // Kiểm tra Shizuku có đang chạy không
        if (!Shizuku.pingBinder()) {
            new AlertDialog.Builder(this)
                    .setTitle("Shizuku chưa chạy")
                    .setMessage("Vui lòng khởi động Shizuku trước.\n\nTải tại: shizuku.dev")
                    .setPositiveButton("OK", (d, w) -> finish())
                    .show();
            return;
        }

        // Đã có quyền
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startFPSService(1);
            return;
        }

        // Xin quyền
        shizukuListener = (requestCode, grantResult) -> {
            // Remove listener ngay
            if (shizukuListener != null) {
                Shizuku.removeRequestPermissionResultListener(shizukuListener);
                shizukuListener = null;
            }

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startFPSService(1);
            } else {
                runOnUiThread(() ->
                        Toast.makeText(this, "Shizuku từ chối quyền!", Toast.LENGTH_SHORT).show()
                );
                finish();
            }
        };

        Shizuku.addRequestPermissionResultListener(shizukuListener);
        Shizuku.requestPermission(REQ_SHIZUKU);
    }

    // ─── Start service ──────────────────────────────────────────
    private void startFPSService(int mode) {
        Intent i = new Intent(this, FPSService.class);
        i.putExtra("mode", mode);
        startService(i);

        // Tự đóng sau 300ms
        getWindow().getDecorView().postDelayed(this::finish, 300);
    }

    @Override
    protected void onDestroy() {
        // Cleanup listener nếu activity bị destroy giữa chừng
        if (shizukuListener != null) {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
            shizukuListener = null;
        }
        super.onDestroy();
    }
}
