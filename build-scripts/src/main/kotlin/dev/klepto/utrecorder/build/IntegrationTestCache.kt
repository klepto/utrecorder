package dev.klepto.utrecorder.build

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

/**
 * The JVM argument that names the download cache an integration test reuses across runs.
 *
 * The argument sets the [PROPERTY] system property of the test JVM.
 *
 * @property directory The absolute path of the cache directory.
 */
class IntegrationTestCache(
    @get:Internal
    val directory: Provider<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-D$PROPERTY=${directory.get()}")

    companion object {
        /**
         * The name of the system property that holds the cache directory, both in Gradle and in the test JVM.
         */
        const val PROPERTY = "utrecorder.test.cache"
    }
}
