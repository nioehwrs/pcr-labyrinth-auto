// 顶层构建文件：只声明插件（版本都在 gradle/libs.versions.toml 里，与 limingjie-assistant 对齐）。
// 版本：AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 / JDK 17（见 PLAN.md §3）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
