# DMC P1 本地播放器统一使用 libmpv

**状态：** 统一后端方向获用户确认；审阅意见已纳入。R1 原生 POC 已通过；R2/R3 仍需分别验收。
**范围：** 最终让 DMC 本地音乐统一使用 libmpv 播放并支持 FLAC；按 R1 → R2 → R3 分阶段迁移。

## 1. 目标与现状

DMC 已有本地曲库、播放队列、`PlaybackCoordinator`、歌词和 Now Playing 页面。当前生产入口在 `Main.kt` 中创建 `PlaybackCoordinator(JavaFxPlaybackEngine())`；JavaFX Media 支持 MP3、WAV、M4A、AAC，但拒绝 FLAC。`PlaybackEngine` 是现有播放器与解码后端之间的抽象。

最终目标是由单一 libmpv 后端解码所有现有本地音频格式。保留 Compose 界面、曲库、队列、歌词、封面、快捷控制和播放状态持久化。允许为可靠处理异步原生事件，对 `PlaybackEvent`、`PlaybackCoordinator` 和设置保存逻辑做最小必要调整；不重构 UI、队列和曲库。

## 2. 分阶段验收门

### R1 — libmpv Native POC（当前阶段）

- 通过测试专用入口和 JNA 直接验证 Windows x64 libmpv ABI、实例初始化、销毁、事件读取和本地文件解码。
- 验证 MP3、FLAC、PCM WAV、AAC/M4A 的载入、时长/播放进度及文件结束事件；使用 `ao=null` 的结果只证明解码和事件，不代表设备发声。
- 加载操作必须等到真实文件事件，不把 `mpv_command(loadfile)` 成功返回当成文件已就绪。记录 native API 版本、错误码、事件顺序和 DLL 哈希。
- R1 不替换生产 `JavaFxPlaybackEngine`，不改 `PlaybackCoordinator`/`PlaybackEvent`，不新增音频输出设置，不删除 JavaFX 代码或依赖，不把 libmpv DLL 打进安装包或公开发布。
- POC DLL 只放在本机临时目录。验证通过状态为 `NATIVE_POC_PASS`；缺少可核验 DLL、ABI 不匹配、必需依赖无法加载或任一格式解码失败时为 `NATIVE_POC_BLOCKED` / `NATIVE_POC_FAIL`，不得用模拟测试替代真实 DLL 验收。

当前 POC 使用 mpv 第一方 GitHub CI 的 Windows x64 LGPL 开发构建，并固定到 release 页面所示 mpv commit `c3ac7f39514c7f45bc38a18e66887240539ee33c`。第一方页面明确将其标记为未测试开发构建；该构建仅用于 R1 ABI/行为原型，不作为稳定版或可发行二进制依据。实际下载包另记录 SHA-256 和文件清单。

**R1 本机证据（2026-10-13，Windows x64 / JDK 25）：** `nativeMpvPoc` 10 项通过，常规离线 `test --rerun-tasks` 244 项通过。真实 DLL 返回 client API 2.5；CI ZIP SHA-256 为 `9FFBED7A8AA013DA07A925DFBB046116683796AF76A9B635E4E7B3328D44F9D5`，`libmpv-2.dll` SHA-256 为 `FF1AAFF2CA9D5A56242DCEF28B20E3221B47F5FA38B25FBF6F3A8195E87157C1`。MP3（10.031 秒）、短 FLAC（0.5 秒）、合成进度 FLAC（3 秒）、PCM WAV（0.9 秒）和 AAC/M4A（2 秒）都报告文件载入及 EOF；除短 FLAC 外，其余格式均采到正向播放位置。短 FLAC 的 EOF 已验证，因时长过短未采到可靠进度；长 FLAC 进度样本 SHA-256 为 `77FF2AD6096FB21A0FF4F797A0E11123113B5D5EC7574DC7B8ED4DEFBF1A44B8`。Unicode 文件路径通过，STOP 被识别为 STOP，缺失文件和损坏 M4A 分别返回 ERROR（`-13`、`-17`），三次创建/解码/销毁循环通过。

以上 POC 使用 `ao=null`，只证明本机 DLL ABI、初始化/销毁、事件和解码行为；它不证明扬声器实际发声、WASAPI 共享/独占行为、生产播放器接入、安装包运行或公开发行许可。生产入口和 JavaFX 后端在 R1 中保持未改动。

### R2 — 统一生产播放与共享输出

- 新增生产 `LibMpvPlaybackEngine : PlaybackEngine`，接入现有 `PlaybackCoordinator`；迁移期间保留 JavaFX 后端作为对照，不先删除。
- 采用 Windows WASAPI 共享输出作为默认基线。必须显式选用 `ao=wasapi` 并设置 `audio-fallback-to-null=no`；实际 Windows 验收要确认输出后端不是 `null` 且设备确实发声。
- `loadfile` 改变播放列表后，实际载入是独立异步过程。`load()`/播放状态不得只根据命令返回报告就绪；应处理文件载入/播放事件、错误、超时、取消以及异步命令的 `reply_userdata`，避免 seek、stop、快速换曲和释放期间的竞态。
- 给每次播放建立单调递增的 session/generation ID。`PlaybackEvent` 携带该身份；协调器仅接受当前曲目的事件。旧曲目的迟到 `Ended`、`Position`、`Failed` 不得推进队列或覆盖当前状态。原生 `playlist_entry_id`/异步请求身份要映射到应用 session；若所选 API 版本不提供对应信息，必须用等效同步/隔离策略并通过测试证明。
- 映射 `MPV_EVENT_END_FILE` 的 reason：仅符合当前 session 的 EOF 才可作为自然结束候选；`STOP`、`QUIT`、`ERROR`、`REDIRECT` 和未知 reason 不推进队列。mpv 文档指出损坏或不完整媒体也可能报 EOF，因此 EOF 不能单独证明文件健康；同时收集解码错误事件/日志并按可验证证据分类。
- 增加确定性协调器测试：旧曲目的迟到 `Ended`、`Position`、`Failed` 均不影响当前曲目；覆盖 `load(A)` 紧接 `load(B)`、载入中 Stop、快速连续 Seek 和关闭时事件循环等待。
- 共享设备初始化失败必须返回错误，不得静默切换 null 输出。R2 验收为 `UNIFIED_PLAYBACK_PASS`，包含真实 Windows 共享输出和原有队列/歌词/控制回归。

### R3 — 独占输出、安装包与发行许可

- 共享模式稳定并通过 R2 后，才增加可选 WASAPI 独占模式。设备不支持或被占用时明确报错，不静默回退共享；`current-ao` 等信息用于确认实际输出后端，独占行为另以真实设备操作验收。不能要求所有设备对其他应用呈现相同的排他效果。
- 发行前冻结 mpv、FFmpeg、全部依赖、Windows x64 工具链和构建参数；保存对应源码、每个 DLL 的 SHA-256、来源、实际许可证、随包许可证文件以及 DLL 替换/重链接说明。构建参数（包括 `-Dgpl=false`）不是合规结论。
- 当前 mpv 第一方 CI 页面将该开发构建标记为 LGPLv3，并说明使用 LGPL FFmpeg 变体；正式发行仍须审核实际二进制依赖及其许可证。来源或许可证无法证明的二进制不得进入公开安装包。
- Inno Setup 安装版必须在没有系统 mpv 的干净 Windows 环境启动并实际播放。通过格式回归、安装、共享/独占和许可证门槛后，才删除 `JavaFxPlaybackEngine`、其专属 JavaFX 依赖及旧后端专属测试；测试覆盖必须迁移，不得靠删测试通过。
- R3 验收状态分别记录 `WASAPI_MODES_PASS`、`INSTALL_AUDIO_PASS`、`LICENSE_DISTRIBUTION_READY`。

## 3. 事件与原生资源约束

- libmpv 命令和事件等待遵循固定线程/锁约定；事件循环不能阻塞 Compose UI，也不能在销毁后回调。
- 只有当前 session 的正常结束事件才可能推进协调器队列。解码、文件载入、输出设备、native 加载错误都映射为可读失败信息，不得伪装成 EOF。
- 用状态机和 `reply_userdata`/播放列表条目标识关联命令与原生事件。每次新曲目使旧 session 失效；停止和关闭使相应 session 失效。
- 关闭时停止接收新命令、结束事件循环并按官方 API 顺序销毁 handle。重复创建/销毁必须通过压力烟测。
- libmpv 在 JVM 进程内运行；访问冲突等 native 崩溃可能直接结束整个进程，不能承诺由 Kotlin 捕获。POC 记录 DLL/API 版本和可公开错误信息；Windows 崩溃证据依赖系统 Error Reporting/事件查看器，不能伪造进程内恢复能力。
- JNA 必须以明确的本地文件路径加载开发/打包 DLL；不得依赖当前目录或任意 `PATH` 命中不同版本的 native 库。生产路径后续限定为应用私有安装目录。

## 4. 设置与数据兼容

- 当前 `DesktopSettings` 定义在 `DownloadTask.kt`，由 `DownloadTaskStore` 和任务记录一起保存到 JSON；设置页面在 `Main.kt` 中重新构造 `DesktopSettings`。
- R1 不增加音频设置。后续加入 `audioOutputMode` 时，默认 `SHARED`；旧 JSON 缺少字段或字段无效时回退默认，并保留其他值。
- 设置 UI 更新对象时使用当前设置的 `copy(...)` 或等效完整映射，避免保存下载/网络设置时将音频字段重置。通过旧 JSON 样本、序列化往返、设置 UI 保存往返测试验证。
- 不修改 SQLite 曲库 schema、下载任务格式和已持久化播放队列格式；不把账号会话交给播放器后端。

## 5. 测试与验收

### R1 自动与本机 Native POC

- 本机实际 x64 LGPL 开发 DLL 的加载、`mpv_client_api_version` 和初始化/终止成功。
- 有效 MP3、FLAC、PCM WAV、AAC/M4A 产生载入事件、可读取时长、播放位置前进和 EOF 事件。
- stop/quit/error reason 映射正确；损坏文件不得被报告为“健康解码通过”。
- 连续创建并销毁多个实例，不残留事件线程/handle；记录外部测试库的版本、来源、SHA-256 和加载依赖。
- R1 通过 `ao=null` 不计为真实设备发声，也不宣称安装包验收通过。

### R2/R3 后续测试

- 协调器注入旧 session 的 `Ended`、`Position`、`Failed`，确认状态、播放索引和队列不变。
- 覆盖载入/播放/seek/stop/关闭并发、载入超时、取消、native 错误和事件循环释放。
- 覆盖旧 DesktopSettings JSON、音频设置往返和 UI 修改其他设置后字段保持。
- R2 在真实 Windows WASAPI 共享设备播放 FLAC、MP3、M4A、WAV；`ao=null` 测试只计解码状态。
- R3 验证独占设备错误、安装版 DLL 加载路径、干净环境安装和实际声音，以及完整许可/哈希清单。

实际设备发声、测试 DLL 解码、生产安装包构建和许可证审查是不同证据，报告中分别标注。

## 6. 非目标

- R1 不修改生产播放行为，不开发 UI，不迁移 Coordinator，不添加共享/独占设置，不删除 JavaFX。
- 当前 P1 不做均衡器、DSP、自动增益、bit-perfect 承诺、自动输出模式切换、网络播放、标签写入或播放器 UI 重设计。
- 不因 JavaFX 暂时保留而维护长期双后端；它仅作为 R1/R2 迁移对照，最终在 R3 达标后移除。

## 7. 依据

- mpv 第一方 Windows CI 开发构建及 LGPL/GPL 资产说明：<https://github.com/mpv-player/mpv/releases/tag/git-release>
- mpv 官方 Windows 安装来源说明（第一方开发构建用途限制）：<https://mpv.io/installation/>
- mpv libmpv C API、异步命令、`reply_userdata` 和事件结构：<https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h>
- mpv `loadfile` 的异步载入语义、WASAPI 与 audio fallback 选项：<https://mpv.io/manual/stable/>
- FLAC 上游关于无损解码还原 PCM 的说明：<https://github.com/xiph/flac-website/blob/main/features.html>
