package com.jeffarese.rexmobile.terminal

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [InputQueue] against a fake server that answers when told to, and [AnswerEnter]'s decisions. */
class InputQueueTest {
    private class FakeLink : InputQueue.Link {
        val log = mutableListOf<String>()
        /** Event calls not answered yet; answered at once unless [holdEvents]. */
        val unanswered = mutableListOf<CompletableJob>()
        var holdEvents = false
        /** What input_mode answers, in turn: canonical or not, or null for a server that can't tell. */
        val modes = ArrayDeque<Boolean?>()
        val cursor = ArrayDeque<CursorLine?>()
        /** Whether the program in front relays input, as ssh does; null when unknown. */
        var relays: Boolean? = false

        override suspend fun event(type: String, payload: JsonObject): Job {
            val what = (payload["key"] ?: payload["text"]) as JsonPrimitive
            log += listOfNotNull(type, what.content, (payload["action"] as? JsonPrimitive)?.content).joinToString(" ")
            return Job().also { if (holdEvents) unanswered += it else it.complete() }
        }

        override suspend fun write(text: String) {
            log += "write $text"
        }

        override suspend fun awaitOthers() = Unit

        override suspend fun canonical(): Boolean? {
            log += "input_mode"
            return modes.removeFirstOrNull()
        }

        override suspend fun cursorLine(): CursorLine? = cursor.removeFirstOrNull()

        override suspend fun relaysInput(): Boolean? {
            log += "process"
            return relays
        }

        fun answerAll() {
            unanswered.forEach { it.complete() }
            unanswered.clear()
        }
    }

    private fun withQueue(test: suspend CoroutineScope.(InputQueue, FakeLink) -> Unit) = runBlocking {
        val link = FakeLink()
        val queue = InputQueue(link)
        val job = launch { queue.run() }
        try {
            test(queue, link)
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun settle() = repeat(50) { yield() }

    private fun keys(vararg names: String) = names.flatMap { listOf("key $it press", "key $it release") }

    @Test
    fun keysGoOutWithoutWaitingForAnswers() = withQueue { queue, link ->
        link.holdEvents = true
        listOf(RemoteKey.DOWN, RemoteKey.DOWN, RemoteKey.ENTER).forEach { queue.offer(InputQueue.Item.Key(it)) }
        settle()
        assertEquals(keys("arrow_down", "arrow_down", "enter"), link.log)
        assertEquals(6, link.unanswered.size)
    }

    @Test
    fun textWaitsUntilTheKeysBeforeItAreAnswered() = withQueue { queue, link ->
        link.holdEvents = true
        queue.offer(InputQueue.Item.Key(RemoteKey.ESCAPE))
        queue.offer(InputQueue.Item.Bytes("hi".toByteArray()))
        settle()
        assertEquals(keys("escape"), link.log)
        link.answerAll()
        settle()
        assertEquals(keys("escape") + "write hi", link.log)
    }

    @Test
    fun keyAfterAPasteWaitsForIt() = withQueue { queue, link ->
        link.holdEvents = true
        queue.offer(InputQueue.Item.Paste("/effort low"))
        queue.offer(InputQueue.Item.Key(RemoteKey.ENTER))
        settle()
        assertEquals(listOf("paste /effort low"), link.log)
        link.answerAll()
        delay(150)
        assertEquals(listOf("paste /effort low") + keys("enter"), link.log)
    }

    @Test
    fun lineReadGetsEnter() = withQueue { queue, link ->
        // zsh's `read`: the terminal reads a line at a time before and after the answer.
        link.modes += listOf(true, true)
        queue.offer(InputQueue.Item.Answer("y"))
        queue.offer(InputQueue.Item.Bytes("ls".toByteArray()))
        settle()
        // Nothing typed after the card slips in between the answer and its Enter.
        assertEquals(listOf("input_mode", "write y", "input_mode") + keys("enter") + "write ls", link.log)
    }

    @Test
    fun singleKeyReadGetsNoEnter() = withQueue { queue, link ->
        // zsh's `read -q`: a key at a time while it waits. Afterwards the shell may be back in line
        // mode for the next command, so the mode after the answer isn't even asked.
        link.modes += listOf(false, true)
        queue.offer(InputQueue.Item.Answer("y"))
        settle()
        assertEquals(listOf("input_mode", "process", "write y"), link.log)
    }

    @Test
    fun behindSshTheScreenDecides() = withQueue { queue, link ->
        // apt over ssh: the terminal here is raw because ssh made it so, and the far side echoes the
        // answer and waits for Enter.
        link.modes += listOf(false)
        link.relays = true
        link.cursor += CursorLine(5, 34, "Do you want to continue? [Y/n]")
        link.cursor += CursorLine(5, 35, "Do you want to continue? [Y/n] y")
        queue.offer(InputQueue.Item.Answer("y"))
        delay(InputQueue.ECHO_WAIT_MS + 150)
        assertEquals(listOf("input_mode", "process", "write y") + keys("enter"), link.log)
    }

    @Test
    fun aFailedFirstModeReadLetsTheScreenDecide() = withQueue { queue, link ->
        // `read -q` and then a second question: the mode read before the answer fails, and by the
        // time of a read after it the shell is back in line mode, which must not decide.
        link.modes += listOf(null, true)
        link.cursor += CursorLine(5, 24, "Delete build? (y/n)")
        link.cursor += CursorLine(6, 24, "Delete cache? (y/n)")
        queue.offer(InputQueue.Item.Answer("y"))
        delay(InputQueue.ECHO_WAIT_MS + 150)
        assertEquals(listOf("input_mode", "write y"), link.log)
    }

    @Test
    fun noEnterIfTheModeChangesWithTheAnswer() = withQueue { queue, link ->
        link.modes += listOf(true, false)
        queue.offer(InputQueue.Item.Answer("yes"))
        settle()
        assertEquals(listOf("input_mode", "write yes", "input_mode"), link.log)
    }

    @Test
    fun withoutTheModeTheScreenDecides() = withQueue { queue, link ->
        // The answer echoed after the question, the cursor still on its row: a line read.
        link.modes += listOf(null, null)
        link.cursor += CursorLine(5, 16, "Continue? [y/N]")
        link.cursor += CursorLine(5, 17, "Continue? [y/N] y")
        queue.offer(InputQueue.Item.Answer("y"))
        delay(InputQueue.ECHO_WAIT_MS + 150)
        assertEquals(listOf("input_mode", "write y") + keys("enter"), link.log)
    }

    @Test
    fun withoutTheModeAnAnswerThatMovedOnGetsNoEnter() = withQueue { queue, link ->
        link.modes += listOf(null, null)
        link.cursor += CursorLine(5, 22, "Overwrite file? (y/n)")
        link.cursor += CursorLine(7, 2, "❯")
        queue.offer(InputQueue.Item.Answer("y"))
        delay(InputQueue.ECHO_WAIT_MS + 150)
        assertEquals(listOf("input_mode", "write y"), link.log)
    }

    @Test
    fun decisionFromTheMode() {
        assertEquals(true, AnswerEnter.fromMode(true, true))
        assertEquals(false, AnswerEnter.fromMode(false, true))
        assertEquals(false, AnswerEnter.fromMode(true, false))
        assertEquals(false, AnswerEnter.fromMode(false, null))
        assertEquals(true, AnswerEnter.fromMode(true, null))
        assertNull(AnswerEnter.fromMode(null, true))
        assertNull(AnswerEnter.fromMode(null, false))
        assertNull(AnswerEnter.fromMode(null, null))
    }

    @Test
    fun decisionFromTheScreen() {
        val question = CursorLine(3, 16, "Continue? [y/N]")
        assertTrue(AnswerEnter.fromScreen(question, CursorLine(3, 17, "Continue? [y/N] y"), "y"))
        val ssh = CursorLine(4, 23, "yes/no/[fingerprint])?")
        assertTrue(AnswerEnter.fromScreen(ssh, CursorLine(4, 26, "yes/no/[fingerprint])? yes"), "yes"))
        // Not echoed, as zsh's `read -q`, or the cursor moved on to what came next.
        assertFalse(AnswerEnter.fromScreen(question, question, "y"))
        assertFalse(AnswerEnter.fromScreen(question, CursorLine(4, 0, ""), "y"))
        // Something else was typed.
        assertFalse(AnswerEnter.fromScreen(question, CursorLine(3, 17, "Continue? [y/N] n"), "y"))
        assertFalse(AnswerEnter.fromScreen(null, CursorLine(3, 17, "Continue? [y/N] y"), "y"))
    }
}
