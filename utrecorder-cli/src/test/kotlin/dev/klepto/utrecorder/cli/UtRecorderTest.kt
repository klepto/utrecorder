package dev.klepto.utrecorder.cli

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.cli.UtRecorder.Invocation.Help
import dev.klepto.utrecorder.cli.UtRecorder.Invocation.Invalid
import dev.klepto.utrecorder.cli.UtRecorder.Invocation.Record
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.core.RecorderStage.Capturing
import dev.klepto.utrecorder.core.RecorderStage.Muxing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

class UtRecorderTest :
    FunSpec({
        val home = Path.of("build", "test", "utrecorder-cli-test")
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home)

        afterSpec { home.toFile().deleteRecursively() }

        test(
            "outputName drops the host, the first path segment and the .dem extension, then keeps only letters, " +
                "digits, dots, hyphens and underscores, or falls back to demo",
        ) {
            UtRecorder.outputName("http://api.utbt.net/demos/CAP fades 12.dem?id=1") shouldBe "capfades12"
            UtRecorder.outputName("http://api.utbt.net/demos/capfades12.DEM") shouldBe "capfades12"
            UtRecorder.outputName("http://api.utbt.net/a/b/x.dem") shouldBe "bx"
            UtRecorder.outputName("http://api.utbt.net/demos/x.dem.bak") shouldBe "x.dem.bak"
            UtRecorder.outputName("http://api.utbt.net") shouldBe "demo"
            UtRecorder.outputName(
                "http://api.utbt.net/demos/90729d6a-ac84-45f6-bdc0-beacad976e55",
            ) shouldBe "90729d6a-ac84-45f6-bdc0-beacad976e55"
        }

        test("outputName throws when the URL holds a malformed percent escape") {
            shouldThrow<IllegalArgumentException> {
                UtRecorder.outputName("http://api.utbt.net/demos/cap%zz.dem")
            }.message shouldBe "Demo URL http://api.utbt.net/demos/cap%zz.dem holds a malformed percent escape"
        }

        test("parseArguments takes --help or -h anywhere as a request for the usage") {
            UtRecorder.parseArguments(listOf("--help")) shouldBe Help
            UtRecorder.parseArguments(listOf("-h")) shouldBe Help
            UtRecorder.parseArguments(listOf("http://api.utbt.net/demos/x.dem", "--help", "--bogus")) shouldBe Help
        }

        test("parseArguments takes a single argument as the demo URL") {
            UtRecorder.parseArguments(listOf("http://api.utbt.net/demos/x.dem")) shouldBe
                Record("http://api.utbt.net/demos/x.dem")
        }

        test("parseArguments rejects no argument, more than one argument and an unknown option") {
            UtRecorder.parseArguments(emptyList()) shouldBe Invalid("No demo URL given")
            UtRecorder.parseArguments(listOf("http://a/demos/x.dem", "http://a/demos/y.dem")) shouldBe
                Invalid("Expected one demo URL, got 2 arguments")
            UtRecorder.parseArguments(listOf("http://a/demos/x.dem", "--bogus")) shouldBe
                Invalid("Unknown option --bogus")
            UtRecorder.parseArguments(listOf("-")) shouldBe Invalid("Unknown option -")
        }

        test("parseArguments rejects a URL that holds a malformed percent escape") {
            UtRecorder.parseArguments(listOf("http://api.utbt.net/demos/cap%zz.dem")) shouldBe
                Invalid("Demo URL http://api.utbt.net/demos/cap%zz.dem holds a malformed percent escape")
        }

        test("progressLine prints the stage and the whole percent done") {
            UtRecorder.progressLine(Progress(Capturing, 0.426)) shouldBe "Capturing 42%"
            UtRecorder.progressLine(Progress(Muxing, 1.0)) shouldBe "Muxing 100%"
        }

        test("main throws when the demo cannot be fetched") {
            shouldThrow<Exception> { UtRecorder.main("http://127.0.0.1:1/demos/never.dem", directories) }
        }

        test("main leaves no workspace behind when it is cancelled during the fetch") {
            directories.workspace.toFile().deleteRecursively()
            val requestReceived = CompletableDeferred<Unit>()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") {
                requestReceived.complete(Unit)
                // Never responds: the fetch stays suspended on the network read until it is cancelled.
            }
            server.start()

            try {
                val url = "http://${server.address.hostString}:${server.address.port}/demos/hangs.dem"

                coroutineScope {
                    val job = launch { UtRecorder.main(url, directories) }
                    requestReceived.await()
                    job.cancelAndJoin()
                }

                directories.workspace.listDirectoryEntries() shouldBe emptyList()
            } finally {
                server.stop(0)
            }
        }
    })
