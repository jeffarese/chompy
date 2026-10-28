package com.jeffarese.rexmobile.terminal

import com.jeffarese.rexmobile.terminal.FyiLine.Kind.GAP
import com.jeffarese.rexmobile.terminal.FyiLine.Kind.MISSING
import com.jeffarese.rexmobile.terminal.FyiLine.Kind.SKETCH
import com.jeffarese.rexmobile.terminal.FyiLine.Kind.TEXT
import com.jeffarese.rexmobile.terminal.FyiLine.Kind.TITLE
import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude Code 2.1.311's "You should know" panel, captured through a live Rex server at 50 columns
 * (see src/test/resources/screens/claude-fyi-*): a note staged in the plugin's store, opened,
 * scrolled in fullscreen and dismissed. The .vt captures keep the colours the .txt ones lose.
 */
class FyiReaderTest {
    private val note = "Release builds fall back to the debug key when keystore.properties is missing, " +
        "so a phone that has the signed app will refuse the update."
    private val offerActions = listOf(FyiAction(1, "Learn more"), FyiAction(2, "Knew this already"), FyiAction(0, "Dismiss"))
    private val openActions = listOf(FyiAction(1, "Understood"), FyiAction(2, "Chat in main session"), FyiAction(0, "Dismiss"))
    private val explanation = listOf(
        FyiLine(TITLE, "Why the phone refuses it"),
        FyiLine(
            TEXT,
            "Android only installs an update signed with the same key as the app already on the phone. A debug-signed APK " +
                "looks like a different app, so the install fails with a signature conflict instead of updating.",
        ),
        FyiLine(SKETCH, "keystore.properties ── yes ──▶ release key…"),
        FyiLine(SKETCH, "        │"),
        FyiLine(SKETCH, "        no"),
        FyiLine(SKETCH, "        ▼"),
        FyiLine(SKETCH, "   debug key ──▶ \"App not installed\""),
        FyiLine(TEXT, "Check that keystore.properties sits next to settings.gradle.kts before you build."),
        FyiLine(GAP),
        FyiLine(TITLE, "What to do"),
        FyiLine(
            TEXT,
            "Keep keystore.properties and rex-mobile-release.jks on every machine that builds releases, and look for " +
                "signingConfig release in the build log.",
        ),
    )

    @Test
    fun anOfferAtPhoneWidth() {
        val n = fixture("claude-fyi-offer").fyi!!
        assertEquals("Heads up", n.tag)
        assertEquals(note, n.headline)
        assertEquals(offerActions, n.actions)
        assertTrue(n.body.isEmpty())
        assertTrue(n.complete)
        assertTrue(n.actions.all(n::shows))
        assertEquals(7, n.rows.size)
    }

    @Test
    fun anOfferOnOneLine() {
        val n = fixture("claude-fyi-offer-wide").fyi!!
        assertEquals("Heads up", n.tag)
        assertTrue(n.headline!!.startsWith("Claude Code can't make a \"You should know\" note appear on demand"))
        assertTrue(n.headline!!.endsWith("by hand."))
        assertEquals(offerActions, n.actions)
    }

    @Test
    fun anOpenExplanationThatFits() {
        val info = fixture("claude-fyi-explained")
        val n = info.fyi!!
        assertEquals("You should know", n.tag)
        assertEquals(note, n.headline)
        assertEquals(explanation, n.body)
        assertEquals(openActions, n.actions)
        assertTrue(n.complete)
    }

    @Test
    fun aScrolledPanelShowsAWindowOfItsRows() {
        val top = fixture("claude-fyi-explained-top").fyi!!
        assertEquals(28, top.rows.size)
        assertEquals(0..12, top.shown)
        assertFalse(top.complete)
        assertEquals(note, top.headline)
        assertEquals(explanation.take(3) + FyiLine(MISSING), top.body)
        assertTrue(top.actions.isEmpty())

        val bottom = fixture("claude-fyi-explained-bottom").fyi!!
        assertEquals(15..27, bottom.shown)
        assertNull(bottom.headline)
        assertEquals(openActions, bottom.actions)
        assertTrue(bottom.actions.all(bottom::shows))
        assertEquals(FyiLine(MISSING), bottom.body.first())
        assertEquals(explanation.drop(5), bottom.body.drop(1))

        val middle = fixture("claude-fyi-explained-middle").fyi!!
        assertEquals(12..24, middle.shown)
    }

    @Test
    fun readsOfAScrolledPanelAddUp() {
        val top = fixture("claude-fyi-explained-top").fyi!!
        val middle = fixture("claude-fyi-explained-middle").fyi!!
        val bottom = fixture("claude-fyi-explained-bottom").fyi!!
        assertTrue(top.sameNote(bottom))

        // Two rows between these two windows were never on screen.
        val gap = bottom.mergedWith(top)
        assertFalse(gap.complete)
        assertEquals(note, gap.headline)
        assertEquals(openActions, gap.actions)
        assertEquals(1, gap.body.count { it.kind == MISSING })

        val whole = middle.mergedWith(top).let { bottom.mergedWith(it) }
        assertTrue(whole.complete)
        assertEquals(note, whole.headline)
        assertEquals(explanation, whole.body)
        assertEquals(openActions, whole.actions)
        // Only the window on screen now takes the buttons' digits.
        assertTrue(whole.actions.all(whole::shows))
        val scrolledUp = middle.mergedWith(whole)
        assertTrue(scrolledUp.complete)
        assertFalse(scrolledUp.actions.any(scrolledUp::shows))
    }

    @Test
    fun anotherNoteStartsAfresh() {
        val offer = fixture("claude-fyi-offer").fyi!!
        val open = fixture("claude-fyi-explained-bottom").fyi!!
        assertFalse(open.sameNote(offer))
        assertEquals(open, open.mergedWith(offer))
    }

    @Test
    fun theThanksAfterADismissal() {
        val n = fixture("claude-fyi-asked").fyi!!
        assertNull(n.tag)
        assertEquals("Dismissed.", n.headline)
        assertEquals(listOf(FyiAction(1, "That was helpful"), FyiAction(2, "Didn’t understand"), FyiAction(0, "Dismiss")), n.actions)
        assertTrue(n.body.isEmpty())
    }

    @Test
    fun aNoteSentToTheInputBox() {
        val info = fixture("claude-fyi-chat")
        assertNull(info.fyi)
        val input = info.claudeInput!!
        assertTrue(input, input.startsWith("Here is a note offered by a side agent:"))
        assertTrue(input, input.endsWith("release` in the build log."))
    }

    @Test
    fun noPanelWithoutTheNote() {
        for (name in listOf("claude-idle-auto", "claude-idle-typed", "claude-working", "claude-permission-bash", "claude-reply-bullets")) {
            assertNull(name, fixture(name).fyi)
        }
    }

    @Test
    fun coloursTellTitlesAndSketchesApart() {
        val info = vt("claude-fyi-explained")
        val n = info.fyi!!
        // Without the dimmed suggestion, the box is empty: the buttons can take a digit.
        assertEquals("", info.claudeInput)
        assertEquals(note, n.headline)
        assertEquals(explanation, n.body)
        assertEquals(openActions, n.actions)
        assertEquals(true, n.rows.first { it?.text?.trim() == "What to do" }!!.bold)
        assertEquals(true, n.rows.first { it?.text?.trim() == "no" }!!.sketch)

        val offer = vt("claude-fyi-offer").fyi!!
        assertEquals(offerActions, offer.actions)
        assertEquals(listOf(FyiAction(1, "That was helpful"), FyiAction(2, "Didn’t understand"), FyiAction(0, "Dismiss")), vt("claude-fyi-asked").fyi!!.actions)
        assertEquals(12..24, vt("claude-fyi-explained-middle").fyi!!.shown)
    }

    @Test
    fun aPanelTallerThanTheScreenStartsInTheHistory() {
        // The classic renderer at 24 rows: the panel's first rows scrolled up off the screen.
        val n = vt("claude-fyi-explained-short", rows = 24).fyi!!
        assertEquals("Heads up", n.tag)
        assertEquals(note, n.headline)
        assertEquals(28, n.rows.size)
        assertEquals(0..22, n.shown)
        assertTrue(n.screenRow < 0)
        assertEquals(explanation.take(10), n.body.take(10))
        assertEquals(listOf(FyiLine(TEXT, "Keep keystore.properties and rex-mobile-release.jks on every machine"), FyiLine(MISSING)), n.body.drop(10))
        assertTrue(n.actions.isEmpty())
    }

    @Test
    fun aNumberInTheNoteIsNotAButton() {
        // Claude draws a button's digit in its accent colour and the ": " plain; "1:" in code is all one colour.
        val accent = "\u001b[38;2;177;185;249m"
        val grey = "\u001b[38;2;153;153;153m"
        val plain = "\u001b[0m"
        val t = TestSession(columns = 50, rows = 12)
        t.print("\r\n\r\n" + " ".repeat(46) + "$grey[-]$plain\r\n")
        t.print("$accent✦$plain ${grey}Heads up · $plain${"Run it twice:"}\r\n")
        t.print("  ${accent}1: first$plain build\r\n")
        t.print("  ${accent}1$plain: Learn more   ${accent}0$plain: Dismiss\r\n\r\n")
        t.print("─".repeat(50) + "\r\n❯ \r\n" + "─".repeat(50) + "\r\n  ⏵⏵ auto mode on (shift+tab to cycle)")
        val n = ScreenReader.read(t.emulator).fyi!!
        assertEquals("Run it twice: 1: first build", n.headline)
        assertEquals(listOf(FyiAction(1, "Learn more"), FyiAction(0, "Dismiss")), n.actions)
    }

    /**
     * Claude sometimes puts a line of its own between the panel and the input box, right-aligned:
     * a hint such as "new task? /clear to save 178.2k tokens" (captured live), or an update notice.
     * The panel's buttons are still its last row.
     */
    @Test
    fun aLineOfClaudesBetweenThePanelAndTheBox() {
        // The box's top rule carries the session's title: "──── In-app update delivery ─".
        assertEquals("", fixture("claude-fyi-hint").claudeInput)
        val n = fixture("claude-fyi-hint").fyi!!
        assertEquals("Heads up", n.tag)
        assertTrue(n.headline!!.startsWith("If the update token expires"))
        assertEquals(offerActions, n.actions)
        assertTrue(n.body.isEmpty())
        assertTrue(n.actions.all(n::shows))

        val update = screen("claude-fyi-hint") { line ->
            if ("new task?" in line) "✓ Update installed · Restart to apply".padStart(line.length) else line
        }.fyi!!
        assertEquals(offerActions, update.actions)
        assertTrue(update.body.isEmpty())
    }

    /** A captured screen: a "# cursor_row=N ..." line, then the screen's rows. */
    private fun fixture(name: String): ScreenInfo = screen(name) { it }

    /** A captured screen with each row changed by [edit]. */
    private fun screen(name: String, edit: (String) -> String): ScreenInfo {
        val text = javaClass.classLoader!!.getResource("screens/$name.txt")!!.readText()
        val header = text.substringBefore('\n')
        val cursorRow = Regex("""cursor_row=(\d+)""").find(header)!!.groupValues[1].toInt()
        val rows = Regex("""rows=(\d+)""").find(header)!!.groupValues[1].toInt()
        val lines = text.substringAfter('\n').split('\n').take(rows).map(edit)
        return ScreenReader.read(lines, cursorRow)
    }

    /** A screen captured as escape sequences, replayed into a terminal 50 columns wide. */
    private fun vt(name: String, rows: Int = 40): ScreenInfo {
        val t = TestSession(columns = 50, rows = rows)
        t.print(javaClass.classLoader!!.getResource("screens/$name.vt")!!.readText())
        return ScreenReader.read(t.emulator)
    }
}
