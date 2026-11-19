package dev.klepto.utrecorder.ut99

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class UtPackageTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utpackage-test")

        afterSpec { root.toFile().deleteRecursively() }

        val guid = "123456789ABCDEF0876543210BADF00D"
        val otherGuid = "11111111222222223333333344444444"
        val guidHeaderBytes = listOf(0x78, 0x56, 0x34, 0x12, 0xF0, 0xDE, 0xBC, 0x9A, 0x21, 0x43, 0x65, 0x87, 0x0D, 0xF0, 0xAD, 0x0B)
        val otherGuidHeaderBytes = listOf(0x11, 0x11, 0x11, 0x11, 0x22, 0x22, 0x22, 0x22, 0x33, 0x33, 0x33, 0x33, 0x44, 0x44, 0x44, 0x44)

        fun writePackage(
            path: Path,
            guidBytes: List<Int> = guidHeaderBytes,
        ): Path {
            val tagAndVersion = listOf(0xC1, 0x83, 0x2A, 0x9E, 0x44, 0x00, 0x00, 0x00)
            val header = ByteArray(64)
            tagAndVersion.forEachIndexed { index, value -> header[index] = value.toByte() }
            guidBytes.forEachIndexed { index, value -> header[36 + index] = value.toByte() }
            return path.createParentDirectories().also { it.writeBytes(header) }
        }

        fun writeGenerations(
            path: Path,
            exportCount: Int,
            nameCount: Int,
            generations: List<Pair<Int, Int>>,
            version: Short = 69,
        ): Path {
            val bytes = ByteBuffer.allocate(56 + generations.size * 8 + 16).order(ByteOrder.LITTLE_ENDIAN)
            bytes
                .putInt(0x9E2A83C1.toInt())
                .putShort(version)
                .putShort(0)
                .putInt(1)
            bytes
                .putInt(12, nameCount)
                .putInt(16, 0x100)
                .putInt(20, exportCount)
                .putInt(24, 0x200)
            bytes.putInt(28, 7).putInt(32, 0x300)
            guidHeaderBytes.forEachIndexed { index, value -> bytes.put(36 + index, value.toByte()) }
            bytes.putInt(52, generations.size)
            generations.forEachIndexed { index, (exports, names) -> bytes.putInt(56 + index * 8, exports).putInt(60 + index * 8, names) }
            (0 until 16).forEach { bytes.put(56 + generations.size * 8 + it, (0xA0 + it).toByte()) }
            return path.createParentDirectories().also { it.writeBytes(bytes.array()) }
        }

        fun generationsOf(path: Path): List<Pair<Int, Int>> {
            val bytes = ByteBuffer.wrap(path.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until bytes.getInt(52)).map { bytes.getInt(56 + it * 8) to bytes.getInt(60 + it * 8) }
        }

        suspend fun repairedReadOnly(path: Path): Boolean {
            val file = path.toFile()
            file.setWritable(false)
            try {
                return UtPackage.read(path).repairGenerations()
            } finally {
                file.setWritable(true)
            }
        }

        test("named returns a package with that name and GUID, and a null path") {
            val required = UtPackage.named("Engine", guid)

            required.name shouldBe "Engine"
            required.guid() shouldBe guid
            required.path shouldBe null
        }

        test("read returns a package named after the file, with the file as its path") {
            val path = writePackage(root / "read-name" / "Engine.u")

            val held = UtPackage.read(path)

            held.name shouldBe "Engine"
            held.path shouldBe path
        }

        test("read returns a package carrying the GUID its header holds") {
            val path = writePackage(root / "read-guid" / "Engine.u")

            UtPackage.read(path).guid() shouldBe guid
        }

        test("read returns the last GUID of the heritage table for a version before 68, even past the first 1024 bytes") {
            val path = (root / "read-heritage" / "Engine.u").createParentDirectories()
            val bytes = ByteArray(2080)
            val header = listOf(0xC1, 0x83, 0x2A, 0x9E, 0x3D, 0x00, 0x00, 0x00)
            header.forEachIndexed { index, value -> bytes[index] = value.toByte() }
            listOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00).forEachIndexed { index, value -> bytes[36 + index] = value.toByte() }
            otherGuidHeaderBytes.forEachIndexed { index, value -> bytes[2048 + index] = value.toByte() }
            guidHeaderBytes.forEachIndexed { index, value -> bytes[2064 + index] = value.toByte() }
            path.writeBytes(bytes)

            UtPackage.read(path).guid() shouldBe guid
        }

        test("read returns an empty GUID for a package whose heritage table lies past the end of the file") {
            val path = (root / "read-heritage-truncated" / "Engine.u").createParentDirectories()
            val bytes = ByteArray(2056)
            val header = listOf(0xC1, 0x83, 0x2A, 0x9E, 0x3D, 0x00, 0x00, 0x00)
            header.forEachIndexed { index, value -> bytes[index] = value.toByte() }
            listOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00).forEachIndexed { index, value -> bytes[36 + index] = value.toByte() }
            path.writeBytes(bytes)

            UtPackage.read(path).guid() shouldBe ""
        }

        test("read returns an empty GUID for a file that is not a package") {
            val path = root / "read-not-a-package" / "Engine.u"
            path.createParentDirectories()
            path.writeText("not a package")

            UtPackage.read(path).guid() shouldBe ""
        }

        test("read does not read the file until guid is called") {
            val path = root / "read-lazy" / "Engine.u"

            val held = UtPackage.read(path)

            held.name shouldBe "Engine"
            held.path shouldBe path
        }

        test("guid of a package whose file was deleted after it was read throws IOException") {
            val path = writePackage(root / "read-deleted" / "Engine.u")
            val held = UtPackage.read(path)
            path.deleteExisting()

            shouldThrow<IOException> { held.guid() }
        }

        test("satisfies is true when the names and the GUIDs are equal ignoring case") {
            val held = UtPackage.named("engine", guid.lowercase())

            held.satisfies(UtPackage.named("Engine", guid)) shouldBe true
        }

        test("satisfies is false when the names differ") {
            UtPackage.named("Engine", guid).satisfies(UtPackage.named("Core", guid)) shouldBe false
        }

        test("satisfies is false when the names are equal and the GUIDs differ") {
            UtPackage.named("Engine", guid).satisfies(UtPackage.named("Engine", otherGuid)) shouldBe false
        }

        test("satisfies is true for any package of that name, without reading its header, when the required package carries no GUID") {
            val path = writePackage(root / "satisfies-empty" / "Engine.u")
            val held = UtPackage.read(path)

            held.satisfies(UtPackage.named("Engine", "")) shouldBe true

            writePackage(path, otherGuidHeaderBytes)
            held.guid() shouldBe otherGuid
        }

        test("packages of the same name and path are equal and share a hash code even when their GUIDs differ") {
            val path = writePackage(root / "equals-guid" / "Engine.u")
            val first = UtPackage.read(path)
            first.guid() shouldBe guid

            writePackage(path, otherGuidHeaderBytes)
            val second = UtPackage.read(path)
            second.guid() shouldBe otherGuid

            first shouldBe second
            first.hashCode() shouldBe second.hashCode()
        }

        test("equals and hashCode do not read the header") {
            val path = writePackage(root / "equals-lazy" / "Engine.u")
            val first = UtPackage.read(path)
            val second = UtPackage.read(path)

            first shouldBe second
            first.hashCode() shouldBe second.hashCode()

            writePackage(path, otherGuidHeaderBytes)
            first.guid() shouldBe otherGuid
        }

        test("packages of the same name at different paths are not equal") {
            val first = UtPackage.read(writePackage(root / "equals-here" / "Engine.u"))
            val second = UtPackage.read(writePackage(root / "equals-there" / "Engine.u"))

            first shouldNotBe second
        }

        test("toString names a held package by its file, and a named package by its shortened GUID") {
            val path = writePackage(root / "to-string" / "Engine.u")

            UtPackage.read(path).toString() shouldBe "Engine at $path"
            UtPackage.named("Engine", guid).toString() shouldBe "Engine[12345678]"
        }

        test("repairGenerations rewrites a stale last generation to the header counts, and leaves every other byte") {
            val path = writeGenerations(root / "repair-stale" / "Snowlvl.utx", exportCount = 5, nameCount = 23, listOf(1 to 1, 1 to 1))
            val before = path.readBytes()

            UtPackage.read(path).repairGenerations() shouldBe true

            generationsOf(path) shouldBe listOf(1 to 1, 5 to 23)
            val after = path.readBytes()
            after.size shouldBe before.size
            after.indices.filter { after[it] != before[it] }.all { it in 64 until 72 } shouldBe true
            UtPackage.read(path).guid() shouldBe guid
        }

        test("repairGenerations rewrites the only generation of a package that holds one") {
            val path = writeGenerations(root / "repair-only" / "Snowlvl.utx", exportCount = 5, nameCount = 23, listOf(1 to 1))

            UtPackage.read(path).repairGenerations() shouldBe true

            generationsOf(path) shouldBe listOf(5 to 23)
            UtPackage.read(path).guid() shouldBe guid
        }

        test("repairGenerations rewrites a last generation whose export count matches and whose name count does not") {
            val path = writeGenerations(root / "repair-names" / "Snowlvl.utx", exportCount = 5, nameCount = 23, listOf(5 to 22))

            UtPackage.read(path).repairGenerations() shouldBe true

            generationsOf(path) shouldBe listOf(5 to 23)
        }

        test("repairGenerations does not write to a package whose last generation already holds the header counts") {
            val path = writeGenerations(root / "repair-correct" / "Engine.u", exportCount = 5, nameCount = 23, listOf(1 to 1, 5 to 23))
            val before = path.readBytes()

            repairedReadOnly(path) shouldBe false

            path.readBytes() shouldBe before
        }

        test("repairGenerations does not write to a file that is not a package") {
            val path = (root / "repair-not-a-package" / "Engine.u").createParentDirectories()
            path.writeText("not a package, but long enough to hold a whole package header and a generation")
            val before = path.readBytes()

            repairedReadOnly(path) shouldBe false

            path.readBytes() shouldBe before
        }

        test("repairGenerations does not write to a package version before 68, which holds no generations") {
            val path =
                writeGenerations(root / "repair-old-version" / "Engine.u", exportCount = 5, nameCount = 23, listOf(1 to 1), version = 61)
            val before = path.readBytes()

            repairedReadOnly(path) shouldBe false

            path.readBytes() shouldBe before
        }

        test("repairGenerations does not write to a package that holds no generation") {
            val path = writeGenerations(root / "repair-no-generation" / "Engine.u", exportCount = 5, nameCount = 23, emptyList())
            val before = path.readBytes()

            repairedReadOnly(path) shouldBe false

            path.readBytes() shouldBe before
        }

        test("repairGenerations does not write to a package whose last generation lies past the end of the file") {
            val path = (root / "repair-truncated" / "Engine.u").createParentDirectories()
            val bytes = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
            bytes
                .putInt(0x9E2A83C1.toInt())
                .putShort(69)
                .putInt(12, 23)
                .putInt(20, 5)
                .putInt(52, 3)
            path.writeBytes(bytes.array())
            val before = path.readBytes()

            repairedReadOnly(path) shouldBe false

            path.readBytes() shouldBe before
        }

        test("repairGenerations returns false for a package a demo names") {
            UtPackage.named("Snowlvl", guid).repairGenerations() shouldBe false
        }

        test("repairGenerations of a package whose file was deleted after it was read throws IOException") {
            val path = writeGenerations(root / "repair-deleted" / "Snowlvl.utx", exportCount = 5, nameCount = 23, listOf(1 to 1))
            val held = UtPackage.read(path)
            path.deleteExisting()

            shouldThrow<IOException> { held.repairGenerations() }
        }

        test("list returns the regular files held directly in the directory whose extension is one of the extensions, ignoring case") {
            val directory = root / "list"
            directory.createDirectories()
            listOf("Engine.u", "BTPog.U", "CTF-BT-II.unr", "Skins.utx", "Notes.txt").forEach { (directory / it).writeText("") }
            (directory / "Cache.u").createDirectories()
            (directory / "Cache.u" / "Nested.u").writeText("")

            val found = UtPackage.list(directory, setOf("u", "unr"))

            found.map { "${it.path?.fileName}" }.toSet() shouldBe setOf("Engine.u", "BTPog.U", "CTF-BT-II.unr")
        }

        test("list returns an empty list when the directory does not exist") {
            UtPackage.list(root / "list-missing", setOf("u")) shouldBe emptyList()
        }
    })
