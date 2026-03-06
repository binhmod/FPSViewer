package me.binhmod.fps;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.LinkedList;

/**
 * @author binhmod
 * @date 2026/6/3
 */

public class FPSView extends View {
    private Paint textPaint, graphPaint, bgPaint;
    private String fpsText = "0 FPS", pkgText = "";
    private LinkedList<Integer> fpsHistory = new LinkedList<Integer>();
    private final int MAX_HISTORY = 40;

    public FPSView(Context c) {
        super(c);
        bgPaint = new Paint();
        bgPaint.setColor(0x99000000);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(45);
        textPaint.setFakeBoldText(true);

        graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        graphPaint.setStrokeWidth(3);
        graphPaint.setStyle(Paint.Style.STROKE);
    }

    public void update(int fps, String pkg) {
        this.fpsText = fps + " FPS";
        this.pkgText = pkg;
        
        int color;
        if (fps >= 60) color = Color.GREEN;
        else if (fps >= 30) color = Color.YELLOW;
        else color = Color.RED;

        textPaint.setColor(color);
        graphPaint.setColor(color);

        fpsHistory.addLast(fps);
        if (fpsHistory.size() > MAX_HISTORY) fpsHistory.removeFirst();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), 20, 20, bgPaint);

        if (fpsHistory.size() > 1) {
            Path path = new Path();
            float xStep = (float) getWidth() / MAX_HISTORY;
            float h = getHeight();
            for (int i = 0; i < fpsHistory.size(); i++) {
                float x = i * xStep;
                float y = h - (fpsHistory.get(i) * h / 144f);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            canvas.drawPath(path, graphPaint);
        }

        canvas.drawText(fpsText, 30, 60, textPaint);
        Paint pText = new Paint(textPaint);
        pText.setTextSize(22);
        pText.setColor(Color.WHITE);
        pText.setFakeBoldText(false);
        canvas.drawText(pkgText, 30, 95, pText);
    }
}
