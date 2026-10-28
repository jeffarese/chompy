package com.jeffarese.rexmobile.ui.onboarding

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.MainActivity
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun clear() {
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        // An empty saved list, so the store doesn't seed this build's preset hosts.
        context.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().putString("list", "[]").commit()
    }

    @Test
    fun firstLaunchWithNoHostsShowsTheWelcomeUntilItIsDone() {
        val firstRun = FirstRun(context)
        assertTrue(firstRun.shouldShow(emptyList()))
        // Leaving the app mid-way shows it again next time.
        assertTrue(FirstRun(context).shouldShow(emptyList()))
        firstRun.markDone()
        assertFalse(FirstRun(context).shouldShow(emptyList()))
    }

    @Test
    fun presetHostsSkipTheWelcomeForGood() {
        val host = HostConfig(label = "Studio", url = "https://studio-rex.tail1234.ts.net")
        assertFalse(FirstRun(context).shouldShow(listOf(host)))
        // Removing every host later doesn't bring it back.
        assertFalse(FirstRun(context).shouldShow(emptyList()))
    }

    @Test
    fun launchWithNoHostsOpensTheWelcomeAndSkipLandsOnHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onNodeWithText("Get started").assertExists()
            compose.onNodeWithText("Skip").performClick()
            compose.onNodeWithText("Connect to a Rex server").assertExists()
            assertTrue(FirstRun(context).done)
        }
    }

    @Test
    fun addressesAreNormalizedOrRejected() {
        assertEquals(AddressCheck.Ok("https://mac-rex.tail1234.ts.net"), checkAddress("  mac-rex.tail1234.ts.net "))
        assertEquals(AddressCheck.Ok("https://mac-rex.tail1234.ts.net"), checkAddress("https://mac-rex.tail1234.ts.net/"))
        assertEquals(AddressCheck.Ok("http://192.168.1.4:8080"), checkAddress("http://192.168.1.4:8080"))
        assertTrue(checkAddress("") is AddressCheck.Invalid)
        assertTrue(checkAddress("my mac") is AddressCheck.Invalid)
        // java.net.URI drops such a host instead of failing: it used to become https://null.
        assertTrue(checkAddress("my_mac.ts.net") is AddressCheck.Invalid)
    }

    @Test
    fun plainHttpIsOnlyForTailnetAndLocalAddresses() {
        listOf("http://mac-rex.tail1234.ts.net:7297", "http://100.101.102.103:7297", "http://[fd7a:115c:a1e0::1]:7297", "http://studio:7297", "http://10.0.0.2")
            .forEach { assertTrue(it, checkAddress(it) is AddressCheck.Ok) }
        listOf("http://example.com", "http://8.8.8.8:7297", "http://100.128.0.1")
            .forEach { assertTrue(it, checkAddress(it) is AddressCheck.Invalid) }
        assertTrue(checkAddress("https://example.com") is AddressCheck.Ok)
    }

    @Test
    fun aPairingCodeGivesItsAddress() {
        assertEquals("https://laptop-rex.tail1234.ts.net", addressFromCode("https://laptop-rex.tail1234.ts.net"))
        assertEquals("https://mac-rex.ts.net:7297", addressFromCode("Pair with https://mac-rex.ts.net:7297/ please"))
        assertEquals("https://mac-rex.ts.net", addressFromCode("rex://pair?url=https%3A%2F%2Fmac-rex.ts.net"))
        assertEquals("https://mac-rex.ts.net", addressFromCode("mac-rex.ts.net"))
        assertEquals(null, addressFromCode("not an address"))
    }

    @Test
    fun aTailnetAddressWaitsForTailscale() = runBlocking {
        var tried = false
        var tailscale = Tailscale.OFF
        val step = AddHostStep(HostStore(context), { tailscale }) { url -> tried = true; Probe.Connected(HostConfig(label = "x", url = url), null, 0) }
        assertEquals(Probe.NeedsTailscale("https://mac-rex.tail1234.ts.net", Tailscale.OFF), step.connect("mac-rex.tail1234.ts.net", ""))
        assertFalse(tried)
        // Not a tailnet address: Tailscale doesn't matter.
        assertTrue(step.connect("http://192.168.1.4:8080", "") is Probe.Connected)
        tailscale = Tailscale.ON
        assertTrue(step.connect("mac-rex.tail1234.ts.net", "") is Probe.Connected)
        assertTrue(isTailnetAddress("http://100.64.0.1:7297"))
        assertFalse(isTailnetAddress("http://100.128.0.1"))
    }

    @Test
    fun aHostAlreadyAddedSaysSo() = runBlocking {
        val store = HostStore(context)
        store.add("Studio", "https://mac-rex.ts.net:7297")
        val step = AddHostStep(store) { url -> Probe.Connected(HostConfig(label = "x", url = url), null, 0) }
        assertEquals(Probe.Invalid("Already added as Studio"), step.connect("mac-rex.ts.net", ""))
    }

    @Test
    fun anHttpsAddressWithoutAPortAlsoTries7297() {
        assertEquals(listOf("https://mac-rex.ts.net", "https://mac-rex.ts.net:7297"), AddHostStep.candidates("https://mac-rex.ts.net"))
        assertEquals(listOf("https://mac-rex.ts.net:443"), AddHostStep.candidates("https://mac-rex.ts.net:443"))
        assertEquals(listOf("http://100.64.0.1"), AddHostStep.candidates("http://100.64.0.1"))
    }

    @Test
    fun aHostOnlyOn7297IsSavedWithItsPort() = runBlocking {
        val store = HostStore(context)
        val step = AddHostStep(store) { url ->
            if (url.endsWith(":7297")) Probe.Connected(HostConfig(label = "x", url = url), "0.0.1", 0)
            else Probe.Failed(url, Problem.NO_ANSWER, "refused")
        }
        val result = step.connect("box.tail1234.ts.net", "") as Probe.Connected
        assertEquals("https://box.tail1234.ts.net:7297", result.host.url)
        assertEquals(listOf("https://box.tail1234.ts.net:7297"), store.hosts.value.map { it.url })
    }

    @Test
    fun thePortAsTypedWinsWhenBothAnswer() = runBlocking {
        val store = HostStore(context)
        val step = AddHostStep(store) { url ->
            // The default port is the slower one to answer.
            if (!url.endsWith(":7297")) kotlinx.coroutines.delay(200)
            Probe.Connected(HostConfig(label = "x", url = url), "0.0.1", 0)
        }
        assertEquals("https://box.tail1234.ts.net", (step.connect("box.tail1234.ts.net", "") as Probe.Connected).host.url)
    }

    @Test
    fun theFailureOfAServerThatAnsweredIsShown() = runBlocking {
        val step = AddHostStep(HostStore(context)) { url ->
            if (url.endsWith(":7297")) Probe.Failed(url, Problem.TURNED_AWAY, "Expected HTTP 101 response but was '403 Forbidden'")
            else Probe.Failed(url, Problem.NO_ANSWER, "refused")
        }
        assertEquals(Problem.TURNED_AWAY, (step.connect("box.tail1234.ts.net", "") as Probe.Failed).problem)
    }

    @Test
    fun failuresAreReadFromTheirMessages() {
        assertEquals(Problem.NOT_FOUND, classify("Unable to resolve host \"mac-rex.tail1234.ts.net\": No address associated with hostname"))
        assertEquals(Problem.TURNED_AWAY, classify("Expected HTTP 101 response but was '403 Forbidden'"))
        assertEquals(Problem.NOT_REX, classify("Expected HTTP 101 response but was '404 Not Found'"))
        assertEquals(Problem.NOT_SECURE, classify("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."))
        assertEquals(Problem.NO_ANSWER, classify("failed to connect to mac-rex.tail1234.ts.net/100.64.0.1 (port 443) after 10000ms"))
        assertEquals(Problem.OTHER, classify(null))
        assertEquals("Use the full address, ending in .ts.net.", hint(Problem.NOT_SECURE, "https://mac-rex", null))
    }

    @Test
    fun aProxyErrorShowsItsStatus() {
        val reason = "Expected HTTP 101 response but was '502 Bad Gateway'"
        assertEquals(Problem.PROXY_ERROR, classify(reason))
        assertTrue(hint(classify(reason), "https://mac-rex.ts.net", reason).startsWith("mac-rex.ts.net answered 502 Bad Gateway"))
        val notRex = "Expected HTTP 101 response but was '404 Not Found'"
        assertTrue(hint(classify(notRex), "https://mac-rex.ts.net", notRex).contains("(404 Not Found)"))
    }

    @Test
    fun aProxyThatAnswers502IsAProxyError() = runBlocking {
        val result = withHttpServer("502 Bad Gateway") { testConnection(http, it, timeoutMs = 5000) } as Probe.Failed
        assertEquals(result.detail, Problem.PROXY_ERROR, result.problem)
    }

    @Test
    fun aHostThatAnswersIsAddedWithItsName() = runBlocking {
        val store = HostStore(context)
        val step = AddHostStep(store) { url -> Probe.Connected(HostConfig(label = "x", url = url), "0.0.1", 2) }

        val named = step.connect("mac-rex.tail1234.ts.net", " Studio ") as Probe.Connected
        assertEquals("Studio", named.host.label)
        val unnamed = step.connect("laptop-rex.tail1234.ts.net", "") as Probe.Connected
        assertEquals("laptop-rex", unnamed.host.label)
        assertEquals(listOf("https://mac-rex.tail1234.ts.net", "https://laptop-rex.tail1234.ts.net"), store.hosts.value.map { it.url })
    }

    @Test
    fun aHostThatDoesNotAnswerIsAddedOnlyWhenAsked() = runBlocking {
        val store = HostStore(context)
        val step = AddHostStep(store) { url -> Probe.Failed(url, Problem.NO_ANSWER, "timeout") }

        assertTrue(step.connect("mac-rex.tail1234.ts.net", "") is Probe.Failed)
        assertTrue(store.hosts.value.isEmpty())
        assertEquals("mac-rex", step.addAnyway("mac-rex.tail1234.ts.net", "")?.label)
        assertEquals(1, store.hosts.value.size)
    }

    @Test
    fun aBadAddressIsNotTried() = runBlocking {
        var tried = false
        val step = AddHostStep(HostStore(context)) { tried = true; Probe.Idle }
        assertTrue(step.connect("my mac", "") is Probe.Invalid)
        assertFalse(tried)
    }

    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()

    @Test
    fun aClosedPortIsNoAnswer() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val result = testConnection(http, "http://127.0.0.1:$port", timeoutMs = 5000) as Probe.Failed
        assertEquals(result.detail, Problem.NO_ANSWER, result.problem)
    }

    @Test
    fun aServerThatRefusesThePhoneIsTurnedAway() = runBlocking {
        val result = withHttpServer("403 Forbidden") { testConnection(http, it, timeoutMs = 5000) } as Probe.Failed
        assertEquals(result.detail, Problem.TURNED_AWAY, result.problem)
    }

    @Test
    fun aWebServerIsNotRex() = runBlocking {
        val result = withHttpServer("404 Not Found") { testConnection(http, it, timeoutMs = 5000) } as Probe.Failed
        assertEquals(result.detail, Problem.NOT_REX, result.problem)
    }

    /** A one-request HTTP server that answers [status], as a Rex server does a caller it won't let in. */
    private suspend fun <T> withHttpServer(status: String, block: suspend (url: String) -> T): T {
        val server = ServerSocket(0)
        val worker = thread {
            runCatching {
                server.accept().use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    s.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
        return try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            worker.join(2000)
        }
    }
}
