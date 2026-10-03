import dev.klepto.utrecorder.build.IntegrationTestCache
import dev.klepto.utrecorder.build.bundle
import dev.klepto.utrecorder.build.javaVersion
import dev.klepto.utrecorder.build.ktlintVersion
import dev.klepto.utrecorder.build.lib
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val INTEGRATION_TEST_PATTERN = "*IntegrationTest"

kotlin {
    jvmToolchain(javaVersion)
}

ktlint {
    version.set(ktlintVersion)
    outputToConsole.set(true)
}

dependencies {
    implementation(lib("kotlin.logging"))
    testImplementation(lib("kotlin.test"))
    testImplementation(bundle("kotest"))
    testImplementation(lib("logback.classic"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The build logs at quiet level, which hides Gradle's default failed-test lines, so a failure is logged there.
    testLogging.quiet {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.test {
    filter.excludeTestsMatching(INTEGRATION_TEST_PATTERN)
}

val integrationTestCachePath =
    providers
        .systemProperty(IntegrationTestCache.PROPERTY)
        .filter(String::isNotEmpty)
        .orElse(".utrecorder/cache")

val integrationTestCache =
    layout.settingsDirectory
        .dir(integrationTestCachePath)
        .map { it.asFile.absolutePath }

tasks.register<Test>("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the tests that exercise real external dependencies. Opt-in, never part of `check`."

    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    filter.includeTestsMatching(INTEGRATION_TEST_PATTERN)
    filter.isFailOnNoMatchingTests = false
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
    jvmArgumentProviders += IntegrationTestCache(integrationTestCache)
}
