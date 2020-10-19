/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets.tests

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.test.dispatcher.*
import io.ktor.util.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.test.Test

class UDPSocketTest {

    private val done = atomic(0)

    @Test
    fun testBroadcastFails(): Unit = testUdpSockets(1000) { selector ->
        if (OS_NAME == "win") {
            return@testUdpSockets
        }

        retryIgnoringBindException {
            lateinit var socket: BoundDatagramSocket
            var denied = false
            try {
                socket = aSocket(selector)
                    .udp()
                    .bind()

                socket.use {
                    val datagram = Datagram(
                        packet = buildPacket { writeText("0123456789") },
                        address = NetworkAddress("255.255.255.255", 56700)
                    )

                    it.send(datagram)
                }
            } catch (cause: Exception /* TODO: SocketException */) {
                if (cause.message?.contains("Permission denied", ignoreCase = true) != true) {
                    throw cause
                }

                denied = true
            }

            assertTrue(denied)
            socket.socketContext.join()
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun testBroadcastSuccessful() = testUdpSockets(15000) { selector ->
        retryIgnoringBindException {
            val serverSocket = CompletableDeferred<BoundDatagramSocket>()
            val server = launch {
                aSocket(selector)
                    .udp()
                    .bind(NetworkAddress("0.0.0.0", 56700))
                    .use { socket ->
                        serverSocket.complete(socket)
                        val received = socket.receive()
                        assertEquals("0123456789", received.packet.readText())
                    }
            }

            serverSocket.await()

            aSocket(selector)
                .udp()
                .bind {
                    broadcast = true
                }
                .use { socket ->
                    socket.send(
                        Datagram(
                            packet = buildPacket { writeText("0123456789") },
                            address = NetworkAddress("255.255.255.255", 56700)
                        )
                    )
                }

            server.join()
        }
    }

    @Test
    fun testClose(): Unit = testUdpSockets(1000) { selector ->
        retryIgnoringBindException {
            val socket = aSocket(selector)
                .udp()
                .bind()

            socket.close()

            socket.socketContext.join()
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun testInvokeOnClose() = testUdpSockets(1000) { selector ->
        retryIgnoringBindException {
            val socket: BoundDatagramSocket = aSocket(selector)
                .udp()
                .bind()

            socket.outgoing.invokeOnClose {
                done += 1
            }

            assertFailsWith<IllegalStateException> {
                socket.outgoing.invokeOnClose {
                    done += 2
                }
            }

            socket.close()
            socket.close()

            socket.socketContext.join()
            assertTrue(socket.isClosed)
            assertEquals(1, done.value)
        }
    }

    @Test
    fun testOutgoingInvokeOnClose() = testUdpSockets(1000) { selector ->
        retryIgnoringBindException {
            val socket: BoundDatagramSocket = aSocket(selector)
                .udp()
                .bind()

            socket.outgoing.invokeOnClose {
                done += 1
                assertTrue(it is AssertionError)
            }

            socket.outgoing.close(AssertionError())

            assertEquals(1, done.value)
            socket.socketContext.join()
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun testOutgoingInvokeOnCloseWithSocketClose() = testUdpSockets(1000) { selector ->
        retryIgnoringBindException {
            val socket: BoundDatagramSocket = aSocket(selector)
                .udp()
                .bind()

            socket.outgoing.invokeOnClose {
                done += 1
            }

            socket.close()

            assertEquals(1, done.value)

            socket.socketContext.join()
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun testOutgoingInvokeOnClosed() = testUdpSockets(1000) { selector ->
        retryIgnoringBindException {
            val socket: BoundDatagramSocket = aSocket(selector)
                .udp()
                .bind()

            socket.outgoing.close(AssertionError())

            socket.outgoing.invokeOnClose {
                done += 1
                assertTrue(it is AssertionError)
            }

            assertEquals(1, done.value)

            socket.socketContext.join()
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun testSendReceive(): Unit = testUdpSockets(15000) { selector ->
        aSocket(selector)
            .udp()
            .bind(NetworkAddress("127.0.0.1", 8000)) {
                reuseAddress = true
                reusePort = true
            }
            .use { socket ->
                // Send messages to localhost
                launch {
                    val address = NetworkAddress("127.0.0.1", 8000)
                    repeat(10) {
                        val bytePacket = buildPacket { append("hello") }
                        val data = Datagram(bytePacket, address)
                        socket.send(data)
                    }
                }

                // Receive messages from localhost
                repeat(10) {
                    val incoming = socket.receive()
                    assertEquals("hello", incoming.packet.readText())
                }
            }
    }
}

private fun testUdpSockets(
    timeoutMillis: Long,
    block: suspend CoroutineScope.(SelectorManager) -> Unit
) {
    if (!PlatformUtils.IS_JVM && !PlatformUtils.IS_NATIVE) return
    testSuspend {
        withTimeout(timeoutMillis) {
            // TODO: Calling [use] instead of [let] causes [UDPSocketTest] to get stuck on native.
            SelectorManager().let/*use*/ { selector ->
                block(selector)
            }
        }
    }
}

internal inline fun retryIgnoringBindException(block: () -> Unit) {
    var done = false
    while (!done) {
        try {
            block()
        } catch (cause: Exception /* TODO: SocketException */) {
            // TODO: fix this for posix if needed
            if (!cause.message.equals("Already bound", ignoreCase = true)) {
                throw cause
            }
        }

        done = true
    }
}

private val OS_NAME: String
    get() {
        val os = "unknown" // TODO: System.getProperty("os.name", "unknown").toLowerCase()
        return when {
            os.contains("win") -> "win"
            os.contains("mac") -> "mac"
            os.contains("nux") -> "unix"
            else -> "unknown"
        }
    }
