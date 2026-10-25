# DMC 当前项目状态

**审阅日期：2026-10-25；远端代码基线：main @ 6220796220cb10323b248e54945e2a2855b3b177（2026-10-17）。** 本文件是远端代码、发布页面、工作流和历史报告核验，不是本轮本机重新运行应用/测试的结果。

## 当前实际状态

| 维度 | 核验结论 | 依据与边界 |
| --- | --- | --- |
| 公开仓库 | PASS | main HEAD 6220796；代码结构为 Kotlin/JVM + Compose Desktop，JNA/libmpv |
| 最新已核实发行版 | RELEASED | v0.0.3，2026-10-17，非 GitHub prerelease 标记 |
| 发行版播放运行库 | INCLUDED IN RELEASE | v0.0.3 Release 描述及 CI 的 IncludeReleaseMpv 构建参数、libmpv 源码/运行库清单/校验资产 |
| 本地源构建 | CONFIGURATION-DEPENDENT | 缺省 Gradle releaseVersion 为 0.1.2；普通 packageExe 和普通 Inno 打包不等同于官方 v0.0.3 发行 |
| 主分支 CI | PASS REPORTED | 2026-10-17 在该 HEAD 的两次 Desktop CI 运行显示 success；本轮未重跑 |
| 本地播放实现 | IMPLEMENTED IN CODE | LocalTrack → PlaybackEngine.load → LibMpvPlaybackEngine，WASAPI shared；限定本地常规文件 |
| 曲库与播放器界面 | IMPLEMENTED IN CODE | 四类本地浏览、队列、歌词、沉浸式 UI 相关代码与测试存在；不等同于所有交互人工通过 |
| 网易云发现 / 下载 | IMPLEMENTED IN CODE | 可选的服务、会话、下载任务与音源 URL 安全处理 |
| WebDAV / 云端在线播放 | NOT IMPLEMENTED | 目前不存在远端目录和播放源抽象；本地路径模型不能直接承载 URL |
| Windows 发布体验 | PARTIAL EVIDENCE | CI 打包、校验与安装/卸载烟雾测试不能代替多机器真实安装、扬声器输出、升级及 UAC 验收 |

GitHub 证据：[main HEAD](https://github.com/ChangfengluoO71/desktop-music-candidate/commit/6220796220cb10323b248e54945e2a2855b3b177)、[v0.0.3 Release](https://github.com/ChangfengluoO71/desktop-music-candidate/releases/tag/v0.0.3)、[Desktop CI](https://github.com/ChangfengluoO71/desktop-music-candidate/actions)。历史 2026-10-14 的全量 298 tests 和 nativeMpvShared 两项通过记录见 [Player P1](PLAYER-P1.md) 与 [原生验收记录](testing/libmpv-r2-windows-acceptance.md)。**不能将历史测试数当作当前 HEAD 的本轮实测数。**

## 结构性限制和风险

1. 本地曲目键基于 Windows 规范化路径（LocalTrack.pathKey）；移动文件等于新身份，无法直接表达云盘文件 ID。
2. PlaybackCoordinator 的 queue 是 List<LocalTrack>；PlaybackQueueStore v1 从本地 availableTracks 按路径恢复。引入远端类型必须先定义兼容迁移，不能伪造本地路径。
3. libmpv native engine 在 load 时调用 Files.isRegularFile / Files.isReadable，并将本地绝对路径交给 loadfile；网络 URL 尚未打通。
4. 当前下载任务模型仍对网易云 ID 有依赖；不要为了云端播放改写下载队列或账号会话数据。
5. JavaFX 旧代码及依赖仍存在，属于迁移残余，不代表生产播放后端存在自动回退。
6. 发布分发已包含运行库，但每次更新 libmpv/FFmpeg 构建及依赖仍需重新做许可和可再链接义务核查。

## 立即处理的门禁（C0）

- 在干净 Windows 11、已有安装升级、卸载保留数据三种路径实测 **v0.0.3**：安装路径、开始菜单、完整 GUI、MP3/FLAC/M4A/WAV、libmpv 加载、歌词和队列，记录截图与设备环境。
- 验证 100%、125%、150%、200% DPI、窗口改变、主窗口/沉浸式歌词交互、封面加载及错误提示。
- 核对 releaseVersion 与二进制 BuildInfo，继续将可见版本、更新检查、Release tag 分开验证。
- 清点未完成的人工验收、许可证、遗留 JavaFX 依赖；仅在完成回归后决定清理。
- 开始 WebDAV POC 前冻结本地播放和队列向后兼容测试；不允许先改持久化 schema 再补迁移。

## 下一阶段

详见 [ROADMAP.md](ROADMAP.md) 与 [CLOUD-PLAYBACK-DESIGN.md](CLOUD-PLAYBACK-DESIGN.md)。本文件中的 WebDAV 工作全部属于待做规划，绝不暗示功能已经落地。