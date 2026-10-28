package com.jeffarese.rexmobile.terminal

/**
 * Cuts a `terminal.format` redraw (vt) down to its last lines. A long conversation's scrollback runs
 * to megabytes, while the emulator here keeps [RemoteTerminal.TRANSCRIPT_ROWS] rows: the rest would
 * be downloaded and drawn only to scroll away. The redraw opens with the terminal's modes and tab
 * stops and the cursor sent home, then writes one line per row, and ends with the cursor's place.
 */
internal object FormatTail {
    private const val ESC = '\u001b'
    private const val REPLACEMENT = '�'

    /** The escape sequences the redraw opens with, before its first line. */
    fun preamble(head: String): String {
        var i = 0
        while (i + 1 < head.length && head[i] == ESC) {
            var j = i + 1
            if (head[j] == '[') {
                j++
                while (j < head.length && head[j].code !in 0x40..0x7e) j++
                // A style opens the first line's text.
                if (j < head.length && head[j] == 'm') break
            } else {
                // Intermediates, as in ESC ( B, then the final character.
                while (j < head.length && head[j].code in 0x20..0x2f) j++
            }
            if (j >= head.length) break
            i = j + 1
        }
        return head.substring(0, i)
    }

    /**
     * The redraw from [preamble] and its last [lines] lines, out of [tail]: text read from partway
     * through, whose first line is dropped as it may start partway through a character or an escape
     * sequence. Null when [tail] doesn't hold that many whole lines.
     */
    fun join(preamble: String, tail: String, lines: Int): String? {
        var from = tail.length
        repeat(lines) {
            from = tail.lastIndexOf('\n', from - 1)
            if (from < 0) return null
        }
        // Styles reset at each run, but the line cut into may have left one in effect.
        return preamble + "$ESC[0m" + tail.substring(from + 1)
    }

    /**
     * The bytes [data] stood for, read from an offset that may fall inside a character: the server
     * sends a replacement character for each byte of one cut into, where the text has three bytes.
     */
    fun bytesRead(data: String, fromAnyOffset: Boolean): Long {
        val size = data.toByteArray().size.toLong()
        if (!fromAnyOffset) return size
        return size - 2L * stray(data)
    }

    /** The replacement characters that stand for the bytes of a character cut into, at the start of text read from any offset. */
    private fun stray(data: String) = data.take(3).takeWhile { it == REPLACEMENT }.length

    /** The start of [data] that stood for its first [bytes] bytes, counted as [bytesRead] counts them. */
    fun prefix(data: String, bytes: Long, fromAnyOffset: Boolean): String {
        val stray = if (fromAnyOffset) stray(data) else 0
        var n = 0L
        var i = 0
        while (i < data.length && n < bytes) {
            val c = data.codePointAt(i)
            n += when {
                i < stray -> 1
                c < 0x80 -> 1
                c < 0x800 -> 2
                c < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(c)
        }
        return data.substring(0, i)
    }

    /**
     * The redraw of [total] bytes whose first part, [head], has been read: whole, or when it is
     * longer, the opening and its last [lines] lines. Those are read from [tailBytes] before its end
     * first; when that holds too few lines, only what is missing before it is read, sized from the
     * lines read so far, up to the end of the line it cut into: nothing else is read twice.
     *
     * [read] gives the text from one byte offset to at least another, or to the end; cut is true
     * when the first offset may fall inside a character.
     */
    suspend fun load(head: String, total: Long, lines: Int, tailBytes: Long, read: suspend (from: Long, until: Long, cut: Boolean) -> String): String {
        val headBytes = head.toByteArray().size.toLong()
        var from = maxOf(headBytes, total - tailBytes)
        var text = read(from, total, from > headBytes)
        while (true) {
            if (from <= headBytes) return head + text
            join(preamble(head), text, lines)?.let { return it }
            // The lines read so far, at the bytes each took, tell how much further back the rest begins.
            val have = text.count { it == '\n' }
            val perLine = bytesRead(text, fromAnyOffset = true) / maxOf(1, have)
            val earlier = maxOf(headBytes, from - maxOf(MIN_STEP_BYTES, (lines - have + 1) * perLine * 5 / 4))
            val nl = text.indexOf('\n')
            text = if (nl < 0) {
                // Not even one line break to join at: read it all again from there.
                read(earlier, total, earlier > headBytes)
            } else {
                // Up to the line break that ends the first line read so far, which may have begun partway.
                val upTo = from + bytesRead(text.substring(0, nl + 1), fromAnyOffset = true)
                prefix(read(earlier, upTo, earlier > headBytes), upTo - earlier, earlier > headBytes) + text.substring(nl + 1)
            }
            from = earlier
        }
    }

    /** The least to go back for more lines, so a server sending little can't make it creep. */
    private const val MIN_STEP_BYTES = 64L * 1024
}
