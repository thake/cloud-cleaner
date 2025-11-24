import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  alias(libs.plugins.kotlinJvm)
  application
  alias(libs.plugins.graalvm.native)
  alias(libs.plugins.gmazzo.buildconfig)
  alias(libs.plugins.git.version)
}

val gitVersion: groovy.lang.Closure<String> by extra

version = gitVersion().removePrefix("v")

repositories { mavenCentral() }

application { mainClass = "cloudcleaner.aws.MainKt" }

tasks.named<JavaExec>("run") {
  isIgnoreExitValue = true
  workingDir(rootProject.projectDir)
}

dependencies {
  implementation(project(":base"))
  implementation(libs.clikt)
  implementation(awssdk.services.backup)
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
  implementation(awssdk.services.dynamodb)
  implementation(awssdk.services.kms)
  implementation(awssdk.services.lambda)
  implementation(awssdk.services.sso)
  implementation(awssdk.runtime.smithy.kotlin.http.client.engine.okhttp)

  implementation(libs.kotlin.logging)
  implementation(libs.kotlinx.io.core)
  implementation(libs.kaml)
  implementation(libs.kotlinx.coroutines.slf4j)

  // jvm only
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
  testImplementation(libs.wiremock.standalone)
}

buildConfig {
  packageName = "cloudcleaner.aws"
  useKotlinOutput { topLevelConstants = true }
  buildConfigField("APP_VERSION", provider { project.version.toString() })
}

tasks.test {
  useJUnitPlatform()
  environment("AWS_ACCESS_KEY_ID", "test")
  environment("AWS_SECRET_ACCESS_KEY", "test")
  environment("AWS_DEFAULT_REGION", "eu-central-1")
  testLogging {
    exceptionFormat = TestExceptionFormat.FULL
  }
}

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

graalvmNative {
  binaries.all { resources.autodetect() }
  binaries {
    named("main") {
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
