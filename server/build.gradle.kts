plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}


application {
    mainClass.set("io.goldintelligence.server.MainKt")
}

dependencies {
    implementation(project(":client"))

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach { useJUnit() }
