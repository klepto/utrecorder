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
        ivy("https://nodejs.org/dist/") {
            name = "Node.js"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "utrecorder"
include("utrecorder-common")
include("utrecorder-ut99")
include("utrecorder-uscript")
include("utrecorder-hooks")
include("utrecorder-core")
include("utrecorder-cli")
include("utrecorder-server")
include("utrecorder-web")
