package dev.klepto.utrecorder.ut99

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
