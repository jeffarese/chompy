package com.jeffarese.rexmobile.terminal

import com.jeffarese.rexmobile.terminal.ScreenPrompt.Kind
import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screens captured from Claude Code 2.1.311 and zsh through a live Rex server (see
 * src/test/resources/screens), plus made-up screens for what shouldn't count as a prompt.
 */
class ScreenReaderTest {
    private val proceed = "Do you want to proceed?"
    private val alwaysAllow = "Yes, and always allow access to /private/tmp/claude-502/-Users-dev-person-workspace-rex-mobile/" +
        "34274be2-8e3d-4e8e-b42e-fe0949a9fa8d/scratchpad/prompts-live/probe1 from this project"
    private val switchToAuto = "Yes, and switch to auto mode · auto mode handles these prompts for you"

    @Test
    fun bashPermissionPromptAtEveryWidth() {
        for (name in listOf("claude-permission-bash", "claude-permission-bash-narrow", "claude-permission-bash-phone")) {
            val info = fixture(name)
            val p = info.prompt!!
            assertEquals(name, Kind.MENU, p.kind)
            assertEquals(name, proceed, p.question)
            assertEquals(name, listOf("Yes", alwaysAllow, switchToAuto, "No"), p.options.map { it.label })
            assertEquals(name, listOf(1, 2, 3, 4), p.options.map { it.number })
            assertEquals(name, listOf(true, false, false, false), p.options.map { it.selected })
            assertTrue(name, p.options.all { it.detail == null && it.checked == null })
            assertFalse(p.multiSelect)
            // The command, without the tip or Claude's description of it.
            val command = if (name.endsWith("phone")) "date > out2.txt" else "date > out.txt"
            assertEquals(name, listOf("Bash command", command), p.context)
            // The footer gives way to the prompt's own hints.
            assertNull(name, info.claudeMode)
            assertFalse(name, info.interruptible)
        }
    }

    @Test
    fun highlightFollowsTheArrowKeys() {
        val p = fixture("claude-permission-bash-option2").prompt!!
        assertEquals(listOf(false, true, false, false), p.options.map { it.selected })
        assertEquals(alwaysAllow, p.options[1].label)
    }

    @Test
    fun fileWritePrompt() {
        val p = fixture("claude-permission-write").prompt!!
        assertEquals("Do you want to create hello.txt?", p.question)
        assertEquals(
            listOf(
                "Yes",
                "Yes, and switch to accept edits (auto-approve file edits and common file commands) for this session (shift+tab)",
                "No",
            ),
            p.options.map { it.label },
        )
        assertEquals(listOf("Create file", "hello.txt", "1 hi"), p.context)
        assertEquals(listOf(false, false, true), fixture("claude-permission-write-no").prompt!!.options.map { it.selected })
    }

    @Test
    fun longDiffIsCutShort() {
        val rule = "─".repeat(46)
        val dashes = "╌".repeat(46)
        val screen = listOf(
            rule,
            " Edit file",
            " src/app/Main.kt",
            dashes,
            "  10 -  val a = 1",
            "  10 +  val a = 2",
            "  11    val b = 3",
            "  12    val c = 4",
            "  13    val d = 5",
            dashes,
            " Do you want to make this edit to Main.kt?",
            " ❯ 1. Yes",
            "   2. No",
            "",
            " Esc to cancel",
        )
        val p = read(screen, 11).prompt!!
        assertEquals("Do you want to make this edit to Main.kt?", p.question)
        assertEquals(listOf("Edit file", "src/app/Main.kt", "10 -  val a = 1", "10 +  val a = 2", "11    val b = 3", "…"), p.context)
    }

    @Test
    fun askUserQuestionWithDescriptions() {
        val p = fixture("claude-ask-colour").prompt!!
        assertEquals("Which of these three colours do you prefer?", p.question)
        assertEquals(listOf("Red", "Green", "Blue", "Type something.", "Chat about this"), p.options.map { it.label })
        assertEquals(listOf(1, 2, 3, 4, 5), p.options.map { it.number })
        assertEquals(
            listOf(
                "A bold, warm colour that signals energy and urgency.",
                "A calm, natural colour associated with growth and balance.",
                "A cool, steady colour linked to trust and clarity.",
                null,
                null,
            ),
            p.options.map { it.detail },
        )
        assertTrue(p.options[0].selected)
        assertFalse(p.multiSelect)
        assertEquals(emptyList<String>(), p.context)
    }

    @Test
    fun multiSelectQuestion() {
        val p = fixture("claude-ask-multi").prompt!!
        assertEquals("Which fruits do you like?", p.question)
        assertTrue(p.multiSelect)
        assertEquals(listOf("Apple", "Banana", "Cherry", "Type something", "Submit", "Chat about this"), p.options.map { it.label })
        assertEquals(listOf(1, 2, 3, 4, null, 5), p.options.map { it.number })
        assertEquals(listOf(false, false, false, false, null, null), p.options.map { it.checked })
        assertEquals("Crisp and sweet, sold year-round in most places.", p.options[0].detail)

        assertEquals(listOf(true, false, false, false, null, null), fixture("claude-ask-multi-after1").prompt!!.options.map { it.checked })
        val toggled = fixture("claude-ask-multi-toggled").prompt!!
        assertEquals(listOf(true, false, true, false, null, null), toggled.options.map { it.checked })
        assertEquals(3, toggled.options.single { it.selected }.number)

        // The highlight can rest on the unnumbered Submit.
        val submit = fixture("claude-ask-multi-submit").prompt!!.options.single { it.selected }
        assertEquals("Submit", submit.label)
        assertNull(submit.number)

        val review = fixture("claude-ask-multi-review").prompt!!
        assertEquals("Ready to submit your answers?", review.question)
        assertEquals(listOf("Submit answers", "Cancel"), review.options.map { it.label })
        assertEquals(emptyList<String>(), p.context)
        assertEquals(emptyList<String>(), review.context)
    }

    /** Claude Code 2.1.312 asking two questions at once, captured live at 80 columns and then 168. */
    @Test
    fun claudesQuestionsWithTheirTabs() {
        val open = QuestionTab.State.OPEN
        val answered = QuestionTab.State.ANSWERED
        val submit = QuestionTab.State.SUBMIT
        val colour = fixture("claude-ask-two-colour").prompt!!
        assertTrue(colour.claudeQuestion)
        assertEquals("Which colour do you prefer?", colour.question)
        assertEquals(listOf("Colour" to open, "Fruits" to open, "Submit" to submit), colour.tabs.map { it.label to it.state })
        // Without colours, which tab is on screen isn't known.
        assertNull(colour.currentTab)
        val answer = PromptOption.Role.ANSWER
        val own = PromptOption.Role.OWN
        val chat = PromptOption.Role.CHAT
        assertEquals(listOf(answer, answer, answer, own, chat), colour.options.map { it.role })
        assertNull(colour.options[3].typed)
        assertEquals(emptyList<ReviewedAnswer>(), colour.review)

        // What is typed into "Type something." takes its place.
        val typed = fixture("claude-ask-two-typed").prompt!!
        assertEquals(listOf(answer, answer, answer, own, chat), typed.options.map { it.role })
        assertEquals("deep purple", typed.options[3].typed)
        assertTrue(typed.options[3].selected)

        val fruits = fixture("claude-ask-two-fruits-typed").prompt!!
        assertTrue(fruits.multiSelect)
        assertEquals(listOf("Colour" to answered, "Fruits" to answered, "Submit" to submit), fruits.tabs.map { it.label to it.state })
        assertEquals(listOf(answer, answer, answer, own, PromptOption.Role.SUBMIT, chat), fruits.options.map { it.role })
        assertEquals("figs", fruits.options[3].typed)
        assertEquals(listOf(true, false, true, true, null, null), fruits.options.map { it.checked })

        val review = fixture("claude-ask-two-review").prompt!!
        assertEquals("Ready to submit your answers?", review.question)
        assertEquals(
            listOf(
                ReviewedAnswer("Which colour do you prefer?", "deep purple"),
                ReviewedAnswer("Which of these fruits do you like?", "Apple, Cherry, figs"),
            ),
            review.review,
        )
        assertEquals(listOf(answer, answer), review.options.map { it.role })

        // One question has a tab of its own and no Submit tab; a permission prompt has none.
        assertEquals(listOf("Colour"), fixture("claude-ask-colour").prompt!!.tabs.map { it.label })
        assertEquals(listOf("Fruits", "Submit"), fixture("claude-ask-multi").prompt!!.tabs.map { it.label })
        assertFalse(fixture("claude-permission-bash").prompt!!.claudeQuestion)
    }

    @Test
    fun theTabOnScreenHasABackgroundOfItsOwn() {
        // As Claude Code draws it: black on lavender for the tab on screen.
        val on = "\u001b[38;2;0;0;0m\u001b[48;2;177;185;249m"
        val plain = "\u001b[0m"
        val t = TestSession(columns = 60, rows = 12)
        t.print("${"─".repeat(60)}\r\n")
        t.print("←  ☒ Colour $on ☐ Fruits $plain ✔ Submit  →\r\n\r\n")
        t.print("Which fruits do you like?\r\n\r\n")
        t.print("❯ 1. [ ] Apple\r\n  2. [ ] Banana\r\n  3. [ ] Type something\r\n     Submit\r\n\r\n")
        t.print("Enter to select · Tab/Arrow keys to navigate · Esc to cancel")
        val p = ScreenReader.read(t.emulator).prompt!!
        assertEquals(listOf(false, true, false), p.tabs.map { it.current })
        assertEquals(1, p.currentTab)
    }

    @Test
    fun folderTrustPrompt() {
        val p = fixture("claude-trust-synthetic").prompt!!
        // Not the "Security guide" link just above the options.
        assertEquals("Is this a project you created or one you trust?", p.question)
        assertEquals(listOf("Yes, I trust this folder", "No, exit"), p.options.map { it.label })
        assertTrue(p.options[0].selected)
    }

    @Test
    fun descriptionUnderALabelThatFillsItsLine() {
        // Colours as Claude Code draws them: the highlighted label blue, other labels plain, and
        // descriptions and a label's "· aside" grey. The first label fills the 46 columns, so only
        // the colour tells its description from more of the label.
        val blue = "\u001b[38;2;177;185;249m"
        val grey = "\u001b[38;2;153;153;153m"
        val plain = "\u001b[0m"
        val t = TestSession(columns = 46, rows = 14)
        t.print("Where should this deploy?\r\n\r\n")
        t.print("$blue❯ 1. Staging cluster in the eu-west region now$plain\r\n")
        t.print("$grey     Deploys to staging-eu first so you can$plain\r\n")
        t.print("$grey     check it before production.$plain\r\n")
        t.print("  2. Production, after staging-eu has been\r\n")
        t.print("     checked\r\n")
        // Wrapped inside the grey aside, and just before it.
        t.print("  3. Canary $grey· a few hosts first, then the$plain\r\n")
        t.print("$grey     others$plain\r\n")
        t.print("  4. Roll back to the release before this one\r\n")
        t.print("$grey     · keeps the data$plain\r\n\r\n")
        t.print("${grey}Enter to select · Esc to cancel$plain")
        val p = ScreenReader.read(t.emulator).prompt!!
        assertEquals("Where should this deploy?", p.question)
        assertEquals(
            listOf(
                "Staging cluster in the eu-west region now",
                "Production, after staging-eu has been checked",
                "Canary · a few hosts first, then the others",
                "Roll back to the release before this one · keeps the data",
            ),
            p.options.map { it.label },
        )
        assertEquals(listOf("Deploys to staging-eu first so you can check it before production.", null, null, null), p.options.map { it.detail })
    }

    @Test
    fun shellYesNoOnTheCursorLine() {
        val yn = fixture("shell-yn").prompt!!
        assertEquals(Kind.YES_NO, yn.kind)
        assertEquals("Continue?", yn.question)
        assertEquals(listOf("Yes", "No"), yn.options.map { it.label })
        assertEquals(listOf("y", "n"), yn.options.map { it.answer })
        assertEquals(listOf(false, true), yn.options.map { it.selected }) // [y/N] defaults to no

        // The answered "[y/N] y" above isn't on the cursor line, so only the new question counts.
        val q = fixture("shell-yn-q").prompt!!
        assertEquals("Overwrite file?", q.question)
        assertEquals(listOf(false, false), q.options.map { it.selected })

        val ssh = read(listOf("Are you sure you want to continue connecting (yes/no/[fingerprint])?"), 0).prompt!!
        assertEquals(listOf("yes", "no"), ssh.options.map { it.answer })
        assertEquals("Are you sure you want to continue connecting", ssh.question)
        assertEquals(listOf(true, false), read(listOf("Install? [Y/n] "), 0).prompt!!.options.map { it.selected })
        assertNull(read(listOf("Install? [Y/n] ", ""), 1).prompt)
        assertEquals(emptyList<String>(), yn.context)
    }

    @Test
    fun yesNoQuestionWrappedOntoTheCursorRow() {
        // On a phone-sized block the question's bracket lands on the row above the cursor's.
        val t = TestSession(columns = 46, rows = 8)
        t.print("% ssh newhost\r\nThe authenticity of host 'newhost' can't be established.\r\n")
        t.print("Are you sure you want to continue connecting (yes/no/[fingerprint])? ")
        assertEquals("yes/no/[fingerprint])?", ScreenReader.visibleLines(t.emulator)[t.emulator.cursorRow])
        val p = ScreenReader.read(t.emulator).prompt!!
        assertEquals(Kind.YES_NO, p.kind)
        assertEquals("Are you sure you want to continue connecting", p.question)
        assertEquals(listOf("yes", "no"), p.options.map { it.answer })

        // A row that only happens to fill the width, ended by a newline, isn't joined.
        val u = TestSession(columns = 20, rows = 4)
        u.print("12345678901234567890\r\nOK? [y/N] ")
        assertEquals("OK?", ScreenReader.read(u.emulator).prompt!!.question)
    }

    @Test
    fun noPromptOnOrdinaryScreens() {
        val names = listOf(
            "claude-idle-auto", "claude-idle-manual", "claude-idle-accept-edits", "claude-idle-plan",
            "claude-idle-auto-narrow", "claude-idle-plan-narrow", "claude-idle-suggestion", "claude-idle-typed",
            "claude-effort-high", "claude-effort-low", "claude-working", "claude-working-3", "claude-streaming",
            "claude-after-esc", "claude-after-no", "claude-ask-colour-after3", "claude-slash-autocomplete",
            "claude-reply-bullets",
        )
        for (name in names) assertNull(name, fixture(name).prompt)
    }

    @Test
    fun claudesInputBoxLessItsDimmedSuggestion() {
        val rule = "─".repeat(40)
        fun input(typed: String): String? {
            val t = TestSession(columns = 40, rows = 10)
            t.print("⏺ Done.\r\n\r\n$rule\r\n❯ $typed\r\n$rule\r\n  ⏵⏵ auto mode on (shift+tab to cycle)")
            return ScreenReader.read(t.emulator).claudeInput
        }
        // Claude 2.1.311 draws its suggestion as ESC[2m text right after the prompt.
        assertEquals("", input("\u001b[0m\u001b[2mTry \"fix lint errors\"\u001b[0m"))
        assertEquals("hello there", input("hello there"))
        assertEquals("", input(""))
        assertEquals("1", fixture("claude-idle-typed").claudeInput)
        // A shell prompt with the same glyph isn't Claude's box.
        val shell = TestSession(columns = 40, rows = 6).apply { print(" ~/work\r\n❯ ls") }
        assertNull(ScreenReader.read(shell.emulator).claudeInput)
    }

    @Test
    fun fullscreenOnTheAlternateScreenWithTheMouse() {
        val t = TestSession(columns = 40, rows = 10)
        t.print("$ claude\r\n")
        assertFalse(ScreenReader.read(t.emulator).fullscreen)
        t.print("\u001b[?1049h\u001b[?1002h\u001b[?1006h")
        assertTrue(ScreenReader.read(t.emulator).fullscreen)
        // An editor that leaves the mouse alone scrolls its own way, with keys.
        t.print("\u001b[?1002l")
        assertFalse(ScreenReader.read(t.emulator).fullscreen)
    }

    @Test
    fun permissionModeFromTheFooter() {
        for (suffix in listOf("", "-narrow")) {
            assertEquals("auto mode", fixture("claude-idle-auto$suffix").claudeMode)
            assertEquals("manual mode", fixture("claude-idle-manual$suffix").claudeMode)
            assertEquals("accept edits", fixture("claude-idle-accept-edits$suffix").claudeMode)
            assertEquals("plan mode", fixture("claude-idle-plan$suffix").claudeMode)
        }
        assertEquals("bypass permissions", read(listOf("> ", "  ⏵⏵ bypass permissions on (shift+tab to cycle)"), 0).claudeMode)
        assertNull(read(listOf("I left the lights on"), 0).claudeMode)
    }

    @Test
    fun effortFromBadgeSpinnerAndStatusLine() {
        assertEquals("high", fixture("claude-effort-high").claudeEffort)
        assertEquals("low", fixture("claude-effort-low").claudeEffort)
        // Without the badge, the status line's "Haiku 5.5@medium" tells.
        assertEquals("medium", fixture("claude-idle-plan-narrow").claudeEffort)
        assertEquals("medium", fixture("claude-working").claudeEffort)
        assertEquals("xhigh", read(listOf("✶ Musing… (3s · thinking with xhigh effort)", "────", "❯", "────"), 2).claudeEffort)
        assertNull(read(listOf("────", "❯", "────", "  ⏸ plan mode on"), 1).claudeEffort)
    }

    @Test
    fun interruptibleWhileTheSpinnerShows() {
        assertTrue(fixture("claude-working").interruptible) // "· Combobulating… (1s · thinking with medium effort)"
        assertTrue(fixture("claude-working-3").interruptible) // "✽ Lollygagging… (1s · ↓ 4 tokens)"
        assertFalse(fixture("claude-idle-manual").interruptible) // "✻ Churned for 2s · done 1:32 AM"
        assertFalse(fixture("claude-after-esc").interruptible)
        // Claude Code hides the spinner while a reply streams in, so this screen can't tell.
        assertFalse(fixture("claude-streaming").interruptible)

        val wrapped = listOf("✢ Actioning… (running Stop hook · 2s ·", "↓ 91 tokens)", "────", "❯", "────", "  ⏸ manual mode on")
        assertTrue(read(wrapped, 3).interruptible)
        assertTrue(read(listOf("✳ Transmuting…", "", "> "), 2).interruptible)
        assertTrue(read(listOf("* Thinking… (12s · ↑ 1.2k tokens · esc to interrupt)", "> "), 1).interruptible)
        assertFalse(read(listOf("⏺ Volcanoes… are openings", "> "), 1).interruptible)
    }

    @Test
    fun finishedToolRowIsNotASpinner() {
        // "✔  Synchronizing…  24s · no output", a tool row cut to one word at phone width, near the input box.
        val idle = fixture("claude-idle-tool-one-word")
        assertFalse(idle.interruptible)
        assertNull(idle.prompt)
        assertEquals("manual mode", idle.claudeMode)
        assertFalse(read(listOf("⊘  Fetching…  2s · cancelled", "", "> "), 2).interruptible)
        assertFalse(read(listOf("- Synchronizing… is a word in this list", "", "> "), 2).interruptible)
    }

    @Test
    fun numberedListInAReplyIsNotAMenu() {
        val screen = listOf(
            "⏺ Here is the plan:",
            "",
            "  1. Add the reader",
            "  2. Wire it into the terminal",
            "  3. Test it on the phone",
            "",
            "✻ Worked for 3s · done 1:40 AM",
            "",
            "──────────────────────────────",
            "❯",
            "──────────────────────────────",
            "  ⏸ manual mode on",
        )
        assertNull(read(screen, 9).prompt)
    }

    @Test
    fun menuWithOutputBelowItIsStale() {
        val live = listOf("? Pick a colour", "❯ 1. Red", "  2. Green", "  3. Blue", "", "  enter to select")
        assertEquals(listOf("Red", "Green", "Blue"), read(live, 1).prompt!!.options.map { it.label })
        assertNull(read(live + listOf("You picked Red", "$ "), 7).prompt)
        // A message the user typed as a list, still on screen above Claude's input box.
        val history = listOf("❯ 1. rename the class", "  2. update the tests", "", "✳ Pondering… (2s)", "", "────", "❯", "────")
        assertNull(read(history, 6).prompt)
        // A reply's list with a draft in Claude's input box below it.
        val draft = listOf("  1. foo", "  2. bar", "", "────", "❯ my next message", "────", "  ⏸ manual mode on")
        assertNull(read(draft, 4).prompt)
        // A list without a highlight, or that doesn't start at 1.
        assertNull(read(listOf("  1. Red", "  2. Green", "", "Esc to cancel"), 0).prompt)
        assertNull(read(listOf("❯ 2. Red", "  3. Green", "", "Esc to cancel"), 0).prompt)
    }

    @Test
    fun boxedPromptWithWrappedOptions() {
        val inner = 42
        fun boxed(s: String) = "│" + s.padEnd(inner) + "│"
        val screen = listOf(
            "╭" + "─".repeat(inner) + "╮",
            boxed(" Bash command"),
            boxed(""),
            boxed("   rm -rf build"),
            boxed(""),
            boxed(" Do you want to proceed?"),
            boxed(" ❯ 1. Yes"),
            boxed("   2. Yes, and don't ask again for rm"),
            boxed("      commands in /Users/me/projects/app"),
            boxed("   3. No, and tell Claude what to do"),
            boxed("      differently (esc)"),
            "╰" + "─".repeat(inner) + "╯",
            "",
        )
        val p = read(screen, 6).prompt!!
        assertEquals(proceed, p.question)
        assertEquals(
            listOf("Yes", "Yes, and don't ask again for rm commands in /Users/me/projects/app", "No, and tell Claude what to do differently (esc)"),
            p.options.map { it.label },
        )
        assertTrue(p.options.all { it.detail == null })
    }

    private fun read(lines: List<String>, cursorRow: Int) = ScreenReader.read(lines, cursorRow)

    /** A captured screen: a "# cursor_row=N ..." line, then the screen's rows. */
    private fun fixture(name: String): ScreenInfo {
        val text = javaClass.classLoader!!.getResource("screens/$name.txt")!!.readText()
        val header = text.substringBefore('\n')
        val cursorRow = Regex("""cursor_row=(\d+)""").find(header)!!.groupValues[1].toInt()
        val rows = Regex("""rows=(\d+)""").find(header)!!.groupValues[1].toInt()
        val lines = text.substringAfter('\n').split('\n').take(rows)
        return ScreenReader.read(lines, cursorRow)
    }
}
