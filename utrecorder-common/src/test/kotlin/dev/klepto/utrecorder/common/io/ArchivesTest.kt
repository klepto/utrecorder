package dev.klepto.utrecorder.common.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.io.path.readText

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
    })
