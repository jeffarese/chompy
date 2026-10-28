package com.jeffarese.rexmobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val URL = Regex("""https?://[^\s<>"'`─-╿]+""")
private val URL_CHARS = Regex("""^[^\s<>"'`─-╿]+""")

/**
 * Web links in a terminal's text, newest first, without repeats.
 *
 * Programs such as Claude Code wrap long lines themselves, so a link that runs to the right edge
 * continues on the next line after its indent; those pieces are joined back together.
 *
 * @param lines the terminal's lines, top to bottom, with lines the terminal itself wrapped already joined.
 * @param columns the terminal's width.
 */
fun findLinks(lines: List<String>, columns: Int, max: Int = 10): List<String> {
    val found = LinkedHashSet<String>()
    for ((i, line) in lines.withIndex()) {
        for (m in URL.findAll(line)) {
            var url = m.value
            var row = i
            var endsLine = m.range.last == line.trimEnd().lastIndex
            while (endsLine && reachesEdge(lines[row], columns)) {
                val next = lines.getOrNull(row + 1)?.trimStart(*INDENT)?.trimEnd() ?: break
                val piece = URL_CHARS.find(next)?.value ?: break
                if (piece.startsWith("http")) break
                url += piece
                row++
                endsLine = piece.length == next.length
            }
            // Seen again lower down: it is newer there.
            trimLink(url).let { found -= it; found += it }
        }
    }
    return found.reversed().take(max)
}

private val INDENT = charArrayOf(' ', '\t', '│', '⎿')

private fun reachesEdge(line: String, columns: Int): Boolean {
    val n = line.trimEnd().length
    return columns > 0 && n > 0 && (n % columns == 0 || n % columns >= columns - 4)
}

/** Drops punctuation that ends the sentence around a link rather than the link. */
private fun trimLink(url: String): String {
    val u = url.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
    return if (u.endsWith(')') && '(' !in u) u.dropLast(1) else u
}

/** Lets the user pick one of [links] to open in the browser. */
@Composable
fun LinksDialog(links: List<String>, context: Context, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open link") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                links.forEach { url ->
                    Text(
                        url.removePrefix("https://").removePrefix("http://"), color = Rex.unseen, fontSize = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable {
                            onDismiss()
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
