package dev.klepto.utrecorder.core

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.core.demo.DemoUnavailableException
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.NoSuchFileException
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class RecorderTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-recorder-test")
        val installation = home / "install"
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home / "home")
        val config = RecorderConfig(ConfigReader.EMPTY, UtConfig(ConfigReader.EMPTY, directories, installation))
        val recorder = Recorder(config, directories)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/status") { exchange ->
            val status =
                exchange.requestURI.path
                    .split('/')[2]
                    .toInt()
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()

        fun url(path: String): String = "http://127.0.0.1:${server.address.port}/$path"

        beforeSpec {
            (installation / "System").createDirectories()
            (installation / "System" / UtPlatform.current().gameExecutable).writeText("")
        }
        afterSpec {
            server.stop(0)
            home.toFile().deleteRecursively()
        }

        test("playDemo fails before launching the game when no file exists at the demo path") {
            shouldThrow<NoSuchFileException> { recorder.playDemo(home / "missing.dem") }
        }

        test("playDemo fails before launching the game when the demo does not parse") {
            val demo = home / "broken.dem"
            demo.writeBytes(byteArrayOf(0x1, 0x2, 0x3, 0x4))

            shouldThrow<IllegalStateException> { recorder.playDemo(demo) }
        }

        test("record fails before downloading when the demo URL names no file") {
            val url = "http://example.invalid/?name=run.dem#top"

            shouldThrow<IllegalArgumentException> { recorder.record(url) }.message shouldBe
                "Demo URL http://example.invalid/?name=run.dem#top names no file"
        }

        test("record fails before downloading when the decoded demo file name leaves the workspace") {
            listOf(
                "http://example.invalid/demos/..%2Frun.dem",
                "http://example.invalid/demos/..%5Crun.dem",
                "http://example.invalid/demos/%2E%2E",
                "http://example.invalid/demos/C%3Arun.dem",
                "http://example.invalid/demos/run%00.dem",
            ).forEach { url ->
                shouldThrow<IllegalArgumentException> { recorder.record(url) }.message shouldBe
                    "Demo URL $url names no file"
            }
        }

        test("record fails with DemoUnavailableException when the demo download answers 404") {
            val url = url("status/404/run.dem")

            val exception = shouldThrow<DemoUnavailableException> { recorder.record(url) }

            exception.message shouldBe "Demo $url is unavailable: HTTP 404"
            exception.url shouldBe url
            exception.status shouldBe 404
        }

        test("record fails with DemoUnavailableException for every client error status of the demo download") {
            listOf(400, 403, 410, 499).forEach { status ->
                val url = url("status/$status/run.dem")

                shouldThrow<DemoUnavailableException> { recorder.record(url) }.status shouldBe status
            }
        }

        test("record fails with an error other than DemoUnavailableException when the demo download answers 500") {
            val exception = shouldThrow<IllegalStateException> { recorder.record(url("status/500/run.dem")) }

            exception.shouldNotBeInstanceOf<DemoUnavailableException>()
        }

        test("record fails with an error other than DemoUnavailableException when the demo download cannot connect") {
            val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

            val exception = shouldThrow<IOException> { recorder.record("http://127.0.0.1:$port/run.dem") }

            exception.shouldNotBeInstanceOf<DemoUnavailableException>()
        }
    })
