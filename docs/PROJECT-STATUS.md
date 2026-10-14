# DMC 项目状态

快照：2026-10-14。R3 集成播放器与本地曲库代码已完成；R3 自动化测试和 Windows EXE 构建通过。实际窗口交互及固定 libmpv 运行库验收仍阻塞，不能据此宣布 P1 全部验收通过。

## 仓库与构建

- 仓库：ChangfengluoO71/desktop-music-candidate，公开仓库；当前分支 main。
- 本轮基线：`c76e52ca4e5b4f9905f30326e727e7942e04dbe3`。
- 本轮未 Push、Merge、Release；未修改 Android 端。
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
| 源码启动 | PASS | `./gradlew.bat --no-daemon run` 成功启动应用进程 |
| 本次原生 libmpv 验收 | BLOCKED | 检索未找到固定版 `libmpv-2.dll` 与归档，未运行 `nativeMpvShared` |
| 本地 GUI 交互 | GUI_BLOCKED | 当前源码窗口已启动；Computer Use 的必需文档 API 在此会话不可用，因此未执行窗口操作或高 DPI 验收 |
| 安装/升级/卸载 | NOT RUN | 本轮只构建 EXE，未运行安装器 |

R2 历史记录单独保留于 `docs/testing/libmpv-r2-windows-acceptance.md`；它不能替代 R3 对 audio-display=no 和嵌入封面窗口行为的验收。

## 运行与依赖边界

- 播放器依赖用户提供受支持的 libmpv 运行库；当前 EXE 构建未包含该运行库。
- JavaFX 旧实现和依赖尚在仓库中作为迁移残留，但生产入口使用 libmpv；删除残留放到独立审核。
- 本地播放器不需要登录；在线网易云搜索、登录和下载与本地曲库保持分离。

## 下一步

1. 恢复固定版 libmpv DLL/归档并完成 nativeMpvShared，记录哈希、WASAPI 和无额外窗口结果。
2. 完成真实桌面操作和高 DPI 清单，分别核对主窗口播放、队列、歌词、搜索焦点及四种曲库浏览。
3. 在运行库许可、来源和安装包方案确认后，做真实安装版验收。
4. 根据使用反馈规划 P2：收藏/历史/播放列表、媒体键、播放设备和播放行为设置。
