package dev.klepto.utrecorder.common.io

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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.security.NoSuchAlgorithmException
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
        }
    })
