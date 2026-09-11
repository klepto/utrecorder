package dev.klepto.utrecorder.ut99

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Synthetic demo builders. They write the exact inverse of a reference demo reader, which defines what a correct parser
// accepts: little-endian record headers, a bit stream packet (LSB first per byte) of bunches terminated by a single
// set bit, and control-channel payloads made of compact-index-length FStrings.

private const val MAX_PACKET_ID = 16384
private const val MAX_CHANNEL = 1023
private const val MAX_PACKET_BYTES = 512
private const val CONTROL_CHANNEL = 0

private class DemoBitWriter {
    private val bits = mutableListOf<Int>()

    fun bit(value: Int) {
        bits += value
    }

    fun int(
        value: Int,
        max: Int,
    ) {
        var built = 0
        var mask = 1
        while (built + mask < max && mask != 0) {
            val set = value and mask != 0
            bit(if (set) 1 else 0)
            if (set) built = built or mask
            mask = mask shl 1
        }
    }

    fun payloadBits(
        payload: ByteArray,
        count: Int,
    ) {
        for (index in 0 until count) {
            val byteValue = payload[index shr 3].toInt()
            bit((byteValue shr (index and 7)) and 1)
        }
    }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray((bits.size + 7) / 8)
        for (index in bits.indices) {
            if (bits[index] == 1) {
                bytes[index shr 3] = (bytes[index shr 3].toInt() or (1 shl (index and 7))).toByte()
            }
        }
        return bytes
    }
}

private fun DemoBitWriter.bunch(
    channel: Int,
    payload: ByteArray,
) {
    bit(0) // not an ack
    bit(0) // control
    bit(0) // reliable
    int(channel, MAX_CHANNEL)
    val bitCount = payload.size * 8
    int(bitCount, MAX_PACKET_BYTES * 8)
    payloadBits(payload, bitCount)
}

private fun demoCompactIndex(value: Int): ByteArray {
    val bytes = mutableListOf<Int>()
    var remaining = value ushr 6
    var first = value and 0x3F
    if (remaining != 0) first = first or 0x40
    bytes += first
    while (remaining != 0) {
        var chunk = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining != 0) chunk = chunk or 0x80
        bytes += chunk
    }
    return ByteArray(bytes.size) { bytes[it].toByte() }
}

private fun demoFString(text: String): ByteArray {
    val content = text.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
    return demoCompactIndex(content.size) + content
}

private fun demoPacket(bunches: List<Pair<Int, List<String>>>): ByteArray {
    val writer = DemoBitWriter()
    writer.int(0, MAX_PACKET_ID)
    for ((channel, lines) in bunches) {
        val payload = lines.fold(ByteArray(0)) { acc, line -> acc + demoFString(line) }
        writer.bunch(channel, payload)
    }
    writer.bit(1) // terminating bit
    return writer.toByteArray()
}

private fun demoTimeFields(time: Float): Pair<Int, Int> {
    val bits = time.toRawBits()
    val low = bits and 0x7FFFFF
    val high = (bits ushr 23) shl 20
    return low to high
}

private fun demoRecord(
    frame: Int,
    time: Float,
    bunches: List<Pair<Int, List<String>>>,
): ByteArray {
    val packet = demoPacket(bunches)
    val (low, high) = demoTimeFields(time)
    val buffer = ByteBuffer.allocate(16 + packet.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(frame)
    buffer.putInt(low)
    buffer.putInt(high)
    buffer.putInt(packet.size)
    buffer.put(packet)
    return buffer.array()
}

private fun demoControlRecord(
    frame: Int,
    time: Float,
    lines: List<String>,
): ByteArray = demoRecord(frame, time, listOf(CONTROL_CHANNEL to lines))

private fun demoTruncatedHeader(
    frame: Int,
    time: Float,
    claimedByteCount: Int,
): ByteArray {
    val (low, high) = demoTimeFields(time)
    val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(frame)
    buffer.putInt(low)
    buffer.putInt(high)
    buffer.putInt(claimedByteCount)
    return buffer.array()
}

private fun demoBytes(vararg records: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    records.forEach { output.write(it) }
    return output.toByteArray()
}

class UtDemoTest :
    FunSpec({
        context("real demo") {
            val resource = requireNotNull(UtDemoTest::class.java.getResource("/demo.dem"))
            val path = Path.of(resource.toURI())

            // Real demo, map: the sample demo.dem opens CTF-BT-II-CoZyliciouS, the level its WELCOME line names.
            test("map") {
                UtDemo.read(path).map shouldBe "CTF-BT-II-CoZyliciouS"
            }

            // Real demo, packages: the sample demo.dem reads to exactly the packages its USES lines name, in the order the
            // demo names them. Stock packages such as Core and Engine are included, and so are entries whose file name is a cache
            // name of the form <GUID>.uxx.
            test("packages") {
                UtDemo.read(path).packages shouldBe
                    listOf(
                        UtDemoPackage("CTF-BT-II-CoZyliciouS", "9BC77A5B4BA204262CD5288BD5973216", "CTF-BT-II-CoZyliciouS.unr"),
                        UtDemoPackage("Engine", "D18A7B9211D38F04100067B9F6F8975A", "Engine.u"),
                        UtDemoPackage("Core", "4770B88411D38E3E100067B9F6F8975A", "Core.u"),
                        UtDemoPackage("Egypt", "C22468A311D2C10C45442B9700005453", "Egypt.utx"),
                        UtDemoPackage("Detail", "1E90ACA811D1ED664544279700005453", "Detail.utx"),
                        UtDemoPackage("Fire", "4770B88811D38E3E100067B9F6F8975A", "Fire.u"),
                        UtDemoPackage("NaliCast", "1E90ACBF11D1ED664544279700005453", "NaliCast.utx"),
                        UtDemoPackage("DoorsAnc", "A3B94EE711D1ED674544E58A00005453", "DoorsAnc.uax"),
                        UtDemoPackage("UnrealShare", "4770B88C11D38E3E100067B9F6F8975A", "UnrealShare.u"),
                        UtDemoPackage("DoorsSnd", "014A12E111D49A06A0006EB2333218D2", "DoorsSnd.uax"),
                        UtDemoPackage("FireEng", "1E90ACAC11D1ED664544279700005453", "FireEng.utx"),
                        UtDemoPackage("genfluid", "1E90ACAE11D1ED664544279700005453", "genfluid.utx"),
                        UtDemoPackage("NaliFX", "1E90ACC011D1ED664544279700005453", "NaliFX.utx"),
                        UtDemoPackage("swJumpPad", "5AC8779640DFA12A546D2B907A0A7D52", "swJumpPad.u"),
                        UtDemoPackage("Botpack", "1C69657611D38F44100067B9F6F8975A", "Botpack.u"),
                        UtDemoPackage("UnrealI", "4770B88D11D38E3E100067B9F6F8975A", "UnrealI.u"),
                        UtDemoPackage("AmbAncient", "A3B94EE311D1ED674544E58A00005453", "AmbAncient.uax"),
                        UtDemoPackage("FurryMover4", "1D611D194B8851B95A7527901DA9CF11", "FurryMover4.u"),
                        UtDemoPackage("Skyward", "C073770511D2D1A4E000278383161081", "Skyward.umx"),
                        UtDemoPackage("SoldierSkins", "93CC0A8111D3888FE0006295BE341081", "SoldierSkins.utx"),
                        UtDemoPackage("CommandoSkins", "E96BC96311D31D2F4F006B8CDE9A0349", "CommandoSkins.utx"),
                        UtDemoPackage("FCommandoSkins", "B7B49CA611D38BCDE0006395BE341081", "FCommandoSkins.utx"),
                        UtDemoPackage("SGirlSkins", "D4F6ABE111D385CAE0006295BE341081", "SGirlSkins.utx"),
                        UtDemoPackage("BossSkins", "24E5A72411D321104F006B8CDE9A0349", "BossSkins.utx"),
                        UtDemoPackage("UTSAccuBeta4_2", "9DD2A904438C8CA729588EBFBA3F8501", "9DD2A904438C8CA729588EBFBA3F8501.uxx"),
                        UtDemoPackage("BTPPUser", "1542D4954E3A947A74E2AEBDDF7D0F7E", "1542D4954E3A947A74E2AEBDDF7D0F7E.uxx"),
                        UtDemoPackage("IpToCountry_AOL", "4DF59BA011DAA3D3500087C6E7D45DFC", "IpToCountry_AOL.u"),
                        UtDemoPackage("CountryFlags3", "DC1B324D41E3BBED5849DF8FCD5042CD", "CountryFlags3.utx"),
                        UtDemoPackage("UTBT_BTMod_v17", "06A243994ADC9DAB5068B9A5D0B9C82E", "UTBT_BTMod_v17.u"),
                        UtDemoPackage("LadrStatic", "2E005A6211D2FB5650004BB23FDC1804", "LadrStatic.utx"),
                        UtDemoPackage("UTBT_BTPog_v06", "9051AF28490DD00A30C54AAB8EF1A153", "9051AF28490DD00A30C54AAB8EF1A153.uxx"),
                        UtDemoPackage("BTACv6", "2BFA88C54BED63DF0AFCE3B430061366", "2BFA88C54BED63DF0AFCE3B430061366.uxx"),
                        UtDemoPackage("UTBT_Fonts", "CF845D8411D380DD100060B9F6F8975A", "UTBT_Fonts.utx"),
                        UtDemoPackage("UTBT_Medals_Fill", "CF48A7124F4E982F135092875566AEB3", "UTBT_Medals_Fill.utx"),
                        UtDemoPackage("UTBT_Medals_Outline", "C805E9474B73BB322F58DAB87F41CBF1", "UTBT_Medals_Outline.utx"),
                        UtDemoPackage("UTBT_MapVote_v32", "C72608FC48C854A09A51E7964C08AD3C", "C72608FC48C854A09A51E7964C08AD3C.uxx"),
                        UtDemoPackage("UTBT_Auth_V10", "A506D52E47DF383158CB0CB74300029B", "A506D52E47DF383158CB0CB74300029B.uxx"),
                        UtDemoPackage("UTBT_PlayerPawn_V17", "B831230A47D532D98A01FEBD9CE7AD59", "B831230A47D532D98A01FEBD9CE7AD59.uxx"),
                        UtDemoPackage("NPLoader_v22", "5070D28611F133A10F7DD08F28F4FB99", "NPLoader_v22.u"),
                        UtDemoPackage("NPLoaderLLU_v22", "587DF6AC11F133A11586B9929D58E5EB", "NPLoaderLLU_v22.u"),
                        UtDemoPackage("NPLoaderLLD_x86_v22", "524665DA11F133A112E96394E6399E16", "NPLoaderLLD_x86_v22.u"),
                        UtDemoPackage("NPLoaderLLD_amd64_v22", "53599B7C11F133A10E4052ACC61F157B", "NPLoaderLLD_amd64_v22.u"),
                        UtDemoPackage("NPLoaderLLS_x86_v22", "5467B8D211F133A1E3B2A681E92F4A26", "NPLoaderLLS_x86_v22.u"),
                        UtDemoPackage("NPLoaderLLS_amd64_v22", "556A263E11F133A1F9F9DBB7598421EE", "NPLoaderLLS_amd64_v22.u"),
                        UtDemoPackage("NPLoaderLLS_arm64_v22", "56718FB811F133A178704FAC76CD96E6", "NPLoaderLLS_arm64_v22.u"),
                        UtDemoPackage("NPLoaderLLDL_v22", "577CCE9A11F133A1A9A13886B9BCE6A2", "NPLoaderLLDL_v22.u"),
                        UtDemoPackage("IACEv14", "0CF870C211F15156D7AF3AA0EDA4ECD2", "IACEv14.u"),
                        UtDemoPackage("ACEv14c_Cdll", "117D5BE411F1515662D1BDA0B4C49C96", "ACEv14c_Cdll.u"),
                        UtDemoPackage("ACEv14c_Cso", "129502DE11F15156C4B77EAAF9CEAD28", "ACEv14c_Cso.u"),
                        UtDemoPackage("ACEv14c_C", "104EF26E11F151560AA6B991E4B3F9EE", "ACEv14c_C.u"),
                    )
            }

            // Real demo, duration: the sample demo.dem lasts 78.3621 s, the time of its last record, within a small tolerance.
            test("duration") {
                (UtDemo.read(path).duration - 78.3621.seconds).absoluteValue shouldBeLessThanOrEqualTo 1.milliseconds
            }
        }

        context("malformed demo") {
            // Malformed demo, cut off: a demo cut off in the middle of a record keeps the map and the packages of the records
            // before the cut, and takes its duration from the last whole record.
            test("cut off") {
                val first =
                    demoControlRecord(
                        frame = 0,
                        time = 1.5f,
                        lines =
                            listOf(
                                "WELCOME LEVEL=TestMap LONE=0",
                                "USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=First FLAGS=1 SIZE=100 GEN=1 FNAME=First.u",
                            ),
                    )
                val second =
                    demoControlRecord(
                        frame = 1,
                        time = 5.25f,
                        lines =
                            listOf(
                                "USES GUID=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB PKG=Second FLAGS=1 SIZE=100 GEN=1 FNAME=Second.u",
                            ),
                    )
                val cutOff = demoTruncatedHeader(frame = 2, time = 9f, claimedByteCount = 50)
                val bytes = demoBytes(first, second, cutOff)

                val demo = UtDemo.read(bytes)

                demo.map shouldBe "TestMap"
                demo.packages shouldBe
                    listOf(
                        UtDemoPackage("First", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "First.u"),
                        UtDemoPackage("Second", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", "Second.u"),
                    )
                demo.duration shouldBe 5.25.seconds
            }
        }

        context("package rules") {
            // Package rules, repeated name: a package named twice, the two names differing only in case, appears once, as its
            // first entry.
            test("repeated name") {
                val bytes =
                    demoBytes(
                        demoControlRecord(
                            frame = 0,
                            time = 1f,
                            lines =
                                listOf(
                                    "WELCOME LEVEL=TestMap LONE=0",
                                    "USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=Weapon FLAGS=1 SIZE=100 GEN=1 FNAME=Weapon.u",
                                    "USES GUID=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB PKG=weapon FLAGS=1 SIZE=100 GEN=1 FNAME=weapon.u",
                                ),
                        ),
                    )

                UtDemo.read(bytes).packages shouldBe
                    listOf(UtDemoPackage("Weapon", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Weapon.u"))
            }

            // Package rules, other channel: a USES line carried on a channel other than the control channel is not read as a
            // package.
            test("other channel") {
                val bytes =
                    demoBytes(
                        demoRecord(
                            frame = 0,
                            time = 1f,
                            bunches =
                                listOf(
                                    CONTROL_CHANNEL to listOf("WELCOME LEVEL=TestMap LONE=0"),
                                    1 to
                                        listOf(
                                            "USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=Weapon FLAGS=1 SIZE=100 GEN=1 FNAME=Weapon.u",
                                        ),
                                ),
                        ),
                    )

                UtDemo.read(bytes).packages shouldBe emptyList()
            }
        }
    })
