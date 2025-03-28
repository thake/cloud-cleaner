plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "com.thorsten-hake"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
application {
    mainClass.set("MainKt")
}
tasks.test {
    useJUnitPlatform()
}
dependencies {
    implementation(libs.clikt)
    implementation(awssdk.services.cloudformation)
    implementation(awssdk.services.sts)
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
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.awaitility.kotlin)
}