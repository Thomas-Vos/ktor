/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets

import io.ktor.network.util.*
import kotlinx.cinterop.*
import platform.posix.*

internal fun assignOptions(descriptor: Int, options: SocketOptions) {
    disableSigpipe(descriptor)
    setSocketFlag(descriptor, SO_REUSEADDR, options.reuseAddress)
    reusePortFlag?.let { setSocketFlag(descriptor, it, options.reusePort) }
    if (options is SocketOptions.UDPSocketOptions) {
        setSocketFlag(descriptor, SO_BROADCAST, options.broadcast)
    }

    if (options is SocketOptions.UDPSocketOptions) {
        options.receiveBufferSize.takeIf { it > 0 }?.let {
            setSocketOption(descriptor, SO_RCVBUF, it)
        }
        options.sendBufferSize.takeIf { it > 0 }?.let {
            setSocketOption(descriptor, SO_SNDBUF, it)
        }
    }
}

/**
 * Makes writes to [descriptor] fail with `EPIPE` instead of raising `SIGPIPE` when the connection is broken,
 * for example after the peer closed it or after iOS reclaimed the socket of a suspended app.
 * The default action of `SIGPIPE` terminates the process.
 *
 * Darwin uses the `SO_NOSIGPIPE` socket option ([noSigpipeOption]). Linux and Android have no such option,
 * so every `send`/`sendto` passes `MSG_NOSIGNAL` ([noSigpipeSendFlag]) instead.
 */
internal fun disableSigpipe(descriptor: Int) {
    noSigpipeOption?.let { setSocketFlag(descriptor, it, true) }
}

private fun setSocketFlag(
    descriptor: Int,
    optionName: Int,
    optionValue: Boolean
) = setSocketOption(descriptor, optionName, if (optionValue) 1 else 0)

@OptIn(ExperimentalForeignApi::class)
private fun setSocketOption(
    descriptor: Int,
    optionName: Int,
    optionValue: Int
) = memScoped {
    val optval = alloc<IntVar>()
    optval.value = optionValue
    ktor_setsockopt(descriptor, SOL_SOCKET, optionName, optval.ptr, sizeOf<IntVar>().convert()).check()
}
