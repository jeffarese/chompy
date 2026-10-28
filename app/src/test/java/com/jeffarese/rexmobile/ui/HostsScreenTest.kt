package com.jeffarese.rexmobile.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.RexMobileApp
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.ui.onboarding.AddressCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class HostsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
    private val store = model.hostStore

    @Before
    fun clear() = store.hosts.value.forEach { store.remove(it.id) }

    @Test
    fun importNeedsAHostToAsk() {
        rule.setContent { RexTheme { HostsScreen(model, onBack = {}) } }
        rule.onNodeWithText("Import hosts from your servers").assertDoesNotExist()
        store.add("Studio", "studio-rex.tail1234.ts.net")
        rule.onNodeWithText("Import hosts from your servers").assertExists()
    }

    // The dialog itself isn't driven here: under Robolectric an AlertDialog with a text field never goes idle.

    private val studio = HostConfig(label = "Studio", url = "https://studio-rex.tail1234.ts.net")
    private val laptop = HostConfig(label = "Laptop", url = "https://laptop-rex.tail1234.ts.net")

    @Test
    fun anAddressAlreadyListedNamesItsHost() {
        assertEquals(
            AddressCheck.Invalid("Already added as Studio"),
            checkHostAddress("https://STUDIO-rex.tail1234.ts.net", listOf(studio, laptop), editing = null),
        )
    }

    @Test
    fun editingToAnotherHostsAddressIsRefusedButKeepingItsOwnIsNot() {
        assertEquals(
            AddressCheck.Invalid("Already added as Studio"),
            checkHostAddress("studio-rex.tail1234.ts.net", listOf(studio, laptop), editing = laptop),
        )
        assertEquals(
            AddressCheck.Ok("https://laptop-rex.tail1234.ts.net"),
            checkHostAddress("laptop-rex.tail1234.ts.net", listOf(studio, laptop), editing = laptop),
        )
    }

    @Test
    fun aNewOrBadAddress() {
        assertEquals(AddressCheck.Ok("https://mini-rex.tail1234.ts.net"), checkHostAddress("mini-rex.tail1234.ts.net", listOf(studio), null))
        assertTrue(checkHostAddress(" ", listOf(studio), null) is AddressCheck.Invalid)
    }
}
