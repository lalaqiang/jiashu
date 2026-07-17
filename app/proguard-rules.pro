# Keep Xposed 入口类
-keep class com.example.gamespeed.SpeedHook { *; }

# Keep IXposedHookLoadPackage 接口相关
-keep class de.robv.android.xposed.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
