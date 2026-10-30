package com.jeffarese.rexmobile.model

import android.content.ComponentCallbacks2
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.terminal.RemoteTerminal
import com.jeffarese.rexmobile.terminal.showOnScreen
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.time.Duration

/** Which terminals the app opens ahead of time, keeps and closes, against a fake server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppModelTerminalsTest {
    private val blocks = (1..8).map { "b$it" }
    private val rex = FakeRex(*blocks.toTypedArray())
    // An empty saved list, so the store doesn't seed this build's preset hosts beside the fake one.
    private val context: Context = ApplicationProvider.getApplicationContext<Context>().also {
        it.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().putString("list", "[]").commit()
    }
    private val model = AppModel(context, rex)
    private val config = model.hostStore.add("fake", "fake.test")

    @After
    fun tearDown() {
        blocks.forEach(model::dropTerminal)
        model.connections.value.forEach { it.disconnect() }
        model.scope.cancel()
    }

    private fun host(): HostConnection {
        waitFor("the host's connection") { model.connection(config.id) != null }
        return model.connection(config.id)!!.also { h -> waitFor("online") { h.state.value.status == ConnStatus.ONLINE } }
    }

    private fun network(type: Int) {
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(info)
    }

    /** Open a block's terminal and show it, as the terminal screen does. */
    private fun look(host: HostConnection, blockId: String): RemoteTerminal =
        model.terminal(host, "s1", blockId).also { showOnScreen(it) }

    @Test
    fun nothingOpensAheadOfTimeOnMobileData() {
        model.onForeground()
        val host = host()
        network(ConnectivityManager.TYPE_MOBILE)
        model.prewarm(host, "s1", "b2")
        pump(500)
        assertEquals(0, rex.datas.size)

        network(ConnectivityManager.TYPE_WIFI)
        model.prewarm(host, "s1", "b2")
        waitFor("the stream") { rex.datas.size == 1 }
    }

    @Test
    fun openingAheadOfTimeNeverClosesATerminalLookedAt() {
        model.onForeground()
        network(ConnectivityManager.TYPE_WIFI)
        val host = host()
        val looked = (1..5).map { look(host, "b$it") }
        model.visibleBlockId = "b5"
        // The sixth fills the room; the seventh takes the sixth's place, not that of one looked at.
        model.prewarm(host, "s1", "b6")
        model.prewarm(host, "s1", "b7")
        pump(200)
        // Open, all five still the same.
        looked.forEachIndexed { i, t -> assertSame(t, model.terminal(host, "s1", "b${i + 1}")) }

        // All six looked at: nothing opens ahead of time.
        val seventh = look(host, "b7")
        val streams = rex.datas.size
        model.prewarm(host, "s1", "b8")
        pump(200)
        assertEquals(streams, rex.datas.size)
        assertSame(looked[0], model.terminal(host, "s1", "b1"))
        assertSame(seventh, model.terminal(host, "s1", "b7"))
    }

    @Test
    fun lowMemoryClosesPausedTerminalsNotOnScreen() {
        model.onForeground()
        val host = host()
        val onScreen = look(host, "b1")
        val paused = look(host, "b2")
        val streaming = look(host, "b3")
        model.visibleBlockId = "b1"
        onScreen.pause()
        paused.pause()

        // Out of sight, they only give back what they can.
        model.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertSame(paused, model.terminal(host, "s1", "b2"))
        paused.pause()

        @Suppress("DEPRECATION")
        model.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertSame(onScreen, model.terminal(host, "s1", "b1"))
        assertSame(streaming, model.terminal(host, "s1", "b3"))
        assertNotSame(paused, model.terminal(host, "s1", "b2"))
    }

    /** Robolectric runs the main looper only when told, so keep it going while real time passes. */
    private fun pump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(25)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            pump(25)
        }
    }
}
