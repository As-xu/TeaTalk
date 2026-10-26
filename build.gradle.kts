// 只声明“多个模块共用的”插件；插件版本来自 gradle/libs.versions.toml。
// KSP 只在 :app 中使用（Room 代码生成），所以不在根工程声明：
// 这样单独构建 :design 时不会去解析 KSP 插件标记，避免镜像抖动拖垮整次构建。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
