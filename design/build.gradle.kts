plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.teatalk.design"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.teatalk.design"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-design"
    }

    // 复用仓库内 debug keystore（与 app 模块一致，沙箱禁止写用户 .android 目录）
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
}
