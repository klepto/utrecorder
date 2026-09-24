package dev.klepto.utrecorder.common.http

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

        fun respondToHead(
            exchange: HttpExchange,
            contentLength: Long?,
        ) {
            contentLength?.let { exchange.responseHeaders["Content-Length"] = it.toString() }
            exchange.sendResponseHeaders(200, -1)
        }

        fun respondWithBody(
            exchange: HttpExchange,
            bytes: ByteArray,
        ) {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            bodyBytesSent += bytes.size
        }

        fun serve(
            body: String,
            contentLength: Boolean = true,
        ) {
            val bytes = body.toByteArray()
            handler = { exchange ->
                when (exchange.requestMethod) {
                    "HEAD" -> respondToHead(exchange, bytes.size.toLong().takeIf { contentLength })
                    else -> respondWithBody(exchange, bytes)
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

            test("returns a failure for a 404 status") {
                headCatching(url).shouldBeFailure()
            }

            test("returns a failure for a refused connection") {
                val closedPort = ServerSocket(0).use { it.localPort }

                headCatching("http://${server.address.hostString}:$closedPort/file").shouldBeFailure()
            }

            test("sends only a HEAD request and receives no body") {
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
