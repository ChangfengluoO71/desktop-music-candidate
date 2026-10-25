# DMC 播放器 P1：现状与未关闭门禁

**本页在 2026-10-25 对齐远端 main @ 6220796；不代表本轮进行了新的本机 GUI 测试。** 历史详细 R1/R2/R3 设计留在 docs/superpowers/。

## 代码已具备

- libmpv/JNA 原生播放器，WASAPI shared 音频输出和独立 native worker；local MP3/FLAC/M4A/WAV 的 Windows 原生测试在历史记录中有通过证据。
- 主窗口正在播放、底部迷你播放控制、本地曲库（专辑/歌手/歌曲/文件夹）、本地文件监听与封面缓存。
- 播放列表式队列操作、歌词定位、原文/翻译/罗马音轨道、沉浸式播放页面相关背景与歌词逻辑、键盘快捷键；仓库具有对应代码/自动化测试。
- 本地播放不依赖网易云会话；在未装 libmpv 或 WASAPI 不可用时报告错误，不退回 JavaFX 或静音输出。

## 当前明确的限制

- PlaybackEngine.load(track: LocalTrack, sessionId)；LibMpvPlaybackEngine.load 会检查 Files.isRegularFile / Files.isReadable，原生 loadfile 指向本地路径。**不支持把网络 URL 直接传给现有接口。**
- PlaybackCoordinator 和 PlaybackQueueStore v1 都以 LocalTrack/pathKey 为核心；云端播放需要类型和 schema 的兼容演化。
- 内嵌封面、DPI 与全套 GUI 菜单的人机操作不能从自动化测试存在推导出通过。
- JavaFX 代码及依赖尚存在但不是生产后端；若清理须先跑构建、播放和分发许可回归。

## 本地开发与发行版区别

2026-10-14 的历史记录为 298 项自动化测试通过、nativeMpvShared 两项通过；这是旧基线测试，不是 2026-10-25 新验收。参见 [R2 Windows 原生验收](testing/libmpv-r2-windows-acceptance.md)。

**v0.0.3 GitHub Release 包含 LGPL libmpv、对应源码与许可材料**；该 Release 的 CI 有打包和安装烟雾测试步骤。普通 packageExe、createDistributable 或不带 IncludeReleaseMpv 的手工 Inno 构建则不能推定包含该 DLL。开发机还可运行 scripts/prepare-libmpv.ps1 下载固定哈希的本地测试版，和官方 LGPL 发布构建应严格区分。

## 未关闭的验收

1. 官方 v0.0.3 安装版真实播放、设备输出、安装覆盖升级与卸载保留配置。
2. 100%、125%、150%、200% 缩放，专辑与歌手卡片菜单、歌词跟随/滚动、沉浸式背景、封面不弹出额外窗口、键盘焦点。
3. 基于既有 queue v1 数据的恢复回归以及缺文件/文件重命名场景。
4. libmpv / FFmpeg 更新后真实发行构建与第三方许可证清单回归。

下一步云端媒体播放属于新工作流，见 [云端播放技术方案](CLOUD-PLAYBACK-DESIGN.md)。