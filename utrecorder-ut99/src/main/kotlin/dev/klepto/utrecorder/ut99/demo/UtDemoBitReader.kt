package dev.klepto.utrecorder.ut99.demo

/**
 * A cursor over the bits of a demo packet, read least significant bit first within each byte.
 *
 * A read past the end yields a zero bit and still advances, so [remaining] turns negative once a caller overruns.
 *
 * Use [packet] to read the bits of a packet up to its terminating bit.
 *
 * @property data The bytes holding the bits.
 * @property length The number of bits of [data] the reader covers.
 */
internal class UtDemoBitReader(
    private val data: ByteArray,
    private val length: Int = data.size * Byte.SIZE_BITS,
) {
    /**
     * The index of the next bit to read.
     */
    private var position = 0

    /**
     * The number of bits left to read, negative once a read has overrun the end.
     */
    val remaining: Int get() = length - position

    /**
     * Reads the next bit, either `0` or `1`.
     */
    fun bit(): Int =
        when {
            position < length -> data.bitAt(position)
            else -> 0
        }.also { position++ }

    /**
     * Reads an integer below [max], written in only as many bits as the integers below [max] need.
     */
    fun int(max: Int): Int {
        var value = 0
        var mask = 1
        while (value + mask < max && mask != 0) {
            if (bit() == 1) value = value or mask
            mask = mask shl 1
        }
        return value
    }

    /**
     * Reads the next [count] bits into bytes of their own, least significant bit first.
     */
    fun bits(count: Int): ByteArray =
        ByteArray((count + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS).also { output ->
            repeat(count) { index -> if (bit() == 1) output.setBit(index) }
        }

    companion object {
        /**
         * Returns a reader over the bits of [packet] that come before its last set bit, which terminates the packet. A
         * packet with no bit set reads as empty.
         */
        fun packet(packet: ByteArray): UtDemoBitReader = UtDemoBitReader(packet, lastSetBit(packet) ?: 0)

        /**
         * Returns the index of the last set bit of [data], or `null` if no bit is set.
         */
        private fun lastSetBit(data: ByteArray): Int? = (0 until data.size * Byte.SIZE_BITS).lastOrNull { data.bitAt(it) == 1 }

        /**
         * Returns the bit at the given [index], either `0` or `1`.
         */
        private fun ByteArray.bitAt(index: Int): Int = (this[index / Byte.SIZE_BITS].toInt() shr (index % Byte.SIZE_BITS)) and 1

        /**
         * Sets the bit at the given [index].
         */
        private fun ByteArray.setBit(index: Int) {
            this[index / Byte.SIZE_BITS] = (this[index / Byte.SIZE_BITS].toInt() or (1 shl (index % Byte.SIZE_BITS))).toByte()
        }
    }
}
