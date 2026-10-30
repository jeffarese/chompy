package com.jeffarese.rexmobile.ui

import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** A pull to refresh lasts until the hosts are back, or names those that aren't. */
class HomeRefreshTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = mutableListOf<HostConnection>()

    @After
    fun tearDown() {
        hosts.forEach { it.disconnect() }
        scope.cancel()
    }

    private fun host(label: String, rex: FakeRex) =
        HostConnection(HostConfig(label = label, url = "https://${label.lowercase()}.test"), rex, scope) {}.also { hosts += it }

    @Test
    fun itEndsOnceEveryHostIsOnline() = runBlocking {
        val studio = host("Studio", FakeRex())
        val started = System.currentTimeMillis()
        assertEquals(emptyList<String>(), refreshHosts(listOf(studio)))
        assertEquals(ConnStatus.ONLINE, studio.state.value.status)
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun itNamesTheHostsStillAway() = runBlocking {
        val studio = host("Studio", FakeRex())
        val air = host("Air", FakeRex().also { rex -> rex.refuses = { true } })
        assertEquals(listOf("Air"), refreshHosts(listOf(studio, air), timeoutMs = 1_500))
    }

    @Test
    fun aHostThatSeemsOnlineButDoesntAnswerIsWaitedFor() = runBlocking {
        val rex = FakeRex()
        // The first check after connecting goes unanswered, as on a connection the network has dropped silently.
        val silent = AtomicBoolean(true)
        val answer = rex.handle
        rex.handle = { method, payload ->
            if (method == "server.version" && silent.getAndSet(false)) CompletableFuture<JsonElement>() else answer(method, payload)
        }
        val studio = host("Studio", rex)
        studio.connect()
        withTimeout(5_000) { studio.state.first { it.status == ConnStatus.ONLINE } }
        val controls = rex.controls.size

        assertEquals(emptyList<String>(), refreshHosts(listOf(studio)))
        // Online again on a new connection, not still on the silent one.
        assertTrue(rex.controls.size > controls)
        assertEquals(ConnStatus.ONLINE, studio.state.value.status)
    }
}
