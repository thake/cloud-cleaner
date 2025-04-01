plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "com.thorsten-hake"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kaml)
    implementation(libs.kotlinx.coroutines.slf4j)

    //jvm only
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // test
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)

    // jvm test
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}