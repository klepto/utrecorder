package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.log.catchingIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.time.Duration

/**
 * This string as an absolute, normalized path, resolved against the working directory when relative.
 */
fun String.toAbsolutePath(): Path = Paths.get(this).toAbsolutePath().normalize()

/**
 * A constant naming one path segment, resolved on a path through [get].
 *
 * @property pathName The segment this constant resolves to.
 */
interface PathName {
    val pathName: String
}

/**
 * The child of this path called [name].
 */
operator fun Path.get(name: String): Path = resolve(name)

/**
 * The child of this path called by the segment [name] holds.
 */
operator fun Path.get(name: PathName): Path = get(name.pathName)

/**
 * The working directory of the process [properties] describe, read from `user.dir` as an absolute, normalized path.
 *
 * @throws IllegalStateException if [properties] holds no `user.dir`.
 */
fun workingDirectory(properties: PropertySource = PropertySource.DEFAULT): Path =
    checkNotNull(properties.systemProperty("user.dir")) { "No user.dir system property" }.toAbsolutePath()

/**
 * Copies the file at this path to [target], creating the parent directories of [target], and returns [target].
 *
 * @throws IllegalStateException with the message [failureMessage] returns, if this path holds nothing.
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
 * Moves the file at this path to [target], replacing whatever is already there.
 *
 * A directory is merged into [target] one file at a time, overwriting the files [target] already holds and keeping the
 * ones this directory does not contain.
 */
@OptIn(ExperimentalPathApi::class)
suspend fun Path.move(target: Path): Unit =
    withContext(Dispatchers.IO) {
        if (!isDirectory()) {
            moveFile(target.createParentDirectories())
            return@withContext
        }

        walk(PathWalkOption.INCLUDE_DIRECTORIES)
            .toList()
            .forEach { source ->
                val destination = target.resolve(relativize(source))
                if (source.isDirectory()) {
                    destination.createDirectories()
                } else {
                    source.moveFile(destination.createParentDirectories())
                }
            }

        deleteRecursively()
    }

/**
 * Moves a single file by atomic rename, or by a replacing move on a filesystem without atomic rename.
 */
private fun Path.moveFile(target: Path) =
    try {
        moveTo(target, StandardCopyOption.ATOMIC_MOVE)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        moveFileReplacing(target, unsupported)
    }

/**
 * Moves a single file by a replacing move, attaching [unsupported] to the failure if the move fails too.
 */
private fun Path.moveFileReplacing(
    target: Path,
    unsupported: AtomicMoveNotSupportedException,
): Path =
    try {
        moveTo(target, StandardCopyOption.REPLACE_EXISTING)
    } catch (failure: IOException) {
        throw failure.apply { addSuppressed(unsupported) }
    }

/**
 * The lines of the file at this path decoded with [charset], or no lines if the file does not exist.
 */
suspend fun Path.readLinesOrEmpty(charset: Charset = Charsets.UTF_8): List<String> =
    withContext(Dispatchers.IO) {
        when {
            exists() -> readLines(charset)
            else -> emptyList()
        }
    }

/**
 * The contents of the file at this path decoded with [charset], or an empty string if the file does not exist.
 */
suspend fun Path.readTextOrEmpty(charset: Charset = Charsets.UTF_8): String =
    withContext(Dispatchers.IO) {
        when {
            exists() -> readText(charset)
            else -> ""
        }
    }

/**
 * The digest of the file at this path under [algorithm], such as `MD5` or `SHA-256`, as lowercase hexadecimal.
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
 * This array as lowercase hexadecimal, two digits per byte.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * The outcome of removing this path and everything under it, tried up to [attempts] times with [delay] between
 * attempts. A path that does not exist is a success, and when no attempt succeeds the failure is that of the last one.
 * Only an [IOException] is held as a failure.
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
 * The outcome of removing this path and everything under it once, after waiting [after].
 */
@OptIn(ExperimentalPathApi::class)
private fun Path.deleteOnceCatching(after: Duration = Duration.ZERO): Result<Unit> {
    Thread.sleep(after.inWholeMilliseconds)
    return catchingIo { deleteRecursively() }
}
