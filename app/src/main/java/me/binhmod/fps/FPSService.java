package me.binhmod.fps;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.*;
import java.io.*;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

/**
 * @author binhmod
 * @date 2026/6/3
 */

public class FPSService extends Service {
    private WindowManager wm;
    private FPSView view;
    private WindowManager.LayoutParams params;
    private int mode;
    private volatile boolean running = true;
    private String currentPkg = "binhmod @ github";
    
    private long lastTotalFrames = 0, lastTime = 0;
    private int lastCalculatedFps = 0;
    
    private LinkedList<Integer> fpsWindow = new LinkedList<Integer>();
    private final int WINDOW_SIZE = 3;

    private static FPSService instance = null;
    private final Pattern framePattern = Pattern.compile("(count=|Total frames rendered:)\\s*(\\d+)");

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (instance != null && instance != this) {
            instance.removeOverlayOnly();
        }
        instance = this;
        running = true;

        if (intent != null) mode = intent.getIntExtra("mode", 1);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        if (view == null) {
            view = new FPSView(this);
            params = new WindowManager.LayoutParams(
                    400, 160,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 100; params.y = 100;

            try {
                wm.addView(view, params);
                setupDrag();
            } catch (Exception e) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        startLoop();
        return START_STICKY;
    }

    private void removeOverlayOnly() {
        if (view != null && wm != null) {
            try {
                if (view.isAttachedToWindow()) wm.removeViewImmediate(view);
            } catch (Exception ignored) {}
        }
        view = null;
    }

    private void startLoop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        String pkg = getForegroundApp();
                        if (pkg != null) currentPkg = pkg;
                        
                        final int rawFps = getFPS();
                        
                        fpsWindow.add(rawFps);
                        if (fpsWindow.size() > WINDOW_SIZE) fpsWindow.removeFirst();
                        
                        int sum = 0;
                        for (int f : fpsWindow) sum += f;
                        final int smoothedFps = sum / fpsWindow.size();

                        if (view != null && running) {
                            view.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (view != null && view.isAttachedToWindow()) {
                                        view.update(smoothedFps, currentPkg);
                                    }
                                }
                            });
                        }
                        Thread.sleep(350);
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private int getFPS() {
        long total = 0;
        try {
            Process p = exec("dumpsys SurfaceFlinger --stats");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = framePattern.matcher(line);
                if (m.find()) {
                    total = Long.parseLong(m.group(2));
                    break;
                }
            }
            r.close(); p.destroy();
        } catch (Exception e) { return lastCalculatedFps; }

        long now = System.currentTimeMillis();
        int fps = 0;
        if (lastTotalFrames > 0 && lastTime > 0) {
            long diff = total - lastTotalFrames;
            long tDiff = now - lastTime;
            if (tDiff > 0) {
                if (diff > 0) {
                    fps = (int) ((diff * 1000f) / tDiff);
                    lastCalculatedFps = fps;
                } else {
                    fps = lastCalculatedFps;
                }
            }
        }
        lastTotalFrames = total;
        lastTime = now;
        return Math.min(fps, 240);
    }

    private Process exec(String cmd) throws Exception {
        if (mode == 2) return Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        return Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
    }

    private String getForegroundApp() {
        try {
            Process p = exec("dumpsys window | grep mCurrentFocus");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = r.readLine();
            r.close(); p.destroy();
            if (l != null && l.contains("/")) {
                int start = l.lastIndexOf(" ") + 1;
                int end = l.indexOf("/");
                if (start < end) return l.substring(start, end);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void setupDrag() {
        view.setOnTouchListener(new View.OnTouchListener() {
            int x, y; float tx, ty; long lastTap;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (view == null || !view.isAttachedToWindow()) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (System.currentTimeMillis() - lastTap < 300) stopSelf();
                        lastTap = System.currentTimeMillis();
                        x = params.x; y = params.y; tx = e.getRawX(); ty = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = x + (int)(e.getRawX() - tx);
                        params.y = y + (int)(e.getRawY() - ty);
                        try { wm.updateViewLayout(view, params); } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });
    }

    @Override public void onDestroy() { 
        running = false; 
        removeOverlayOnly();
        instance = null;
        super.onDestroy(); 
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
