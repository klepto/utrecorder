import dev.klepto.utrecorder.build.bundle
import dev.klepto.utrecorder.build.detektVersion
import dev.klepto.utrecorder.build.javaVersion
import dev.klepto.utrecorder.build.ktlintVersion
import dev.klepto.utrecorder.build.lib
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

/**
 * Name pattern of the tests that run against real external dependencies.
 */
val integrationTestPattern = "*IntegrationTest"

kotlin {
    jvmToolchain(javaVersion)
}

detekt {
    toolVersion = detektVersion
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

ktlint {
    version.set(ktlintVersion)
    outputToConsole.set(true)
}

dependencies {
    implementation(lib("kotlin.logging"))
    testImplementation(lib("kotlin.test"))
    testImplementation(bundle("kotest"))
    testRuntimeOnly(lib("logback.classic"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.test {
    filter.excludeTestsMatching(integrationTestPattern)
}

val testSourceSet = the<JavaPluginExtension>().sourceSets["test"]

tasks.register<Test>("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the tests that exercise real external dependencies. Opt-in, never part of `check`."

    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    filter.includeTestsMatching(integrationTestPattern)
    filter.isFailOnNoMatchingTests = false
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
