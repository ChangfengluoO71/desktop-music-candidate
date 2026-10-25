# DMC 架构基线与云端扩展边界

> 这是旧 P0 文档的替代性现状说明：2026-10-25 根据 main @ 6220796 核查。此前文档中“JavaFX 为当前播放器、FLAC 不支持”等叙述属于过时状态，不再作为开发前提。历史阶段记录仍保存在 P0-STATUS.md 和 docs/superpowers/。

## 当前功能边界

~~~mermaid
flowchart LR
  Files[Local audio files] --> Scanner[DesktopMusicScanner]
  Scanner --> Store[LocalMusicStore / SQLite]
  Store --> LocalTrack[LocalTrack / pathKey]
  LocalTrack --> Queue[PlaybackCoordinator / PlaybackQueueStore v1]
  Queue --> Engine[LibMpvPlaybackEngine / JNA]
  Engine --> Output[libmpv / WASAPI shared]
  Netease[Optional NetEase discovery] --> Download[DownloadTaskManager / downloader]
  Download --> Files
  Session[SessionManager / Credential Manager] --> Netease
  Remote[Future WebDAV source] -. planned .-> Catalog[Remote catalog / source identity]
  Catalog -. planned .-> Resolver[Authorized media stream resolver]
  Resolver -. planned .-> Queue
~~~

- 本地文件 pathKey 是现有曲库/队列主标识；不可复用为 remote URL ID。
- libmpv 当前为唯一生产播放后端，但 load() 限定可读本地常规文件；JavaFX 遗留代码不是生产回退。
- 下载相关 ID / 鉴权与网易云 Provider 耦合。本地扫描与本地播放无需登录。
- v0.0.3 发布安装包包含 CI 构建的 LGPL libmpv；本地普通 Gradle / Inno 打包流程不自动等价。

## 目标协议边界（均未实现）

~~~mermaid
flowchart LR
  UI[Unified music library / queue] --> Item[PlaybackItem]
  Item --> Local[LocalPathSource]
  Item --> Remote[RemoteSourceRef]
  Remote --> Adapter[WebDavAdapter]
  Adapter --> Credential[Isolated credential vault]
  Adapter --> Stream[Range-aware stream resolver]
  Stream --> Proxy[Loopback / session-scoped media bridge]
  Local --> Engine[libmpv playback engine]
  Proxy --> Engine
  Engine --> Audio[WASAPI shared]
~~~

推荐最小模型：

- PlaybackItem = { itemId, title, artist, sourceRef, metadata }; itemId 是 DMC 内稳定 ID，不以 URL、路径或网盘凭据为键。
- SourceRef = LocalPath(localTrackId) | WebDavObject(sourceId, relativePath, etag?, size?, lastModified?)；保留演化至其他 Provider 的可能。
- CloudSourceConfig 保存 URL、显示名、认证方式和**凭据引用**，不把密码/令牌写进曲库或播放队列。
- WebDavAdapter 负责目录列举和协议探测；StreamResolver 负责运行期获取、刷新和安全访问；PlaybackEngine 只消费已解析的可播放输入。
- PlaybackQueueStore v1 必须仍能被新读取器加载；迁移采用新增版本、原子写入、失败回滚，不改写旧格式后丢失本地歌曲。

## 关键防护

HTTPS 默认为必需，HTTP 仅允许用户主动配置的受信任环回地址；禁止证书验证关闭作为默认解法。对来自服务器的 href 和 redirect 限制 origin/网盘根目录与文件类型，不在跳转时透传 Authorization。远端请求有并发限制/超时/大小限制；元数据解析防 XML 外部实体和 XML 炸弹；可视化日志不记录完整认证 URL 或 Header。

具体协议行为、POC 验收矩阵和不做清单见 [CLOUD-PLAYBACK-DESIGN.md](CLOUD-PLAYBACK-DESIGN.md)。