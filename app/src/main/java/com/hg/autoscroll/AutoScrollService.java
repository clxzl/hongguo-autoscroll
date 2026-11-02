package com.hg.autoscroll;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AutoScrollService extends AccessibilityService {

    public static final String PREFS = "auto_scroll";
    public static final String KEY_MODE = "mode";
    public static final String KEY_INTERVAL = "interval";
    public static final String KEY_DEBUG = "debug_text";
    public static final String KEY_AD = "ad_enabled";
    public static final String KEY_BUBBLE = "bubble_enabled";
    public static final String KEY_RUNNING = "is_running";
    public static final String KEY_RUN_STARTED = "run_started_at";
    public static final String MODE_SMART = "smart";
    public static final String MODE_TIMER = "timer";
    public static final String LOG_FILE = "scan.log";

    private static final String TAG = "AutoScroll";
    // 「下一集」在红果的播放器控件/选集面板里常驻，会误触发，不能当片尾信号
    private static final String[] END_KEYWORDS = {"本集完", "本集已完", "下一话", "上滑继续"};
    private static final String AD_MARKER = "广告";
    private static final String GATE_MARKER = "后可继续";
    private static final long TICK_MS = 600;
    private static final long UI_TICK_MS = 1000;
    private static final long PROMPT_HOLD_MS = 1200;
    private static final long PROMPT_GRACE_MS = 2000;
    private static final long SMART_MIN_GAP_MS = 10000;
    private static final long AD_MIN_GAP_MS = 4000;
    private static final long AD_BLIND_MS = 8000;
    private static final long TAP_COOLDOWN_MS = 1200;
    private static final int MAX_AD_TAPS = 6;

    private static AutoScrollService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private BubbleView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private boolean bubbleAdded;
    private boolean bubbleCollapsed;
    private int bubbleSide = 1;
    private float downRawX;
    private float downRawY;
    private int dragStartX;
    private int dragStartY;
    private boolean dragging;
    private boolean dragMoved;
    private ValueAnimator bubbleAnim;

    private boolean running;
    private String targetPackage;
    private String lastForegroundPackage;
    private String targetLabel = "";

    private String mode = MODE_SMART;
    private int intervalSec = 30;
    private boolean adEnabled = true;
    private boolean bubbleEnabled = true;

    private long nextTimerSwipeAt;
    private long lastSmartSwipeAt;
    private long promptSince;
    private long lastPromptSeenAt;
    private long lastHiddenLogAt;
    private boolean lastKeywordPresent;
    private boolean gateActive;

    private boolean debugEnabled;
    private boolean lastAdPresent;
    private long adFirstSeenAt;
    private int adTapCount;
    private long lastTapAt;
    private String lastSnapshotText = "";
    private long lastSnapshotLogAt;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    loadPrefs();
                }
            };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                doTick();
            } catch (Exception e) {
                Log.e(TAG, "tick failed", e);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            currentForegroundPackage();
            updateBubbleState();
            syncBubble();
            handler.postDelayed(this, UI_TICK_MS);
        }
    };

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean isRunningNow() {
        return instance != null && instance.running;
    }

    public static void pauseFromUi() {
        if (instance != null) instance.setRunning(false);
    }

    public static boolean startFor(String pkg, String label) {
        if (instance == null) return false;
        instance.targetPackage = pkg;
        instance.targetLabel = label;
        instance.setRunning(true);
        instance.updateBubbleState();
        instance.syncBubble();
        return true;
    }

    public static String keywordSummary() {
        return String.join(" / ", END_KEYWORDS);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        getSharedPreferences(PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener);
        running = false;
        publishRunning(false);
        currentForegroundPackage();
        loadPrefs();
        syncBubble();
        updateBubbleState();
        log("service connected");
        handler.post(uiTick);
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        mode = sp.getString(KEY_MODE, MODE_SMART);
        intervalSec = sp.getInt(KEY_INTERVAL, 30);
        debugEnabled = sp.getBoolean(KEY_DEBUG, false);
        adEnabled = sp.getBoolean(KEY_AD, true);
        bubbleEnabled = sp.getBoolean(KEY_BUBBLE, true);
        if (intervalSec < 5) intervalSec = 5;
        if (intervalSec > 600) intervalSec = 600;
        if (running && MODE_TIMER.equals(mode)) {
            long now = System.currentTimeMillis();
            if (nextTimerSwipeAt < now) nextTimerSwipeAt = now + intervalSec * 1000L;
        }
        updateBubbleState();
        syncBubble();
    }

    private void syncBubble() {
        boolean own = getPackageName().equals(lastForegroundPackage);
        boolean shouldShow = bubbleEnabled && lastForegroundPackage != null && !own;
        if (shouldShow && !bubbleAdded) {
            addBubble();
        } else if (!shouldShow && bubbleAdded) {
            removeBubble();
        }
    }

    private void addBubble() {
        if (bubbleAdded || wm == null) return;
        if (bubble == null) {
            bubble = new BubbleView(this);
            final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (dragMoved) return true;
                    if (bubbleCollapsed) {
                        expandBubble();
                    } else {
                        toggleRunning();
                    }
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    // 窗口跟随手指移动时窗口内坐标几乎不变，慢拖会被误判为长按
                    if (dragMoved) return;
                    openMain();
                }
            });
            bubble.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    gd.onTouchEvent(event);
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            if (bubbleAnim != null) bubbleAnim.cancel();
                            downRawX = event.getRawX();
                            downRawY = event.getRawY();
                            dragStartX = bubbleParams.x;
                            dragStartY = bubbleParams.y;
                            dragging = false;
                            dragMoved = false;
                            break;
                        case MotionEvent.ACTION_MOVE: {
                            int dx = Math.round(event.getRawX() - downRawX);
                            int dy = Math.round(event.getRawY() - downRawY);
                            if (!dragging && (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4))) {
                                dragging = true;
                                dragMoved = true;
                                if (bubbleCollapsed) {
                                    expandFromCollapsedOnDrag();
                                }
                            }
                            if (dragging) {
                                DisplayMetrics dm = getResources().getDisplayMetrics();
                                int size = bubbleSize();
                                moveBubble(
                                        clamp(dragStartX + dx, 0, dm.widthPixels - size),
                                        clamp(dragStartY + dy, 0, dm.heightPixels - size));
                            }
                            break;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (dragging) {
                                dragging = false;
                                settleBubble();
                            }
                            break;
                        default:
                            break;
                    }
                    return true;
                }
            });

            DisplayMetrics dm = getResources().getDisplayMetrics();
            bubbleParams = new WindowManager.LayoutParams(
                    bubbleSize(),
                    bubbleSize(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            bubbleParams.gravity = Gravity.TOP | Gravity.START;
            bubbleParams.x = dm.widthPixels - bubbleSize() - dp(8);
            bubbleParams.y = (int) (dm.heightPixels * 0.42f);
        }
        try {
            wm.addView(bubble, bubbleParams);
            bubbleAdded = true;
            updateBubbleState();
        } catch (Exception e) {
            Log.e(TAG, "add bubble failed", e);
        }
    }

    private void removeBubble() {
        if (bubbleAnim != null) {
            bubbleAnim.cancel();
            bubbleAnim = null;
        }
        if (!bubbleAdded || wm == null || bubble == null) return;
        try {
            wm.removeView(bubble);
        } catch (Exception ignored) {
        }
        bubbleAdded = false;
    }

    private int bubbleSize() {
        return dp(50);
    }

    private void moveBubble(int x, int y) {
        bubbleParams.x = x;
        bubbleParams.y = y;
        if (!bubbleAdded) return;
        try {
            wm.updateViewLayout(bubble, bubbleParams);
        } catch (Exception ignored) {
        }
    }

    private void settleBubble() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = bubbleSize();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        int center = bubbleParams.x + size / 2;
        if (center <= dp(40)) {
            collapseBubble(-1);
        } else if (center >= w - dp(40)) {
            collapseBubble(1);
        } else {
            bubbleCollapsed = false;
            bubble.setCollapsed(false, bubbleSide);
            animateBubbleTo(clamp(bubbleParams.x, dp(4), w - size - dp(4)),
                    clamp(bubbleParams.y, 0, h - size), null);
        }
    }

    private void collapseBubble(int side) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleSide = side;
        int targetY = clamp(bubbleParams.y, 0, dm.heightPixels - bubbleSize());
        int edgeX = side < 0 ? 0 : dm.widthPixels - bubbleSize();
        animateBubbleTo(edgeX, targetY, () -> {
            if (!bubbleAdded) return;
            bubbleCollapsed = true;
            bubbleParams.width = dp(24);
            bubbleParams.x = side < 0 ? 0 : dm.widthPixels - dp(24);
            bubble.setCollapsed(true, side);
            if (bubbleAdded) {
                try {
                    wm.updateViewLayout(bubble, bubbleParams);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void expandBubble() {
        if (!bubbleCollapsed) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleCollapsed = false;
        bubbleParams.width = bubbleSize();
        bubbleParams.x = bubbleSide < 0 ? 0 : dm.widthPixels - bubbleSize();
        bubble.setCollapsed(false, bubbleSide);
        if (bubbleAdded) {
            try {
                wm.updateViewLayout(bubble, bubbleParams);
            } catch (Exception ignored) {
            }
        }
        int targetX = bubbleSide < 0 ? dp(8) : dm.widthPixels - bubbleSize() - dp(8);
        animateBubbleTo(targetX, clamp(bubbleParams.y, 0, dm.heightPixels - bubbleSize()), null);
    }

    private void expandFromCollapsedOnDrag() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleCollapsed = false;
        bubbleParams.width = bubbleSize();
        bubbleParams.x = bubbleSide < 0 ? 0 : dm.widthPixels - bubbleSize();
        bubble.setCollapsed(false, bubbleSide);
        dragStartX = bubbleParams.x;
        if (bubbleAdded) {
            try {
                wm.updateViewLayout(bubble, bubbleParams);
            } catch (Exception ignored) {
            }
        }
    }

    private void animateBubbleTo(int targetX, int targetY, Runnable onEnd) {
        if (bubbleAnim != null) bubbleAnim.cancel();
        final int startX = bubbleParams.x;
        final int startY = bubbleParams.y;
        if (startX == targetX && startY == targetY) {
            if (onEnd != null) onEnd.run();
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            moveBubble(Math.round(startX + (targetX - startX) * t),
                    Math.round(startY + (targetY - startY) * t));
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (bubbleAnim == animation) bubbleAnim = null;
                if (!cancelled && onEnd != null) onEnd.run();
            }
        });
        bubbleAnim = anim;
        anim.start();
    }

    private void updateBubbleState() {
        if (bubble == null) return;
        boolean targetForeground = targetPackage != null && targetPackage.equals(lastForegroundPackage);
        if (!targetForeground) {
            bubble.setState(BubbleView.State.OFFLINE, "");
            return;
        }
        if (!running) {
            bubble.setState(BubbleView.State.PAUSED, "");
            return;
        }
        long now = System.currentTimeMillis();
        if (adEnabled && lastAdPresent) {
            bubble.setState(BubbleView.State.AD, "AD");
            return;
        }
        if (MODE_TIMER.equals(mode)) {
            long left = Math.max(0, nextTimerSwipeAt - now);
            bubble.setState(BubbleView.State.RUNNING, String.format(Locale.CHINA, "%02d", (left + 999) / 1000));
            return;
        }
        bubble.setState(BubbleView.State.RUNNING, "AUTO");
    }

    private void toggleRunning() {
        if (running) {
            setRunning(false);
            toast("自动下滑已停止");
            updateBubbleState();
            return;
        }
        String pkg = currentForegroundPackage();
        boolean own = pkg != null && pkg.equals(getPackageName());
        boolean home = pkg != null && !own && isLauncher(pkg);
        if (pkg == null || own || home) {
            toast("请先打开红果短剧，再点这个按钮");
            return;
        }
        targetPackage = pkg;
        targetLabel = appLabel(pkg);
        setRunning(true);
        toast("自动下滑已开启（" + (MODE_SMART.equals(mode) ? "智能" : "定时") + " · " + targetLabel + "）");
        updateBubbleState();
    }

    private void setRunning(boolean value) {
        running = value;
        handler.removeCallbacks(tick);
        promptSince = 0;
        lastPromptSeenAt = 0;
        lastSmartSwipeAt = 0;
        lastKeywordPresent = false;
        gateActive = false;
        lastAdPresent = false;
        adTapCount = 0;
        lastSnapshotText = "";
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (running) {
            nextTimerSwipeAt = System.currentTimeMillis() + intervalSec * 1000L;
            handler.postDelayed(tick, TICK_MS);
            sp.edit()
                    .putBoolean(KEY_RUNNING, true)
                    .putLong(KEY_RUN_STARTED, System.currentTimeMillis())
                    .apply();
            log("start target=" + targetPackage + " mode=" + mode);
        } else {
            sp.edit()
                    .putBoolean(KEY_RUNNING, false)
                    .putLong(KEY_RUN_STARTED, 0)
                    .apply();
            log("stop");
        }
        updateBubbleState();
    }

    private void publishRunning(boolean value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, value)
                .putLong(KEY_RUN_STARTED, 0)
                .apply();
    }

    private void doTick() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        String fg;
        if (root != null && root.getPackageName() != null) {
            fg = root.getPackageName().toString();
            lastForegroundPackage = fg;
        } else {
            fg = lastForegroundPackage;
        }
        syncBubble();
        if (targetPackage == null || fg == null || !targetPackage.equals(fg)) return;

        long now = System.currentTimeMillis();
        ScanResult scan = new ScanResult();
        if (root != null) {
            scan.snapshot = debugEnabled ? new StringBuilder() : null;
            walk(root, 0, scan);
        }
        if (scan.snapshot != null) logSnapshot(scan.snapshot.toString(), now);

        // 倒计时广告（N 秒后可继续上滑）：这期间滑动会被吞掉，等它自己走完
        if (scan.gate) {
            if (!gateActive) {
                gateActive = true;
                log("gate detected");
            }
            if (lastAdPresent) {
                lastAdPresent = false;
                log("ad gone");
            }
            updateBubbleState();
            return;
        }
        if (gateActive) {
            gateActive = false;
            log("gate completed");
        }

        boolean adPage = adEnabled && scan.ad;
        if (adPage) {
            if (!lastAdPresent) {
                lastAdPresent = true;
                adTapCount = 0;
                adFirstSeenAt = now;
                log("ad detected");
            }
            if (scan.skipNode != null && adTapCount < MAX_AD_TAPS && now - lastTapAt >= TAP_COOLDOWN_MS) {
                lastTapAt = now;
                adTapCount++;
                boolean ok = scan.skipNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                log("click skip(" + scan.skipText + ") " + (ok ? "ok" : "failed"));
            } else if (scan.endHit == null && scan.skipNode == null
                    && now - adFirstSeenAt >= AD_BLIND_MS
                    && (lastSmartSwipeAt == 0 || now - lastSmartSwipeAt >= SMART_MIN_GAP_MS)) {
                // 广告页既没跳过也没提示：直接盲滑一次，免得卡在广告里
                lastSmartSwipeAt = now;
                swipeUp("ad");
            }
        } else if (lastAdPresent) {
            lastAdPresent = false;
            log("ad gone");
            if (MODE_TIMER.equals(mode)) nextTimerSwipeAt = now + intervalSec * 1000L;
        }
        updateBubbleState();

        if (MODE_TIMER.equals(mode)) {
            if (now >= nextTimerSwipeAt) {
                swipeUp("timer");
                nextTimerSwipeAt = now + intervalSec * 1000L;
                updateBubbleState();
            }
            return;
        }

        // 智能模式：不看「广告」这类文案（很多广告页在无障碍树里只剩画面），只认上滑提示。
        // 提示必须真正可见（红果会把还没到时间的提示节点提前挂进树里，vis=false，不能算数）；
        // 真提示会闪烁，缺一两次采样不能算消失，否则永远等不到出现时长
        String hit = scan.endHit;
        if (hit == null) {
            if (scan.hiddenHit != null && now - lastHiddenLogAt >= 10_000L) {
                lastHiddenLogAt = now;
                log("keyword hidden ignored: " + scan.hiddenHit + " @" + boundsText(scan.hiddenBounds));
            }
            if (promptSince != 0 && now - lastPromptSeenAt >= PROMPT_GRACE_MS) {
                promptSince = 0;
                lastKeywordPresent = false;
            }
            return;
        }
        lastPromptSeenAt = now;
        if (promptSince == 0) {
            promptSince = now;
            lastKeywordPresent = true;
            log("keyword: " + hit + " @" + boundsText(scan.hitBounds) + " vis=" + scan.hitVisible);
        }
        if (now - promptSince < PROMPT_HOLD_MS) return;
        // 广告页滑早了也没关系，可以早点离开；正片页则留足间隔，防止连滑跳集
        long minGap = adPage ? AD_MIN_GAP_MS : SMART_MIN_GAP_MS;
        if (lastSmartSwipeAt != 0 && now - lastSmartSwipeAt < minGap) return;

        lastSmartSwipeAt = now;
        promptSince = 0;
        lastKeywordPresent = false;
        swipeUp("smart");
    }

    private static class ScanResult {
        String endHit;
        Rect hitBounds;
        boolean hitVisible;
        String hiddenHit;
        Rect hiddenBounds;
        boolean ad;
        boolean gate;
        AccessibilityNodeInfo skipNode;
        String skipText;
        StringBuilder snapshot;
    }

    private void walk(AccessibilityNodeInfo node, int depth, ScanResult out) {
        if (node == null || depth > 25) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String t = text == null ? null : text.toString();
        String d = desc == null ? null : desc.toString();

        if (out.endHit == null && (matchKeyword(t) || matchKeyword(d))) {
            String s = matchKeyword(t) ? t : d;
            if (node.isVisibleToUser()) {
                out.endHit = s;
                out.hitBounds = new Rect();
                node.getBoundsInScreen(out.hitBounds);
                out.hitVisible = true;
            } else if (out.hiddenHit == null) {
                out.hiddenHit = s;
                out.hiddenBounds = new Rect();
                node.getBoundsInScreen(out.hiddenBounds);
            }
        }
        if (!out.ad && ((t != null && t.contains(AD_MARKER)) || (d != null && d.contains(AD_MARKER)))) {
            out.ad = true;
        }
        if (!out.gate && ((t != null && t.contains(GATE_MARKER)) || (d != null && d.contains(GATE_MARKER)))) {
            out.gate = true;
        }
        if (out.skipText == null) {
            checkSkipNode(node, t, out);
            if (out.skipText == null) checkSkipNode(node, d, out);
        }
        if (out.skipText != null) out.ad = true;
        if (out.snapshot != null && t != null && out.snapshot.length() < 1200) {
            out.snapshot.append(t).append(" | ");
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            walk(node.getChild(i), depth + 1, out);
        }
    }

    private void checkSkipNode(AccessibilityNodeInfo node, String text, ScanResult out) {
        if (!isSkipText(text) || !node.isVisibleToUser()) return;
        AccessibilityNodeInfo target = findClickable(node, 4);
        if (target == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int area = bounds.width() * bounds.height();
        if (area <= 0) return;
        if (out.skipNode != null) {
            Rect old = new Rect();
            out.skipNode.getBoundsInScreen(old);
            if (area >= old.width() * old.height()) return;
        }
        out.skipNode = target;
        out.skipText = text;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node, int maxUp) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i <= maxUp && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private boolean isSkipText(String text) {
        if (text == null) return false;
        if (text.contains("片头")) return false;
        return text.contains("跳过") || text.contains("关闭广告");
    }

    private boolean matchKeyword(String text) {
        if (text == null) return false;
        for (String k : END_KEYWORDS) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private String boundsText(Rect b) {
        if (b == null) return "[?]";
        return "[" + b.left + "," + b.top + "][" + b.right + "," + b.bottom + "]";
    }

    private void logSnapshot(String text, long now) {
        if (text.isEmpty() || text.equals(lastSnapshotText)) return;
        if (now - lastSnapshotLogAt < 1000) return;
        lastSnapshotText = text;
        lastSnapshotLogAt = now;
        log("ui: " + text);
    }

    private void swipeUp(String reason) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        float x = w * (0.45f + (float) Math.random() * 0.1f);
        Path path = new Path();
        path.moveTo(x, h * 0.80f);
        path.lineTo(x, h * 0.25f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 260))
                .build();
        boolean ok = dispatchGesture(gesture, null, null);
        log("swipe(" + reason + ") " + (ok ? "sent" : "failed"));
    }

    private String currentForegroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            lastForegroundPackage = root.getPackageName().toString();
        }
        return lastForegroundPackage;
    }

    private boolean isLauncher(String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
        return ri != null && ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName);
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void openMain() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "open main failed", e);
        }
    }

    private void log(String msg) {
        try {
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date()) + " " + msg + "\n";
            File f = new File(getFilesDir(), LOG_FILE);
            if (f.length() > 60_000) {
                byte[] data = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int read = in.read(data);
                    int keep = Math.min(Math.max(read, 0), 30_000);
                    String tail = new String(data, Math.max(read - keep, 0), keep, StandardCharsets.UTF_8);
                    try (FileOutputStream out = new FileOutputStream(f, false)) {
                        out.write(tail.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "log failed", e);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, Math.max(min, max)));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundPackage();
            updateBubbleState();
            syncBubble();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        running = false;
        publishRunning(false);
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener);
        removeBubble();
        super.onDestroy();
    }
}
