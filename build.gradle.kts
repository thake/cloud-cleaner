plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.git.version)
}

group = "com.thorsten-hake"
val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion().removePrefix("v")
