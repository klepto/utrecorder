package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace.Companion.RETENTION
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.common.log.mergeAndThrow
import dev.klepto.utrecorder.common.time.TimestampId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Scratch space of one run: a directory of its own under [Directories.workspace], or the files it names inside a
 * shared [rootDirectory].
 *
 * Use [workspace] to run a block in one, which removes the workspace when the block returns and keeps it for inspection
 * when the block throws. [open] creates the workspace directory and removes the entries earlier workspaces left behind
 * more than 7 days ago.
 *
 * @property name The name the directory or the files of this workspace start with, followed by its id.
 * @property rootDirectory The shared directory this workspace names its files in, or `null` for a directory of its own.
 * @property directories The directories holding the workspace directory.
 */
class Workspace private constructor(
    val name: String,
    val rootDirectory: Path? = null,
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * The id that tells this workspace apart from others of the same [name].
     */
    private val id: String = IDS.next()

    /**
     * The name of this workspace followed by its id.
     */
    private val prefix: String = "$name-$id"

    /**
     * Whether this workspace names its files in a shared [rootDirectory].
     */
    private val shared: Boolean = rootDirectory != null

    /**
     * The directory of this workspace under [Directories.workspace]: its own directory, or where the files of a shared
     * workspace are moved when its block throws.
     */
    private val workspaceDirectory: Path = directories.workspace[prefix]

    /**
     * The directory the block of this workspace works in: [rootDirectory] when shared, its own directory otherwise.
     * [open] creates it on disk.
     */
    val directory: Path = rootDirectory ?: workspaceDirectory

    /**
     * The prefix of every file this workspace names, empty for a directory of its own.
     */
    private val filePrefix: String = if (shared) "$prefix-" else ""

    /**
     * The files a shared workspace named, removed or moved out when its block ends.
     */
    private val outputs: Queue<Path> = ConcurrentLinkedQueue()

    /**
     * The pattern the name of a file a workspace of this [name] left in a shared root matches.
     */
    private val sharedEntryPattern = Regex("""^${Regex.escape(name)}-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-""")

    /**
     * Returns a new empty file in [directory].
     */
    suspend fun tempFile(): Path = withContext(Dispatchers.IO) { createTempFile(directory, filePrefix) }.also(::track)

    /**
     * Returns a new empty directory in [directory].
     */
    suspend fun tempDirectory(): Path = withContext(Dispatchers.IO) { createTempDirectory(directory, filePrefix) }.also(::track)

    /**
     * Returns the path of the file [name] in [directory], prefixed with the name and id of this workspace when shared.
     * The file is not created.
     */
    fun file(name: String): Path = directory["$filePrefix$name"].also(::track)

    /**
     * Returns the directory [name] in [directory], prefixed with the name and id of this workspace when shared, creating
     * it.
     */
    suspend fun directory(name: String): Path =
        withContext(Dispatchers.IO) { directory["$filePrefix$name"].createDirectories() }.also(::track)

    /**
     * Removes this workspace: its own directory, or the files it named in the shared root.
     *
     * @throws java.io.IOException if an entry cannot be removed, once every entry has been tried.
     */
    suspend fun delete() {
        if (!shared) {
            directory.deleteCatching().getOrThrow()
            return
        }

        outputs
            .mapNotNull { it.deleteCatching().exceptionOrNull() }
            .mergeAndThrow()
    }

    /**
     * Moves the files a shared workspace named into its directory under [Directories.workspace], without their prefix.
     * Does nothing for a workspace with a directory of its own.
     *
     * @throws java.io.IOException if a file cannot be moved, once every file has been tried.
     */
    suspend fun persistShared() {
        if (!shared) return
        withContext(Dispatchers.IO) { workspaceDirectory.createDirectories() }
        val toPersist = withContext(Dispatchers.IO) { outputs.filter { it.exists() } }
        toPersist
            .mapNotNull { persistCatching(it).exceptionOrNull() }
            .mergeAndThrow()
    }

    /**
     * Moves the shared file at [path] into [workspaceDirectory], without its prefix.
     */
    private suspend fun persistCatching(path: Path): Result<Unit> =
        runCatching { path.move(workspaceDirectory[path.fileName.toString().removePrefix(filePrefix)]) }

    /**
     * Records [path] for removal or moving when this workspace is shared.
     */
    private fun track(path: Path) {
        if (shared) outputs.add(path)
    }

    /**
     * Removes every entry of [Directories.workspace] and every file of this workspace's name in the shared root that is
     * older than [RETENTION].
     *
     * @throws java.io.IOException if an entry cannot be read or removed, once every entry has been tried.
     */
    private suspend fun prune() {
        val deadline = Clock.System.now() - RETENTION
        pruneCandidates()
            .mapNotNull { pruneCatching(it, deadline).exceptionOrNull() }
            .mergeAndThrow()
    }

    /**
     * Returns the entries [prune] considers: every entry of [Directories.workspace], and the files of the shared root
     * whose name matches [sharedEntryPattern].
     */
    private suspend fun pruneCandidates(): List<Path> =
        withContext(Dispatchers.IO) {
            val owned = directories.workspace.createDirectories().listDirectoryEntries()
            val sharedEntries = rootDirectory?.listDirectoryEntries().orEmpty()
            owned + sharedEntries.filter { sharedEntryPattern.containsMatchIn(it.fileName.toString()) }
        }

    companion object {
        /**
         * How long an entry left behind by a workspace is kept.
         */
        private val RETENTION = 7.days

        /**
         * The source of the ids workspaces carry.
         */
        private val IDS = TimestampId()

        /**
         * The logger.
         */
        private val log = logger<Workspace>()

        /**
         * Creates the workspace directory of a new [Workspace] of [name], prunes the entries [directories] and
         * [rootDirectory] hold that are older than [RETENTION], and returns it.
         */
        suspend fun open(
            name: String,
            rootDirectory: Path? = null,
            directories: Directories = Directories.DEFAULT,
        ): Workspace {
            val workspace = Workspace(name, rootDirectory, directories)
            withContext(Dispatchers.IO) { workspace.directory.createDirectories() }
            runCatching { workspace.prune() }.onFailure { log.warn(it) { "Failed to prune expired workspace entries" } }
            return workspace
        }

        /**
         * Removes the entry at [path] when it was last modified before [deadline].
         */
        private suspend fun pruneCatching(
            path: Path,
            deadline: Instant,
        ): Result<Unit> =
            runCatching { withContext(Dispatchers.IO) { path.getLastModifiedTime() }.toInstant().toKotlinInstant() < deadline }
                .mapCatching { expired -> if (expired) path.deleteCatching().getOrThrow() }
    }
}

/**
 * Runs [block] in a new [Workspace] named [name] and returns what it returns.
 *
 * The block receives the workspace and its [Workspace.directory]. Without a [rootDirectory], the workspace is a
 * directory of its own under [Directories.workspace]. With one, it names its files inside [rootDirectory]. The
 * workspace is removed when the block returns or is cancelled. When the block throws, a workspace of its own is kept
 * in place and the files of a shared one are moved into a directory under [Directories.workspace], and the exception
 * is rethrown. A block producing a file the caller keeps moves or copies it out before it returns.
 *
 * @throws java.io.IOException if the workspace cannot be removed after the block returned.
 */
suspend fun <T> workspace(
    name: String = "task",
    rootDirectory: Path? = null,
    directories: Directories = Directories.DEFAULT,
    block: suspend Workspace.(Path) -> T,
): T {
    val workspace = Workspace.open(name, rootDirectory, directories)
    var ended = false
    try {
        return block(workspace, workspace.directory)
    } catch (cancelled: CancellationException) {
        ended = true
        withContext(NonCancellable) {
            runCatching { workspace.delete() }.onFailure(cancelled::addSuppressed)
        }
        throw cancelled
    } catch (cause: Exception) {
        ended = true
        runCatching { workspace.persistShared() }.onFailure(cause::addSuppressed)
        throw cause
    } catch (error: Error) {
        ended = true
        throw error
    } finally {
        if (!ended) workspace.delete()
    }
}
