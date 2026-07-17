package com.example.gamespeed;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * 悬浮窗调速服务
 *
 * 三个独立窗口：
 *  1. 悬浮球（圆形渐变）：常驻，点击展开/收起控制面板；长按关闭服务
 *  2. 控制面板：SeekBar + 加速/减速/重置/录制按钮
 *  3. 录制覆盖层：全屏捕获触摸，把屏幕点击坐标自动写入 ClickConfig
 *
 * 特性：
 *  - 悬浮球可拖动（拖到屏幕边缘自动贴边）
 *  - 面板展开时跟随悬浮球位置
 *  - 调整倍率立即写入 SharedPreferences + ContentProvider（Hook 进程 1 秒内感知）
 *  - 录制时点击屏幕任意位置（除「停止录制」按钮外）即添加点位
 */
public class FloatingService extends Service {

    private static final String PREF_NAME = "speed";
    private static final String KEY_SPEED = "speed";
    private static final int DEFAULT_DURATION_MS = 50; // 录制默认按下时长

    private WindowManager wm;
    private View ballView;          // 悬浮球
    private View panelView;         // 控制面板
    private View recordView;        // 录制覆盖层
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams recordParams;

    private TextView tvSpeed;
    private SeekBar seekBar;
    private TextView tvRecordCount;
    private Button btnStopRecord;
    private SharedPreferences sp;
    private boolean panelShown = false;
    private boolean recording = false;
    private int recordCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        // 统一用 MODE_PRIVATE，跨进程同步交给 SpeedProvider
        sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

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

    /** 创建悬浮球（圆形渐变背景） */
    private void createBall() {
        int size = (int) (44 * getResources().getDisplayMetrics().density);
        ballView = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        ballView.setLayoutParams(lp);
        ballView.setBackgroundResource(R.drawable.floating_ball);

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

        ballView.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private boolean moved;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = ballParams.x;
                        initY = ballParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        downTime = System.currentTimeMillis();
                        v.setAlpha(0.85f);
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
                        v.setAlpha(1.0f);
                        // 长按 = 关闭服务
                        if (System.currentTimeMillis() - downTime > 600 && !moved) {
                            stopSelf();
                            return true;
                        }
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
        Button btnRecord = panelView.findViewById(R.id.btnRecord);

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

        btnClose.setOnClickListener(v -> hidePanel());

        btnRecord.setOnClickListener(v -> {
            hidePanel();
            startRecording();
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
        int panelWidth = (int) (240 * getResources().getDisplayMetrics().density);
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
        SpeedProvider.setSpeed(this, speed); // 跨进程通知 Hook 进程
    }

    // ============ 录制功能：屏幕点选生成点位 ============
    private void startRecording() {
        if (recording) return;
        if (recordView == null) {
            recordView = LayoutInflater.from(this).inflate(R.layout.record_overlay, null);
            btnStopRecord = recordView.findViewById(R.id.btnStopRecord);
            tvRecordCount = recordView.findViewById(R.id.tvRecordCount);
            btnStopRecord.setOnClickListener(v -> stopRecording());

            recordParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    getWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            recordParams.gravity = Gravity.TOP | Gravity.START;
            recordParams.x = 0;
            recordParams.y = 0;

            // 覆盖层触摸监听：ACTION_DOWN 时添加点位；坐标落在「停止录制」按钮内则放行给按钮
            recordView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        // 判断是否落在「停止录制」按钮范围
                        if (btnStopRecord != null && btnStopRecord.getWidth() > 0) {
                            int[] loc = new int[2];
                            btnStopRecord.getLocationOnScreen(loc);
                            if (e.getRawX() >= loc[0]
                                    && e.getRawX() <= loc[0] + btnStopRecord.getWidth()
                                    && e.getRawY() >= loc[1]
                                    && e.getRawY() <= loc[1] + btnStopRecord.getHeight()) {
                                return false; // 让按钮接收
                            }
                        }
                        int x = (int) e.getRawX();
                        int y = (int) e.getRawY();
                        ClickConfig.addPoint(FloatingService.this, x, y, DEFAULT_DURATION_MS);
                        recordCount++;
                        if (tvRecordCount != null) {
                            tvRecordCount.setText(getString(R.string.record_count, recordCount));
                        }
                        Toast.makeText(FloatingService.this,
                                "(" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                }
            });
        }

        try {
            wm.addView(recordView, recordParams);
            recording = true;
            recordCount = 0;
            if (tvRecordCount != null) {
                tvRecordCount.setText(getString(R.string.record_count, 0));
            }
            Toast.makeText(this, R.string.record_started, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, "录制启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (!recording) return;
        try { wm.removeView(recordView); } catch (Throwable e) { /* ignored */ }
        recording = false;
        Toast.makeText(this,
                getString(R.string.record_stopped, recordCount),
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
        hidePanel();
        try { wm.removeView(ballView); } catch (Throwable e) { /* ignored */ }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
