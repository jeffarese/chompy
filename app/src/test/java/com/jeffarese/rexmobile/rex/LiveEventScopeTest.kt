package com.jeffarese.rexmobile.rex

import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The app asks only for session events. Its blocks' events must still come, both in a session
 * attached at bootstrap and in one created after it. Skipped unless REX_LIVE_HOST names a server;
 * it creates two sessions and destroys them at the end.
 */
class LiveEventScopeTest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()

    @Test
    fun blockEventsComeForSessionsAttachedAtBootstrapAndAfter() = runBlocking {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = ControlConnection.normalizeHostUrl(address!!)
        val maker = HostConnection(HostConfig(label = "maker", url = url), http, scope) {}
        val watcher = HostConnection(HostConfig(label = "watcher", url = url), http, scope) {}
        val tag = "itest-" + Random.nextInt(100_000, 999_999)
        val made = mutableListOf<String>()
        try {
            maker.connect()
            withTimeout(20_000) { maker.state.first { it.status == ConnStatus.ONLINE } }
            val before = maker.newSession("$tag-before")!!.also { made += it }

            // Attached at bootstrap, then one created once it is online.
            watcher.connect()
            withTimeout(20_000) { watcher.state.first { it.status == ConnStatus.ONLINE && before in it.views } }
            val after = watcher.newSession("$tag-after")!!.also { made += it }

            for (sid in listOf(before, after)) {
                val bid = withTimeout(10_000) {
                    watcher.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }
                }.views.getValue(sid).windows!!.first().blocks.first().blockId
                // Its meta read first, so what follows can only come as events.
                withTimeout(10_000) { watcher.state.first { it.blocks[bid]?.process != null } }
                delay(800) // let the shell draw its prompt
                val title = "$tag-${if (sid == before) "before" else "after"}"
                // The sleep keeps the shell's prompt from setting a title of its own meanwhile.
                watcher.blockMethod(sid, bid, "write", buildJsonObject {
                    put("data", "printf '\\033]2;$title\\007\\033]7501;state=working:id=$tag\\033\\\\'; sleep 8\r")
                })
                withTimeout(10_000) {
                    watcher.state.first { st -> st.blocks[bid]?.let { m -> m.title == title && m.statuses.any { it.id == tag } } == true }
                }
            }
        } finally {
            made.forEach { sid -> runCatching { maker.call("session.destroy", buildJsonObject { put("session_id", sid) }) } }
            maker.disconnect()
            watcher.disconnect()
            delay(300)
            scope.cancel()
        }
    }
}
