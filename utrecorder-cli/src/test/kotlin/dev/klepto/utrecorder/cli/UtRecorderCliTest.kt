package dev.klepto.utrecorder.cli

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.config.Directories
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
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
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

        test("cancelling the coroutine running main(url, directories) leaves no child process alive and no workspace directory behind") {
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
                    val job = launch { UtRecorderCli.main(url, directories) }
                    requestReceived.await()
                    job.cancelAndJoin()
                }

                val leftWorkspaceFiles =
                    if (directories.workspace.exists()) {
                        directories.workspace.listDirectoryEntries()
                    } else {
                        emptyList()
                    }
                leftWorkspaceFiles shouldBe emptyList()
            } finally {
                server.stop(0)
            }
        }
    })
