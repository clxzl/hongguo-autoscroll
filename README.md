# hongguo-autoscroll

红果短剧（com.phoenix.read）自动上滑助手。一个 Android 无障碍服务（AccessibilityService）应用：检测到剧集结束提示或广告页时自动上滑切到下一集，带悬浮球状态指示，目标是在「挂机看剧」场景下免手动操作。

## 功能

- **智能模式**：识别屏幕上的「上滑继续」提示后自动上滑切集
  - 要求提示节点真正可见才触发，避免把提前挂进无障碍树的隐藏节点当成片尾信号
  - 提示持续 1.2 秒才滑，2 秒宽限窗口容忍红果无障碍树的断续刷新
  - 正片 10 秒 / 广告页 4 秒最小间隔，防止连滑跳集
- **广告处理**
  - 可见「广告」或「跳过广告」/「关闭广告」文案即判定广告页（只认短文案，避免剧名里的「广告」误判）
  - 广告页没有片尾提示时，8 秒后盲滑一次，之后按 10 秒重试
  - 不点击跳过按钮、不等待倒计时：倒计时页没有提示就不动，倒计时结束出现提示再滑
- **定时模式**：固定间隔上滑
- **悬浮球**：显示 待机 / 运行 / 广告 状态，可拖动、点击启停

## 下载

前往 [Releases](../../releases) 下载最新 APK（如 `AutoScroll-v1.14.apk`）。

## 构建要求

- Android Studio 或 Gradle 8.x
- minSdk 26 / targetSdk 34

```bash
gradle assembleDebug
```

## 使用

1. 安装 APK，在系统设置中开启「自动上滑」无障碍服务
2. 打开红果短剧播放页面，悬浮球点击启动
3. 之后每到片尾或广告会自动上滑

## 测试

[test/gate-app](test/gate-app) 是一个独立的无障碍行为测试 App，用真实无障碍服务驱动，模拟片尾、倒计时广告、无倒计时广告、提示闪烁等 10 个场景：

```bash
cd test/gate-app && gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am broadcast -a com.hg.gatetest.RUN --ei id 3   # 跑场景 3
adb logcat -s GateTest
```

场景通过 `downs/ups` 计数断言滑动是否按预期发生（次数与时机）。

## 已知限制

- 红果为 arm64 应用，x86_64 模拟器（无 ARM 转译）无法安装，测试 App 是行为级验证；真机表现以实际日志为准
- 应用核心是文本启发式识别，红果改版文案后需要同步更新关键词

## License

[MIT](LICENSE)
