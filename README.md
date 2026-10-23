# TeaTalk（Android 客户端）

「去服务端」的移动端角色扮演客户端：原生 Android + 本地 Prompt 引擎，直连大模型 API。
数据与 SillyTavern 兼容（角色卡 / 世界书 / 聊天记录可导入导出），**不依赖 ST 服务端**。

## 模块

| 模块 | 说明 |
|---|---|
| `:core` | 纯 Kotlin（JVM）引擎：角色卡解析、世界书、Prompt 管线、宏、预算、SSE 解析。无 Android 依赖，可单测。 |
| `:app` | Compose UI + 导航 + 存储 + 网络接线。 |

## 本机构建（Windows，无 Android Studio 也可）

本机已知限制（2026-10-24 实测）：

1. **没有全局 gradle**：用随仓库外的 `.tools/gradle-8.9`（或自行 `gradle wrapper` 后换 `gradlew`）。
2. **`GRADLE_USER_HOME` 必须指向工作区内**（例如 `D:/Project/TeaTalkApp/.gradle-home`），
   否则默认的 `C:\Users\<you>\.gradle` 会被沙箱拒绝，报 `native-platform.dll` 加载失败。
3. **`gradle` 官方源不可达**：`settings.gradle.kts` 已把阿里云/腾讯镜像排在前面。
4. `ANDROID_HOME` 未设置时用 `local.properties` 里的 `sdk.dir`。

```bash
export JAVA_HOME="D:/DevEnv/java/jdk21/ms-21.0.9"
export GRADLE_USER_HOME="D:/Project/TeaTalkApp/.gradle-home"
export ANDROID_HOME="D:/DevEnv/Android/Sdk"
/d/Project/TeaTalkApp/.tools/gradle-8.9/bin/gradle :app:assembleDebug --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 文档

- 方案与路线：`../docs/00-技术方案与演进路线.md`
- 开工清单：`../docs/01-M0-开工清单.md`
- 调研：`../docs/research/`
