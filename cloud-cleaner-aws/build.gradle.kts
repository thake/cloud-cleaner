plugins {
    alias(libs.plugins.kotlinJvm)
    application
    alias(libs.plugins.graalvm.native)
}

group = "com.thorsten-hake"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
application {
    mainClass = "cloudcleaner.aws.MainKt"
}
tasks.named<JavaExec>("run") {
    isIgnoreExitValue = true
    workingDir(rootProject.projectDir)
}
dependencies {
    implementation(project(":base"))
    implementation(libs.clikt)
    implementation(awssdk.services.cloudformation)
    implementation(awssdk.services.sts)
    implementation(awssdk.services.iam)
    implementation(awssdk.services.s3)
    implementation(awssdk.services.secretsmanager)
    implementation(awssdk.services.ssm)
    implementation(awssdk.services.ec2)
    implementation(awssdk.services.route53)
    implementation(awssdk.services.ecr)
    implementation(awssdk.services.cloudwatchlogs)
    implementation(awssdk.services.sqs)
    implementation(awssdk.services.sns)
    implementation(awssdk.services.sso)

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

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain{
        vendor = JvmVendorSpec.GRAAL_VM
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

graalvmNative {
    binaries.all {
        resources.autodetect()
    }
    binaries {
        named("main") {
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
                vendor.set(JvmVendorSpec.GRAAL_VM)
            })
            fallback = false
            verbose = true
            mainClass = "cloudcleaner.aws.MainKt"

            buildArgs.add("--initialize-at-build-time=ch.qos.logback")
            buildArgs.add("--initialize-at-build-time=kotlin")
            buildArgs.add("--initialize-at-build-time=org.slf4j.LoggerFactory")

            buildArgs.add("-H:+InstallExitHandlers")
            buildArgs.add("-H:+ReportUnsupportedElementsAtRuntime")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }

}
