package com.jeffarese.rexmobile.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Finding, downloading and keeping the app's updates, against a fake GitHub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var dir: File

    /** The newest version GitHub has, or null for none; and whether GitHub is reachable. */
    @Volatile private var latest: String? = "0.3.2"
    @Volatile private var online = true
    @Volatile private var metered = false
    @Volatile private var problem: String? = null
    private var now = 1_000_000_000L

    private val apk = ByteArray(50_000) { it.toByte() }

    private val github = Interceptor { chain ->
        val request = chain.request()
        if (!online) throw java.io.IOException("No connection")
        val body = when {
            request.url.encodedPath.endsWith("/releases/latest") -> latest?.let { v ->
                """{"tag_name":"v$v","body":"- New in $v","assets":[{"name":"rex-mobile-$v.apk","url":"https://api.github.com/a/$v","size":${apk.size}}]}"""
                    .toByteArray()
            }
            else -> apk
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200).message("")
            .body((body ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType())).build()
    }

    private fun updater(current: String = "0.3.1") = Updater(
        context, scope, ReleaseFeed("o/r", "", OkHttpClient.Builder().addInterceptor(github).build()),
        currentVersion = current, dir = dir, verify = { problem }, metered = { metered }, clock = { now },
    )

    private fun <T> Updater.await(timeoutMs: Long = 5_000, test: (UpdateState) -> T?): T = runBlocking {
        withTimeout(timeoutMs) { state.first { test(it) != null } }.let { test(it)!! }
    }

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "updates-test").apply { deleteRecursively() }
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun downloadsANewerVersionOnWifiAndKeepsItReady() {
        val u = updater()
        u.onResume()
        val ready = u.await { it as? UpdateState.Ready }
        assertEquals("0.3.2", ready.release.version)
        assertTrue(ready.apk.exists())
        assertEquals(apk.size.toLong(), ready.apk.length())
        assertEquals(now, u.lastChecked)

        // After the app closes, it is ready again without asking GitHub.
        online = false
        val again = updater()
        assertEquals(ready, again.state.value)
    }

    @Test
    fun onAMeteredNetworkItWaitsToBeAsked() {
        metered = true
        val u = updater()
        u.onResume()
        val available = u.await { it as? UpdateState.Available }
        assertFalse(File(dir, "rex-0.3.2.apk").exists())

        u.download(available.release)
        u.await { it as? UpdateState.Ready }
    }

    @Test
    fun aCheckTheUserAsksForDownloadsOnAnyNetwork() {
        metered = true
        val u = updater()
        u.check()
        u.await { it as? UpdateState.Ready }
    }

    @Test
    fun theSameOrAnOlderVersionIsUpToDate() {
        latest = "0.3.1"
        updater().apply { check() }.await { it as? UpdateState.UpToDate }
        latest = null
        updater().apply { check() }.await { it as? UpdateState.UpToDate }
    }

    @Test
    fun checksOncePerLaunchThenEveryFewHours() {
        latest = "0.3.1"
        val u = updater()
        u.onResume()
        u.await { it as? UpdateState.UpToDate }
        latest = "0.3.2"
        u.onResume()
        Thread.sleep(200)
        assertEquals(UpdateState.UpToDate, u.state.value)

        now += Updater.CHECK_EVERY_MS + 1
        u.onResume()
        u.await { it as? UpdateState.Ready }
    }

    @Test
    fun installsOnComingBackOnceAllowed() {
        val u = updater()
        u.onResume()
        u.await { it as? UpdateState.Ready }
        u.permissionIntent()
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        u.onResume()
        // Android's answer, such as its confirmation, comes for the session being made: the update waits for it.
        Thread.sleep(500)
        assertTrue("${u.state.value}", u.state.value is UpdateState.Installing)
        assertEquals(1, context.packageManager.packageInstaller.mySessions.size)
    }

    @Test
    fun onlyAFailedCheckTheUserAskedForIsShown() {
        online = false
        val u = updater()
        u.check(manual = false)
        Thread.sleep(300)
        assertEquals(UpdateState.Idle, u.state.value)
        assertEquals(0L, u.lastChecked)

        u.check()
        assertEquals("No connection", u.await { it as? UpdateState.Failed }.message)
    }

    @Test
    fun aDownloadThatIsntRexIsThrownAway() {
        problem = "The download isn't signed with Chompy's key"
        val u = updater()
        u.check()
        val available = u.await { it as? UpdateState.Available }
        assertEquals(problem, available.error)
        assertFalse(File(dir, "rex-0.3.2.apk").exists())
    }

    @Test
    fun theNewVersionShowsWhatItBroughtOnce() {
        updater().apply { check() }.await { it as? UpdateState.Ready }

        // Installed: the next launch runs 0.3.2.
        val after = updater(current = "0.3.2")
        assertEquals("0.3.2", after.justUpdated.value?.version)
        assertEquals("- New in 0.3.2", after.justUpdated.value?.notes)
        // Its download is gone.
        assertFalse(File(dir, "rex-0.3.2.apk").exists())

        after.dismissWhatsNew()
        assertNull(after.justUpdated.value)
        assertNull(updater(current = "0.3.2").justUpdated.value)
    }

    @Test
    fun aFirstLaunchHasNothingToShow() {
        assertNull(updater(current = "0.3.2").justUpdated.value)
    }

    @Test
    fun puttingOffAVersionLastsADay() {
        val u = updater()
        u.check()
        val ready = u.await { it as? UpdateState.Ready }
        u.snooze(ready.release)
        assertEquals("0.3.2", u.snoozed.value)
        assertEquals("0.3.2", updater().snoozed.value)

        now += Updater.SNOOZE_MS + 1
        assertNull(updater().snoozed.value)
    }

    @Test
    fun aBuildWithoutAFeedDoesNothing() {
        val u = Updater(context, scope, null, currentVersion = "0.3.1", dir = dir)
        assertFalse(u.enabled)
        u.onResume()
        u.check()
        assertEquals(UpdateState.Idle, u.state.value)
    }
}
