package com.jeffarese.rexmobile.terminal

import com.jeffarese.rexmobile.terminal.emulator.TerminalEmulator
import com.jeffarese.rexmobile.terminal.emulator.TextStyle
import kotlin.math.abs

/** What the bottom of a terminal's screen shows, read from its text. */
data class ScreenInfo(
    /** A menu or question the program is waiting on, if one is showing. */
    val prompt: ScreenPrompt? = null,
    /** Claude Code's permission mode from its footer, such as "plan mode" or "accept edits"; null if none shows. */
    val claudeMode: String? = null,
    /** Claude Code's effort level from its footer, such as "high"; null if none shows. */
    val claudeEffort: String? = null,
    /** True while the screen offers to interrupt the program, as Claude Code's "esc to interrupt". */
    val interruptible: Boolean = false,
    /** What is typed in Claude Code's input box, less the dimmed suggestion it shows in an empty one; null if no box shows. */
    val claudeInput: String? = null,
    /**
     * True while the program draws on the alternate screen and takes the mouse, as Claude Code's
     * fullscreen mode does: the terminal keeps no history of it, so scrolling goes to the program.
     */
    val fullscreen: Boolean = false,
    /** The note Claude Code's "You should know" plugin shows above its input box, if one shows. */
    val fyi: FyiNote? = null,
    /** The survey Claude Code shows over its input box and any note, such as "How is Claude doing this session?", if one shows. */
    val survey: ClaudeSurvey? = null,
)

/**
 * A note from Claude Code's built-in "You should know" plugin, read from the panel it draws just
 * above the input box: a line about the work, such as "Heads up · …", its explanation once asked
 * for, and buttons that Claude presses when their digit is typed into the empty input box.
 *
 * The panel scrolls when it is taller than the room Claude gives it, so a note can keep the rows
 * earlier reads saw ([mergedWith]) as well as the ones on screen now ([shown]).
 */
data class FyiNote(
    /**
     * The panel's rows top down, as far as they have been read; null for one not seen. Those over
     * the ✦ are blank: the note's blank first row, and any rows another plugin draws over the note.
     */
    val rows: List<FyiRow?>,
    /** The rows on screen now, as indices in [rows]. */
    val shown: IntRange,
    /** The screen row that shows the first of [shown]. */
    val screenRow: Int,
    /** How wide a row of the panel's text is at most, which tells a wrapped line from one that ended. */
    val width: Int,
) {
    private val parts by lazy { ScreenReader.fyiParts(rows, width) }

    /** "You should know" or "Heads up"; null for a note without one, such as "Dismissed.", or one not seen yet. */
    val tag: String? get() = parts.tag
    /** The text after the ✦ and the tag, such as the note, "One moment…" or "Dismissed."; null if not seen yet. */
    val headline: String? get() = parts.headline
    /** The explanation, once open. */
    val body: List<FyiLine> get() = parts.body
    /** The buttons, in the panel's order. */
    val actions: List<FyiAction> get() = parts.actions
    /** A link the note offers, such as its learning page. */
    val link: String? get() = parts.link
    /** True once every row of the panel has been read. */
    val complete: Boolean get() = rows.none { it == null }

    /** True if [action]'s button is on screen now: Claude only takes the digits of the buttons it shows. */
    fun shows(action: FyiAction): Boolean = parts.actionRows[action]?.let { it in shown } == true

    /** True if [other] reads as the same panel: as many rows, and the same text in every row both have seen. */
    fun sameNote(other: FyiNote): Boolean =
        rows.size == other.rows.size && rows.indices.all { i ->
            val a = rows[i]
            val b = other.rows[i]
            a == null || b == null || (a.text == b.text && a.actions == b.actions)
        }

    /** This read with the rows of [previous] it didn't see, if [previous] was the same panel scrolled elsewhere. */
    fun mergedWith(previous: FyiNote?): FyiNote =
        if (previous == null || complete || !sameNote(previous)) this
        else copy(rows = rows.mapIndexed { i, r -> r ?: previous.rows[i] })
}

/** A row of a "You should know" panel. */
data class FyiRow(
    /** The row's text up to its buttons, with its indent, without trailing spaces. */
    val text: String,
    /** The buttons on the row. */
    val actions: List<FyiAction> = emptyList(),
    /** True if the row is bold throughout, as a title in an explanation is; null when the style isn't known. */
    val bold: Boolean? = null,
    /** True if the row is all in one colour of its own, as a sketch in an explanation is; null when the style isn't known. */
    val sketch: Boolean? = null,
)

/** A button on a "You should know" panel, pressed by typing [key] into Claude's empty input box. */
data class FyiAction(val key: Int, val label: String)

/** A line of an open explanation. */
data class FyiLine(val kind: Kind, val text: String = "") {
    enum class Kind {
        /** A card's title. */
        TITLE,
        /** A paragraph, its wrapped lines joined. */
        TEXT,
        /** A line of a sketch, to show in a fixed-width font as it is. */
        SKETCH,
        /** The space between cards. */
        GAP,
        /** Rows scrolled out of sight that haven't been read yet. */
        MISSING,
    }
}

/**
 * One of Claude Code's surveys, such as "How is Claude doing this session?", or the question a
 * rating may bring, "Can Anthropic look at your session transcript…". Claude takes an answer when
 * its key is typed into the empty input box, as it does a note's buttons; while a survey shows, a
 * note under it has no buttons.
 */
data class ClaudeSurvey(
    /** The question, without its "(optional)". */
    val question: String,
    /** The answers, in the survey's order. */
    val answers: List<SurveyAnswer>,
    /** A page the survey links to, such as what sharing a transcript means. */
    val link: String? = null,
)

/** An answer to one of Claude Code's surveys, given by typing [key] into Claude's empty input box. */
data class SurveyAnswer(val key: Char, val label: String)

/** A question on screen that the user answers by picking one of a few options. */
data class ScreenPrompt(
    val kind: Kind,
    /** The question above the options, such as "Do you want to proceed?". */
    val question: String?,
    val options: List<PromptOption>,
    /** What the question is about, shown above it, such as "Bash command" and the command itself. */
    val context: List<String> = emptyList(),
    /**
     * The tabs over a question Claude Code asks with its AskUserQuestion tool: one per question,
     * then Submit. Empty for any other menu.
     */
    val tabs: List<QuestionTab> = emptyList(),
    /** The answers Claude Code shows for review on its Submit tab, before they are sent. */
    val review: List<ReviewedAnswer> = emptyList(),
    /**
     * For one of Claude Code's questions whose options have previews, the preview it shows beside
     * them, the highlighted option's, line by line as its box shows it; null for any other menu.
     */
    val preview: List<String>? = null,
) {
    /** True when picking an option ticks or unticks it, as in a multi-select question, rather than answering. */
    val multiSelect: Boolean get() = options.any { it.checked != null }

    /**
     * True for a question Claude Code asks with its AskUserQuestion tool, rather than a permission prompt or another menu:
     * its tabs, its previews, or, when the screen is too short for its top, its "Chat about this".
     */
    val claudeQuestion: Boolean get() = tabs.isNotEmpty() || preview != null || options.any { it.role == PromptOption.Role.CHAT }

    /** The end of [question] in at most about [max] characters, from the start of a sentence, for where only a line or two fits. */
    fun questionEnd(max: Int = 200): String? {
        val q = question?.substringAfterLast('\n')?.trim() ?: return null
        if (q.length <= max) return q
        val starts = Regex("""[.?!:;] +(?=\S)""").findAll(q).map { it.range.last + 1 }
        val from = starts.firstOrNull { q.length - it <= max } ?: return "…" + q.takeLast(max).trimStart()
        return "…" + q.substring(from)
    }

    /**
     * This prompt with what Claude Code told Rex it asks filled in, if it cut off the top of the screen:
     * the question, and the labels of the options above the screen. Only when the answers on screen are
     * the ones [asked] gives the same numbers, as Rex only hears of a question set's first question.
     */
    fun withAsked(asked: AskedQuestion?): ScreenPrompt {
        if (asked == null || kind != Kind.MENU || tabs.isNotEmpty() || !claudeQuestion || review.isNotEmpty()) return this
        val answers = options.filter { it.role == PromptOption.Role.ANSWER }
        if (answers.size != asked.labels.size || answers.withIndex().any { (k, o) -> o.number != k + 1 }) return this
        val seen = answers.filter { !it.hidden }
        if (seen.isEmpty() || seen.any { squash(it.label) != squash(asked.labels[it.number!! - 1]) }) return this
        if (question != null && !squash(asked.question).endsWith(squash(question))) return this
        return copy(
            question = asked.question,
            options = options.map { o -> if (o.hidden && o.role == PromptOption.Role.ANSWER) o.copy(label = asked.labels[o.number!! - 1]) else o },
        )
    }

    /** The tab on screen, by index in [tabs]; null when the screen's colours weren't read. */
    val currentTab: Int? get() = tabs.indexOfFirst { it.current == true }.takeIf { it >= 0 }

    /**
     * The keys that pick option [index] of this menu as it is on screen. A numbered option is picked
     * by its number, which in Claude Code picks it at once (or ticks it, in a multi-select question)
     * whatever is highlighted; others are reached with the arrow keys from the highlighted one, then
     * Enter. Beside previews a number only moves the highlight, and does nothing at all while "Chat
     * about this" is highlighted, so every option there is reached with the arrow keys. Null if there
     * is no such option, or nothing is highlighted to count from.
     */
    fun menuKeys(index: Int): List<RemoteKey>? {
        val number = (options.getOrNull(index) ?: return null).number
        if (preview == null && number != null && number in 1..9) return listOf(RemoteKey.digit(number))
        return moveKeys(index)?.plus(RemoteKey.ENTER)
    }

    /**
     * The arrow keys that move the highlight to option [index] from the one highlighted on screen now,
     * which may have moved since the option was offered: in a question with previews, to show that
     * option's preview. Null if there is no such option, or nothing is highlighted to count from.
     */
    fun moveKeys(index: Int): List<RemoteKey>? {
        if (index !in options.indices) return null
        val from = options.indexOfFirst { it.selected }
        if (from < 0) return null
        return List(abs(index - from)) { if (index > from) RemoteKey.DOWN else RemoteKey.UP }
    }

    enum class Kind {
        /** A numbered menu, such as Claude Code's permission prompts and questions. */
        MENU,
        /** A shell-style yes/no question, such as "[y/N]". */
        YES_NO,
    }
}

/**
 * What Claude Code tells Rex while it waits on one of its questions, "answer: <question> (<label> ·
 * <label> · …)": the question and its options' labels, numbered from 1 in that order. Of a set of
 * questions, the first only, whichever is on screen.
 */
data class AskedQuestion(val question: String, val labels: List<String>) {
    companion object {
        private const val PREFIX = "answer:"

        /** Rex's status message for a question; null for any other. */
        fun parse(msg: String?): AskedQuestion? {
            val text = msg?.trim()?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.trim() ?: return null
            if (!text.endsWith(")")) return null
            // The labels are in the last parentheses, which may hold some of their own, as "(Recommended)".
            var depth = 0
            var open = -1
            for (k in text.indices.reversed()) {
                when (text[k]) {
                    ')' -> depth++
                    '(' -> if (--depth == 0) {
                        open = k
                        break
                    }
                }
            }
            if (open <= 0) return null
            val question = text.substring(0, open).trim()
            val labels = text.substring(open + 1, text.length - 1).split(" · ").map { it.trim() }
            if (question.isEmpty() || labels.isEmpty() || labels.any { it.isEmpty() }) return null
            return AskedQuestion(question, labels)
        }
    }
}

/** [text] without its spaces, to compare what wrapping may have joined with or without one. */
private fun squash(text: String) = text.filterNot { it.isWhitespace() }

/** A tab over one of Claude Code's questions. */
data class QuestionTab(
    val label: String,
    val state: State,
    /** True for the tab on screen, which Claude draws on a background of its own; null when colours aren't known. */
    val current: Boolean? = null,
) {
    enum class State {
        /** ☐: not answered yet. */
        OPEN,
        /** ☒: answered. */
        ANSWERED,
        /** ✔ Submit: the review of every answer. */
        SUBMIT,
    }
}

/** A question and the answer given to it, as Claude Code's review lists them. */
data class ReviewedAnswer(val question: String, val answer: String)

data class PromptOption(
    /** The number shown beside the option, if any. */
    val number: Int?,
    val label: String,
    /** Lines under the option that describe it, if the program showed any. */
    val detail: String? = null,
    /** True for the option the menu currently highlights. */
    val selected: Boolean = false,
    /** Whether a checkbox option is ticked; null for an option without a checkbox. */
    val checked: Boolean? = null,
    /** What to type to pick this option, for a question answered by typing, such as "y". */
    val answer: String? = null,
    /** What the option does in one of Claude Code's questions; [Role.ANSWER] in any other menu. */
    val role: Role = Role.ANSWER,
    /**
     * True for an option above the top of the screen, as when a long question doesn't fit it: only
     * its number is known, and its label is blank unless Claude told Rex what it is.
     */
    val hidden: Boolean = false,
) {
    enum class Role {
        /** One of the answers offered. */
        ANSWER,
        /** "Type something.": an answer of one's own, typed while the option is highlighted. */
        OWN,
        /** "Submit", under a multi-select question's options: done with this question. */
        SUBMIT,
        /** "Chat about this": no answer; talk it over with Claude instead. */
        CHAT,
    }

    /** The answer typed into an [Role.OWN] option, if any. */
    val typed: String? get() = label.takeIf { role == Role.OWN && !hidden && !OWN_PLACEHOLDER.matches(it) }

    companion object {
        /** What an [Role.OWN] option shows until something is typed into it. */
        val OWN_PLACEHOLDER = Regex("""Type something\.?""")
    }
}

/**
 * Reads [ScreenInfo] from a screen's lines.
 *
 * A menu counts only while it is live: numbered from 1, exactly one option highlighted, and
 * nothing below it but key hints. That keeps numbered lists in Claude's replies, and menus that
 * have since scrolled up, from offering buttons.
 */
object ScreenReader {
    /**
     * @param lines the visible screen, top to bottom, without trailing spaces.
     * @param cursorRow the cursor's row in [lines].
     */
    fun read(lines: List<String>, cursorRow: Int): ScreenInfo = read(lines, cursorRow, null, lines.maxOfOrNull { it.length } ?: 0, null)

    /** Reads the emulator's visible screen. Call it on the thread that feeds the emulator. */
    fun read(emulator: TerminalEmulator): ScreenInfo {
        val lines = visibleLines(emulator)
        val info = read(lines, emulator.cursorRow, cursorLine(emulator), emulator.mColumns) { row, index -> styleAt(emulator, row, index) }
            .copy(fullscreen = emulator.isAlternateBufferActive && emulator.isMouseTrackingActive)
        return if (info.fyi == null && info.claudeInput != null) info.copy(fyi = fyiAbove(emulator, lines)) else info
    }

    /**
     * A note whose panel starts above the screen. Claude's classic renderer draws a panel as tall
     * as the screen when it has more rows than that, so its first rows scroll into the history,
     * just above the screen. They stay as they were when they scrolled there, with the panel at
     * its top, so they are only read while it is still there.
     */
    private fun fyiAbove(emulator: TerminalEmulator, visible: List<String>): FyiNote? {
        val rows = visible.map(::Row)
        val box = inputBox(rows, rows.indexOfLast { !it.blank }) ?: return null
        if (panelBottom(rows, box.first, emulator.mColumns)?.second == null) return null
        val screen = emulator.screen
        val extra = minOf(screen.activeTranscriptRows, MAX_NOTE_ROWS)
        if (extra == 0) return null
        val lines = List(extra) { k -> screen.getSelectedText(0, k - extra, emulator.mColumns - 1, k - extra).trimEnd() } + visible
        val all = lines.map(::Row)
        return fyiOf(lines, all, box.first + extra, emulator.mColumns, trustFrom = extra) { row, index -> styleAt(emulator, row - extra, index) }
            ?.let { it.copy(screenRow = it.screenRow - extra) }
    }

    /**
     * @param cursorLine the cursor's row joined with the rows it wrapped from, when known, so a long
     *   yes/no question still reads whole.
     * @param columns the screen's width.
     * @param style the style of the cell that holds character [index] of row [row], when known.
     */
    private fun read(lines: List<String>, cursorRow: Int, cursorLine: String?, columns: Int, style: ((row: Int, index: Int) -> Long)?): ScreenInfo {
        val rows = lines.map(::Row)
        val end = rows.indexOfLast { !it.blank }
        if (end < 0) return ScreenInfo()
        val box = inputBox(rows, end)
        // Claude Code keeps its spinner, effort and footer just above or below its input box.
        val bottom = rows.subList(maxOf(0, end - 11), end + 1).map { it.text }
        val footer = rows.subList(0, end + 1).filter { !it.blank }.takeLast(3).map { it.text }.asReversed()
        val yesNoLine = cursorLine?.replace('\u00a0', ' ') ?: rows.getOrNull(cursorRow)?.text
        val fyi = box?.let { fyiOf(lines, rows, it.first, columns, style = style) }
        return ScreenInfo(
            prompt = readPreviewQuestion(lines, rows, style) ?: readMenu(rows, style) ?: yesNoLine?.let(::readYesNo),
            claudeMode = footer.firstNotNullOfOrNull(::modeOf),
            claudeEffort = effortOf(rows.map { it.text }, bottom, footer),
            interruptible = bottom.any { it.contains("esc to interrupt", ignoreCase = true) || SPINNER.matches(it) },
            claudeInput = box?.let { inputOf(rows, it, style) },
            fyi = fyi,
            survey = box?.let { surveyOf(rows, fyi?.screenRow ?: it.first, style) },
        )
    }

    /**
     * The cursor's row joined with the rows above it that the emulator wrapped onto the next one, as
     * a question longer than the screen is wide; null if the row carries on from none.
     */
    private fun cursorLine(emulator: TerminalEmulator): String? {
        val screen = emulator.screen
        val row = emulator.cursorRow
        var start = row
        while (start > 0 && row - start < 3 && screen.getLineWrap(start - 1)) start--
        if (start == row) return null
        return screen.getSelectedText(0, start, emulator.mColumns - 1, row).trimEnd()
    }

    /** The style of the cell under character [index] of screen row [row]'s text, as the terminal laid it out. */
    private fun styleAt(emulator: TerminalEmulator, row: Int, index: Int): Long {
        val line = emulator.screen.allocateFullLineIfNecessary(row)
        return line.getStyle(line.findColumnOfIndex(index).coerceIn(0, emulator.mColumns - 1))
    }

    fun visibleLines(emulator: TerminalEmulator): List<String> {
        val screen = emulator.screen
        val lastColumn = emulator.mColumns - 1
        return List(emulator.mRows) { row -> screen.getSelectedText(0, row, lastColumn, row).trimEnd() }
    }

    // ---- Menus ----

    private const val MARKERS = "❯›>▶►▸"
    private val OPTION = Regex("""^( *)(?:([$MARKERS]) *)?(\d{1,2})[.)] +(\S.*)$""")
    private val MARKED = Regex("""^( *)[$MARKERS] +(\S.*)$""")
    private val CHECKBOX = Regex("""^(?:\[([ xX✔✓✗*•])]|([☐☑☒])) +""")
    private val HINTS = listOf(
        "esc to", "enter to", "tab to", "space to", "to cancel", "to confirm", "to select", "to navigate",
        "to exit", "to go back", "to amend", "to toggle", "to submit", "↑/↓", "↑↓",
    )

    private class Item(val number: Int?, val selected: Boolean, val textCol: Int, text: String, first: Row, firstRow: Int) {
        val checked: Boolean?
        val labelCol: Int
        val label = StringBuilder()
        var detail: StringBuilder? = null
        var lastLine: Row = first
        /** The screen row of [lastLine]. */
        var lastRow: Int = firstRow

        init {
            val box = CHECKBOX.find(text)
            checked = box?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }) !in setOf(" ", "☐") }
            labelCol = textCol + (box?.value?.length ?: 0)
            label.append(text.substring(box?.value?.length ?: 0).trim())
        }

        fun toOption() = PromptOption(number, label.toString(), detail?.toString(), selected, checked)
    }

    private fun readMenu(rows: List<Row>, style: ((row: Int, index: Int) -> Long)?): ScreenPrompt? {
        val lastIdx = rows.indexOfLast { OPTION.matches(it.text) }
        if (lastIdx < 0) return null
        val last = OPTION.find(rows[lastIdx].text)!!
        val numberCol = last.groups[3]!!.range.first
        // Walk up to option 1. Every line between two options must belong to the one above it. A menu
        // taller than the screen, as a long question of Claude's on a phone, runs off its top: the
        // options numbered below the first one on screen are above it.
        var expected = last.groupValues[3].toInt()
        if (expected < 2) return null
        var first = lastIdx
        var blanks = 0
        var i = lastIdx - 1
        var hidden = 0
        while (expected > 1) {
            if (i < 0) {
                hidden = expected - 1
                break
            }
            val r = rows[i]
            val m = OPTION.find(r.text)
            when {
                m != null -> {
                    if (m.groupValues[3].toInt() != expected - 1) return null
                    if (abs(m.groups[3]!!.range.first - numberCol) > 1) return null
                    expected--
                    first = i
                    blanks = 0
                }
                r.blank -> if (++blanks > 2) return null
                r.rule || r.indent > numberCol || markedTextCol(r) > numberCol -> blanks = 0
                else -> return null
            }
            i--
        }

        // Read the options top down, with their wrapped lines, details and unnumbered items such as "Submit".
        val width = rows.maxOf { it.text.length }
        fun lookAt(row: Int, index: Int) = style?.let { look(it(row, index)) }
        val items = mutableListOf<Item>()
        var j = first
        var afterBlank = false
        while (j < rows.size) {
            val r = rows[j]
            val opt = if (j <= lastIdx) OPTION.find(r.text) else null
            // A highlighted line without a number, such as "❯    Submit", lined up with the options' text.
            val marked = if (opt == null && markedTextCol(r) > numberCol && (j < lastIdx || !afterBlank)) MARKED.find(r.text) else null
            val current = items.lastOrNull()
            when {
                opt != null -> items += Item(
                    opt.groupValues[3].toInt(), opt.groups[2] != null, opt.groups[4]!!.range.first, opt.groupValues[4], r, j,
                )
                r.blank || r.rule -> afterBlank = true
                marked != null -> items += Item(null, true, marked.groups[2]!!.range.first, marked.groupValues[2], r, j)
                current == null -> break
                current.checked != null && r.indent >= current.textCol && r.indent < current.labelCol ->
                    items += Item(null, false, r.indent, r.text.trim(), r, j)
                r.indent > numberCol -> {
                    val prev = current.lastLine
                    val detail = current.detail
                    // A description below a label that fills its line looks just like the label
                    // wrapping. Claude Code draws descriptions grey, though, and wrapped text goes on
                    // in the style it broke off in, unless it broke just before a grey "· aside".
                    val look = lookAt(j, r.indent)
                    val before = lookAt(current.lastRow, prev.text.length - 1)
                    val unlike = look != null && before != null && look != before && !r.text.trimStart().startsWith("·")
                    when {
                        detail != null -> join(detail, prev, r, width)
                        !afterBlank && !unlike && wraps(prev, r, width) -> join(current.label, prev, r, width)
                        else -> current.detail = StringBuilder(r.text.trim())
                    }
                    current.lastLine = r
                    current.lastRow = j
                }
                else -> break
            }
            if (!r.blank && !r.rule) afterBlank = false
            j++
        }
        // Off the top of the screen, the highlight may be too.
        val highlighted = items.count { it.selected }
        if (highlighted > 1 || (highlighted == 0 && hidden == 0)) return null
        if (!onlyHints(rows.subList(j, rows.size), width)) return null
        val options = List(hidden) { PromptOption(it + 1, "", hidden = true) } + items.map { it.toOption() }
        // Claude's question has "Chat about this" last, under a rule, even when its tabs are off the screen.
        val asks = options.lastOrNull { it.number != null }?.label == CHAT
        if (hidden > 0) {
            // What is above it on screen is the end of an option; the question, the tabs and the
            // rest of the options are further up. Only Claude's questions are that tall: any other
            // list running off the top isn't taken for a menu.
            if (!asks) return null
            return ScreenPrompt(ScreenPrompt.Kind.MENU, null, withRoles(options))
        }
        val above = paragraphsAbove(rows, first, max = MAX_PARAGRAPHS)
        val tabsRow = tabsAbove(rows, first)
        val tabs = tabsRow?.let { readTabs(rows[it].text, it, style) }.orEmpty()
        val review = if (tabsRow == null) emptyList() else reviewIn(rows, tabsRow + 1, above.firstOrNull()?.first ?: first, width)
        // Claude's question whole, every paragraph of it, from its tabs or the top of the screen. Not
        // its review, which is a menu of its own without "Chat about this".
        val question = if (asks && review.isEmpty()) wholeQuestion(rows, above.filter { tabsRow == null || it.first > tabsRow }, width)
        else questionIn(rows, above.take(4), width)
        return ScreenPrompt(
            ScreenPrompt.Kind.MENU,
            question,
            if (tabs.isEmpty() && !asks) options else withRoles(options),
            if (asks || tabs.isNotEmpty()) emptyList() else above.firstOrNull()?.let { contextAbove(rows, it.first, width) }.orEmpty(),
            tabs,
            review,
        )
    }

    private const val CHAT = "Chat about this"
    /** The most paragraphs one of Claude's questions is read as. */
    private const val MAX_PARAGRAPHS = 12

    /** One of Claude's questions from its paragraphs, nearest first: in order, each joined where it wrapped, a line apart. */
    private fun wholeQuestion(rows: List<Row>, paragraphs: List<IntRange>, width: Int): String? =
        paragraphs.asReversed().joinToString("\n") { paragraphText(rows, it, width) }.ifBlank { null }

    // ---- Claude Code's AskUserQuestion ----

    /** A tab over Claude's question: ☐ for one not answered, ☒ for one answered, ✔ for Submit. */
    private val TAB = Regex("""^([☐☒✔]) (\S.*)$""")
    private val REVIEW_QUESTION = Regex("""^● +(\S.*)$""")
    private val REVIEW_ANSWER = Regex("""^→ +(\S.*)$""")
    private const val MAX_TABS_UP = 30

    /** The row of the tabs over the question whose options start at [first], under the rule Claude draws over the question. */
    private fun tabsAbove(rows: List<Row>, first: Int): Int? {
        for (i in first - 1 downTo maxOf(0, first - MAX_TABS_UP)) {
            if (rows[i].rule) return null
            if (tabParts(rows[i].text) != null) return i
        }
        return null
    }

    /** A row such as "←  ☐ Colour  ☒ Fruits  ✔ Submit  →" split into its tabs, two spaces or more apart; null for any other row. */
    private fun tabParts(line: String): List<Pair<String, IntRange>>? {
        val parts = Regex("""\S+(?: \S+)*""").findAll(line).map { it.value to it.range }.toList()
        val tabs = parts.filter { it.first != "←" && it.first != "→" }
        return tabs.takeIf { it.isNotEmpty() && it.all { (t, _) -> TAB.matches(t) } }
    }

    /** The tabs on row [row]: the one on screen is drawn on a background of its own. */
    private fun readTabs(line: String, row: Int, style: ((row: Int, index: Int) -> Long)?): List<QuestionTab> =
        tabParts(line)!!.map { (text, range) ->
            val m = TAB.matchEntire(text)!!
            val state = when (m.groupValues[1]) {
                "☒" -> QuestionTab.State.ANSWERED
                "✔" -> QuestionTab.State.SUBMIT
                else -> QuestionTab.State.OPEN
            }
            val current = style?.let { TextStyle.decodeBackColor(it(row, range.first)) != TextStyle.COLOR_INDEX_BACKGROUND }
            QuestionTab(m.groupValues[2], state, current)
        }

    /**
     * Claude's options with what each does: the unnumbered one is Submit, "Chat about this" is set
     * apart under the rule, and the last numbered one before it takes an answer of one's own, as
     * "Type something." or what has been typed into it.
     */
    private fun withRoles(options: List<PromptOption>): List<PromptOption> {
        val chat = options.indexOfLast { it.number != null && it.label == CHAT }
        val own = if (chat > 0) options.subList(0, chat).indexOfLast { it.number != null }
        else options.indexOfLast { PromptOption.OWN_PLACEHOLDER.matches(it.label) }
        return options.mapIndexed { i, o ->
            val role = when {
                i == chat -> PromptOption.Role.CHAT
                o.number == null -> PromptOption.Role.SUBMIT
                i == own -> PromptOption.Role.OWN
                else -> PromptOption.Role.ANSWER
            }
            o.copy(role = role)
        }
    }

    /** The review on the Submit tab, in rows [from] until [until]: "● " and a question, then "→ " and its answer. */
    private fun reviewIn(rows: List<Row>, from: Int, until: Int, width: Int): List<ReviewedAnswer> {
        val out = mutableListOf<Pair<StringBuilder, StringBuilder?>>()
        var prev: Row? = null
        for (k in from until until) {
            val r = rows[k]
            val t = r.text.trim()
            val q = REVIEW_QUESTION.matchEntire(t)
            val a = REVIEW_ANSWER.matchEntire(t)
            when {
                r.blank -> prev = null
                q != null -> out += StringBuilder(q.groupValues[1]) to null
                a != null && out.isNotEmpty() && out.last().second == null -> out[out.size - 1] = out.last().first to StringBuilder(a.groupValues[1])
                prev != null && out.isNotEmpty() -> join(out.last().second ?: out.last().first, prev, r, width)
            }
            if (!r.blank) prev = r
        }
        return out.mapNotNull { (q, a) -> a?.let { ReviewedAnswer(q.toString(), it.toString()) } }
    }

    // ---- Claude Code's questions with previews ----

    /**
     * The top of a preview's box, two spaces or more right of the first option: "❯ 1. Sidebar      ┌───".
     * Not a box drawn inside the preview, which has the preview's side to its left.
     */
    private val PREVIEW_TOP = Regex("""^( *[^ │┃┌╭├└╰][^│┃┌╭├└╰]*?) {2,}([┌╭])─""")
    /** An option's row in the column beside a preview: maybe the highlight, maybe its number, then its label or part of it. */
    private val PREVIEW_OPTION = Regex("""^( *)([$MARKERS])? *(?:(\d{1,2})\.)? *(.*)$""")
    /** The farthest an option's number sits from the left edge; its label's wrapped lines start further in. */
    private const val PREVIEW_NUMBER_COL = 2

    /**
     * One of Claude's questions whose options have previews. Claude draws those in two columns: the
     * options on the left, numbered but without descriptions or "Type something.", and on the right
     * the highlighted option's preview in a box, with "Notes: press n to add notes" under it; then,
     * under a rule, "Chat about this" without a number.
     *
     * At a phone's width a label wraps, and Claude may then put the option's number on a later line of
     * it: "❯   Phone in a frame +", " 1.captions", "   (Recommended)". So each line of the left column
     * goes with the option whose number is on the nearest line, the one above on a tie. While "Chat
     * about this" is highlighted, the option highlighted last keeps its marker, as its preview stays.
     */
    private fun readPreviewQuestion(lines: List<String>, rows: List<Row>, style: ((row: Int, index: Int) -> Long)?): ScreenPrompt? {
        val top = lines.indexOfLast { PREVIEW_TOP.containsMatchIn(it) }
        if (top < 0) return null
        val col = PREVIEW_TOP.find(lines[top])!!.groups[2]!!.range.first
        // Each row split where the box starts, which may stray a column or two from the top's.
        val left = mutableListOf<String>()
        val shown = mutableListOf<StringBuilder>()
        val dividers = mutableSetOf<Int>()
        var closed = false
        var rule = -1
        for (i in top until lines.size) {
            // The rule over "Chat about this" starts at the left edge; a row of the box that only
            // draws a line, "│ ──────── │", doesn't.
            if (rows[i].rule && rows[i].indent < col - 2) {
                rule = i
                break
            }
            val line = lines[i]
            val at = (maxOf(1, col - 2)..minOf(line.length - 1, col + 2)).firstOrNull { k ->
                line[k] != ' ' && line.substring(0, k).let { it.isBlank() || it.endsWith("  ") }
            }
            val right = at?.let { line.substring(it) }
            left += when {
                at != null -> line.substring(0, at).trimEnd()
                // Right of the box: a wrapped line of it, or the line about notes under it.
                line.indexOfFirst { it != ' ' } >= col - 2 -> ""
                else -> line
            }
            if (right == null || closed) continue
            when (right[0]) {
                '┌', '╭' -> if (i != top) return null
                '│' -> shown += StringBuilder(right.drop(1))
                // A line Claude draws across the box for the lines it hides: "├─── ✂ ─── 12 lines hidden ┤".
                '├' -> {
                    dividers += shown.size
                    shown += StringBuilder(right.drop(1))
                }
                '└', '╰' -> closed = true
                // A line of the box too long for the screen, carried on onto the next row.
                else -> shown.lastOrNull()?.append(' ')?.append(right) ?: return null
            }
        }
        if (!closed || rule < 0 || shown.isEmpty()) return null
        val preview = shown.mapIndexed { k, sb ->
            val s = sb.toString().trimEnd().removeSuffix("│").removeSuffix("┤").trimEnd()
            if (k in dividers) s.replace(Regex("[─┤]+"), " ").trim().replace(Regex(" {2,}"), " ") else s.removePrefix(" ")
        }

        // The options: the rows that hold a number, and each other line to the nearest of them.
        class Line(val row: Int, val marked: Boolean, val number: Int?, val text: String)
        val parts = left.mapIndexedNotNull { k, text ->
            if (text.isBlank()) return@mapIndexedNotNull null
            val m = PREVIEW_OPTION.matchEntire(text) ?: return@mapIndexedNotNull null
            val numberAt = m.groups[3]?.range?.first
            val number = m.groups[3]?.value?.toInt()?.takeIf { numberAt!! <= PREVIEW_NUMBER_COL }
            val marked = m.groups[2] != null && m.groups[1]!!.value.length <= 1
            Line(k, marked, number, if (number != null || marked) m.groupValues[4].trim() else text.trim())
        }
        val numbered = parts.filter { it.number != null }
        if (numbered.size < 2 || numbered.map { it.number } != (1..numbered.size).toList()) return null
        val owner = parts.associateWith { p ->
            p.number ?: numbered.minWith(compareBy<Line> { abs(it.row - p.row) }.thenBy { it.row }).number!!
        }
        val options = numbered.map { n ->
            val own = parts.filter { owner[it] == n.number }
            PromptOption(n.number, own.joinToString(" ") { it.text }.trim(), selected = own.any { it.marked })
        }.toMutableList()

        // "Chat about this", then nothing but key hints.
        var chatRow = rule + 1
        while (chatRow < rows.size && rows[chatRow].blank) chatRow++
        val chat = rows.getOrNull(chatRow)?.text?.takeIf { t -> HINTS.none { it in t.lowercase() } }
        val tailFrom = if (chat != null) chatRow + 1 else rule + 1
        if (!onlyHints(rows.subList(minOf(tailFrom, rows.size), rows.size), rows.maxOf { it.text.length })) return null
        if (chat != null) {
            val m = PREVIEW_OPTION.matchEntire(chat)!!
            val focused = m.groups[2] != null
            if (focused) options.replaceAll { it.copy(selected = false) }
            options += PromptOption(null, m.groupValues[4].trim(), selected = focused, role = PromptOption.Role.CHAT)
        }
        if (options.count { it.selected } != 1) return null

        val width = rows.maxOf { it.text.length }
        val above = paragraphsAbove(rows, top, max = MAX_PARAGRAPHS)
        val tabsRow = tabsAbove(rows, top)
        return ScreenPrompt(
            ScreenPrompt.Kind.MENU,
            wholeQuestion(rows, above.filter { tabsRow == null || it.first > tabsRow }, width),
            options,
            tabs = tabsRow?.let { readTabs(rows[it].text, it, style) }.orEmpty(),
            preview = preview,
        )
    }

    /**
     * The colour and the dim and italic effects of a cell's style: what sets a description apart
     * from a label, where bold or underline may only mark a word inside one.
     */
    private fun look(style: Long): Long =
        TextStyle.decodeForeColor(style).toLong() shl 32 or
            (TextStyle.decodeEffect(style) and (TextStyle.CHARACTER_ATTRIBUTE_DIM or TextStyle.CHARACTER_ATTRIBUTE_ITALIC)).toLong()

    private fun markedTextCol(r: Row) = MARKED.find(r.text)?.groups?.get(2)?.range?.first ?: -1

    /** True if [next] carries on [prev]'s text, because prev had no room left for next's first word. */
    private fun wraps(prev: Row, next: Row, width: Int): Boolean {
        val room = (prev.limit ?: width) - prev.text.length
        return next.text.trimStart().substringBefore(' ').length + 1 > room
    }

    /** Joins a wrapped line, without a space where a word too long for the line was broken. */
    private fun join(sb: StringBuilder, prev: Row, next: Row, width: Int) {
        val text = next.text.trim()
        val full = prev.text.length >= (prev.limit ?: width)
        val broken = full && sb.toString().substringAfterLast(' ').length + text.substringBefore(' ').length > (prev.limit ?: width) - next.indent
        if (!broken) sb.append(' ')
        sb.append(text)
    }

    /**
     * Up to [max] paragraphs above row [first], nearest first, as row ranges. They stop at a rule, or
     * at more than two blank rows in a row.
     */
    private fun paragraphsAbove(rows: List<Row>, first: Int, max: Int = 4): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = first - 1
        while (out.size < max) {
            var blanks = 0
            while (i >= 0 && rows[i].blank && blanks < 2) { i--; blanks++ }
            if (i < 0 || rows[i].blank || rows[i].rule) break
            val end = i
            while (i >= 0 && !rows[i].blank && !rows[i].rule) i--
            out += i + 1..end
        }
        return out
    }

    /** A paragraph's rows as one line, joined where they wrapped. */
    private fun paragraphText(rows: List<Row>, range: IntRange, width: Int): String {
        val sb = StringBuilder(rows[range.first].text.trim())
        for (k in range.first + 1..range.last) join(sb, rows[k - 1], rows[k], width)
        return sb.toString()
    }

    /**
     * The question a menu answers: the paragraph just above it, as up to its last three lines. When
     * that has no question mark, as the folder-trust dialog's "Security guide" link, it is the nearest
     * sentence a few paragraphs up that ends in one.
     */
    private fun questionIn(rows: List<Row>, above: List<IntRange>, width: Int): String? {
        val nearest = above.firstOrNull() ?: return null
        val direct = (maxOf(nearest.first, nearest.last - 2)..nearest.last).joinToString(" ") { rows[it].text.trim() }
        if ('?' in direct) return direct
        for (p in above.drop(1)) {
            val text = paragraphText(rows, p, width)
            val end = text.lastIndexOf('?')
            if (end < 0) continue
            val start = listOf(". ", ": ", "! ", "? ").maxOf { text.lastIndexOf(it, end - 1).let { at -> if (at < 0) 0 else at + 2 } }
            return text.substring(start, end + 1).trim()
        }
        return direct.ifEmpty { null }
    }

    /**
     * What a Claude Code permission prompt asks about, from the lines between its top rule and the
     * question at row [question]: the header, such as "Bash command" or "Create file", the file's path
     * if one follows it, then the command or the file's text from between the dashed rules, at most
     * [MAX_CONTEXT] lines in all. The tip and Claude's description of a command are left out. Empty
     * for a dialog without dashed rules above its question, such as AskUserQuestion's.
     */
    private fun contextAbove(rows: List<Row>, question: Int, width: Int): List<String> {
        var i = question - 1
        while (i >= 0 && rows[i].blank) i--
        if (i < 0 || !rows[i].dashed) return emptyList()
        val bodyEnd = i - 1
        i--
        while (i >= 0 && !rows[i].rule && bodyEnd - i < 40) i--
        if (i < 0 || !rows[i].dashed) return emptyList()
        val bodyRows = rows.subList(i + 1, bodyEnd + 1).dropWhile { it.blank }.dropLastWhile { it.blank }
        val indent = bodyRows.filter { !it.blank }.minOfOrNull { it.indent } ?: 0
        val body = bodyRows.map { it.text.drop(indent) }
        val headEnd = i - 1
        i--
        while (i >= 0 && !rows[i].rule && headEnd - i < 12) i--
        val head = logicalLines(rows, i + 1..headEnd, width).filterNot { it.startsWith("Tip:") }
        val lines = head.take(1) + head.drop(1).filter(::looksLikePath) + body
        return if (lines.size > MAX_CONTEXT) lines.take(MAX_CONTEXT - 1) + "…" else lines
    }

    private const val MAX_CONTEXT = 6

    /** The lines of [range] with each wrapped line joined back together, blank rows left out. */
    private fun logicalLines(rows: List<Row>, range: IntRange, width: Int): List<String> {
        val out = mutableListOf<StringBuilder>()
        var open = false
        for (k in range) {
            val r = rows[k]
            if (r.blank) {
                open = false
                continue
            }
            if (open && wraps(rows[k - 1], r, width)) join(out.last(), rows[k - 1], r, width) else out += StringBuilder(r.text.trim())
            open = true
        }
        return out.map { it.toString() }
    }

    /** True for a line such as "hello.txt" or "/Users/me/My Files/a.txt", rather than a sentence. */
    private fun looksLikePath(line: String) = ' ' !in line || line.startsWith("/") || line.startsWith("~")

    /** True if [tail], what is below a menu, holds nothing but key hints such as "Esc to cancel", wrapped or not. */
    private fun onlyHints(tail: List<Row>, width: Int): Boolean {
        if (tail.count { !it.blank && !it.rule } > 4) return false
        var prev: Row? = null
        for (r in tail) {
            if (r.blank || r.rule) {
                prev = null
                continue
            }
            val s = r.text.lowercase()
            if (HINTS.none { it in s } && (prev == null || !wraps(prev, r, width))) return false
            prev = r
        }
        return true
    }

    // ---- Yes/no questions ----

    private val YES_NO = Regex("""^(.*?)\s*[\[(](y|yes)/(n|no)(?:/\[fingerprint])?[\])]\s*[:?]?$""", RegexOption.IGNORE_CASE)

    private fun readYesNo(line: String): ScreenPrompt? {
        val m = YES_NO.find(line) ?: return null
        val (question, y, n) = m.destructured
        val words = y.length > 1
        return ScreenPrompt(
            ScreenPrompt.Kind.YES_NO,
            question.trim().ifEmpty { null },
            listOf(
                PromptOption(null, "Yes", selected = !words && y == "Y" && n == "n", answer = if (words) "yes" else "y"),
                PromptOption(null, "No", selected = !words && n == "N" && y == "y", answer = if (words) "no" else "n"),
            ),
        )
    }

    // ---- Claude Code's footer ----

    private val MODE = Regex("""^\s*[⏵⏸⏩▶►⇥]+\s*([a-z][a-z ]{1,30}?) on\b""")
    private val MODE_HINT = Regex("""^\s*([a-z][a-z ]{1,30}?) on \(shift\+tab to cycle\)""")
    private val EFFORT_BADGE = Regex("""[○◐◑◒◓●◉◔◕]\s+([a-z]+)\s+·\s+/effort""")
    private const val LEVELS = "low|medium|high|xhigh|max"
    private val WITH_EFFORT = Regex("""\bwith ($LEVELS) effort\b""")
    private val AT_EFFORT = Regex("""@($LEVELS)\b""")
    /**
     * Claude Code's spinner: one of its frames, one space, and a single word with an ellipsis, as
     * "✽ Lollygagging…". A tool's row cut short at phone width, as "✔  Synchronizing…  24s", has
     * two spaces after a different glyph, and stays when the turn is over.
     */
    private val SPINNER = Regex("""^\s*[·✢✳✶✻✽*] \p{L}[\p{L}'’-]*…(?:\s.*)?$""")

    private fun modeOf(line: String): String? =
        (MODE.find(line) ?: MODE_HINT.find(line))?.groupValues?.get(1)

    /**
     * The badge Claude Code shows briefly after a change wins, then the spinner's "thinking with high
     * effort", then a status line's "Model@high", then the banner at the top.
     */
    private fun effortOf(all: List<String>, bottom: List<String>, footer: List<String>): String? =
        bottom.asReversed().firstNotNullOfOrNull { EFFORT_BADGE.find(it)?.groupValues?.get(1) }
            ?: bottom.asReversed().firstNotNullOfOrNull { WITH_EFFORT.find(it)?.groupValues?.get(1) }
            ?: footer.firstNotNullOfOrNull { AT_EFFORT.find(it)?.groupValues?.get(1) }
            ?: all.asReversed().firstNotNullOfOrNull { WITH_EFFORT.find(it)?.groupValues?.get(1) }

    // ---- Claude Code's input box ----

    private const val MAX_INPUT_ROWS = 40

    /**
     * Claude Code's input box, from the rule above it to the rule that closes it near the bottom.
     * The first row inside starts with ❯ and the rest of a long message is indented after it, as
     * the note the "You should know" plugin puts there for "Chat in main session" is.
     */
    private fun inputBox(rows: List<Row>, end: Int): IntRange? {
        val close = (end downTo maxOf(0, end - 6)).firstOrNull { rows[it].rule } ?: return null
        for (i in close - 1 downTo maxOf(0, close - MAX_INPUT_ROWS)) {
            val r = rows[i]
            // Its top rule may carry the session's title, as "──── Fix the build ─".
            if ((r.rule || r.titledRule) && r.indent < 2) {
                val first = i + 1
                val prompt = rows[first].text.indexOf('❯')
                return if (first < close && prompt >= 0 && rows[first].text.substring(0, prompt).isBlank()) i..close else null
            }
            if (!r.blank && r.indent < 2 && !r.text.trimStart().startsWith('❯')) return null
        }
        return null
    }

    /**
     * The text in Claude Code's input [box]: the rows from the one starting with ❯ down to the rule
     * that closes it. Claude dims the suggestion it shows in an empty box, so dimmed characters
     * don't count; without styles, every character does.
     */
    private fun inputOf(rows: List<Row>, box: IntRange, style: ((row: Int, index: Int) -> Long)?): String {
        val first = box.first + 1
        val close = box.last
        val prompt = rows[first].text.indexOf('❯')
        return (first until close).joinToString("\n") { r ->
            val text = rows[r].text
            val from = if (r == first) prompt + 1 else 0
            (from until text.length)
                .filter { i -> style == null || TextStyle.decodeEffect(style(r, i)) and TextStyle.CHARACTER_ATTRIBUTE_DIM == 0 }
                .joinToString("") { text[it].toString() }
                .trim()
        }.trim()
    }

    // ---- Claude Code's "You should know" notes ----

    /** What folds the panel away, at the right end of its first row on screen. */
    private const val HANDLE = "[-]"
    /** Under a panel taller than its room: the rows out of sight, as "↑ 3 more · ↓ 12 more". */
    private val CUE = Regex("""^(?:↑ (\d+) more)?(?: · )?(?:↓ (\d+) more)?$""")
    private val TAGGED = Regex("""^(You should know|Heads up) · (.*)$""")
    private val BUTTON = Regex("""^(\d): (\S.*)$""")
    private val BUTTON_START = Regex("""\d: \S""")
    private val BUTTON_GAP = Regex(""" {2,}""")
    private val NOTE_URL = Regex("""https?://\S+""")
    /** The panel wraps its text this many columns short of the screen's width. */
    private const val NOTE_MARGIN = 5
    private const val MAX_NOTE_ROWS = 60

    /**
     * The panel Claude Code's "You should know" plugin draws just above the input box, past the
     * effort badge that may sit between them. The note's first row is blank; then "✦ " and the
     * note, the explanation once open, and the buttons, each a digit in Claude's accent colour,
     * ": " and a label, two spaces or more apart. The "[-]" that folds the panel sits at the right
     * end of its first row: the note's blank one, unless another plugin draws rows of its own over
     * the note, as one that pins background shells does. When the panel is taller than its room
     * it shows a window of its rows with "↑ 3 more · ↓ 12 more" under it, and the "[-]" over the
     * window's first row.
     *
     * @param box the row of the rule over Claude's input box.
     * @param trustFrom the first of [lines] that shows the panel as it is now, unless it is scrolled to its top.
     */
    private fun fyiOf(
        lines: List<String>, rows: List<Row>, box: Int, columns: Int, trustFrom: Int = 0, style: ((row: Int, index: Int) -> Long)?,
    ): FyiNote? {
        val (last, cue) = panelBottom(rows, box, columns) ?: return null
        val bottom = if (cue != null) last - 1 else last
        val top = (bottom downTo maxOf(0, bottom - MAX_NOTE_ROWS)).firstOrNull { isHandle(lines[it], columns) } ?: return null
        val above = cue?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val below = cue?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val window = (top..bottom).map { r ->
            val line = lines[r].replace('\u00a0', ' ').let { if (r == top) it.trimEnd().dropLast(HANDLE.length) else it }.trimEnd()
            fyiRow(line, r, style)
        }
        val trusted = { k: Int -> above == 0 || top + k >= trustFrom }
        val head = window.indices.firstOrNull { k -> trusted(k) && window[k].text.startsWith("✦ ") && (k == 0 || window[k - 1].text.isEmpty()) }
        // At the panel's top, the note's blank row and its ✦ are on screen.
        if (above == 0 && (head == null || head == 0)) return null
        val all = MutableList<FyiRow?>(above + window.size + below) { null }
        window.forEachIndexed { k, row -> if (trusted(k)) all[above + k] = row }
        // Over the ✦, only the note's blank row and another plugin's rows: blank, so rows that
        // change while a shell runs don't make the note look like a new one.
        if (head != null) for (i in 0 until above + head) all[i] = FyiRow("")
        return FyiNote(all, above until above + window.size, top, maxOf(1, columns - NOTE_MARGIN))
    }

    /**
     * The last row of the panel's window above Claude's input [box], past the effort badge, blank
     * rows and Claude's own right-aligned lines there (a hint such as "new task? /clear to save
     * 178.2k tokens", or an update notice), and the "↓ 12 more" cue if that row is one. Those
     * lines end a column or two short of the screen's edge, past any of the panel's rows: at phone
     * width, they may start further left than they end short of it.
     */
    private fun panelBottom(rows: List<Row>, box: Int, columns: Int): Pair<Int, MatchResult?>? {
        val notPanel = { r: Row -> r.blank || r.rightAligned || r.text.length > columns - NOTE_MARGIN || EFFORT_BADGE.containsMatchIn(r.text) }
        var bottom = box - 1
        while (bottom >= 0 && notPanel(rows[bottom])) bottom--
        if (bottom < 0) return null
        val cue = rows[bottom].text.takeIf { it.isNotEmpty() && !it.startsWith(' ') }?.let(CUE::matchEntire)
            ?.takeIf { it.groupValues[1].isNotEmpty() || it.groupValues[2].isNotEmpty() }
        return bottom to cue
    }

    private fun isHandle(line: String, columns: Int): Boolean {
        val l = line.trimEnd()
        return l.endsWith(HANDLE) && l.length >= columns - 2 && (l.length == HANDLE.length || l[l.length - HANDLE.length - 1] == ' ')
    }

    /** A row of the panel, with its buttons apart from its text. */
    private fun fyiRow(line: String, row: Int, style: ((row: Int, index: Int) -> Long)?): FyiRow {
        val hotkey = { i: Int -> style == null || hotkeyAt(style, row, i) }
        val (text, actions) = splitButtons(line, hotkey)
        val first = text.indexOfFirst { it != ' ' }
        if (style == null) return FyiRow(text, actions)
        if (first < 0) return FyiRow(text, actions, bold = false, sketch = false)
        val ink = (first until text.length).filter { text[it] != ' ' }
        // A paragraph's text may have words in bold; a title is bold throughout.
        val bold = ink.all { TextStyle.decodeEffect(style(row, it)) and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0 }
        val colours = ink.map { TextStyle.decodeForeColor(style(row, it)) }.toSet()
        val sketch = colours.size == 1 && colours.first() != TextStyle.COLOR_INDEX_FOREGROUND && !text.trimStart().startsWith("✦")
        return FyiRow(text, actions, bold, sketch)
    }

    /** A button's digit is in a colour of its own, unlike the ": " after it; a "1:" in the note's text is not. */
    private fun hotkeyAt(style: (row: Int, index: Int) -> Long, row: Int, index: Int): Boolean {
        val digit = look(style(row, index))
        return digit != look(style(row, index + 1)) && TextStyle.decodeForeColor(style(row, index)) != TextStyle.COLOR_INDEX_FOREGROUND
    }

    /**
     * Splits [line] into its text and the buttons that end it: from the first place where the rest
     * of the line is nothing but buttons, after the indent, two spaces or " · ".
     */
    private fun splitButtons(line: String, hotkey: (Int) -> Boolean): Pair<String, List<FyiAction>> {
        var from = 0
        while (true) {
            val at = BUTTON_START.find(line, from)?.range?.first ?: return line to emptyList()
            from = at + 1
            val before = line.substring(0, at)
            if (!(before.isBlank() || before.endsWith("  ") || before.endsWith(" · "))) continue
            val parts = line.substring(at).trimEnd().split(BUTTON_GAP)
            var offset = at
            val actions = mutableListOf<FyiAction>()
            for (p in parts) {
                val m = BUTTON.matchEntire(p) ?: break
                if (!hotkey(line.indexOf(p, offset))) break
                offset = line.indexOf(p, offset) + p.length
                actions += FyiAction(m.groupValues[1].toInt(), m.groupValues[2].trim())
            }
            if (actions.size == parts.size) return before.trimEnd().removeSuffix(" ·").trimEnd() to actions
        }
    }

    internal class FyiParts(
        val tag: String?,
        val headline: String?,
        val body: List<FyiLine>,
        val actions: List<FyiAction>,
        /** The row of each button, by index in the panel's rows. */
        val actionRows: Map<FyiAction, Int>,
        val link: String?,
    )

    /** Reads the note, its explanation and its buttons from a panel's rows, as far as they are known. */
    internal fun fyiParts(rows: List<FyiRow?>, width: Int): FyiParts {
        val actionRows = LinkedHashMap<FyiAction, Int>()
        // The buttons at the end, on rows of their own.
        var end = rows.size
        while (end > 0 && rows[end - 1]?.let { it.text.isBlank() && it.actions.isNotEmpty() } == true) end--
        // The ✦ line, wrapped with an indent; buttons may follow it on its last row, as after "Dismissed.".
        val head = rows.indexOfFirst { it?.text?.startsWith("✦ ") == true }
        var tag: String? = null
        var headline: String? = null
        var bodyFrom = 1
        if (head >= 0) {
            val sb = StringBuilder(rows[head]!!.text.removePrefix("✦ ").trim())
            var k = head
            while (rows[k]!!.actions.isEmpty() && k + 1 < end) {
                val next = rows[k + 1] ?: break
                if (next.text.isBlank() || next.bold == true || next.sketch == true || !next.text.startsWith("  ")) break
                join(sb, Row(rows[k]!!.text), Row(next.text), width)
                k++
            }
            for (i in head..k) rows[i]!!.actions.forEach { actionRows[it] = i }
            val m = TAGGED.matchEntire(sb)
            tag = m?.groupValues?.get(1)
            headline = (m?.groupValues?.get(2) ?: sb.toString()).trim()
            bodyFrom = k + 1
        }
        for (i in end until rows.size) rows[i]!!.actions.forEach { actionRows[it] = i }
        val body = fyiBody(rows, bodyFrom, end, width)
        val link = (listOfNotNull(headline) + body.map { it.text })
            .firstNotNullOfOrNull { NOTE_URL.find(it)?.value?.trimEnd('.', ',', ')') }
        return FyiParts(tag, headline, body, actionRows.keys.toList(), actionRows, link)
    }

    /** The explanation in rows [from] until [end]: titles, paragraphs with their lines joined, sketch lines as they are. */
    private fun fyiBody(rows: List<FyiRow?>, from: Int, end: Int, width: Int): List<FyiLine> {
        val out = mutableListOf<FyiLine>()
        var paragraph: StringBuilder? = null
        var prev: FyiRow? = null
        fun close() {
            paragraph?.let { out += FyiLine(FyiLine.Kind.TEXT, it.toString()) }
            paragraph = null
        }
        for (i in from until end) {
            val r = rows[i]
            when {
                r == null -> {
                    close()
                    if (out.lastOrNull()?.kind != FyiLine.Kind.MISSING) out += FyiLine(FyiLine.Kind.MISSING)
                }
                r.text.isBlank() -> {
                    close()
                    if (out.isNotEmpty() && out.last().kind != FyiLine.Kind.GAP) out += FyiLine(FyiLine.Kind.GAP)
                }
                r.sketch ?: looksSketched(r.text) -> {
                    close()
                    out += FyiLine(FyiLine.Kind.SKETCH, r.text.removePrefix("  ").trimEnd())
                }
                // A title starts a card, after a blank row, and may wrap.
                r.bold?.let { it && (prev?.text?.isBlank() != false || out.lastOrNull()?.kind == FyiLine.Kind.TITLE) }
                    ?: looksLikeTitle(rows, i, width) -> {
                    close()
                    out += FyiLine(FyiLine.Kind.TITLE, r.text.trim())
                }
                else -> {
                    val p = paragraph
                    if (p != null && prev != null && wraps(Row(prev.text), Row(r.text), width)) join(p, Row(prev.text), Row(r.text), width)
                    else {
                        close()
                        paragraph = StringBuilder(r.text.trim())
                    }
                }
            }
            prev = r
        }
        close()
        while (out.lastOrNull()?.kind == FyiLine.Kind.GAP) out.removeAt(out.lastIndex)
        return out
    }

    /** Without styles: box-drawing characters and arrows, or more indent than the cards' text has. */
    private fun looksSketched(text: String): Boolean =
        text.indexOfFirst { it != ' ' } > 2 || text.any { it in '─'..'╿' || it in '←'..'⇿' || it in '■'..'◿' }

    /** Without styles: a short line that starts a card and ends before the line had to wrap, without a full stop. */
    private fun looksLikeTitle(rows: List<FyiRow?>, i: Int, width: Int): Boolean {
        val text = rows[i]!!.text
        val next = rows.getOrNull(i + 1)
        val startsCard = rows.getOrNull(i - 1)?.text?.isBlank() == true
        return startsCard && next != null && next.text.isNotBlank() && !wraps(Row(text), Row(next.text), width) &&
            text.trimEnd().last() !in ".,;:!?)\"'…"
    }

    // ---- Claude Code's surveys ----

    private val SURVEY_QUESTION = Regex("""^● \S""")
    /** An answer's key, two columns in or after a space, then ": " and its label, as "1: Bad" or "y: Yes". */
    private val SURVEY_KEY = Regex("""(?:^ {2}| )([0-9a-z]): \S""")
    /** A whole link, not one cut short with "…". */
    private val SURVEY_LINK = Regex("""^https?://[^\s…]+$""")
    /** The rows a survey's question and link may take over its answers. */
    private const val MAX_SURVEY_ROWS = 6
    /** Claude's hints over its input box start this far in or further, even at phone width. */
    private const val HINT_INDENT = 8

    /**
     * The survey Claude Code draws over its input box, and over a note's panel if one shows: "● "
     * and the question, which wraps two columns in, maybe a link, and the answers, two columns in,
     * each a key, ": " and a label. The ● and the keys are in cyan. Blank rows, the effort badge
     * and Claude's hints may sit between the answers and the box; nothing sits between them and a
     * panel.
     *
     * @param under the row the survey sits over: the panel's first, or the rule over the input box.
     */
    private fun surveyOf(rows: List<Row>, under: Int, style: ((row: Int, index: Int) -> Long)?): ClaudeSurvey? {
        var last = under - 1
        while (last >= 0 && rows[last].let { it.blank || it.indent >= HINT_INDENT || EFFORT_BADGE.containsMatchIn(it.text) }) last--
        if (last < 1) return null
        val answers = answersOf(rows[last].text, last, style) ?: return null
        var first = last - 1
        while (!SURVEY_QUESTION.containsMatchIn(rows[first].text)) {
            if (rows[first].indent != 2 || last - first >= MAX_SURVEY_ROWS || first == 0) return null
            first--
        }
        if (style != null && TextStyle.decodeForeColor(style(first, 0)) == TextStyle.COLOR_INDEX_FOREGROUND) return null
        val text = (first until last).map { rows[it].text.trim() }
        // A link under the question takes the rows left, wrapped where it is longer than one.
        val link = text.indexOfFirst { it.startsWith("http://") || it.startsWith("https://") }.takeIf { it > 0 } ?: text.size
        return ClaudeSurvey(
            question = text.take(link).joinToString(" ").removePrefix("● ").removeSuffix("(optional)").trim(),
            answers = answers,
            link = text.drop(link).joinToString("").takeIf { SURVEY_LINK.matches(it) },
        )
    }

    /** The answers that fill [line] from two columns in; null if anything else is on it. */
    private fun answersOf(line: String, row: Int, style: ((row: Int, index: Int) -> Long)?): List<SurveyAnswer>? {
        val keys = SURVEY_KEY.findAll(line).map { it.groups[1]!!.range.first }.filter { style == null || hotkeyAt(style, row, it) }.toList()
        if (keys.size < 2 || keys.first() != 2) return null
        val answers = keys.mapIndexed { n, at -> SurveyAnswer(line[at], line.substring(at + 3, keys.getOrNull(n + 1) ?: line.length).trim()) }
        return answers.takeIf { a -> a.none { it.label.isEmpty() } && a.distinctBy { it.key }.size == a.size }
    }

    // ---- Lines ----

    private const val VERTICAL = "│┃║╎╏┆┇┊┋"
    private const val RULE = "─━═╌╍┄┅┈┉-–—╭╮╯╰┌┐└┘├┤┬┴┼╔╗╚╝▔▁ "
    private const val DASHED = "╌╍┄┅┈┉ "

    /** A screen line with any box border and no-break spaces turned into spaces. */
    private class Row(raw: String) {
        val text: String
        /** The column of the line's right-hand border, if it had one. */
        val limit: Int?

        init {
            val chars = raw.replace(' ', ' ').toCharArray()
            val first = chars.indexOfFirst { it != ' ' }
            var right: Int? = null
            if (first >= 0 && chars[first] in VERTICAL) {
                chars[first] = ' '
                val last = chars.indexOfLast { it != ' ' }
                if (last > first && chars[last] in VERTICAL) {
                    chars[last] = ' '
                    right = last - 1
                }
            }
            text = String(chars).trimEnd()
            limit = right
        }

        val blank = text.isEmpty()
        val indent = text.indexOfFirst { it != ' ' }
        val rule = text.trim().let { t -> t.length >= 3 && t.all { it in RULE } }
        /** A rule with a title in it, such as Claude Code's input box with the session's name: "──── Fix the build ─". */
        val titledRule = !rule && text.trim().let { t ->
            t.length >= 6 && t.first() in RULE && t.last() in RULE && t.count { it in RULE } * 2 >= t.length
        }
        /** Text pushed to the right: more space before it than text, as Claude's hints over its input box. */
        val rightAligned = !blank && indent >= 8 && indent * 2 >= text.length
        /** A dashed rule, such as the ones Claude Code draws around a command or a file's text. */
        val dashed = rule && text.trim().all { it in DASHED }
    }
}
