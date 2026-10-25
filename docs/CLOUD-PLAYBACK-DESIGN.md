# DMC WebDAV 与云端音乐播放：调研、技术决策及验收

**状态：PROPOSED / NOT IMPLEMENTED。** 2026-10-25 调研；基线 main @ 6220796，Release v0.0.3。本文只给出可审查的设计与门禁；没有做过真实 WebDAV/115 账户联机验收。

## 1. 目标及范围

DMC 在不破坏已有本地音频播放器、SQLite 索引、网易云下载记录和安装凭据的前提下，提供**只读** WebDAV 目录浏览与**无需预先整首下载**的云端播放。首批协议场景：标准 WebDAV（例如 NAS / Nextcloud）、rclone serve webdav、OpenList 对外 WebDAV；115 / 阿里 / 夸克等经用户自行配置网关的间接接入仅在各自网关可用、合规和资源可取流时成立，不能概括为 DMC 对所有网盘的原生支持。

优先支持 MP3、FLAC、M4A/AAC、WAV；实际播放取决于编码细节、服务器响应与 libmpv 构建，不能用扩展名承诺兼容。

## 2. 协议依据

- WebDAV RFC 4918：PROPFIND Depth:0 / Depth:1、207 Multi-Status、resourcetype、getcontentlength、getlastmodified、getetag，限制深层递归及解析尺寸。https://www.rfc-editor.org/rfc/rfc4918
- HTTP RFC 9110 第 14 节：Range: bytes，206 Partial Content / Content-Range，服务器可以忽略 Range 返回 200；不能仅凭 Accept-Ranges 头假定 seek 一定成功。https://www.rfc-editor.org/rfc/rfc9110.html
- libmpv 的 HTTP URL、自定义请求头和缓存选项确实存在，但现有 DMC engine 只加载 Files.isRegularFile 的 LocalTrack；需要真实改造后才能使用。https://mpv.io/manual/stable/
- OpenList 当前文档描述了 /dav/ 只读读取权限及多存储 WebDAV 映射。https://doc.oplist.org/guide/advanced/webdav
- OpenList 115 Cloud **旧非官方驱动**文档标明不主动维护，存在会话和兼容风险；另有 115 Open 接入路径但需要正式凭据，不应假定普通用户都有。https://doc.oplist.org/guide/drivers/115 与 https://doc.oplist.org/guide/drivers/115_open
- rclone serve webdav 使用 VFS 并具有缓存行为，适合作为独立 mock 以外的真实兼容测试服务。https://rclone.org/commands/rclone_serve_webdav/

## 3. 推荐的最小实现

### C1：WebDAV 只读适配器

WebDavSourceConfig：sourceId、baseUrl、displayName、authMethod、credentialRef、readOnly、networkPolicy；SQLite 只保存 credentialRef，秘密使用 Windows Credential Manager（需新建独立 target / scope）。连接向导要求用户显式输入 WebDAV endpoint，不收集网盘本体 Cookie。

WebDavAdapter：listChildren(remoteDirId) 通过 PROPFIND Depth:1（不使用默认 infinity）；stat(file) 优先 PROPFIND Depth:0；parseResponse 仅接受已验证根目录内 href，正确处理多状态码、实体解码、中文/空格、相对与绝对 URI。根目录不允许服务端返回的 ../ 穿越，禁止 XML DTD/外部实体，设置响应字节和目录条目上限。优先懒加载目录而非递归索引整库。

初期元数据从 DAV 属性与文件名推断；远端标签/封面只对用户选定曲目进行有界读取。不要为扫描全部曲目触发整首远端下载。

### C2：可 seek 的 libmpv 播放流

必须先改变现有类型边界：PlaybackItem(itemId, metadata, sourceRef) / SourceRef = LocalPath | WebDavObject。LocalTrack / pathKey 不变，只通过 adapter 映射到 PlaybackItem。PlaybackEngine 应加载受验证的 ResolvedPlaybackInput，而不是直接强制 LocalTrack；保持原本本地生产行为及回归测试。

**首选：应用控制的受限环回 HTTP Range 桥接器。** 业务层用 OkHttp 对 WebDAV 发出授权 GET/Range，解析状态和 ETag，对 libmpv 暴露 127.0.0.1 的短期一次会话 URL。使用随机不可预测的会话令牌、仅 loopback 绑定、限制允许方法/源/路径、关闭后立即失效，绝不对外网监听；不得把 Basic 密码、Cookie、长期 Token 或签名 URL 传进 mpv 命令或日志。桥接器对 Content-Range、206/416 做正确映射、背压、取消和有界分块。

备选：libmpv 直接加载远端 HTTPS，并设置 per-track header/cookie；只有在证明跨源跳转、日志/异常与并发会话无秘密泄露时再考虑。绝不使用系统盘符映射作为唯一播放实现，也不默认全量下载落盘。

Range 探测以实际小范围 GET 为准：收到 206 且校验 Content-Range 才标记 SEEKABLE；收到 200 视作不支持字节定位，不假装 seek 有效；收到 416/401/403/404/429/5xx 清楚分类，不使用无限自动重试。切歌取消前一会话的远端请求，seek 优先重用/取消范围连接而非重复全曲拉取。

### C2：队列版本迁移

当前 PlaybackQueueStore v1 仅按 pathKey 从 availableTracks 恢复。新版本保存 itemId、sourceKind、sourceId、objectId（远端相对路径可作可变 locator）和非敏感标题；**不存 bearer token、密码、临时直链或 loopback URL**。v1 旧记录仍可无损读取；新写入用原子替换，迁移失败保留旧记录并显示提示。队列播放启动时重新校验 source 在线、认证未失效以及文件版本；云端曲目离线或被删除时跳过，用户仍可查看条目及错误原因。

## 4. 网络安全与性能策略

- 默认仅 HTTPS；除非用户主动开启明确的 localhost/127.0.0.1 例外，否则拒绝 HTTP。证书错误不允许静默忽略。
- 来源 URL 和登录跳转时的 Authorization 不跨 origin 泄露；不默认追踪任意 302，校验 URL scheme/host、重定向次数，防 SSRF、DNS rebinding；本地用户显式输入的局域网 DAV 需单独受控权限策略。
- 不在日志、诊断、异常、持久化队列及 UI 复制按钮泄露凭据、签名 URL 或 token。
- 限制连接超时、请求并发、PROPFIND 响应体、目录数量、标签封面字节、预读和磁盘缓存配额；音频采用有界 Range/流式背压，优先避免重复下载。
- HTTP 401/403 提示重新授权；404 视曲目失效；416/200 表示定位能力变化；429 遵守 Retry-After；5xx/超时使用有限指数退避，避免整库风暴。
- 音频缓存属于用户数据，可清理、可限制、账号解除绑定可删除；不缓存明文密码，不将离线缓存行为等同于完整下载权利。

## 5. 最小验收计划

| 门 | 测试场景 | 合格证据 |
| --- | --- | --- |
| G0 | 当前 v0.0.3 安装版、回归、本地队列 v1 | 真实 Windows 运行/安装记录及基础测试不回退 |
| G1 | WebDAV 207 / Depth:1 / 中文文件名 / 大目录 / 401 | Mock 集成与一台标准服务器通过；只读，无 DAV 写请求 |
| G2 | GET 206 + Content-Range / 不支持 Range 返回 200 / 416 | 实际字节请求与日志（已脱敏），精确 seek 或明确降级 |
| G3 | MP3、FLAC、M4A、WAV，连续播放/seek/切歌/断网 | 标注服务器、网络环境、libmpv 版本与听音/位置/错误证据 |
| G4 | 任意重定向、跨源认证、过期链接、关闭后旧会话 URL | 密码不泄露、旧链接失效、无多余连接/临时文件 |
| G5 | v1 队列恢复、新云端队列恢复、远端文件被移走 | 不破坏老曲库/下载 JSON，恢复后不自动错误播放 |
| G6 | OpenList 的一个**合法配置且可真实取流**的网盘 | 独立于标准 DAV 测试；缺失能力标注 BLOCKED 而非通用 PASS |

建议测量项（不是已实现指标）：首声延迟 p50/p95、seek 回应 p95、每分钟请求次数、远端拉取字节/完整文件大小之比、封面/缓存占用、取消后的活动请求数。先形成真实基线再定阈值；POC 可以用可控局域网环境以 p95 首声 ≤3 秒、seek ≤2 秒作为**目标**，公网不沿用相同 SLA。

## 6. 不在第一阶段做的事

不做原生 115 Cookie 逆向、不做网盘上传删除/文件移动、不做所有网盘的登录聚合、不做自动将全库缓存到本地、不做远端文件标签写回、不做跨源智能去重、不做背景转码服务、不做密码绕过、不承诺所有 NAS 或网盘都支持 seek。

若之后需要音乐服务器目录、丰富元数据和服务端播放列表，可独立评估 **OpenSubsonic / Subsonic** 适配器；它与 WebDAV 是两个协议层，不应相互冒充。Navidrome 的官方兼容说明：https://www.navidrome.org/docs/developers/subsonic-api/ （如页面迁移，参阅 https://github.com/navidrome/website）。

## 7. 建议执行顺序与停机条件

C0 现有安装版收口 → C1 只读客户端 + 模拟服务 → C2 单曲流式 POC → C2 可迁移队列和 UI → C3 元数据与缓存 → C4 网盘能力矩阵。若 Range / 授权 / redirect / 安全门禁无法关闭，保持功能实验标记，不允许合入生产主播放链路。此设计只授权增加独立适配层及必要的向后兼容抽象，不授权重构网易云下载业务。