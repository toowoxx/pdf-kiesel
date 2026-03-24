import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

group = "de.toowoxx.pdfkiesel"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

kotlin {
    // JVM target (server-side)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class) applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Android target — only when the Android plugin is applied by the host project.
// Apply from an Android-capable project with: `apply plugin: 'com.android.kotlin.multiplatform.library'`
pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    kotlin {
        androidLibrary {
            namespace = "de.toowoxx.pdfkiesel"
            compileSdk = libs.versions.android.compileSdk.get().toInt()
            minSdk = libs.versions.android.minSdk.get().toInt()
            compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        }
    }

    tasks.register<Exec>("buildRust") {
        description = "Build Rust pdfgen library for Android"
        group = "rust"
        val buildScript = file("rust/build-android.sh")
        val useNix = providers.exec {
            commandLine("which", "nix-shell")
            isIgnoreExitValue = true
        }.result.get().exitValue == 0

        if (useNix) {
            commandLine("nix-shell", "-p", "rustup", "cargo-ndk", "--run", "bash ${buildScript.absolutePath}")
        } else {
            commandLine("bash", buildScript.absolutePath)
        }
    }
}

// iOS targets — only on macOS
if (System.getProperty("os.name").lowercase().contains("mac")) {
    kotlin {
        val pdfgenLibDir = rootProject.projectDir.resolve("pdf-kiesel/iosFrameworks/pdfgen-ios")
        val pdfgenLibPath = mapOf(
            "iosArm64" to pdfgenLibDir.resolve("device"),
            "iosSimulatorArm64" to pdfgenLibDir.resolve("sim"),
            "iosX64" to pdfgenLibDir.resolve("sim-x86_64"),
        )

        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
            iosTarget.compilations["main"].cinterops {
                create("pdfgen") {
                    defFile(project.file("src/nativeInterop/cinterop/pdfgen.def"))
                    includeDirs(project.file("src/nativeInterop/cinterop/pdfgen"))
                    pdfgenLibPath[iosTarget.name]?.let { extraOpts("-libraryPath", it.absolutePath) }
                }
            }
        }
    }

    tasks.register<Exec>("buildRustIos") {
        description = "Build Rust pdfgen library for iOS"
        group = "rust"
        val rustDir = file("rust")
        val buildScript = file("rust/build-ios.sh")
        val iosLibDir = rootProject.projectDir.resolve("pdf-kiesel/iosFrameworks/pdfgen-ios")

        inputs.dir(rustDir.resolve("src"))
        inputs.file(rustDir.resolve("Cargo.toml"))
        inputs.file(rustDir.resolve("Cargo.lock"))
        inputs.file(buildScript)
        outputs.file(iosLibDir.resolve("device/libpdfgen.a"))
        outputs.file(iosLibDir.resolve("sim/libpdfgen.a"))
        outputs.file(iosLibDir.resolve("sim-x86_64/libpdfgen.a"))

        commandLine("bash", buildScript.absolutePath)
    }

    val buildRustIosTask = tasks.named("buildRustIos")
    tasks.matching { it.name.startsWith("cinteropPdfgen") }.configureEach {
        dependsOn(buildRustIosTask)
    }
}

// JVM Rust build task
tasks.register<Exec>("buildRustJvm") {
    description = "Build Rust pdfgen library for JVM (host platform)"
    group = "rust"
    val buildScript = file("rust/build-jvm.sh")
    val useNix = providers.exec {
        commandLine("which", "nix-shell")
        isIgnoreExitValue = true
    }.result.get().exitValue == 0

    if (useNix) {
        commandLine("nix-shell", "-p", "gcc", "--run", "bash ${buildScript.absolutePath}")
    } else {
        commandLine("bash", buildScript.absolutePath)
    }
}
