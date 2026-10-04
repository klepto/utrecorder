package dev.klepto.utrecorder.ut99

/**
 * A signed integer in the variable-length encoding Unreal Tournament writes string lengths with.
 *
 * Use [read] to read one out of a byte array.
 *
 * @property value The signed value the index holds.
 * @property size The number of bytes the index takes up.
 */
class UtCompactIndex private constructor(
    val value: Int,
    val size: Int,
) {
    companion object {
        private const val SIGN_BIT = 0x80
        private const val FIRST_CONTINUATION_BIT = 0x40
        private const val FIRST_VALUE_BITMASK = 0x3F
        private const val FIRST_VALUE_BITS = 6
        private const val CONTINUATION_BIT = 0x80
        private const val VALUE_BITMASK = 0x7F
        private const val VALUE_BITS = 7

        /**
         * Returns the compact index at [index] of [data]. If [data] ends before the compact index does, returns `null`
         * instead.
         *
         * The first byte of a compact index holds a sign bit, a continuation bit and 6 bits of the value. Each further
         * byte holds 7 more bits of the value and its own continuation bit. A byte follows while the byte before it
         * sets its continuation bit.
         */
        fun read(
            data: ByteArray,
            index: Int,
        ): UtCompactIndex? {
            val first = data.getOrNull(index)?.toUByte()?.toInt() ?: return null
            var value = first and FIRST_VALUE_BITMASK
            var more = first and FIRST_CONTINUATION_BIT != 0
            var bits = FIRST_VALUE_BITS
            var size = 1
            while (more && bits < Int.SIZE_BITS) {
                val next = data.getOrNull(index + size)?.toUByte()?.toInt() ?: return null
                value = value or ((next and VALUE_BITMASK) shl bits)
                more = next and CONTINUATION_BIT != 0
                bits += VALUE_BITS
                size++
            }

            return when {
                first and SIGN_BIT != 0 -> UtCompactIndex(-value, size)
                else -> UtCompactIndex(value, size)
            }
        }
    }
}
