# 开发环境（Windows + Android Studio）

> 目标：任何人拿到仓库 + 本机这套环境，能在 Android Studio 里一键 Sync/Build，或命令行 `build.sh` 出包。
> 最后验证：2026-10-25（Gradle wrapper 8.9，AGP 8.6.1，`:app:assembleDebug` 通过）。

## 1. 已验证的本机工具链

| 组件 | 版本 / 位置 | 说明 |
| --- | --- | --- |
| Android Studio | `AI-261.23567.138.2611.15503007`（2026.1.1），`D:\ProgramFiles\Android\Android Studio` | 用内嵌 JBR 21 或下面的 JDK 21 均可 |
| JDK | `21.0.9`（Microsoft Build），`D:\DevEnv\java\jdk21\ms-21.0.9` | Gradle 和 AGP 都要求 JDK 17+；统一用 21 |
| Android SDK | `D:\DevEnv\Android\Sdk`（写进 `local.properties` 的 `sdk.dir`） | 需要 `platforms;android-34`、`build-tools;34.0.0`、`platform-tools`、`emulator` |
| Gradle | wrapper 8.9（`TeaTalk/gradle/wrapper/gradle-wrapper.properties`） | 仓库自带 `gradlew` / `gradlew.bat`，**不要用 AS 默认的 9.x** |
| Kotlin / AGP | 2.0.21 / 8.6.1（`gradle/libs.versions.toml`） | KSP 2.0.21-1.0.28，Room 2.6.1 |
| adb | `D:\DevEnv\Android\Sdk\platform-tools\adb.exe` | 真机 `PQY0221205009539`（NOH-AN00 / API 31）、模拟器 `emulator-5554`（AVD `teatalk34`） |
| 签名 | `TeaTalk/.keystore/debug.keystore`（仓库内，debug 用） | release keystore 未建 |

## 2. 两种构建方式

```powershell
# 方式 A：Android Studio 打开 D:\Project\TeaTalkApp\TeaTalk → Sync → Run
#   注意：Settings > Build, Execution, Deployment > Build Tools > Gradle
#         Gradle JDK = 21（或 Embedded JDK 21）
#         File > Project Structure > SDK = Android API 34

# 方式 B：命令行（等价于 CI）
cd D:\Project\TeaTalkApp\TeaTalk
$env:JAVA_HOME = 'D:\DevEnv\java\jdk21\ms-21.0.9'
$env:ANDROID_HOME = 'D:\DevEnv\Android\Sdk'
.\gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# 方式 C：仓库根目录脚本（自动找 JDK/SDK，缺 Gradle 会提示）
D:\Project\TeaTalkApp\build.sh
```

> 首次 `gradlew` 会下载 Gradle 8.9 到 `%USERPROFILE%\.gradle\wrapper\dists`（已配置腾讯镜像，约 136MB）。
> 命令行如果设置了 `GRADLE_USER_HOME=D:\Project\TeaTalkApp\.gradle-home`，会复用项目内缓存，和 AS 的 `~/.gradle` 相互独立——两边各下一次，属正常。

## 3. 网络与镜像（本机实测）

| 源 | 状态 | 处理 |
| --- | --- | --- |
| `services.gradle.org` / `downloads.gradle.org` | ❌ 不可达（307 → github 超时） | wrapper 已指向 `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` |
| Google Maven（`dl.google.com`） | ✅ 通（~150ms） | 仓库顺序：阿里云 google → 阿里云 public → google() → mavenCentral() |
| Maven Central | ✅ 通（~800ms） | 同上 |
| 阿里云 Maven | ✅ 快（~30ms） | 已在 `settings.gradle.kts` 置顶 |
| npm / node fetch | ✅ 通 | 下载大文件时用 node 脚本（curl/PowerShell SSL 会失败） |

## 4. 模拟器与真机

- AVD：`teatalk34`（API 34，x86_64 google_apis，Pixel 5 外观，1080×2340），已创建。AS Device Manager 里可直接启动；无窗口启动：`emulator -avd teatalk34 -no-window -gpu swiftshader_indirect`
- 真机：华为 NOH-AN00（HarmonyOS 4.2 / API 31），USB 调试已开、`adb devices` 可见
- 多设备时指定：`adb -s <serial> install -r app-debug.apk`

## 5. 需要你（项目所有者）提供的

| 项 | 为什么需要 | 现在是否阻塞 |
| --- | --- | --- |
| GitHub 仓库 `https://github.com/As-xu/TeaTalk` | 社区开源；已配置 `origin` 并与远程同步 | ✅ 已就绪 |
| M1 模型接口：`TeaTalk/key.json`（`base_url` / `model_name` / `api_key`） | 联调流式聊天与真实回复 | ✅ 已提供（本地文件，已 gitignore） |
| Release keystore 的口令与放置位置 | 出正式包 / 自更新 | 不阻塞 M0/M1 开发，阻塞首次发布 |
| （可选）第二台设备或平板 | 适配 minSdk 28 的窄屏/大屏 | 不阻塞 |
| （可选）10 张「边界卡」：超长名字、超大世界书、CHARX、带表情的 V3 卡 | 导入鲁棒性回归 | 不阻塞（已有 29 张真实卡） |

## 5.1 密钥管理

- 真实的 `key.json` 只放本地，`.gitignore` 已忽略；仓库里只提交 `key.example.json` 模板。
- 读取方：App 运行时不读这个文件；M1 联调时由构建脚本/命令行注入（或本地 debug 配置读取后不写日志）。
- 若曾误提交：立刻轮换 key，再用 `git filter-repo` 清理历史。

## 6. 已知坑（本机踩过，已规避）

1. **Gradle 官方源不可达** → wrapper 用腾讯镜像；AS 提示下载失败就检查 `gradle-wrapper.properties` 是否是 9.x（旧值）。
2. **新建目录要由 git / Android Studio 创建**：aapt2 写 `stableIds.txt` 报 `数据无效 (13)`，根因是目录带 `Low` 完整性标签；用 `git checkout`/AS 重建目录即可（详见 `docs/01-M0-开工清单.md` 修复记录）。
3. **KDoc 注释里不能出现 `*/`**（例如写 `*/*` 的 MIME 通配），会提前闭合注释导致编译失败。
4. **沙箱/杀软**：本机 DSH 早期在 `workspace-write` 下会拦 aapt2 写文件；现在 `danger-full-access`。AS 里若出现 "Access is denied" 写 build 目录，先排除杀软对 `%TEMP%` 的拦截。
5. **`img` 下载/截图**：测试卡、截图放在 `.tools/`（不入库）；仓库里的截图在 `docs/screenshots/`。

6. **阿里云镜像 502 会拖垮整次构建**：`maven.aliyun.com/repository/public` 偶发对个别构件（本次是 `com.google.devtools.ksp.gradle.plugin`）返回 502，Gradle 会因此禁用整个仓库并直接 BUILD FAILED（症状是 `:design` 一堆任务同时失败、根因只有一条 `Received status code 502`）。
   - 已修：`settings.gradle.kts` 改为**官方源优先**（google / mavenCentral / gradlePluginPortal），阿里云镜像只做兜底并加 `content` 过滤；
   - 同时把 `ksp` 插件从不必要的根工程 `plugins {}` 移除（只在 `:app` 声明），单独构建 `:design` 不再解析 KSP。
7. **Android Studio 构建请用 Gradle wrapper**：`gradle/wrapper/gradle-wrapper.properties` 指向腾讯镜像 8.9；AS 里 `Settings → Build Tools → Gradle` 选 “Use Gradle from: gradle-wrapper.properties”，Gradle JDK 选 17+（本机用 21）。
