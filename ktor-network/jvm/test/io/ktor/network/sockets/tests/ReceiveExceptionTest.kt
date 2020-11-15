/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets.tests

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.debug.junit4.*
import org.junit.*
import kotlin.coroutines.*
import kotlin.io.use
import kotlin.test.*
import kotlin.test.Test

class ReceiveExceptionTest : CoroutineScope {
    private val testJob = Job()
    private val selector = ActorSelectorManager(Dispatchers.Default + testJob)

    @get:Rule
    val timeout = CoroutinesTimeout(1000, cancelOnTimeout = true)

    override val coroutineContext: CoroutineContext
        get() = testJob

    @AfterTest
    fun tearDown() {
        testJob.cancel()
        selector.close()
    }

    @Test
    fun testReceiveFails(): Unit = runBlocking {
        val exception = assertFailsWith<IOException> {
            val socket = aSocket(selector)
                .udp()
                .bind()

            socket.use {
                it.receive()
            }
        }
        assertEquals(exception.message, "Test receive exception")
    }
}
