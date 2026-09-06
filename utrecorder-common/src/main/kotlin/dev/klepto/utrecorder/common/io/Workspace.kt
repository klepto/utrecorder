package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The directory tasks run in, and how it treats what they leave behind.
 *
 * @param directory The directory the working directory of a task is created under.
 * @param keep Whether the working directory of a successful task is kept.
 * @param retention How long the working directory of a failed task is kept before a later task removes it.
 * @author Augustinas R. <http://github.com/klepto>
 */
class Workspace(
    val directory: Path = Directories.DEFAULT.tasks,
    val keep: Boolean = DEFAULT_KEEP,
    val retention: Duration = DEFAULT_RETENTION,
) {
    /**
     * Runs the task named [name] held by [block] in a working directory of its own, and returns what it returns.
     *
     * The directory is removed once [block] returns and kept once it throws, so that what survives a run is exactly
     * the diagnostics of the runs that failed. A workspace built with [keep] keeps the directory of a successful run
     * too.
     *
     * Nothing under the directory outlives the call: a [block] producing a file the caller keeps has to move or copy
     * it out before it returns.
     */
    fun <T> task(
        name: String,
        block: (Path) -> T,
    ): T {
        val taskDirectory = createTaskDirectory(name)
        val result = block(taskDirectory)
        if (!keep) {
            taskDirectory.deleteRecursivelyQuietly()
        }
        return result
    }

    /**
     * Creates the working directory of a task named [name], pruning the directories left behind by expired ones first.
     *
     * The name carries a timestamp so that leftovers read in the order they were produced, and the directory is
     * created rather than resolved, so that two tasks starting within the same millisecond never share one.
     */
    private fun createTaskDirectory(name: String): Path {
        prune()
        return directory.createTemporaryDirectory("$name-${LocalDateTime.now().format(TIMESTAMP)}-")
    }

    /**
     * Removes every working directory that has outlived [retention]. Failed tasks are unbounded and nothing
     * else ever collects them.
     */
    private fun prune() {
        val deadline = Instant.now().minusMillis(retention.inWholeMilliseconds)
        val expired =
            try {
                directory
                    .listDirectoryEntries()
                    .filter { it.isDirectory() && it.getLastModifiedTime().toInstant() < deadline }
            } catch (_: IOException) {
                return
            }
        expired.forEach(Path::deleteRecursivelyQuietly)
    }

    companion object {
        /**
         * The workspace a caller that names none runs its tasks in. Resolved on first use rather than at class load.
         */
        val DEFAULT: Workspace by lazy { fromProperties() }

        /**
         * Builds the workspace [properties] configures, under the tasks directory of the given [directories].
         */
        fun fromProperties(
            properties: PropertySource = PropertySource.DEFAULT,
            directories: Directories = Directories.DEFAULT,
        ): Workspace =
            Workspace(
                directory = directories.tasks,
                keep = properties.get("tasks.keep") { it.toBoolean() } ?: DEFAULT_KEEP,
                retention = properties.get("tasks.retention") { Duration.parse(it) } ?: DEFAULT_RETENTION,
            )

        /**
         * Whether the working directory of a successful task is kept by default. Leftovers are the diagnostics of a
         * failed run, so a successful run leaves none behind unless a caller asks for it.
         */
        private const val DEFAULT_KEEP = false

        /**
         * How long a leftover working directory is kept by default.
         */
        private val DEFAULT_RETENTION = 7.days

        /**
         * The timestamp the name of a working directory carries.
         */
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}
