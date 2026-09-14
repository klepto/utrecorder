package dev.klepto.utrecorder.common.io

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class HttpTest :
    FunSpec({
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        val notFound: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1) }
        var handler = notFound
        val methods = mutableListOf<String>()
        var bodyBytesSent = 0

        server.createContext("/") { exchange ->
            methods += exchange.requestMethod
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()

        afterSpec { server.stop(0) }

        beforeTest {
            handler = notFound
            methods.clear()
            bodyBytesSent = 0
        }

        val url = "http://${server.address.hostString}:${server.address.port}/file"

        fun serve(
            body: String,
            contentLength: Boolean = true,
        ) {
            handler = { exchange ->
                val bytes = body.toByteArray()
                if (exchange.requestMethod == "HEAD") {
                    if (contentLength) exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    bodyBytesSent += bytes.size
                }
            }
        }

        context("headCatching") {
            test("returns success holding the content length a 200 response reports") {
                serve("utrecorder-22")

                headCatching(url).shouldBeSuccess(13L)
            }

            test("returns success holding null for a response carrying no content length") {
                serve("utrecorder-22", contentLength = false)

                headCatching(url).shouldBeSuccess(null)
            }

            test("returns a failure for a 404") {
                headCatching(url).shouldBeFailure()
            }

            test("returns a failure for a port that refuses the connection") {
                val refused = ServerSocket(0).use { "http://${InetAddress.getLoopbackAddress().hostAddress}:${it.localPort}/file" }

                headCatching(refused).shouldBeFailure()
            }

            test("sends a HEAD request, so the server sees no GET and sends no body") {
                serve("utrecorder-22")

                headCatching(url)

                methods shouldBe listOf("HEAD")
                bodyBytesSent shouldBe 0
            }
        }

        context("useHttpClient") {
            test("runs a GET against the server and returns what the block returns") {
                serve("utrecorder-22")

                useHttpClient { get(url).bodyAsText() } shouldBe "utrecorder-22"
            }
        }

        context("encodeUrlPathSegment") {
            test("leaves a plain name as it is") {
                "BTPog".encodeUrlPathSegment() shouldBe "BTPog"
            }

            test("encodes a space as %20 and a plus as %2B") {
                "CTF-BT +Fun".encodeUrlPathSegment() shouldBe "CTF-BT%20%2BFun"
            }

            test("encodes a non-ASCII character in UTF-8") {
                "Café".encodeUrlPathSegment() shouldBe "Caf%C3%A9"
            }
        }
    })
