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
        versionCode = 7
        versionName = "0.7-shorter-utterance"
    }

    // ⚠ 이 서명 설정이 없으면, 깃허브에서 새로 빌드할 때마다 매번 다른 임시 서명이 생겨서
    // "패키지가 기존 앱과 충돌합니다"라며 설치가 안 돼요(지우고 새로 깔아야 함 → 음성인식
    // 모델도 다시 받아야 해서 불편함). app/debug.keystore를 저장소에 같이 올려두고 항상
    // 똑같은 서명을 쓰게 하면, 새 버전을 그냥 위에 덮어 설치할 수 있고 받아둔 모델도 안 지워져요.
    signingConfigs {
        create("stableDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableDebug")
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

    packaging {
        // onnxruntime / tokenizers / vosk 네이티브 라이브러리 + Java 서비스 파일이 서로
        // 겹칠 수 있어서(META-INF/*, libc++_shared.so) 첫 번째 것만 쓰게 해요.
        resources.pickFirsts.add("META-INF/*")
        jniLibs.useLegacyPackaging = true; jniLibs.pickFirsts.add("**/libc++_shared.so") // libc++_shared.so 중복 문제 예방
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // 네트워크 (음성인식/번역 모델을 처음 한 번 내려받을 때만 씀)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 온디바이스 번역 모델(M2M100-418M)을 폰 안에서 돌리는 엔진 (마이크로소프트 공식 ONNX Runtime)
    // - 예전에 쓰던 ML Kit 번역은 직역투가 심해서, 별도 테스트 앱에서 검증을 마친
    //   모델로 교체했어요. 처음엔 더 큰 NLLB-200을 썼다가, 음성인식(Vosk) 모델과
    //   같이 메모리에 떠 있으면 메모리가 부족해져서 앱이 꺼지는 문제가 있어서
    //   더 가벼운 M2M100-418M으로 바꿨어요. (자세한 내용은 NllbTranslator.kt 참고)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // 번역 모델이 쓰는 토크나이저(tokenizer.json)를 그대로 읽어서 문장<->숫자 변환을 해주는 라이브러리.
    // 언어 코드("__ja__", "__ko__" 등)의 내부 숫자값을 직접 하드코딩하지 않고
    // 이 라이브러리가 tokenizer.json에서 그대로 읽어오게 해서 실수를 줄인다.
    // ⚠ ai.djl.huggingface:tokenizers가 데스크톱용 jna(.jar)를 같이 끌고 들어오는데,
    // 아래 Vosk가 쓰는 jna(.aar)와 같은 클래스를 다른 형태로 중복시켜서 빌드가 깨져요
    // (checkDebugDuplicateClasses 실패). 안드로이드에서는 tokenizer-native가 실제
    // 네이티브 라이브러리를 제공하니, jna는 Vosk 쪽 것만 쓰도록 제외해요.
    implementation("ai.djl.huggingface:tokenizers:0.33.0") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("ai.djl.android:tokenizer-native:0.33.0")
    runtimeOnly("dev.atsushieno:libcxx-provider:29.0.14206865") // 안드로이드 네이티브 tokenizer + libc++_shared.so 부품 추가 - 없으면 로딩 실패

    // 완전 무료 · 오프라인 음성인식 (Vosk) - 인터넷 없이 폰 안에서 처리되고, 분당 과금이 전혀 없어요.
    // 언어별 모델은 딱 한 번만 인터넷으로 내려받고(약 40~50MB), 그다음부터는 계속 무료/오프라인이에요.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // 비동기 처리
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
