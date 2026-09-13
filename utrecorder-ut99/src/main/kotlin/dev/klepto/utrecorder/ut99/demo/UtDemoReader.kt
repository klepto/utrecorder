package dev.klepto.utrecorder.ut99.demo

import kotlin.time.Duration.Companion.seconds

/**
 * Reads a [UtDemo] out of the records of a demo and the text lines its control channel carries.
 */
internal object UtDemoReader {
    /**
     * Reads the demo held in [bytes]. Throws [IllegalStateException] if no record names the map.
     */
    fun read(bytes: ByteArray): UtDemo {
        val records = UtDemoRecord.read(bytes).toList()
        val lines = records.flatMap { it.controlLines() }
        val map = checkNotNull(lines.firstNotNullOfOrNull(::levelOf)) { "Demo names no map" }
        val packages = lines.mapNotNull(::packageOf).distinctBy { it.name.lowercase() }
        val duration = (records.lastOrNull()?.time ?: 0f).toDouble().seconds
        return UtDemo(map, packages, duration, UtDemoCapReader.read(records))
    }

    /**
     * Returns the map a `WELCOME` [line] names, or `null` if the line is not one or names no map.
     */
    private fun levelOf(line: String): String? = fieldsOf(line, "WELCOME")?.get("LEVEL")

    /**
     * Returns the package a `USES` [line] names, or `null` if the line is not one.
     */
    private fun packageOf(line: String): UtDemoPackage? {
        val fields = fieldsOf(line, "USES") ?: return null
        return UtDemoPackage(
            name = fields["PKG"].orEmpty(),
            guid = fields["GUID"].orEmpty(),
            fileName = fields["FNAME"].orEmpty(),
        )
    }

    /**
     * Returns the `KEY=VALUE` fields of a [line] opening with [command], or `null` if it opens with another one.
     */
    private fun fieldsOf(
        line: String,
        command: String,
    ): Map<String, String>? {
        val words = line.split(' ')
        if (words.first() != command) return null

        return words
            .drop(1)
            .filter { it.indexOf('=') > 0 }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
