plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "folyan.app"
    compileSdk = 35
    ndkVersion = "28.2.13676358" // NDK r28c, тот же, что в ffmpeg/build_ffmpeg.sh

    defaultConfig {
        applicationId = "folyan.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // libffmpeg.so запускается как программа, поэтому библиотеки должны лежать на диске
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("yt-dlp")
            install("certifi")
        }
    }
}
