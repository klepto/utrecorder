package dev.klepto.utrecorder.ut99

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Objects
import kotlin.io.path.extension
import kotlin.io.path.inputStream
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
 * @property name The name of the package, such as `Engine`.
 * @property path The file the package is held in, or `null` for a package a demo names.
 */
class UtPackage private constructor(
    val name: String,
    val path: Path?,
    guid: () -> String,
) {
    /**
     * The GUID of this package version, read from the file header on first access. Empty for a file that is not a
     * package, and for a package whose demo recorded no GUID.
     *
     * @throws java.io.IOException if the header of the file cannot be read.
     */
    val guid: String by lazy(guid)

    /**
     * Returns `true` when this package satisfies [required]: the names are equal ignoring case, and either [required]
     * carries no GUID or the two GUIDs are equal ignoring case.
     */
    fun satisfies(required: UtPackage): Boolean =
        name.equals(required.name, ignoreCase = true) &&
            (required.guid.isEmpty() || guid.equals(required.guid, ignoreCase = true))

    /**
     * Returns `true` when [other] is a package of the same name, ignoring case, held at the same path. The GUID takes
     * no part.
     */
    override fun equals(other: Any?): Boolean = other is UtPackage && name.equals(other.name, ignoreCase = true) && path == other.path

    override fun hashCode(): Int = Objects.hash(name.lowercase(), path)

    override fun toString(): String = "$name[${guid.take(SHORT_GUID_LENGTH)}]" + (path?.let { " at $it" } ?: "")

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
        private const val VERSION_MASK = 0xFFFF

        /**
         * The first file version holding its GUID at [GUID_OFFSET] rather than in a heritage table.
         */
        private const val HERITAGE_VERSION = 68

        /**
         * The offset a package header of [HERITAGE_VERSION] or later holds its GUID at.
         */
        private const val GUID_OFFSET = 36

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
         * The number of bytes read off a package to find its GUID.
         */
        private const val HEADER_BYTES = 1024

        /**
         * The number of GUID characters [toString] names a package version by.
         */
        private const val SHORT_GUID_LENGTH = 8

        /**
         * Returns the package [name] at the version [guid] names. An empty [guid] is a package version the demo did
         * not record.
         */
        fun named(
            name: String,
            guid: String,
        ): UtPackage = UtPackage(name, null) { guid }

        /**
         * Returns the package held at [path], named after the file and carrying the GUID of its header.
         */
        fun read(path: Path): UtPackage = UtPackage(path.nameWithoutExtension, path) { readGuid(path) }

        /**
         * Returns the packages held directly in [directory]: its regular files whose extension is one of [extensions],
         * compared ignoring case. Empty when [directory] does not exist.
         */
        suspend fun list(
            directory: Path,
            extensions: Set<String>,
        ): List<UtPackage> =
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory()) {
                    emptyList()
                } else {
                    directory
                        .listDirectoryEntries()
                        .filter { it.isRegularFile() && it.hasExtension(extensions) }
                        .map(::read)
                }
            }

        /**
         * Returns `true` when the extension of this path is one of [extensions], compared ignoring case.
         */
        private fun Path.hasExtension(extensions: Set<String>): Boolean = extensions.any { it.equals(extension, ignoreCase = true) }

        /**
         * Returns the GUID the header of the file at [path] holds, or an empty GUID when the file is not a package.
         */
        private fun readGuid(path: Path): String {
            val header = readHeader(path) ?: return ""
            val offset = guidOffset(header) ?: return ""
            if (offset + GUID_BYTES > header.limit()) return ""
            return (0 until GUID_WORDS).joinToString("") { "%08X".format(header.getInt(offset + it * WORD_BYTES)) }
        }

        /**
         * Returns the offset [header] holds its GUID at. A version before the heritage table was dropped holds the
         * GUIDs of every version the package was saved as, and the last of them is the current one.
         */
        private fun guidOffset(header: ByteBuffer): Int? {
            if (header.limit() < GUID_OFFSET + GUID_BYTES) return null
            if (header.getInt(0) != PACKAGE_TAG) return null
            if ((header.getShort(VERSION_OFFSET).toInt() and VERSION_MASK) >= HERITAGE_VERSION) return GUID_OFFSET

            val count = header.getInt(GUID_OFFSET)
            val heritage = header.getInt(GUID_OFFSET + Int.SIZE_BYTES)
            val offset = heritage + maxOf(0, count - 1) * GUID_BYTES
            return offset.takeIf { it >= 0 }
        }

        /**
         * Returns the opening bytes of the file at [path], or `null` when it holds fewer than a header of them.
         */
        private fun readHeader(path: Path): ByteBuffer? {
            val bytes = path.inputStream().use { it.readNBytes(HEADER_BYTES) }
            return when {
                bytes.size < GUID_OFFSET + GUID_BYTES -> null
                else -> ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            }
        }
    }
}
