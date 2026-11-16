package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.security.NoSuchAlgorithmException
import kotlin.concurrent.thread
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

class PathsTest :
    FunSpec({
        val sample = "utrecorder-22"

        val windowsOnly = { _: TestCase -> OperatingSystem.current() == WINDOWS }

        val root = createTempDirectory("utrecorder-paths-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun file(
            name: String,
            content: String = "",
        ) = (root / name).apply { writeText(content) }

        context("toAbsolutePath") {
            test("resolves a relative path against the user.dir working directory") {
                "utrecorder-relative-probe".toAbsolutePath() shouldBe
                    Paths.get(System.getProperty("user.dir"), "utrecorder-relative-probe")
            }

            test("normalizes redundant path segments") {
                "$root/nested/../probe".toAbsolutePath() shouldBe (root / "probe")
            }
        }

        context("isPlainFileName") {
            test("accepts a file name and rejects a blank name, a dot segment, a separator, a drive and a null") {
                val rejected = listOf("", " ", ".", "..", "../Escape.unr", "Maps\\Escape.unr", "C:Escape.unr", "Escape\u0000.unr")

                "DM-Deck16][.unr".isPlainFileName() shouldBe true
                rejected.filter(String::isPlainFileName) shouldBe emptyList()
            }
        }

        context("copy") {
            test("copies the content and keeps the source") {
                val source = file("copy-source", sample)
                val target = root / "copy-target"

                source.copy(target)

                source.readText() shouldBe sample
                target.readText() shouldBe sample
            }

            test("throws with the default message when the source holds nothing") {
                val exception =
                    shouldThrow<IllegalStateException> {
                        (root / "copy-absent").copy(root / "copy-absent-target")
                    }

                exception.message shouldBe "No file to copy at ${root / "copy-absent"}"
            }

            test("throws with the message of failureMessage when the source holds nothing") {
                val exception =
                    shouldThrow<IllegalStateException> {
                        (root / "copy-absent-custom").copy(root / "copy-absent-custom-target") { "custom failure" }
                    }

                exception.message shouldBe "custom failure"
            }

            test("creates the missing parent directories of the target and returns the target") {
                val target = root / "copy-missing-parent" / "nested" / "target"

                val copied = file("copy-parent-source", sample).copy(target)

                copied shouldBe target
                target.readText() shouldBe sample
            }

            test("replaces the content of an existing target when overwrite is true") {
                val target = file("copy-overwrite-target", "old")

                file("copy-overwrite-source", "new").copy(target, overwrite = true)

                target.readText() shouldBe "new"
            }

            test("throws and leaves an existing target unchanged when overwrite is false") {
                val target = file("copy-kept-target", "old")

                shouldThrow<FileAlreadyExistsException> { file("copy-kept-source", "new").copy(target, overwrite = false) }

                target.readText() shouldBe "old"
            }
        }

        context("link") {
            test("links the target to the file of the source, creating its parent directories and returning it") {
                val source = file("link-source", sample)
                val target = root / "link-missing-parent" / "target"

                val linked = source.link(target)

                linked shouldBe target
                Files.isSameFile(source, target) shouldBe true
                source.writeText("changed")
                target.readText() shouldBe "changed"
            }

            test("throws with the message of failureMessage when the source holds nothing") {
                val exception =
                    shouldThrow<IllegalStateException> {
                        (root / "link-absent").link(root / "link-absent-target") { "custom failure" }
                    }

                exception.message shouldBe "custom failure"
            }
        }

        context("move") {
            test("moves the content and removes the source") {
                val source = file("move-source", sample)
                val target = root / "move-target"

                source.move(target)

                source.shouldNotExist()
                target.readText() shouldBe sample
            }

            test("replaces the file already at the target") {
                val source = file("replace-source", "new")
                val target = file("replace-target", "old")

                source.move(target)

                source.shouldNotExist()
                target.readText() shouldBe "new"
            }

            test("keeps binary content byte for byte") {
                val bytes = Random(469).nextBytes(64 * 1024)
                val source = (root / "binary-source").apply { writeBytes(bytes) }
                val target = root / "binary-target"

                source.move(target)

                target.readBytes() shouldBe bytes
            }

            test("creates the missing parent directories of the target") {
                val target = root / "missing-dir" / "target"

                file("orphan-source", sample).move(target)

                target.readText() shouldBe sample
            }

            test("moves a directory with its children") {
                val source = (root / "tree-source").createDirectories()
                (source / "child").writeText(sample)
                val target = root / "tree-target"

                source.move(target)

                source.shouldNotExist()
                target.listDirectoryEntries().map { it.fileName.toString() } shouldBe listOf("child")
                (target / "child").readText() shouldBe sample
            }

            test("merges a directory into an existing target, overwriting shared files and keeping the rest") {
                val source = (root / "merge-source").createDirectories()
                (source / "shared").writeText("new")

                val target = (root / "merge-target").createDirectories()
                (target / "shared").writeText("old")
                (target / "kept").writeText("kept")

                source.move(target)

                source.shouldNotExist()
                (target / "shared").readText() shouldBe "new"
                (target / "kept").readText() shouldBe "kept"
            }

            test("throws when the source is missing") {
                shouldThrow<NoSuchFileException> {
                    (root / "ghost").move(root / "ghost-target")
                }
            }
        }

        context("replaceIfChanged") {
            val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root / "replace-home")

            test("writes a missing file and leaves no staged file beside it") {
                val directory = (root / "replace-missing").createDirectories()

                (directory / "UTRecorder.u").replaceIfChanged(sample.toByteArray(), directories)

                (directory / "UTRecorder.u").readText() shouldBe sample
                directory.listDirectoryEntries().map { it.fileName.toString() } shouldBe listOf("UTRecorder.u")
            }

            test("replaces a file that holds other bytes") {
                val directory = (root / "replace-changed").createDirectories()
                (directory / "UTRecorder.u").writeText("stale")

                (directory / "UTRecorder.u").replaceIfChanged(sample.toByteArray(), directories)

                (directory / "UTRecorder.u").readText() shouldBe sample
            }

            test("leaves a file that already holds the bytes untouched") {
                val directory = (root / "replace-unchanged").createDirectories()
                val target = (directory / "UTRecorder.u").apply { writeText(sample) }
                target.setLastModifiedTime(FileTime.fromMillis(0))

                target.replaceIfChanged(sample.toByteArray(), directories)

                target.getLastModifiedTime() shouldBe FileTime.fromMillis(0)
            }
        }

        context("readLinesOrEmpty") {
            test("returns the lines of an existing file") {
                file("read-lines", "first\nsecond").readLinesOrEmpty() shouldBe listOf("first", "second")
            }

            test("returns an empty list for a missing file") {
                (root / "read-lines-absent").readLinesOrEmpty() shouldBe emptyList()
            }

            test("decodes with the given charset") {
                val path = (root / "read-lines-charset").apply { writeText("café", Charsets.ISO_8859_1) }

                path.readLinesOrEmpty(Charsets.ISO_8859_1) shouldBe listOf("café")
            }
        }

        context("readFilteredLinesOrEmpty") {
            test("returns only the lines the filter accepts, in file order") {
                val path = file("filtered-lines", "keep first\ndrop\nkeep second\ndrop again\nkeep third")

                path.readFilteredLinesOrEmpty { it.startsWith("keep") } shouldBe listOf("keep first", "keep second", "keep third")
            }

            test("returns an empty list for a missing file") {
                (root / "filtered-lines-absent").readFilteredLinesOrEmpty { true } shouldBe emptyList()
            }

            test("ends a line at a line feed, a carriage return or both, as readLinesOrEmpty does") {
                val path = file("filtered-lines-terminators", "a\r\nb\rc\n\r\rd\r\n")

                path.readFilteredLinesOrEmpty { true } shouldBe listOf("a", "b", "c", "", "", "d")
            }

            test("returns the last line when no terminator ends it, and no empty line after a final terminator") {
                file("filtered-lines-open", "first\nlast").readFilteredLinesOrEmpty { true } shouldBe listOf("first", "last")
                file("filtered-lines-closed", "first\nlast\n").readFilteredLinesOrEmpty { true } shouldBe listOf("first", "last")
                file("filtered-lines-empty", "").readFilteredLinesOrEmpty { true } shouldBe emptyList()
            }

            test("cuts a line longer than the maximum line length before the filter sees it") {
                val path = file("filtered-lines-long", "a".repeat(100) + "\nshort")
                val seen = mutableListOf<String>()

                val lines =
                    path.readFilteredLinesOrEmpty(maxLineLength = 10) { line ->
                        seen += line
                        true
                    }

                lines shouldBe listOf("aaaaaaaaaa", "short")
                seen shouldBe listOf("aaaaaaaaaa", "short")
            }

            test("cuts a line at 65536 characters when no maximum line length is given") {
                val path = file("filtered-lines-default-cut", "b".repeat(100_000))

                path.readFilteredLinesOrEmpty { true }.map(String::length) shouldBe listOf(65536)
            }

            test("decodes with the given charset") {
                val path = (root / "filtered-lines-charset").apply { writeText("café\ncaf", Charsets.ISO_8859_1) }

                path.readFilteredLinesOrEmpty(Charsets.ISO_8859_1) { it.endsWith("é") } shouldBe listOf("café")
            }

            test("keeps only the accepted lines of a log of hundreds of megabytes") {
                val path = root / "filtered-lines-large.log"
                path.bufferedWriter(Charsets.ISO_8859_1).use { writer ->
                    repeat(2_000_000) { index ->
                        writer.write("ScriptLog: junk line $index of a runaway playback, padded to fill the log quickly\n")
                        if (index % 500_000 == 0) writer.write("ScriptLog: UTRecorder: mark $index\n")
                    }
                }

                val lines = path.readFilteredLinesOrEmpty(Charsets.ISO_8859_1) { "UTRecorder: mark " in it }

                path.deleteExisting()
                lines shouldBe
                    listOf(
                        "ScriptLog: UTRecorder: mark 0",
                        "ScriptLog: UTRecorder: mark 500000",
                        "ScriptLog: UTRecorder: mark 1000000",
                        "ScriptLog: UTRecorder: mark 1500000",
                    )
            }

            test("throws when the maximum line length is not positive") {
                shouldThrow<IllegalArgumentException> {
                    file("filtered-lines-zero", "line").readFilteredLinesOrEmpty(maxLineLength = 0) { true }
                }
            }
        }

        context("readLastLinesOrEmpty") {
            test("returns the last lines of the file, in file order") {
                file("last-lines", "first\nsecond\nthird\nfourth").readLastLinesOrEmpty(2) shouldBe listOf("third", "fourth")
            }

            test("returns every line of a file that holds fewer lines than asked for") {
                file("last-lines-short", "first\nsecond\n").readLastLinesOrEmpty(5) shouldBe listOf("first", "second")
            }

            test("returns an empty list for a missing file") {
                (root / "last-lines-absent").readLastLinesOrEmpty(5) shouldBe emptyList()
            }

            test("ends a line at a line feed, a carriage return or both, as readLinesOrEmpty does") {
                val path = file("last-lines-terminators", "a\r\nb\rc\n\r\rd\r\n")

                path.readLastLinesOrEmpty(10) shouldBe listOf("a", "b", "c", "", "", "d")
            }

            test("reads only the last bytes, and leaves out the line the cut falls inside but keeps one that starts at the cut") {
                val path = file("last-lines-cut", "first\nsecond\nthird\n")

                path.readLastLinesOrEmpty(10, maxBytes = 19) shouldBe listOf("first", "second", "third")
                path.readLastLinesOrEmpty(10, maxBytes = 18) shouldBe listOf("second", "third")
                path.readLastLinesOrEmpty(10, maxBytes = 13) shouldBe listOf("second", "third")
                path.readLastLinesOrEmpty(10, maxBytes = 12) shouldBe listOf("third")
            }

            test("returns no line when the last line alone is longer than the bytes it reads") {
                file("last-lines-long", "short\n" + "a".repeat(100)).readLastLinesOrEmpty(10, maxBytes = 50) shouldBe emptyList()
            }

            test("decodes with the given charset") {
                val path = (root / "last-lines-charset").apply { writeText("caf\ncafé", Charsets.ISO_8859_1) }

                path.readLastLinesOrEmpty(1, Charsets.ISO_8859_1) shouldBe listOf("café")
            }

            test("throws when the line count or the maximum byte count is not positive") {
                val path = file("last-lines-zero", "line")

                shouldThrow<IllegalArgumentException> { path.readLastLinesOrEmpty(0) }
                shouldThrow<IllegalArgumentException> { path.readLastLinesOrEmpty(1, maxBytes = 0) }
            }
        }

        context("readTextOrEmpty") {
            test("returns the contents of an existing file") {
                file("read-text", sample).readTextOrEmpty() shouldBe sample
            }

            test("returns an empty string for a missing file") {
                (root / "read-text-absent").readTextOrEmpty() shouldBe ""
            }

            test("decodes with the given charset") {
                val path = (root / "read-text-charset").apply { writeText("café", Charsets.ISO_8859_1) }

                path.readTextOrEmpty(Charsets.ISO_8859_1) shouldBe "café"
            }
        }

        context("fileExists") {
            test("returns true for an existing file") {
                file("exists", sample).fileExists() shouldBe true
            }

            test("returns false for a missing file") {
                (root / "exists-absent").fileExists() shouldBe false
            }
        }

        context("digest") {
            test("returns the reference MD5 and SHA-256 of an empty file") {
                val empty = file("empty")

                empty.digest("MD5") shouldBe "d41d8cd98f00b204e9800998ecf8427e"
                empty.digest("SHA-256") shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            }

            test("returns the reference MD5 and SHA-256 of a known input as lowercase hexadecimal, zero-padded per byte") {
                val known = file("known", sample)

                known.digest("MD5") shouldBe "00ad189b536dc412168fc866a7ec13b0"
                known.digest("SHA-256") shouldBe "0323ec0736b6000fffd1a01bc5a70c1e35dc998f1214edde7a668879b54b9562"
            }

            test("reads the whole file when it exceeds the copy buffer") {
                val bytes = Random(22).nextBytes(3 * 1024 * 1024)

                (root / "large").apply { writeBytes(bytes) }.digest("SHA-256") shouldBe
                    "2525586c2e19b526b0c3159d2321ef4242f41515e02654c3afd257a3a10e6ec1"
            }

            test("closes the file so it can be deleted afterwards") {
                val path = file("closed", sample)

                path.digest("SHA-256")

                path.deleteExisting()
                path.shouldNotExist()
            }

            test("throws on an unknown algorithm") {
                shouldThrow<NoSuchAlgorithmException> { file("unknown-algo").digest("SHA-999") }
            }

            test("throws on a missing file") {
                shouldThrow<NoSuchFileException> { (root / "absent").digest("SHA-256") }
            }

            test("throws on a directory") {
                val directory = (root / "a-directory").createDirectories()

                shouldThrow<IOException> { directory.digest("SHA-256") }
            }
        }

        context("deleteCatching") {
            test("removes a populated directory tree") {
                val directory = (root / "delete-tree").createDirectories()
                (directory / "child").writeText(sample)

                directory.deleteCatching()

                directory.shouldNotExist()
            }

            test("succeeds at once for a missing path, without waiting out the delay") {
                val missing = root / "delete-absent"
                val (outcome, elapsed) = measureTimedValue { missing.deleteCatching(attempts = 3, delay = 5.seconds) }

                outcome.shouldBeSuccess()
                elapsed.inWholeMilliseconds shouldBeLessThan 5000L
            }

            test("throws when attempts is below 1") {
                shouldThrow<IllegalArgumentException> {
                    (root / "delete-zero-attempts").deleteCatching(attempts = 0)
                }
            }

            test("removes a file whose stream another thread closes during the retries")
                .config(enabledIf = windowsOnly) {
                    val path = file("delete-released", sample)
                    val stream = FileOutputStream(path.toFile(), true)
                    val closer =
                        thread {
                            Thread.sleep(300)
                            stream.close()
                        }

                    val outcome = path.deleteCatching(attempts = 50, delay = 100.milliseconds)
                    closer.join()

                    outcome.shouldBeSuccess()
                    path.shouldNotExist()
                }

            test("leaves a file held open through every attempt in place and returns the failure of the last attempt")
                .config(enabledIf = windowsOnly) {
                    val path = file("delete-held", sample)
                    val stream = FileOutputStream(path.toFile(), true)

                    val (outcome, elapsed) =
                        try {
                            measureTimedValue { path.deleteCatching(attempts = 3, delay = 200.milliseconds) }
                        } finally {
                            stream.close()
                        }

                    outcome.exceptionOrNull().shouldBeInstanceOf<IOException>()
                    elapsed.inWholeMilliseconds shouldBeGreaterThanOrEqual 400L
                    path.shouldExist()
                }

            test("stops waiting between attempts when cancelled")
                .config(enabledIf = windowsOnly) {
                    val path = file("delete-cancelled", sample)
                    val stream = FileOutputStream(path.toFile(), true)

                    val elapsed =
                        try {
                            measureTime {
                                coroutineScope {
                                    val deletion = launch { path.deleteCatching(attempts = 3, delay = 5.seconds) }
                                    delay(200.milliseconds)
                                    deletion.cancelAndJoin()
                                }
                            }
                        } finally {
                            stream.close()
                        }

                    elapsed.inWholeMilliseconds shouldBeLessThan 5000L
                    path.shouldExist()
                }
        }
    })
