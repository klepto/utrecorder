package dev.klepto.utrecorder.cli

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.core.RecorderProgress
import dev.klepto.utrecorder.core.RecorderStage.Capturing
import dev.klepto.utrecorder.core.RecorderStage.Muxing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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

        test("outputName drops the host and the first path segment, then keeps only letters, digits and dots, or falls back to demo") {
            UtRecorder.outputName("http://api.utbt.net/demos/CAP fades 12.dem?id=1") shouldBe "capfades12.dem"
            UtRecorder.outputName("http://api.utbt.net/a/b/x.dem") shouldBe "bx.dem"
            UtRecorder.outputName("http://api.utbt.net") shouldBe "demo"
        }

        test("progressLine prints the stage and the whole percent done") {
            UtRecorder.progressLine(RecorderProgress(Capturing, 0.426)) shouldBe "Capturing 42%"
            UtRecorder.progressLine(RecorderProgress(Muxing, 1.0)) shouldBe "Muxing 100%"
        }

        test("main throws when the demo cannot be fetched") {
            shouldThrow<Exception> { UtRecorder.main("http://127.0.0.1:1/demos/never.dem", directories) }
        }

        test("main keeps the download workspace when it is cancelled during the fetch") {
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

                directories.workspace
                    .listDirectoryEntries()
                    .single()
                    .fileName
                    .toString() shouldStartWith "download-"
            } finally {
                server.stop(0)
            }
        }
    })
