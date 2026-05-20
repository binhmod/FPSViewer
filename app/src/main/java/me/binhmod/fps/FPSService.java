package me.binhmod.fps;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import rikka.shizuku.Shizuku;

public class FPSService extends Service {

    private WindowManager wm;
    private FPSView view;
    private WindowManager.LayoutParams params;
    private Handler mainHandler;

    private int mode;
    private volatile boolean running = true;

    private static FPSService instance = null;
    private String currentPkg = "";
    private String currentLayer = null;

    private String kernelFpsPath = null;
    private boolean kernelPathChecked = false;

    // Layer cache
    private long layerCacheTime = 0;
    private static final long LAYER_CACHE_MS = 3000;

    // --latency cache: avoid spamming dumpsys every 200ms
    private double cachedFps    = -1;
    private long   cachedAt     = 0;
    private int    staleMisses  = 0;        // consecutive parse failures
    private static final long   CACHE_VALID_MS = 180;
    private static final int    STALE_RESET    = 5; // after 5 misses, reset layer

    // EMA smoothing: alpha=0.3 → responsive but not jittery
    private double emaFps   = -1;
    private static final double EMA_ALPHA = 0.3;

    // gfxinfo fallback state
    private long   gfxLastFrames = 0;
    private long   gfxLastTime   = 0;
    private String gfxLastPkg    = null;

    private static final Pattern PAT_ACTIVITY_RECORD =
            Pattern.compile("ActivityRecord\\{[^}]*\\s([a-zA-Z0-9._]+)/");
    private static final Pattern PAT_PKG_SLASH =
            Pattern.compile("\\s([a-zA-Z][a-zA-Z0-9._]*)/[a-zA-Z]");
    private static final Pattern PAT_WINDOW =
            Pattern.compile("Window\\{[^}]*\\s([a-zA-Z0-9._]+)/");
    private static final Pattern PAT_LAYER_ID =
            Pattern.compile("#(\\d+)");
    private static final Pattern PAT_TOTAL_FRAMES =
            Pattern.compile("Total frames rendered:\\s*(\\d+)");

    // -------------------------------------------------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (instance != null && instance != this) instance.removeOverlayOnly();
        instance = this;
        running  = true;

        if (intent != null) mode = intent.getIntExtra("mode", 1);
        wm          = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        if (view == null) {
            view   = new FPSView(this);
            params = new WindowManager.LayoutParams(
                    420, 130,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 100;
            params.y = 100;
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

    // -------------------------------------------------------
    //  Main loop — 200ms polling
    // -------------------------------------------------------
    private void startLoop() {
        new Thread(() -> {
            while (running) {
                try {
                    String pkg = getForegroundPackage();
                    if (pkg != null && !pkg.isEmpty() && !pkg.equals(currentPkg)) {
                        currentPkg    = pkg;
                        currentLayer  = null;
                        layerCacheTime = 0;
                        cachedFps     = -1;
                        cachedAt      = 0;
                        staleMisses   = 0;
                        emaFps        = -1;
                        gfxLastFrames = 0;
                        gfxLastTime   = 0;
                        gfxLastPkg    = null;
                    }

                    double raw = measureFps(currentPkg);

                    // EMA smoothing — skip if no valid reading yet
                    double smoothed;
                    if (raw >= 0) {
                        if (emaFps < 0) {
                            emaFps = raw;           // seed EMA on first valid reading
                        } else {
                            emaFps = EMA_ALPHA * raw + (1 - EMA_ALPHA) * emaFps;
                        }
                        smoothed = emaFps;
                    } else {
                        smoothed = emaFps >= 0 ? emaFps : 0;
                    }

                    final double displayFps = Math.min(smoothed, 999.9);
                    final String displayPkg = currentPkg;

                    if (view != null && running) {
                        mainHandler.post(() -> {
                            if (view != null && view.isAttachedToWindow())
                                view.update(displayFps, displayPkg);
                        });
                    }

                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // -------------------------------------------------------
    //  measureFps — 3-tier
    // -------------------------------------------------------
    private double measureFps(String pkg) {
        // Tier 1: kernel node (fastest, most accurate)
        double kfps = readKernelFps();
        if (kfps >= 0) { staleMisses = 0; return kfps; }

        if (pkg == null || pkg.isEmpty()) return -1;

        // Tier 2: SurfaceFlinger --latency
        long now = System.currentTimeMillis();
        if (cachedFps >= 0 && (now - cachedAt) < CACHE_VALID_MS) return cachedFps;

        String layer = resolveLayer(pkg);
        if (layer != null) {
            double fps = readSurfaceFlingerLatency(layer);
            if (fps >= 0) {
                cachedFps   = fps;
                cachedAt    = System.currentTimeMillis();
                staleMisses = 0;
                return fps;
            } else {
                staleMisses++;
                // Layer is dead — force re-resolve next tick
                if (staleMisses >= STALE_RESET) {
                    currentLayer   = null;
                    layerCacheTime = 0;
                    staleMisses    = 0;
                }
            }
        }

        // Tier 3: gfxinfo framestats fallback
        double gfps = readGfxInfo(pkg);
        if (gfps >= 0) { cachedFps = gfps; cachedAt = System.currentTimeMillis(); return gfps; }

        return cachedFps >= 0 ? cachedFps : -1;
    }

    // -------------------------------------------------------
    //  Tier 1: kernel sysfs node
    // -------------------------------------------------------
    private double readKernelFps() {
        if (!kernelPathChecked) {
            kernelPathChecked = true;
            kernelFpsPath     = detectKernelFpsPath();
        }
        if (kernelFpsPath == null || kernelFpsPath.isEmpty()) return -1;
        try {
            Process p    = exec("cat " + kernelFpsPath);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line  = r.readLine();
            r.close(); p.destroy();
            if (line == null || line.isBlank()) return -1;
            line = line.trim();
            if (line.startsWith("fps:")) line = line.split("\\s+")[1];
            double v = Double.parseDouble(line);
            return v >= 0 ? v : -1;
        } catch (Exception e) { return -1; }
    }

    private String detectKernelFpsPath() {
        String[] paths = {
            "/sys/devices/virtual/graphics/fb0/measured_fps",
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/drm/sde-crtc-1/measured_fps"
        };
        for (String path : paths) {
            try {
                Process p = exec("[ -r " + path + " ] && cat " + path);
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String out = r.readLine();
                r.close(); p.destroy();
                if (out != null && !out.isBlank()) return path;
            } catch (Exception ignored) {}
        }
        return "";
    }

    // -------------------------------------------------------
    //  Tier 2: SurfaceFlinger --latency
    //
    //  Output format (per line after header):
    //    <desiredVsync_ns>  <actualVsync_ns>  <presentFence_ns>
    //
    //  We take col[2] (presentFence) as the actual on-screen
    //  timestamp. Buffer holds last 128 frames.
    //
    //  Window: use frames within the last 1 second only.
    //  If fewer than 4 frames in that window, reject (game paused).
    //  This prevents a stale spike from 3 seconds ago skewing FPS.
    // -------------------------------------------------------
    private double readSurfaceFlingerLatency(String layer) {
        try {
            Process p = exec("dumpsys SurfaceFlinger --latency \"" + layer + "\"");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));

            long maxTs   = 0;
            int  count   = 0;
            long minTs   = Long.MAX_VALUE;
            // Two-pass avoided: track max inline, then filter in second list
            List<Long> all = new ArrayList<>(130);

            boolean first = true;
            String line;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; } // skip refresh-period line
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] c = line.split("\\s+");
                if (c.length < 3) continue;
                try {
                    long ts = Long.parseLong(c[2]);
                    if (ts <= 0 || ts == Long.MAX_VALUE) continue;
                    all.add(ts);
                    if (ts > maxTs) maxTs = ts;
                } catch (NumberFormatException ignored) {}
            }
            r.close(); p.destroy();

            if (all.size() < 2) return -1;

            // Keep only frames within the last 1 000 ms (1 second window)
            long cutoff = maxTs - 1_000_000_000L;
            List<Long> recent = new ArrayList<>(all.size());
            for (long ts : all) {
                if (ts >= cutoff) {
                    recent.add(ts);
                    if (ts < minTs) minTs = ts;
                }
            }

            // Need at least 4 frames in the window to get a stable reading.
            // Fewer means game is paused / very low FPS edge case.
            if (recent.size() < 4) {
                // Fallback: use full buffer but cap duration at 3s to avoid stale data
                recent = all;
                Collections.sort(recent);
                minTs = recent.get(0);
                maxTs = recent.get(recent.size() - 1);
                if (maxTs - minTs > 3_000_000_000L) return -1;
            } else {
                // recent is unsorted — find actual min
                minTs = maxTs; // reset
                for (long ts : recent) if (ts < minTs) minTs = ts;
            }

            long duration = maxTs - minTs;
            if (duration <= 0) return -1;

            count = recent.size();
            return (count - 1) * 1_000_000_000.0 / duration;

        } catch (Exception e) { return -1; }
    }

    // -------------------------------------------------------
    //  Tier 3: gfxinfo framestats (View-based apps fallback)
    // -------------------------------------------------------
    private double readGfxInfo(String pkg) {
        try {
            Process p = exec("dumpsys gfxinfo " + pkg + " framestats");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long totalFrames = -1;
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = PAT_TOTAL_FRAMES.matcher(line);
                if (m.find()) { totalFrames = Long.parseLong(m.group(1)); break; }
            }
            r.close(); p.destroy();

            if (totalFrames < 0) return -1;

            long now = System.currentTimeMillis();
            if (gfxLastPkg == null || !gfxLastPkg.equals(pkg)) {
                gfxLastFrames = totalFrames; gfxLastTime = now; gfxLastPkg = pkg;
                return -1;
            }

            long df = totalFrames - gfxLastFrames;
            long dt = now - gfxLastTime;
            if (dt < 250) return cachedFps;

            gfxLastFrames = totalFrames;
            gfxLastTime   = now;

            if (df <= 0 || dt <= 0) return -1;
            return (df * 1000.0) / dt;
        } catch (Exception e) { return -1; }
    }

    // -------------------------------------------------------
    //  Foreground package detection
    // -------------------------------------------------------
    private String getForegroundPackage() {
        String pkg = extractFromCommand(
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity|mFocusedActivity'",
                PAT_ACTIVITY_RECORD, PAT_PKG_SLASH);
        if (pkg != null) return pkg;

        pkg = extractFromCommand(
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow'",
                PAT_ACTIVITY_RECORD, PAT_PKG_SLASH);
        if (pkg != null) return pkg;

        return extractFromCommand(
                "dumpsys window windows | grep -A 2 'mCurrentFocus\\|mFocusedWindow'",
                PAT_WINDOW, PAT_PKG_SLASH);
    }

    private String extractFromCommand(String cmd, Pattern primary, Pattern fallback) {
        try {
            Process p = exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
            r.close(); p.destroy();
            for (String l : lines) { Matcher m = primary.matcher(l); if (m.find()) return m.group(1); }
            for (String l : lines) { Matcher m = fallback.matcher(l); if (m.find()) return m.group(1); }
        } catch (Exception ignored) {}
        return null;
    }

    // -------------------------------------------------------
    //  Layer resolution — priority: SurfaceView > window > any
    // -------------------------------------------------------
    private String resolveLayer(String pkg) {
        long now = System.currentTimeMillis();
        if (currentLayer != null && (now - layerCacheTime) < LAYER_CACHE_MS) return currentLayer;
        currentLayer = null;

        try {
            Process p = exec("dumpsys SurfaceFlinger --list");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
            r.close(); p.destroy();

            String pkgL = pkg.toLowerCase(Locale.US);
            String[] best   = new String[3];
            int[]    bestId = {-1, -1, -1};

            for (String l : lines) {
                if (l.isBlank()) continue;
                String ll = l.toLowerCase(Locale.US);
                if (!ll.contains(pkgL) || isSystemLayer(ll)) continue;
                String name = l.trim();
                int id = 0;
                Matcher m = PAT_LAYER_ID.matcher(name);
                if (m.find()) { try { id = Integer.parseInt(m.group(1)); } catch (Exception ignored) {} }

                boolean isSV = ll.startsWith("surfaceview") || ll.contains("surfaceview -");
                boolean hasSlash = name.contains("/");

                if (isSV       && id > bestId[0]) { best[0] = name; bestId[0] = id; }
                if (!isSV && hasSlash && id > bestId[1]) { best[1] = name; bestId[1] = id; }
                if (id > bestId[2]) { best[2] = name; bestId[2] = id; }
            }

            String chosen = best[0] != null ? best[0] : best[1] != null ? best[1] : best[2];
            currentLayer   = chosen;
            layerCacheTime = System.currentTimeMillis();
            return chosen;
        } catch (Exception e) { return null; }
    }

    private boolean isSystemLayer(String n) {
        return n.contains("statusbar") || n.contains("navigationbar")
                || n.contains("wallpaper") || n.contains("screenshotlayer")
                || n.contains("dim layer") || n.contains("inputmethod");
    }

    // -------------------------------------------------------
    //  Shell exec
    // -------------------------------------------------------
    private Process exec(String cmd) throws Exception {
        if (mode == 2) return Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        return Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
    }

    // -------------------------------------------------------
    //  Overlay lifecycle & drag
    // -------------------------------------------------------
    private void removeOverlayOnly() {
        if (view != null && wm != null) {
            try { if (view.isAttachedToWindow()) wm.removeViewImmediate(view); } catch (Exception ignored) {}
        }
        view = null;
    }

    private void setupDrag() {
        view.setOnTouchListener(new View.OnTouchListener() {
            int   startX, startY;
            float touchX, touchY;
            long  lastTap;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (view == null || !view.isAttachedToWindow()) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        long now = System.currentTimeMillis();
                        if (now - lastTap < 300) stopSelf();
                        lastTap = now;
                        startX = params.x; startY = params.y;
                        touchX = e.getRawX(); touchY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int)(e.getRawX() - touchX);
                        params.y = startY + (int)(e.getRawY() - touchY);
                        try { wm.updateViewLayout(view, params); } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        currentLayer = null;
        removeOverlayOnly();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
