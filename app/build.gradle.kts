plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号：CI 通过环境变量注入，保证 versionCode 严格递增且与 Release tag 同源。
// versionCode 不递增会导致覆盖安装被系统拒绝（旧版一直留在车机上）。
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"

android {
    namespace = "com.tiger.cardesk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tiger.cardesk"
        // 车机是 Android 8（API 26），minSdk 放到 21 兼容更老的机器
        minSdk = 21
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName

        // 关于「32 位」：本工程没有一行 native 代码，也没有带 .so 的依赖，
        // 打出来的是「全架构通用包」，32 位（armeabi-v7a）车机原生可直接安装。
        // 反过来说——将来若引入了带 .so 的库（地图 SDK、音视频等），
        // 就必须在 defaultConfig 里显式加 ndk { abiFilters += "armeabi-v7a" }，
        // 否则 APK 会缺 32 位 so，车机装得上却一调用就 UnsatisfiedLinkError。
    }

    signingConfigs {
        create("release") {
            // 签名信息由 CI Secrets 注入；本地不带 env 时不影响 debug 构建。
            // rootProject.file() 把 KEYSTORE_PATH 统一按「仓库根目录」解析，避免拼出 app/app/xxx
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrEmpty()) {
                storeFile = rootProject.file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 放大错误上限，避免一次只报前几个错误把真正的根因截断
        freeCompilerArgs = freeCompilerArgs + listOf("-Xmax-errors=200")
    }
}

// ---------------------------------------------------------------------------
// Web 界面只有一份源文件：仓库根目录的 index.html
// 构建时自动复制进 assets，杜绝「改了根目录、APK 里还是旧的」这类同步事故。
// 生成物 ./app/src/main/assets/index.html 已在 .gitignore 中排除。
// ---------------------------------------------------------------------------
val copyWebAssets by tasks.registering(Copy::class) {
    from(rootProject.file("index.html"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(copyWebAssets) }
tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }
    .configureEach { dependsOn(copyWebAssets) }

dependencies {
    // 刻意保持零第三方依赖：APK 体积小、启动快、车机老系统上少一类崩溃来源。
    // 需要时再按需添加，例如：
    // implementation("androidx.core:core-ktx:1.12.0")
}
