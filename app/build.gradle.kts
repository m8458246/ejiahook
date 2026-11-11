plugins {
    id("com.android.application")
}

android {
    namespace = "com.cycy.ejiahook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cycy.ejiahook"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    // 固定签名，保证每次 CI 出的包能覆盖安装（debug keystore 每次 runner 都不同）
    signingConfigs {
        create("stable") {
            storeFile = file("keystore.jks")
            storePassword = "ejiahook"
            keyAlias = "ejiahook"
            keyPassword = "ejiahook"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // META-INF/xposed/* 必须原样进入 APK，框架靠它识别模块与入口
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 由 Xposed 框架在运行时提供，不打包进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
    compileOnly("androidx.annotation:annotation:1.9.1")
}
