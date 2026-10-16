# DMC 项目状态

快照：2026-10-16。R3 集成播放器与本地曲库代码已完成；历史全量测试、Windows EXE 构建和本机固定版 libmpv 原生验收通过。当前源码窗口已可见，专辑/歌手卡片操作已改为封面悬浮按钮；本轮尚未手动点击验收这些操作。

## 仓库与构建

- 仓库：ChangfengluoO71/desktop-music-candidate，公开仓库；当前分支 main。
- 本轮起始基线：`918b4a199b9c0b26dfa0d28f7f674e4024655c33`（`main`）。
- 本轮未 Merge、Release；未修改 Android 端。
- 构建使用 JDK 25，字节码目标保持项目配置。
- 现有预览发布、安装身份、用户数据目录和在线模块不在本轮修改范围内。

## R3 实施状态

- 播放器入口进入 DMC 主窗口“正在播放”页面，迷你播放器跨页面保持显示；不再通过独立 DialogWindow 展示完整播放器。
- Now Playing 随窗口尺寸切换并排或堆叠布局；歌词支持时间点跳转，手动滚动暂停跟随和显式恢复。
- libmpv 继续作为唯一生产播放器，固定 WASAPI shared，设置 audio-display=no。无回退到 JavaFX 的逻辑。
- 本地音乐库新增专辑、歌手、歌曲和文件夹浏览，网格自适应、歌曲列表虚拟化、排序和文件夹筛选。
- 专辑/曲目支持播放、播放下一首和加入队列；封面仓库共享提取、限制 64 MiB 解码缓存、并发去重和变更失效。
- 目录监听可按变更路径增量更新，等待复制文件稳定，并在监听丢失/溢出后做根目录恢复扫描。

## 验证结果

| 类型 | 结果 | 证据 |
|---|---|---|
| JDK 25 全量自动化 | PASS | `./gradlew.bat --no-daemon test --rerun-tasks`：298 tests，49 reports，0 failures/errors/skips |
| Windows EXE 构建 | PASS | `./gradlew.bat --no-daemon packageExe`：生成 `build/compose/binaries/main/exe/DesktopMusicCandidate-0.1.2.exe` |
| 本轮源码启动 | PASS | JDK 25 下 `./gradlew.bat --no-daemon run`；窗口标题 `Desktop Music Candidate`，已捕获本地曲库窗口画面 |
| 本次原生 libmpv 验收 | PASS | JDK 25.0.2，`nativeMpvShared` 2 tests passed；MP3/FLAC/M4A/WAV 使用 WASAPI 并前进；嵌入封面无额外窗口 |
| 卡片操作 GUI 交互 | NOT RUN | 画面确认播放与更多操作图标显示在封面上；本轮未点击菜单项、未验证队列结果或高 DPI |
| 全量自动化（本轮） | NOT RUN | 沿用上一次 298 项结果；本轮更新了卡片操作测试步骤，但未执行测试套件 |
| 安装/升级/卸载 | NOT RUN | 本轮只构建 EXE，未运行安装器 |

R2 历史记录单独保留于 `docs/testing/libmpv-r2-windows-acceptance.md`；它不能替代 R3 对 audio-display=no 和嵌入封面窗口行为的验收。

## 运行与依赖边界

- 播放器依赖用户提供受支持的 libmpv 运行库；当前 EXE 构建未包含该运行库。
- JavaFX 旧实现和依赖尚在仓库中作为迁移残留，但生产入口使用 libmpv；删除残留放到独立审核。
- 本地播放器不需要登录；在线网易云搜索、登录和下载与本地曲库保持分离。

## 下一步

1. 手动验收专辑/歌手封面上的播放按钮与更多菜单，确认“下一首播放”和“加入播放队列”结果。
2. 在 100%、125%、150% 和 200% Windows 缩放下检查封面卡片、弹出菜单、Now Playing 和歌词布局，并验证键盘焦点与禁用态。
3. 完成安装版播放验收；当前安装包未携带 libmpv，需先明确运行库许可与分发方案。
4. 视觉验收通过后，进入沉浸式 Now Playing 设计：封面氛围背景、主封面、歌词/翻译/罗马音和清晰的播放控制；先提交设计稿，再实施。
5. 随后再规划 P2：收藏/历史/播放列表、媒体键、播放设备和播放行为设置。

## 卡片操作改版（2026-10-16）

- 专辑和歌手卡片不再在每张封面下方铺开三个宽按钮；改为封面右下角播放图标，以及右上角更多菜单。
- 更多菜单提供“下一首播放”和“加入播放队列”；卡片主体继续执行原有专辑/歌手筛选行为。
- JDK 25 源码窗口已启动并捕获画面；自动化测试步骤已跟随菜单交互更新，但本轮未运行测试，也未人工点击验收。
- 本次记录归档见 `docs/archive/2026-10-16-local-library-card-actions.md`。

## libmpv 本机运行库跟进（2026-10-14）

- 根因：当前预览包不含 libmpv，本机用户数据目录原先也没有 `libmpv-2.dll`；播放器按既有安全逻辑只从显式绝对路径或应用私有目录加载，因此初始化时无法播放。
- 已新增 `scripts/prepare-libmpv.ps1`，固定上游 Windows x64 开发归档并校验 SHA-256，再安装到 `%APPDATA%\DesktopMusicCandidate\libmpv`。归档与运行库留在用户目录，不进入 Git 或安装包。
- `nativeMpvShared` 已改为默认发现上述 DLL 与缓存归档；需 JDK 21+，项目使用 JDK 25 验证。JDK 17 错误会在任务执行前明确提示。
- JDK 25.0.2 原生验收：2 tests passed；MP3、FLAC、M4A/AAC、WAV 均使用 WASAPI 并前进；嵌入封面测试未生成额外可见窗口。具体来源和哈希见 `docs/testing/libmpv-runtime.md`。
- 这只关闭本机 libmpv 原生验收项；人工 GUI 控件、安装版播放、发布二进制许可审核仍未完成。
- 随后使用 JDK 25 启动源码应用，Windows 进程模块检查确认已从 `%APPDATA%\DesktopMusicCandidate\libmpv\libmpv-2.dll` 加载运行库；未使用临时 `dmc.libmpv.path` 覆盖。
