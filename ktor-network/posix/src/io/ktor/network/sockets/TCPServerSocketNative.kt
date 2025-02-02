/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets

import io.ktor.network.selector.*
import io.ktor.network.util.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.coroutines.*

@OptIn(ExperimentalForeignApi::class)
internal class TCPServerSocketNative(
    override val descriptor: Int,
    private val selector: SelectorManager,
    override val localAddress: SocketAddress,
    parent: CoroutineContext = EmptyCoroutineContext
) : SelectableBase(), ServerSocket, CoroutineScope {
    private val _socketContext: CompletableJob = SupervisorJob(parent[Job])

    override val coroutineContext: CoroutineContext = parent + Dispatchers.Unconfined + _socketContext

    override val socketContext: Job
        get() = _socketContext

    init {
        signalIgnoreSigpipe()
    }

    @Suppress("DUPLICATE_LABEL_IN_WHEN")
    override suspend fun accept(): Socket = memScoped {
        val clientAddress = alloc<sockaddr_storage>()
        val clientAddressLength: UIntVarOf<UInt> = alloc()
        clientAddressLength.value = sizeOf<sockaddr_storage>().convert()

        var clientDescriptor: Int
        while (true) {
            clientDescriptor = ktor_accept(descriptor, clientAddress.ptr.reinterpret(), clientAddressLength.ptr)
            if (clientDescriptor > 0) {
                break
            }

            val error = getSocketError()
            when {
                isWouldBlockError(error) -> {
                    selector.select(this@TCPServerSocketNative, SelectInterest.ACCEPT)
                }

                else -> when (error) {
                    EAGAIN, EWOULDBLOCK -> selector.select(this@TCPServerSocketNative, SelectInterest.ACCEPT)
                    EBADF -> error("Descriptor invalid")
                    ECONNABORTED -> error("Connection aborted")
                    EFAULT -> error("Address is not writable part of user address space")
                    EINTR -> error("Interrupted by signal")
                    EINVAL -> error("Socket is unwilling to accept")
                    EMFILE -> error("Process descriptor file table is full")
                    ENFILE -> error("System descriptor file table is full")
                    ENOMEM -> error("OOM")
                    ENOTSOCK -> error("Descriptor is not a socket")
                    EOPNOTSUPP -> error("Not TCP socket")
                    else -> error("Unknown error: $error")
                }
            }
        }
        buildOrClose(clientDescriptor) {
            nonBlocking(clientDescriptor).check()

            val remoteAddress = clientAddress.reinterpret<sockaddr>().toNativeSocketAddress()

            TCPSocketNative(
                selector,
                clientDescriptor,
                remoteAddress = remoteAddress.toSocketAddress(),
                parent = selfContext() + coroutineContext
            )
        }
    }

    override fun close() {
        _socketContext.complete()
        _socketContext.invokeOnCompletion {
            ktor_shutdown(descriptor, ShutdownCommands.Both)
            // Descriptor is closed by the selector manager
            selector.notifyClosed(this)
        }
    }
}

private suspend inline fun selfContext(): CoroutineContext = coroutineContext
