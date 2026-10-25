# DMC 构建、验收与发布边界

## 环境

- Windows，JDK 25（仓库现有发布工作流使用 Temurin 25），Gradle Wrapper 9.3.1；Kotlin JVM/Compose Desktop。
- Inno Setup 6 用于公开 Windows 安装包；不要把 Gradle packageExe 的生成物与公开 Inno Setup 安装包混称。
- 测试默认使用本地 fixture，无需账号。播放器 native WASAPI 测试需要匹配的 Windows libmpv。

## 开发命令（PowerShell）

~~~powershell
.\gradlew.bat --no-daemon test --rerun-tasks
.\gradlew.bat --no-daemon run
.\gradlew.bat --no-daemon createDistributable
.\scripts\prepare-libmpv.ps1
.\gradlew.bat --no-daemon nativeMpvShared
~~~

scripts/prepare-libmpv.ps1 提供仅用于本机开发/测试的固定哈希 runtime，放入应用私有目录。这不是公开发布流水线所打包的对应源码审计版。有关来源与 SHA-256 记录：[libmpv runtime](testing/libmpv-runtime.md)。

## 版本号容易混淆的地方

build.gradle.kts 缺省的 releaseVersion 仍是 **0.1.2**；仓库最新公开 GitHub Release 是 **v0.0.3**，其工作流执行 createDistributable 时显式传入 -PreleaseVersion=0.0.3 / -PupdateChannel=stable。Gradle 输出的本地文件名不是最新公开发行版的证据。

## 公开安装包流程

.github/workflows/desktop-ci.yml 的 workflow_dispatch（release_version、update_channel、publish_release）构建固定的 LGPL libmpv 与相应源码归档、检查 DLL 和依赖许可、运行本地原生样本测试，然后使用 Inno Setup 的 **/DIncludeReleaseMpv** 构建安装包，并做安装卸载烟雾测试；可重用受校验的先前 native runtime 工作流产物。Release 包含 EXE、对应源码包、运行时清单和 SHA256SUMS.txt。

普通 createDistributable、packageExe、packageMsi **不会自动包含发布版 libmpv**；手工调用 ISCC 而没有 /DIncludeReleaseMpv，同样不是官方发布流程。不要把未经审计的开发 DLL 塞入公开安装包。

完整发布说明见 [CI 状态](GITHUB_PRIVATE_PREP.md) 与 [第三方许可清单](../THIRD_PARTY.md)。CI 的发布步骤不意味着客户端真实听音、升级保留数据和高 DPI 验收已经完成。

## WebDAV 开发提示

WebDAV 的协议测试应使用不含真实账号的模拟服务器及单独的测试账号。不要上传用户名、密码、Cookie、缓存、播放签名 URL、抓包日志或真实音乐文件到 Git。云端模块加入后必须保持完全离线本地曲库测试独立通过。