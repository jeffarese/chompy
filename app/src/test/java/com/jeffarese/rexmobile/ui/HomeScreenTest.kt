package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.RexMobileApp
import com.jeffarese.rexmobile.model.ConnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val model get() = ApplicationProvider.getApplicationContext<RexMobileApp>().model

    private fun clearHosts() = model.hostStore.hosts.value.forEach { model.hostStore.remove(it.id) }

    private fun show() = rule.setContent { RexTheme { HomeScreen(model, onOpen = {}, onHosts = {}) } }

    @Test
    fun searchOpenedFarDownTheListShowsItsBox() {
        clearHosts()
        // Enough hosts that the list scrolls; none of them answers.
        repeat(12) { model.hostStore.add("Host $it", "127.0.0.1:${9000 + it}") }
        show()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        rule.onNodeWithText("Chompy").assertDoesNotExist()

        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        clearHosts()
    }

    @Test
    fun searchComesBackWithoutTheKeyboard() {
        var home by mutableStateOf(true)
        // As in MainActivity: Home leaves composition while another screen is up, and its state is kept.
        rule.setContent {
            RexTheme {
                val saved = rememberSaveableStateHolder()
                if (home) saved.SaveableStateProvider("home") { HomeScreen(model, onOpen = {}, onHosts = {}) }
                else Box(Modifier.fillMaxSize())
            }
        }
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasSetTextAction()).assertIsFocused()

        home = false
        rule.waitForIdle()
        home = true

        // Still searching, but the field doesn't take the focus (and bring the keyboard up) again.
        rule.onNode(hasSetTextAction()).assertExists().assertIsNotFocused()
    }

    @Test
    fun noHostsShowsTheWayToAddOneWithoutAHostCount() {
        clearHosts()
        show()
        rule.onNodeWithText("Connect to a Rex server").assertExists()
        rule.onNodeWithText("0/0 hosts").assertDoesNotExist()
    }

    @Test
    fun anUnreachableHostSaysWhyAndOffersARetry() {
        clearHosts()
        val port = ServerSocket(0).use { it.localPort }
        val host = model.hostStore.add("Studio", "127.0.0.1:$port")
        show()
        rule.waitUntil(5_000) { model.connection(host.id) != null }
        val conn = model.connection(host.id)!!
        conn.connect()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Can't reach this host").fetchSemanticsNodes().isNotEmpty() }
        // What to check rather than OkHttp's words, which follow in small type.
        rule.onNodeWithText("127.0.0.1 didn't answer", substring = true).assertExists()
        rule.onNodeWithText("Failed to connect", substring = true).assertExists()
        rule.onNodeWithText("Trying again…").assertExists()
        rule.onNodeWithText("Retry").assertExists().performClick()
        // The same card while it tries again, not a bare "Connecting…".
        rule.onNodeWithText("Can't reach this host").assertExists()
        rule.onNodeWithText("Connecting…").assertDoesNotExist()
        conn.disconnect()
    }

    @Test
    fun theSearchBoxsClearButtonIsAFingersWidth() {
        show()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("claude")
        rule.onNodeWithContentDescription("Clear").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        rule.onNodeWithText("claude").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp")
    fun onAWideScreenHomeIsAColumnInTheMiddle() {
        clearHosts()
        show()
        val card = rule.onNodeWithText("Connect to a Rex server").getUnclippedBoundsInRoot()
        // 640dp wide in the middle of 1000dp, and the card's own 16dp margin and 20dp padding inside that.
        assertEquals(180.dp + 36.dp, card.left)
        val bar = rule.onNodeWithContentDescription("Search").getUnclippedBoundsInRoot()
        assertEquals(1000.dp - 180.dp - 18.dp, bar.right)
    }

    @Test
    fun aFailedCallSaysWhy() {
        assertEquals("not connected", failureReason(IllegalStateException("whatever"), ConnStatus.OFFLINE))
        assertEquals("not connected", failureReason(null, ConnStatus.CONNECTING))
        assertEquals("boom", failureReason(IllegalStateException("boom"), ConnStatus.ONLINE))
        assertNull(failureReason(null, ConnStatus.ONLINE))
    }
}
