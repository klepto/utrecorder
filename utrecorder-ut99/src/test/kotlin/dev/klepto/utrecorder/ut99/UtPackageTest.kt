package dev.klepto.utrecorder.ut99

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Tests for [UtPackage] and the packages [packagesIn] lists.
 *
 * The package files the tests read are written by [writePackage].
 */
class UtPackageTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utpackage-test")

        afterSpec { root.toFile().deleteRecursively() }

        test("named gives a package with that name and GUID, and a null path") {
            val required = UtPackage.named("Engine", GUID)

            required.name shouldBe "Engine"
            required.guid shouldBe GUID
            required.path shouldBe null
        }

        test("read gives a package named after the file, carrying the path") {
            val path = writePackage(root / "read-name" / "Engine.u")

            val held = UtPackage.read(path)

            held.name shouldBe "Engine"
            held.path shouldBe path
        }

        test("read on a package file gives the GUID its header holds") {
            val path = writePackage(root / "read-guid" / "Engine.u")

            UtPackage.read(path).guid shouldBe GUID
        }

        test("read on a file that is not a package gives an empty GUID") {
            val path = root / "read-not-a-package" / "Engine.u"
            path.createParentDirectories()
            path.writeText("not a package")

            UtPackage.read(path).guid shouldBe ""
        }

        test("read reads nothing until the GUID is asked for") {
            val path = root / "read-lazy" / "Engine.u"

            val held = UtPackage.read(path)

            held.name shouldBe "Engine"
            held.path shouldBe path
        }

        test("satisfies is true when the names differ only by case and the GUIDs differ only by case") {
            val held = UtPackage.named("engine", GUID.lowercase())

            held.satisfies(UtPackage.named("Engine", GUID)) shouldBe true
        }

        test("satisfies is false when the names differ") {
            UtPackage.named("Engine", GUID).satisfies(UtPackage.named("Core", GUID)) shouldBe false
        }

        test("satisfies is false when the names match and the GUIDs differ") {
            UtPackage.named("Engine", GUID).satisfies(UtPackage.named("Engine", OTHER_GUID)) shouldBe false
        }

        test("satisfies is true for any package of that name when the required package carries an empty GUID") {
            val path = writePackage(root / "satisfies-empty" / "Engine.u")
            val held = UtPackage.read(path)

            held.satisfies(UtPackage.named("Engine", "")) shouldBe true

            writePackage(path, OTHER_GUID_HEADER_BYTES)
            held.guid shouldBe OTHER_GUID
        }

        test("packages of the same name and path are equal and share a hash code even when their GUIDs differ") {
            val path = writePackage(root / "equals-guid" / "Engine.u")
            val first = UtPackage.read(path)
            first.guid shouldBe GUID

            writePackage(path, OTHER_GUID_HEADER_BYTES)
            val second = UtPackage.read(path)
            second.guid shouldBe OTHER_GUID

            first shouldBe second
            first.hashCode() shouldBe second.hashCode()
        }

        test("equality reads no header") {
            val path = writePackage(root / "equals-lazy" / "Engine.u")
            val first = UtPackage.read(path)
            val second = UtPackage.read(path)

            first shouldBe second
            first.hashCode() shouldBe second.hashCode()

            writePackage(path, OTHER_GUID_HEADER_BYTES)
            first.guid shouldBe OTHER_GUID
        }

        test("packages of the same name at different paths are not equal") {
            val first = UtPackage.read(writePackage(root / "equals-here" / "Engine.u"))
            val second = UtPackage.read(writePackage(root / "equals-there" / "Engine.u"))

            first shouldNotBe second
        }

        test("toString names the package, its shortened GUID and its file") {
            val path = writePackage(root / "to-string" / "Engine.u")

            UtPackage.read(path).toString() shouldBe "Engine[12345678] at $path"
            UtPackage.named("Engine", GUID).toString() shouldBe "Engine[12345678]"
        }

        test("packagesIn with no extension lists every package extension of one directory and skips everything else") {
            val directory = root / "packages-in"
            val packageNames = listOf("Engine.u", "CTF-BT-II.unr", "Skins.utx", "Ambient.uax", "Track.umx")
            packageNames.forEach { writePackage(directory / it) }
            (directory / "Notes.txt").writeText("not a package")
            (directory / "Engine.uz").writeText("a compressed package is not one")
            (directory / "Cache" / "Engine.u").let { writePackage(it) }

            val found = packagesIn(directory)

            found.map { it.path?.fileName.toString() }.toSet() shouldBe packageNames.toSet()
        }
    })
