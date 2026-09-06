package dev.klepto.utrecorder.common.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
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

private const val SAMPLE = "utrecorder-22"
private const val SAMPLE_MD5 = "00ad189b536dc412168fc866a7ec13b0"
private const val SAMPLE_SHA256 = "0323ec0736b6000fffd1a01bc5a70c1e35dc998f1214edde7a668879b54b9562"

private const val EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e"
private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

class PathsTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-paths-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun file(
            name: String,
            content: String = "",
        ) = (root / name).apply { writeText(content) }

        context("digest") {
            test("matches the reference MD5 of an empty file") {
                file("empty-md5").digest("MD5") shouldBe EMPTY_MD5
            }

            test("matches the reference SHA-256 of an empty file") {
                file("empty-sha").digest("SHA-256") shouldBe EMPTY_SHA256
            }

            test("matches the reference MD5 of a known input") {
                file("sample-md5", SAMPLE).digest("MD5") shouldBe SAMPLE_MD5
            }

            test("matches the reference SHA-256 of a known input") {
                file("sample-sha", SAMPLE).digest("SHA-256") shouldBe SAMPLE_SHA256
            }

            test("pads bytes below 0x10 to two hex digits") {
                val md5 = file("padding", SAMPLE).digest("MD5")

                md5.length shouldBe 32
                md5.take(2) shouldBe "00"
            }

            test("emits lowercase hex only") {
                file("lowercase", SAMPLE).digest("SHA-256") shouldMatch Regex("[0-9a-f]{64}")
            }

            test("reads the whole file when it exceeds the copy buffer") {
                val bytes = Random(22).nextBytes(3 * 1024 * 1024)
                val expected =
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }

                (root / "large").apply { writeBytes(bytes) }.digest("SHA-256") shouldBe expected
            }

            test("gives different digests to different content") {
                file("differs-a", SAMPLE).digest("SHA-256") shouldNotBe
                    file("differs-b", "$SAMPLE!").digest("SHA-256")
            }

            test("closes the file so it can be deleted afterwards") {
                val path = file("closed", SAMPLE)

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
                val source = file("move-source", SAMPLE)
                val target = root / "move-target"

                source.move(target)

                source.shouldNotExist()
                target.readText() shouldBe SAMPLE
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

                file("nested-source", SAMPLE).move(target)

                target.shouldExist()
                target.readText() shouldBe SAMPLE
            }

            test("moves a directory with its children") {
                val source = (root / "tree-source").apply { createDirectories() }
                (source / "child").writeText(SAMPLE)
                val target = root / "tree-target"

                source.move(target)

                source.shouldNotExist()
                target.listDirectoryEntries().map { it.fileName.toString() } shouldBe listOf("child")
                (target / "child").readText() shouldBe SAMPLE
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

                file("orphan-source", SAMPLE).move(target)

                target.readText() shouldBe SAMPLE
            }
        }

        context("stage") {
            test("runs the block against a directory that is a sibling of the receiver") {
                val target = root / "stage-sibling" / "install"

                target.stage { directory ->
                    directory.shouldExist()
                    directory.parent shouldBe target.toAbsolutePath().parent
                }
            }

            test("removes the directory and passes the block's result through once it returns") {
                val target = root / "stage-return"
                var captured: Path? = null

                val result =
                    target.stage { directory ->
                        captured = directory
                        SAMPLE
                    }

                result shouldBe SAMPLE
                captured!!.shouldNotExist()
            }

            test("removes the directory and propagates the exception when the block throws") {
                val target = root / "stage-throws"
                var captured: Path? = null

                shouldThrow<IllegalStateException> {
                    target.stage { directory ->
                        captured = directory
                        error("boom")
                    }
                }

                captured!!.shouldNotExist()
            }

            test("never creates the receiver itself, only its parent") {
                val target = root / "stage-parent-only" / "install"

                target.stage { }

                target.parent.shouldExist()
                target.shouldNotExist()
            }

            test("creates the parent directory when it is missing") {
                val target = root / "stage-missing-parent" / "nested" / "install"

                target.stage { }

                target.parent.shouldExist()
            }

            test("gives different directories to two calls on the same receiver") {
                val target = root / "stage-twice"

                val first = target.stage { it }
                val second = target.stage { it }

                first shouldNotBe second
            }
        }

        context("createTemporaryDirectory") {
            test("creates a uniquely named directory under the receiver, named with the given prefix") {
                val target = root / "temp-dir-parent"

                val created = target.createTemporaryDirectory("probe-")

                created.parent shouldBe target
                created.fileName.toString() shouldStartWith "probe-"
                created.shouldExist()
            }

            test("creates the receiver directory when it does not exist") {
                val target = root / "temp-dir-missing" / "nested"

                target.createTemporaryDirectory("probe-")

                target.shouldExist()
            }

            test("gives different directories to two calls with the same prefix") {
                val target = root / "temp-dir-twice"

                val first = target.createTemporaryDirectory("probe-")
                val second = target.createTemporaryDirectory("probe-")

                first shouldNotBe second
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

        context("createTemporaryFile") {
            test("creates the file next to the receiver") {
                val target = root / "temp-file-sibling" / "install"

                val created = target.createTemporaryFile()

                created.parent shouldBe target.toAbsolutePath().parent
                created.shouldExist()
            }

            test("creates the parent directory when it is missing") {
                val target = root / "temp-file-missing-parent" / "nested" / "install"

                target.createTemporaryFile()

                target.parent.shouldExist()
            }

            test("gives different files to two calls on the same receiver") {
                val target = root / "temp-file-twice"

                val first = target.createTemporaryFile()
                val second = target.createTemporaryFile()

                first shouldNotBe second
            }
        }

        context("deleteRecursivelyQuietly") {
            test("removes a populated directory tree") {
                val directory = (root / "delete-tree").apply { createDirectories() }
                (directory / "child").writeText(SAMPLE)

                directory.deleteRecursivelyQuietly()

                directory.shouldNotExist()
            }

            test("does not throw for a path that does not exist") {
                val missing = root / "delete-absent"

                missing.deleteRecursivelyQuietly()

                missing.shouldNotExist()
            }
        }
    })
