# DMC

**DMC** 是面向 Windows 的独立桌面音乐播放器。产品以离线本地曲库为基础，提供本地播放、音乐管理，以及可选的在线发现和下载。WebDAV / 云端音乐播放属于**规划中**，当前版本尚未实现。

> 本说明依据 GitHub main 在 2026-10-25 审阅时的 HEAD 6220796220cb10323b248e54945e2a2855b3b177，及 2026-10-17 发布的 v0.0.3。代码、自动化、发布产物与人工 GUI 验收是不同证据，不能相互替代。

## 已实现的核心模块

- 本地音乐目录扫描、SQLite 索引、标签与音质参数读取、重复分析，及可选的目录变更监听。
- libmpv / JNA 播放器（Windows WASAPI shared）；本地队列、迷你播放器、正在播放页面、歌词及曲库视图。
- 沉浸式播放器相关布局/歌词/背景代码与测试已进入仓库；各缩放比例及真实设备体验仍需人工验证。
- 网易云搜索、发现、歌单/收藏导入、可选扫码会话、下载、歌词和封面处理；与无需登录的本地曲库分离。
- GitHub 检查更新、安装包下载及两项独立的镜像设置；下载来源和校验规则参见 [更新文档](docs/UPDATE-MIRRORS.md)。

**重要的播放限制：** 当前 libmpv 实现仍是**本地文件专用**。PlaybackEngine.load 接收 LocalTrack，原生引擎检查本地常规文件，队列持久化依赖 pathKey。现有版本没有 WebDAV 浏览、云盘认证、HTTP 流式播放或云端队列恢复功能。

## 发布与构建

最新已核实的 GitHub Release 是 [v0.0.3](https://github.com/ChangfengluoO71/desktop-music-candidate/releases/tag/v0.0.3)（2026-10-17）。**官方发布工作流生成的 Windows Inno Setup 安装包**包含固定构建的 LGPL libmpv 运行库、相应源码和许可告知，以及 Java 运行时。Release 同时提供校验和运行库清单。不要把这一点与**本地直接执行 packageExe / createDistributable** 或不带 IncludeReleaseMpv 参数编译安装包混为一谈；这些命令不会自动复现官方发布运行库的打包过程。

本地开发与安装说明：[BUILDING.md](docs/BUILDING.md)。Gradle 缺省开发版本仍为 0.1.2，发布工作流通过 releaseVersion 显式设置 0.0.3；请勿混用两种版本口径。

## 接下来开发什么

优先顺序：**发布版真实验收和文档基线 → WebDAV 只读协议 POC → 统一云端播放源与队列 → 云端曲库 / 缓存 → 可选原生网盘服务适配**。

- [当前事实与未验收事项](docs/PROJECT-STATUS.md)
- [产品开发路线图](docs/ROADMAP.md)
- [WebDAV 和云端音乐技术方案](docs/CLOUD-PLAYBACK-DESIGN.md)
- [播放引擎与迁移边界](docs/PLAYER-P1.md)
- [架构与身份解耦](docs/P0-ARCHITECTURE.md)
- [产品品牌与安装身份](docs/PRODUCT-IDENTITY.md)
- [构建和安装](docs/BUILDING.md)
- [仓库、CI 和发布](docs/GITHUB_PRIVATE_PREP.md)
- [来源与第三方许可证](UPSTREAM.md) · [第三方许可清单](THIRD_PARTY.md)

## 产品与许可声明

DMC 是独立项目，不是 Hachimi 官方桌面版本。文档品牌为 DMC，但可执行文件、数据目录和部分包名仍保留 DesktopMusicCandidate；不得仅为改名破坏旧安装、下载路径或 Windows Credential Manager 凭据。仓库代码以 [LICENSE](LICENSE) 和 [THIRD_PARTY.md](THIRD_PARTY.md) 列出的各组件许可为准。