package dev.klepto.utrecorder.common.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ArchivesTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-archives-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun tarBzip2(name: String): Path {
            val archive = root / name
            val stream = BZip2CompressorOutputStream(archive.outputStream())
            TarArchiveOutputStream(stream).use { tar ->
                val content = "linked".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("lib/source.txt").apply { size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
                tar.putArchiveEntry(
                    TarArchiveEntry("bin/link.txt", TarArchiveEntry.LF_LINK).apply { linkName = "lib/source.txt" },
                )
                tar.closeArchiveEntry()
            }
            return archive
        }

        context("extract") {
            test("extracts a tar hard link as a copy of the file it links to") {
                val target = root / "copied"

                tarBzip2("copied.tar.bz2").extract(target)

                (target / "bin" / "link.txt").readText() shouldBe "linked"
            }

            test("fails with a clear message when the filter rejects the file a tar hard link links to") {
                val target = root / "filtered"

                val exception =
                    shouldThrow<IllegalStateException> {
                        tarBzip2("filtered.tar.bz2").extract(target) { it.startsWith("bin/") }
                    }

                exception.message shouldContain "lib/source.txt"
            }
        }

        context("listFiles") {
            fun zip(name: String): Path {
                val archive = root / name
                ZipArchiveOutputStream(archive.outputStream()).use { zip ->
                    zip.putArchiveEntry(ZipArchiveEntry("System/"))
                    zip.closeArchiveEntry()
                    listOf("System/Engine.u", "Maps/Face.unr", "ReleaseNotes.md").forEach { entry ->
                        zip.putArchiveEntry(ZipArchiveEntry(entry))
                        zip.write(entry.toByteArray())
                        zip.closeArchiveEntry()
                    }
                }
                return archive
            }

            test("lists the file entries of a zip archive without its directory entries") {
                zip("listed.zip").listFiles() shouldBe setOf("System/Engine.u", "Maps/Face.unr", "ReleaseNotes.md")
            }

            test("lists only the entries the filter accepts") {
                zip("filtered.zip").listFiles { it.startsWith("System/") } shouldBe setOf("System/Engine.u")
            }

            test("lists the links of a tar archive as files") {
                tarBzip2("linked.tar.bz2").listFiles() shouldBe setOf("lib/source.txt", "bin/link.txt")
            }

            test("extracts nothing") {
                val archive = zip("untouched.zip")

                archive.listFiles()

                archive.parent.listDirectoryEntries("*").map { it.fileName.toString() } shouldNotContain "System"
            }

            test("fails on an unsupported archive format") {
                val archive = (root / "archive.rar").apply { writeText("") }

                shouldThrow<IllegalStateException> { archive.listFiles() }
            }
        }
    })
