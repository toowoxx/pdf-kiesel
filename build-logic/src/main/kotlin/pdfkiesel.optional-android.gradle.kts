val androidSdkAvailable = providers.environmentVariable("ANDROID_HOME").isPresent ||
    providers.environmentVariable("ANDROID_SDK_ROOT").isPresent ||
    rootDir.resolve("local.properties").let { it.exists() && it.readText().contains(Regex("^\\s*sdk\\.dir\\s*=", RegexOption.MULTILINE)) }

if (androidSdkAvailable) {
    apply(plugin = "com.android.kotlin.multiplatform.library")

    the<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>().apply {
        val androidTarget = extensions.getByType(
            com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget::class.java
        )
        androidTarget.apply {
            namespace = "de.toowoxx.pdfkiesel"
            compileSdk = 35
            minSdk = 26
        }
    }
}
