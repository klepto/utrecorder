package dev.klepto.utrecorder.common.io

import com.github.stephenc.javaisotools.loopfs.iso9660.Iso9660FileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Tar entry mode bit marking the file as executable by its owner.
 */
private const val OWNER_EXECUTE = 64

/**
 * Extracts the archive into the given [target] directory or in place if [target] is not specified.
 * The archive format is picked by the file extension, supported formats being `.iso`, `.zip` and `.tar.bz2`.
 *
 * Entries are offered to [filter] by their archive-relative path, using `/` as the separator and carrying no leading
 * or trailing separator. Only the entries it accepts are written out.
 *
 * @throws IllegalStateException if the archive extension matches none of the supported formats.
 */
fun Path.extract(
    target: Path = this.toAbsolutePath().parent,
    filter: (String) -> Boolean = { true },
) {
    val name = fileName.toString().lowercase()
    when {
        name.endsWith(".iso") -> extractIso(target, filter)
        name.endsWith(".zip") -> extractZip(target, filter)
        name.endsWith(".tar.bz2") -> extractTarBzip2(target, filter)
        else -> error("Unsupported archive format: $fileName")
    }
}

/**
 * Extracts an ISO 9660 disk image.
 */
private fun Path.extractIso(
    target: Path,
    filter: (String) -> Boolean,
) = Iso9660FileSystem(toFile(), true).use { fileSystem ->
    for (entry in fileSystem) {
        val name = entry.path.trim('/')
        if (name.isEmpty() || !filter(name)) continue

        val file = target.resolveEntry(name)
        if (entry.isDirectory) {
            file.createDirectories()
        } else {
            fileSystem.getInputStream(entry).use { it.writeTo(file) }
        }
    }
}

/**
 * Extracts a zip archive.
 */
private fun Path.extractZip(
    target: Path,
    filter: (String) -> Boolean,
) = ZipFile.builder().setPath(this).get().use { zip ->
    for (entry in zip.entries) {
        val name = entry.name.trim('/')
        if (name.isEmpty() || !filter(name)) continue

        val file = target.resolveEntry(name)
        if (entry.isDirectory) {
            file.createDirectories()
        } else {
            zip.getInputStream(entry).use { it.writeTo(file) }
            if (entry.unixMode and OWNER_EXECUTE != 0) file.markExecutable()
        }
    }
}

/**
 * Extracts a bzip2-compressed tar archive.
 */
private fun Path.extractTarBzip2(
    target: Path,
    filter: (String) -> Boolean,
) {
    val links = mutableListOf<Pair<Path, String>>()
    TarArchiveInputStream(BZip2CompressorInputStream(inputStream().buffered(), true)).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val name = entry.name.trim('/')
            if (name.isEmpty() || !filter(name)) continue

            val file = target.resolveEntry(name)
            when {
                entry.isDirectory -> {
                    file.createDirectories()
                }

                entry.isSymbolicLink || entry.isLink -> {
                    links += file to entry.linkName
                }

                else -> {
                    tar.writeTo(file)
                    if (entry.mode and OWNER_EXECUTE != 0) file.markExecutable()
                }
            }
        }
    }
    links.forEach { (file, name) -> target.link(file, name) }
}

/**
 * Links [file] to the archive entry named [name], relative to this extraction root. Falls back to copying the linked
 * file when the filesystem refuses symbolic links.
 */
private fun Path.link(
    file: Path,
    name: String,
) {
    file.parent?.createDirectories()
    file.deleteIfExists()
    runCatching { Files.createSymbolicLink(file, Path.of(name)) }.getOrElse {
        val source = if (name.startsWith("/")) resolveEntry(name.trimStart('/')) else file.resolveSibling(name)
        check(source.exists()) { "Cannot link $file to $name" }
        source.copyTo(file, overwrite = true)
        file.markExecutable()
    }
}

/**
 * Resolves the archive entry [name] against this directory, rejecting entries that would escape it.
 */
private fun Path.resolveEntry(name: String): Path {
    val root = toAbsolutePath().normalize()
    val path = root.resolve(name).normalize()
    require(path.startsWith(root)) { "Archive entry escapes the target directory: $name" }
    return path
}

/**
 * Writes the stream into [file], creating its parent directories.
 */
private fun InputStream.writeTo(file: Path) {
    file.parent?.createDirectories()
    file.outputStream().buffered().use { copyTo(it) }
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
