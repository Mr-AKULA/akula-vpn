import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.jna)
    implementation(libs.zxing.core)
}

abstract class DownloadFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        URI(sourceUrl.get())
            .toURL()
            .openStream()
            .use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
    }
}

abstract class ExtractZipEntryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipFile: RegularFileProperty

    @get:Input
    abstract val entrySuffix: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val zip = zipFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        ZipFile(zip).use { archive ->
            val entry = archive.entries().asSequence()
                .firstOrNull { it.name.endsWith(entrySuffix.get()) }
                ?: error("${entrySuffix.get()} entry was not found in ${zip.absolutePath}")

            archive.getInputStream(entry).use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }
    }
}

abstract class VerifyNativeResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    abstract val requiredPaths: ListProperty<String>

    @TaskAction
    fun verify() {
        val root = resourcesDir.get().asFile
        val missing = requiredPaths.get()
            .map { root.resolve(it) }
            .filterNot { it.isFile }

        require(missing.isEmpty()) {
            "Missing desktop native resources:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.relativeTo(root).invariantSeparatorsPath}" }
        }
    }
}

val defaultOlcRtcRepo = rootProject.layout.projectDirectory.asFile.parentFile
    .resolve("olcrtc")
    .absolutePath
val olcrtcRepo = providers.environmentVariable("OLCRTC_REPO")
    .orElse(defaultOlcRtcRepo)
val olcrtcRepoDir = olcrtcRepo.map { rootProject.file(it) }
val generatedNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")
val hevSocks5TunnelSourceDir = rootProject.layout.projectDirectory.dir("androidApp/src/main/jni/hev-socks5-tunnel")
val currentBuildOs = OperatingSystem.current()
val desktopPackageName = "AKULA VPN"
val desktopPackageVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0").get()
val tun2SocksVersion = "2.6.0"
val wintunVersion = "0.14.1"
val currentBuildTargetFormats = when {
    currentBuildOs.isMacOsX -> arrayOf(TargetFormat.Dmg)
    currentBuildOs.isWindows -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
    currentBuildOs.isLinux -> arrayOf(TargetFormat.AppImage)
    else -> emptyArray()
}

fun desktopArchName(arch: String): String = when (arch.lowercase()) {
    "x86_64", "amd64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Unsupported desktop architecture: $arch")
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

val hostDesktopArch = desktopArchName(System.getProperty("os.arch"))

fun registerOlcRtcBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    inputs.files(olcrtcRepoDir.map { dir ->
        fileTree(dir) {
            include("cmd/**", "internal/**", "pkg/**", "mobile/**", "go.mod", "go.sum")
        }
    })
    outputs.file(outputFile)
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "0")
    commandLine(
        "go",
        "build",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
}

fun registerOlcRtcLibraryBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    inputs.files(olcrtcRepoDir.map { dir ->
        fileTree(dir) {
            include("cmd/**", "internal/**", "pkg/**", "mobile/**", "go.mod", "go.sum")
        }
    })
    outputs.file(outputFile)
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "1")
    commandLine(
        "go",
        "build",
        "-buildmode=c-shared",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc-cgo"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
}

val buildOlcRtcDarwinArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "olcrtc-darwin-arm64"
)

val buildOlcRtcDarwinAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "olcrtc-darwin-amd64"
)

val buildOlcRtcWindowsAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.exe"
)

val buildOlcRtcLinuxAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "olcrtc-linux-amd64"
)

val buildOlcRtcLinuxArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "olcrtc-linux-arm64"
)

val buildOlcRtcLibDarwinArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "libolcrtc-darwin-arm64.dylib"
)

val buildOlcRtcLibDarwinAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "libolcrtc-darwin-amd64.dylib"
)

val buildOlcRtcLibLinuxAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "libolcrtc-linux-amd64.so"
)

val buildOlcRtcLibLinuxArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "libolcrtc-linux-arm64.so"
)

val buildOlcRtcLibWindowsAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.dll"
)

val desktopNativeAssetTasks = mutableListOf<Any>(
    buildOlcRtcDarwinArm64,
    buildOlcRtcDarwinAmd64,
    buildOlcRtcWindowsAmd64,
    buildOlcRtcLinuxAmd64,
    buildOlcRtcLinuxArm64,
    buildOlcRtcLibDarwinArm64,
    buildOlcRtcLibDarwinAmd64,
    buildOlcRtcLibLinuxAmd64,
    buildOlcRtcLibLinuxArm64,
    buildOlcRtcLibWindowsAmd64
)
val hostDesktopNativeAssetTasks = mutableListOf<Any>()

when {
    currentBuildOs.isMacOsX -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinArm64)
        }
    }
    currentBuildOs.isWindows -> {
        hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
        hostDesktopNativeAssetTasks.add(buildOlcRtcLibWindowsAmd64)
    }
    currentBuildOs.isLinux -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxArm64)
        }
    }
}

if (currentBuildOs.isLinux) {
    val buildHevSocks5TunnelLinux = tasks.register<Exec>("buildHevSocks5TunnelLinux") {
        val outputFile = generatedNativeResources.map {
            it.file("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
        val output = outputFile.get().asFile

        outputs.file(outputFile)
        workingDir = hevSocks5TunnelSourceDir.asFile
        commandLine(
            "sh",
            "-c",
            "mkdir -p ${shellQuote(output.parentFile.absolutePath)} && make clean exec && install -m 0755 bin/hev-socks5-tunnel ${shellQuote(output.absolutePath)}"
        )
    }
    desktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
    hostDesktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
}

if (currentBuildOs.isWindows) {
    val tun2SocksWindowsOutput = generatedNativeResources.map {
        it.file("native/tun2socks-windows-amd64.exe")
    }
    val wintunWindowsOutput = generatedNativeResources.map {
        it.file("native/wintun.dll")
    }

    val downloadTun2SocksWindowsAmd64 = tasks.register<DownloadFileTask>("downloadTun2SocksWindowsAmd64") {
        sourceUrl.set("https://github.com/xjasonlyu/tun2socks/releases/download/v$tun2SocksVersion/tun2socks-windows-amd64.zip")
        outputFile.set(layout.buildDirectory.file("tmp/tun2socks/tun2socks-windows-amd64-$tun2SocksVersion.zip"))
    }

    val extractTun2SocksWindowsAmd64 = tasks.register<ExtractZipEntryTask>("extractTun2SocksWindowsAmd64") {
        zipFile.set(downloadTun2SocksWindowsAmd64.flatMap { it.outputFile })
        entrySuffix.set("tun2socks-windows-amd64.exe")
        outputFile.set(tun2SocksWindowsOutput)
    }

    val downloadWintunWindowsAmd64 = tasks.register<DownloadFileTask>("downloadWintunWindowsAmd64") {
        sourceUrl.set("https://www.wintun.net/builds/wintun-$wintunVersion.zip")
        outputFile.set(layout.buildDirectory.file("tmp/wintun/wintun-$wintunVersion.zip"))
    }

    val extractWintunWindowsAmd64 = tasks.register<ExtractZipEntryTask>("extractWintunWindowsAmd64") {
        zipFile.set(downloadWintunWindowsAmd64.flatMap { it.outputFile })
        entrySuffix.set("/bin/amd64/wintun.dll")
        outputFile.set(wintunWindowsOutput)
    }

    // AKULA VPN для Windows — всё в одной программе (Павел 17.10.2026: «как на телефоне»). Туннели — официальные
    // подписанные модули, без отдельных программ: AmneziaWG (amneziawg.exe + wintun.dll, MIT) и OpenVPN 2.7
    // (openvpn.exe + OpenSSL, GPL) с драйвером ovpn-dco (подписан Microsoft). Кладутся в папку программы
    // (Program Files/AKULA VPN/app/resources/vpn) — служба SYSTEM не должна запускать файлы из папки пользователя.
    // Суммы — от проверенных сборок (подпись Authenticode / GPG OpenVPN, 17.10.2026): другой файл не попадёт.
    fun sha256Of(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    val vpnDownloads = layout.buildDirectory.dir("downloads/vpn")
    fun verifiedDownload(name: String, url: String, sha: String) = tasks.register<DownloadFileTask>(name) {
        sourceUrl.set(url)
        outputFile.set(vpnDownloads.map { it.file(url.substringAfterLast('/')) })
        onlyIf { !outputFile.get().asFile.exists() || sha256Of(outputFile.get().asFile) != sha }
        doLast {
            val file = outputFile.get().asFile
            val actual = sha256Of(file)
            check(actual == sha) { file.delete(); "${file.name}: неожиданная SHA-256 $actual — файл подменён или версия другая" }
        }
    }
    val downloadAmneziaWgWindowsAmd64 = verifiedDownload(
        "downloadAmneziaWgWindowsAmd64",
        "https://github.com/amnezia-vpn/amneziawg-windows-client/releases/download/3.1.0/amneziawg-amd64-3.1.0.msi",
        "a1b48ea8699cd347832a3691d832004574ef8ad65bcf887611ac8acb99b7de8b"
    )
    val downloadOpenVpnWindowsAmd64 = verifiedDownload(
        "downloadOpenVpnWindowsAmd64",
        "https://swupdate.openvpn.org/community/releases/OpenVPN-2.7.8-I001-amd64.msi",
        "1ea61a3056905b90607d4b0f084c04e5cf3043e07cf192d9cd44b0474f29cc49"
    )
    val downloadOvpnDcoWindowsAmd64 = verifiedDownload(
        "downloadOvpnDcoWindowsAmd64",
        "https://github.com/OpenVPN/ovpn-dco-win/releases/download/2.8.13/ovpn-dco-win-2.8.13-amd64.zip",
        "7e3bfa83b55771e352c3aa65281a93948674833502302fc02dcb546b957ca9d4"
    )
    val windowsVpnTools = layout.buildDirectory.dir("generated/akulaAppResources")
    val prepareWindowsVpnTools = tasks.register("prepareWindowsVpnTools") {
        dependsOn(downloadAmneziaWgWindowsAmd64, downloadOpenVpnWindowsAmd64, downloadOvpnDcoWindowsAmd64)
        val awgMsi = downloadAmneziaWgWindowsAmd64.flatMap { it.outputFile }
        val ovpnMsi = downloadOpenVpnWindowsAmd64.flatMap { it.outputFile }
        val dcoZip = downloadOvpnDcoWindowsAmd64.flatMap { it.outputFile }
        inputs.files(awgMsi, ovpnMsi, dcoZip)
        outputs.dir(windowsVpnTools)
        val tmp = temporaryDir
        doLast {
            val root = windowsVpnTools.get().asFile.resolve("windows-x64/vpn")
            root.deleteRecursively()
            // Административная распаковка MSI: только файлы, без установки и без следов в системе
            fun unpackMsi(msi: File, into: File) {
                into.deleteRecursively(); into.mkdirs()
                val p = ProcessBuilder("msiexec.exe", "/a", msi.absolutePath, "/qn", "TARGETDIR=${into.absolutePath}")
                    .inheritIO().start()
                check(p.waitFor() == 0) { "msiexec /a ${msi.name}: код ${p.exitValue()}" }
            }
            val awgTmp = tmp.resolve("awg"); unpackMsi(awgMsi.get().asFile, awgTmp)
            val ovpnTmp = tmp.resolve("ovpn"); unpackMsi(ovpnMsi.get().asFile, ovpnTmp)
            fun copyInto(dir: File, names: List<String>, target: File) {
                target.mkdirs()
                names.forEach { dir.resolve(it).copyTo(target.resolve(it), overwrite = true) }
            }
            copyInto(awgTmp.resolve("AmneziaWG"), listOf("amneziawg.exe", "wintun.dll"), root.resolve("awg"))
            copyInto(
                ovpnTmp.resolve("OpenVPN/bin"),
                listOf("openvpn.exe", "tapctl.exe", "libcrypto-3-x64.dll", "libssl-3-x64.dll", "libpkcs11-helper-1.dll", "vcruntime140.dll"),
                root.resolve("ovpn")
            )
            ZipFile(dcoZip.get().asFile).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val out = root.resolve("ovpn/dco").resolve(entry.name).normalize()
                    check(out.startsWith(root)) { "Подозрительный путь в архиве: ${entry.name}" }
                    out.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            listOf("awg/amneziawg.exe", "awg/wintun.dll", "ovpn/openvpn.exe", "ovpn/dco/win10/ovpn-dco.inf", "ovpn/dco/win11/ovpn-dco.inf")
                .forEach { check(root.resolve(it).isFile) { "Нет $it после распаковки" } }
        }
    }
    hostDesktopNativeAssetTasks.add(prepareWindowsVpnTools)
    desktopNativeAssetTasks.add(prepareWindowsVpnTools)
    desktopNativeAssetTasks.add(extractTun2SocksWindowsAmd64)
    desktopNativeAssetTasks.add(extractWintunWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractTun2SocksWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractWintunWindowsAmd64)
}

fun requiredHostNativeResourcePaths(): List<String> = buildList {
    when {
        currentBuildOs.isMacOsX -> {
            add("native/olcrtc-darwin-$hostDesktopArch")
            add("native/libolcrtc-darwin-$hostDesktopArch.dylib")
        }
        currentBuildOs.isWindows -> {
            add("native/olcrtc-windows-amd64.exe")
            add("native/olcrtc-windows-amd64.dll")
            add("native/tun2socks-windows-amd64.exe")
            add("native/wintun.dll")
        }
        currentBuildOs.isLinux -> {
            add("native/olcrtc-linux-$hostDesktopArch")
            add("native/libolcrtc-linux-$hostDesktopArch.so")
            add("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
    }
}

val verifyDesktopNativeResources = tasks.register<VerifyNativeResourcesTask>("verifyDesktopNativeResources") {
    dependsOn(hostDesktopNativeAssetTasks.toList())
    resourcesDir.set(generatedNativeResources)
    requiredPaths.set(requiredHostNativeResourcePaths())
}

tasks.register("buildDesktopNativeAssets") {
    dependsOn(desktopNativeAssetTasks)
    dependsOn(verifyDesktopNativeResources)
}

sourceSets {
    main {
        resources.srcDir(generatedNativeResources)
        resources.srcDir(layout.projectDirectory.dir("appIcons"))
    }
}

if (currentBuildOs.isWindows) {
    val jpackageAppRootDir = layout.buildDirectory.dir("compose/binaries/main-release/app")

    tasks.register<Zip>("packageReleasePortableZip") {
        group = "distribution"
        description = "Packages a portable Windows zip from the jpackage app image."

        dependsOn("createReleaseDistributable")
        from(jpackageAppRootDir)
        archiveFileName.set("$desktopPackageName-$desktopPackageVersion-windows-amd64-portable.zip")
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

        doFirst {
            val appRoot = jpackageAppRootDir.get().asFile
            val appEntries = appRoot.listFiles().orEmpty()
            require(appRoot.isDirectory && appEntries.isNotEmpty()) {
                "Windows portable app image was not created at ${appRoot.absolutePath}"
            }
        }
    }
}

tasks.named("processResources") {
    dependsOn(verifyDesktopNativeResources)
}

listOf(
    "run",
    "createReleaseDistributable",
    "packageReleaseDistributionForCurrentOS",
    "packageReleaseExe",
    "packageReleaseMsi",
    "packageReleaseDmg",
    "packageReleaseAppImage",
    "packageReleasePortableZip"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(verifyDesktopNativeResources)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // Модули туннелей Windows (prepareWindowsVpnTools) → <папка программы>/app/resources/vpn
            appResourcesRootDir.set(layout.buildDirectory.dir("generated/akulaAppResources"))
            modules("jdk.httpserver")
            targetFormats(*currentBuildTargetFormats)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
                menuGroup = "AKULA"
                shortcut = true
                dirChooser = true // папку выбирает пользователь; модули туннелей проверяются по подписи перед запуском
                upgradeUuid = "dbf85571-dc4f-40d4-b3ff-1a3766a5e1af" // свой: не обновлять поверх оригинального Olcbox
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "org.olcbox.app.desktopApp"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array><dict>
                            <key>CFBundleURLName</key><string>org.olcbox.app.import</string>
                            <key>CFBundleURLSchemes</key><array><string>olcbox</string></array>
                        </dict></array>
                    """.trimIndent()
                }
            }
        }
    }
}

if (currentBuildOs.isLinux) {
    val appImageTool = providers.environmentVariable("APPIMAGETOOL").orElse("appimagetool")
    val jpackageAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/appimage/AppDir")
    val linuxIconFile = layout.projectDirectory.file("appIcons/LinuxIcon.png")
    val appImageFile = layout.buildDirectory.file(
        "compose/binaries/main-release/appimage/$desktopPackageName-$desktopPackageVersion-$hostDesktopArch.AppImage"
    )

    val prepareReleaseLinuxAppDir = tasks.register<Exec>("prepareReleaseLinuxAppDir") {
        group = "distribution"
        description = "Prepares the AppDir layout used by appimagetool."

        dependsOn("packageReleaseAppImage")
        inputs.dir(jpackageAppDir)
        inputs.file(linuxIconFile)
        outputs.dir(appDir)

        commandLine(
            "sh",
            "-c",
            """
            set -eu

            source_dir="${'$'}1"
            target_dir="${'$'}2"
            icon_file="${'$'}3"

            rm -rf "${'$'}target_dir"
            mkdir -p "${'$'}target_dir"
            cp -R "${'$'}source_dir/." "${'$'}target_dir/"

            cat > "${'$'}target_dir/AppRun" <<'APPRUN'
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/bin/$desktopPackageName" "${'$'}@"
            APPRUN
            chmod +x "${'$'}target_dir/AppRun"

            cat > "${'$'}target_dir/org.olcbox.app.desktopApp.desktop" <<'DESKTOP'
            [Desktop Entry]
            Type=Application
            Name=$desktopPackageName
            Exec=$desktopPackageName %u
            Icon=olcbox
            Categories=Network;Utility;
            Terminal=false
            MimeType=x-scheme-handler/olcbox;
            DESKTOP

            cp "${'$'}icon_file" "${'$'}target_dir/olcbox.png"
            """.trimIndent(),
            "prepareReleaseLinuxAppDir",
            jpackageAppDir.get().asFile.absolutePath,
            appDir.get().asFile.absolutePath,
            linuxIconFile.asFile.absolutePath
        )
    }

    val packageReleaseLinuxAppImage = tasks.register<Exec>("packageReleaseLinuxAppImage") {
        group = "distribution"
        description = "Packages the Linux desktop app as a real .AppImage file."

        dependsOn(prepareReleaseLinuxAppDir)
        inputs.dir(appDir)
        outputs.file(appImageFile)

        commandLine(
            appImageTool.get(),
            appDir.get().asFile.absolutePath,
            appImageFile.get().asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
        dependsOn(packageReleaseLinuxAppImage)
    }
}

// Модули туннелей должны быть готовы до того, как Compose соберёт ресурсы программы
if (currentBuildOs.isWindows) {
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn("prepareWindowsVpnTools") }
}

// Установщики AKULA VPN для Windows: jpackage упаковывает готовую программу (createDistributable) с нашим шаблоном
// WiX (desktopApp/wix/main.wxs) — при удалении и обновлении он гасит туннели (служба AmneziaWG, openvpn.exe, адаптер).
// Свои задачи, а не packageMsi/packageExe Compose: тот очищает папку ресурсов jpackage и свой шаблон не подложить.
// Файлы — AKULA-VPN-windows.exe/.msi без версии: постоянная ссылка …/releases/latest/download/<файл> (сайт, консоль).
if (currentBuildOs.isWindows) {
    val akulaInstallers = layout.buildDirectory.dir("akula-installers")
    listOf("msi", "exe").forEach { type ->
        tasks.register<Exec>("packageAkula${type.replaceFirstChar { it.uppercase() }}") {
            group = "distribution"
            description = "AKULA VPN installer ($type) with tunnel cleanup on uninstall"
            dependsOn("createDistributable")
            val appImage = layout.buildDirectory.dir("compose/binaries/main/app/$desktopPackageName")
            val out = akulaInstallers.map { it.dir(type) }
            inputs.dir(appImage)
            inputs.dir(layout.projectDirectory.dir("wix"))
            outputs.dir(out)
            doFirst { out.get().asFile.apply { deleteRecursively(); mkdirs() } }
            val jpackage = File(System.getProperty("java.home"), "bin/jpackage.exe")
            commandLine(
                jpackage.absolutePath,
                "--type", type,
                "--app-image", appImage.get().asFile.absolutePath,
                "--name", desktopPackageName,
                "--app-version", desktopPackageVersion,
                "--resource-dir", layout.projectDirectory.dir("wix").asFile.absolutePath,
                "--icon", layout.projectDirectory.file("appIcons/WindowsIcon.ico").asFile.absolutePath,
                // папку выбирает пользователь (Павел 17.10.2026); по умолчанию Program Files. Модули туннелей перед запуском
                // проверяются по цифровой подписи (WindowsVpnToolsVerifier) — подменить их из папки пользователя нельзя
                "--win-dir-chooser", "--win-shortcut", "--win-menu", "--win-menu-group", "AKULA",
                "--win-upgrade-uuid", "dbf85571-dc4f-40d4-b3ff-1a3766a5e1af",
                "--dest", out.get().asFile.absolutePath
            )
            doLast {
                val built = out.get().asFile.listFiles { f -> f.extension == type }?.singleOrNull()
                    ?: error("jpackage не создал .$type")
                built.renameTo(out.get().asFile.resolve("AKULA-VPN-windows.$type"))
            }
        }
    }
}
