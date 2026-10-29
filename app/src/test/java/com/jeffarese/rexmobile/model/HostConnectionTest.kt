package com.jeffarese.rexmobile.model

import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.FakeRex.Companion.status
import com.jeffarese.rexmobile.rex.ProgramStatus
import com.jeffarese.rexmobile.rex.TERMINAL_CREATOR
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class HostConnectionTest {
    private val rex = FakeRex("b1", "b2")
    private val alerts = CopyOnWriteArrayList<Alert>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errors += e })
    private val host = HostConnection(HostConfig(label = "fake", url = "https://fake.test"), rex, scope) { alerts += it }

    @After
    fun tearDown() {
        host.disconnect()
        scope.cancel()
    }

    private fun states(bid: String) = host.state.value.blocks[bid]?.statuses?.map { it.state }

    @Test
    fun statusesAreReadAgainAfterAReconnect() {
        rex.block("b1").statuses = listOf(status("claude", "working", "Fix the tests"))
        host.connect()
        waitFor("the first read") { states("b1") == listOf("working") }
        assertEquals(emptyList<Alert>(), alerts)

        // While the connection is down, the agent stops to ask: the event that said so is lost with it.
        rex.block("b1").statuses = listOf(status("claude", "blocked", "Fix the tests"))
        rex.controls.last().drop()
        waitFor("the read after reconnecting") { states("b1") == listOf("blocked") }
        waitFor("the alert") { alerts.isNotEmpty() }
        assertEquals(listOf(Alert.Kind.BLOCKED), alerts.map { it.kind })
        assertEquals("b1", alerts.single().blockId)
        assertTrue(host.state.value.blocks.getValue("b1").unseen)

        // An agent that finished while away is no longer working; one unchanged doesn't alert again.
        rex.block("b2").statuses = listOf(status("claude", "working"))
        rex.controls.last().drop()
        waitFor("b2's new status") { states("b2") == listOf("working") }
        rex.block("b2").statuses = emptyList()
        rex.block("b2").title = "idle now"
        rex.controls.last().drop()
        waitFor("b2's status gone") { states("b2") == emptyList<String>() && host.state.value.blocks["b2"]?.title == "idle now" }
        assertEquals(listOf("blocked"), states("b1"))
        assertEquals(listOf(Alert.Kind.BLOCKED), alerts.map { it.kind })
    }

    @Test
    fun anAgentThatFinishedBeforeTheAppStartedIsNewUntilSeen() {
        val reviewed = Reviewed()
        rex.block("b1").statuses = listOf(status("claude", "done", at = "2026-10-28T10:00:00Z"))
        rex.block("b2").statuses = listOf(status("claude", "error", at = "2026-10-28T09:00:00Z"))
        // b2's failure was looked at before.
        reviewed.seen("b2", listOf(ProgramStatus(id = "claude", state = "error", updatedAt = "2026-10-28T09:00:00Z")))

        fun start() = HostConnection(HostConfig(label = "fake", url = "https://fake.test"), rex, scope, reviewed = reviewed) { alerts += it }
            .also { it.connect() }
        fun HostConnection.unseen(bid: String) = state.value.blocks[bid]?.unseen

        val first = start()
        waitFor("both read") { first.state.value.blocks.let { it["b1"]?.statuses?.size == 1 && it["b2"]?.statuses?.size == 1 } }
        waitFor("b1 new") { first.unseen("b1") == true }
        assertEquals(false, first.unseen("b2"))
        // Nothing happened while the app watched, so nothing to alert about.
        assertEquals(emptyList<Alert>(), alerts)

        first.markSeen("b1")
        waitFor("b1 seen") { first.unseen("b1") == false }
        first.disconnect()

        val second = start()
        waitFor("both read again") { second.state.value.blocks.let { it["b1"]?.statuses?.size == 1 && it["b2"]?.statuses?.size == 1 } }
        assertEquals(false, second.unseen("b1"))

        // It finished again since: new again.
        second.disconnect()
        rex.block("b1").statuses = listOf(status("claude", "done", at = "2026-10-28T11:00:00Z"))
        val third = start()
        waitFor("b1 new again") { third.unseen("b1") == true }
        third.disconnect()
    }

    @Test
    fun aSubtaskFinishingIsNotAnAlert() {
        rex.block("b1").statuses = listOf(buildJsonObject { put("state", "working") }, status("build", "working"))
        host.connect()
        waitFor("the first read") { states("b1") == listOf("working", "working") }

        rex.controls.last().blockEvent("b1", "program_status_changed", buildJsonObject { put("record", status("build", "done")) })
        waitFor("the subtask done") { states("b1") == listOf("working", "done") }
        rex.controls.last().blockEvent("b1", "program_status_changed", buildJsonObject { put("record", buildJsonObject { put("state", "done") }) })
        waitFor("the agent done") { states("b1") == listOf("done", "done") }
        waitFor("the alert") { alerts.isNotEmpty() }
        assertEquals(listOf(Alert.Kind.DONE), alerts.map { it.kind })
    }

    @Test
    fun anEventDuringTheReadWinsOverIt() {
        rex.block("b1").statuses = listOf(status("claude", "working"))
        host.connect()
        waitFor("the first read") { states("b1") == listOf("working") }

        // The next read of b1 is held, and what it will say of its status is already out of date.
        val heldStatus = CompletableFuture<JsonElement>()
        val heldTitle = CompletableFuture<JsonElement>()
        val answer = rex.handle
        rex.handle = { method, payload ->
            val b1 = payload.toString().contains("\"b1\"")
            when {
                b1 && method == "$TERMINAL_CREATOR.program_status" -> heldStatus
                b1 && method == "$TERMINAL_CREATOR.title" -> heldTitle
                else -> answer(method, payload)
            }
        }
        rex.block("b1").statuses = listOf(status("claude", "blocked"))
        rex.controls.last().drop()
        waitFor("the held read") { rex.count("$TERMINAL_CREATOR.program_status") >= 4 && rex.count("$TERMINAL_CREATOR.title") >= 4 }

        rex.controls.last().blockEvent("b1", "program_status_changed", buildJsonObject {
            putJsonObject("record") { put("id", "claude"); put("state", "done") }
        })
        waitFor("the event") { states("b1") == listOf("done") }
        heldStatus.complete(answer("$TERMINAL_CREATOR.program_status", buildJsonObject { put("block_id", "b1") }).get())
        heldTitle.complete(buildJsonObject { put("title", "read after the event") })
        // The title, read with the status, says the read has been put in place.
        waitFor("the read put in place") { host.state.value.blocks["b1"]?.title == "read after the event" }
        assertEquals(listOf("done"), states("b1"))
        assertEquals(listOf(Alert.Kind.DONE), alerts.map { it.kind })
    }

    @Test
    fun aViewChangeAnnouncedDuringItsReadIsReadToo() {
        host.connect()
        waitFor("the first view") { host.state.value.views["s1"]?.revision == 1L }

        // The read for revision 2 is held while the session changes again.
        val held = CompletableFuture<JsonElement>()
        val answer = rex.handle
        rex.handle = { method, payload -> if (method == "session.view" && !held.isDone) held else answer(method, payload) }
        rex.revision = 2
        rex.controls.last().viewChanged(2)
        waitFor("the held read") { rex.count("session.view") == 1 }
        val second = answer("session.view", buildJsonObject { put("session_id", "s1") }).get()
        rex.revision = 3
        rex.controls.last().viewChanged(3)
        held.complete(second)
        waitFor("the view at revision 3") { host.state.value.views["s1"]?.revision == 3L }
    }

    @Test
    fun aHostWithoutAPortThatAnswersOnlyOn7297IsKeptThere() {
        rex.refuses = { it.port != 7297 }
        val moved = CopyOnWriteArrayList<String>()
        val other = HostConnection(HostConfig(label = "fake", url = "https://fake.test"), rex, scope, onMoved = { moved += it }) {}
        try {
            other.connect()
            waitFor("online on 7297") { other.state.value.status == ConnStatus.ONLINE }
            assertEquals(listOf("https://fake.test:7297"), moved)
        } finally {
            other.disconnect()
        }
    }

    @Test
    fun anEventOfAnUnexpectedShapeIsSkipped() {
        host.connect()
        waitFor("the first read") { host.state.value.blocks.keys == setOf("b1", "b2") && rex.count("$TERMINAL_CREATOR.title") == 2 }
        Thread.sleep(100)
        // A session without its id, then an ordinary event.
        rex.controls.last().event(buildJsonObject {
            put("type", "session_created")
            putJsonObject("payload") { putJsonObject("session") { put("label", 5) } }
        })
        rex.controls.last().blockEvent("b1", "title_changed", buildJsonObject { put("title", "after") })
        waitFor("the event after it") { host.state.value.blocks["b1"]?.title == "after" }
        assertEquals(emptyList<Throwable>(), errors)
    }

    @Test
    fun aConnectionStillOwingAnswersIsKeptWhenSlowToAnswer() {
        host.connect()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        val answer = rex.handle
        val version = CompletableFuture<JsonElement>()
        val redraw = CompletableFuture<JsonElement>()
        rex.handle = { method, payload ->
            when (method) {
                "server.version" -> version
                "$TERMINAL_CREATOR.format" -> redraw
                else -> answer(method, payload)
            }
        }
        // A large reply still on its way, which the check's answer would wait behind on a slow link.
        val pendingCall = Thread { runCatching { kotlinx.coroutines.runBlocking { host.blockMethod("s1", "b1", "format", timeoutMs = 60_000) } } }
        pendingCall.start()
        waitFor("the redraw asked for") { rex.count("$TERMINAL_CREATOR.format") == 1 }

        val busy = CompletableFuture<Liveness>()
        host.ensureAlive { liveness, _ -> busy.complete(liveness) }
        assertEquals(Liveness.BUSY, busy.get())
        assertEquals(1, rex.controls.size)
        assertEquals(ConnStatus.ONLINE, host.state.value.status)

        // With nothing owed, a connection that doesn't answer is given up.
        redraw.complete(buildJsonObject { put("content", "") })
        pendingCall.join()
        val silent = CompletableFuture<Liveness>()
        host.ensureAlive { liveness, _ -> silent.complete(liveness) }
        assertEquals(Liveness.SILENT, silent.get())
        waitFor("a new connection") { rex.controls.size == 2 }
    }

    @Test
    fun reconnectingReplacesAConnectionThatDoesNotAnswerEvenWhileItOwesAnswers() {
        host.connect()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        val answer = rex.handle
        val never = CompletableFuture<JsonElement>()
        rex.handle = { method, payload ->
            when (method) {
                "server.version", "$TERMINAL_CREATOR.format" -> never
                else -> answer(method, payload)
            }
        }
        // A call stuck on a connection that died, as after the phone changed networks.
        val stuck = Thread { runCatching { kotlinx.coroutines.runBlocking { host.blockMethod("s1", "b1", "format", timeoutMs = 60_000) } } }
        stuck.start()
        waitFor("the stuck call") { rex.count("$TERMINAL_CREATOR.format") == 1 }

        val result = CompletableFuture<Liveness>()
        host.ensureAlive(force = true) { liveness, _ -> result.complete(liveness) }
        assertEquals(Liveness.SILENT, result.get())
        waitFor("a new connection") { rex.controls.size == 2 }
        stuck.join(5_000)
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; blocks ${host.state.value.blocks}, alerts $alerts")
            Thread.sleep(10)
        }
    }
}
