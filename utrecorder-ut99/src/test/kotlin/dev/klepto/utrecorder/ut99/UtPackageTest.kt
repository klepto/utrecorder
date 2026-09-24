package dev.klepto.utrecorder.ut99

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
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

        test("named returns a package with that name and GUID, and a null path") {
            val required = UtPackage.named("Engine", guid)

            required.name shouldBe "Engine"
            required.guid shouldBe guid
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

            UtPackage.read(path).guid shouldBe guid
        }

        test("read returns an empty GUID for a file that is not a package") {
            val path = root / "read-not-a-package" / "Engine.u"
            path.createParentDirectories()
            path.writeText("not a package")

            UtPackage.read(path).guid shouldBe ""
        }

        test("read does not read the file until guid is accessed") {
            val path = root / "read-lazy" / "Engine.u"

            val held = UtPackage.read(path)

            held.name shouldBe "Engine"
            held.path shouldBe path
        }

        test("guid of a package whose file was deleted after it was read throws IOException") {
            val path = writePackage(root / "read-deleted" / "Engine.u")
            val held = UtPackage.read(path)
            path.deleteExisting()

            shouldThrow<IOException> { held.guid }
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
            held.guid shouldBe otherGuid
        }

        test("packages of the same name and path are equal and share a hash code even when their GUIDs differ") {
            val path = writePackage(root / "equals-guid" / "Engine.u")
            val first = UtPackage.read(path)
            first.guid shouldBe guid

            writePackage(path, otherGuidHeaderBytes)
            val second = UtPackage.read(path)
            second.guid shouldBe otherGuid

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
            first.guid shouldBe otherGuid
        }

        test("packages of the same name at different paths are not equal") {
            val first = UtPackage.read(writePackage(root / "equals-here" / "Engine.u"))
            val second = UtPackage.read(writePackage(root / "equals-there" / "Engine.u"))

            first shouldNotBe second
        }

        test("toString names the package, its shortened GUID and its file") {
            val path = writePackage(root / "to-string" / "Engine.u")

            UtPackage.read(path).toString() shouldBe "Engine[12345678] at $path"
            UtPackage.named("Engine", guid).toString() shouldBe "Engine[12345678]"
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
