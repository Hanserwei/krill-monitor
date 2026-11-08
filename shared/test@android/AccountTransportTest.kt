package com.hanserwei.krill_monitor

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class AccountTransportTest {
    @Test
    fun realEngineNeverReplaysCreateOrRotationOnImmediateServiceRetry() = runTest {
        for (path in listOf("/api/tokens", "/api/tokens/42/rotate")) {
            val requests = AtomicInteger()
            val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            val worker =
                thread(isDaemon = true) {
                    try {
                        while (!server.isClosed) {
                            server.accept().use { socket ->
                                socket.soTimeout = 3000
                                val reader = socket.getInputStream().bufferedReader()
                                var length = 0
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                        length = line.substringAfter(':').trim().toInt()
                                    }
                                }
                                repeat(length) { check(reader.read() >= 0) }
                                requests.incrementAndGet()
                                socket
                                    .getOutputStream()
                                    .write(
                                        "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                                            .toByteArray()
                                    )
                            }
                        }
                    } catch (failure: SocketException) {
                        if (!server.isClosed) throw failure
                    }
                }
            val client = defaultMonitorHttpClient()
            try {
                val response =
                    client.post("http://127.0.0.1:${server.localPort}$path") {
                        if (path == "/api/tokens") setBody("{}")
                    }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals(1, requests.get(), "Non-idempotent Key mutation was replayed")
            } finally {
                client.close()
                server.close()
                worker.join(3000)
            }
        }
    }
}
