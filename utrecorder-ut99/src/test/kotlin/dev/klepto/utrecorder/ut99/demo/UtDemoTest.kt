package dev.klepto.utrecorder.ut99.demo

import dev.klepto.utrecorder.ut99.UtPackage
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

private fun demoRawPacket(bunches: List<Pair<Int, ByteArray>>): ByteArray {
    val writer = DemoBitWriter()
    writer.int(0, MAX_PACKET_ID)
    for ((channel, payload) in bunches) {
        writer.bunch(channel, payload)
    }
    writer.bit(1) // terminating bit
    return writer.toByteArray()
}

private fun demoPacket(bunches: List<Pair<Int, List<String>>>): ByteArray =
    demoRawPacket(
        bunches.map { (channel, lines) ->
            channel to lines.fold(ByteArray(0)) { acc, line -> acc + demoFString(line) }
        },
    )

// Copies a payload's bits forward by a count, leaving the bits before it clear, so a framed string starts off a byte
// boundary the way the real demo's messages do.
private fun demoShiftedPayload(
    shift: Int,
    payload: ByteArray,
): ByteArray {
    val bytes = ByteArray((shift + payload.size * 8 + 7) / 8)
    for (index in 0 until payload.size * 8) {
        if ((payload[index shr 3].toInt() shr (index and 7)) and 1 == 1) {
            val target = index + shift
            bytes[target shr 3] = (bytes[target shr 3].toInt() or (1 shl (target and 7))).toByte()
        }
    }
    return bytes
}

private fun demoTimeFields(time: Float): Pair<Int, Int> {
    val bits = time.toRawBits()
    val low = bits and 0x7FFFFF
    val high = (bits ushr 23) shl 20
    return low to high
}

private fun demoRecordOf(
    frame: Int,
    time: Float,
    packet: ByteArray,
): ByteArray {
    val (low, high) = demoTimeFields(time)
    val buffer = ByteBuffer.allocate(16 + packet.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(frame)
    buffer.putInt(low)
    buffer.putInt(high)
    buffer.putInt(packet.size)
    buffer.put(packet)
    return buffer.array()
}

private fun demoRecord(
    frame: Int,
    time: Float,
    bunches: List<Pair<Int, List<String>>>,
): ByteArray = demoRecordOf(frame, time, demoPacket(bunches))

private fun demoRawRecord(
    frame: Int,
    time: Float,
    bunches: List<Pair<Int, ByteArray>>,
): ByteArray = demoRecordOf(frame, time, demoRawPacket(bunches))

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

// Bunny Track line builders. The mods compose these strings server-side: UTBT_BTMod_v17 broadcasts the finished in and
// capped! lines to everyone, and BTPog sends its two markers to the owning client alone, which is why the real demo
// carries them on channels of their own rather than on the control channel.
private const val GAME_CHANNEL = 1
private const val MARKER_CHANNEL = 298

private class CapLine(
    val time: Float,
    val channel: Int,
    val text: String,
)

private fun btSpawn(
    time: Float,
    id: String,
) = CapLine(time, MARKER_CHANNEL, "BTPOG_SPAWN_MARKER:$id")

private fun btCapMarker(
    time: Float,
    id: String,
) = CapLine(time, MARKER_CHANNEL, "BTPOG_CAP_MARKER:$id")

private fun btFinished(
    time: Float,
    name: String,
    reported: String,
    place: String,
) = CapLine(time, GAME_CHANNEL, "[UTBT] $name finished in $reported ($place)")

private fun btCapped(
    time: Float,
    vararg names: String,
) = CapLine(time, GAME_CHANNEL, names.joinToString(" and ") + " capped! Red team scores!")

// A demo of cap lines: a control record naming the map, then one record per line. Every record time is an exact binary
// fraction, so no case turns on whether the reader rounds or truncates its milliseconds.
private fun demoCapBytes(
    vararg lines: CapLine,
    shift: Int = 0,
): ByteArray =
    demoBytes(
        demoControlRecord(frame = 0, time = 0f, lines = listOf("WELCOME LEVEL=TestMap LONE=0")),
        *lines
            .mapIndexed { index, line ->
                demoRawRecord(
                    frame = index + 1,
                    time = line.time,
                    bunches = listOf(line.channel to demoShiftedPayload(shift, demoFString(line.text))),
                )
            }.toTypedArray(),
    )

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
            // demo names them. Stock packages such as Core and Engine are included, and so are the packages the client loaded
            // out of its cache. The GUIDs are asserted on their own because a package compares on its name alone.
            test("packages") {
                val expected =
                    listOf(
                        UtPackage.named("CTF-BT-II-CoZyliciouS", "9BC77A5B4BA204262CD5288BD5973216"),
                        UtPackage.named("Engine", "D18A7B9211D38F04100067B9F6F8975A"),
                        UtPackage.named("Core", "4770B88411D38E3E100067B9F6F8975A"),
                        UtPackage.named("Egypt", "C22468A311D2C10C45442B9700005453"),
                        UtPackage.named("Detail", "1E90ACA811D1ED664544279700005453"),
                        UtPackage.named("Fire", "4770B88811D38E3E100067B9F6F8975A"),
                        UtPackage.named("NaliCast", "1E90ACBF11D1ED664544279700005453"),
                        UtPackage.named("DoorsAnc", "A3B94EE711D1ED674544E58A00005453"),
                        UtPackage.named("UnrealShare", "4770B88C11D38E3E100067B9F6F8975A"),
                        UtPackage.named("DoorsSnd", "014A12E111D49A06A0006EB2333218D2"),
                        UtPackage.named("FireEng", "1E90ACAC11D1ED664544279700005453"),
                        UtPackage.named("genfluid", "1E90ACAE11D1ED664544279700005453"),
                        UtPackage.named("NaliFX", "1E90ACC011D1ED664544279700005453"),
                        UtPackage.named("swJumpPad", "5AC8779640DFA12A546D2B907A0A7D52"),
                        UtPackage.named("Botpack", "1C69657611D38F44100067B9F6F8975A"),
                        UtPackage.named("UnrealI", "4770B88D11D38E3E100067B9F6F8975A"),
                        UtPackage.named("AmbAncient", "A3B94EE311D1ED674544E58A00005453"),
                        UtPackage.named("FurryMover4", "1D611D194B8851B95A7527901DA9CF11"),
                        UtPackage.named("Skyward", "C073770511D2D1A4E000278383161081"),
                        UtPackage.named("SoldierSkins", "93CC0A8111D3888FE0006295BE341081"),
                        UtPackage.named("CommandoSkins", "E96BC96311D31D2F4F006B8CDE9A0349"),
                        UtPackage.named("FCommandoSkins", "B7B49CA611D38BCDE0006395BE341081"),
                        UtPackage.named("SGirlSkins", "D4F6ABE111D385CAE0006295BE341081"),
                        UtPackage.named("BossSkins", "24E5A72411D321104F006B8CDE9A0349"),
                        UtPackage.named("UTSAccuBeta4_2", "9DD2A904438C8CA729588EBFBA3F8501"),
                        UtPackage.named("BTPPUser", "1542D4954E3A947A74E2AEBDDF7D0F7E"),
                        UtPackage.named("IpToCountry_AOL", "4DF59BA011DAA3D3500087C6E7D45DFC"),
                        UtPackage.named("CountryFlags3", "DC1B324D41E3BBED5849DF8FCD5042CD"),
                        UtPackage.named("UTBT_BTMod_v17", "06A243994ADC9DAB5068B9A5D0B9C82E"),
                        UtPackage.named("LadrStatic", "2E005A6211D2FB5650004BB23FDC1804"),
                        UtPackage.named("UTBT_BTPog_v06", "9051AF28490DD00A30C54AAB8EF1A153"),
                        UtPackage.named("BTACv6", "2BFA88C54BED63DF0AFCE3B430061366"),
                        UtPackage.named("UTBT_Fonts", "CF845D8411D380DD100060B9F6F8975A"),
                        UtPackage.named("UTBT_Medals_Fill", "CF48A7124F4E982F135092875566AEB3"),
                        UtPackage.named("UTBT_Medals_Outline", "C805E9474B73BB322F58DAB87F41CBF1"),
                        UtPackage.named("UTBT_MapVote_v32", "C72608FC48C854A09A51E7964C08AD3C"),
                        UtPackage.named("UTBT_Auth_V10", "A506D52E47DF383158CB0CB74300029B"),
                        UtPackage.named("UTBT_PlayerPawn_V17", "B831230A47D532D98A01FEBD9CE7AD59"),
                        UtPackage.named("NPLoader_v22", "5070D28611F133A10F7DD08F28F4FB99"),
                        UtPackage.named("NPLoaderLLU_v22", "587DF6AC11F133A11586B9929D58E5EB"),
                        UtPackage.named("NPLoaderLLD_x86_v22", "524665DA11F133A112E96394E6399E16"),
                        UtPackage.named("NPLoaderLLD_amd64_v22", "53599B7C11F133A10E4052ACC61F157B"),
                        UtPackage.named("NPLoaderLLS_x86_v22", "5467B8D211F133A1E3B2A681E92F4A26"),
                        UtPackage.named("NPLoaderLLS_amd64_v22", "556A263E11F133A1F9F9DBB7598421EE"),
                        UtPackage.named("NPLoaderLLS_arm64_v22", "56718FB811F133A178704FAC76CD96E6"),
                        UtPackage.named("NPLoaderLLDL_v22", "577CCE9A11F133A1A9A13886B9BCE6A2"),
                        UtPackage.named("IACEv14", "0CF870C211F15156D7AF3AA0EDA4ECD2"),
                        UtPackage.named("ACEv14c_Cdll", "117D5BE411F1515662D1BDA0B4C49C96"),
                        UtPackage.named("ACEv14c_Cso", "129502DE11F15156C4B77EAAF9CEAD28"),
                        UtPackage.named("ACEv14c_C", "104EF26E11F151560AA6B991E4B3F9EE"),
                    )

                val packages = UtDemo.read(path).packages

                packages shouldBe expected
                packages.map { it.guid } shouldBe expected.map { it.guid }
            }

            // Real demo, map package: the level package is the first USES line a demo carries, so the map is the first entry of
            // the packages, and nothing has to fetch it separately.
            test("map is the first package") {
                val demo = UtDemo.read(path)

                demo.packages.first().name shouldBe demo.map
            }

            // Real demo, duration: the sample demo.dem lasts 78.3621 s, the time of its last record, within a small tolerance.
            test("duration") {
                (UtDemo.read(path).duration - 78.3621.seconds).absoluteValue shouldBeLessThanOrEqualTo 1.milliseconds
            }

            // Real demo, cap: the sample demo.dem reads to the cap its BTPog markers and its UTBT broadcast lines
            // report together - capper fierd_1, id 2ZNKJEI2Y1K05AENS6UM827L, a run time of 74907 ms and a demo time of
            // 77634 ms. kleps is not the capper: the 1:13.987 kleps is reported to have finished in is 0.92 s from the
            // marker gap, which is what the tolerance exists to reject.
            test("cap") {
                val cap = requireNotNull(UtDemo.read(path).cap)

                cap.capper shouldBe "fierd_1"
                cap.id shouldBe "2ZNKJEI2Y1K05AENS6UM827L"
                cap.runTime shouldBe 74.907.seconds
                (cap.demoTime - 77.634.seconds).absoluteValue shouldBeLessThanOrEqualTo 1.milliseconds
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
                        UtPackage.named("First", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                        UtPackage.named("Second", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
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

                val packages = UtDemo.read(bytes).packages

                packages shouldBe listOf(UtPackage.named("Weapon", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                packages.single().guid shouldBe "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            }

            // Package rules, no GUID: a USES line carrying no GUID field gives a package all the same, with an empty GUID, so
            // that a package the demo recorded no version of asks for any version of it.
            test("no GUID") {
                val bytes =
                    demoBytes(
                        demoControlRecord(
                            frame = 0,
                            time = 1f,
                            lines =
                                listOf(
                                    "WELCOME LEVEL=TestMap LONE=0",
                                    "USES PKG=Weapon FLAGS=1 SIZE=100 GEN=1 FNAME=Weapon.u",
                                ),
                        ),
                    )

                val packages = UtDemo.read(bytes).packages

                packages shouldBe listOf(UtPackage.named("Weapon", ""))
                packages.single().guid shouldBe ""
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

        context("cap evidence") {
            // Cap evidence, no cap: a demo whose records carry nothing but control lines reads normally - the map and
            // the packages are read as before, and the cap is null.
            test("no cap") {
                val bytes =
                    demoBytes(
                        demoControlRecord(
                            frame = 0,
                            time = 1f,
                            lines =
                                listOf(
                                    "WELCOME LEVEL=TestMap LONE=0",
                                    "USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=First FLAGS=1 SIZE=100 GEN=1 FNAME=First.u",
                                ),
                        ),
                    )

                val demo = UtDemo.read(bytes)

                demo.map shouldBe "TestMap"
                demo.packages shouldBe listOf(UtPackage.named("First", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                demo.cap shouldBe null
            }

            // Cap evidence, markers only: a demo carrying a spawn marker and a cap marker of the same id, and no
            // broadcast line, yields a cap all the same - the id and the demo time come from the cap marker, the run
            // time is the gap between the two markers, and the capper is null because nothing names anybody.
            test("markers only") {
                val bytes = demoCapBytes(btSpawn(2.5f, "CAPID1"), btCapMarker(77f, "CAPID1"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap evidence, messages only: a demo carrying the finished in and capped! lines and no marker yields a
            // cap - the capper and the run time come from the text, the demo time comes from the record the capped!
            // line arrives in, and the id is null.
            test("messages only") {
                val bytes = demoCapBytes(btFinished(77f, "kleps", "1:14.500", "1/1"), btCapped(77f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = null, runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap evidence, cap marker alone with a reported time: a cap marker with no spawn marker of its id still
            // yields a cap, with its id and its demo time, and a run time taken from the message that reports one.
            test("cap marker alone with a reported time") {
                val bytes =
                    demoCapBytes(
                        btCapMarker(77f, "CAPID1"),
                        btFinished(77f, "kleps", "1:14.500", "1/1"),
                        btCapped(77f, "kleps"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap evidence, cap marker alone with no reported time: the same demo without its finished in line yields
            // a cap with a null run time, because there is neither a gap to measure nor a time to read.
            test("cap marker alone with no reported time") {
                val bytes = demoCapBytes(btCapMarker(77f, "CAPID1"), btCapped(77f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = null, demoTime = 77.seconds)
            }

            // Cap evidence, spawn marker alone: a demo that starts a run and never caps it carries a spawn marker and
            // nothing else, and reads with a null cap.
            test("spawn marker alone") {
                UtDemo.read(demoCapBytes(btSpawn(2.5f, "CAPID1"))).cap shouldBe null
            }

            // Cap evidence, mismatched marker ids: a cap marker whose id differs from the spawn marker's does not pair
            // with it. The cap carries the cap marker's own id and no gap, so the run time is not measured from a
            // spawn that belongs to another run.
            test("mismatched marker ids") {
                val bytes =
                    demoCapBytes(btSpawn(2.5f, "CAPID1"), btCapMarker(77f, "CAPID2"), btCapped(77f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID2", runTime = null, demoTime = 77.seconds)
            }
        }

        context("cap capper") {
            // Cap capper, reported time inside the tolerance: a team run whose only reported time is 40 ms from the
            // marker gap gives the player that time belongs to as the capper.
            test("reported time inside the tolerance") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btFinished(77f, "kleps", "1:13.000", "1/2"),
                        btFinished(77f, "fierd_1", "1:14.540", "2/2"),
                        btCapped(77f, "kleps", "fierd_1"),
                        btCapMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "fierd_1", id = "CAPID1", runTime = 74.54.seconds, demoTime = 77.seconds)
            }

            // Cap capper, reported time outside the tolerance: the same team run with its reported time 60 ms from the
            // gap leaves the capper null, and the cap still carries its id and its times.
            test("reported time outside the tolerance") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btFinished(77f, "kleps", "1:13.000", "1/2"),
                        btFinished(77f, "fierd_1", "1:14.560", "2/2"),
                        btCapped(77f, "kleps", "fierd_1"),
                        btCapMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap capper, closest time wins: a team run whose two reported times are 10 ms and 30 ms from the gap
            // gives the 10 ms player as the capper.
            test("closest time wins") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btFinished(77f, "kleps", "1:14.530", "1/2"),
                        btFinished(77f, "fierd_1", "1:14.510", "2/2"),
                        btCapped(77f, "kleps", "fierd_1"),
                        btCapMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "fierd_1", id = "CAPID1", runTime = 74.51.seconds, demoTime = 77.seconds)
            }

            // Cap capper, exact tie: a team run whose two reported times are the same distance either side of the gap
            // leaves the capper null.
            test("exact tie") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btFinished(77f, "kleps", "1:14.480", "1/2"),
                        btFinished(77f, "fierd_1", "1:14.520", "2/2"),
                        btCapped(77f, "kleps", "fierd_1"),
                        btCapMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap capper, one name: a capped! line naming exactly one player gives that player as the capper when no
            // line reports a time, because a solo run leaves one candidate and nothing to guess between.
            test("one name") {
                val bytes =
                    demoCapBytes(btSpawn(2.5f, "CAPID1"), btCapped(77f, "kleps"), btCapMarker(77f, "CAPID1"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap capper, no reported time: a capped! line naming two players, with no finished in line anywhere in
            // the demo, gives a cap with a null capper.
            test("no reported time") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btCapped(77f, "kleps", "fierd_1"),
                        btCapMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }
        }

        context("cap selection") {
            // Cap selection, first cap: a demo carrying a whole cap and then a second whole cap, of another id and
            // another name, reads to the first - what follows a cap is the next round starting.
            test("first cap") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btCapped(77f, "kleps"),
                        btCapMarker(77f, "CAPID1"),
                        btSpawn(78f, "CAPID2"),
                        btCapped(100f, "fierd_1"),
                        btCapMarker(100f, "CAPID2"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap selection, repeated evidence: a demo carrying the same cap marker and the same capped! line twice,
            // in two records at two different times, reads to the earlier time.
            test("repeated evidence") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btCapMarker(77f, "CAPID1"),
                        btCapped(77f, "kleps"),
                        btCapMarker(80f, "CAPID1"),
                        btCapped(80f, "kleps"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap selection, marker before broadcast: a demo whose cap marker arrives a second before its capped! line
            // takes its demo time from the marker, not from the broadcast that follows it.
            test("marker before broadcast") {
                val bytes =
                    demoCapBytes(btSpawn(2.5f, "CAPID1"), btCapMarker(77f, "CAPID1"), btCapped(78f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }
        }

        context("cap text") {
            // Cap text, bit offset: a demo whose cap lines sit at a bit offset inside their bunch, rather than on a
            // byte boundary, reads to the same cap as one whose lines are byte aligned.
            test("bit offset") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btCapped(77f, "kleps"),
                        btCapMarker(77f, "CAPID1"),
                        shift = 5,
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap text, length check: a demo carrying a cap-shaped run of printable text whose preceding compact-index
            // byte is not its own length reads with a null cap, so no cap is invented out of raw data.
            test("length check") {
                val text = "kleps and fierd_1 capped! Red team scores!"
                val payload = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
                val bytes =
                    demoBytes(
                        demoControlRecord(frame = 0, time = 0f, lines = listOf("WELCOME LEVEL=TestMap LONE=0")),
                        demoRawRecord(frame = 1, time = 77f, bunches = listOf(GAME_CHANNEL to payload)),
                    )

                UtDemo.read(bytes).cap shouldBe null
            }

            // Cap text, name encoding: a capper whose name carries a byte above 0x7F yields a cap, and the capper
            // reads back with that character intact.
            test("name encoding") {
                val name = "fi\u00E9rd_1"
                val bytes =
                    demoCapBytes(btSpawn(2.5f, "CAPID1"), btCapped(77f, name), btCapMarker(77f, "CAPID1"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = name, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            // Cap text, seconds only time format: a run under a minute reports its time as SS.mmm rather than
            // M:SS.mmm, and the run time reads back from it.
            test("seconds only time format") {
                val bytes =
                    demoCapBytes(
                        btSpawn(2.5f, "CAPID1"),
                        btFinished(57f, "kleps", "53.000", "1/2"),
                        btFinished(57f, "fierd_1", "54.500", "2/2"),
                        btCapped(57f, "kleps", "fierd_1"),
                        btCapMarker(57f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "fierd_1", id = "CAPID1", runTime = 54.5.seconds, demoTime = 57.seconds)
            }
        }
    })
