/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.selector

import io.ktor.network.sockets.*
import io.ktor.network.util.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import io.ktor.utils.io.locks.*
import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class, InternalAPI::class)
internal class SignalPoint : Closeable {
    private val readDescriptor: Int
    private val writeDescriptor: Int
    private var remaining: Int by atomic(0)
    private val lock = SynchronizedObject()
    private var closed = false

    val selectionDescriptor: Int
        get() = readDescriptor

    init {
        initSocketsIfNeeded()

        val (read, write) = memScoped {
            val fd1 = ktor_socket(AF_INET, SOCK_STREAM, IPPROTO_TCP).check()
            setSocketFlag(fd1, SO_REUSEADDR, true).check()
            var address = InetSocketAddress("127.0.0.1", 0).resolve().single()
            address.nativeAddress { pointer, size ->
                ktor_bind(fd1, pointer, size).check()
            }
            ktor_listen(fd1, 1).check()
            address = getLocalAddress(fd1)
            val fd2 = ktor_socket(AF_INET, SOCK_STREAM, 0).check()
            address.nativeAddress { pointer, size ->
                ktor_connect(fd2, pointer, size).check()
            }
            val fd3 = ktor_accept(fd1, null, null).check()
            closesocket(fd1.convert())

            nonBlocking(fd2).check()
            nonBlocking(fd3).check()

            Pair(fd2, fd3)
        }

        readDescriptor = read
        writeDescriptor = write
    }

    fun check() {
        synchronized(lock) {
            if (closed) return@synchronized
            while (remaining > 0) {
                remaining -= readFromPipe()
            }
        }
    }

    @OptIn(UnsafeNumber::class)
    fun signal() {
        synchronized(lock) {
            if (closed) return@synchronized

            if (remaining > 0) return

            memScoped {
                val array = allocArray<ByteVar>(1)
                array[0] = 7
                // note: here we ignore the result of write intentionally
                // we simply don't care whether the buffer is full or the pipe is already closed
                val result = ktor_send(writeDescriptor, array, 1.convert(), 0)
                if (result < 0) return

                remaining += result.toInt()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return@synchronized
            closed = true

            close(writeDescriptor)
            readFromPipe()
            close(readDescriptor)
        }
    }

    @OptIn(UnsafeNumber::class)
    private fun readFromPipe(): Int {
        var count = 0

        memScoped {
            val buffer = allocArray<ByteVar>(1024)

            do {
                val result = ktor_recv(readDescriptor, buffer, 1024.convert(), 0).convert<Int>()
                if (result < 0) {
                    if (getSocketError() != WSAEWOULDBLOCK) {
                        throw PosixException.forSocketError()
                    }

                    break
                }

                if (result == 0) {
                    break
                }

                count += result
            } while (true)
        }

        return count
    }
}
