# hongguo-autoscroll

红果短剧（com.phoenix.read）自动上滑助手。一个 Android 无障碍服务（AccessibilityService）应用：检测到剧集结束提示或广告页时自动上滑切到下一集，带悬浮球状态指示，目标是在「挂机看剧」场景下免手动操作。

## 功能

- **智能模式**：识别片尾提示「上滑继续观看短剧」后自动上滑切集
  - 要求提示节点真正可见才触发，避免把提前挂进无障碍树的隐藏节点当成片尾信号
  - 2 秒宽限窗口容忍提示闪烁（红果的无障碍树是断续刷新的）
  - 正片 10 秒最小间隔，防止连滑跳集（广告页由广告逻辑单独控速）
- **广告过滤**：按证据判定广告页，判定到就滑走
  - 倒计时广告（「3秒后可继续上滑观看短剧」「5s 后可继续上滑观看」）读完秒立刻上滑，不再干等
  - 「广告」角标（含「广告 15s」这类剩余秒数）、广告 SDK 控件（穿山甲 `openadsdk` / `tt_video_ad` / `splash_ad` 等 view id 与 class）
  - 直播购物页（「进入直播间」「直播中」「讲解中」「已售 N 件」）、游戏推广页（「点击进入游戏」「点击试玩」）
  - 商业词组合：「优选服务」「进入直播间」单词即判定；「预约 / 咨询 / 查看详情…」同屏两个词、或一个词 + 严格「广告」标签
  - 跳过按钮自动点击：文字与 **view id** 两条路（无文字的图标按钮靠 `tt_splash_skip_btn` 这类 id 认），排除「跳过片头」，
    带倒计时的「跳过 5」归零前不点；可点控件占屏超过 40% 视为容器，不点它
  - 上滑提示按前缀认：「上滑继续观看短剧」「上滑继续看短剧」两种写法都算
  - 直播购物页 / 直播播放器（`ttlive_player_render_view`）从 0.60 屏高起手，避开底部可滚动商品面板吃掉手势
  - 一波广告最多滑 8 次 / 45 秒；倍速面板（「锁定倍速」等）在屏上时暂停一切自动滑动
  - **广告力度**：标准（只认文字与控件证据）/ 强力（再加「整页读不到文字」的画面型广告兜底，5 分钟最多 4 次，且只在播放页生效）
- **定时模式**：固定间隔上滑
- **悬浮球**：显示 待机 / 运行 / 广告 状态，可拖动、点击启停

## 下载

前往 [Releases](../../releases) 下载最新 APK（如 `AutoScroll-v1.9.apk`）。

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
4. 设置页「广告力度」可切标准 / 强力；广告页识别到的证据会写进运行日志（`ad detected: …`）

## 测试

[test/gate-app](test/gate-app) 是一个独立的无障碍行为测试 App，用真实无障碍服务驱动，模拟片尾、倒计时广告、无倒计时广告、提示闪烁、直播推广页等 12 个场景：

```bash
cd test/gate-app && gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am broadcast -a com.hg.gatetest.RUN --ei id 3   # 跑场景 3
adb logcat -s GateTest
```

场景通过 `downs/ups` 计数断言滑动是否按预期发生（次数与时机）。

## 已知限制

- 广告识别依赖无障碍树里能读到的文字与控件 id：红果改版文案、或广告画面完全由 SDK 自绘（读不到一个字）时可能识别不到；「强力」模式的空白页兜底能覆盖一部分，但无文字的正片也有被误滑的风险（已限制成只在播放页生效、且 5 分钟最多 4 次），所以默认不开
- 红果为 arm64 应用，x86_64 模拟器（无 ARM 转译）无法安装，测试 App 是行为级验证；真机表现以实际日志为准
- 应用核心是文本启发式识别，红果改版文案后需要同步更新关键词（片尾提示词见 `AdDetector.END_KEYWORDS`，广告特征见 `AdDetector` 顶部的正则与关键词表）

## 参考

广告判定特征参考了这几个同类开源项目（都已在本机实测过红果的广告页，本项目只借鉴「看什么特征」，代码是独立实现）：

- [lifeiyu-44/hongguo-ad-skipper](https://github.com/lifeiyu-44/hongguo-ad-skipper) —— 倒计时两种文案、角标秒数、拆分布局配对、直播/游戏/商城推广特征、空白页兜底
- [jvkal/-hongguo-ad-swiper](https://github.com/jvkal/-hongguo-ad-swiper) —— 商业词组合判定、倍速面板守卫、滑动起点避让
- [Persimmonsss/ad-skipper](https://github.com/Persimmonsss/ad-skipper) —— 广告 SDK 控件识别
- [baidu0013/hongguo-short-drama-gkd](https://github.com/baidu0013/hongguo-short-drama-gkd) —— 带真机快照的 GKD 规则：穿山甲 `tt_splash_skip_btn`、短剧页 Activity、直播播放器控件 id
- [pxlwjp0425/autoskip](https://github.com/pxlwjp0425/autoskip) —— 点击目标占屏面积上限（>40% 视为容器）这个防误触护栏
- [lnzx/Peeler](https://github.com/lnzx/Peeler) —— LSPosed 方案（需 root），用于对照广告来源

## License

[MIT](LICENSE)
