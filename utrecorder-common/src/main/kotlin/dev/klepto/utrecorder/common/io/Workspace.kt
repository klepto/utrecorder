package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.TimestampId
import dev.klepto.utrecorder.common.getOrNull
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.mergeAndThrow
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.common.withBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Scratch directory of one run, which names the files the run writes and tracks them until the run ends.
 *
 * Open one with [workspace]. A workspace exists only for the duration of the block that opened it.
 */
class Workspace private constructor(
    private val name: String,
    private val id: String,
    private val workingDirectory: Path?,
    private val parent: Workspace?,
    private val config: DirectoriesConfig,
) {
    private val prefix: String = "$name-$id"

    /**
     * Directory under the workspace directory of [config] that holds the id of this workspace.
     */
    private val claimedDirectory: Path = config.workspace[prefix]

    /**
     * Directory under the workspace directory of [config] that the tracked files end up in.
     *
     * A nested workspace uses the directory of its parent.
     */
    private val outputDirectory: Path = parent?.outputDirectory ?: claimedDirectory

    /**
     * Pattern that matches the name of a file that a workspace of this name wrote into a shared working directory.
     */
    private val sharedFilePattern: Regex = Regex("""^${Regex.escape(name)}-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-""")

    /**
     * Directory this workspace names its files in.
     *
     * This is the working directory when one is given, and the output directory otherwise.
     */
    val directory: Path = workingDirectory ?: outputDirectory

    /**
     * Whether this workspace holds a directory of its own, the one [delete] removes along with the files it tracks.
     */
    private val ownsDirectory: Boolean = workingDirectory == null && parent == null

    private val registry: PathRegistry =
        when {
            ownsDirectory -> PathRegistry("", directory)
            else -> PathRegistry("$prefix-", directory)
        }

    /**
     * Returns the path of a new file named [name] in [directory], and tracks it.
     *
     * Unless this workspace holds a directory of its own, the file name carries the workspace name and id as a prefix.
     */
    operator fun get(name: String): Path = registry.newFile(name).also(::track)

    /**
     * Returns a new temporary file in [directory], and tracks it.
     */
    suspend fun tempFile(): Path = registry.tempFile().also(::track)

    /**
     * Returns a new temporary directory in [directory], and tracks it.
     */
    suspend fun tempDirectory(): Path = registry.tempDirectory().also(::track)

    /**
     * Tracks [path] in this workspace and in every workspace around it.
     */
    fun track(path: Path) {
        registry.register(path)
        parent?.track(path)
    }

    /**
     * Removes the files this workspace tracks and, when it holds a directory of its own, that directory.
     *
     * Nothing calls this on its own: a workspace is kept until pruning removes it, unless the block removes it here.
     */
    suspend fun delete() {
        registry.delete()
        if (ownsDirectory) directory.deleteCatching().getOrThrow()
    }

    /**
     * Moves the files this workspace tracks into [target], then removes the workspace behind them.
     */
    suspend fun move(target: Path) {
        persist(target)
        delete()
    }

    /**
     * Runs [block] in this workspace, then moves the tracked files into the output directory.
     *
     * Before [block] runs, removes expired entries. A failure to remove them is logged and does not stop [block].
     * If [block] throws, rethrows its exception with a failure to move the tracked files added as suppressed.
     */
    private suspend fun <T> enter(block: suspend Workspace.(Workspace) -> T): T {
        directory.createDirectories()
        suspendCatching { prune() }.onFailure { log.warn(it) { "Failed to prune expired workspace entries" } }
        val outcome = runCatching { block(this) }
        val exit = exitCatching()
        return outcome
            .onFailure { failure -> exit.exceptionOrNull()?.let(failure::addSuppressed) }
            .getOrThrow()
            .also { exit.getOrThrow() }
    }

    /**
     * Returns the outcome of moving the tracked files into the output directory and removing it when left empty.
     *
     * Runs even when the calling coroutine is cancelled.
     */
    private suspend fun exitCatching(): Result<Unit> =
        withContext(NonCancellable) {
            runCatching {
                persist()
                deleteIfEmpty()
            }
        }

    /**
     * Moves the tracked files into [target], each by its location relative to [directory].
     *
     * A file that is already at its target stays in place.
     */
    private suspend fun persist(target: Path = outputDirectory) {
        registry
            .getRoots()
            .associateWith { target.resolve(registry.relativize(it)).toAbsolutePath().normalize() }
            .filter { (source, destination) -> source != destination }
            .mapNotNull { (source, destination) -> suspendCatching { source.move(destination) }.exceptionOrNull() }
            .mergeAndThrow()
    }

    /**
     * Removes the claimed directory when the workspace left nothing there.
     *
     * A nested workspace always leaves its claimed directory empty, since its files end up in the directory of its parent.
     */
    private suspend fun deleteIfEmpty() {
        withContext(Dispatchers.IO) {
            if (claimedDirectory.isDirectory() && claimedDirectory.listDirectoryEntries().isEmpty()) {
                claimedDirectory.deleteIfExists()
            }
        }
    }

    /**
     * Removes the expired entries of the workspace directory and of the shared working directory.
     *
     * Only a top-level workspace removes entries of the workspace directory.
     */
    private suspend fun prune() {
        expiredEntries(Clock.System.now() - RETENTION)
            .mapNotNull { it.deleteCatching().exceptionOrNull() }
            .mergeAndThrow()
    }

    /**
     * Returns the entries last modified before [deadline].
     *
     * These are entries of the workspace directory and files of the shared working directory that match
     * [sharedFilePattern].
     */
    private suspend fun expiredEntries(deadline: Instant): List<Path> =
        withContext(Dispatchers.IO) {
            val workspaceEntries =
                when (parent) {
                    null -> config.workspace.createDirectories().listDirectoryEntries()
                    else -> emptyList()
                }
            val sharedEntries =
                workingDirectory
                    ?.listDirectoryEntries()
                    .orEmpty()
                    .filter { sharedFilePattern.containsMatchIn(it.fileName.toString()) }
            (workspaceEntries + sharedEntries)
                .filter { it.getLastModifiedTime().toInstant().toKotlinInstant() < deadline }
        }

    companion object {
        /**
         * How long an entry left behind by a workspace is kept.
         */
        private val RETENTION = 3.days

        /**
         * Source of the ids that workspaces carry.
         */
        private val IDS = TimestampId()

        private val log = logger<Workspace>()

        /**
         * Returns the result of [block], run in a new workspace named [name].
         *
         * The workspace is nested in the workspace of the calling coroutine, if there is one.
         */
        suspend fun <T> scope(
            name: String,
            workingDirectory: Path?,
            config: DirectoriesConfig,
            block: suspend Workspace.(Workspace) -> T,
        ): T {
            val id = claim(name, config)
            val workspace = Workspace(name, id, workingDirectory, getOrNull<Workspace>(), config)
            return withBinding(workspace) {
                withContext(Dispatchers.IO) { workspace.enter(block) }
            }
        }

        /**
         * Returns a new id for a workspace named [name], held by creating its directory under the workspace directory of
         * [config].
         *
         * An id whose directory already exists, such as one held by another process, is skipped for the next one.
         */
        private suspend fun claim(
            name: String,
            config: DirectoriesConfig,
        ): String =
            withContext(Dispatchers.IO) {
                val parent = config.workspace.createDirectories()
                generateSequence(IDS::next).first { parent["$name-$it"].createDirectoryIfAbsent() }
            }

        /**
         * Returns `true` if this directory was created, and `false` if something already exists at this path.
         */
        private fun Path.createDirectoryIfAbsent(): Boolean =
            try {
                Files.createDirectory(this)
                true
            } catch (exists: FileAlreadyExistsException) {
                false
            }
    }
}

/**
 * Returns the result of [block], run in a new workspace named [name].
 *
 * If [workingDirectory] is not provided, the workspace names its files in a directory of its own instead. If [config]
 * is not provided, uses the directories read from the properties and the environment instead.
 *
 * If the calling coroutine already runs in a workspace, the new workspace is nested in it. A nested workspace has no
 * directory of its own: its files end up in the directory of the workspace around it, named with the workspace name and
 * id as a prefix.
 */
suspend fun <T> workspace(
    name: String = "task",
    workingDirectory: Path? = null,
    config: DirectoriesConfig = DirectoriesConfig(),
    block: suspend Workspace.(Workspace) -> T,
): T = Workspace.scope(name, workingDirectory, config, block)
