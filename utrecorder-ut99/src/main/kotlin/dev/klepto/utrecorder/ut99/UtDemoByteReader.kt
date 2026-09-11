package dev.klepto.utrecorder.ut99

/**
 * A cursor over the bytes of a bunch payload, reading the strings the engine writes into it.
 *
 * Use [strings] to read every string the payload holds.
 *
 * @property data The bytes to read.
 */
internal class UtDemoByteReader(
    private val data: ByteArray,
) {
    /**
     * The index of the next byte to read.
     */
    private var position = 0

    /**
     * Reads strings until the payload runs out or holds an empty or malformed one.
     */
    fun strings(): List<String> = generateSequence { string() }.takeWhile { it.isNotEmpty() }.toList()

    /**
     * Reads the next string: a compact index giving its length, then that many bytes ending in a null. Returns `null`
     * when the payload holds no such string.
     */
    private fun string(): String? {
        val length = compactIndex() ?: return null
        if (length !in 1..data.size - position) return null

        val bytes = data.copyOfRange(position, position + length).also { position += length }
        return String(bytes, Charsets.ISO_8859_1).removeSuffix(TERMINATOR.toString())
    }

    /**
     * Reads the next compact index: a sign, then the value in 6 bits and further 7-bit groups while each byte sets its
     * continuation bit. Returns `null` when the payload runs out first.
     */
    private fun compactIndex(): Int? {
        val first = byte() ?: return null
        var value = first and FIRST_VALUE_MASK
        var more = first and FIRST_CONTINUATION_BIT != 0
        var shift = FIRST_VALUE_BITS
        while (more && shift < Int.SIZE_BITS) {
            val next = byte() ?: return null
            value = value or ((next and VALUE_MASK) shl shift)
            more = next and CONTINUATION_BIT != 0
            shift += VALUE_BITS
        }

        return when {
            first and SIGN_BIT != 0 -> -value
            else -> value
        }
    }

    /**
     * Reads the next byte as an unsigned value, or returns `null` when the payload runs out.
     */
    private fun byte(): Int? =
        data
            .getOrNull(position)
            ?.toUByte()
            ?.toInt()
            ?.also { position++ }

    companion object {
        private const val TERMINATOR = Char.MIN_VALUE
        private const val SIGN_BIT = 0x80
        private const val FIRST_CONTINUATION_BIT = 0x40
        private const val FIRST_VALUE_MASK = 0x3F
        private const val FIRST_VALUE_BITS = 6
        private const val CONTINUATION_BIT = 0x80
        private const val VALUE_MASK = 0x7F
        private const val VALUE_BITS = 7
    }
}
