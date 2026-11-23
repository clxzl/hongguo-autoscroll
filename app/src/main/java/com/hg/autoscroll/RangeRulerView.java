package com.hg.autoscroll;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

public class RangeRulerView extends View {

    public interface OnIntervalChanged {
        void onIntervalChanged(int seconds);
    }

    private static final int MIN = 5;
    private static final int MAX = 600;

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private int value = 30;
    private OnIntervalChanged listener;
    private float downX;
    private float downY;
    private boolean dragging;

    public RangeRulerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        tick.setStrokeWidth(dp(1));
        mark.setColor(0xFFFFCC00);
        text.setColor(0xFF8A8A8A);
        text.setTextSize(sp(12));
        text.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        setContentDescription("间隔 30 秒，左右拖动调整");
    }

    public void setInterval(int seconds) {
        int v = Math.max(MIN, Math.min(MAX, seconds));
        if (v == value) return;
        value = v;
        setContentDescription("间隔 " + value + " 秒，左右拖动调整");
        invalidate();
    }

    public void setOnIntervalChanged(OnIntervalChanged listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float top = dp(8);
        float tickH = dp(18);
        float step = w / (MAX - MIN);

        for (int s = MIN; s <= MAX; s += 10) {
            float x = (s - MIN) * step;
            boolean major = s % 100 == 0;
            tick.setColor(major ? 0xFF555555 : 0xFF2E2E2E);
            canvas.drawLine(x, top, x, top + tickH, tick);
        }

        float mx = (value - MIN) * step;
        canvas.drawRect(mx - dp(3.5f), 0, mx + dp(3.5f), dp(7), mark);
        canvas.drawRect(mx - dp(0.5f), dp(7), mx + dp(0.5f), top + tickH + dp(8), mark);

        float baseline = h - dp(2);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("5", 0, baseline, text);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("300", (300 - MIN) * step, baseline, text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("600", w, baseline, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging && Math.abs(dx) > dp(6) && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) pick(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) pick(event.getX());
                release();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void release() {
        dragging = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void pick(float x) {
        float w = Math.max(getWidth(), 1);
        int seconds = Math.round(MIN + (x / w) * (MAX - MIN));
        seconds = Math.max(MIN, Math.min(MAX, seconds));
        if (seconds == value) return;
        value = seconds;
        setContentDescription("间隔 " + value + " 秒，左右拖动调整");
        invalidate();
        if (listener != null) listener.onIntervalChanged(value);
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
