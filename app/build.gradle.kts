plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.test_exoplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.test_exoplayer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        renderscriptTargetApi = 21
        renderscriptSupportModeEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 🚨 [필수] 부모 테마를 위한 Material 라이브러리
    implementation("com.google.android.material:material:1.11.0")

    // 🚨 [필수] Media3 (ExoPlayer) 라이브러리 (HLS 지원 포함)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource:1.3.1") // ← 1.3.1로 통일!

    // WebSocket 통신을 위한 OkHttp 라이브러리
    // implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.socket:socket.io-client:2.1.0")    // Socket.IO 전용 클라이언트를 추가합니다.
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0") // (선택) 디버깅용
    implementation(project(":sdk")) // OpenCV 모듈
    implementation ("androidx.core:core-ktx:1.12.0") // 이 버전을 사용하면 안정적입니다.




}