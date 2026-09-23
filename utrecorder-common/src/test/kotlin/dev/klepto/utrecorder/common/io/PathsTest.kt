package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
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
        val sampleMd5 = "00ad189b536dc412168fc866a7ec13b0"
        val sampleSha256 = "0323ec0736b6000fffd1a01bc5a70c1e35dc998f1214edde7a668879b54b9562"
        val emptyMd5 = "d41d8cd98f00b204e9800998ecf8427e"
        val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val windowsOnly = { _: TestCase -> OperatingSystem.current() == OperatingSystem.WINDOWS }

        val root = createTempDirectory("utrecorder-paths-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun file(
            name: String,
            content: String = "",
        ) = (root / name).apply { writeText(content) }

        context("digest") {
            test("matches the reference MD5 of an empty file") {
                file("empty-md5").digest("MD5") shouldBe emptyMd5
            }

            test("matches the reference SHA-256 of an empty file") {
                file("empty-sha").digest("SHA-256") shouldBe emptySha256
            }

            test("matches the reference MD5 of a known input") {
                file("sample-md5", sample).digest("MD5") shouldBe sampleMd5
            }

            test("matches the reference SHA-256 of a known input") {
                file("sample-sha", sample).digest("SHA-256") shouldBe sampleSha256
            }

            test("pads bytes below 0x10 to two hex digits") {
                val md5 = file("padding", sample).digest("MD5")

                md5.length shouldBe 32
                md5.take(2) shouldBe "00"
            }

            test("emits lowercase hex only") {
                file("lowercase", sample).digest("SHA-256") shouldMatch Regex("[0-9a-f]{64}")
            }

            test("reads the whole file when it exceeds the copy buffer") {
                val bytes = Random(22).nextBytes(3 * 1024 * 1024)

                (root / "large").apply { writeBytes(bytes) }.digest("SHA-256") shouldBe
                    "2525586c2e19b526b0c3159d2321ef4242f41515e02654c3afd257a3a10e6ec1"
            }

            test("gives different digests to different content") {
                file("differs-a", sample).digest("SHA-256") shouldNotBe
                    file("differs-b", "$sample!").digest("SHA-256")
            }

            test("closes the file so it can be deleted afterwards") {
                val path = file("closed", sample)

                path.digest("SHA-256")

                path.deleteExisting()
                path.shouldNotExist()
            }

            test("rejects an unknown algorithm") {
                shouldThrow<NoSuchAlgorithmException> { file("unknown-algo").digest("SHA-999") }
            }

            test("fails on a missing file") {
                shouldThrow<NoSuchFileException> { (root / "absent").digest("SHA-256") }
            }

            test("fails on a directory") {
                val directory = (root / "a-directory").apply { createDirectories() }

                shouldThrow<IOException> { directory.digest("SHA-256") }
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

            test("replaces an existing target") {
                val source = file("replace-source", "new")
                val target = file("replace-target", "old")

                source.move(target)

                source.shouldNotExist()
                target.readText() shouldBe "new"
            }

            test("preserves binary content") {
                val bytes = Random(469).nextBytes(64 * 1024)
                val source = (root / "binary-source").apply { writeBytes(bytes) }
                val target = root / "binary-target"

                source.move(target)

                target.readBytes() shouldBe bytes
            }

            test("moves into an existing sibling directory") {
                val destination = (root / "nested").apply { createDirectories() }
                val target = destination / "moved"

                file("nested-source", sample).move(target)

                target.shouldExist()
                target.readText() shouldBe sample
            }

            test("moves a directory with its children") {
                val source = (root / "tree-source").apply { createDirectories() }
                (source / "child").writeText(sample)
                val target = root / "tree-target"

                source.move(target)

                source.shouldNotExist()
                target.listDirectoryEntries().map { it.fileName.toString() } shouldBe listOf("child")
                (target / "child").readText() shouldBe sample
            }

            test("merges a directory into an existing target, overwriting shared files and keeping the rest") {
                val source = (root / "merge-source").apply { createDirectories() }
                (source / "shared").writeText("new")

                val target = (root / "merge-target").apply { createDirectories() }
                (target / "shared").writeText("old")
                (target / "kept").writeText("kept")

                source.move(target)

                source.shouldNotExist()
                (target / "shared").readText() shouldBe "new"
                (target / "kept").readText() shouldBe "kept"
            }

            test("fails when the source is missing") {
                shouldThrow<NoSuchFileException> {
                    (root / "ghost").move(root / "ghost-target")
                }
            }

            test("creates the target directory when it does not exist") {
                val target = root / "missing-dir" / "target"

                file("orphan-source", sample).move(target)

                target.readText() shouldBe sample
            }
        }

        context("toAbsolutePath") {
            test("resolves a relative path against the current working directory") {
                "utrecorder-relative-probe".toAbsolutePath() shouldBe
                    Paths.get("utrecorder-relative-probe").toAbsolutePath().normalize()
            }

            test("normalizes redundant path segments") {
                val messy = "$root/nested/../probe"

                messy.toAbsolutePath() shouldBe (root / "probe")
            }

            test("returns an absolute path") {
                "relative".toAbsolutePath().isAbsolute shouldBe true
            }
        }

        context("deleteCatching") {
            test("removes a populated directory tree") {
                val directory = (root / "delete-tree").apply { createDirectories() }
                (directory / "child").writeText(sample)

                directory.deleteCatching()

                directory.shouldNotExist()
            }

            test("does not throw for a path that does not exist") {
                val missing = root / "delete-absent"

                missing.deleteCatching()

                missing.shouldNotExist()
            }

            test("throws IllegalArgumentException for zero attempts") {
                shouldThrow<IllegalArgumentException> {
                    (root / "delete-zero-attempts").deleteCatching(attempts = 0)
                }
            }

            test("succeeds at once for a missing path, without waiting out the delay") {
                val missing = root / "delete-absent-delayed"
                val (outcome, elapsed) = measureTimedValue { missing.deleteCatching(attempts = 3, delay = 5.seconds) }

                outcome.shouldBeSuccess()
                elapsed.inWholeMilliseconds shouldBeLessThan 5000L
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

        context("readLinesOrEmpty") {
            test("reads back the lines of an existing file") {
                file("read-lines", "first\nsecond").readLinesOrEmpty() shouldBe listOf("first", "second")
            }

            test("returns no lines for a missing file") {
                (root / "read-lines-absent").readLinesOrEmpty() shouldBe emptyList()
            }

            test("decodes with the given charset") {
                val path = (root / "read-lines-charset").apply { writeText("café", Charsets.ISO_8859_1) }

                path.readLinesOrEmpty(Charsets.ISO_8859_1) shouldBe listOf("café")
            }
        }

        context("copy") {
            test("copies the content and keeps the source contents intact") {
                val source = file("copy-source", sample)
                val target = root / "copy-target"

                source.copy(target)

                source.readText() shouldBe sample
                target.readText() shouldBe sample
            }

            test("fails with the default message when the source is missing") {
                val exception =
                    shouldThrow<IllegalStateException> {
                        (root / "copy-absent").copy(root / "copy-absent-target")
                    }

                exception.message shouldBe "No file to copy at ${root / "copy-absent"}"
            }

            test("fails with the given message when the source is missing") {
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

            test("throws FileAlreadyExistsException and leaves an existing target unchanged when overwrite is false") {
                val target = file("copy-kept-target", "old")

                shouldThrow<FileAlreadyExistsException> { file("copy-kept-source", "new").copy(target, overwrite = false) }

                target.readText() shouldBe "old"
            }
        }

        context("readTextOrEmpty") {
            test("reads back the contents of an existing file") {
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
    })
