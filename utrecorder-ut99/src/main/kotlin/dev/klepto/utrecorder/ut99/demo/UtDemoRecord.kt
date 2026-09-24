package dev.klepto.utrecorder.ut99.demo

import dev.klepto.utrecorder.ut99.UtPlatform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One record of a demo: the network packet the server sent in one frame, decoded into its bunches.
 *
 * Use [read] to read every record of a demo.
 *
 * @property time The demo time the packet was sent at.
 */
class UtDemoRecord private constructor(
    val time: Duration,
    private val bunches: List<Bunch>,
) {
    /**
     * Returns the text lines the control channel carries in this record.
     */
    fun controlLines(): List<String> =
        bunches
            .filter { it.channel == CONTROL_CHANNEL }
            .flatMap { ByteReader(it.payload).strings() }

    /**
     * Returns every framed string the bunches of this record carry, read at each of the eight bit offsets of a bunch
     * payload.
     */
    fun shiftedStrings(): List<String> = bunches.flatMap { it.shiftedStrings() }

    /**
     * A piece of the data one channel of the connection carries.
     *
     * @property channel The channel the bunch belongs to.
     * @property payload The data the bunch carries.
     */
    private class Bunch(
        val channel: Int,
        val payload: ByteArray,
    ) {
        /**
         * Returns every framed string the payload carries, read at each of the eight bit offsets it can begin at.
         */
        fun shiftedStrings(): List<String> =
            (0 until Byte.SIZE_BITS)
                .map { payload.shiftedBy(it) }
                .flatMap { it.framedStrings() }

        companion object {
            private const val MIN_STRING_BYTES = 9
            private const val FIRST_PRINTABLE_BYTE = 0x20

            /**
             * Returns the bytes of this payload from the bit at [shift] on.
             */
            private fun ByteArray.shiftedBy(shift: Int): ByteArray =
                when (shift) {
                    0 -> this
                    else -> ByteArray(size) { index -> shiftedByte(index, shift) }
                }

            /**
             * Returns the byte at [index] of this payload once its bits have moved back by [shift].
             */
            private fun ByteArray.shiftedByte(
                index: Int,
                shift: Int,
            ): Byte {
                val low = this[index].toUByte().toInt() ushr shift
                val high = getOrElse(index + 1) { 0 }.toInt() shl (Byte.SIZE_BITS - shift)
                return (low or high).toByte()
            }

            /**
             * Returns every framed string that begins at some index of this payload.
             */
            private fun ByteArray.framedStrings(): List<String> = indices.mapNotNull { framedStringAt(this, it) }

            /**
             * Returns the framed string that begins at [index] of [data]. If no framed string begins there, returns
             * `null` instead.
             *
             * A framed string is a compact index that gives its length, then that many printable bytes ending in a null.
             * It holds at least eight characters.
             */
            private fun framedStringAt(
                data: ByteArray,
                index: Int,
            ): String? {
                val length = compactIndexAt(data, index) ?: return null
                val start = index + length.size
                val framed =
                    length.value in MIN_STRING_BYTES..data.size - start &&
                        data.isFramedString(start, start + length.value)

                return when {
                    framed -> stringAt(data, start, length.value)
                    else -> null
                }
            }

            /**
             * Returns whether this payload holds printable bytes from [start] up to the null that has to end the string
             * at [end].
             */
            private fun ByteArray.isFramedString(
                start: Int,
                end: Int,
            ): Boolean =
                this[end - 1].toInt() == 0 &&
                    (start until end - 1).all { this[it].toUByte().toInt() >= FIRST_PRINTABLE_BYTE }
        }
    }

    /**
     * A cursor over the bits of a packet, read least significant bit first within each byte.
     *
     * A read past the end yields a zero bit and still advances, so [remaining] turns negative once a caller overruns.
     *
     * @property data The bytes holding the bits.
     * @property length The number of bits of [data] the reader covers.
     */
    private class BitReader(
        private val data: ByteArray,
        private val length: Int,
    ) {
        private var position = 0

        /**
         * The number of bits left to read. Negative once a read has overrun the end.
         */
        val remaining: Int get() = length - position

        /**
         * Returns the next bit, either `0` or `1`, and moves past it.
         */
        fun bit(): Int =
            when {
                position < length -> data.bitAt(position)
                else -> 0
            }.also { position++ }

        /**
         * Returns the next integer below [max] and moves past it. The integer takes only as many bits as the integers
         * below [max] need.
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
         * Returns the next [count] bits as bytes of their own, least significant bit first, and moves past them.
         */
        fun bits(count: Int): ByteArray =
            ByteArray((count + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS).also { output ->
                repeat(count) { index -> if (bit() == 1) output.setBit(index) }
            }

        companion object {
            /**
             * Returns a reader over the bits of [packet] that come before its last set bit, which terminates the packet.
             * A packet with no bit set reads as empty.
             */
            fun packet(packet: ByteArray): BitReader = BitReader(packet, lastSetBit(packet) ?: 0)

            /**
             * Returns the index of the last set bit of [data]. If no bit is set, returns `null` instead.
             */
            private fun lastSetBit(data: ByteArray): Int? =
                (0 until data.size * Byte.SIZE_BITS)
                    .lastOrNull { data.bitAt(it) == 1 }

            /**
             * Returns the bit at [index] of this array, either `0` or `1`.
             */
            private fun ByteArray.bitAt(index: Int): Int = (this[index / Byte.SIZE_BITS].toInt() shr (index % Byte.SIZE_BITS)) and 1

            /**
             * Sets the bit at [index] of this array.
             */
            private fun ByteArray.setBit(index: Int) {
                val byte = index / Byte.SIZE_BITS
                this[byte] = (this[byte].toInt() or (1 shl (index % Byte.SIZE_BITS))).toByte()
            }
        }
    }

    /**
     * A cursor over the bytes of a bunch payload, reading the strings the engine writes into it.
     *
     * @property data The bytes to read.
     */
    private class ByteReader(
        private val data: ByteArray,
    ) {
        private var position = 0

        /**
         * Returns the strings the payload holds from the current position on, and moves past them. Reading stops at
         * the end of the payload, or at the first empty or malformed string.
         */
        fun strings(): List<String> =
            generateSequence { string() }
                .takeWhile { it.isNotEmpty() }
                .toList()

        /**
         * Returns the next string and moves past it. A string is a compact index that gives its length, then that
         * many bytes ending in a null. If the payload holds no such string, returns `null` instead.
         */
        private fun string(): String? {
            val length = compactIndexAt(data, position) ?: return null
            val start = position + length.size
            if (length.value !in 1..data.size - start) return null

            position = start + length.value
            return stringAt(data, start, length.value)
        }
    }

    /**
     * A compact index read out of a payload.
     *
     * @property value The signed value the index holds.
     * @property size The number of bytes the index takes up.
     */
    private class CompactIndex(
        val value: Int,
        val size: Int,
    )

    companion object {
        private const val CONTROL_CHANNEL = 0
        private const val HEADER_BYTES = 16
        private const val MAX_PACKET_ID = 16384
        private const val MAX_CHANNEL = 1023
        private const val MAX_SEQUENCE = 1024
        private const val MAX_CHANNEL_TYPE = 8
        private const val MAX_PACKET_BYTES = 512
        private const val TIME_HIGH_SHIFT = 20
        private const val FLOAT_MANTISSA_BITS = 23
        private const val TERMINATOR = Char.MIN_VALUE
        private const val SIGN_BIT = 0x80
        private const val FIRST_CONTINUATION_BIT = 0x40
        private const val FIRST_VALUE_BITMASK = 0x3F
        private const val FIRST_VALUE_BITS = 6
        private const val CONTINUATION_BIT = 0x80
        private const val VALUE_BITMASK = 0x7F
        private const val VALUE_BITS = 7

        /**
         * Returns the records held in [bytes], in the order the demo holds them. Reading stops at the first record that
         * does not decode.
         */
        fun read(bytes: ByteArray): Sequence<UtDemoRecord> {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return generateSequence { readRecord(buffer) }
        }

        /**
         * Returns the next record of [buffer] and moves past it. If the buffer holds no whole record, or the record
         * does not decode, returns `null` instead.
         */
        private fun readRecord(buffer: ByteBuffer): UtDemoRecord? {
            if (buffer.remaining() < HEADER_BYTES) return null
            val low = buffer.position(buffer.position() + Int.SIZE_BYTES).int // past the frame
            val high = buffer.int
            val count = buffer.int
            if (count !in 0..buffer.remaining()) return null

            val packet = ByteArray(count).also { buffer.get(it) }
            val bunches = readBunches(packet) ?: return null
            return UtDemoRecord(decodeTime(low, high), bunches)
        }

        /**
         * Returns the demo time the two time fields of a record header hold.
         */
        private fun decodeTime(
            low: Int,
            high: Int,
        ): Duration =
            when (high) {
                0 -> Duration.ZERO
                else -> Float.fromBits(((high ushr TIME_HIGH_SHIFT) shl FLOAT_MANTISSA_BITS) or low).toDouble().seconds
            }

        /**
         * Returns the bunches [packet] carries. If one of them does not decode, returns `null` instead.
         */
        private fun readBunches(packet: ByteArray): List<Bunch>? {
            val reader = BitReader.packet(packet)
            reader.int(MAX_PACKET_ID) // the packet id
            return buildList {
                while (reader.remaining > 0) {
                    // A set bit opens an acknowledgement of a packet id rather than a bunch.
                    if (reader.bit() == 1) reader.int(MAX_PACKET_ID) else add(readBunch(reader) ?: return null)
                }
            }
        }

        /**
         * Returns the next bunch of [reader] and moves past it. If its payload runs past the end of the packet, returns
         * `null` instead.
         */
        private fun readBunch(reader: BitReader): Bunch? {
            val control = reader.bit() == 1
            val open = control && reader.bit() == 1
            if (control) reader.bit() // closes the channel
            val reliable = reader.bit() == 1
            val channel = reader.int(MAX_CHANNEL)
            if (reliable) reader.int(MAX_SEQUENCE)
            if (reliable || open) reader.int(MAX_CHANNEL_TYPE)
            val count = reader.int(MAX_PACKET_BYTES * Byte.SIZE_BITS)
            if (count > reader.remaining) return null

            return Bunch(channel, reader.bits(count))
        }

        /**
         * Returns the compact index at [index] of [data]. If [data] ends before the compact index does, returns `null`
         * instead.
         *
         * The first byte of a compact index holds a sign bit, a continuation bit and 6 bits of the value. Each further
         * byte holds 7 more bits of the value and its own continuation bit. A byte follows while the byte before it
         * sets its continuation bit.
         */
        private fun compactIndexAt(
            data: ByteArray,
            index: Int,
        ): CompactIndex? {
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
                first and SIGN_BIT != 0 -> CompactIndex(-value, size)
                else -> CompactIndex(value, size)
            }
        }

        /**
         * Returns the [length] bytes of [data] from [start] as text, without the null that ends them.
         */
        private fun stringAt(
            data: ByteArray,
            start: Int,
            length: Int,
        ): String = String(data, start, length, UtPlatform.CHARSET).removeSuffix(TERMINATOR.toString())
    }
}
