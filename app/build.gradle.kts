import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.trolyao"
    compileSdk = 35

    // --- CẤU HÌNH ĐỂ PICOVOICE ĐỌC MODEL NHANH NHẤT ---
    @Suppress("UnstableApiUsage")
    androidResources {
        // Rhino dùng .rhn, Porcupine dùng .ppn. Không nén để máy map thẳng vào memory.
        noCompress.addAll(listOf("rhn", "ppn", "txt"))
    }

    defaultConfig {
        applicationId = "com.example.trolyao"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ĐỌC KEY PICOVOICE TỪ local.properties
        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }

        // Lấy key PICOVOICE_ACCESS_KEY để gán vào BuildConfig
        val pivoKey = properties.getProperty("PICOVOICE_ACCESS_KEY") ?: ""
        buildConfigField("String", "SECRET_KEY", "\"$pivoKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true // Bật để app nhẹ hơn
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

    // --- PICOVOICE ENGINES (BỘ NÃO MỚI) ---
    // Phiên bản 3.0.0 ổn định nhất cho Android
    implementation("ai.picovoice:porcupine-android:3.0.0")
    implementation("ai.picovoice:rhino-android:3.0.0")

    // --- AUDIO & UTILS ---
    implementation("androidx.media:media:1.7.0")

    // --- TEST ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}