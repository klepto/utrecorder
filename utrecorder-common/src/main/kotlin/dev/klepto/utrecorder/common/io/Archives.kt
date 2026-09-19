package dev.klepto.utrecorder.common.io

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
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Tar entry mode bit marking the file as executable by its owner.
 */
private const val OWNER_EXECUTE = 64

/**
 * The path the archive entry [name] extracts to under this directory, or `null` when the entry names the archive root
 * or [filter] rejects it. [filter] receives the name without leading or trailing `/`.
 */
private fun Path.entryPath(
    name: String,
    filter: (String) -> Boolean,
): Path? = name.trim('/').takeIf { it.isNotEmpty() && filter(it) }?.let { resolveEntry(it) }

/**
 * Resolves the archive entry [name] against this directory, rejecting entries that would escape it.
 */
private fun Path.resolveEntry(name: String): Path {
    val root = toAbsolutePath().normalize()
    val path = root.resolve(name.trim('/')).normalize()
    require(path.startsWith(root)) { "Archive entry escapes the target directory: $name" }
    return path
}

/**
 * Creates this directory when the entry is a [directory], and otherwise writes the stream [open] returns to this file.
 */
private fun Path.writeEntry(
    directory: Boolean,
    open: () -> InputStream,
) {
    when {
        directory -> createDirectories()
        else -> open().use { it.writeTo(this) }
    }
}

/**
 * Writes the stream into [file], creating its parent directories.
 */
private fun InputStream.writeTo(file: Path) {
    file
        .createParentDirectories()
        .outputStream()
        .buffered()
        .use { copyTo(it) }
}

/**
 * Grants execute permission to the file. Does nothing on filesystems without POSIX permissions.
 */
private fun Path.markExecutable() {
    val view = Files.getFileAttributeView(this, PosixFileAttributeView::class.java) ?: return
    val permissions = view.readAttributes().permissions()
    permissions += PosixFilePermission.OWNER_EXECUTE
    permissions += PosixFilePermission.GROUP_EXECUTE
    permissions += PosixFilePermission.OTHERS_EXECUTE
    view.setPermissions(permissions)
}

/**
 * Extracts the archive into the given [target] directory or in place if [target] is not specified.
 * The archive format is picked by the file extension, supported formats being `.iso`, `.zip` and `.tar.bz2`.
 *
 * Entries are offered to [filter] by their archive-relative path, using `/` as the separator and carrying no leading
 * or trailing separator. Only the entries it accepts are written out.
 *
 * @throws IllegalStateException if the archive extension matches none of the supported formats.
 */
suspend fun Path.extract(
    target: Path = this.toAbsolutePath().parent,
    filter: (String) -> Boolean = { true },
): Unit =
    withContext(Dispatchers.IO) {
        val name = fileName.toString().lowercase()
        when {
            name.endsWith(".iso") -> extractIso(target, filter)
            name.endsWith(".zip") -> extractZip(target, filter)
            name.endsWith(".tar.bz2") -> extractTarBzip2(target, filter)
            else -> error("Unsupported archive format: $fileName")
        }
    }

private fun Path.extractIso(
    target: Path,
    filter: (String) -> Boolean,
) = Iso9660FileSystem(toFile(), true).use { fileSystem ->
    for (entry in fileSystem) {
        val file = target.entryPath(entry.path, filter) ?: continue
        file.writeEntry(entry.isDirectory) { fileSystem.getInputStream(entry) }
    }
}

private fun Path.extractZip(
    target: Path,
    filter: (String) -> Boolean,
) = ZipFile.builder().setPath(this).get().use { zip ->
    for (entry in zip.entries) {
        val file = target.entryPath(entry.name, filter) ?: continue
        zip.extractEntry(entry, file)
    }
}

/**
 * Writes the given zip [entry] to [file], marking a file executable when the entry's mode says so.
 */
private fun ZipFile.extractEntry(
    entry: ZipArchiveEntry,
    file: Path,
) {
    file.writeEntry(entry.isDirectory) { getInputStream(entry) }
    if (!entry.isDirectory && entry.unixMode and OWNER_EXECUTE != 0) file.markExecutable()
}

private fun Path.extractTarBzip2(
    target: Path,
    filter: (String) -> Boolean,
) {
    val links = mutableListOf<Pair<Path, String>>()
    TarArchiveInputStream(BZip2CompressorInputStream(inputStream().buffered(), true)).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val file = target.entryPath(entry.name, filter) ?: continue
            tar.extractEntry(entry, file, links)
        }
    }
    links.forEach { (file, name) -> target.link(file, name) }
}

/**
 * Writes the given tar [entry] to [file], creating the directory instead when the entry is one and collecting the
 * links into [links], which are resolved once the whole archive is extracted.
 */
private fun TarArchiveInputStream.extractEntry(
    entry: TarArchiveEntry,
    file: Path,
    links: MutableList<Pair<Path, String>>,
) {
    when {
        entry.isDirectory -> {
            file.createDirectories()
        }

        entry.isSymbolicLink || entry.isLink -> {
            links += file to entry.linkName
        }

        else -> {
            writeTo(file)
            if (entry.mode and OWNER_EXECUTE != 0) file.markExecutable()
        }
    }
}

/**
 * Links [file] to the archive entry named [name], relative to this extraction root. Copies the linked file instead
 * when the filesystem refuses symbolic links.
 */
private fun Path.link(
    file: Path,
    name: String,
) {
    file.createParentDirectories()
    file.deleteIfExists()
    runCatching { Files.createSymbolicLink(file, Path.of(name)) }
        .onFailure { cause -> copyLinked(file, name, cause) }
}

/**
 * Copies the file the link [name] points at to [file], throwing with the symbolic link's failure [cause] when the
 * archive holds no such file.
 */
private fun Path.copyLinked(
    file: Path,
    name: String,
    cause: Throwable,
) {
    val source =
        when {
            name.startsWith("/") -> resolveEntry(name)
            else -> file.resolveSibling(name)
        }
    if (!source.exists()) throw IllegalStateException("Cannot link $file to $name", cause)
    source.copyTo(file, overwrite = true)
    file.markExecutable()
}
