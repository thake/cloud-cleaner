plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }

    versionCatalogs {
        create("awssdk") {
            from("aws.sdk.kotlin:version-catalog:1.5.98")
        }
    }
}
rootProject.name = "cloud-cleaner"
include("base")
include("cloud-cleaner-aws")
