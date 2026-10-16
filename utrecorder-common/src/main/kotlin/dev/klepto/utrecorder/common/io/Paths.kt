package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.suspendCatchingIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.time.Duration

/**
 * Returns this string as an absolute, normalized path, resolved against the working directory when relative.
 */
fun String.toAbsolutePath(): Path = Paths.get(this).toAbsolutePath().normalize()

/**
 * Returns the working directory of this process, read from `user.dir` as an absolute, normalized path.
 *
 * @throws IllegalStateException if the `user.dir` system property is not set.
 */
fun workingDirectory(): Path = checkNotNull(System.getProperty("user.dir")) { "No user.dir system property" }.toAbsolutePath()

/**
 * A constant naming one path segment, resolved on a path through [get].
 *
 * @property pathName The segment this constant resolves to.
 */
interface PathName {
    val pathName: String
}

/**
 * Returns the child of this path called [name].
 */
operator fun Path.get(name: String): Path = resolve(name)

/**
 * Returns the child of this path called by the segment that [name] holds.
 */
operator fun Path.get(name: PathName): Path = this[name.pathName]

/**
 * Returns `true` if this string names a file in the directory it is resolved against, and no other location.
 *
 * A plain file name is not blank, is not `.` or `..`, and holds no `/`, `\`, `:` or null character.
 */
fun String.isPlainFileName(): Boolean = isNotBlank() && this != "." && this != ".." && none { it in UNSAFE_NAME_CHARACTERS }

/**
 * The characters a plain file name must not hold.
 */
private const val UNSAFE_NAME_CHARACTERS = "/\\:\u0000"

/**
 * Returns `true` if a file or directory exists at this path.
 */
suspend fun Path.fileExists(): Boolean = withContext(Dispatchers.IO) { exists() }

/**
 * Returns the lines of the file at this path, decoded with [charset].
 *
 * If the file does not exist, returns an empty list instead.
 */
suspend fun Path.readLinesOrEmpty(charset: Charset = Charsets.UTF_8): List<String> =
    withContext(Dispatchers.IO) {
        when {
            exists() -> readLines(charset)
            else -> emptyList()
        }
    }

/**
 * Returns the contents of the file at this path, decoded with [charset].
 *
 * If the file does not exist, returns an empty string instead.
 */
suspend fun Path.readTextOrEmpty(charset: Charset = Charsets.UTF_8): String =
    withContext(Dispatchers.IO) {
        when {
            exists() -> readText(charset)
            else -> ""
        }
    }

/**
 * Returns the digest of the file at this path under [algorithm], such as `MD5` or `SHA-256`, as lowercase hexadecimal.
 */
suspend fun Path.digest(algorithm: String): String =
    withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(algorithm)
        inputStream().use { input ->
            DigestInputStream(input, digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        }
        digest.digest().toHex()
    }

/**
 * Returns this array as lowercase hexadecimal, two digits per byte.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Copies the file at this path to [target], creating the parent directories of [target], and returns [target].
 *
 * @throws IllegalStateException with the message that [failureMessage] returns, if this path holds nothing.
 * @throws java.nio.file.FileAlreadyExistsException if [target] exists and [overwrite] is `false`.
 */
suspend fun Path.copy(
    target: Path,
    overwrite: Boolean = false,
    failureMessage: () -> String = { "No file to copy at $this" },
): Path =
    withContext(Dispatchers.IO) {
        check(exists(), failureMessage)
        copyTo(target.createParentDirectories(), overwrite)
    }

/**
 * Creates [target] as a hard link to the file at this path, creating the parent directories of [target], and returns
 * [target].
 *
 * @throws IllegalStateException with the message that [failureMessage] returns, if this path holds nothing.
 * @throws java.nio.file.FileAlreadyExistsException if [target] exists.
 * @throws IOException if the filesystem cannot link [target] to this path, such as across filesystems.
 */
suspend fun Path.link(
    target: Path,
    failureMessage: () -> String = { "No file to link at $this" },
): Path =
    withContext(Dispatchers.IO) {
        check(exists(), failureMessage)
        Files.createLink(target.createParentDirectories(), this@link)
    }

/**
 * Moves the file or directory at this path to [target], creating the parent directories of [target], and returns
 * [target].
 *
 * A file replaces the file already at [target]. A directory is merged into [target] one file at a time. The merge
 * overwrites the files [target] already holds and keeps the ones this directory does not contain.
 */
suspend fun Path.move(target: Path): Path =
    withContext(Dispatchers.IO) {
        when {
            isDirectory() -> moveDirectory(target)
            else -> moveFile(target.createParentDirectories())
        }
        target
    }

/**
 * Merges this directory into [target] one file at a time, then deletes what is left of this directory.
 */
@OptIn(ExperimentalPathApi::class)
private fun Path.moveDirectory(target: Path) {
    walk(PathWalkOption.INCLUDE_DIRECTORIES)
        .toList()
        .forEach { source ->
            val destination = target.resolve(relativize(source))
            when {
                source.isDirectory() -> destination.createDirectories()
                else -> source.moveFile(destination.createParentDirectories())
            }
        }
    deleteRecursively()
}

/**
 * Moves a single file by atomic rename, or by a staged copy when the atomic rename is not supported, such as across
 * filesystems.
 *
 * Either way, [target] never holds part of the file.
 */
private fun Path.moveFile(target: Path): Path =
    try {
        moveTo(target, StandardCopyOption.ATOMIC_MOVE)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        moveFileStaged(target, unsupported)
    }

/**
 * Moves a single file by a staged copy to [target], then deletes the file at this path. If the move fails too,
 * attaches [unsupported] to the failure.
 */
private fun Path.moveFileStaged(
    target: Path,
    unsupported: AtomicMoveNotSupportedException,
): Path =
    try {
        copyStaged(target).also { deleteExisting() }
    } catch (failure: IOException) {
        throw failure.apply { addSuppressed(unsupported) }
    }

/**
 * Copies the file at this path to a temporary file in the directory of [target], then renames the temporary file to
 * [target] in one step, and returns [target].
 *
 * If the copy or the rename fails, deletes the temporary file.
 */
private fun Path.copyStaged(target: Path): Path {
    val staged = createTempFile(target.parent, ".${target.fileName}-", ".tmp")
    return try {
        copyTo(staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            .moveTo(target, StandardCopyOption.ATOMIC_MOVE)
    } catch (failure: IOException) {
        throw failure.apply { runCatching { staged.deleteIfExists() }.exceptionOrNull()?.let(::addSuppressed) }
    }
}

/**
 * Writes [bytes] to the file at this path unless the file already holds them, and returns this path.
 *
 * The bytes are written to a file of a workspace opened in the directory of this path, which then replaces this path
 * in one move. Calls in this process that write into one directory run one at a time. If [config] is not provided,
 * uses the directories read from the properties and the environment instead.
 */
suspend fun Path.replaceIfChanged(
    bytes: ByteArray,
    config: DirectoriesConfig = DirectoriesConfig(),
): Path {
    val target = toAbsolutePath().normalize()
    val lock = REPLACE_LOCKS.computeIfAbsent(target.parent) { Mutex() }
    lock.withLock {
        if (!target.holds(bytes)) target.replace(bytes, config)
    }
    return this
}

/**
 * The lock of each directory that [replaceIfChanged] has written into.
 */
private val REPLACE_LOCKS = ConcurrentHashMap<Path, Mutex>()

/**
 * Returns `true` if the file at this path exists and holds exactly [bytes].
 */
private suspend fun Path.holds(bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        exists() && fileSize() == bytes.size.toLong() && readBytes().contentEquals(bytes)
    }

/**
 * Replaces the file at this path with [bytes], staged in a workspace opened in the directory of this path.
 */
private suspend fun Path.replace(
    bytes: ByteArray,
    config: DirectoriesConfig,
) {
    workspace("replace", parent, config) { staging ->
        val staged = staging[fileName.toString()]
        withContext(Dispatchers.IO) { staged.writeBytes(bytes) }
        staged.move(this@replace)
    }
}

/**
 * Returns the outcome of deleting this path and everything under it, tried up to [attempts] times with [delay] between
 * attempts.
 *
 * A path that does not exist counts as a success. Only an [IOException] is held as a failure. If no attempt succeeds,
 * the failure is that of the last attempt. Cancellation stops the wait between attempts.
 *
 * @throws IllegalArgumentException if [attempts] is below 1.
 */
suspend fun Path.deleteCatching(
    attempts: Int = 1,
    delay: Duration = Duration.ZERO,
): Result<Unit> {
    require(attempts >= 1) { "Delete attempts must be at least 1, got $attempts" }
    return withContext(Dispatchers.IO) {
        (2..attempts).fold(deleteOnceCatching()) { outcome, _ ->
            when {
                outcome.isSuccess -> outcome
                else -> deleteOnceCatching(after = delay)
            }
        }
    }
}

/**
 * Returns the outcome of deleting this path and everything under it once, after waiting [after].
 */
@OptIn(ExperimentalPathApi::class)
private suspend fun Path.deleteOnceCatching(after: Duration = Duration.ZERO): Result<Unit> {
    delay(after)
    return suspendCatchingIo { deleteRecursively() }
}
