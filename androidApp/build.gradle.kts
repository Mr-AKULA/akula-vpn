import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
// Ключ подписи AKULA VPN — в общей папке секретов (не в репозитории); старое место — запасное
val keystorePropertiesFile = File(System.getenv("AKULA_KEYSTORE_PROPERTIES")
    ?: "${System.getProperty("user.home")}/.akula-secrets/android-signing/akula-vpn-keystore.properties")
    .takeIf { it.exists() } ?: rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

val hasReleaseKeystore =
    keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionCode = providers.gradleProperty("olcbox.versionCode")
    .map { it.toInt() }
    .orElse(1)
val defaultAndroidAbiFilters = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidAbiFilters = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(defaultAndroidAbiFilters)

require(androidAbiFilters.isNotEmpty()) {
    "olcbox.android.abiFilters must contain at least one Android ABI"
}

android {
    namespace = "org.olcbox.app"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 24
        targetSdk = 37

        applicationId = "ru.akula.vpn"
        versionCode = olcboxVersionCode.get()
        versionName = olcboxVersion.get()

        ndk {
            abiFilters += androidAbiFilters
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // OpenVPN 2 (ics-openvpn ovpn2): движок openvpn3 не нужен — минус ~9 МБ (libosslspeedtest нужна: через неё грузится OpenSSL)
            excludes += setOf("**/libovpn3.so")
        }
    }
}

// In AGP 9.0+ Kotlin settings for Android are configured like this:
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(project(":sharedUI:ovpn-bin"))  // App наследует класс приложения ics-openvpn
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.datastore.preferences)
}

// AKULA VPN: сборки выходят пустыми — без вшитых подключений, адресов и ключей (Павел 17.10.2026).
// Каждый пользователь добавляет свою ссылку-подписку из консоли сервера. Старый механизм «своя сборка
// у каждого» (assets/akula_preset.json) больше не используется — если файл появится, сборка остановится.
val checkNoEmbeddedProfiles = tasks.register("checkNoEmbeddedProfiles") {
    val preset = layout.projectDirectory.file("src/main/assets/akula_preset.json").asFile
    doLast {
        if (preset.exists()) {
            throw GradleException("Сборка должна быть пустой: удалите ${preset.path} (подключения — только через подписку)")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNoEmbeddedProfiles) }
