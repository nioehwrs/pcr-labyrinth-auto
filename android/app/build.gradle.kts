// P0 应用模块：Kotlin + Jetpack Compose，单模块 :app
// 包名/应用名见 PLAN.md §3：namespace = applicationId = com.lmj.labyrinthauto，桌面名「黎明界自动化」
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lmj.labyrinthauto"
    compileSdk = 35

    // 反馈通道：**issue 专属小号**的令牌随包分发（2026-11-01 用户方案）。
    // 安全边界：该小号对发布仓库只有"观察者"权限（实测改不了 Release），诊断包只能传到它自己的
    // `labyrinth-auto-feedback`；令牌泄露最坏是公开仓库 spam + 附件仓库乱传，动不了 APK。
    // 令牌不进版本库：读 android/gitee.properties（已 gitignore），缺文件时为空 ⇒ 反馈入口提示未配置。
    val giteePropsFile = rootProject.file("gitee.properties")
    val giteeToken: String = if (giteePropsFile.exists()) {
        giteePropsFile.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("token") && it.contains("=") }
            ?.substringAfter("=")?.trim().orEmpty()
    } else ""

    defaultConfig {
        applicationId = "com.lmj.labyrinthauto"
        minSdk = 26
        targetSdk = 35
        // 0.4.0：渠道服支持 + 多账号 + 凭证加密 + 架构自检/修复
        versionCode = 32
        versionName = "0.6.6"

        // 本阶段只保证 MuMu（x86_64）。P0 没有 native 库；
        // 等 PLAN.md §4 方案 B（内嵌 libfrida-core.so）时这条才会真正生效。
        ndk { abiFilters += setOf("x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 反馈用（见上面 giteeToken）：空的就表示这次构建不带反馈上传能力
        buildConfigField("String", "GITEE_TOKEN", "\"$giteeToken\"")
    }

    // 发布签名：读取 android/keystore.properties（本地、已 gitignore，含密码）。
    // 没有该文件时（如 CI / 协作者）跳过签名，仍可 assembleRelease（只是装不上发行包）。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps: Map<String, String>? = if (keystorePropsFile.exists()) {
        keystorePropsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { line ->
                val (k, v) = line.split("=", limit = 2)
                k.trim() to v.trim()
            }
    } else null

    signingConfigs {
        create("release") {
            if (keystoreProps != null) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    packaging {
        jniLibs {
            // ★2026-11-4：注入桥 121MB（占 APK 90%）改为**首次启动按需下载**
            //   （与 huabanxie-server 同一套：Release 附件 `libfrida_bridge-<ver>.so.gz`）。
            //   源文件仍留在 jniLibs/ 供构建/上传用，这里只是不让它进 APK。
            excludes += "**/libfrida_bridge.so"
        }
    }

    buildTypes {
        release {
            // ★2026-11-4：开 R8（体积 58MB → 预期 ~30MB，见 CHANGELOG）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true      // 反馈令牌走 BuildConfig（见 defaultConfig 的 GITEE_TOKEN）
    }
    // Kotlin 1.9.24 对应的 Compose 编译器扩展版本（官方兼容表）
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // ★2026-10-25：内嵌的 libfrida_bridge.so 有 116MB，默认以 **stored（不压缩）** 方式入包，
        // 把 APK 顶到 178MB —— Gitee Release 附件单文件上限约 100MB，根本传不上去。
        // 打开 legacy packaging 后 so 走 deflate 压缩（APK 掉到 ~70MB）；安装时解压到 lib 目录，
        // `System.loadLibrary("frida_bridge")` 照常工作，只是占用的磁盘会多一份解压后的 so。
        jniLibs.useLegacyPackaging = true
    }

    // ★应用内「更新记录」（2026-10-23 用户）：只维护仓库根目录那一份 CHANGELOG.md ——
    //   构建前复制到 build/generated/changelogAsset/，再把它挂成 assets 源目录。
    //   这样"应用里看到的"永远等于"仓库里写的那份"，不会出现两份内容不同步。
    sourceSets.getByName("main").assets.srcDir(
        layout.buildDirectory.dir("generated/changelogAsset"),
    )
}

val copyChangelogAsset = tasks.register<Copy>("copyChangelogAsset") {
    from(rootProject.file("../CHANGELOG.md"))
    into(layout.buildDirectory.dir("generated/changelogAsset"))
    // 源文件没了也别让构建失败（只是应用内不显示更新记录）
    setDuplicatesStrategy(DuplicatesStrategy.INCLUDE)
}

// assets 合并任务必须等复制完成（比只挂 preBuild 更直接，避免顺序问题）
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyChangelogAsset)
}
tasks.named("preBuild") { dependsOn(copyChangelogAsset) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    // JVM 单测要**真 org.json**：android.jar 里那份在单测里是 stub，会抛 "not mocked"
    // （P4 对拍把 PC 版生成的 fixtures 当基准，解析 JSON 是刚需）
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
