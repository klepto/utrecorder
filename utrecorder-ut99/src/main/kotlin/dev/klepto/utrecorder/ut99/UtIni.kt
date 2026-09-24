package dev.klepto.utrecorder.ut99

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * An Unreal Tournament configuration file, edited in place and written back with its original lines preserved.
 *
 * A key that appears more than once within a section is kept as separate entries.
 *
 * Use [read] to read one from disk.
 *
 * @param text The contents of the configuration file.
 */
class UtIni(
    text: String,
) {
    /**
     * The line separator used by the original file.
     */
    private val newline =
        when {
            text.contains("\r\n") -> "\r\n"
            else -> "\n"
        }

    /**
     * The lines of the configuration file.
     */
    private val lines = text.split(newline).toMutableList()

    /**
     * Returns every [key] value within the given [section], in the order they appear in the file.
     */
    operator fun get(
        section: String,
        key: String,
    ): List<String> = entryIndices(section, key).map { entryValue(lines[it]) }

    /**
     * Replaces every [key] entry within the given [section] with a single entry holding [value]. Appends the entry if
     * the section holds none.
     */
    operator fun set(
        section: String,
        key: String,
        value: String,
    ) {
        val indices = entryIndices(section, key)
        if (indices.isEmpty()) {
            add(section, key, value)
            return
        }

        lines[indices.first()] = "$key=$value"
        indices.drop(1).reversed().forEach { lines.removeAt(it) }
    }

    /**
     * Appends another [key] entry holding [value] to the given [section], keeping the entries already there. The entry
     * goes after the last non-blank line of the section. The section is appended to the file if it is not present.
     */
    fun add(
        section: String,
        key: String,
        value: String,
    ) {
        val indices = sectionIndices(section)
        if (indices == null) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$section]"
            lines += "$key=$value"
            return
        }

        val index = indices.lastOrNull { lines[it].isNotBlank() }?.plus(1) ?: indices.first
        lines.add(index, "$key=$value")
    }

    /**
     * Removes every [key] entry from the given [section].
     */
    fun remove(
        section: String,
        key: String,
    ) = entryIndices(section, key).reversed().forEach { lines.removeAt(it) }

    /**
     * Replaces the single [key] entry holding [from] with one holding [to], leaving any other entry of the key where
     * it is.
     *
     * @throws IllegalArgumentException when the section holds no entry of the key at [from].
     */
    fun replace(
        section: String,
        key: String,
        from: String,
        to: String,
    ) {
        lines[entryIndex(section, key, from)] = "$key=$to"
    }

    /**
     * Removes the single [key] entry holding [value] from the given [section], leaving any other entry of the key
     * where it is.
     *
     * @throws IllegalArgumentException when the section holds no entry of the key at [value].
     */
    fun remove(
        section: String,
        key: String,
        value: String,
    ) {
        lines.removeAt(entryIndex(section, key, value))
    }

    /**
     * Writes the configuration to the given [path] and returns it.
     */
    suspend fun write(path: Path): Path {
        val content = toString()
        return withContext(Dispatchers.IO) {
            path.createParentDirectories().also { it.writeText(content, UtPlatform.CHARSET) }
        }
    }

    override fun toString(): String = lines.joinToString(newline)

    /**
     * Returns the index of the first [key] entry holding [value] within the given [section].
     *
     * @throws IllegalArgumentException when the section holds no entry of the key at [value].
     */
    private fun entryIndex(
        section: String,
        key: String,
        value: String,
    ): Int =
        entryIndices(section, key).firstOrNull { entryValue(lines[it]) == value }
            ?: throw IllegalArgumentException("No $key=$value entry in the [$section] section")

    /**
     * Returns the indices of the [key] entries within the given [section].
     */
    private fun entryIndices(
        section: String,
        key: String,
    ): List<Int> =
        sectionIndices(section)
            ?.filter { entryKey(lines[it]).equals(key, ignoreCase = true) }
            .orEmpty()

    /**
     * Returns the indices of the lines within the given [section] excluding its header, or `null` if the file holds no
     * such section. The range is empty when the section holds no lines, in which case its [IntRange.last] still points
     * at the section header.
     */
    private fun sectionIndices(section: String): IntRange? {
        val start = lines.indexOfFirst { isSection(it) && sectionName(it).equals(section, ignoreCase = true) }
        if (start == -1) return null

        val end = (start + 1 until lines.size).firstOrNull { isSection(lines[it]) } ?: lines.size
        return (start + 1) until end
    }

    companion object {
        /**
         * The prefix of a comment line.
         */
        private const val COMMENT = ";"

        /**
         * Returns the configuration file read from the given [path].
         */
        suspend fun read(path: Path): UtIni = UtIni(withContext(Dispatchers.IO) { path.readText(UtPlatform.CHARSET) })

        /**
         * Returns the key of the given entry [line], or `null` if the line holds no entry.
         */
        private fun entryKey(line: String): String? {
            val trimmed = line.trim()
            if (trimmed.startsWith(COMMENT) || !trimmed.contains('=')) return null
            return trimmed.substringBefore('=').trim()
        }

        /**
         * Returns the value of the given entry [line].
         */
        private fun entryValue(line: String): String = line.substringAfter('=').trim()

        /**
         * Returns whether the given [line] is a section header.
         */
        private fun isSection(line: String): Boolean {
            val trimmed = line.trim()
            return trimmed.startsWith("[") && trimmed.endsWith("]")
        }

        /**
         * Returns the name of the given section header [line].
         */
        private fun sectionName(line: String): String = line.trim().removeSurrounding("[", "]")
    }
}
