package me.binhmod.fps;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.LinkedList;

public class FPSView extends View {

    private final Paint bgPaint;
    private final Paint fpsPaint;
    private final Paint pkgPaint;
    private final Paint graphStrokePaint;
    private final Paint graphFillPaint;

    private String fpsText = "-- FPS";
    private String pkgText = "";
    private final LinkedList<Double> history = new LinkedList<>();
    private static final int   MAX_HISTORY   = 60;
    private static final float MAX_FPS_SCALE = 120f;
    private static final float CORNER        = 14f;

    private final RectF bgRect = new RectF();

    public FPSView(Context c) {
        super(c);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xBB0A0A0A);

        fpsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fpsPaint.setTextSize(46f);
        fpsPaint.setFakeBoldText(true);
        fpsPaint.setTypeface(Typeface.MONOSPACE);
        fpsPaint.setShadowLayer(6f, 1f, 2f, 0xDD000000);

        pkgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pkgPaint.setTextSize(17f);
        pkgPaint.setColor(0xCCFFFFFF);
        pkgPaint.setShadowLayer(4f, 1f, 1f, 0xDD000000);

        graphStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        graphStrokePaint.setStrokeWidth(2f);
        graphStrokePaint.setStyle(Paint.Style.STROKE);
        graphStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        graphStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        graphFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        graphFillPaint.setStyle(Paint.Style.FILL);
    }

    public void update(double fps, String pkg) {
        fpsText = String.format("%.1f FPS", fps);
        pkgText = pkg != null ? pkg : "";

        int color;
        if      (fps >= 60) color = 0xFF4CAF50;
        else if (fps >= 30) color = 0xFFFFEB3B;
        else                color = 0xFFF44336;

        fpsPaint.setColor(color);
        graphStrokePaint.setColor(color);
        graphFillPaint.setColor((color & 0x00FFFFFF) | 0x3D000000);

        history.addLast(fps);
        if (history.size() > MAX_HISTORY) history.removeFirst();

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();

        bgRect.set(0, 0, w, h);
        canvas.drawRoundRect(bgRect, CORNER, CORNER, bgPaint);

        // Graph as background
        if (history.size() > 1) {
            float xStep   = (float) w / (MAX_HISTORY - 1);
            float gTop    = 4f;
            float gHeight = h - 8f;

            Path stroke = new Path();
            Path fill   = new Path();

            for (int i = 0; i < history.size(); i++) {
                float x = i * xStep;
                float norm = (float)(history.get(i) / MAX_FPS_SCALE);
                float y = gTop + gHeight - norm * gHeight;
                y = Math.max(gTop + 2, Math.min(y, gTop + gHeight - 2));

                if (i == 0) {
                    stroke.moveTo(x, y);
                    fill.moveTo(x, gTop + gHeight);
                    fill.lineTo(x, y);
                } else {
                    stroke.lineTo(x, y);
                    fill.lineTo(x, y);
                }
            }
            float lastX = (history.size() - 1) * xStep;
            fill.lineTo(lastX, 4f + (h - 8f));
            fill.close();

            canvas.drawPath(fill,   graphFillPaint);
            canvas.drawPath(stroke, graphStrokePaint);
        }

        // FPS text overlaid on graph
        canvas.drawText(fpsText, 12f, h * 0.72f, fpsPaint);

        // Package name — truncate to last 2 segments if too wide
        if (!pkgText.isEmpty()) {
            String label = pkgText;
            if (pkgPaint.measureText(label) > getWidth() - 16f) {
                String[] parts = label.split("\\.");
                if (parts.length >= 2)
                    label = parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
            canvas.drawText(label, 12f, h * 0.94f, pkgPaint);
        }
    }
}
