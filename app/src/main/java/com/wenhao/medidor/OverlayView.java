package com.wenhao.medidor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;

/** Capa 2D sobre la cámara: líneas, puntos, etiquetas y retícula central. */
public class OverlayView extends View {

    public static class Label {
        final float x, y;
        final String text;
        final boolean area;

        Label(float x, float y, String text, boolean area) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.area = area;
        }
    }

    public static class Snapshot {
        final ArrayList<float[]> lines = new ArrayList<>();
        final ArrayList<float[]> points = new ArrayList<>();
        final ArrayList<Label> labels = new ArrayList<>();
        float[] liveLine;
        String liveText;
        boolean reticleOk;
    }

    private volatile Snapshot snapshot;

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint live = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointInner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bigText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reticle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;

        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * d);
        line.setStrokeCap(Paint.Cap.ROUND);

        lineShadow.setColor(0x80000000);
        lineShadow.setStyle(Paint.Style.STROKE);
        lineShadow.setStrokeWidth(6 * d);
        lineShadow.setStrokeCap(Paint.Cap.ROUND);

        live.setColor(0xFFFFEB3B);
        live.setStyle(Paint.Style.STROKE);
        live.setStrokeWidth(2.5f * d);
        live.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));

        pointOuter.setColor(Color.WHITE);
        pointInner.setColor(0xFF2196F3);

        labelBg.setColor(0xCC000000);
        areaBg.setColor(0xDD1565C0);

        labelText.setColor(Color.WHITE);
        labelText.setTextSize(14 * sp);
        labelText.setTypeface(Typeface.DEFAULT_BOLD);
        labelText.setTextAlign(Paint.Align.CENTER);

        bigText.setColor(Color.WHITE);
        bigText.setTextSize(28 * sp);
        bigText.setTypeface(Typeface.DEFAULT_BOLD);
        bigText.setTextAlign(Paint.Align.CENTER);

        reticle.setStyle(Paint.Style.STROKE);
        reticle.setStrokeWidth(2 * d);
    }

    public void setSnapshot(Snapshot s) {
        snapshot = s;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Snapshot s = snapshot;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        if (s != null) {
            for (float[] l : s.lines) {
                canvas.drawLine(l[0], l[1], l[2], l[3], lineShadow);
                canvas.drawLine(l[0], l[1], l[2], l[3], line);
            }
            if (s.liveLine != null) {
                float[] l = s.liveLine;
                canvas.drawLine(l[0], l[1], l[2], l[3], live);
            }
            for (float[] p : s.points) {
                canvas.drawCircle(p[0], p[1], 7 * d, pointOuter);
                canvas.drawCircle(p[0], p[1], 4 * d, pointInner);
            }
            for (Label lb : s.labels) {
                drawLabel(canvas, lb.x, lb.y, lb.text, labelText, lb.area ? areaBg : labelBg, 8 * d, 4 * d);
            }
        }

        boolean ok = s != null && s.reticleOk;
        reticle.setColor(ok ? 0xFF4CAF50 : 0xB4FFFFFF);
        float r = 18 * d;
        canvas.drawCircle(cx, cy, r, reticle);
        canvas.drawLine(cx - r - 8 * d, cy, cx - r + 6 * d, cy, reticle);
        canvas.drawLine(cx + r - 6 * d, cy, cx + r + 8 * d, cy, reticle);
        canvas.drawLine(cx, cy - r - 8 * d, cx, cy - r + 6 * d, reticle);
        canvas.drawLine(cx, cy + r - 6 * d, cx, cy + r + 8 * d, reticle);
        reticle.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 2.5f * d, reticle);
        reticle.setStyle(Paint.Style.STROKE);

        if (s != null && s.liveText != null) {
            drawLabel(canvas, cx, cy - r - 44 * d, s.liveText, bigText, labelBg, 14 * d, 6 * d);
        }
    }

    private void drawLabel(Canvas c, float x, float y, String text, Paint tp, Paint bg, float padX, float padY) {
        float w = tp.measureText(text);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float h = fm.descent - fm.ascent;
        rect.set(x - w / 2 - padX, y - h / 2 - padY, x + w / 2 + padX, y + h / 2 + padY);
        float rr = rect.height() / 2;
        c.drawRoundRect(rect, rr, rr, bg);
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, tp);
    }
}
