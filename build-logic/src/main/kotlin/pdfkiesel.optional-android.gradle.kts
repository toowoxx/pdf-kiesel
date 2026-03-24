val androidSdkAvailable = providers.environmentVariable("ANDROID_HOME").isPresent

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
