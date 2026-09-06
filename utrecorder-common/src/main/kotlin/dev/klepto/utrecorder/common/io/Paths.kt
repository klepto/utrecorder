package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.PropertySource
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.walk

/**
 * Converts the byte array to a hexadecimal string.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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
 * Runs [block] against a staging directory created next to this path, and removes the directory once it returns or
 * throws.
 *
 * Staging is a sibling of what it becomes rather than a directory under `cache/`, so that moving it into place is a
 * rename within one filesystem and a cache wipe cannot destroy a materialization in flight.
 *
 * Nothing survives a failure, unlike the working directory of a task: a half-materialized target is bulk to discard
 * rather than something to read afterwards.
 */
fun <T> Path.stage(block: (Path) -> T): T {
    val staging = parentDirectory().createTemporaryDirectory("$fileName-")
    try {
        return block(staging)
    } finally {
        staging.deleteRecursivelyQuietly()
    }
}

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
fun Path.createTemporaryFile(): Path = createTempFile(parentDirectory(), "$fileName-", ".tmp")

/**
 * Returns the directory this path sits in, creating it if it does not exist.
 */
private fun Path.parentDirectory(): Path = toAbsolutePath().parent.apply { createDirectories() }

/**
 * Returns this string as an absolute, normalized path.
 */
fun String.toAbsolutePath(): Path = Paths.get(this).toAbsolutePath().normalize()

/**
 * Returns the home directory of the user this process runs as, read from [properties].
 */
fun homeDirectory(properties: PropertySource = PropertySource.DEFAULT): Path =
    checkNotNull(properties.property("user.home")) { "no user.home system property" }.toAbsolutePath()

/**
 * Removes this path and everything under it, leaving it in place if it cannot be removed.
 *
 * A directory holding a file some other process still has open - a handle a just-exited child leaks on Windows,
 * typically - is left behind rather than failing an operation that has already produced its result.
 */
@OptIn(ExperimentalPathApi::class)
fun Path.deleteRecursivelyQuietly() {
    try {
        deleteRecursively()
    } catch (_: IOException) {
        // Left in place.
    }
}

/**
 * Returns a path obtained by resolving the given [name] on this path.
 */
operator fun Path.get(name: String): Path = resolve(name)

/**
 * Returns a path obtained by resolving the given [name] on this path.
 */
operator fun Path.get(name: PathName): Path = resolve(name.pathName)

/**
 * Represents a path name.
 */
interface PathName {
    val pathName: String
}
