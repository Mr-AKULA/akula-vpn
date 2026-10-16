rootProject.name = "Multiplatform-App"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
        flatDir {
            dirs("androidApp/jniLibs/arm64-v8a")
        }
    }
}
include(":sharedUI")
include(":sharedUI:olcrtc-bin")
include(":sharedUI:awg-bin")
include(":sharedUI:ovpn-bin")
include(":androidApp")
include(":desktopApp")

