plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.goldintelligence.ingestion"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
