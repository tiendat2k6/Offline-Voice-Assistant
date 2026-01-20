pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Giữ nguyên chế độ này để quản lý thư viện tập trung tại đây
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // Picovoice SDK được phân phối chính thức qua Maven Central và Google
        google()
        mavenCentral()

        // Thêm JitPack nếu sau này bạn muốn dùng các thư viện mở rộng khác (như MQTT)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TroLyAo"
include(":app")