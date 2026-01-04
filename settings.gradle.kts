pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Ưu tiên MavenCentral lên đầu để tìm Sherpa-ONNX nhanh hơn
        mavenCentral()
        google()

        // Kho dự phòng nếu MavenCentral chính bị nghẽn
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/releases") }
    }
}

rootProject.name = "TroLyAo"
include(":app")
