pluginManagement {
    includeBuild("build-scripts")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "utrecorder"
include("utrecorder-ut99")
include("utrecorder-common")
include("utrecorder-uscript")
include("utrecorder-hooks")
include("utrecorder-core")
