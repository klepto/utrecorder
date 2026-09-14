package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.time.TimestampId
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The directory tasks run in, and how it treats what they leave behind.
 *
 * Use [DEFAULT] for the workspace the current process runs its tasks in, or [fromProperties] to build one from a given
 * [PropertySource].
 *
 * @property directory The directory the working directory of a task is created under.
 * @property keep Whether the working directory of a successful task is kept.
 * @property retention How long the working directory of a failed task is kept before a later task removes it.
 */
class Workspace(
    val directory: Path,
    val keep: Boolean,
    val retention: Duration,
) {
    /**
     * Runs the task named [name] held by [block] in a working directory of its own, and returns what it returns.
     *
     * The directory is removed once [block] returns, unless [keep] is set, and kept once it throws. A [block] producing
     * a file the caller keeps moves or copies it out before it returns.
     *
     * @throws java.io.IOException if a working directory left by an expired task cannot be removed, before [block]
     * runs, or if the working directory cannot be removed after [block] returned.
     */
    fun <T> task(
        name: String,
        block: (Path) -> T,
    ): T {
        val taskDirectory = createTaskDirectory(name)
        val result = block(taskDirectory)
        if (!keep) {
            taskDirectory.deleteRecursivelyCatching().getOrThrow()
        }
        return result
    }

    /**
     * Creates the working directory of a task named [name], pruning the directories left behind by expired ones first.
     */
    private fun createTaskDirectory(name: String): Path {
        prune()
        return directory.createTemporaryDirectory("$name-${IDS.next()}-")
    }

    /**
     * Removes every working directory that has outlived [retention], throwing the first failure once every one has
     * been tried.
     */
    private fun prune() {
        val deadline = Instant.now().minusMillis(retention.inWholeMilliseconds)
        val failures =
            listTaskDirectories()
                .filter { it.getLastModifiedTime().toInstant() < deadline }
                .mapNotNull { it.deleteRecursivelyCatching().exceptionOrNull() }
        failures.firstOrNull()?.let { throw it }
    }

    /**
     * The working directories under [directory], none if [directory] does not exist.
     */
    private fun listTaskDirectories(): List<Path> =
        when {
            directory.exists() -> directory.listDirectoryEntries().filter { it.isDirectory() }
            else -> emptyList()
        }

    companion object {
        /**
         * Whether the working directory of a successful task is kept by default.
         */
        private const val DEFAULT_KEEP = false

        /**
         * How long a leftover working directory is kept by default.
         */
        private val DEFAULT_RETENTION = 7.days

        /**
         * The source of the timestamps the name of a working directory carries.
         */
        private val IDS = TimestampId()

        /**
         * The workspace a caller that names none runs its tasks in.
         */
        val DEFAULT: Workspace by lazy { fromProperties() }

        /**
         * The workspace [properties] configures, under the tasks directory of the given [directories].
         */
        fun fromProperties(
            properties: PropertySource = PropertySource.DEFAULT,
            directories: Directories = Directories.DEFAULT,
        ): Workspace =
            Workspace(
                directory = directories.tasks,
                keep = properties.get("tasks.keep") { it.toBoolean() } ?: DEFAULT_KEEP,
                retention = properties.duration("tasks.retention") ?: DEFAULT_RETENTION,
            )
    }
}
