package com.hg.autoscroll;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int BG = Color.BLACK;
    private static final int INK = 0xFFEDEDED;
    private static final int MUTED = 0xFF9A9A9A;
    private static final int FAINT = 0xFF8A8A8A;
    private static final int LINE = 0xFF1D1D1D;
    private static final int YELLOW = 0xFFFFCC00;

    private SharedPreferences sp;
    private Handler handler;

    private TextView versionView;
    private TextView heroNumber;
    private TextView heroMode;
    private TextView modeValue;
    private TextView adValue;
    private TextView adLevelValue;
    private TextView bubbleValue;
    private TextView accessValue;
    private View accessDot;
    private TextView logValue;
    private RangeRulerView ruler;
    private Switch adSwitch;
    private Switch bubbleSwitch;
    private ShutterButton shutter;
    private TextView footerState;
    private TextView footerElapsed;

    private Typeface monoRegular;
    private Typeface monoBold;
    private int intervalSec = 30;

    private AlertDialog logDialog;
    private TextView logText;
    private ScrollView logScroll;
    private Runnable logRefresh;

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            refreshRunning();
            handler.postDelayed(this, 1000);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (AutoScrollService.KEY_INTERVAL.equals(key)) {
                        intervalSec = sp.getInt(AutoScrollService.KEY_INTERVAL, 30);
                        heroNumber.setText(String.valueOf(intervalSec));
                        ruler.setInterval(intervalSec);
                    }
                    refreshRows();
                    refreshRunning();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(AutoScrollService.PREFS, MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        monoRegular = loadMono(false);
        monoBold = loadMono(true);
        intervalSec = sp.getInt(AutoScrollService.KEY_INTERVAL, 30);

        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(buildHeader());
        content.addView(buildHero(), topMargin(dp(26)));
        LinearLayout.LayoutParams rulerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        rulerLp.topMargin = dp(14);
        content.addView(buildRuler(), rulerLp);
        content.addView(buildRows(), topMargin(dp(16)));

        TextView note = new TextView(this);
        note.setText("只在红果短剧内识别；切出红果或锁屏时不会滑动。长按悬浮球可回到设置，识别只在本机完成。");
        note.setTextSize(15);
        note.setTextColor(MUTED);
        note.setLineSpacing(dp(3), 1f);
        content.addView(note, topMargin(dp(18)));

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(86)));

        root.addView(buildFooter(), bottomParams(dp(74)));
        root.addView(new CornerFrame(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        scroll.setVerticalFadingEdgeEnabled(false);
        refreshRows();
        refreshRunning();
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView title = new TextView(this);
        title.setText("续一集");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);
        title.setLetterSpacing(0.12f);
        titleBox.addView(title);

        TextView sub = new TextView(this);
        sub.setText("AUTO SCROLL / SETTINGS");
        sub.setTextSize(12);
        sub.setTypeface(monoRegular);
        sub.setTextColor(FAINT);
        sub.setLetterSpacing(0.1f);
        sub.setPadding(0, dp(6), 0, 0);
        titleBox.addView(sub);
        row.addView(titleBox, titleLp);

        versionView = new TextView(this);
        versionView.setText("v" + versionName());
        versionView.setTextSize(12);
        versionView.setTypeface(monoRegular);
        versionView.setTextColor(FAINT);
        versionView.setLetterSpacing(0.06f);
        row.addView(versionView);
        return row;
    }

    private View buildHero() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout lab = new LinearLayout(this);
        lab.setOrientation(LinearLayout.HORIZONTAL);
        lab.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = new TextView(this);
        left.setText("INTERVAL / 间隔");
        left.setTextSize(12);
        left.setTypeface(monoRegular);
        left.setTextColor(MUTED);
        left.setLetterSpacing(0.1f);
        lab.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        heroMode = new TextView(this);
        heroMode.setTextSize(12);
        heroMode.setTypeface(monoRegular);
        heroMode.setTextColor(FAINT);
        heroMode.setLetterSpacing(0.06f);
        lab.addView(heroMode);
        box.addView(lab);

        LinearLayout numberRow = new LinearLayout(this);
        numberRow.setOrientation(LinearLayout.HORIZONTAL);
        numberRow.setGravity(Gravity.BOTTOM);

        heroNumber = new TextView(this);
        heroNumber.setText(String.valueOf(intervalSec));
        heroNumber.setTextSize(96);
        heroNumber.setTypeface(monoBold);
        heroNumber.setTextColor(YELLOW);
        heroNumber.setIncludeFontPadding(false);
        heroNumber.setLetterSpacing(-0.04f);
        heroNumber.setPadding(0, dp(6), 0, 0);
        heroNumber.setOnClickListener(v -> showIntervalDialog());
        numberRow.addView(heroNumber);

        TextView sec = new TextView(this);
        sec.setText("″");
        sec.setTextSize(34);
        sec.setTypeface(monoBold);
        sec.setTextColor(YELLOW);
        sec.setIncludeFontPadding(false);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.leftMargin = dp(4);
        secLp.bottomMargin = dp(14);
        numberRow.addView(sec, secLp);
        box.addView(numberRow);

        TextView hint = new TextView(this);
        hint.setText("智能模式只在片尾触发；定时模式按这个间隔上滑。");
        hint.setTextSize(15);
        hint.setTextColor(MUTED);
        hint.setPadding(0, dp(4), 0, 0);
        box.addView(hint);
        return box;
    }

    private View buildRuler() {
        ruler = new RangeRulerView(this);
        ruler.setInterval(intervalSec);
        ruler.setOnIntervalChanged(new RangeRulerView.OnIntervalChanged() {
            @Override
            public void onIntervalChanged(int seconds) {
                intervalSec = seconds;
                heroNumber.setText(String.valueOf(seconds));
                sp.edit().putInt(AutoScrollService.KEY_INTERVAL, seconds).apply();
            }
        });
        return ruler;
    }

    private View buildRows() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout modeRight = new LinearLayout(this);
        modeRight.setOrientation(LinearLayout.HORIZONTAL);
        modeRight.setGravity(Gravity.CENTER_VERTICAL);
        modeValue = monoText("智能识别", 13, INK);
        modeRight.addView(modeValue, rightTextParams());
        modeRight.addView(chevron(), leftMargin(dp(8)));
        View modeRow = row("模式", "MODE", modeRight, v -> showModeDialog());
        addRow(box, modeRow);

        LinearLayout adRight = new LinearLayout(this);
        adRight.setOrientation(LinearLayout.HORIZONTAL);
        adRight.setGravity(Gravity.CENTER_VERTICAL);
        adValue = monoText("自动跳过", 13, INK);
        adRight.addView(adValue, rightTextParams());
        adSwitch = makeSwitch(sp.getBoolean(AutoScrollService.KEY_AD, true));
        adSwitch.setContentDescription("广告处理");
        adRight.addView(adSwitch, leftMargin(dp(8)));
        View adRow = row("广告处理", "AD POLICY", adRight, null);
        adSwitch.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean(AutoScrollService.KEY_AD, checked).apply();
            refreshRows();
        });
        addRow(box, adRow);

        LinearLayout adLevelRight = new LinearLayout(this);
        adLevelRight.setOrientation(LinearLayout.HORIZONTAL);
        adLevelRight.setGravity(Gravity.CENTER_VERTICAL);
        adLevelValue = monoText("标准", 13, INK);
        adLevelRight.addView(adLevelValue, rightTextParams());
        adLevelRight.addView(chevron(), leftMargin(dp(8)));
        View adLevelRow = row("广告力度", "AD DEPTH", adLevelRight, v -> showAdLevelDialog());
        addRow(box, adLevelRow);

        LinearLayout bubbleRight = new LinearLayout(this);
        bubbleRight.setOrientation(LinearLayout.HORIZONTAL);
        bubbleRight.setGravity(Gravity.CENTER_VERTICAL);
        bubbleValue = monoText("开", 13, INK);
        bubbleRight.addView(bubbleValue, rightTextParams());
        bubbleSwitch = makeSwitch(sp.getBoolean(AutoScrollService.KEY_BUBBLE, true));
        bubbleSwitch.setContentDescription("悬浮球");
        bubbleRight.addView(bubbleSwitch, leftMargin(dp(8)));
        View bubbleRow = row("悬浮球", "BUBBLE", bubbleRight, null);
        bubbleSwitch.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean(AutoScrollService.KEY_BUBBLE, checked).apply();
            refreshRows();
            Toast.makeText(this, checked ? "悬浮球已显示" : "悬浮球已隐藏", Toast.LENGTH_SHORT).show();
        });
        addRow(box, bubbleRow);

        LinearLayout accessRight = new LinearLayout(this);
        accessRight.setOrientation(LinearLayout.HORIZONTAL);
        accessRight.setGravity(Gravity.CENTER_VERTICAL);
        accessDot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(INK);
        accessDot.setBackground(dotBg);
        accessRight.addView(accessDot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        accessValue = monoText("已连接", 13, INK);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        avLp.leftMargin = dp(8);
        accessRight.addView(accessValue, avLp);
        TextView accessChevron = chevron();
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acLp.leftMargin = dp(8);
        accessRight.addView(accessChevron, acLp);
        addRow(box, row("无障碍服务", "ACCESS", accessRight, v -> openAccessibilitySettings()));

        logValue = monoText("暂无记录", 12, FAINT);
        LinearLayout logRight = new LinearLayout(this);
        logRight.setOrientation(LinearLayout.HORIZONTAL);
        logRight.setGravity(Gravity.CENTER_VERTICAL);
        logRight.addView(logValue, rightTextParams());
        TextView logChevron = chevron();
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcLp.leftMargin = dp(8);
        logRight.addView(logChevron, lcLp);
        addRow(box, row("运行日志", "LOG", logRight, v -> showLogDialog()));

        return box;
    }

    private View row(String label, String sub, View right, View.OnClickListener click) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        wrap.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout contentRow = new LinearLayout(this);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER_VERTICAL);
        contentRow.setMinimumHeight(dp(63));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(15);
        name.setTextColor(INK);
        name.setLetterSpacing(0.02f);
        left.addView(name);
        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextSize(12);
        subView.setTypeface(monoRegular);
        subView.setTextColor(FAINT);
        subView.setLetterSpacing(0.04f);
        subView.setPadding(0, dp(4), 0, 0);
        left.addView(subView);
        contentRow.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        contentRow.addView(right);
        wrap.addView(contentRow);

        if (click != null) {
            contentRow.setOnClickListener(click);
        }
        return wrap;
    }

    private void addRow(LinearLayout parent, View rowView) {
        parent.addView(rowView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView chevron() {
        TextView t = new TextView(this);
        t.setText("›");
        t.setTextSize(19);
        t.setTypeface(monoRegular);
        t.setTextColor(FAINT);
        return t;
    }

    private TextView monoText(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTypeface(monoRegular);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams rightTextParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Switch makeSwitch(boolean checked) {
        Switch s = new Switch(this);
        s.setShowText(false);
        s.setChecked(checked);
        s.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{INK, 0xFF8A8A8A}));
        s.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xFF3A3A3A, 0xFF222222}));
        return s;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(BG);

        View divider = new View(this);
        divider.setBackgroundColor(0xFF202020);
        footer.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(22), 0, dp(22), 0);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        footerState = new TextView(this);
        footerState.setTextSize(12);
        footerState.setTypeface(monoRegular);
        footerState.setTextColor(MUTED);
        footerState.setLetterSpacing(0.08f);
        left.addView(footerState);

        footerElapsed = new TextView(this);
        footerElapsed.setTextSize(15);
        footerElapsed.setTypeface(monoRegular);
        footerElapsed.setTextColor(INK);
        footerElapsed.setLetterSpacing(0.04f);
        footerElapsed.setPadding(0, dp(3), 0, 0);
        left.addView(footerElapsed);
        bar.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> onShutter());
        bar.addView(shutter, new LinearLayout.LayoutParams(dp(48), dp(48)));

        footer.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(73)));
        return footer;
    }

    private void onShutter() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }
        if (!AutoScrollService.isConnected()) {
            Toast.makeText(this, "服务正在连接，请回到红果短剧再点悬浮球", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AutoScrollService.isRunningNow()) {
            AutoScrollService.pauseFromUi();
            Toast.makeText(this, "自动上滑已暂停", Toast.LENGTH_SHORT).show();
            return;
        }
        String target = "com.phoenix.read";
        if (!isInstalled(target)) {
            Toast.makeText(this, "请先打开红果短剧，再点悬浮球开始", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AutoScrollService.startFor(target, labelOf(target))) {
            Toast.makeText(this, "自动上滑已开启（红果短剧）", Toast.LENGTH_SHORT).show();
        }
    }

    private void showModeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(8));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView title = new TextView(this);
        title.setText("模式");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("MODE / 触发方式");
        sub.setTextSize(12);
        sub.setTypeface(monoRegular);
        sub.setTextColor(FAINT);
        sub.setLetterSpacing(0.1f);
        sub.setPadding(0, dp(6), 0, dp(16));
        box.addView(sub);

        String current = sp.getString(AutoScrollService.KEY_MODE, AutoScrollService.MODE_SMART);
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout segs = new LinearLayout(this);
        segs.setOrientation(LinearLayout.HORIZONTAL);
        segs.setClipToPadding(false);

        View smart = segment("智能识别", "片尾触发", AutoScrollService.MODE_SMART.equals(current));
        View timer = segment("定时上滑", intervalSec + "″ 一次", AutoScrollService.MODE_TIMER.equals(current));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                0, dp(62), 1f);
        segLp.rightMargin = dp(5);
        segs.addView(smart, segLp);
        LinearLayout.LayoutParams segLp2 = new LinearLayout.LayoutParams(
                0, dp(62), 1f);
        segLp2.leftMargin = dp(5);
        segs.addView(timer, segLp2);
        box.addView(segs);

        TextView foot = new TextView(this);
        foot.setText("智能识别只在片尾或广告结束时上滑；定时按间隔上滑。");
        foot.setTextSize(15);
        foot.setTextColor(MUTED);
        foot.setLineSpacing(dp(3), 1f);
        foot.setPadding(0, dp(16), 0, dp(8));
        box.addView(foot);

        smart.setOnClickListener(v -> {
            setMode(AutoScrollService.MODE_SMART);
            if (holder[0] != null) holder[0].dismiss();
        });
        timer.setOnClickListener(v -> {
            setMode(AutoScrollService.MODE_TIMER);
            if (holder[0] != null) holder[0].dismiss();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("取消", null)
                .create();
        holder[0] = dialog;
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xFF0A0A0A));
    }

    private View segment(String label, String sub, boolean selected) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F0F0F);
        bg.setStroke(dp(1), selected ? YELLOW : 0xFF262626);
        box.setBackground(bg);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(selected ? YELLOW : 0xFFD5D5D5);
        box.addView(title);

        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextSize(12);
        subView.setTypeface(monoRegular);
        subView.setTextColor(FAINT);
        subView.setPadding(0, dp(4), 0, 0);
        box.addView(subView);
        return box;
    }

    private void setMode(String mode) {
        sp.edit().putString(AutoScrollService.KEY_MODE, mode).apply();
        refreshRows();
    }

    private void showAdLevelDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(8));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView title = new TextView(this);
        title.setText("广告力度");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("AD DEPTH / 判定激进程度");
        sub.setTextSize(12);
        sub.setTypeface(monoRegular);
        sub.setTextColor(FAINT);
        sub.setLetterSpacing(0.1f);
        sub.setPadding(0, dp(6), 0, dp(16));
        box.addView(sub);

        String current = sp.getString(AutoScrollService.KEY_AD_LEVEL, AutoScrollService.AD_LEVEL_STD);
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout segs = new LinearLayout(this);
        segs.setOrientation(LinearLayout.HORIZONTAL);
        segs.setClipToPadding(false);

        View std = segment("标准", "文字证据", AutoScrollService.AD_LEVEL_STD.equals(current));
        View strong = segment("强力", "空白页兜底", AutoScrollService.AD_LEVEL_STRONG.equals(current));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                0, dp(62), 1f);
        segLp.rightMargin = dp(5);
        segs.addView(std, segLp);
        LinearLayout.LayoutParams segLp2 = new LinearLayout.LayoutParams(
                0, dp(62), 1f);
        segLp2.leftMargin = dp(5);
        segs.addView(strong, segLp2);
        box.addView(segs);

        TextView foot = new TextView(this);
        foot.setText("标准：认倒计时、广告角标、广告 SDK 控件、直播/游戏/推广卡，读完秒立刻上滑。\n"
                + "强力：再加「整页读不到文字的广告画面」兜底，5 分钟最多兜底 4 次；"
                + "无文字的正片有可能被误滑，介意就别开。");
        foot.setTextSize(15);
        foot.setTextColor(MUTED);
        foot.setLineSpacing(dp(3), 1f);
        foot.setPadding(0, dp(16), 0, dp(8));
        box.addView(foot);

        std.setOnClickListener(v -> {
            setAdLevel(AutoScrollService.AD_LEVEL_STD);
            if (holder[0] != null) holder[0].dismiss();
        });
        strong.setOnClickListener(v -> {
            setAdLevel(AutoScrollService.AD_LEVEL_STRONG);
            if (holder[0] != null) holder[0].dismiss();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("取消", null)
                .create();
        holder[0] = dialog;
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xFF0A0A0A));
    }

    private void setAdLevel(String level) {
        sp.edit().putString(AutoScrollService.KEY_AD_LEVEL, level).apply();
        refreshRows();
    }

    private void showIntervalDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(4));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView title = new TextView(this);
        title.setText("间隔");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("INTERVAL / 5 – 600 秒");
        sub.setTextSize(12);
        sub.setTypeface(monoRegular);
        sub.setTextColor(FAINT);
        sub.setLetterSpacing(0.1f);
        sub.setPadding(0, dp(6), 0, dp(12));
        box.addView(sub);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(intervalSec));
        input.setTextSize(22);
        input.setTypeface(monoRegular);
        input.setTextColor(INK);
        input.setHintTextColor(0xFF6A6A6A);
        input.setBackgroundTintList(ColorStateList.valueOf(YELLOW));
        input.setSelectAllOnFocus(true);
        box.addView(input);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String v = s.toString();
                if (!v.isEmpty() && Integer.parseInt(v) > 600) {
                    s.replace(0, s.length(), "600");
                }
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, which) -> {
                    int value = intervalSec;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    value = Math.max(5, Math.min(600, value));
                    intervalSec = value;
                    heroNumber.setText(String.valueOf(value));
                    ruler.setInterval(value);
                    sp.edit().putInt(AutoScrollService.KEY_INTERVAL, value).apply();
                })
                .create();
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xFF0A0A0A));
    }

    private void showLogDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(4));
        box.setBackgroundColor(0xFF0A0A0A);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("运行日志");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);
        headText.addView(title);
        TextView sub = new TextView(this);
        sub.setText("SCAN.LOG / 本机记录");
        sub.setTextSize(12);
        sub.setTypeface(monoRegular);
        sub.setTextColor(FAINT);
        sub.setLetterSpacing(0.1f);
        sub.setPadding(0, dp(5), 0, 0);
        headText.addView(sub);
        head.addView(headText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextSize(24);
        close.setTextColor(0xFF9A9A9A);
        close.setPadding(dp(8), dp(2), dp(8), dp(8));
        head.addView(close);
        box.addView(head);

        LinearLayout debugRow = new LinearLayout(this);
        debugRow.setOrientation(LinearLayout.HORIZONTAL);
        debugRow.setGravity(Gravity.CENTER_VERTICAL);
        debugRow.setPadding(0, dp(14), 0, dp(2));
        TextView debugLabel = new TextView(this);
        debugLabel.setText("记录界面文字");
        debugLabel.setTextSize(15);
        debugLabel.setTextColor(INK);
        debugRow.addView(debugLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch debugSwitch = makeSwitch(sp.getBoolean(AutoScrollService.KEY_DEBUG, false));
        debugSwitch.setContentDescription("记录界面文字");
        debugSwitch.setOnCheckedChangeListener((b, checked) ->
                sp.edit().putBoolean(AutoScrollService.KEY_DEBUG, checked).apply());
        debugRow.addView(debugSwitch);
        box.addView(debugRow);

        TextView debugHint = new TextView(this);
        debugHint.setText("开启后每秒记录红果界面里能读到的文字，广告或片尾识别不准时用。");
        debugHint.setTextSize(15);
        debugHint.setTextColor(MUTED);
        debugHint.setLineSpacing(dp(3), 1f);
        debugHint.setPadding(0, dp(2), 0, dp(10));
        box.addView(debugHint);

        TextView keywords = new TextView(this);
        keywords.setText("识别词  " + AutoScrollService.keywordSummary());
        keywords.setTextSize(12);
        keywords.setTypeface(monoRegular);
        keywords.setTextColor(FAINT);
        keywords.setLineSpacing(dp(4), 1f);
        keywords.setPadding(0, 0, 0, dp(10));
        box.addView(keywords);

        FrameLayout logFrame = new FrameLayout(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(0xFF0F0F0F);
        logBg.setStroke(dp(1), 0xFF1F1F1F);
        logFrame.setBackground(logBg);

        logScroll = new ScrollView(this);
        logScroll.setPadding(dp(12), dp(12), dp(12), dp(12));
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTypeface(monoRegular);
        logText.setTextColor(0xFFBDBDBD);
        logText.setLineSpacing(dp(3), 1f);
        logScroll.addView(logText);
        logFrame.addView(logScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(logFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);
        Button clear = dialogButton("清空", false);
        Button copy = dialogButton("复制", false);
        Button refresh = dialogButton("刷新", true);
        buttons.addView(clear, new LinearLayout.LayoutParams(0, dp(44), 1f));
        buttons.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1f));
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1.3f));
        box.addView(buttons);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("关闭", null)
                .create();
        logDialog = dialog;
        logRefresh = new Runnable() {
            @Override
            public void run() {
                if (logText == null) return;
                refreshLogNow();
                handler.postDelayed(this, 1500);
            }
        };
        refreshLogNow();
        clear.setOnClickListener(v -> {
            new File(getFilesDir(), AutoScrollService.LOG_FILE).delete();
            refreshLogNow();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("log", readLog()));
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
            }
        });
        refresh.setOnClickListener(v -> refreshLogNow());
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(logRefresh);
            logRefresh = null;
            logDialog = null;
            logText = null;
            logScroll = null;
            refreshRows();
        });
        dialog.show();
        logScroll.post(() -> {
            if (logScroll != null) logScroll.fullScroll(View.FOCUS_DOWN);
        });
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xFF0A0A0A));
        handler.post(logRefresh);
    }

    private Button dialogButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.BLACK : 0xFFD5D5D5);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? YELLOW : 0xFF0F0F0F);
        if (!primary) bg.setStroke(dp(1), 0xFF2F2F2F);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void refreshLogNow() {
        if (logText == null) return;
        boolean follow = logScroll == null
                || logScroll.getScrollY() + logScroll.getHeight() >= logText.getHeight() - dp(24);
        logText.setText(readLog());
        if (follow && logScroll != null) {
            logScroll.post(() -> {
                if (logScroll != null) logScroll.fullScroll(View.FOCUS_DOWN);
            });
        }
    }

    private String readLog() {
        File f = new File(getFilesDir(), AutoScrollService.LOG_FILE);
        if (!f.exists()) return "（暂无记录）";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = in.read(data);
            String content = new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            if (content.length() > 12000) {
                content = content.substring(content.length() - 12000);
            }
            return content.isEmpty() ? "（暂无记录）" : content;
        } catch (Exception e) {
            return "（读取失败）";
        }
    }

    private String lastLogTime() {
        String content = readLog();
        String[] lines = content.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.length() >= 11 && line.charAt(2) == '-' && line.charAt(5) == ' ') {
                return line.substring(6, 11);
            }
        }
        return null;
    }

    private void refreshRows() {
        String mode = sp.getString(AutoScrollService.KEY_MODE, AutoScrollService.MODE_SMART);
        boolean smart = AutoScrollService.MODE_SMART.equals(mode);
        if (heroMode != null) heroMode.setText(smart ? "SMART · 智能" : "TIMER · 定时");
        if (modeValue != null) modeValue.setText(smart ? "智能识别" : "定时上滑");
        if (adSwitch != null) adSwitch.setChecked(sp.getBoolean(AutoScrollService.KEY_AD, true));
        if (adValue != null) adValue.setText(adSwitch != null && adSwitch.isChecked() ? "自动跳过" : "不处理");
        if (adLevelValue != null) {
            String level = sp.getString(AutoScrollService.KEY_AD_LEVEL, AutoScrollService.AD_LEVEL_STD);
            adLevelValue.setText(AutoScrollService.AD_LEVEL_STRONG.equals(level) ? "强力" : "标准");
        }
        if (bubbleSwitch != null) bubbleSwitch.setChecked(sp.getBoolean(AutoScrollService.KEY_BUBBLE, true));
        if (bubbleValue != null) bubbleValue.setText(bubbleSwitch != null && bubbleSwitch.isChecked() ? "开" : "关");
        boolean enabled = isAccessibilityEnabled();
        if (accessValue != null) accessValue.setText(enabled ? "已连接" : "未开启");
        if (accessDot != null && accessDot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) accessDot.getBackground()).setColor(enabled ? INK : 0xFF6A6A6A);
        }
        if (logValue != null) {
            String time = lastLogTime();
            logValue.setText(time == null ? "暂无记录" : "上次 " + time);
        }
    }

    private void refreshRunning() {
        boolean connected = isAccessibilityEnabled() && AutoScrollService.isConnected();
        boolean running = connected && AutoScrollService.isRunningNow();
        if (footerState != null) {
            footerState.setText(!connected ? "OFFLINE" : running ? "RUNNING" : "STANDBY");
        }
        if (footerElapsed != null) {
            footerElapsed.setText(running ? elapsed() : "00:00:00");
        }
        if (shutter != null) shutter.setActive(running);
        if (accessValue != null) {
            boolean enabled = isAccessibilityEnabled();
            accessValue.setText(enabled ? "已连接" : "未开启");
        }
    }

    private String elapsed() {
        long started = sp.getLong(AutoScrollService.KEY_RUN_STARTED, 0);
        if (started <= 0) return "00:00:00";
        long seconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
        return String.format(Locale.CHINA, "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String versionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "1.9" : info.versionName;
        } catch (Exception e) {
            return "1.9";
        }
    }

    private String labelOf(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String me = getPackageName() + "/" + AutoScrollService.class.getName();
        return enabled.contains(me);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "在设置里打开「短剧自动上滑」", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sp.registerOnSharedPreferenceChangeListener(prefListener);
        intervalSec = sp.getInt(AutoScrollService.KEY_INTERVAL, 30);
        heroNumber.setText(String.valueOf(intervalSec));
        ruler.setInterval(intervalSec);
        refreshRows();
        refreshRunning();
        handler.removeCallbacks(uiTick);
        handler.post(uiTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(uiTick);
        sp.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (logDialog != null) logDialog.dismiss();
    }

    private Typeface loadMono(boolean bold) {
        try {
            return Typeface.createFromAsset(getAssets(),
                    bold ? "fonts/JetBrainsMono-Bold.ttf" : "fonts/JetBrainsMono-Regular.ttf");
        } catch (Exception e) {
            return Typeface.MONOSPACE;
        }
    }

    private LinearLayout.LayoutParams topMargin(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px;
        return lp;
    }

    private LinearLayout.LayoutParams leftMargin(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px;
        return lp;
    }

    private FrameLayout.LayoutParams bottomParams(int heightPx) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private class ShutterButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean active;

        ShutterButton(Context context) {
            super(context);
            setContentDescription("暂停自动上滑");
        }

        void setActive(boolean active) {
            if (this.active == active) return;
            this.active = active;
            setContentDescription(active ? "暂停自动上滑" : "开始自动上滑");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - dpf(2);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(2));
            paint.setColor(active ? YELLOW : 0xFF4A4A4A);
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStyle(Paint.Style.FILL);
            int color = active ? YELLOW : 0xFF8A8A8A;
            paint.setColor(color);

            if (active) {
                canvas.drawRect(cx - dpf(5.5f), cy - dpf(6.5f), cx - dpf(3), cy + dpf(6.5f), paint);
                canvas.drawRect(cx + dpf(3), cy - dpf(6.5f), cx + dpf(5.5f), cy + dpf(6.5f), paint);
            } else {
                path.reset();
                path.moveTo(cx - dpf(4), cy - dpf(7));
                path.lineTo(cx + dpf(7), cy);
                path.lineTo(cx - dpf(4), cy + dpf(7));
                path.close();
                canvas.drawPath(path, paint);
            }
        }

        private float dpf(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }

    private class CornerFrame extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CornerFrame(Context context) {
            super(context);
            paint.setColor(YELLOW);
            paint.setAlpha(120);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float len = dp(10);
            float w = getWidth();
            float h = getHeight();
            canvas.drawLine(0, 0, len, 0, paint);
            canvas.drawLine(0, 0, 0, len, paint);
            canvas.drawLine(w, 0, w - len, 0, paint);
            canvas.drawLine(w, 0, w, len, paint);
            canvas.drawLine(0, h, len, h, paint);
            canvas.drawLine(0, h, 0, h - len, paint);
            canvas.drawLine(w, h, w - len, h, paint);
            canvas.drawLine(w, h, w, h - len, paint);
        }
    }

}
