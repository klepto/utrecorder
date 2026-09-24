package dev.klepto.utrecorder.ut99.demo

import dev.klepto.utrecorder.ut99.UtPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests [UtDemo] and [UtDemoRecord] against the `demo.dem` resource and against demos built in memory.
 *
 * A built demo is a run of records, each a 16-byte header holding the demo time and packet size, then a packet. The
 * packet writes its packet id, bunches and acknowledgements bit by bit in the layout the reader decodes, and ends in a
 * set bit. Cap lines go out on channel 298 for the `BTPog` markers and on channel 1 for the `UTBT_BTMod` broadcasts.
 */
class UtDemoTest :
    FunSpec({
        val welcome = "WELCOME LEVEL=TestMap LONE=0"

        fun bitsOf(bytes: ByteArray): List<Int> =
            List(bytes.size * Byte.SIZE_BITS) { (bytes[it / Byte.SIZE_BITS].toInt() shr (it % Byte.SIZE_BITS)) and 1 }

        fun bytesOf(bits: List<Int>): ByteArray =
            ByteArray((bits.size + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS).also { bytes ->
                bits.forEachIndexed { index, bit ->
                    val byte = index / Byte.SIZE_BITS
                    bytes[byte] = (bytes[byte].toInt() or (bit shl (index % Byte.SIZE_BITS))).toByte()
                }
            }

        fun MutableList<Int>.int(
            value: Int,
            max: Int,
        ) {
            var written = 0
            var mask = 1
            while (written + mask < max && mask != 0) {
                val bit = if (value and mask != 0) 1 else 0
                this += bit
                written = written or (mask * bit)
                mask = mask shl 1
            }
        }

        fun MutableList<Int>.packetId(id: Int) = int(id, 16384)

        fun MutableList<Int>.ack(id: Int) {
            this += 1
            packetId(id)
        }

        fun MutableList<Int>.bunch(
            channel: Int,
            payload: ByteArray,
            open: Boolean = false,
            close: Boolean = false,
            reliable: Boolean = false,
        ) {
            this += 0
            this += if (open || close) 1 else 0
            if (open || close) {
                this += if (open) 1 else 0
                this += if (close) 1 else 0
            }
            this += if (reliable) 1 else 0
            int(channel, 1023)
            if (reliable) int(517, 1024)
            if (reliable || open) int(5, 8)
            int(payload.size * Byte.SIZE_BITS, 512 * Byte.SIZE_BITS)
            this += bitsOf(payload)
        }

        fun packet(bunches: MutableList<Int>.() -> Unit): ByteArray {
            val bits =
                buildList {
                    packetId(0)
                    bunches()
                    this += 1
                }
            return bytesOf(bits)
        }

        fun compactIndex(value: Int): ByteArray {
            val groups =
                generateSequence(value ushr 6) { it ushr 7 }
                    .takeWhile { it != 0 }
                    .map { it and 0x7F }
                    .toList()
            val first = (value and 0x3F) or (if (groups.isEmpty()) 0 else 0x40)
            val rest = groups.mapIndexed { index, group -> if (index < groups.lastIndex) group or 0x80 else group }
            return (listOf(first) + rest)
                .map { it.toByte() }
                .toByteArray()
        }

        fun strings(vararg lines: String): ByteArray =
            lines.fold(ByteArray(0)) { bytes, line ->
                val content = line.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
                bytes + compactIndex(content.size) + content
            }

        fun header(
            time: Float,
            count: Int,
        ): ByteArray {
            val bits = time.toRawBits()
            return ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0)
                .putInt(bits and 0x7FFFFF)
                .putInt((bits ushr 23) shl 20)
                .putInt(count)
                .array()
        }

        fun record(
            time: Float,
            packet: ByteArray,
        ): ByteArray = header(time, packet.size) + packet

        fun controlRecord(
            time: Float,
            vararg lines: String,
        ): ByteArray = record(time, packet { bunch(0, strings(*lines)) })

        fun demo(vararg records: ByteArray): ByteArray = records.fold(ByteArray(0)) { bytes, record -> bytes + record }

        class CapLine(
            val time: Float,
            val channel: Int,
            val text: String,
        )

        fun spawnMarker(
            time: Float,
            id: String,
        ) = CapLine(time, 298, "BTPOG_SPAWN_MARKER:$id")

        fun capMarker(
            time: Float,
            id: String,
        ) = CapLine(time, 298, "BTPOG_CAP_MARKER:$id")

        fun finished(
            time: Float,
            name: String,
            reported: String,
            place: String,
        ) = CapLine(time, 1, "[UTBT] $name finished in $reported ($place)")

        fun capped(
            time: Float,
            vararg names: String,
        ) = CapLine(time, 1, names.joinToString(" and ") + " capped! Red team scores!")

        fun capRecord(
            line: CapLine,
            shift: Int,
        ): ByteArray {
            val payload = bytesOf(List(shift) { 0 } + bitsOf(strings(line.text)))
            return record(line.time, packet { bunch(line.channel, payload) })
        }

        fun capDemo(
            vararg lines: CapLine,
            shift: Int = 0,
        ): ByteArray {
            val capRecords = lines.map { capRecord(it, shift) }
            return demo(controlRecord(0f, welcome), *capRecords.toTypedArray())
        }

        context("real demo") {
            val resource = requireNotNull(UtDemoTest::class.java.getResource("/demo.dem"))
            val path = Path.of(resource.toURI())

            test("read returns the map the demo opens") {
                UtDemo.read(path).map shouldBe "CTF-BT-II-CoZyliciouS"
            }

            test("read returns the packages the demo loads, in the order it names them, with their GUIDs") {
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

            test("read returns the level package as the first package") {
                val demo = UtDemo.read(path)

                demo.packages.first().name shouldBe demo.map
            }

            test("read returns the demo time of the last record as the duration") {
                (UtDemo.read(path).duration - 78.3621.seconds).absoluteValue shouldBeLessThanOrEqualTo 1.milliseconds
            }

            test("read returns the cap that ends the run") {
                val cap = requireNotNull(UtDemo.read(path).cap)

                cap.capper shouldBe "fierd_1"
                cap.id shouldBe "2ZNKJEI2Y1K05AENS6UM827L"
                cap.runTime shouldBe 74.907.seconds
                (cap.demoTime - 77.634.seconds).absoluteValue shouldBeLessThanOrEqualTo 1.milliseconds
            }
        }

        context("control lines") {
            test("controlLines returns the lines of a control bunch between acknowledgements") {
                val bytes =
                    record(
                        1f,
                        packet {
                            ack(3)
                            bunch(0, strings(welcome, "USES PKG=Weapon"))
                            ack(4)
                        },
                    )

                UtDemoRecord.read(bytes).single().controlLines() shouldBe listOf(welcome, "USES PKG=Weapon")
            }

            test("controlLines returns the lines of a bunch that opens the channel") {
                val bytes = record(1f, packet { bunch(0, strings(welcome), open = true) })

                UtDemoRecord.read(bytes).single().controlLines() shouldBe listOf(welcome)
            }

            test("controlLines returns the lines of a reliable bunch") {
                val bytes = record(1f, packet { bunch(0, strings(welcome), reliable = true) })

                UtDemoRecord.read(bytes).single().controlLines() shouldBe listOf(welcome)
            }

            test("controlLines returns the lines of a bunch that closes the channel") {
                val bytes = record(1f, packet { bunch(0, strings(welcome), close = true) })

                UtDemoRecord.read(bytes).single().controlLines() shouldBe listOf(welcome)
            }
        }

        context("malformed demo") {
            test("read keeps the records before a record cut off at the end") {
                val bytes =
                    demo(
                        controlRecord(
                            1.5f,
                            welcome,
                            "USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=First FLAGS=1 SIZE=100 GEN=1 FNAME=First.u",
                        ),
                        controlRecord(
                            5.25f,
                            "USES GUID=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB PKG=Second FLAGS=1 SIZE=100 GEN=1 FNAME=Second.u",
                        ),
                        header(9f, 50),
                    )

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
            test("read keeps the first entry of a package named more than once, comparing names ignoring case") {
                val bytes =
                    controlRecord(
                        1f,
                        welcome,
                        "USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=Weapon FLAGS=1 SIZE=100 GEN=1 FNAME=Weapon.u",
                        "USES GUID=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB PKG=weapon FLAGS=1 SIZE=100 GEN=1 FNAME=weapon.u",
                    )

                val packages = UtDemo.read(bytes).packages

                packages shouldBe listOf(UtPackage.named("Weapon", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                packages.single().guid shouldBe "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            }

            test("read returns an empty GUID for a package the demo names no GUID for") {
                val bytes =
                    controlRecord(1f, welcome, "USES PKG=Weapon FLAGS=1 SIZE=100 GEN=1 FNAME=Weapon.u")

                val packages = UtDemo.read(bytes).packages

                packages shouldBe listOf(UtPackage.named("Weapon", ""))
                packages.single().guid shouldBe ""
            }

            test("read ignores USES lines outside the control channel") {
                val bytes =
                    record(
                        1f,
                        packet {
                            bunch(0, strings(welcome))
                            bunch(1, strings("USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=Weapon FLAGS=1 SIZE=100 GEN=1 FNAME=Weapon.u"))
                        },
                    )

                UtDemo.read(bytes).packages shouldBe emptyList()
            }
        }

        context("cap evidence") {
            test("read returns a null cap when the demo holds no cap") {
                val bytes =
                    controlRecord(
                        1f,
                        welcome,
                        "USES GUID=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA PKG=First FLAGS=1 SIZE=100 GEN=1 FNAME=First.u",
                    )

                val demo = UtDemo.read(bytes)

                demo.map shouldBe "TestMap"
                demo.packages shouldBe listOf(UtPackage.named("First", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                demo.cap shouldBe null
            }

            test("read returns the time between the markers as the run time when the demo holds no message") {
                val bytes = capDemo(spawnMarker(2.5f, "CAPID1"), capMarker(77f, "CAPID1"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns the capper and the reported run time when the demo holds no marker") {
                val bytes = capDemo(finished(77f, "kleps", "1:14.500", "1/1"), capped(77f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = null, runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns the reported run time when the demo holds a cap marker but no spawn marker") {
                val bytes =
                    capDemo(
                        capMarker(77f, "CAPID1"),
                        finished(77f, "kleps", "1:14.500", "1/1"),
                        capped(77f, "kleps"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns a null run time when the demo holds a cap marker but no spawn marker and reports no time") {
                val bytes = capDemo(capMarker(77f, "CAPID1"), capped(77f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = null, demoTime = 77.seconds)
            }

            test("read returns a null cap when the demo holds only a spawn marker") {
                UtDemo.read(capDemo(spawnMarker(2.5f, "CAPID1"))).cap shouldBe null
            }

            test("read ignores a spawn marker whose id differs from the cap marker") {
                val bytes = capDemo(spawnMarker(2.5f, "CAPID1"), capMarker(77f, "CAPID2"), capped(77f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID2", runTime = null, demoTime = 77.seconds)
            }

            test("read keeps a demo time below a millisecond") {
                val demo = UtDemo.read(capDemo(capMarker(0.0625f, "CAPID1")))

                demo.cap shouldBe UtDemoCap(capper = null, id = "CAPID1", runTime = null, demoTime = 62.5.milliseconds)
                demo.duration shouldBe 62.5.milliseconds
            }
        }

        context("cap capper") {
            test("read returns the name whose reported time is within 50 milliseconds of the time between the markers") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        finished(77f, "kleps", "1:13.000", "1/2"),
                        finished(77f, "fierd_1", "1:14.540", "2/2"),
                        capped(77f, "kleps", "fierd_1"),
                        capMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "fierd_1", id = "CAPID1", runTime = 74.54.seconds, demoTime = 77.seconds)
            }

            test("read returns a null capper and the time between the markers when no reported time is within 50 milliseconds of it") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        finished(77f, "kleps", "1:13.000", "1/2"),
                        finished(77f, "fierd_1", "1:14.560", "2/2"),
                        capped(77f, "kleps", "fierd_1"),
                        capMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns the name whose reported time is nearest the time between the markers") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        finished(77f, "kleps", "1:14.530", "1/2"),
                        finished(77f, "fierd_1", "1:14.510", "2/2"),
                        capped(77f, "kleps", "fierd_1"),
                        capMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "fierd_1", id = "CAPID1", runTime = 74.51.seconds, demoTime = 77.seconds)
            }

            test("read returns a null capper when two reported times are equally near the time between the markers") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        finished(77f, "kleps", "1:14.480", "1/2"),
                        finished(77f, "fierd_1", "1:14.520", "2/2"),
                        capped(77f, "kleps", "fierd_1"),
                        capMarker(77f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns the one name the capped line holds when the demo reports no time") {
                val bytes = capDemo(spawnMarker(2.5f, "CAPID1"), capped(77f, "kleps"), capMarker(77f, "CAPID1"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns a null capper when the capped line holds more than one name and the demo reports no time") {
                val bytes = capDemo(spawnMarker(2.5f, "CAPID1"), capped(77f, "kleps", "fierd_1"), capMarker(77f, "CAPID1"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = null, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }
        }

        context("cap selection") {
            test("read returns the first cap when the demo holds more than one") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        capped(77f, "kleps"),
                        capMarker(77f, "CAPID1"),
                        spawnMarker(78f, "CAPID2"),
                        capped(100f, "fierd_1"),
                        capMarker(100f, "CAPID2"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns the first demo time when the demo repeats the markers and messages of a cap") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        capMarker(77f, "CAPID1"),
                        capped(77f, "kleps"),
                        capMarker(80f, "CAPID1"),
                        capped(80f, "kleps"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read returns the demo time of the cap marker over a later capped line") {
                val bytes = capDemo(spawnMarker(2.5f, "CAPID1"), capMarker(77f, "CAPID1"), capped(78f, "kleps"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }
        }

        context("cap text") {
            test("read finds cap lines that begin at any bit offset of a payload") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        capped(77f, "kleps"),
                        capMarker(77f, "CAPID1"),
                        shift = 5,
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "kleps", id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read ignores a cap line whose length does not frame it") {
                val text = "kleps and fierd_1 capped! Red team scores!"
                val payload = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
                val bytes =
                    demo(
                        controlRecord(0f, welcome),
                        record(77f, packet { bunch(1, payload) }),
                    )

                UtDemo.read(bytes).cap shouldBe null
            }

            test("read ignores a length past the largest int") {
                val payload = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F) + ByteArray(30) { 0x41 }
                val bytes =
                    demo(
                        controlRecord(0f, welcome),
                        record(77f, packet { bunch(1, payload) }),
                    )

                UtDemo.read(bytes).cap shouldBe null
            }

            test("read decodes names as ISO-8859-1") {
                val name = "fiérd_1"
                val bytes = capDemo(spawnMarker(2.5f, "CAPID1"), capped(77f, name), capMarker(77f, "CAPID1"))

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = name, id = "CAPID1", runTime = 74.5.seconds, demoTime = 77.seconds)
            }

            test("read parses a reported time that holds no minutes") {
                val bytes =
                    capDemo(
                        spawnMarker(2.5f, "CAPID1"),
                        finished(57f, "kleps", "53.000", "1/2"),
                        finished(57f, "fierd_1", "54.500", "2/2"),
                        capped(57f, "kleps", "fierd_1"),
                        capMarker(57f, "CAPID1"),
                    )

                UtDemo.read(bytes).cap shouldBe
                    UtDemoCap(capper = "fierd_1", id = "CAPID1", runTime = 54.5.seconds, demoTime = 57.seconds)
            }
        }
    })
