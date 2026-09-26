package dev.klepto.utrecorder.core.demo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readBytes

class DemoFetcherTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-demofetcher-test")
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home)
        val directory = home / "workspace" / "record-1"

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val body = ByteArray(16) { it.toByte() }
        server.createContext("/") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        afterSpec {
            server.stop(0)
            home.toFile().deleteRecursively()
        }

        val fetcher = DemoFetcher(directories)
        val url = "http://${server.address.address.hostAddress}:${server.address.port}/dir/demo-file.dem?id=1"

        test("fetch downloads the demo into the given directory under the file name of the URL path") {
            val fetched = fetcher.fetch(url, directory)

            fetched shouldBe directory / "demo-file.dem"
            fetched.readBytes() shouldBe body
            (directories.cache / "demos").exists() shouldBe false
        }
    })
