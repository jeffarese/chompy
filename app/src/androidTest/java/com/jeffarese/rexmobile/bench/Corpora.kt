package com.jeffarese.rexmobile.bench

import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Synthetic terminal output for the engine benchmarks, the same bytes on every run: each corpus
 * comes from a fixed seed. None of it is recorded from a real session.
 */
object Corpora {
    private const val E = "\u001b"

    /** Each corpus is about this long. */
    const val TARGET_BYTES = 4 * 1024 * 1024

    val names = listOf("ascii", "sgr", "unicode", "tui")

    fun build(name: String, columns: Int, rows: Int): ByteArray = when (name) {
        "ascii" -> ascii(columns)
        "sgr" -> sgr(columns)
        "unicode" -> unicode(columns)
        "tui" -> tui(columns, rows)
        else -> error("no corpus $name")
    }

    /** Lines of printable ASCII words, up to a screen wide, as a build log or a long listing prints. */
    fun ascii(columns: Int): ByteArray = lines { r, sb ->
        val width = 1 + r.nextInt(columns)
        while (sb.length < width) {
            repeat(1 + r.nextInt(10)) { sb.append(('!' + r.nextInt(94))) }
            sb.append(' ')
        }
        sb.setLength(width)
    }

    /** Short runs, each in its own style: 256-color and truecolor, foreground and background, bold and the rest. */
    fun sgr(columns: Int): ByteArray = lines { r, sb ->
        var width = 0
        while (width < columns - 10) {
            sb.append(E).append('[')
            when (r.nextInt(6)) {
                0 -> sb.append("38;5;").append(r.nextInt(256))
                1 -> sb.append("48;5;").append(r.nextInt(256))
                2 -> sb.append("38;2;").append(r.nextInt(256)).append(';').append(r.nextInt(256)).append(';').append(r.nextInt(256))
                3 -> sb.append("48;2;").append(r.nextInt(256)).append(';').append(r.nextInt(256)).append(';').append(r.nextInt(256))
                4 -> sb.append(listOf("1", "2", "3", "4", "7", "9", "1;4", "22;23;24").random(r))
                else -> sb.append('0')
            }
            sb.append('m')
            val n = 1 + r.nextInt(8)
            repeat(n) { sb.append('a' + r.nextInt(26)) }
            width += n
        }
        sb.append(E).append("[0m")
    }

    private val WIDE = listOf("日本語", "中文字", "한국어", "漢字", "テスト")
    private val EMOJI = listOf(
        "😀", "👍🏽", "🇯🇵", "❤️",
        "👨‍👩‍👧", "🧑‍💻", "✅", "🚀",
    )
    private val NARROW = listOf("é", "ñ", "ü", "é", "ộ", "─", "│", "╭", "╯", "⏺", "⎿", "✻", "→", "•")

    /** Wide characters, emoji sequences, combining marks and line drawing among ASCII words. */
    fun unicode(columns: Int): ByteArray = lines { r, sb ->
        var width = 0
        while (width < columns - 12) {
            when (r.nextInt(4)) {
                0 -> WIDE.random(r).let { sb.append(it); width += it.length * 2 }
                1 -> EMOJI.random(r).let { sb.append(it); width += 2 }
                2 -> repeat(3) { sb.append(NARROW.random(r)); width++ }
                else -> repeat(1 + r.nextInt(6)) { sb.append('a' + r.nextInt(26)); width++ }
            }
            sb.append(' ')
            width++
        }
    }

    /**
     * A full-screen program redrawing itself, as Claude Code's fullscreen interface does: each frame
     * is a synchronized update (mode 2026) that moves the cursor to every row and draws it styled,
     * a bordered input box and a spinner at the bottom.
     */
    fun tui(columns: Int, rows: Int): ByteArray {
        val r = Random(4)
        val out = ByteArrayOutputStream(TARGET_BYTES + 64 * 1024)
        val sb = StringBuilder()
        var frame = 0
        while (out.size() < TARGET_BYTES) {
            sb.setLength(0)
            sb.append(E).append("[?2026h").append(E).append("[?25l")
            for (row in 1..rows) {
                sb.append(E).append('[').append(row).append(";1H")
                when {
                    row == rows - 3 -> sb.append(E).append("[38;5;244m╭").append("─".repeat(columns - 2)).append('╮').append(E).append("[0m")
                    row == rows - 2 -> sb.append(E).append("[38;5;244m│").append(E).append("[0m > ").append("typing ".repeat(3).take(columns - 6))
                        .append(E).append('[').append(columns).append('G').append(E).append("[38;5;244m│").append(E).append("[0m")
                    row == rows - 1 -> sb.append(E).append("[38;5;244m╰").append("─".repeat(columns - 2)).append('╯').append(E).append("[0m")
                    row == rows -> sb.append(E).append("[38;2;215;119;87m").append("✻·✢✳✶✻✽"[frame % 7]).append(E).append("[0m Thinking… ")
                        .append(E).append("[2m(").append(frame).append("s · esc to interrupt)").append(E).append("[0m")
                    else -> {
                        val bullet = if (r.nextInt(5) == 0) "$E[38;2;120;200;120m⏺$E[0m " else "  "
                        sb.append(bullet)
                        var width = 2
                        val limit = r.nextInt(columns - 4)
                        while (width < limit) {
                            if (r.nextInt(4) == 0) sb.append(E).append("[1m")
                            if (r.nextInt(6) == 0) sb.append(E).append("[38;5;").append(r.nextInt(256)).append('m')
                            val n = 1 + r.nextInt(9)
                            repeat(minOf(n, limit - width)) { sb.append('a' + r.nextInt(26)) }
                            sb.append(E).append("[0m ")
                            width += n + 1
                        }
                    }
                }
                sb.append(E).append("[K")
            }
            sb.append(E).append('[').append(rows - 2).append(";5H").append(E).append("[?25h").append(E).append("[?2026l")
            out.write(sb.toString().toByteArray())
            frame++
        }
        return out.toByteArray()
    }

    private fun lines(seed: Long = 1, line: (Random, StringBuilder) -> Unit): ByteArray {
        val r = Random(seed)
        val out = ByteArrayOutputStream(TARGET_BYTES + 4096)
        val sb = StringBuilder()
        while (out.size() < TARGET_BYTES) {
            sb.setLength(0)
            line(r, sb)
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
        }
        return out.toByteArray()
    }

    /** [n] lines of [columns] random base64 characters, as the live load benchmark fills a scrollback with. */
    fun base64Lines(n: Int, columns: Int, styled: Boolean): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val r = Random(7)
        val out = ByteArrayOutputStream(n * (columns + 40))
        val sb = StringBuilder()
        repeat(n) {
            sb.setLength(0)
            for (c in 0 until columns) {
                // Styled lines change color every ten characters or so, as highlighted code does.
                if (styled && c % 10 == 0) sb.append(E).append("[38;5;").append(r.nextInt(256)).append('m')
                sb.append(alphabet[r.nextInt(64)])
            }
            if (styled) sb.append(E).append("[0m")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
        }
        return out.toByteArray()
    }

    /** Split [bytes] into chunks of [size], as output arrives from the network. */
    fun chunks(bytes: ByteArray, size: Int = 16 * 1024): List<ByteArray> =
        (bytes.indices step size).map { bytes.copyOfRange(it, minOf(bytes.size, it + size)) }

    private fun <T> List<T>.random(r: Random): T = this[r.nextInt(size)]
}
