package dev.klepto.utrecorder.common.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random

class EmbeddedArtifactsTest :
    FunSpec({
        val namespace = "artifacts"

        val root = createTempDirectory("utrecorder-embedded-artifacts-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun classpathRoot(name: String): Path = (root / name).createDirectories()

        fun writeIndex(
            classpathRoot: Path,
            content: String,
        ) = (classpathRoot / namespace / EmbeddedArtifacts.INDEX).createParentDirectories().writeText(content)

        fun writeArtifact(
            classpathRoot: Path,
            name: String,
            bytes: ByteArray,
        ) = (classpathRoot / namespace / name).createParentDirectories().writeBytes(bytes)

        fun artifactsOf(vararg classpathRoots: Path): EmbeddedArtifacts {
            val urls =
                classpathRoots
                    .map { it.toUri().toURL() }
                    .toTypedArray()
            return EmbeddedArtifacts(namespace, URLClassLoader(urls, null))
        }

        test("lists the index names in index order, trimmed and without blank lines") {
            val classpath = classpathRoot("order")
            writeIndex(classpath, "b.txt\n  a.txt  \n\nc.txt\n")

            artifactsOf(classpath).names shouldBe listOf("b.txt", "a.txt", "c.txt")
        }

        test("merges the index of every classpath root without duplicates") {
            val firstClasspath = classpathRoot("merge-a")
            val secondClasspath = classpathRoot("merge-b")
            writeIndex(firstClasspath, "a.txt\nb.txt")
            writeIndex(secondClasspath, "b.txt\nc.txt")

            artifactsOf(firstClasspath, secondClasspath).names shouldBe listOf("a.txt", "b.txt", "c.txt")
        }

        test("lists no names when the classpath holds no index") {
            artifactsOf(classpathRoot("empty")).names shouldBe emptyList()
        }

        test("keeps the names it read first when the index changes later") {
            val classpath = classpathRoot("cached")
            writeIndex(classpath, "a.txt")
            val artifacts = artifactsOf(classpath)
            val first = artifacts.names

            writeIndex(classpath, "a.txt\nb.txt")

            artifacts.names shouldBe first
        }

        test("reads the exact bytes of a named artifact") {
            val classpath = classpathRoot("read")
            val bytes = Random(22).nextBytes(4096)
            writeArtifact(classpath, "blob.bin", bytes)

            artifactsOf(classpath).read("blob.bin") shouldBe bytes
        }

        test("throws with the resource path when the artifact is missing") {
            val artifacts = artifactsOf(classpathRoot("missing"))

            val error = shouldThrow<IllegalStateException> { artifacts.read("ghost.bin") }

            error.message shouldBe "Embedded artifact not found at artifacts/ghost.bin"
        }

        test("writes every artifact in index order and overwrites the files already there") {
            val classpath = classpathRoot("extract")
            writeIndex(classpath, "a.txt\nb.txt")
            writeArtifact(classpath, "a.txt", "A content".toByteArray())
            writeArtifact(classpath, "b.txt", "B content".toByteArray())
            val target = (root / "extract-target").createDirectories()
            (target / "a.txt").writeText("stale")

            val written = artifactsOf(classpath).extractTo(target)

            written shouldBe listOf(target / "a.txt", target / "b.txt")
            (target / "a.txt").readText() shouldBe "A content"
            (target / "b.txt").readText() shouldBe "B content"
        }

        test("creates a missing target directory and the parent directory of a nested index entry") {
            val classpath = classpathRoot("extract-nested")
            writeIndex(classpath, "sub/thing.u")
            writeArtifact(classpath, "sub/thing.u", "nested content".toByteArray())
            val target = root / "extract-nested-target" / "nested"

            val written = artifactsOf(classpath).extractTo(target)

            written shouldBe listOf(target / "sub" / "thing.u")
            (target / "sub" / "thing.u").readText() shouldBe "nested content"
        }

        test("writes each artifact to the path the target names for it") {
            val classpath = classpathRoot("extract-named")
            writeIndex(classpath, "a.txt\nb.txt")
            writeArtifact(classpath, "a.txt", "A content".toByteArray())
            writeArtifact(classpath, "b.txt", "B content".toByteArray())
            val target = root / "extract-named-target"

            val written = artifactsOf(classpath).extractTo { name -> target / "run-$name" }

            written shouldBe listOf(target / "run-a.txt", target / "run-b.txt")
            (target / "run-a.txt").readText() shouldBe "A content"
            (target / "run-b.txt").readText() shouldBe "B content"
        }
    })
