package dev.klepto.utrecorder.cli

import dev.klepto.utrecorder.common.config.Directories
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

class UtRecorderCliTest :
    FunSpec({
        val home = Path.of("build", "test", "utrecorder-cli-test")
        val directories = Directories(home)

        afterSpec { home.toFile().deleteRecursively() }

        test("the URL's file name is taken with the query string dropped, sanitised, or falls back") {
            UtRecorderCli.outputName("http://api.utbt.net/demos/CAP fades 12.dem?id=1") shouldBe "capfades12.dem"
            UtRecorderCli.outputName("http://api.utbt.net") shouldBe "demo"
        }

        test("the cached demo is reused without a second network touch") {
            val url = "http://api.utbt.net:1/demos/kept.dem"
            val cached = directories.cache / "demos" / "kept.dem"
            cached.parent.createDirectories()
            cached.writeText("cached demo")

            UtRecorderCli.cachedDemo(url, directories) shouldBe cached
            cached.exists() shouldBe true
        }

        test("a failure of the fetch or the recording reaches the caller as the thrown error") {
            shouldThrow<Exception> { UtRecorderCli.main("http://127.0.0.1:1/demos/never.dem", directories) }
        }
    })
