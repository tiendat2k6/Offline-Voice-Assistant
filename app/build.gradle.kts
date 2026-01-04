import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.trolyao"
    compileSdk = 35

    // --- KHỐI LỆNH QUAN TRỌNG ĐỂ SHERPA ĐỌC ĐƯỢC MODEL ---
    @Suppress("UnstableApiUsage")

    androidResources {
        noCompress.addAll(listOf("onnx", "tflite", "p3m", "txt"))
    }



    defaultConfig {
        applicationId = "com.example.trolyao"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // TỐI ƯU CHO REDMI 9T: Chỉ lấy thư viện 64-bit để máy chạy nhẹ nhất
//        ndk {
//            abiFilters.add("arm64-v8a")
//        }

        // ĐỌC KEY PICOVOICE
        val properties = Properties()
        if (project.rootProject.file("local.properties").exists()) {
            properties.load(project.rootProject.file("local.properties").inputStream())
        }
        buildConfigField("String", "SECRET_KEY", "\"${properties.getProperty("PICOVOICE_ACCESS_KEY")}\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // --- ANDROID CORE ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // --- AI ENGINES ---
    implementation("ai.picovoice:porcupine-android:3.0.1")

    implementation(files("libs/sherpa-onnx-1.12.20.aar"))

    // --- AUDIO UTILS ---
    implementation("androidx.media:media:1.7.0")

    // --- TEST ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
