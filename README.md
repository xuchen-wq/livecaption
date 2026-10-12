# LiveCaption

看视频时的实时悬浮字幕：抓取手机正在播放的媒体音频，经 Qwen 实时翻译模型流式产出双语字幕，浮在任意 App 之上。

---

## 快速开始

1. **准备密钥**：开通阿里云百炼，创建 API Key；在业务空间详情页拿到「业务空间 ID（WorkspaceId）」。
2. **安装 APK**：用 `adb install` 或直接把 APK 传到手机安装。
3. **授予悬浮窗权限**：打开 App → 「1 授予悬浮窗权限」→ 在系统设置页里打开开关。
4. **填连接参数**：点「连接设置」→ 填 API Key、业务空间 ID → 保存。
5. **开始**：点「开始悬浮字幕」→ 在系统弹窗里同意屏幕捕获 → **切到浏览器/播放器播放视频**，字幕就会浮在屏幕底部。

看的过程中，屏幕右上角常驻一个按钮：**点一下暂停（暂停即停止计费），再点一下继续**。

## 功能

| | |
|---|---|
| 悬浮字幕 | 浮在任意 App 之上，不用把视频放进自己的播放器 |
| 双语显示 | 原文 + 译文两行，长句自动缩放字号，不截断 |
| 位置可调 | 按住字幕条上下拖动，松手自动记住 |
| 暂停 / 继续 | 暂停立即停止推流与计费，恢复约 1–3 秒 |
| 热词表 | 填专有名词（人名、术语、作品名）提升识别准确率 |
| 模型可选 | `qwen3.8-livetranslate-flash-realtime`（默认）/ `qwen3.5-...` |
| 语种 | 目标语种可指定（默认中文）；3.5 支持锁死源语种 |

## 已知限制（重要，先看这里）

- **只支持 Android 10（API 29）及以上**。iOS 做不了这个形态——系统不允许第三方 App 在别的 App 上层绘制内容。
- **DRM 内容抓不到音频**。Netflix、Disney+、Spotify 及多数正版流媒体 VIP 会返回静音，字幕自然出不来。本地视频、网盘、B站、YouTube、浏览器播放的普通页面通常可以。
- **延迟基线约 600–800ms**，这是模型固有的，流式 delta 已快于官方标称的完整句延迟。
- **每 15 分钟自动轮换一次会话**，会有 1–3 秒的静默间隙（防止上下文累积导致延迟劣化）。
- **单次会话上限约 1.95 小时**音频，长片靠上面的轮换自动处理。
- 百炼控制台的调用记录里，会话结束状态**常显示为 400**。这是"会话结束方式"的记录，不是请求失败——内容照常返回、计费不受影响，可忽略。


## 构建

需要 JDK 17、Android SDK（compileSdk 34）、Gradle 8.7。

仓库未包含 `gradlew`（wrapper 脚本未入库），用本机 Gradle 构建：

```bash
# 先指定 SDK 路径
echo "sdk.dir=/你的/SDK/路径" > local.properties

gradle assembleDebug          # 产物在 app/build/outputs/apk/debug/
```

如需补上 wrapper：`gradle wrapper --gradle-version 8.7`。

## 代码结构

```
app/src/main/java/com/livecaption/
├── MainActivity.kt          设置界面：API Key、模型、语种、热词，启动/停止
├── AudioCaptureService.kt   前台服务：MediaProjection + AudioPlaybackCapture 抓音频、分帧、会话轮换
├── PcmResampler.kt          重采样到 16kHz 单声道 PCM
├── QwenTranslateClient.kt   百炼实时翻译 WebSocket 客户端
└── OverlayCaption.kt        悬浮字幕渲染（可拖动、自动缩放）+ 暂停/继续按钮
```

数据链路：`AudioPlaybackCapture` → 16kHz PCM 分帧 → WebSocket 推流 → 流式接收译文 delta → 悬浮窗渲染。

## License

[MIT](LICENSE) © 2026 xuchen
