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
        versionCode = 2
        versionName = "1.0.0"
    }

    // Development signing key, checked in deliberately so that a clean clone
    // produces an installable release build. Replace it before any public
    // distribution: its passwords are published in README.md.
    val keystoreFile = rootProject.file(
        providers.gradleProperty("GI_KEYSTORE").getOrElse("keystore/gold-intelligence.keystore")
    )

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = providers.gradleProperty("GI_KEYSTORE_PASSWORD").getOrElse("goldintelligence")
                keyAlias = providers.gradleProperty("GI_KEY_ALIAS").getOrElse("goldintelligence")
                keyPassword = providers.gradleProperty("GI_KEY_PASSWORD").getOrElse("goldintelligence")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            // Falls back to an unsigned release when no keystore is present,
            // so the build never fails for lack of a secret.
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":client"))
}
