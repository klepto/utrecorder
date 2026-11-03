package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.readLinesOrEmpty
import dev.klepto.utrecorder.common.io.replaceIfChanged
import dev.klepto.utrecorder.common.logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Cache of the packages fetched into an installation for the demos it plays.
 *
 * A cache package is a file of one of the [UtDirectory.PACKAGES] directories of the installation, with the extension
 * of that directory, that the base manifest of the installation does not list and that the call does not keep. A file
 * counts as listed when the manifest names its path relative to the installation, or that path plus `.uz`, compared
 * ignoring case. Only cache packages are ever deleted, and without a base manifest no file is one.
 *
 * [resolve] deletes the cache packages unused for longer than the package TTL, installs the packages a demo names,
 * and records the last use of every cache package that satisfies one of them. [evict] only deletes. A cache package
 * with no recorded use is used at the first call that sees it. The last uses are kept in a file of the installation,
 * so a new cache of the same installation reads them. Calls on one installation directory run one at a time, across
 * every cache of that directory.
 *
 * @property installation The installation the packages are held in.
 * @property config The package TTL, the sources [resolve] fetches packages from, and the directories the last uses are
 * written under.
 * @property clock The source of the time a package is used at, and that the package TTL is counted to.
 */
class UtPackageCache(
    private val installation: UtInstallation,
    private val config: UtConfig = installation.config,
    private val clock: Clock = Clock.System,
) {
    /**
     * The root of the installation, absolute and normalized.
     */
    private val directory: Path = installation.directory.toAbsolutePath().normalize()

    /**
     * The lock every call on [directory] holds for its whole run.
     */
    private val lock: Mutex = LOCKS.computeIfAbsent(directory) { Mutex() }

    /**
     * The base manifest, listing the core game files of the installation.
     */
    private val manifestPath: Path = directory[MANIFEST_FILE]

    /**
     * The last use of each cache package, one `<relative path>\t<ISO-8601 instant>` line per package.
     */
    private val lastUsesPath: Path = directory[LAST_USES_FILE]

    /**
     * Deletes the expired cache packages, installs [packages] as [UtPackageInstaller.install] does, then records the
     * time [clock] reports as the last use of every cache package that satisfies one of [packages], the ones the
     * installation held before the call included. The files in [keep] are never cache packages, so this call neither
     * deletes nor records them.
     *
     * @throws UtPackageException If no source holds one of [packages]. The packages fetched before it stay in the
     * installation, and their last use is recorded.
     */
    suspend fun resolve(
        packages: List<UtPackage>,
        keep: Collection<Path> = emptyList(),
        onProgress: ProgressListener<Unit> = {},
    ) {
        val kept = keep.map { it.toAbsolutePath().normalize() }.toSet()
        lock.withLock {
            deleteExpired(kept)
            try {
                UtPackageInstaller(packages, installation, config).install(onProgress)
            } finally {
                withContext(NonCancellable) { recordUses(packages, kept) }
            }
        }
    }

    /**
     * Deletes every cache package whose last use is more than the package TTL before the time [clock] reports.
     */
    suspend fun evict() {
        lock.withLock { deleteExpired(emptySet()) }
    }

    /**
     * Deletes every cache package outside [kept] whose last use is more than the package TTL ago, and writes the last
     * uses of the cache packages left. A package that fails to delete is kept, with its last use, for the next call.
     * Without a base manifest, deletes and writes nothing.
     */
    private suspend fun deleteExpired(kept: Set<Path>) {
        val cached =
            cachePackages(kept) ?: run {
                log.warn { "No base manifest at $manifestPath, so no package of $directory is deleted" }
                return
            }
        val now = clock.now()
        val lastUses = readLastUses()
        val remaining = mutableMapOf<String, Instant>()
        cached.forEach { (relativePath, path) ->
            val lastUse = lastUses[relativePath] ?: now
            if (now - lastUse <= config.packageTtl || !delete(path, relativePath, lastUse)) {
                remaining[relativePath] = lastUse
            }
        }
        writeLastUses(remaining)
    }

    /**
     * Deletes the cache package at [path], last used at [lastUse], and returns `true` if it is gone. If the delete
     * fails, logs it and returns `false` instead.
     */
    private suspend fun delete(
        path: Path,
        relativePath: String,
        lastUse: Instant,
    ): Boolean =
        path
            .deleteCatching()
            .onSuccess { log.debug { "Deleted $relativePath, last used at $lastUse" } }
            .onFailure { log.warn(it) { "Failed to delete $relativePath, trying again on the next call" } }
            .isSuccess

    /**
     * Records the time [clock] reports as the last use of every cache package outside [kept] that satisfies one of
     * [packages], and drops the last use of every file that is no cache package. Without a base manifest, records
     * nothing.
     */
    private suspend fun recordUses(
        packages: List<UtPackage>,
        kept: Set<Path>,
    ) {
        val cached = cachePackages(kept) ?: return
        val now = clock.now()
        val lastUses = readLastUses().filterKeys { it in cached }.toMutableMap()
        cached.forEach { (relativePath, path) ->
            val held = UtPackage.read(path)
            if (packages.any { held.satisfies(it) }) lastUses[relativePath] = now
        }
        writeLastUses(lastUses)
    }

    /**
     * Returns the files of the installation, by their `/`-separated path relative to [directory], that are cache
     * packages when the files in [kept] are not. Without a base manifest, returns `null` instead.
     */
    private suspend fun cachePackages(kept: Set<Path>): Map<String, Path>? {
        val manifest = readManifest() ?: return null
        return UtDirectory.PACKAGES
            .flatMap { packageDirectory ->
                installation.packages(packageDirectory).mapNotNull { held ->
                    val path = checkNotNull(held.path) { "No file holds the package ${held.name}" }.toAbsolutePath().normalize()
                    val relativePath = "${packageDirectory.pathName}/${path.fileName}"
                    (relativePath to path).takeUnless { path in kept || relativePath.isListedIn(manifest) }
                }
            }.toMap()
    }

    /**
     * Returns the paths the base manifest lists, in lower case. Without a base manifest, returns `null` instead.
     */
    private suspend fun readManifest(): Set<String>? {
        if (!manifestPath.fileExists()) return null
        return manifestPath
            .readLinesOrEmpty()
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
    }

    /**
     * Returns the recorded last use of each cache package, by its relative path. Skips a line that holds no last use,
     * with a warning, so its package counts as unrecorded.
     */
    private suspend fun readLastUses(): Map<String, Instant> =
        lastUsesPath
            .readLinesOrEmpty()
            .mapNotNull { parseLastUse(it) }
            .toMap()

    /**
     * Returns the relative path and the last use [line] holds. If [line] holds none, logs it and returns `null`
     * instead.
     */
    private fun parseLastUse(line: String): Pair<String, Instant>? {
        val relativePath = line.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val lastUse =
            try {
                Instant.parse(line.substringAfterLast(SEPARATOR))
            } catch (malformed: IllegalArgumentException) {
                log.warn(malformed) { "Skipping the malformed line \"$line\" of $lastUsesPath" }
                return null
            }
        if (relativePath.isEmpty()) {
            log.warn { "Skipping the line \"$line\" of $lastUsesPath, which names no package" }
            return null
        }
        return relativePath to lastUse
    }

    /**
     * Replaces the file of last uses with [lastUses], sorted by relative path, in one move.
     */
    private suspend fun writeLastUses(lastUses: Map<String, Instant>) {
        val text = lastUses.toSortedMap().entries.joinToString("") { (relativePath, lastUse) -> "$relativePath$SEPARATOR$lastUse\n" }
        lastUsesPath.replaceIfChanged(text.toByteArray(Charsets.UTF_8), config.directoriesConfig)
    }

    private companion object {
        /**
         * The name of the base manifest in the installation directory.
         */
        const val MANIFEST_FILE = ".utrecorder.base"

        /**
         * The name of the file of last uses in the installation directory.
         */
        const val LAST_USES_FILE = ".utrecorder.packages"

        /**
         * The character separating the relative path of a package from its last use, in the file of last uses.
         */
        const val SEPARATOR = '\t'

        /**
         * The suffix of the archive the base installation holds a map in before it is decompressed.
         */
        const val ARCHIVE_SUFFIX = ".uz"

        val log = logger<UtPackageCache>()

        /**
         * The lock of each installation directory a cache has run in.
         */
        val LOCKS = ConcurrentHashMap<Path, Mutex>()

        /**
         * Returns `true` when [manifest], in lower case, lists this relative path, or this path plus [ARCHIVE_SUFFIX],
         * comparing ignoring case.
         */
        fun String.isListedIn(manifest: Set<String>): Boolean = lowercase().let { it in manifest || "$it$ARCHIVE_SUFFIX" in manifest }
    }
}
