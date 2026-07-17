package com.example.gamespeed;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 连点器配置（同进程内全局可访问）
 *
 * 数据结构：
 *  - 点位列表 List<ClickPoint>，每个点包含坐标和按下时长
 *  - 点击间隔（毫秒），所有点循环点击时的间隔
 *  - 持久化运行状态（服务被系统杀死后是否自动恢复）
 *
 * 持久化策略：
 *  - 点位和间隔用 SharedPreferences 存储
 *  - 运行状态也存一下，避免服务重启丢失
 */
public class ClickConfig {

    private static final String PREF_NAME = "click_config";
    private static final String KEY_POINTS = "points";
    private static final String KEY_INTERVAL = "interval";
    private static final String KEY_RUNNING = "running";

    private static final int DEFAULT_INTERVAL = 100;

    // 内存中的点位列表（UI 和 Service 共享）
    private static final List<ClickPoint> points = new ArrayList<>();
    private static int interval = DEFAULT_INTERVAL;
    private static boolean loaded = false;

    /** 单个点击点配置 */
    public static class ClickPoint {
        public int x;
        public int y;
        public int durationMs; // 按下时长（影响点击识别）

        public ClickPoint(int x, int y, int durationMs) {
            this.x = x;
            this.y = y;
            this.durationMs = durationMs;
        }

        @Override
        public String toString() {
            return x + "," + y + "," + durationMs;
        }

        public static ClickPoint fromString(String s) {
            String[] parts = s.split(",");
            if (parts.length < 3) return null;
            try {
                return new ClickPoint(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** 首次访问时从 SharedPreferences 加载 */
    private static void ensureLoaded(Context ctx) {
        if (loaded) return;
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        String pointsStr = sp.getString(KEY_POINTS, "");
        points.clear();
        if (!pointsStr.isEmpty()) {
            for (String s : pointsStr.split("\\|")) {
                ClickPoint p = ClickPoint.fromString(s);
                if (p != null) points.add(p);
            }
        }

        interval = sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
        loaded = true;
    }

    public static List<ClickPoint> getPoints() {
        return Collections.unmodifiableList(points);
    }

    public static int getInterval() {
        return interval;
    }

    public static void setInterval(Context ctx, int ms) {
        interval = ms;
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_INTERVAL, ms)
                .apply();
    }

    public static void addPoint(Context ctx, int x, int y, int durationMs) {
        ensureLoaded(ctx);
        points.add(new ClickPoint(x, y, durationMs));
        savePoints(ctx);
    }

    public static void updatePoint(Context ctx, int index, int x, int y, int durationMs) {
        ensureLoaded(ctx);
        if (index < 0 || index >= points.size()) return;
        points.set(index, new ClickPoint(x, y, durationMs));
        savePoints(ctx);
    }

    public static void removePoint(Context ctx, int index) {
        ensureLoaded(ctx);
        if (index < 0 || index >= points.size()) return;
        points.remove(index);
        savePoints(ctx);
    }

    public static void clearPoints(Context ctx) {
        ensureLoaded(ctx);
        points.clear();
        savePoints(ctx);
    }

    private static void savePoints(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(points.get(i).toString());
        }
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_POINTS, sb.toString())
                .apply();
    }

    public static boolean isPersistRunning(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false);
    }

    public static void setPersistRunning(Context ctx, boolean running) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, running)
                .apply();
    }

    /** 供 UI 首次访问调用 */
    public static void init(Context ctx) {
        ensureLoaded(ctx);
    }
}
