import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("pdfkiesel.optional-android")
}

group = "de.toowoxx.pdfkiesel"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    if (System.getProperty("os.name").lowercase().contains("mac")) {
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

    @OptIn(ExperimentalKotlinGradlePluginApi::class) applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Rust build tasks

tasks.register<Exec>("buildRustJvm") {
    description = "Build Rust pdfgen library for JVM (host platform)"
    group = "rust"
    val buildScript = file("rust/build-jvm.sh")
    val outputDir = file("src/jvmMain/resources/native/linux-x86_64")
    val useNix = providers.exec {
        commandLine("which", "nix-shell")
        isIgnoreExitValue = true
    }.result.get().exitValue == 0

    inputs.dir(file("rust/src"))
    inputs.file(file("rust/Cargo.toml"))
    inputs.file(file("rust/Cargo.lock"))
    inputs.file(buildScript)
    outputs.file(outputDir.resolve("libpdfgen.so"))

    if (useNix) {
        commandLine("nix-shell", "-p", "gcc", "--run", "bash ${buildScript.absolutePath}")
    } else {
        commandLine("bash", buildScript.absolutePath)
    }
}

tasks.named("jvmProcessResources") {
    dependsOn("buildRustJvm")
}

if (providers.environmentVariable("ANDROID_HOME").isPresent) {
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

if (System.getProperty("os.name").lowercase().contains("mac")) {
    tasks.register<Exec>("buildRustIos") {
        description = "Build Rust pdfgen library for iOS"
        group = "rust"
        val buildScript = file("rust/build-ios.sh")
        val iosLibDir = rootProject.projectDir.resolve("pdf-kiesel/iosFrameworks/pdfgen-ios")

        inputs.dir(file("rust/src"))
        inputs.file(file("rust/Cargo.toml"))
        inputs.file(file("rust/Cargo.lock"))
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
