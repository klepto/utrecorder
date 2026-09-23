package dev.klepto.utrecorder.ut99.demo

import dev.klepto.utrecorder.ut99.UtPlatform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One record of a demo: the network packet the server sent in one frame, decoded into its bunches.
 *
 * Use [read] to read every record a demo holds.
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
                .flatMap { data -> data.indices.mapNotNull { framedStringAt(data, it) } }

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
             * Returns the framed string beginning at [index] of [data], or `null` when none does. A framed string is a
             * compact index giving its length, then that many printable bytes ending in a null, and it holds at least
             * eight characters.
             */
            private fun framedStringAt(
                data: ByteArray,
                index: Int,
            ): String? {
                val length = compactIndexAt(data, index) ?: return null
                val start = index + length.size
                val framed = length.value in MIN_STRING_BYTES..data.size - start && data.isFramedString(start, start + length.value)

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
             * Returns a reader over the bits of [packet] that come before its last set bit, which terminates the packet.
             * A packet with no bit set reads as empty.
             */
            fun packet(packet: ByteArray): BitReader = BitReader(packet, lastSetBit(packet) ?: 0)

            private fun lastSetBit(data: ByteArray): Int? = (0 until data.size * Byte.SIZE_BITS).lastOrNull { data.bitAt(it) == 1 }

            private fun ByteArray.bitAt(index: Int): Int = (this[index / Byte.SIZE_BITS].toInt() shr (index % Byte.SIZE_BITS)) and 1

            private fun ByteArray.setBit(index: Int) {
                this[index / Byte.SIZE_BITS] = (this[index / Byte.SIZE_BITS].toInt() or (1 shl (index % Byte.SIZE_BITS))).toByte()
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
         * Reads strings until the payload runs out or holds an empty or malformed one.
         */
        fun strings(): List<String> = generateSequence { string() }.takeWhile { it.isNotEmpty() }.toList()

        /**
         * Reads the next string: a compact index giving its length, then that many bytes ending in a null. Returns
         * `null` when the payload holds no such string.
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
        private const val FIRST_VALUE_MASK = 0x3F
        private const val FIRST_VALUE_BITS = 6
        private const val CONTINUATION_BIT = 0x80
        private const val VALUE_MASK = 0x7F
        private const val VALUE_BITS = 7

        /**
         * Reads the records held in [bytes], in the order the demo holds them. Reading stops at the first record that
         * does not decode.
         */
        fun read(bytes: ByteArray): Sequence<UtDemoRecord> {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return generateSequence { readRecord(buffer) }
        }

        /**
         * Reads the next record from [buffer], or returns `null` if the buffer holds no whole record or the record does
         * not decode.
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
         * Returns the bunches [packet] carries, or `null` if one of them does not decode.
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
         * Reads the next bunch from [reader], or returns `null` if its payload runs past the end of the packet.
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
         * Returns the compact index at [index] of [data], or `null` when [data] ends before it does. A sign, then the
         * value in 6 bits and further 7-bit groups while each byte sets its continuation bit.
         */
        private fun compactIndexAt(
            data: ByteArray,
            index: Int,
        ): CompactIndex? {
            val first = data.getOrNull(index)?.toUByte()?.toInt() ?: return null
            var value = first and FIRST_VALUE_MASK
            var more = first and FIRST_CONTINUATION_BIT != 0
            var bits = FIRST_VALUE_BITS
            var size = 1
            while (more && bits < Int.SIZE_BITS) {
                val next = data.getOrNull(index + size)?.toUByte()?.toInt() ?: return null
                value = value or ((next and VALUE_MASK) shl bits)
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
