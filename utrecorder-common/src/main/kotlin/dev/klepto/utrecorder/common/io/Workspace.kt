package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.inject.getOrNull
import dev.klepto.utrecorder.common.inject.withBinding
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.common.log.mergeAndThrow
import dev.klepto.utrecorder.common.time.TimestampId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * @author Augustinas R. <http://github.com/klepto>
 */
class Workspace private constructor(
    private val name: String,
    private val workingDirectory: Path?,
    private val parent: Workspace?,
    private val config: DirectoriesConfig,
) {
    private val id: String = IDS.next()
    private val prefix: String = "$name-$id"

    private val outputDirectory: Path = parent?.outputDirectory ?: config.workspace[prefix]
    private val outputPattern = Regex("""^${Regex.escape(name)}-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-""")

    val directory = workingDirectory ?: outputDirectory

    /**
     * Whether this workspace holds a directory of its own, the one [delete] removes along with the files it tracks.
     */
    private val ownsDirectory: Boolean = workingDirectory == null && parent == null

    private val registry =
        if (workingDirectory != null) {
            PathRegistry("$prefix-", workingDirectory)
        } else {
            PathRegistry("", directory)
        }

    fun track(path: Path) {
        registry.register(path)
        parent?.track(path)
    }

    operator fun get(name: String): Path = registry.newFile(name).also(::track)

    suspend fun tempFile(): Path = registry.tempFile().also(::track)

    suspend fun tempDirectory(): Path = registry.tempDirectory().also(::track)

    /**
     * Removes the files this workspace tracks and, when it holds a directory of its own, that directory. Nothing calls
     * this for a consumer: a workspace is kept until pruning removes it unless the consumer removes it here.
     */
    suspend fun delete() {
        registry.delete()
        if (ownsDirectory) directory.deleteCatching().getOrThrow()
    }

    /**
     * Moves the files this workspace tracks into [directory], then removes the workspace behind them.
     */
    suspend fun move(directory: Path) {
        persist(directory)
        delete()
    }

    private suspend fun persist(directory: Path = outputDirectory) {
        withContext(Dispatchers.IO) {
            registry
                .getRoots()
                .associateWith { directory.resolve(registry.relativize(it)).toAbsolutePath().normalize() }
                .filter { (source, target) -> source != target }
                .mapNotNull { (source, target) ->
                    runCatching { source.move(target) }.exceptionOrNull()
                }.mergeAndThrow()
        }
    }

    /**
     * Removes the directory this workspace keeps its files in when the workspace left nothing there. A nested
     * workspace keeps its files in its parent's directory and leaves that directory to the parent.
     */
    private suspend fun deleteIfEmpty() {
        if (parent != null) return
        withContext(Dispatchers.IO) {
            if (outputDirectory.isDirectory() && outputDirectory.listDirectoryEntries().isEmpty()) {
                outputDirectory.deleteIfExists()
            }
        }
    }

    private suspend fun prune() {
        val deadline = Clock.System.now() - RETENTION
        withContext(Dispatchers.IO) {
            expiredEntries(deadline)
                .mapNotNull {
                    it.deleteCatching().exceptionOrNull()
                }.mergeAndThrow()
        }
    }

    private suspend fun expiredEntries(deadline: Instant): List<Path> =
        withContext(Dispatchers.IO) {
            val workspaceEntries = config.workspace.createDirectories().listDirectoryEntries()
            val workingDirEntries =
                workingDirectory?.listDirectoryEntries().orEmpty().filter {
                    outputPattern.containsMatchIn(
                        it.fileName.toString(),
                    )
                }
            (workspaceEntries + workingDirEntries).filter { it.getLastModifiedTime().toInstant().toKotlinInstant() < deadline }
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
         * Runs [block] in a new workspace named [name] and returns its result. Backs [workspace], the one way to
         * open a workspace.
         */
        suspend fun <T> scope(
            name: String,
            workingDirectory: Path?,
            config: DirectoriesConfig,
            block: suspend Workspace.(Workspace) -> T,
        ): T {
            val parent = getOrNull<Workspace>()
            val workspace = Workspace(name, workingDirectory, parent, config)
            return withBinding(workspace) {
                withContext(Dispatchers.IO) {
                    workspace.directory.createDirectories()
                    runCatching { workspace.prune() }
                    try {
                        workspace.block(workspace)
                    } finally {
                        withContext(NonCancellable) {
                            workspace.persist()
                            workspace.deleteIfEmpty()
                        }
                    }
                }
            }
        }
    }
}

suspend fun <T> workspace(
    name: String = "task",
    workingDirectory: Path? = null,
    config: DirectoriesConfig = DirectoriesConfig(),
    block: suspend Workspace.(Workspace) -> T,
): T = Workspace.scope(name, workingDirectory, config, block)
