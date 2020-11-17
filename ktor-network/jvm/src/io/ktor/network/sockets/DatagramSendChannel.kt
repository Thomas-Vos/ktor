/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets

import io.ktor.network.selector.*
import io.ktor.network.util.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.pool.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.selects.*
import kotlinx.coroutines.sync.*
import java.net.*
import java.nio.*
import java.nio.channels.*
import kotlin.native.concurrent.*

private val HANDLER_INVOKED: (Throwable?) -> Unit = {}

internal class DatagramSendChannel(
    val channel: DatagramChannel,
    val socket: DatagramSocketImpl
) : SendChannel<Datagram> {
    private val onCloseHandler = atomic<((Throwable?) -> Unit)?>(null)
    private val closed = atomic(false)

    @ExperimentalCoroutinesApi
    override val isClosedForSend: Boolean
        get() = socket.isClosed

    @ExperimentalCoroutinesApi
    override val isFull: Boolean
        get() = if (isClosedForSend) false else lock.isLocked

    private val lock = Mutex()

    override fun close(cause: Throwable?): Boolean {
        if (!closed.compareAndSet(false, true)) {
            return false
        }

        val handler = onCloseHandler.getAndSet(HANDLER_INVOKED)
        if (handler != null) {
            handler(cause)
        }

        if (!socket.isClosed) {
            socket.close()
        }

        return true
    }


    override fun offer(element: Datagram): Boolean {
        if (!lock.tryLock()) return false

        var result = false

        try {
            DefaultDatagramByteBufferPool.useInstance { buffer ->
                element.packet.copy().readAvailable(buffer)
                result = channel.send(buffer, element.address) == 0
            }
        } finally {
            lock.unlock()
        }

        if (result) {
            element.packet.release()
        }

        return result
    }

    override suspend fun send(element: Datagram) {
        lock.withLock {
            DefaultDatagramByteBufferPool.useInstance { buffer ->
                element.writeMessageTo(buffer)

                val rc = channel.send(buffer, element.address)
                if (rc != 0) {
                    socket.interestOp(SelectInterest.WRITE, false)
                    return
                }

                sendSuspend(buffer, element.address)
            }

        }
    }

    private suspend fun sendSuspend(buffer: ByteBuffer, address: SocketAddress) {
        while (true) {
            socket.interestOp(SelectInterest.WRITE, true)
            socket.selector.select(socket, SelectInterest.WRITE)

            if (channel.send(buffer, address) != 0) {
                socket.interestOp(SelectInterest.WRITE, false)
                break
            }
        }
    }

    override val onSend: SelectClause2<Datagram, SendChannel<Datagram>>
        get() = TODO("[DatagramSendChannel] doesn't support [onSend] select clause")

    @ExperimentalCoroutinesApi
    override fun invokeOnClose(handler: (cause: Throwable?) -> Unit) {
        if (onCloseHandler.compareAndSet(null, handler)) {
            return
        }

        val value = onCloseHandler.value

        val message = if (value === HANDLER_INVOKED) {
            "Another handler was already registered and successfully invoked"
        } else {
            "Another handler was already registered: $value"
        }

        throw IllegalStateException(message)
    }
}

private fun Datagram.writeMessageTo(buffer: ByteBuffer) {
    packet.readAvailable(buffer)
    buffer.flip()
}
