plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.goldintelligence.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.goldintelligence.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":engine"))
}

kotlin {
    jvmToolchain(21)
}
