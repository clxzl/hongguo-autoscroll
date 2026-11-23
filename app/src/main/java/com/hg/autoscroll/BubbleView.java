package com.hg.autoscroll;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;

import java.util.Collections;

public class BubbleView extends View {

    public enum State { PAUSED, RUNNING, AD, OFFLINE }

    private static final int YELLOW = 0xFFFFCC00;
    private static final int INK = 0xFFEDEDED;
    private static final int GRAY = 0xFFC8C8C8;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private State state = State.PAUSED;
    private String value = "";
    private boolean collapsed;
    private int side = 1;

    public BubbleView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        try {
            textPaint.setTypeface(Typeface.createFromAsset(context.getAssets(),
                    "fonts/JetBrainsMono-Bold.ttf"));
        } catch (Exception e) {
            textPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        }
        setContentDescription("自动上滑已暂停，单击开始");
    }

    public void setState(State state, String value) {
        if (this.state == state && this.value.equals(value)) return;
        this.state = state;
        this.value = value == null ? "" : value;
        if (!collapsed) setContentDescription(describe());
        invalidate();
    }

    public void setCollapsed(boolean collapsed, int side) {
        if (this.collapsed == collapsed && this.side == side) return;
        this.collapsed = collapsed;
        this.side = side;
        setContentDescription(collapsed ? "悬浮球已收起，单击展开" : describe());
        invalidate();
    }

    private String describe() {
        switch (state) {
            case RUNNING:
                return "自动上滑运行中，单击暂停";
            case AD:
                return "等待广告，" + value + " 秒后尝试上滑，单击暂停";
            case OFFLINE:
                return "自动上滑未开始，单击在当前应用开始";
            default:
                return "自动上滑已暂停，单击开始";
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // 收起贴边时手指从系统返回手势区按下，排除后拖动才不会被边缘滑动抢走
        if (Build.VERSION.SDK_INT >= 29 && getWidth() > 0 && getHeight() > 0) {
            setSystemGestureExclusionRects(
                    Collections.singletonList(new Rect(0, 0, getWidth(), getHeight())));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cy = getHeight() / 2f;
        float cx = collapsed ? (side < 0 ? 0 : getWidth()) : getWidth() / 2f;
        float r = cy - dp(6);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xA6000000);
        canvas.drawCircle(cx, cy, r, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        switch (state) {
            case RUNNING:
            case AD:
                paint.setColor(YELLOW);
                break;
            default:
                paint.setColor(0xFF7A7A7A);
                break;
        }
        canvas.drawCircle(cx, cy, r, paint);

        if (state == State.AD) {
            paint.setColor(0x99FFCC00);
            paint.setStrokeWidth(dp(1));
            paint.setPathEffect(new DashPathEffect(new float[]{dp(2), dp(3)}, 0));
            canvas.drawCircle(cx, cy, r + dp(4), paint);
            paint.setPathEffect(null);
        }

        if (collapsed) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(state == State.PAUSED || state == State.OFFLINE ? GRAY : YELLOW);
            canvas.drawCircle(cx - side * r * 0.7f, cy, dp(2.5f), paint);
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        if (state == State.RUNNING || state == State.AD) {
            paint.setColor(YELLOW);
            canvas.drawRect(cx - dp(1), cy - r - dp(4.5f), cx + dp(1), cy - r - dp(0.5f), paint);
            canvas.drawRect(cx - dp(1), cy + r + dp(0.5f), cx + dp(1), cy + r + dp(4.5f), paint);
        }

        if (state == State.PAUSED) {
            paint.setColor(GRAY);
            canvas.drawRect(cx - dp(5), cy - dp(5.5f), cx - dp(2.5f), cy + dp(5.5f), paint);
            canvas.drawRect(cx + dp(2.5f), cy - dp(5.5f), cx + dp(5), cy + dp(5.5f), paint);
            return;
        }

        if (state == State.OFFLINE) {
            paint.setColor(GRAY);
            paint.setStrokeWidth(dp(2));
            float d = dp(7.5f);
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        textPaint.setColor(state == State.AD ? YELLOW : INK);
        textPaint.setTextSize(value.length() > 2 ? dp(10.5f) : dp(16f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f - dp(0.5f);
        canvas.drawText(value, cx, baseline, textPaint);
    }

    private float dp(float value) {
        return value * density;
    }
}
