package dev.klepto.utrecorder.common.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random

private const val NAMESPACE = "artifacts"

class EmbeddedArtifactsTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-embedded-artifacts-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun classpathRoot(name: String) = (root / name).apply { createDirectories() }

        fun writeIndex(
            classpathRoot: Path,
            namespace: String = NAMESPACE,
            content: String,
        ) = (classpathRoot / namespace).apply { createDirectories() }.let { (it / EmbeddedArtifacts.INDEX).writeText(content) }

        fun writeArtifact(
            classpathRoot: Path,
            name: String,
            bytes: ByteArray,
            namespace: String = NAMESPACE,
        ) {
            val path = classpathRoot / namespace / name
            path.parent?.createDirectories()
            path.writeBytes(bytes)
        }

        fun loaderOf(vararg roots: Path): ClassLoader = URLClassLoader(roots.map { it.toUri().toURL() }.toTypedArray(), null)

        test("reads the index, trimming and dropping blank lines while preserving order") {
            val cp = classpathRoot("order")
            writeIndex(cp, content = "b.txt\n  a.txt  \n\nc.txt\n")

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))

            artifacts.names shouldBe listOf("b.txt", "a.txt", "c.txt")
        }

        test("merges every index on the classpath and de-duplicates") {
            val cpA = classpathRoot("merge-a")
            val cpB = classpathRoot("merge-b")
            writeIndex(cpA, content = "a.txt\nb.txt")
            writeIndex(cpB, content = "b.txt\nc.txt")

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cpA, cpB))

            artifacts.names shouldBe listOf("a.txt", "b.txt", "c.txt")
        }

        test("is empty for a class loader with no such index") {
            val cp = classpathRoot("empty")

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))

            artifacts.names shouldBe emptyList()
        }

        test("computes names once and caches it for the life of the instance") {
            val cp = classpathRoot("cached")
            writeIndex(cp, content = "a.txt")
            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))
            val first = artifacts.names

            writeIndex(cp, content = "a.txt\nb.txt")

            artifacts.names shouldBe first
        }

        test("reads the exact bytes of a named artifact") {
            val cp = classpathRoot("read")
            val bytes = Random(22).nextBytes(4096)
            writeArtifact(cp, "blob.bin", bytes)

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))

            artifacts.read("blob.bin") shouldBe bytes
        }

        test("fails with the resource path when the artifact is missing") {
            val cp = classpathRoot("missing")
            (cp / NAMESPACE).createDirectories()

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))

            val error = shouldThrow<IllegalStateException> { artifacts.read("ghost.bin") }

            error.message shouldBe "Embedded artifact not found at $NAMESPACE/ghost.bin"
        }

        test("creates the target directory, writes every artifact in index order, and overwrites files already there") {
            val cp = classpathRoot("extract")
            writeIndex(cp, content = "a.txt\nb.txt")
            writeArtifact(cp, "a.txt", "A content".toByteArray())
            writeArtifact(cp, "b.txt", "B content".toByteArray())
            val target = (root / "extract-target").apply { createDirectories() }
            (target / "a.txt").writeText("stale")

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))
            val written = artifacts.extractTo(target)

            written shouldBe listOf(target / "a.txt", target / "b.txt")
            (target / "a.txt").readText() shouldBe "A content"
            (target / "b.txt").readText() shouldBe "B content"
        }

        test("creates a target directory that does not exist yet") {
            val cp = classpathRoot("extract-missing-dir")
            writeIndex(cp, content = "a.txt")
            writeArtifact(cp, "a.txt", "content".toByteArray())
            val target = root / "does-not-exist-yet" / "nested"

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))
            artifacts.extractTo(target)

            target.shouldExist()
            (target / "a.txt").readText() shouldBe "content"
        }

        test("creates the parent directory for a nested index entry") {
            val cp = classpathRoot("extract-nested")
            writeIndex(cp, content = "sub/thing.u")
            writeArtifact(cp, "sub/thing.u", "nested content".toByteArray())
            val target = root / "extract-nested-target"

            val artifacts = EmbeddedArtifacts(NAMESPACE, loaderOf(cp))
            val written = artifacts.extractTo(target)

            written shouldBe listOf(target / "sub" / "thing.u")
            (target / "sub" / "thing.u").readText() shouldBe "nested content"
        }
    })
