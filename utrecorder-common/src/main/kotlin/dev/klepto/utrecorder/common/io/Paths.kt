package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.PropertySource
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
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Returns a path obtained by resolving the given [name] on this path.
 */
operator fun Path.get(name: String): Path = resolve(name)

/**
 * Returns a path obtained by resolving the given [name] on this path.
 */
operator fun Path.get(name: PathName): Path = get(name.pathName)

/**
 * A constant that names one path segment, so that a caller resolves it with [get] rather than repeating the literal.
 *
 * @property pathName The segment this constant resolves to.
 */
interface PathName {
    val pathName: String
}

/**
 * Returns this string as an absolute, normalized path.
 */
fun String.toAbsolutePath(): Path = Paths.get(this).toAbsolutePath().normalize()

/**
 * Returns the home directory of the user this process runs as, read from [properties].
 */
fun homeDirectory(properties: PropertySource = PropertySource.DEFAULT): Path =
    checkNotNull(properties.property("user.home")) { "No user.home system property" }.toAbsolutePath()

/**
 * Returns the directory this path sits in, creating it if it does not exist.
 */
fun Path.createParentDirectory(): Path = toAbsolutePath().parent.apply { createDirectories() }

/**
 * Creates a uniquely named directory under this directory, creating this directory first if it does not exist.
 */
fun Path.createTemporaryDirectory(prefix: String): Path {
    createDirectories()
    return createTempDirectory(this, prefix)
}

/**
 * Creates a temporary file next to this path, named after it. The file a caller stages into and the file it becomes
 * sit on one filesystem, so that putting it in place is a rename rather than a copy.
 */
fun Path.createTemporaryFile(): Path = createTempFile(createParentDirectory(), "$fileName-", ".tmp")

/**
 * Runs [block] against a staging directory created next to this path, and removes the directory once it returns or
 * throws. Nothing the block staged survives a failure.
 */
fun <T> Path.stage(block: (Path) -> T): T {
    val staging = createParentDirectory().createTemporaryDirectory("$fileName-")
    try {
        return block(staging)
    } finally {
        staging.deleteRecursivelyCatching()
    }
}

/**
 * Copies the file to the given [target] path. Throws if this path holds no file, with the message [failureMessage]
 * returns.
 */
fun Path.copy(
    target: Path,
    failureMessage: () -> String = { "No file to copy at $this" },
) {
    check(exists(), failureMessage)
    copyTo(target)
}

/**
 * Moves the file to the given [target] path, replacing whatever is already there.
 *
 * Directories are merged into [target] one file at a time, overwriting the files it already holds and leaving the
 * ones this directory does not contain.
 */
@OptIn(ExperimentalPathApi::class)
fun Path.move(target: Path) {
    if (!isDirectory()) {
        target.parent?.createDirectories()
        moveFile(target)
        return
    }

    walk(PathWalkOption.INCLUDE_DIRECTORIES)
        .toList()
        .forEach { source ->
            val destination = target.resolve(relativize(source))
            if (source.isDirectory()) {
                destination.createDirectories()
            } else {
                destination.parent?.createDirectories()
                source.moveFile(destination)
            }
        }

    deleteRecursively()
}

/**
 * Moves a single file, preferring an atomic rename and falling back to a replacing move when the filesystem doesn't
 * support atomic rename.
 */
private fun Path.moveFile(target: Path) =
    try {
        moveTo(target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        moveTo(target, StandardCopyOption.REPLACE_EXISTING)
    }

/**
 * Returns the lines of the file at this path decoded with [charset], or no lines if the file does not exist.
 */
fun Path.readLinesOrEmpty(charset: Charset = Charsets.UTF_8): List<String> =
    when {
        exists() -> readLines(charset)
        else -> emptyList()
    }

/**
 * Returns the contents of the file at this path decoded with [charset], or an empty string if the file does not exist.
 */
fun Path.readTextOrEmpty(charset: Charset = Charsets.UTF_8): String =
    when {
        exists() -> readText(charset)
        else -> ""
    }

/**
 * Calculates the digest of the file using the given [algorithm] (e.g. "MD5", "SHA-256").
 */
fun Path.digest(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    inputStream().use { input ->
        DigestInputStream(input, digest).use { it.transferTo(OutputStream.nullOutputStream()) }
    }
    return digest.digest().toHex()
}

/**
 * Converts the byte array to a hexadecimal string.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Removes this path and everything under it, and returns the outcome. A path a caller cannot remove — a directory
 * holding a file another process still has open, typically — is left in place, and the failure is the caller's to read.
 */
@OptIn(ExperimentalPathApi::class)
fun Path.deleteRecursivelyCatching(): Result<Unit> = catchingIo { deleteRecursively() }

/**
 * Runs [block] and returns its outcome, holding an [IOException] it throws as a failure. Every other failure is the
 * caller's, so it is left to propagate rather than turned into a result nothing reads.
 */
private inline fun <T> catchingIo(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cause: IOException) {
        Result.failure(cause)
    }
