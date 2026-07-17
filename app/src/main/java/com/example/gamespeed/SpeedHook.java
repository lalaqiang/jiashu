package com.example.gamespeed;

import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 游戏加速 Hook 模块入口
 *
 * 原理：
 *  - Hook 所有"获取时间"的 API，让返回的时间值按倍率加速流逝
 *  - Hook 所有"睡眠/等待"的 API，让等待时长按倍率缩短
 *  - 双管齐下：游戏看到的"时间流逝"变成 N 倍，自身 sleep 也变短，整体加速
 *
 * 倍率来源：
 *  - 主进程 MainActivity 写入 XSharedPreferences（沙箱内可读）
 *  - 本类每秒刷新一次缓存值，避免每次 Hook 都读文件
 *
 * 使用基准时间法（避免 speed 变化时时间跳变）：
 *  - 记录上次切换 speed 时的「真实时间基准」和「虚拟时间基准」
 *  - 当前虚拟时间 = 虚拟基准 + (真实时间 - 真实基准) * speed
 *  - speed 变化时刷新基准
 */
public class SpeedHook implements IXposedHookLoadPackage {

    private static final String TAG = "GameSpeed";
    private static final String MODULE_PKG = "com.example.gamespeed";
    private static final String PREF_NAME = "speed";

    // ============ 倍率缓存（每个被 Hook 进程独立一份） ============
    private static XSharedPreferences pref;
    private static long lastReloadTime = 0;
    private static double currentSpeed = 1.0;

    // 毫秒级时间基准（用于 currentTimeMillis / uptimeMillis / elapsedRealtime）
    private static long baseRealMillis = 0;
    private static long baseVirtualMillis = 0;
    private static boolean millisInited = false;

    // 纳秒级时间基准（用于 nanoTime / elapsedRealtimeNanos）
    private static long baseRealNanos = 0;
    private static long baseVirtualNanos = 0;
    private static boolean nanosInited = false;

    /** 从主进程 SharedPreferences 读取倍率，1 秒刷新一次 */
    private static double getSpeed() {
        long now = SystemClock.elapsedRealtime();
        if (pref == null) {
            pref = new XSharedPreferences(MODULE_PKG, PREF_NAME);
            pref.makeWorldReadable();
        }
        if (now - lastReloadTime > 1000) {
            pref.reload();
            try {
                currentSpeed = pref.getFloat("speed", 1.0f);
            } catch (Throwable ignored) {
                currentSpeed = 1.0;
            }
            lastReloadTime = now;
        }
        if (currentSpeed < 0.1) currentSpeed = 0.1;   // 最低 0.1 倍（慢动作）
        if (currentSpeed > 20.0) currentSpeed = 20.0;  // 最高 20 倍
        return currentSpeed;
    }

    /** 检测 speed 变化并刷新毫秒基准 */
    private static long toVirtualMillis(long realMillis) {
        double speed = getSpeed();
        if (!millisInited) {
            baseRealMillis = realMillis;
            baseVirtualMillis = realMillis;
            millisInited = true;
            currentSpeed = speed;
        }
        if (speed != currentSpeed) {
            // 把当前累积的虚拟时间结算进基准
            baseVirtualMillis += (long) ((realMillis - baseRealMillis) * currentSpeed);
            baseRealMillis = realMillis;
            currentSpeed = speed;
        }
        return baseVirtualMillis + (long) ((realMillis - baseRealMillis) * currentSpeed);
    }

    /** 检测 speed 变化并刷新纳秒基准 */
    private static long toVirtualNanos(long realNanos) {
        double speed = getSpeed();
        if (!nanosInited) {
            baseRealNanos = realNanos;
            baseVirtualNanos = realNanos;
            nanosInited = true;
            currentSpeed = speed;
        }
        if (speed != currentSpeed) {
            baseVirtualNanos += (long) ((realNanos - baseRealNanos) * currentSpeed);
            baseRealNanos = realNanos;
            currentSpeed = speed;
        }
        return baseVirtualNanos + (long) ((realNanos - baseRealNanos) * currentSpeed);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        // 不限制包名，所有进入沙箱的 APP 都生效
        // 实际作用域由 VirtualXposed/太极阴勾选控制
        XposedBridge.log(TAG + " loaded into: " + lp.packageName);

        hookSystemClock();
        hookThreadSleep();
        hookObjectWait();
        hookSystemTime();
    }

    // ============ 1. Hook SystemClock（影响游戏动画/帧率/计时） ============
    private void hookSystemClock() {
        try {
            XposedHelpers.findAndHookMethod(SystemClock.class, "uptimeMillis",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(toVirtualMillis((Long) p.getResult()));
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook uptimeMillis fail: " + t); }

        try {
            XposedHelpers.findAndHookMethod(SystemClock.class, "elapsedRealtime",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(toVirtualMillis((Long) p.getResult()));
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook elapsedRealtime fail: " + t); }

        try {
            XposedHelpers.findAndHookMethod(SystemClock.class, "elapsedRealtimeNanos",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(toVirtualNanos((Long) p.getResult()));
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook elapsedRealtimeNanos fail: " + t); }
    }

    // ============ 2. Hook Thread.sleep（影响游戏主循环） ============
    private void hookThreadSleep() {
        try {
            XposedHelpers.findAndHookMethod(Thread.class, "sleep", long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        double speed = getSpeed();
                        if (speed > 1.0) {
                            long ms = (long) p.args[0];
                            p.args[0] = Math.max(1, (long) (ms / speed));
                        }
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook sleep(long) fail: " + t); }

        try {
            XposedHelpers.findAndHookMethod(Thread.class, "sleep", long.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        double speed = getSpeed();
                        if (speed > 1.0) {
                            long ms = (long) p.args[0];
                            int ns = (int) p.args[1];
                            long total = ms * 1_000_000L + ns;
                            total = (long) (total / speed);
                            p.args[0] = total / 1_000_000L;
                            p.args[1] = (int) (total % 1_000_000L);
                        }
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook sleep(long,int) fail: " + t); }
    }

    // ============ 3. Hook Object.wait（部分引擎用此做等待） ============
    private void hookObjectWait() {
        try {
            XposedHelpers.findAndHookMethod(Object.class, "wait", long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        double speed = getSpeed();
                        if (speed > 1.0) {
                            long ms = (long) p.args[0];
                            if (ms > 0) {
                                p.args[0] = Math.max(1, (long) (ms / speed));
                            }
                        }
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook wait(long) fail: " + t); }

        try {
            XposedHelpers.findAndHookMethod(Object.class, "wait", long.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        double speed = getSpeed();
                        if (speed > 1.0) {
                            long ms = (long) p.args[0];
                            int ns = (int) p.args[1];
                            long total = ms * 1_000_000L + ns;
                            if (total > 0) {
                                total = (long) (total / speed);
                                p.args[0] = total / 1_000_000L;
                                p.args[1] = (int) (total % 1_000_000L);
                            }
                        }
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook wait(long,int) fail: " + t); }
    }

    // ============ 4. Hook System.nanoTime / currentTimeMillis（部分引擎用） ============
    private void hookSystemTime() {
        try {
            XposedHelpers.findAndHookMethod(System.class, "nanoTime",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(toVirtualNanos((Long) p.getResult()));
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook nanoTime fail: " + t); }

        try {
            XposedHelpers.findAndHookMethod(System.class, "currentTimeMillis",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(toVirtualMillis((Long) p.getResult()));
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + " hook currentTimeMillis fail: " + t); }
    }
}
