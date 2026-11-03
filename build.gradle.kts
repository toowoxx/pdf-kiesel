import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("pdfkiesel.optional-android")
}

group = "de.toowoxx.pdfkiesel"
version = "0.1.0"

val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    // The iOS targets exist on every host, so a consumer's iOS configurations resolve their
    // dependency on pdf-kiesel on Linux as well, and a build that locks its dependencies can lock
    // them there. Only the pdfgen cinterop and the Rust iOS build need a Mac.
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())
    if (isMacHost) {
        val pdfgenLibDir = rootProject.projectDir.resolve("pdf-kiesel/iosFrameworks/pdfgen-ios")
        val pdfgenLibPath = mapOf(
            "iosArm64" to pdfgenLibDir.resolve("device"),
            "iosSimulatorArm64" to pdfgenLibDir.resolve("sim"),
            "iosX64" to pdfgenLibDir.resolve("sim-x86_64"),
        )

        iosTargets.forEach { iosTarget ->
            iosTarget.compilations["main"].cinterops {
                create("pdfgen") {
                    defFile(project.file("src/nativeInterop/cinterop/pdfgen.def"))
                    includeDirs(project.file("src/nativeInterop/cinterop/pdfgen"))
                    pdfgenLibPath[iosTarget.name]?.let { extraOpts("-libraryPath", it.absolutePath) }
                }
            }
        }
    } else {
        // The iOS sources call the pdfgen cinterop, which exists on a Mac only, so their metadata compiles there.
        tasks.matching { it.name == "compileIosMainKotlinMetadata" }.configureEach { enabled = false }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class) applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

val rustDir = file("rust")

// Rust license generation

tasks.register<Exec>("generateRustLicenses") {
    description = "Generate license JSON for Rust dependencies via cargo-about"
    group = "rust"

    val outputFile = file("build/rust-licenses.json")
    // Resolve `nix` to an absolute path at config time. Gradle daemons started
    // outside our wrapper shell do not see the user's Nix profile on PATH, so
    // looking up `nix` via PATH at task-execution time fails. The binary lives
    // at a stable location regardless.
    val nixCommand: String? = sequenceOf(
        "/nix/var/nix/profiles/default/bin/nix",
        "${System.getProperty("user.home")}/.nix-profile/bin/nix",
        "/run/current-system/sw/bin/nix",
    ).map { File(it) }.firstOrNull { it.canExecute() }?.absolutePath
        ?: System.getenv("PATH")?.split(File.pathSeparator)
            ?.asSequence()?.map { File(it, "nix") }
            ?.firstOrNull { it.canExecute() }?.absolutePath

    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    inputs.file(rustDir.resolve("about.toml"))
    outputs.file(outputFile)

    workingDir = rustDir

    if (nixCommand != null) {
        // cargo-about invokes `cargo metadata` internally, so cargo itself
        // must be on PATH inside the spawned shell.
        commandLine(nixCommand, "shell", "nixpkgs#cargo-about", "nixpkgs#cargo", "-c", "cargo-about", "generate", "--format", "json", "-o", outputFile.absolutePath)
    } else {
        commandLine("cargo-about", "generate", "--format", "json", "-o", outputFile.absolutePath)
    }
}

// Rust build tasks

// The inputs that every build of the Rust library depends on. Relative path sensitivity gives the same
// cache key in every worktree of a checkout.
fun Task.declareRustInputs(buildScript: File) {
    inputs.dir(rustDir.resolve("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rustDir.resolve("Cargo.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rustDir.resolve("Cargo.lock")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(buildScript).withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Exec>("buildRustJvm") {
    description = "Build Rust pdfgen library for JVM (host platform)"
    group = "rust"
    val buildScript = rustDir.resolve("build-jvm.sh")
    val outputDir = file("src/jvmMain/resources/native/linux-x86_64")
    val useNix = providers.exec {
        commandLine("which", "nix-shell")
        isIgnoreExitValue = true
    }.result.get().exitValue == 0

    declareRustInputs(buildScript)
    outputs.file(outputDir.resolve("libpdfgen.so"))
    environment("PDFGEN_OUTPUT_DIR", outputDir.absolutePath)

    if (useNix) {
        commandLine("nix-shell", "-p", "gcc", "--run", "bash ${buildScript.absolutePath}")
    } else {
        commandLine("bash", buildScript.absolutePath)
    }
}

tasks.named("jvmProcessResources") {
    val nativeLib = file("src/jvmMain/resources/native/linux-x86_64/libpdfgen.so")
    if (!nativeLib.exists()) {
        dependsOn("buildRustJvm")
    }
}

val androidSdkAvailable = providers.environmentVariable("ANDROID_HOME").isPresent ||
    providers.environmentVariable("ANDROID_SDK_ROOT").isPresent ||
    rootProject.rootDir.resolve("local.properties").let { it.exists() && it.readText().contains(Regex("^\\s*sdk\\.dir\\s*=", RegexOption.MULTILINE)) }

/**
 * The Android NDK folder that builds the Rust library: `ANDROID_NDK_HOME` when it is not blank, otherwise the
 * newest NDK in the first of [Parameters.sdkNdkFolders] that has one. It is a value source, so Gradle runs it
 * again on every build and a newly installed NDK invalidates the configuration cache.
 */
abstract class AndroidNdkHome : ValueSource<String, AndroidNdkHome.Parameters> {
    interface Parameters : ValueSourceParameters {
        val configuredHome: Property<String>
        val sdkNdkFolders: ListProperty<File>
    }

    override fun obtain(): String? =
        parameters.configuredHome.orNull?.takeIf { it.isNotBlank() }
            ?: parameters.sdkNdkFolders.get().firstNotNullOfOrNull { newestNdk(it) }?.path

    private fun newestNdk(sdkNdkFolder: File): File? =
        sdkNdkFolder.listFiles { folder -> folder.name.matches(NDK_VERSION) && folder.resolve("source.properties").isFile }
            ?.maxWithOrNull(compareBy<File, List<Int>>(versionOrder) { folder -> folder.name.split('.').map(String::toInt) })

    private companion object {
        val NDK_VERSION = Regex("""\d+(\.\d+)*""")

        val versionOrder = Comparator<List<Int>> { left, right ->
            left.zip(right) { l, r -> l.compareTo(r) }.firstOrNull { it != 0 }
                ?: left.count().compareTo(right.count())
        }
    }
}

if (androidSdkAvailable) {
    // Without ANDROID_NDK_HOME the NDK is searched in the project-local SDK of the workspace, then in the global SDK.
    val ndkHome = providers.of(AndroidNdkHome::class) {
        parameters.configuredHome.set(providers.environmentVariable("ANDROID_NDK_HOME"))
        parameters.sdkNdkFolders.set(
            listOf(
                projectDir.parentFile.resolve(".android/sdk/ndk"),
                File(System.getProperty("user.home"), "Android/Sdk/ndk"),
            ),
        )
    }

    val buildRust = tasks.register<Exec>("buildRust") {
        description = "Build Rust pdfgen library for Android"
        group = "rust"
        val buildScript = rustDir.resolve("build-android.sh")
        val outputDir = file("src/androidMain/jniLibs")
        val useNix = providers.exec {
            commandLine("which", "nix-shell")
            isIgnoreExitValue = true
        }.result.get().exitValue == 0

        declareRustInputs(buildScript)
        // The script installs the Rust toolchain of this file, and pins cargo-ndk itself.
        inputs.file(rustDir.resolve("rust-toolchain.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
        // Cargo reads these variables, and they change the compiled code.
        listOf("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS").forEach { variable ->
            inputs.property(variable, providers.environmentVariable(variable).orElse(""))
        }
        // The NDK revision is in source.properties, so the key is the same for the same NDK at any path.
        ndkHome.orNull?.let { ndk ->
            environment("ANDROID_NDK_HOME", ndk)
            inputs.file(File(ndk, "source.properties")).withPathSensitivity(PathSensitivity.NAME_ONLY)
        }
        environment("PDFGEN_OUTPUT_DIR", outputDir.absolutePath)
        outputs.dir(outputDir)
        outputs.cacheIf("the declared inputs fix the libraries") { true }

        if (useNix) {
            commandLine("nix-shell", "-p", "rustup", "--run", "bash ${buildScript.absolutePath}")
        } else {
            commandLine("bash", buildScript.absolutePath)
        }
    }

    // The tasks of the library that read the jniLibs folder run after the libraries are built.
    tasks.matching { it.name.contains("JniLib") }.configureEach {
        dependsOn(buildRust)
    }
}

if (isMacHost) {
    tasks.register<Exec>("buildRustIos") {
        description = "Build Rust pdfgen library for iOS"
        group = "rust"
        val buildScript = rustDir.resolve("build-ios.sh")
        val flakeNix = rustDir.resolve("flake.nix")
        val iosLibDir = rootProject.projectDir.resolve("pdf-kiesel/iosFrameworks/pdfgen-ios")

        declareRustInputs(buildScript)
        outputs.file(iosLibDir.resolve("device/libpdfgen.a"))
        outputs.file(iosLibDir.resolve("sim/libpdfgen.a"))
        outputs.file(iosLibDir.resolve("sim-x86_64/libpdfgen.a"))

        workingDir = rustDir

        // The bundled rust toolchain (with iOS cross targets) lives in
        // rust/flake.nix and is entered via `nix develop`. Resolve `nix` to
        // an absolute path (Xcode and the Gradle daemon may not see the Nix
        // profile on PATH) and use the `path:` URL so flake evaluation
        // doesn't require git. Falls back to running build-ios.sh directly
        // when nix isn't available — the script then expects rustup.
        val nixCommand: String? = sequenceOf(
            "/nix/var/nix/profiles/default/bin/nix",
            "${System.getProperty("user.home")}/.nix-profile/bin/nix",
            "/run/current-system/sw/bin/nix",
        ).map { File(it) }.firstOrNull { it.canExecute() }?.absolutePath
            ?: System.getenv("PATH")?.split(File.pathSeparator)
                ?.asSequence()?.map { File(it, "nix") }
                ?.firstOrNull { it.canExecute() }?.absolutePath

        if (nixCommand != null && flakeNix.exists()) {
            commandLine(
                nixCommand,
                "develop",
                "path:${rustDir.absolutePath}",
                "--command",
                "bash",
                buildScript.absolutePath,
            )
        } else {
            commandLine("bash", buildScript.absolutePath)
        }
    }

    val buildRustIosTask = tasks.named("buildRustIos")
    tasks.matching { it.name.startsWith("cinteropPdfgen") }.configureEach {
        dependsOn(buildRustIosTask)
    }
}
