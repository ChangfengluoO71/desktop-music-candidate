# DMC P1 本地播放器统一使用 libmpv

**状态：** libmpv 统一后端方向获用户确认；详细设计待审阅。
**范围：** 将 DMC 的本地播放器统一切换到 libmpv，并把 FLAC 播放纳入 P1。

## 1. 目标与现状

DMC 已有本地曲库、播放队列、`PlaybackCoordinator`、歌词和 Now Playing 页面。当前生产入口使用 `JavaFxPlaybackEngine`；它支持 MP3、WAV、M4A、AAC，但明确拒绝 FLAC。`PlaybackEngine` 已提供载入、播放、暂停、停止、跳转、音量和事件流接口。

本阶段让 MP3、WAV、M4A、AAC、FLAC 等现有本地曲目都使用同一个 libmpv 后端，避免按格式切换输出引擎。保持曲库、队列、歌词、封面和窗口交互行为不变。成功标准是自动化测试证明支持格式可由 libmpv 解码，Windows 安装包内含可加载的 LGPL libmpv 运行库，用户能从安装版实际播放 FLAC 并操作现有播放控制。

## 2. 已确认的设计决定

1. **统一播放后端。** 新增 `LibMpvPlaybackEngine : PlaybackEngine`，生产启动改为使用该实现。`PlaybackCoordinator` 与 Compose 界面继续依赖现有抽象，不直接访问 JNA 或 libmpv。
2. **替换范围仅限播放引擎。** 保留播放器界面、曲库、队列、歌词、封面、播放状态持久化和快捷控制。libmpv 后端及替代测试通过后，删除不再使用的 `JavaFxPlaybackEngine` 与仅供它使用的 JavaFX 运行依赖；迁移并保留原有解码、格式支持和播放行为的测试覆盖，不以删测试代替迁移。
3. **直接嵌入 libmpv。** 通过项目现有 JNA 依赖调用 libmpv C client API；不启动外部 `mpv.exe`，不要求用户单独安装 mpv。
4. **仅播放本地曲目。** 引擎仅接受经本地索引校验的 `LocalTrack` 文件路径。初始化禁用用户 mpv 配置和脚本，不接受网络 URL，不读取账号会话或远程下载地址。
5. **统一 Windows 输出设置。** 默认 WASAPI 共享输出；增加可选的 WASAPI 独占输出。设置持久化，修改后重启应用生效。界面说明独占模式可能暂时占用设备，阻止其他应用播放声音。
6. **音质承诺。** 正确的 FLAC 解码应还原编码前的 PCM；DMC 不宣称 bit-perfect。共享模式可能受 Windows 混音和设备格式影响；独占模式是可选输出路径，不是音质必然改善的保证。
7. **平台边界。** 本阶段只支持既有 Windows x64 安装包，不扩展 Linux/macOS 或其他新格式的产品承诺。

## 3. 引擎结构与事件行为

```mermaid
flowchart LR
  UI[Compose 播放器界面] --> VM[PlayerViewModel]
  VM --> PC[PlaybackCoordinator]
  PC --> IF[PlaybackEngine]
  IF --> MPV[LibMpvPlaybackEngine]
  MPV --> JNA[JNA C API binding]
  JNA --> DLL[Bundled LGPL libmpv + runtime DLLs]
  DLL --> WASAPI[Windows WASAPI]
```

- 所有进入 libmpv 的命令通过一个串行执行器提交；事件等待循环独立于 UI 与命令线程运行。
- 将 `load`、`play`、`pause`、`stop`、`seek`、`volume` 映射到 libmpv client API；观察播放时间、时长、暂停/音量状态及文件结束事件，转成现有 `PlaybackEvent`。
- 只有正常 EOF 发出 `PlaybackEvent.Ended`；解码错误、文件失效、输出设备打不开和 native 库加载失败分别映射为可读失败信息，不触发错误的自动队列推进。
- 关闭协调器时先停止播放、终止事件循环并释放 libmpv handle，确保没有后台线程或设备占用残留。
- 输出模式在 libmpv 实例创建阶段应用；修改设置后重启应用，避免在播放过程中重建后端导致队列/位置状态不同步。

## 4. Windows 原生库与许可

- 选择并固定 mpv 源码版本、依赖版本、构建参数、Windows x64 工具链来源和产物 SHA-256；不依赖机器上偶然存在的 `PATH` DLL。
- 使用 libmpv 共享库，并按 mpv 上游文档以 `-Dgpl=false` 选择 LGPL 构建；审查 FFmpeg 及所有构建依赖是否引入 GPL 或 nonfree 组件。
- 将 `libmpv-2.dll` 及其运行时依赖放在应用私有目录，并由安装器随应用分发。安装版必须在未安装 mpv 的干净 Windows 环境中能够启动和播放。
- 增加原生依赖清单，记录来源 URL、版本/提交、构建参数、哈希和每个 DLL 的许可证。随安装包提供所需许可证、版权声明及对应源代码/获取说明，满足 LGPL 对动态库分发和替换的要求。
- 如果无法证明某个 DLL 的来源、许可证或对应源码，不把它打进安装包；该项作为发行阻塞，不通过忽略合规要求来得到构建成功。

## 5. 设置与数据兼容

- 在现有 `DesktopSettings` 中增加 `audioOutputMode`，取值为 `SHARED` 和 `EXCLUSIVE`，默认 `SHARED`。
- JSON 缺少该字段或字段无效时回退到 `SHARED`，确保既有设置文件可以读取且不丢失其他用户设置。
- 设置界面增加明确的 Windows 音频输出选项、重启提示和独占设备影响说明。
- 不把播放模式放入下载任务或本地音乐索引表，不修改 SQLite 曲库 schema。

## 6. 测试与验收

### 自动化

- libmpv API 适配层覆盖初始化参数、命令映射、属性事件映射、EOF 与解码错误分类、停止与释放时序。
- 用有效合成 FLAC 验证 libmpv 解码、时长和播放位置；用损坏 FLAC 验证错误隔离。
- 用 libmpv null 输出后端验证离线解码、播放/暂停、跳转、音量属性、结束事件与队列推进。该测试只证明解码和事件行为，不证明实际扬声器发声。
- 对现有 MP3、AAC-LC/M4A、PCM WAV 合成样本执行兼容性测试，保持原有格式行为。
- 测试默认共享模式、独占模式参数及旧设置文件回退；测试文件路径含空格、中文和特殊字符。
- 构建/安装烟测检查 x64 native 库与依赖清单完整、许可证文件随包存在、加载时不依赖系统 PATH 或已安装 mpv。

### Windows 实机

1. 构建并安装新 Inno Setup 安装包，从开始菜单启动。
2. 打开含 FLAC 的本地音乐目录，播放一首合法本地 FLAC；听到声音后验证暂停、继续、跳转、音量、上一首/下一首和结束推进。
3. 验证 MP3、M4A、WAV 仍可播放；在 Now Playing 页面检查曲目信息和歌词同步。
4. 选择 WASAPI 独占模式并重启，验证能播放并确认系统设备被占用的提示；切回共享模式后验证其他应用可同时播放。
5. 退出应用后确认设备释放，再启动应用确认播放队列仍按原有规则恢复且不自动发声。

自动化解码通过、安装包构建通过和真实设备实播是不同证据，最终报告分别标记。

## 7. 非目标与风险

- 不做音频均衡器、DSP、自动增益、重采样质量承诺、独占模式自动切换、网络播放、标签写入或播放器 UI 重设计。
- libmpv 的 Windows 构建与完整 DLL 依赖链是主要风险；native 加载、许可证材料和安装后的 DLL 搜索路径必须在代码与 CI 中验证。
- WASAPI 独占会暂时排斥其他应用；默认共享模式，独占必须由用户启用。
- 在用户真实设备上播放之前，只能报告自动化解码/安装包状态，不能宣称听感验收通过。

## 8. 依据

- mpv 官方 Windows 编译说明（包含 `libmpv-2.dll` 构建和 LGPL 配置）：<https://github.com/mpv-player/mpv/blob/master/DOCS/compile-windows.md>
- mpv libmpv C API 与许可证说明：<https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h>
- mpv WASAPI 和独占输出选项：<https://mpv.io/manual/stable/>
- FLAC 上游关于无损解码可逐位还原 PCM 的说明：<https://github.com/xiph/flac-website/blob/main/features.html>
