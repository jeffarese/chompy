package com.jeffarese.rexmobile.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.model.ReleaseFeed
import com.jeffarese.rexmobile.model.UpdateState
import com.jeffarese.rexmobile.model.Updater
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The update sheet draws, with its image, for a version on its way and for the one just installed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class UpdatesTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(context.cacheDir, "updates-ui-test")
    private val apk = ByteArray(50_000) { it.toByte() }

    private val github = Interceptor { chain ->
        val request = chain.request()
        val body = if (request.url.encodedPath.endsWith("/releases/latest"))
            """{"tag_name":"v0.3.2","body":"- New in 0.3.2","assets":[{"name":"rex-mobile-0.3.2.apk","url":"https://api.github.com/a/0.3.2","size":${apk.size}}]}"""
                .toByteArray()
        else apk
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("")
            .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
    }

    private fun updater(current: String, metered: Boolean) = Updater(
        context, scope, ReleaseFeed("o/r", OkHttpClient.Builder().addInterceptor(github).build()),
        currentVersion = current, dir = dir, verify = { null }, metered = { metered },
    )

    private fun <T> Updater.await(test: (UpdateState) -> T?): T = runBlocking {
        withTimeout(5_000) { state.first { test(it) != null } }.let { test(it)!! }
    }

    @Before
    fun setUp() {
        dir.deleteRecursively()
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aVersionOnItsWayShowsItsSheet() {
        // On mobile data it waits to be downloaded.
        val u = updater(current = "0.3.1", metered = true).apply { check(manual = false) }
        u.await { it as? UpdateState.Available }
        rule.setContent { RexTheme { UpdateSheet(u, onDismiss = {}) } }
        rule.onNodeWithText("Chompy 0.3.2").assertExists()
        rule.onNodeWithText("New in 0.3.2").assertExists()
    }

    @Test
    fun theVersionJustInstalledShowsItsSheet() {
        updater(current = "0.3.1", metered = false).apply { check() }.await { it as? UpdateState.Ready }
        val after = updater(current = "0.3.2", metered = false)
        rule.setContent { RexTheme { UpdateSheet(after, onDismiss = {}) } }
        rule.onNodeWithText("Updated to 0.3.2").assertExists()
    }
}
