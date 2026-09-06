package dev.klepto.utrecorder.ut99

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Represents an editable Unreal Tournament configuration file. Comments, blank lines and the order of the original
 * file are preserved, and keys that appear more than once within a section are kept as separate entries.
 *
 * @param text The contents of the configuration file.
 * @author Augustinas R. <http://github.com/klepto>
 */
class UtIni(
    text: String,
) {
    /**
     * The line separator used by the original file.
     */
    private val newline = if (text.contains("\r\n")) "\r\n" else "\n"

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
    ): List<String> = entryIndices(section, key).map { lines[it].trim().substringAfter('=').trim() }

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
     * Appends another [key] entry holding [value] to the given [section], keeping the entries already there. The
     * section is appended to the file if it isn't present.
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

        var index = indices.last + 1
        while (index > indices.first && lines[index - 1].isBlank()) index--
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
     * Writes the configuration to the given [path] and returns it.
     */
    fun write(path: Path): Path {
        path.parent?.createDirectories()
        path.writeText(toString(), CHARSET)
        return path
    }

    /**
     * Returns the contents of the configuration file.
     */
    override fun toString(): String = lines.joinToString(newline)

    /**
     * Returns the indices of the [key] entries within the given [section].
     */
    private fun entryIndices(
        section: String,
        key: String,
    ): List<Int> {
        val indices = sectionIndices(section) ?: return emptyList()
        return indices.filter { entryKey(lines[it]).equals(key, ignoreCase = true) }
    }

    /**
     * Returns the indices of the lines within the given [section] excluding its header, or `null` if the file holds no
     * such section. The range is empty when the section holds no lines, in which case its [IntRange.last] still points
     * at the section header.
     */
    private fun sectionIndices(section: String): IntRange? {
        val start = lines.indexOfFirst { isSection(it) && sectionName(it).equals(section, ignoreCase = true) }
        if (start == -1) return null

        val length = lines.subList(start + 1, lines.size).indexOfFirst { isSection(it) }
        val end = if (length == -1) lines.size else start + 1 + length
        return (start + 1) until end
    }

    /**
     * Returns the key of the given entry [line], or `null` if the line holds no entry.
     */
    private fun entryKey(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith(COMMENT) || !trimmed.contains('=')) return null
        return trimmed.substringBefore('=').trim()
    }

    /**
     * Marks whether the given [line] is a section header.
     */
    private fun isSection(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("[") && trimmed.endsWith("]")
    }

    /**
     * Returns the name of the given section header [line].
     */
    private fun sectionName(line: String) = line.trim().removeSurrounding("[", "]")

    companion object {
        /**
         * Reads the configuration file at the given [path].
         */
        fun read(path: Path): UtIni = UtIni(path.readText(CHARSET))

        /**
         * The charset of Unreal Tournament configuration files. Maps every byte to a single character, so that files
         * survive a read and write untouched.
         */
        private val CHARSET = Charsets.ISO_8859_1

        /**
         * The prefix of a comment line.
         */
        private const val COMMENT = ";"
    }
}
