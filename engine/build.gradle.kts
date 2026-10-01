plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach { useJUnit() }
