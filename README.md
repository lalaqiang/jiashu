# 游戏加速器 Xposed 模块

最小可编译的免 root 手游加速器，集变速 + 连点器 + 悬浮窗调速于一体。配合 VirtualXposed 或太极·阴使用。

## 功能一览

| 功能 | 实现方式 | 免 Root |
|------|------|------|
| **变速** | Hook SystemClock/sleep 等时间 API | ✓（需沙箱注入） |
| **连点器** | AccessibilityService.dispatchGesture | ✓ |
| **悬浮窗调速** | WindowManager + TYPE_APPLICATION_OVERLAY | ✓ |
| **点位管理** | 多点循环点击，可调间隔 | ✓ |

## 变速原理

Hook 6 类时间相关 API，让游戏感知到的时间按倍率加速流逝：

| Hook 目标 | 作用 |
|------|------|
| `SystemClock.uptimeMillis / elapsedRealtime / elapsedRealtimeNanos` | 游戏动画/帧率/计时 |
| `System.nanoTime / currentTimeMillis` | 部分引擎用 |
| `Thread.sleep(long / long,int)` | 游戏主循环 |
| `Object.wait(long / long,int)` | 等待逻辑 |

**基准时间法**：倍率切换时不会时间跳变。记录上次切换时的「真实时间基准」和「虚拟时间基准」，新倍率下用「虚拟基准 + (真实时间 - 真实基准) × speed」计算，避免时间跳变导致游戏异常。

## 连点器原理

用 `AccessibilityService.dispatchGesture()` 模拟点击，**完全免 root**：
- API 24+ 可用
- 支持多点循环点击
- 每点可配置按下时长（影响游戏对点击的识别）
- 全局可调点击间隔（10~2000ms）
- 服务被系统杀死可自动恢复

## 目录结构

```
GameSpeedModule/
├── build.gradle              # 顶层
├── settings.gradle           # 仓库 + 子模块
├── gradle.properties
├── gradle-wrapper.properties
├── README.md
└── app/
    ├── build.gradle          # 应用模块（compileOnly Xposed API）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/xposed_init    # Hook 入口类全限定名
        ├── java/com/example/gamespeed/
        │   ├── SpeedHook.java        # 核心 Hook（变速）
        │   ├── MainActivity.java     # 主控台
        │   ├── ClickService.java     # 连点器无障碍服务
        │   ├── ClickConfig.java      # 连点器配置存储
        │   ├── ClickConfigActivity.java # 连点器配置界面
        │   └── FloatingService.java  # 悬浮窗调速服务
        └── res/
            ├── layout/
            │   ├── activity_main.xml         # 主控台布局
            │   ├── activity_click_config.xml # 连点器配置布局
            │   ├── dialog_point.xml          # 添加点位对话框
            │   ├── item_point.xml            # 点位列表项
            │   └── floating_panel.xml        # 悬浮窗面板
            ├── values/
            │   ├── strings.xml
            │   └── colors.xml
            └── xml/
                ├── xposed_scope.xml             # Xposed 作用域
                └── click_accessibility_config.xml # 无障碍配置
```

## 编译

用 Android Studio 打开 `GameSpeedModule` 目录，或命令行：

```powershell
cd c:\Users\Administrator\Desktop\手游\ss\GameSpeedModule
gradlew.bat assembleDebug
```

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`

> 命令行编译需要先有 Gradle Wrapper（gradlew.bat + gradle/wrapper/）。Android Studio 打开会自动生成。

## 使用流程

### 第一步：装 APK

把编译出的 APK 装到手机上（普通安装即可，无需 root）。

### 第二步：装沙箱

任选其一：
- **VirtualXposed**：https://github.com/android-hacker/VirtualXposed
- **太极·阴**：https://github.com/tiann/Tai-Chi

### 第三步：启用模块

在沙箱的「模块管理」里勾选本模块。

### 第四步：配作用域

修改 `app/src/main/res/xml/xposed_scope.xml`，把 `com.example.targetgame` 改成目标游戏包名，重新编译。

或在沙箱内直接勾选作用域（推荐，更灵活）。

### 第五步：装游戏到沙箱

- VirtualXposed：「添加应用」→ 选择目标游戏
- 太极·阴：「应用管理」→ 创建分身

### 第六步：用变速

**方式 A：主界面调速**
打开 APP，拖动 SeekBar 调整倍率。

**方式 B：悬浮窗调速（推荐）**
1. 在主界面点「启动悬浮窗调速」
2. 第一次会要求授予悬浮窗权限
3. 授权后桌面会出现一个指南针图标悬浮球
4. 拖动可移动位置，点击展开控制面板
5. 在面板里用 SeekBar 或 ±0.5 按钮调整倍率

倍率 1 秒后生效（Hook 进程每秒刷新一次）。

### 第七步：用连点器

1. 在主界面点「连点器配置」
2. 点「点击启用无障碍服务」，跳转到系统设置，开启「游戏加速器 - 连点器」
3. 回到 APP，点「添加」输入要点击的坐标和按下时长
   - 例：(540, 1200) 表示屏幕中央偏下
   - 时长 50ms 是普通点击，200ms 以上可识别为长按
4. 调整「点击间隔」（默认 100ms）
5. 可以添加多个点，服务会按列表循环点击
6. 点「开始」启动连点，「停止」结束

### 第八步：启动游戏

从沙箱内启动目标游戏，加速 + 连点就会同时生效。

## 常见问题

### Q: 调整倍率后游戏没变化？

- 确认模块在沙箱里被启用
- 确认作用域包含目标游戏包名
- 确认游戏是从沙箱内启动的（不是系统启动）
- 查看 Xposed 日志，搜索 `GameSpeed`

### Q: 连点器点了没反应？

- 确认无障碍服务已启用（系统设置 → 无障碍）
- 确认坐标在屏幕范围内（不同手机分辨率不同）
- 确认连点器服务运行中（看 Toast 提示）
- 某些游戏可能屏蔽无障碍手势，可尝试加大按下时长

### Q: 悬浮球不显示？

- 确认授予了悬浮窗权限（系统设置 → 应用 → 游戏加速器 → 显示在其他应用上层）
- 部分系统（MIUI/EMUI）需要在「后台弹出界面」里也开启权限

### Q: 游戏闪退？

- 多数是游戏检测到沙箱或 Hook，换游戏测试
- 单机/放置类游戏通常没问题，大厂网游基本会闪退
- 降低倍率到 2x 试试

### Q: 提示「WORLD_READABLE 不可用」？

Android 7+ 普通模式不允许 `MODE_WORLD_READABLE`。在沙箱内运行时会自动处理，可直接忽略提示。若沙箱也不支持，需要改用 ContentProvider 跨进程同步倍率。

### Q: 倍率超过 5x 后游戏卡死？

部分游戏引擎对时间跳变敏感，建议：
- 从 1.5x 慢慢往上调
- 不要在游戏运行中突然切换到大倍率
- 一般 3x~5x 是甜点区间

## 已知限制

1. **沙箱兼容性**：VirtualXposed 停更于 2019 年左右，Android 12+ 跑起来需要修 bug
2. **反作弊游戏**：带反作弊的网游（腾讯/网易/米哈游等）基本都会闪退或封号，仅适合单机/放置类
3. **WORLD_READABLE**：Android 7+ 系统限制，需在沙箱环境使用
4. **录制功能**：当前需手动输入坐标，未实现屏幕录制选点（路线图）

## 后续扩展路线

- [ ] 录制功能：屏幕录制时点选位置自动生成点位
- [ ] 改用 ContentProvider 同步倍率（兼容 Android 11+）
- [ ] 沙箱兼容性修复（Android 12+）
- [ ] 多游戏独立配置
- [ ] 悬浮球自动贴边
- [ ] 滑动手势录制回放

## 风险提示

仅用于单机/放置类游戏学习研究。带反作弊的网游可能导致封号，使用风险自负。
