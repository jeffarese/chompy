package com.jeffarese.rexmobile.bench

import com.jeffarese.rexmobile.terminal.RemoteTerminal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Whether the phone shows what the server's terminal shows: a fixed corpus of hard cases, read row
 * by row on both, and the input modes a program turns on, each both as they arrive on a live
 * stream and after a fresh stream redraws the screen from `format`.
 *
 * Skipped unless REX_LIVE_HOST is set. It uses one throwaway session, zz-bench-fidelity, and
 * destroys it when done. It reports mismatches rather than failing on them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFidelityBench {
    @Test
    fun screenAndModesMatchTheServer() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val result = buildJsonObject {
            put("engine", BenchEngine.NAME)
            put("columns", COLUMNS)
            put("rows", ROWS)
            LiveHost(address!!).use { live ->
                val (sid, bid) = live.newSession("zz-bench-fidelity")
                var t: RemoteTerminal? = null
                try {
                    live.ownAndResize(sid, bid, COLUMNS, ROWS)
                    t = open(live, sid, bid)

                    // The corpus arrives on the live stream, so the phone's own parser draws it.
                    val corpus = corpus()
                    live.type(sid, bid, "clear; printf '%s' '${Base64.getEncoder().encodeToString(corpus.toByteArray())}' | base64 -d; cat\r")
                    MainLoop.waitFor("the corpus on the phone", 20_000) { BenchEngine.visibleLines(t!!.session).any { "CORPUS-END" in it } }
                    MainLoop.run(1_000)
                    put("textLive", compare(live, sid, bid, t))
                    put("stylesLive", styles(live, sid, bid, t))

                    // A fresh stream redraws the screen from the server's format instead.
                    t.close()
                    t = open(live, sid, bid)
                    put("textFresh", compare(live, sid, bid, t))
                    put("stylesFresh", styles(live, sid, bid, t))

                    // Modes, as a program sets them; cat keeps the shell from setting its own.
                    live.type(sid, bid, "\u0003")
                    MainLoop.run(500)
                    live.type(sid, bid, "clear; printf '$MODES_PRINTF'; echo MODES-$((40+2)); cat\r")
                    MainLoop.waitFor("the modes set", 20_000) { BenchEngine.visibleLines(t!!.session).any { "MODES-42" in it } }
                    MainLoop.run(1_000)
                    put("modesLive", modes(live, sid, bid, t))
                    t.close()
                    t = open(live, sid, bid)
                    put("modesFresh", modes(live, sid, bid, t))
                    live.type(sid, bid, "\u0003")
                } finally {
                    t?.close()
                    live.destroy(sid)
                }
            }
        }
        writeResult("live-fidelity-${BenchEngine.NAME}", result)
    }

    private fun open(live: LiveHost, sid: String, bid: String): RemoteTerminal {
        val t = RemoteTerminal(live.host, sid, bid, live.dataClient().first)
        t.start()
        awaitLoaded(t, 30_000)
        MainLoop.run(500)
        return t
    }

    /**
     * The phone's visible rows against the server's plain text, row by row. The server's text is
     * its scrollback and screen with trailing blank rows left out, so the two line up at the
     * bottom of what each shows.
     */
    private fun compare(live: LiveHost, sid: String, bid: String, t: RemoteTerminal): JsonObject {
        val phone = BenchEngine.visibleLines(t.session).dropLastWhile { it.isBlank() }
        val server = live.text(sid, bid).split('\n').map { it.trimEnd() }.dropLastWhile { it.isBlank() }
        val serverRows = List(maxOf(0, phone.size - server.size)) { "" } + server.takeLast(phone.size)
        val mismatches = phone.indices.filter { phone[it] != serverRows[it] }
        return buildJsonObject {
            put("rowsCompared", phone.size)
            put("rowsMismatched", mismatches.size)
            putJsonArray("mismatches") {
                for (row in mismatches) add(buildJsonObject {
                    put("row", row)
                    put("phone", phone[row])
                    put("server", serverRows[row])
                })
            }
        }
    }

    /**
     * The style of the first cell of each of [STYLE_PROBES] on the phone against the server's, read
     * from its HTML format. Each probe sits in a row with only ASCII before it, so its column is
     * its index in the row.
     */
    private fun styles(live: LiveHost, sid: String, bid: String, t: RemoteTerminal): JsonObject {
        val html = live.method(sid, bid, "format", buildJsonObject { put("format", "html") }).jsonObject.str("content").orEmpty()
        val rows = BenchEngine.visibleLines(t.session)
        var mismatched = 0
        val probes = buildJsonObject {
            for (word in STYLE_PROBES) {
                val row = rows.indexOfFirst { it.contains(" $word") }
                val phone = if (row < 0) null else BenchEngine.cellStyle(t.session, row, rows[row].indexOf(" $word") + 1)
                val server = serverStyle(html, word)
                if (phone != server) mismatched++
                putJsonObject(word) {
                    put("server", server?.toString())
                    put("phone", phone?.toString())
                    put("match", phone == server)
                }
            }
        }
        return buildJsonObject {
            put("mismatched", mismatched)
            put("probes", probes)
        }
    }

    /** The style the server's HTML gives the last run of text that starts with the word [word], not inside a tag. */
    private fun serverStyle(html: String, word: String): CellStyle? {
        val run = (HTML_RUN.pattern + "(?<=[> ])" + Regex.escape(word) + "(?=[ <])").toRegex().findAll(html).lastOrNull() ?: return null
        val css = run.groupValues[3].split(';')
            .mapNotNull { d -> d.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        fun rgb(v: String?) = v?.let { PALETTE.find(it) }?.let { "palette:${it.groupValues[1]}" }
            ?: v?.let { RGB.find(it) }?.destructured?.let { (r, g, b) -> "#%02x%02x%02x".format(r.toInt(), g.toInt(), b.toInt()) }
        val line = css["text-decoration-line"].orEmpty()
        return CellStyle(
            fg = rgb(css["color"]),
            bg = rgb(css["background-color"]),
            bold = css["font-weight"] == "bold",
            italic = css["font-style"] == "italic",
            strike = "line-through" in line,
            underline = if ("underline" !in line) null else when (val s = css["text-decoration-style"]) {
                "wavy" -> "curly"
                null, "solid" -> "single"
                else -> s
            },
            underlineColor = rgb(css["text-decoration-color"]),
            hyperlink = run.groupValues[2].ifEmpty { null },
        )
    }

    /** The modes the phone's emulator believes are on against the server's own. */
    private fun modes(live: LiveHost, sid: String, bid: String, t: RemoteTerminal): JsonObject {
        val list = live.method(sid, bid, "modes").jsonObject["modes"] as? JsonArray
        val server = list.orEmpty().map { it.jsonObject }
            .filter { (it["ansi"] as? JsonPrimitive)?.booleanOrNull != true }
            .associate { it.int("number")!! to (it["set"] as JsonPrimitive).booleanOrNull }
        val names = list.orEmpty().map { it.jsonObject }.filter { (it["ansi"] as? JsonPrimitive)?.booleanOrNull != true }
            .associate { it.int("number")!! to it.str("name") }
        val phone = BenchEngine.decModes(t.session, MODES)
        val serverKitty = kittyFlags(live, sid, bid)
        val phoneKitty = BenchEngine.kittyKeyboardFlags(t.session)
        return buildJsonObject {
            putJsonArray("modes") {
                for (n in MODES) add(buildJsonObject {
                    put("mode", n)
                    put("name", names[n])
                    put("server", server[n])
                    put("phone", phone[n])
                    put("match", server[n] == phone[n])
                })
            }
            putJsonObject("kittyKeyboardFlags") {
                put("server", serverKitty)
                put("phone", phoneKitty)
                put("match", serverKitty == phoneKitty)
            }
            put("mismatched", MODES.count { server[it] != phone[it] } + if (serverKitty != phoneKitty) 1 else 0)
        }
    }

    /** The Kitty keyboard flags the server's terminal has, from the state its vt format ends with. */
    private fun kittyFlags(live: LiveHost, sid: String, bid: String): Int? {
        val vt = live.method(sid, bid, "format", buildJsonObject {
            put("format", "vt")
            put("extra_kitty_keyboard", true)
        }).jsonObject.str("content").orEmpty()
        return KITTY.findAll(vt).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Hard cases, drawn from the top of a cleared 80-column screen: grapheme clusters, wide
     * characters, a wide one that doesn't fit at the right edge, tabs, line drawing, a scroll
     * region, inserting and deleting lines and characters, REP, a hyperlink, underline styles and
     * colors. Each row ends in | where it can, so a shift shows.
     */
    private fun corpus(): String {
        val e = "\u001b"
        val sb = StringBuilder()
        // Content, then a bar at column 60: the spaces before it show the width each side gave the content.
        fun row(s: String) = sb.append(s).append("$e[60G|\r\n")
        row("emoji zwj: 👨‍👩‍👧‍👦 🏳️‍🌈 🧑‍💻 |")
        row("flags: 🇯🇵 🇪🇸 🇺🇸 |")
        row("skin: 👍🏽 👋🏿 |")
        row("vs16: ❤️ ☀️ ✔️ | text: ❤ ☀ ✔ |")
        row("combining: é ä ñ ộ Z̵ |")
        row("cjk: 日本語の文字 한국어 中文 |")
        // 79 columns, then a wide character that can't fit in the last one and wraps.
        sb.append("w".repeat(79) + "中文|\r\n")
        row("tabs:\ta\tb\tc|")
        row("\t\t\tX|")
        row("box: ┌──┬──┐ │ab│cd│ └──┴──┘ ╔═╗ ╠╬╣ |")
        row("dec: $e(0lqqwqqk x x mqqvqqj ~ a `$e(B back |")
        // A scroll region of rows 14 to 17: six lines into four rows leave R3 to R6.
        sb.append("$e[14;17r$e[14;1H")
        sb.append((1..6).joinToString("\r\n") { "region R$it$e[60G|" })
        sb.append("$e[r$e[18;1H")
        // Insert a line above IL-B, then delete the line after it: IL-A, blank, IL-B.
        row("IL-A |")
        row("IL-B |")
        row("IL-C |")
        sb.append("$e[19;1H$e[L$e[21;1H$e[M$e[21;1H")
        // Insert two blanks before "cd", then delete the "f".
        row("chars: abcdef$e[4D$e[2@$e[4C$e[P|")
        row("rep: Z$e[9b|")
        row("osc8: $e]8;;https://example.com/bench$e\\link$e]8;;$e\\ after |")
        row("ul: $e[4:3mcurly$e[0m $e[4:2mdouble$e[0m $e[58:2::255:0:0m$e[4mred-ul$e[59;24m $e[4:4mdotted$e[0m |")
        row("color: $e[38;2;255;120;0morange$e[0m $e[48;2;0;0;128mon-navy$e[0m $e[38;5;196m196$e[0m $e[1;3;9mbis$e[0m |")
        sb.append("CORPUS-END\r\n")
        return sb.toString()
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 30
        /** Mouse reporting 1000, 1002 and 1006, bracketed paste, application cursor keys and keypad, and Kitty keyboard flags 1. */
        const val MODES_PRINTF = "\\e[?1000h\\e[?1002h\\e[?1006h\\e[?2004h\\e[?1h\\e=\\e[>1u"
        val MODES = listOf(1, 7, 25, 66, 1000, 1002, 1003, 1004, 1006, 1049, 2004, 2026)
        /** How a vt format sets the Kitty keyboard flags: CSI = flags ; 1 u, or a push, CSI > flags u. */
        val STYLE_PROBES = listOf("curly", "double", "red-ul", "dotted", "link", "orange", "on-navy", "196", "bis")
        /** An optional link, then an optional styled run, before the probe's text. */
        val HTML_RUN = Regex("""(<a href="([^"]*)">)?(?:<div style="([^"]*)">)?""")
        val PALETTE = Regex("""var\(--vt-palette-(\d+)\)""")
        val RGB = Regex("""rgb\((\d+), *(\d+), *(\d+)\)""")
        val KITTY = Regex("""\u001b\[[=>](\d+)(?:;1)?u""")
    }
}
