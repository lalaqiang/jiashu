package com.example.gamespeed;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * 悬浮窗调速服务
 *
 * 两个独立悬浮窗：
 *  1. 悬浮球（小圆点）：常驻，点击展开/收起控制面板
 *  2. 控制面板：包含 SeekBar + 加速/减速/重置按钮
 *
 * 特性：
 *  - 悬浮球可拖动（拖到屏幕边缘自动贴边）
 *  - 面板展开时跟随悬浮球位置
 *  - 调整倍率立即写入 SharedPreferences，Hook 进程 1 秒内感知
 */
public class FloatingService extends Service {

    private static final String PREF_NAME = "speed";
    private static final String KEY_SPEED = "speed";

    private WindowManager wm;
    private View ballView;          // 悬浮球
    private View panelView;         // 控制面板
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams panelParams;

    private TextView tvSpeed;
    private SeekBar seekBar;
    private SharedPreferences sp;
    private boolean panelShown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        sp = getSharedPreferences(PREF_NAME, MODE_WORLD_READABLE);
        try {
            sp.getFloat(KEY_SPEED, 1.0f);
        } catch (Throwable e) {
            sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        }

        createBall();
        createPanel();
        showBall();
    }

    /** 悬浮窗 LayoutParams 类型 */
    private int getWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_PHONE;
        }
    }

    /** 创建悬浮球 */
    private void createBall() {
        ballView = new View(this);
        int size = (int) (40 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        ballView.setLayoutParams(lp);

        // 圆形蓝色背景
        ballView.setBackgroundDrawable(
                getResources().getDrawable(android.R.drawable.ic_menu_compass));

        ballParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = 0;
        ballParams.y = 200;

        // 拖动 + 点击切换面板
        ballView.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = ballParams.x;
                        initY = ballParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchX;
                        float dy = e.getRawY() - touchY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                        ballParams.x = initX + (int) dx;
                        ballParams.y = initY + (int) dy;
                        wm.updateViewLayout(ballView, ballParams);
                        // 面板跟随
                        if (panelShown) updatePanelPosition();
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!moved) togglePanel();
                        return true;
                }
                return false;
            }
        });
    }

    /** 创建控制面板 */
    private void createPanel() {
        panelView = LayoutInflater.from(this).inflate(R.layout.floating_panel, null);

        tvSpeed = panelView.findViewById(R.id.tvSpeed);
        seekBar = panelView.findViewById(R.id.seekBar);
        Button btnPlus = panelView.findViewById(R.id.btnPlus);
        Button btnMinus = panelView.findViewById(R.id.btnMinus);
        Button btnReset = panelView.findViewById(R.id.btnReset);
        Button btnClose = panelView.findViewById(R.id.btnClose);

        float saved = sp.getFloat(KEY_SPEED, 1.0f);
        seekBar.setMax(200);
        seekBar.setProgress((int) (saved * 10));
        updateSpeedText(saved);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float speed = Math.max(0.1f, progress / 10.0f);
                updateSpeedText(speed);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                saveSpeed(bar.getProgress() / 10.0f);
            }
        });

        btnPlus.setOnClickListener(v -> {
            float cur = seekBar.getProgress() / 10.0f;
            cur = Math.min(20.0f, cur + 0.5f);
            seekBar.setProgress((int) (cur * 10));
            updateSpeedText(cur);
            saveSpeed(cur);
        });

        btnMinus.setOnClickListener(v -> {
            float cur = seekBar.getProgress() / 10.0f;
            cur = Math.max(0.1f, cur - 0.5f);
            seekBar.setProgress((int) (cur * 10));
            updateSpeedText(cur);
            saveSpeed(cur);
        });

        btnReset.setOnClickListener(v -> {
            seekBar.setProgress(10);
            updateSpeedText(1.0f);
            saveSpeed(1.0f);
        });

        btnClose.setOnClickListener(v -> {
            hidePanel();
        });

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
    }

    private void showBall() {
        try {
            wm.addView(ballView, ballParams);
        } catch (Throwable e) { /* 权限不足等 */ }
    }

    private void togglePanel() {
        if (panelShown) hidePanel();
        else showPanel();
    }

    private void showPanel() {
        if (panelShown) return;
        try {
            updatePanelPosition();
            wm.addView(panelView, panelParams);
            panelShown = true;
        } catch (Throwable e) { /* ignored */ }
    }

    private void hidePanel() {
        if (!panelShown) return;
        try {
            wm.removeView(panelView);
        } catch (Throwable e) { /* ignored */ }
        panelShown = false;
    }

    /** 面板位置贴着悬浮球右侧（不够空间则放左侧） */
    private void updatePanelPosition() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int panelWidth = (int) (220 * getResources().getDisplayMetrics().density);
        int x = ballParams.x + 60;
        if (x + panelWidth > screenWidth) {
            x = ballParams.x - panelWidth - 20;
            if (x < 0) x = 0;
        }
        panelParams.x = x;
        panelParams.y = ballParams.y;
        try {
            if (panelShown) wm.updateViewLayout(panelView, panelParams);
        } catch (Throwable e) { /* ignored */ }
    }

    private void updateSpeedText(float speed) {
        if (tvSpeed != null) tvSpeed.setText(String.format("%.1f x", speed));
    }

    private void saveSpeed(float speed) {
        if (speed < 0.1f) speed = 0.1f;
        sp.edit().putFloat(KEY_SPEED, speed).apply();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hidePanel();
        try { wm.removeView(ballView); } catch (Throwable e) { /* ignored */ }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
