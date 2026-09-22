plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yongyong.subtranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yongyong.subtranslator"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // 네트워크 (음성인식 모델을 처음 한 번 내려받을 때만 씀)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 온디바이스 무료 번역 (ML Kit)
    implementation("com.google.mlkit:translate:17.0.3")

    // 완전 무료 · 오프라인 음성인식 (Vosk) - 인터넷 없이 폰 안에서 처리되고, 분당 과금이 전혀 없어요.
    // 언어별 모델은 딱 한 번만 인터넷으로 내려받고(약 40~50MB), 그다음부터는 계속 무료/오프라인이에요.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // 비동기 처리
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
