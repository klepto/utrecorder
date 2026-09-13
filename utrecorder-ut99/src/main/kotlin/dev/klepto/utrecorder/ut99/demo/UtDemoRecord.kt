package dev.klepto.utrecorder.ut99.demo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One record of a demo: the network packet the server sent in one frame, decoded into its bunches.
 *
 * Use [read] to read every record a demo holds.
 *
 * @property frame The frame the packet was sent in.
 * @property time The demo time the packet was sent at, in seconds.
 * @property bunches The bunches the packet carries, in the order it carries them.
 */
internal class UtDemoRecord(
    val frame: Int,
    val time: Float,
    val bunches: List<UtDemoBunch>,
) {
    /**
     * Returns the text lines the control channel carries in this record.
     */
    fun controlLines(): List<String> =
        bunches
            .filter { it.channel == CONTROL_CHANNEL }
            .flatMap { UtDemoByteReader(it.payload).strings() }

    /**
     * Returns every framed string the bunches of this record carry, read at each of the eight bit offsets of a bunch
     * payload.
     */
    fun shiftedStrings(): List<String> = bunches.flatMap(::shiftedStrings)

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
        private const val MIN_STRING_BYTES = 9
        private const val FIRST_PRINTABLE_BYTE = 0x20
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
            val frame = buffer.int
            val low = buffer.int
            val high = buffer.int
            val count = buffer.int
            if (count !in 0..buffer.remaining()) return null

            val packet = ByteArray(count).also { buffer.get(it) }
            val bunches = readBunches(packet) ?: return null
            return UtDemoRecord(frame, decodeTime(low, high), bunches)
        }

        /**
         * Returns the demo time the two time fields of a record header hold.
         */
        private fun decodeTime(
            low: Int,
            high: Int,
        ): Float =
            when (high) {
                0 -> 0f
                else -> Float.fromBits(((high ushr TIME_HIGH_SHIFT) shl FLOAT_MANTISSA_BITS) or low)
            }

        /**
         * Returns the bunches [packet] carries, or `null` if one of them does not decode.
         */
        private fun readBunches(packet: ByteArray): List<UtDemoBunch>? {
            val reader = UtDemoBitReader.packet(packet)
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
        private fun readBunch(reader: UtDemoBitReader): UtDemoBunch? {
            val control = reader.bit() == 1
            val open = control && reader.bit() == 1
            if (control) reader.bit() // closes the channel
            val reliable = reader.bit() == 1
            val channel = reader.int(MAX_CHANNEL)
            if (reliable) reader.int(MAX_SEQUENCE)
            if (reliable || open) reader.int(MAX_CHANNEL_TYPE)
            val count = reader.int(MAX_PACKET_BYTES * Byte.SIZE_BITS)
            if (count > reader.remaining) return null

            return UtDemoBunch(channel, reader.bits(count))
        }

        /**
         * Returns every framed string the payload of [bunch] carries, read at each of the eight bit offsets a payload
         * can begin at.
         */
        private fun shiftedStrings(bunch: UtDemoBunch): List<String> =
            (0 until Byte.SIZE_BITS)
                .map { bunch.payload.shiftedBy(it) }
                .flatMap { data -> data.indices.mapNotNull { framedStringAt(data, it) } }

        /**
         * Returns the bytes of this payload from the bit at [shift] on, so text written off a byte boundary reads as if
         * it were aligned.
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
         * compact index giving its length, then that many printable bytes ending in a null, and it holds at least eight
         * characters.
         */
        private fun framedStringAt(
            data: ByteArray,
            index: Int,
        ): String? {
            val header = compactIndexAt(data, index) ?: return null
            val start = index + header.size
            val end = start + header.value
            val framed = header.value >= MIN_STRING_BYTES && end <= data.size && data.isFramedString(start, end)

            return when {
                framed -> String(data, start, header.value - 1, Charsets.ISO_8859_1)
                else -> null
            }
        }

        /**
         * Returns whether this payload holds printable bytes from [start] up to the null that has to end the string at
         * [end].
         */
        private fun ByteArray.isFramedString(
            start: Int,
            end: Int,
        ): Boolean =
            this[end - 1].toInt() == 0 &&
                (start until end - 1).all { this[it].toUByte().toInt() >= FIRST_PRINTABLE_BYTE }

        /**
         * Returns the compact index at [index] of [data], or `null` when the bytes there hold no whole positive one. A
         * sign, then the value in 6 bits and further 7-bit groups while each byte sets its continuation bit.
         */
        private fun compactIndexAt(
            data: ByteArray,
            index: Int,
        ): CompactIndex? {
            val first = data[index].toUByte().toInt()
            if (first and SIGN_BIT != 0) return null

            var value = first and FIRST_VALUE_MASK
            var more = first and FIRST_CONTINUATION_BIT != 0
            var bits = FIRST_VALUE_BITS
            var size = 1
            while (more && bits < Int.SIZE_BITS && index + size < data.size) {
                val next = data[index + size].toUByte().toInt()
                value = value or ((next and VALUE_MASK) shl bits)
                more = next and CONTINUATION_BIT != 0
                bits += VALUE_BITS
                size++
            }

            return when {
                more -> null
                else -> CompactIndex(value, size)
            }
        }

        /**
         * A compact index read out of a payload.
         *
         * @property value The value the index holds.
         * @property size The number of bytes the index takes up.
         */
        private class CompactIndex(
            val value: Int,
            val size: Int,
        )
    }
}

/**
 * One bunch of a demo packet: a piece of the data one channel of the connection carries.
 *
 * @property channel The channel the bunch belongs to.
 * @property payload The data the bunch carries.
 */
internal class UtDemoBunch(
    val channel: Int,
    val payload: ByteArray,
)
