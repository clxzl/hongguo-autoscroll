package com.hg.gatetest;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class GateActivity extends Activity {

    private static final String TAG = "GateTest";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView epView;
    private TextView metaView;
    private TextView hintView;
    private TextView extraView;
    private int touchDowns;
    private int touchUps;
    private int scenario;
    private int episode = 8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        epView = new TextView(this);
        epView.setTextColor(Color.WHITE);
        epView.setTextSize(30);
        metaView = new TextView(this);
        metaView.setTextColor(Color.GRAY);
        metaView.setTextSize(20);
        hintView = new TextView(this);
        hintView.setTextColor(Color.WHITE);
        hintView.setTextSize(22);
        hintView.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 60;
        root.addView(epView);
        root.addView(metaView, lp);
        root.addView(hintView, lp);
        extraView = new TextView(this);
        extraView.setTextColor(Color.WHITE);
        extraView.setTextSize(22);
        extraView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(extraView, lp);
        setContentView(root);
        showPlaying();

        IntentFilter filter = new IntentFilter("com.hg.gatetest.RUN");
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        Log.i(TAG, "ready");
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            run(intent.getIntExtra("id", 0));
        }
    };

    private void run(int id) {
        handler.removeCallbacksAndMessages(null);
        touchDowns = 0;
        touchUps = 0;
        scenario = id;
        extraView.setText("");
        hintView.setVisibility(View.VISIBLE);
        Log.i(TAG, "--- scenario " + id + " start; downs=" + touchDowns);
        switch (id) {
            case 1:
                s1();
                break;
            case 2:
                s2();
                break;
            case 3:
                s3();
                break;
            case 4:
                s4();
                break;
            case 5:
                s5();
                break;
            case 6:
                s6();
                break;
            case 7:
                s7();
                break;
            case 8:
                s8();
                break;
            case 9:
                s9();
                break;
            case 10:
                s10();
                break;
            case 12:
                s12();
                break;
            case 13:
                s13();
                break;
            default:
                break;
        }
    }

    /** 正常片尾：提示 → 滑走 → 播放 → 提示 → 滑走 */
    private void s1() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s1 end-prompt");
            showPrompt();
            handler.postDelayed(this::showPlaying, 20000);
            handler.postDelayed(() -> {
                Log.i(TAG, "s1 second end-prompt");
                showPrompt();
            }, 22000);
            handler.postDelayed(this::done, 32000);
        }, 3000);
    }

    /** 中途插播的倒计时广告：倒计时结束后恢复播放，不应滑动 */
    private void s2() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s2 gate");
            countdown(5, "第8集 | 3.0x");
        }, 3000);
        handler.postDelayed(() -> {
            Log.i(TAG, "s2 resume");
            showPlaying();
        }, 8800);
        handler.postDelayed(this::done, 18000);
    }

    /** 无倒计时广告，且无障碍树里没有「广告」字样（线上报障的场景） */
    private void s3() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s3 ad page: prompt only, no ad text");
            showPrompt();
        }, 3000);
        handler.postDelayed(this::done, 18000);
    }

    /** 无倒计时广告，树里有「广告」字样 */
    private void s4() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s4 ad page with ad marker");
            epView.setText("第8集 | 3.0x");
            metaView.setText("广告");
            hintView.setText("上滑继续观看短剧");
        }, 3000);
        handler.postDelayed(this::done, 18000);
    }

    /** 广告页不响应滑动：应当按间隔重试，而不是卡死 */
    private void s5() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s5 stuck ad page");
            showPrompt();
        }, 3000);
        handler.postDelayed(() -> {
            Log.i(TAG, "s5 back to playing");
            showPlaying();
        }, 26000);
        handler.postDelayed(this::done, 34000);
    }

    /** 片尾带倒计时的广告：倒计时结束后出现片尾提示，应当滑走 */
    private void s6() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s6 end gate");
            countdown(5, "第8集 | 3.0x");
        }, 3000);
        handler.postDelayed(() -> {
            Log.i(TAG, "s6 end prompt after countdown");
            showPrompt();
        }, 8600);
        handler.postDelayed(this::done, 20000);
    }

    /** 提示节点断续出现（每 400ms 一闪），模拟真机广告页无障碍树时有时无 */
    private void s7() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s7 flickering prompt 400ms");
            flicker(400, 400);
        }, 3000);
        handler.postDelayed(this::done, 18000);
    }

    /** 提示只出现 250ms、消失 1000ms，更稀疏的抖动 */
    private void s8() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s8 flickering prompt 250/1000");
            flicker(250, 1000);
        }, 3000);
        handler.postDelayed(this::done, 18000);
    }

    /** 播放中常驻「下一集」控件（红果播放器/选集面板里有）：不应触发上滑 */
    private void s9() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s9 permanent next-episode button visible");
            metaView.setText("全屏观看 | 发弹幕 | 下一集");
        }, 3000);
        handler.postDelayed(this::done, 15000);
    }

    /** 提示节点提前挂进树里但不可见（真机误滑根因）：8 秒后才真正显示，此前不应滑 */
    private void s10() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s10 hidden prompt present, becomes visible at +8s");
            epView.setText("第9集 | 3.0x");
            metaView.setText("全屏观看 | 发弹幕");
            hintView.setText("上滑继续观看短剧");
            hintView.setVisibility(View.INVISIBLE);
            handler.postDelayed(() -> {
                Log.i(TAG, "s10 prompt becomes visible");
                hintView.setVisibility(View.VISIBLE);
            }, 8000);
        }, 3000);
        handler.postDelayed(this::done, 20000);
    }

    /** 倒计时文案与片尾提示同时存在：只认提示，应立即滑走 */
    private void s12() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s12 gate text + visible prompt");
            hintView.setText("9秒后可继续上滑观看短剧");
            extraView.setText("上滑继续观看短剧");
        }, 3000);
        handler.postDelayed(this::done, 18000);
    }

    /** 广告页只有「广告」「跳过广告」文案、没有片尾提示：8 秒后盲滑兜底 */
    private void s13() {
        showPlaying();
        handler.postDelayed(() -> {
            Log.i(TAG, "s13 ad text + skip text, no prompt");
            epView.setText("第9集 | 3.0x");
            metaView.setText("广告");
            hintView.setText("跳过广告");
        }, 3000);
        handler.postDelayed(this::done, 22000);
    }

    private void flicker(final int onMs, final int offMs) {
        if (scenario != 7 && scenario != 8) return;
        epView.setText("第9集 | 3.0x");
        metaView.setText("全屏观看 | 发弹幕");
        hintView.setText("上滑继续观看短剧");
        handler.postDelayed(() -> {
            if (scenario != 7 && scenario != 8) return;
            hintView.setText("");
            handler.postDelayed(() -> flicker(onMs, offMs), offMs);
        }, onMs);
    }

    private void countdown(final int seconds, final String label) {
        epView.setText(label);
        metaView.setText("");
        tickDown(seconds);
    }

    private void tickDown(final int left) {
        if (left <= 0) return;
        hintView.setText(left + "秒后可继续上滑观看短剧");
        handler.postDelayed(() -> tickDown(left - 1), 1000);
    }

    private void showPlaying() {
        epView.setText("第" + episode + "集 | 3.0x");
        metaView.setText("全屏观看 | 发弹幕");
        hintView.setText("");
        extraView.setText("");
    }

    private void showPrompt() {
        epView.setText("第" + episode + "集 | 3.0x");
        metaView.setText("全屏观看 | 发弹幕");
        hintView.setText("上滑继续观看短剧");
    }

    private void done() {
        Log.i(TAG, "scenario " + scenario + " done: downs=" + touchDowns + " ups=" + touchUps);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchDowns++;
            Log.i(TAG, "touch down #" + touchDowns);
        } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            touchUps++;
            Log.i(TAG, "touch up #" + touchUps);
            // 1/3/4 模拟滑动被页面接受：换到下一条视频
            if (scenario == 1 || scenario == 3 || scenario == 4 || scenario == 12) {
                episode++;
                Log.i(TAG, "page accepted swipe -> 第" + episode + "集");
                showPlaying();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
