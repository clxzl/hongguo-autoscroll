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
    public static final String KEY_AD_LEVEL = "ad_level";
    public static final String AD_LEVEL_STD = "std";
    public static final String AD_LEVEL_STRONG = "strong";
    public static final String KEY_BUBBLE = "bubble_enabled";
    public static final String KEY_RUNNING = "is_running";
    public static final String KEY_RUN_STARTED = "run_started_at";
    public static final String MODE_SMART = "smart";
    public static final String MODE_TIMER = "timer";
    public static final String LOG_FILE = "scan.log";

    private static final String TAG = "AutoScroll";
    private static final long TICK_MS = 600;
    private static final long UI_TICK_MS = 1000;
    private static final long PROMPT_HOLD_MS = 1200;
    private static final long PROMPT_GRACE_MS = 2000;
    private static final long SMART_MIN_GAP_MS = 10000;
    private static final long TAP_COOLDOWN_MS = 1200;
    private static final int MAX_AD_TAPS = 6;
    /** 只看到「广告」两个字时，需要连续出现这么久才认定是广告页 */
    private static final long AD_LABEL_CONFIRM_MS = 1200;

    // ---- 广告处理 ----
    /** 倒计时归零后留一点时间给界面稳定再上滑，避免手势被锁定期吞掉 */
    private static final long AD_DEADLINE_BUFFER_MS = 350;
    /** 屏幕上的秒数每轮都在刷新，归零时刻偏差超过这个值才重新对齐 */
    private static final long AD_DEADLINE_TOLERANCE_MS = 1200;
    /** 一波广告最多滑几次 / 最多折腾多久，防止在卡住的广告里死循环 */
    private static final int AD_MAX_ATTEMPTS = 8;
    private static final int AD_MAX_ATTEMPTS_STRICT = 10;
    private static final long AD_WAVE_MAX_MS = 45000;
    /** 两次广告上滑的最小间隔：普通广告页 / 直播购物页（后者要更急，不然一直卡住） */
    private static final long AD_SWIPE_GAP_MS = 2600;
    private static final long AD_SWIPE_GAP_FAST_MS = 1800;
    /** 画面型广告兜底：屏上一点文字都没有持续这么久，才怀疑是整页广告画面 */
    private static final long AD_BLANK_CONFIRM_MS = 5000;
    private static final long AD_BLANK_BLIP_MS = 3000;
    /** 空白页兜底限流：5 分钟内最多滑 4 次，避免把无文字的正片当广告连环跳集 */
    private static final int AD_BLANK_MAX_FLIPS = 4;
    private static final long AD_BLANK_WINDOW_MS = 300000;
    /** 只有刚见过广告证据才允许空白页兜底 */
    private static final long AD_BLANK_EVIDENCE_MS = 120000;
    /** 广告刚结束的这段时间里出现的上滑提示，按广告节奏处理，别套用片尾的 10 秒间隔 */
    private static final long AD_PROMPT_GRACE_MS = 8000;

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
    private String lastActivityClass = "";
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

    private boolean debugEnabled;
    private String adLevel = AD_LEVEL_STD;
    private boolean lastAdPresent;
    private int adTapCount;
    private long lastTapAt;
    private long adWaveStartAt;
    private int adAttempts;
    private long gateDeadlineAt;
    private boolean adWaveTimedOut;
    private long lastAdEvidenceAt;
    private long adClearedAt;
    private long weakAdSince;
    private boolean speedPanelPaused;
    private long blankSince;
    private final long[] blankFlips = new long[AD_BLANK_MAX_FLIPS];
    private int blankFlipIdx;
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
        return AdDetector.endKeywordSummary();
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
        adLevel = sp.getString(KEY_AD_LEVEL, AD_LEVEL_STD);
        if (!AD_LEVEL_STRONG.equals(adLevel)) adLevel = AD_LEVEL_STD;
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
        lastAdPresent = false;
        adTapCount = 0;
        adWaveStartAt = 0;
        adAttempts = 0;
        gateDeadlineAt = 0;
        adWaveTimedOut = false;
        lastAdEvidenceAt = 0;
        adClearedAt = 0;
        weakAdSince = 0;
        speedPanelPaused = false;
        blankSince = 0;
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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        AdDetector.Snapshot scan = AdDetector.scan(root, dm.widthPixels, dm.heightPixels);
        if (debugEnabled) logSnapshot(scan.texts, now);

        // 倍速面板里的「上滑锁定倍速」和片尾提示同形，面板在屏上时一律不滑
        if (scan.speedPanel) {
            if (!speedPanelPaused) {
                speedPanelPaused = true;
                log("speed panel on screen, swipe paused");
            }
            return;
        }
        if (speedPanelPaused) {
            speedPanelPaused = false;
            log("speed panel gone");
        }

        // 广告优先：广告页也会挂「上滑继续观看」提示，先按广告处理掉，避免同一页被滑两次
        if (adEnabled) {
            if (scan.isAd()) {
                // 弱证据（只有「广告」两个字 / 只有 SDK 控件）先观察 1.2s 再动手，防单帧误判
                if (scan.isWeak()) {
                    if (weakAdSince == 0) {
                        weakAdSince = now;
                        return;
                    }
                    if (now - weakAdSince < AD_LABEL_CONFIRM_MS) return;
                } else {
                    weakAdSince = 0;
                }
                lastAdPresent = true;
                handleAd(scan, now);
                updateBubbleState();
                return;
            }
            weakAdSince = 0;
            if (lastAdPresent) {
                onAdCleared(now);
                updateBubbleState();
            }
            if (MODE_SMART.equals(mode) && AD_LEVEL_STRONG.equals(adLevel) && handleBlankPage(scan, now)) {
                updateBubbleState();
                return;
            }
        }

        if (MODE_TIMER.equals(mode)) {
            if (now >= nextTimerSwipeAt) {
                swipeUp("timer", false);
                nextTimerSwipeAt = now + intervalSec * 1000L;
                updateBubbleState();
            }
            return;
        }

        // 智能模式：不看「广告」这类文案，只认上滑提示。
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
            log("keyword: " + hit + " @" + boundsText(scan.endBounds));
        }
        if (now - promptSince < PROMPT_HOLD_MS) return;
        // 广告刚结束（倒计时读完 / 广告页被滑走）时出现的提示按广告节奏处理，
        // 正片片尾才留足 10 秒间隔，防止连滑跳集
        boolean afterAd = adClearedAt != 0 && now - adClearedAt < AD_PROMPT_GRACE_MS;
        long minGap = afterAd ? AD_SWIPE_GAP_MS : SMART_MIN_GAP_MS;
        if (lastSmartSwipeAt != 0 && now - lastSmartSwipeAt < minGap) return;

        lastSmartSwipeAt = now;
        promptSince = 0;
        lastKeywordPresent = false;
        swipeUp("smart", false);
    }

    /** 广告处理：有倒计时就等它走完，能点「跳过」就点，到点立刻上滑 */
    private void handleAd(AdDetector.Snapshot scan, long now) {
        boolean first = adWaveStartAt == 0;
        if (first) {
            adWaveStartAt = now;
            adAttempts = 0;
            adWaveTimedOut = false;
            log("ad detected: " + scan.reason
                    + (scan.countdownSec > 0 ? " · " + scan.countdownSec + "s" : "")
                    + (activityShort().isEmpty() ? "" : " @" + activityShort()));
        }
        lastAdEvidenceAt = now;

        // 1) 跳过按钮：可用就点。「跳过 5」这类未归零的按钮在识别阶段已经排除，不会白点
        if (scan.skipNode != null && adTapCount < MAX_AD_TAPS && now - lastTapAt >= TAP_COOLDOWN_MS) {
            lastTapAt = now;
            adTapCount++;
            boolean ok = scan.skipNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            log("click skip(" + scan.skipText + ") " + (ok ? "ok" : "failed"));
        }

        // 2) 倒计时：把「还剩几秒」换算成归零时刻，屏幕上的秒数每轮都用来校正
        if (scan.countdownSec > 0) {
            long deadline = now + scan.countdownSec * 1000L + AD_DEADLINE_BUFFER_MS;
            if (Math.abs(deadline - gateDeadlineAt) > AD_DEADLINE_TOLERANCE_MS) {
                gateDeadlineAt = deadline;
            }
            if (now < gateDeadlineAt) {
                return;   // 还在读秒，这时候滑会被锁定期吞掉
            }
        }

        // 3) 到点 / 无倒计时：上滑离开广告
        if (adAttempts >= maxAdAttempts()) {
            if (!adWaveTimedOut) {
                adWaveTimedOut = true;
                log("ad still here after " + adAttempts + " swipes, wait");
            }
            return;
        }
        if (now - adWaveStartAt > AD_WAVE_MAX_MS) {
            if (!adWaveTimedOut) {
                adWaveTimedOut = true;
                log("ad wave timeout (" + (AD_WAVE_MAX_MS / 1000) + "s), stop retrying");
            }
            return;
        }
        // 直播页（商品面板 / 直播播放器）要更急，手势也要从更高处起手
        boolean livePage = scan.liveShop || scan.livePlayer;
        long gap = (livePage || AD_LEVEL_STRONG.equals(adLevel))
                ? AD_SWIPE_GAP_FAST_MS : AD_SWIPE_GAP_MS;
        if (lastSmartSwipeAt != 0 && now - lastSmartSwipeAt < gap) return;

        adAttempts++;
        lastSmartSwipeAt = now;
        log("ad swipe " + adAttempts + "/" + maxAdAttempts() + " (" + scan.reason + ")");
        swipeUp("ad", livePage);
        promptSince = 0;
        lastKeywordPresent = false;
    }

    private void onAdCleared(long now) {
        if (adAttempts > 0) {
            log("ad cleared after " + adAttempts + " swipe(s)");
        } else {
            log("ad cleared");
        }
        lastAdPresent = false;
        adClearedAt = now;
        adWaveStartAt = 0;
        adAttempts = 0;
        adTapCount = 0;
        gateDeadlineAt = 0;
        adWaveTimedOut = false;
        blankSince = 0;
        promptSince = 0;
        if (MODE_TIMER.equals(mode)) nextTimerSwipeAt = now + intervalSec * 1000L;
    }

    /**
     * 画面型广告兜底：广告创意由 SDK 直接绘制，整页读不到一个字，只能靠「屏幕彻底没有文字」来判断。
     * 两道保险：必须刚见过广告证据（否则无文字的正片也会命中），且 5 分钟内最多滑 4 次。
     */
    private boolean handleBlankPage(AdDetector.Snapshot scan, long now) {
        if (scan.textCount > 0) {
            if (blankSince == 0) return false;
            // 界面重绘时会短暂读到文字，宽限一次，别把计时清零
            if (now - blankSince < AD_BLANK_BLIP_MS) return false;
            blankSince = 0;
            return false;
        }
        if (blankSince == 0) {
            blankSince = now;
            return false;
        }
        if (now - blankSince < AD_BLANK_CONFIRM_MS) return false;
        if (lastAdEvidenceAt == 0 || now - lastAdEvidenceAt > AD_BLANK_EVIDENCE_MS) return false;
        if (!activityLooksLikePlayer()) return false;
        if (!allowBlankFlip(now)) return false;

        lastAdEvidenceAt = now;
        lastSmartSwipeAt = now;
        log("blank page " + ((now - blankSince) / 1000) + "s, ad fallback swipe");
        swipeUp("ad:blank", true);
        blankSince = now;
        return true;
    }

    /** 空白页兜底限流：5 分钟窗口内最多 AD_BLANK_MAX_FLIPS 次 */
    private boolean allowBlankFlip(long now) {
        int count = 0;
        for (long t : blankFlips) {
            if (t != 0 && now - t < AD_BLANK_WINDOW_MS) count++;
        }
        if (count >= AD_BLANK_MAX_FLIPS) return false;
        blankFlips[blankFlipIdx % AD_BLANK_MAX_FLIPS] = now;
        blankFlipIdx++;
        return true;
    }

    private int maxAdAttempts() {
        return AD_LEVEL_STRONG.equals(adLevel) ? AD_MAX_ATTEMPTS_STRICT : AD_MAX_ATTEMPTS;
    }

    private String boundsText(Rect b) {
        if (b == null) return "[?]";
        return "[" + b.left + "," + b.top + "][" + b.right + "," + b.bottom + "]";
    }

    private void logSnapshot(String text, long now) {
        if (text == null || text.isEmpty() || text.equals(lastSnapshotText)) return;
        if (now - lastSnapshotLogAt < 1000) return;
        lastSnapshotText = text;
        lastSnapshotLogAt = now;
        log("ui: " + text);
    }

    /**
     * 上滑。
     *
     * @param upper true = 从 0.60 屏高起手：直播购物类广告底部是可滚动商品面板，
     *              从 0.80 起手手势会被面板吃掉
     */
    private void swipeUp(String reason, boolean upper) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        float x = w * (0.45f + (float) Math.random() * 0.1f);
        Path path = new Path();
        path.moveTo(x, upper ? h * 0.60f : h * 0.80f);
        path.lineTo(x, upper ? h * 0.03f : h * 0.25f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, upper ? 220 : 260))
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
            // 红果用的是番茄那套壳：短剧页是 com.dragon.read...ShortSeriesActivity，
            // 阅读页是 ...ReaderActivity。窗口状态事件里的 className 就是 Activity 名
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null && cls != null && pkg.toString().equals(targetPackage)) {
                lastActivityClass = cls.toString();
            }
            currentForegroundPackage();
            updateBubbleState();
            syncBubble();
        }
    }

    private String activityShort() {
        if (lastActivityClass.isEmpty()) return "";
        int dot = lastActivityClass.lastIndexOf('.');
        return dot >= 0 ? lastActivityClass.substring(dot + 1) : lastActivityClass;
    }

    /**
     * 当前页是不是「播放页」。空白页兜底只在播放页生效，页面类型拿不到时放行（有的机型不上报）。
     */
    private boolean activityLooksLikePlayer() {
        if (lastActivityClass.isEmpty()) return true;
        String c = lastActivityClass;
        return c.contains("ShortSeries") || c.contains("Reader")
                || c.contains("Player") || c.contains("Video");
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
