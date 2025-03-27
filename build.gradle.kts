import org.jetbrains.kotlin.gradle.targets.js.binaryen.BinaryenRootPlugin.Companion.kotlinBinaryenExtension

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    application
}

group = "com.thorsten-hake"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
application {
    mainClass.set("com.thorstenhake.awspurge.MainKt")
}
dependencies {
    commonMainImplementation(libs.clikt)
    commonMainImplementation(awssdk.services.cloudformation)

    commonMainImplementation(libs.kotlin.logging)
    commonMainImplementation(libs.sl4fj.simple)
    commonTestImplementation(kotlin("test"))
    commonTestImplementation(libs.testcontainers.localstack)
    commonTestImplementation(libs.kotlinx.coroutines.test)
    commonTestImplementation(libs.kotest.assertions.core)
    commonTestImplementation(libs.awaitility.kotlin)
    commonTestImplementation(libs.mockk)
}
tasks.test {
    useJUnitPlatform()
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "com.thorstenhake.awspurge.MainKt"
            }
        }
    }
    jvmToolchain(21)
    jvm()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
}