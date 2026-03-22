import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidLibrary {
        namespace = "de.toowoxx.pdfkiesel"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    val pdfgenLibDir = rootProject.projectDir.resolve("pdf-kiesel/iosFrameworks/pdfgen-ios")
    val pdfgenLibPath =
        mapOf(
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

    @OptIn(ExperimentalKotlinGradlePluginApi::class) applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Rust native library build task
val buildRust = tasks.register<Exec>("buildRust") {
    description = "Build Rust pdfgen library for Android"
    group = "rust"

    val buildScript = file("rust/build-android.sh")

    // Detect nix-shell at configuration time
    val useNix =
        providers
            .exec {
                commandLine("which", "nix-shell")
                isIgnoreExitValue = true
            }
            .result
            .get()
            .exitValue == 0

    if (useNix) {
        commandLine(
            "nix-shell",
            "-p",
            "rustup",
            "cargo-ndk",
            "--run",
            "bash ${buildScript.absolutePath}",
        )
    } else {
        commandLine("bash", buildScript.absolutePath)
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

    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac")
    }

    // The script handles nix-shell detection internally (re-execs itself
    // through nix-shell if rustup isn't on PATH).
    commandLine("bash", buildScript.absolutePath)
}

// Wire iOS Rust build before cinterop (cinterop needs the .a static library)
val buildRustIosTask = tasks.named("buildRustIos")
tasks.matching { it.name.startsWith("cinteropPdfgen") }.configureEach {
    dependsOn(buildRustIosTask)
}
