package com.example.gamespeed;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 连点器无障碍服务
 *
 * 工作原理：
 *  - 用 AccessibilityService.dispatchGesture() 实现模拟点击（API 24+，免 root）
 *  - 后台线程按间隔循环点击所有配置的点位
 *  - 通过 Intent action 控制启动/停止/更新配置
 *
 * 配置存储：
 *  - 点位列表 + 间隔写在 ClickConfig 静态字段里（同进程访问）
 *  - 启动状态持久化在 SharedPreferences，重启服务可恢复
 */
public class ClickService extends AccessibilityService {

    private static final String TAG = "GameSpeed/Click";

    public static final String ACTION_START = "com.example.gamespeed.action.START_CLICK";
    public static final String ACTION_STOP = "com.example.gamespeed.action.STOP_CLICK";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int currentIndex = 0; // 当前要点的点序号

    private final Runnable clickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            List<ClickConfig.ClickPoint> points = ClickConfig.getPoints();
            if (points.isEmpty()) {
                // 没点位，1 秒后再试
                handler.postDelayed(this, 1000);
                return;
            }

            // 取当前点
            ClickConfig.ClickPoint p = points.get(currentIndex);
            dispatchClick(p.x, p.y, p.durationMs);

            // 下一个点（循环）
            currentIndex = (currentIndex + 1) % points.size();

            // 间隔后再次执行
            int interval = ClickConfig.getInterval();
            handler.postDelayed(this, interval);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理事件，仅用 dispatchGesture
    }

    @Override
    public void onInterrupt() {
        stopClicking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startClicking();
            } else if (ACTION_STOP.equals(action)) {
                stopClicking();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        XposedBridge.log(TAG + " service connected");
        // 若上次退出时仍在运行，自动恢复
        if (ClickConfig.isPersistRunning(this)) {
            startClicking();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopClicking();
    }

    private void startClicking() {
        if (running) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            XposedBridge.log(TAG + " dispatchGesture 需要 Android 7.0+");
            return;
        }
        running = true;
        currentIndex = 0;
        ClickConfig.setPersistRunning(this, true);
        handler.post(clickRunnable);
        XposedBridge.log(TAG + " started, points=" + ClickConfig.getPoints().size()
                + " interval=" + ClickConfig.getInterval());
    }

    private void stopClicking() {
        running = false;
        handler.removeCallbacks(clickRunnable);
        ClickConfig.setPersistRunning(this, false);
        XposedBridge.log(TAG + " stopped");
    }

    /**
     * 发送一次点击手势
     * dispatchGesture 在 API 24+ 可用，完全免 root
     */
    private void dispatchClick(int x, int y, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean ok = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                // 点击完成
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                XposedBridge.log(TAG + " click cancelled");
            }
        }, null);

        if (!ok) {
            XposedBridge.log(TAG + " dispatchGesture failed at (" + x + "," + y + ")");
        }
    }

    /**
     * 判断无障碍服务是否已启用
     * 给 UI 调用，引导用户去开启无障碍权限
     */
    public static boolean isServiceEnabled(AccessibilityService context) {
        // 实际判断放在 MainActivity 里用 Settings.canDrawOverlays 类似方式
        return false;
    }
}
