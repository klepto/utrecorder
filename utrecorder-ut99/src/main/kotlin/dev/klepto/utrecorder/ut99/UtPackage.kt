package dev.klepto.utrecorder.ut99

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Objects
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * A package of an Unreal Tournament installation, either as a demo names it or as an installation holds it.
 *
 * Use [named] for a package a demo asks for, [read] for a file an installation holds, and [list] for the files of a
 * directory.
 *
 * Two packages are equal when their names are equal ignoring case and they are held at the same path. The GUID takes no
 * part.
 *
 * @property name The name of the package, such as `Engine`.
 * @property path The file the package is held in, or `null` for a package a demo names.
 */
class UtPackage private constructor(
    val name: String,
    val path: Path?,
    /**
     * The GUID the demo recorded for a package a demo names. Unused for a package held in a file.
     */
    private val recordedGuid: String,
) {
    /**
     * Returns the GUID of this package version. For a package held in a file, reads it from the file on every call.
     * Returns an empty string for a file that is not a package, for a file that ends before its GUID, and for a package
     * whose demo recorded no GUID.
     *
     * @throws java.io.IOException if the file cannot be read.
     */
    suspend fun guid(): String =
        when (path) {
            null -> recordedGuid
            else -> withContext(Dispatchers.IO) { readGuid(path) }
        }

    /**
     * Returns `true` when this package satisfies [required]: the names are equal ignoring case, and either [required]
     * carries no GUID or the two GUIDs are equal ignoring case.
     */
    suspend fun satisfies(required: UtPackage): Boolean =
        name.equals(required.name, ignoreCase = true) &&
            required.guid().let { it.isEmpty() || guid().equals(it, ignoreCase = true) }

    /**
     * Returns `true` after rewriting the last generation of the file this package is held in to the export and name
     * counts of its header. The game counts the objects of a package in the package map of a demo by that generation,
     * so a stale one shifts the objects of every package the demo names after it. Leaves every other byte of the file,
     * its GUID included, as it is. If the generation already holds those counts, or the file is not a package with
     * generations, writes nothing and returns `false` instead. Returns `false` for a package a demo names.
     *
     * @throws java.io.IOException if the file cannot be read or written.
     */
    suspend fun repairGenerations(): Boolean =
        when (path) {
            null -> false
            else -> withContext(Dispatchers.IO) { repairGenerations(path) }
        }

    override fun equals(other: Any?): Boolean = other is UtPackage && name.equals(other.name, ignoreCase = true) && path == other.path

    override fun hashCode(): Int = Objects.hash(name.lowercase(), path)

    override fun toString(): String = path?.let { "$name at $it" } ?: "$name[${recordedGuid.take(SHORT_GUID_LENGTH)}]"

    companion object {
        /**
         * The tag every Unreal package header opens with, `0x9E2A83C1` as the signed int the header holds.
         */
        private const val PACKAGE_TAG = -0x61D57C3F

        /**
         * The offset a package header holds its file version at.
         */
        private const val VERSION_OFFSET = 4

        /**
         * The mask reading the unsigned file version out of the signed short the header holds it as.
         */
        private const val VERSION_BITMASK = 0xFFFF

        /**
         * The first file version holding its GUID at [GUID_OFFSET] rather than in a heritage table.
         */
        private const val HERITAGE_VERSION = 68

        /**
         * The offset a package header of [HERITAGE_VERSION] or later holds its GUID at.
         */
        private const val GUID_OFFSET = 36

        /**
         * The offset a package header holds its name count at.
         */
        private const val NAME_COUNT_OFFSET = 12

        /**
         * The offset a package header holds its export count at.
         */
        private const val EXPORT_COUNT_OFFSET = 20

        /**
         * The offset a package header of [HERITAGE_VERSION] or later holds its generation count at, right after its
         * GUID. A version before it holds no generations.
         */
        private const val GENERATION_COUNT_OFFSET = 52

        /**
         * The offset a package header of [HERITAGE_VERSION] or later holds its first generation at.
         */
        private const val GENERATIONS_OFFSET = 56

        /**
         * The size of one generation, an export count followed by a name count, in bytes.
         */
        private const val GENERATION_BYTES = 8

        /**
         * The size of a GUID, in bytes.
         */
        private const val GUID_BYTES = 16

        /**
         * The number of words a GUID is written as.
         */
        private const val GUID_WORDS = 4

        /**
         * The size of one GUID word, in bytes.
         */
        private const val WORD_BYTES = 4

        /**
         * The number of GUID characters [toString] names the version of a package a demo names by.
         */
        private const val SHORT_GUID_LENGTH = 8

        /**
         * Returns the package [name] at the version [guid] names. An empty [guid] is a package version the demo did
         * not record.
         */
        fun named(
            name: String,
            guid: String,
        ): UtPackage = UtPackage(name, null, guid)

        /**
         * Returns the package [name] held at [path]. The file is not read until [guid] is called. If [name] is not
         * provided, names the package after the file instead.
         */
        fun read(
            path: Path,
            name: String = path.nameWithoutExtension,
        ): UtPackage = UtPackage(name, path, "")

        /**
         * Returns the packages held directly in [directory]: its regular files whose extension is one of [extensions],
         * compared ignoring case. If [directory] does not exist, returns an empty list instead.
         */
        suspend fun list(
            directory: Path,
            extensions: Set<String>,
        ): List<UtPackage> =
            withContext(Dispatchers.IO) {
                directory
                    .takeIf { it.isDirectory() }
                    ?.listDirectoryEntries()
                    ?.filter { it.isRegularFile() && it.hasExtension(extensions) }
                    ?.map(::read)
                    .orEmpty()
            }

        /**
         * Returns `true` when the extension of this path is one of [extensions], compared ignoring case.
         */
        private fun Path.hasExtension(extensions: Set<String>): Boolean = extensions.any { it.equals(extension, ignoreCase = true) }

        /**
         * Returns the GUID the file at [path] holds. If the file is not a package, or ends before its GUID, returns an
         * empty string instead.
         */
        private fun readGuid(path: Path): String =
            Files.newByteChannel(path).use { channel ->
                val header = channel.readAt(0, GUID_OFFSET + GUID_BYTES) ?: return ""
                val offset = guidOffset(header) ?: return ""
                val guid = channel.readAt(offset, GUID_BYTES) ?: return ""
                (0 until GUID_WORDS).joinToString("") { "%08X".format(guid.getInt(it * WORD_BYTES)) }
            }

        /**
         * Returns `true` after rewriting the last generation of the package file at [path] to the export and name
         * counts of its header. If the generation already holds them, or the file is not a package with generations,
         * writes nothing and returns `false` instead. The file is opened for writing only when it needs the repair.
         */
        private fun repairGenerations(path: Path): Boolean {
            val (offset, counts) =
                Files.newByteChannel(path).use { channel -> staleGeneration(channel) } ?: return false
            Files.newByteChannel(path, StandardOpenOption.WRITE).use { channel ->
                channel.position(offset)
                while (counts.hasRemaining()) channel.write(counts)
            }
            return true
        }

        /**
         * Returns the offset of the last generation of the package file this [channel] reads, with the export and name
         * counts of its header that the generation should hold. If the generation already holds them, or the file is
         * not a package with generations, returns `null` instead.
         */
        private fun staleGeneration(channel: SeekableByteChannel): Pair<Long, ByteBuffer>? {
            val header = channel.readAt(0, GENERATIONS_OFFSET) ?: return null
            if (header.getInt(0) != PACKAGE_TAG) return null
            if ((header.getShort(VERSION_OFFSET).toInt() and VERSION_BITMASK) < HERITAGE_VERSION) return null

            val generationCount = header.getInt(GENERATION_COUNT_OFFSET)
            if (generationCount < 1) return null
            val offset = GENERATIONS_OFFSET + (generationCount - 1).toLong() * GENERATION_BYTES
            val generation = channel.readAt(offset, GENERATION_BYTES) ?: return null
            val exportCount = header.getInt(EXPORT_COUNT_OFFSET)
            val nameCount = header.getInt(NAME_COUNT_OFFSET)
            if (generation.getInt(0) == exportCount && generation.getInt(Int.SIZE_BYTES) == nameCount) return null

            val counts = ByteBuffer.allocate(GENERATION_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            return offset to counts.putInt(exportCount).putInt(nameCount).flip()
        }

        /**
         * Returns the offset of the file [header] opens that holds its GUID. A version before [HERITAGE_VERSION] holds
         * the GUIDs of every version the package was saved as in a heritage table, and the last of them is the current
         * one. If [header] is not a package header, returns `null` instead.
         */
        private fun guidOffset(header: ByteBuffer): Long? {
            if (header.getInt(0) != PACKAGE_TAG) return null
            if ((header.getShort(VERSION_OFFSET).toInt() and VERSION_BITMASK) >= HERITAGE_VERSION) return GUID_OFFSET.toLong()

            val count = header.getInt(GUID_OFFSET)
            val heritage = header.getInt(GUID_OFFSET + Int.SIZE_BYTES)
            val offset = heritage + maxOf(0, count - 1).toLong() * GUID_BYTES
            return offset.takeIf { it >= 0 }
        }

        /**
         * Returns the [size] bytes this channel holds at [position], in little-endian order. If the channel ends before
         * them, returns `null` instead.
         */
        private fun SeekableByteChannel.readAt(
            position: Long,
            size: Int,
        ): ByteBuffer? {
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            position(position)
            while (buffer.hasRemaining()) {
                if (read(buffer) < 0) return null
            }
            return buffer.flip()
        }
    }
}
