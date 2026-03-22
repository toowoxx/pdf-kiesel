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
}

kotlin {
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

// Rust native library build task
tasks.register<Exec>("buildRustJvm") {
    description = "Build Rust pdfgen library for JVM (host platform)"
    group = "rust"

    val buildScript = file("rust/build-jvm.sh")

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
            "gcc",
            "--run",
            "bash ${buildScript.absolutePath}",
        )
    } else {
        commandLine("bash", buildScript.absolutePath)
    }
}
