package dev.klepto.utrecorder.common.io

import com.github.stephenc.javaisotools.loopfs.iso9660.Iso9660FileEntry
import com.github.stephenc.javaisotools.loopfs.iso9660.Iso9660FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Unix mode bit marking the file as executable by its owner.
 */
private const val OWNER_EXECUTE_BITMASK = 64

/**
 * Returns the path of the archive entry [name] in this directory.
 *
 * Only an entry that [filter] accepts gets a path. Entry names are passed through [filter] without leading or trailing
 * path separators. If the entry is the archive root, or [filter] rejects it, returns `null` instead.
 *
 * @throws IllegalArgumentException if the entry resolves outside this directory.
 */
private fun Path.entryPath(
    name: String,
    filter: (String) -> Boolean,
): Path? = name.trim('/').takeIf { it.isNotEmpty() && filter(it) }?.let { resolveEntry(it) }

/**
 * Returns the path of the archive entry [name] in this directory, without passing it through a filter.
 *
 * @throws IllegalArgumentException if the entry resolves outside this directory.
 */
private fun Path.resolveEntry(name: String): Path =
    toAbsolutePath().normalize().let { root ->
        root.resolve(name.trim('/')).normalize().also {
            require(it.startsWith(root)) { "Archive entry escapes the target directory: $name" }
        }
    }

/**
 * Writes the content that [stream] supplies to this file. If [directory] is `true`, creates this path as a directory
 * instead.
 */
private fun Path.writeEntry(
    directory: Boolean,
    stream: () -> InputStream,
) {
    when {
        directory -> createDirectories()
        else -> stream().use { it.writeTo(this) }
    }
}

/**
 * Writes this stream to [file], creating its parent directories.
 */
private fun InputStream.writeTo(file: Path) {
    file
        .createParentDirectories()
        .outputStream()
        .buffered()
        .use { copyTo(it) }
}

/**
 * Grants execute permission to this file if the Unix [mode] marks it executable by its owner.
 */
private fun Path.markExecutable(mode: Int) {
    if (mode and OWNER_EXECUTE_BITMASK != 0) markExecutable()
}

/**
 * Grants execute permission to this file. Does nothing on filesystems without POSIX permissions.
 */
private fun Path.markExecutable() {
    val view = Files.getFileAttributeView(this, PosixFileAttributeView::class.java) ?: return
    view.setPermissions(view.readAttributes().permissions() + listOf(OWNER_EXECUTE, GROUP_EXECUTE, OTHERS_EXECUTE))
}

/**
 * Extracts this archive into [target] and returns the extracted paths. If [target] is not provided, uses the directory
 * that holds the archive instead. The file extension selects the format: `.iso`, `.zip` or `.tar.bz2`.
 *
 * Only the entries that [filter] accepts are extracted. Entry names are passed through [filter] without leading or
 * trailing path separators, using `/` between segments.
 *
 * @throws IllegalStateException if the archive extension matches none of the supported formats.
 */
suspend fun Path.extract(
    target: Path = toAbsolutePath().parent,
    filter: (String) -> Boolean = { true },
): Set<Path> =
    withContext(Dispatchers.IO) {
        val name = fileName.toString().lowercase()
        when {
            name.endsWith(".iso") -> extractIso(target, filter)
            name.endsWith(".zip") -> extractZip(target, filter)
            name.endsWith(".tar.bz2") -> extractTarBzip2(target, filter)
            else -> error("Unsupported archive format: $fileName")
        }
    }

/**
 * Extracts this ISO 9660 image into [target] and returns the extracted paths.
 */
private fun Path.extractIso(
    target: Path,
    filter: (String) -> Boolean,
): Set<Path> =
    Iso9660FileSystem(toFile(), true).use { fileSystem ->
        fileSystem
            .mapNotNull { entry -> fileSystem.extractEntry(entry, target, filter) }
            .toSet()
    }

/**
 * Extracts the given ISO 9660 [entry] into [target] and returns its path. If the entry is the archive root, or [filter]
 * rejects it, returns `null` instead.
 */
private fun Iso9660FileSystem.extractEntry(
    entry: Iso9660FileEntry,
    target: Path,
    filter: (String) -> Boolean,
): Path? {
    val file = target.entryPath(entry.path, filter) ?: return null
    file.writeEntry(entry.isDirectory) { getInputStream(entry) }
    return file
}

/**
 * Extracts this zip archive into [target] and returns the extracted paths.
 */
private fun Path.extractZip(
    target: Path,
    filter: (String) -> Boolean,
): Set<Path> =
    ZipFile.builder().setPath(this).get().use { zip ->
        zip.entries
            .asSequence()
            .mapNotNull { entry -> zip.extractEntry(entry, target, filter) }
            .toSet()
    }

/**
 * Extracts the given zip [entry] into [target] and returns its path. Marks the file executable when the mode of the
 * entry says so. If the entry is the archive root, or [filter] rejects it, returns `null` instead.
 */
private fun ZipFile.extractEntry(
    entry: ZipArchiveEntry,
    target: Path,
    filter: (String) -> Boolean,
): Path? {
    val file = target.entryPath(entry.name, filter) ?: return null
    file.writeEntry(entry.isDirectory) { getInputStream(entry) }
    if (!entry.isDirectory) file.markExecutable(entry.unixMode)
    return file
}

/**
 * Extracts this bzip2-compressed tar archive into [target] and returns the extracted paths. Creates symbolic links after
 * all other entries are written.
 */
private fun Path.extractTarBzip2(
    target: Path,
    filter: (String) -> Boolean,
): Set<Path> {
    val links = mutableListOf<Pair<Path, String>>()
    val stream = BZip2CompressorInputStream(inputStream().buffered(), true)
    val files =
        TarArchiveInputStream(stream).use { tar ->
            generateSequence { tar.nextEntry }
                .mapNotNull { entry -> tar.extractEntry(entry, target, filter, links) }
                .toSet()
        }
    links.forEach { (file, linkName) -> target.link(file, linkName) }
    return files
}

/**
 * Extracts the given tar [entry] into [target] and returns its path. Marks the file executable when the mode of the
 * entry says so. Adds a symbolic link entry to [links] instead of creating it, and extracts a hard link entry as a copy
 * of the file it links to. If the entry is the archive root, or [filter] rejects it, returns `null` instead.
 *
 * @throws IllegalArgumentException if the entry, or the file its hard link links to, resolves outside [target].
 */
private fun TarArchiveInputStream.extractEntry(
    entry: TarArchiveEntry,
    target: Path,
    filter: (String) -> Boolean,
    links: MutableList<Pair<Path, String>>,
): Path? {
    val file = target.entryPath(entry.name, filter) ?: return null
    when {
        entry.isDirectory -> {
            file.createDirectories()
        }

        entry.isSymbolicLink -> {
            links += file to entry.linkName
        }

        entry.isLink -> {
            target.resolveEntry(entry.linkName).copyTo(file.createParentDirectories(), overwrite = true)
            file.markExecutable(entry.mode)
        }

        else -> {
            writeTo(file)
            file.markExecutable(entry.mode)
        }
    }
    return file
}

/**
 * Creates a symbolic link at [file] that points at [linkName]. If the filesystem refuses the symbolic link, copies the
 * linked file instead.
 *
 * @throws IllegalArgumentException if the link points outside this directory.
 * @throws IllegalStateException if the filesystem refuses the symbolic link and the linked file does not exist.
 */
private fun Path.link(
    file: Path,
    linkName: String,
) {
    val source = linkSource(file, linkName)
    file.createParentDirectories()
    file.deleteIfExists()
    runCatching { Files.createSymbolicLink(file, file.parent.relativize(source)) }
        .onFailure { cause -> copyLinked(file, source, cause) }
}

/**
 * Returns the path that the symbolic link [linkName] at [file] points at. An absolute [linkName] resolves against this
 * directory.
 *
 * @throws IllegalArgumentException if the path is outside this directory.
 */
private fun Path.linkSource(
    file: Path,
    linkName: String,
): Path {
    val root = toAbsolutePath().normalize()
    val source =
        when {
            linkName.startsWith("/") -> root.resolve(linkName.trim('/'))
            else -> file.resolveSibling(linkName)
        }.normalize()
    require(source.startsWith(root)) { "Archive link escapes the target directory: $linkName" }
    return source
}

/**
 * Copies [source] to [file] and grants execute permission to the copy.
 *
 * @throws IllegalStateException if [source] does not exist, with the symbolic link failure [cause] as its cause.
 */
private fun copyLinked(
    file: Path,
    source: Path,
    cause: Throwable,
) {
    if (!source.exists()) throw IllegalStateException("Cannot link $file to $source", cause)
    source.copyTo(file, overwrite = true)
    file.markExecutable()
}
