import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tvflyshare.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvflyshare.app"
        minSdk = 21
        targetSdk = 29
        // 统一版本号（2026-10-05 用户明确）：只维护 APP_VERSION_NAME 一个号，
        // versionCode 由它自动推导（X.Y.Z -> X*10000 + Y*100 + Z），禁止再单独手写 versionCode。
        // 例：2.4.0 -> 20400。推导值单调递增，应用内更新比较逻辑不受影响。
        val APP_VERSION_NAME = "2.6.19"
        versionName = APP_VERSION_NAME
        versionCode = APP_VERSION_NAME.split(".").let { it[0].toInt() * 10000 + it[1].toInt() * 100 + it[2].toInt() }

        // 更新配置双源（2026-11-06）：Gitee 优先（国内 TV 可达、仓库文件固定地址），GitHub Releases 兜底。
        // 两者都可由本机 gradle.properties 覆盖；开源默认值公开，不含任何私有地址。
        val updateJsonUrl = project.findProperty("UPDATE_JSON_URL") as String?
            ?: "https://gitee.com/kakyla/FlyShare/raw/master/update.json"
        val updateJsonUrlFallback = project.findProperty("UPDATE_JSON_URL_FALLBACK") as String?
            ?: "https://github.com/kakyla/FlyShare/releases/latest/download/update.json"
        buildConfigField("String", "UPDATE_JSON_URL", "\"$updateJsonUrl\"")
        buildConfigField("String", "UPDATE_JSON_URL_FALLBACK", "\"$updateJsonUrlFallback\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    // 签名配置（2026-11-05 开源准备）：签名密钥不入库，别人 clone 后用他们自己的签名即可编译。
    // 仅当本机提供了完整签名属性时才创建正式签名配置；缺配置时 debug 走 Android 默认调试签名，
    // release 产出未签名包（能编译验证，但不能用于发布）。本机 gradle.properties 齐备，出正式包照常签名。
    val hasReleaseSigning = listOf(
        "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"
    ).all { project.findProperty(it) != null }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(project.findProperty("RELEASE_STORE_FILE") as String)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        debug {
            // 不指定 signingConfig：使用 Android 默认调试签名，开源 clone 无需任何密钥即可编译安装
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        // 零技术债硬门槛：任何编译警告都视为错误，防止技术债悄悄回潮
        allWarningsAsErrors = true
    }

    buildFeatures {
        compose = true
        // 2026-11-05 开源准备：显式开启 BuildConfig 生成（AGP 8.x 默认可能不生成），
        // 供 UpdateRepository 通过 BuildConfig.UPDATE_JSON_URL 注入更新源地址
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // 单元测试：使用 Robolectric 在 JVM 侧提供 Android 框架实现
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// 构建产物命名（2026-10-23 用户明确）：release APK 直接按发布名输出为 FlyShare-X.Y.Z.apk。
// 起因：build/outputs/apk/release 里默认叫 app-release.apk，人工上传时极易拿错，
// 而 update.json 的 apkUrl 写死 FlyShare-X.Y.Z.apk，名字对不上就是线上 404、手机永远升不了级。
// 这样构建完那个目录里就是可直接上传的正确文件，release/ 与构建目录命名完全一致。
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)
                ?.outputFileName
                ?.set("FlyShare-${output.versionName.orNull}.apk")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // NanoHTTPD server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(libs.kotlinx.serialization.json)

    // ZXing
    implementation(libs.zxing.core)

    // SMB client
    implementation(libs.smbj)

    // FTP client
    implementation(libs.commons.net)

    // Security: 加密 SharedPreferences（存储 SMB 密码）
    implementation(libs.androidx.security.crypto)

    debugImplementation(libs.androidx.ui.tooling)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
