package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jeffarese.rexmobile.model.BlockMeta
import com.jeffarese.rexmobile.rex.WindowView

// How blocks and windows are named and drawn in lists.

/** "file://host/Users/jeff/src/app" → "~/src/app". */
fun prettyPath(pwd: String?): String? {
    if (pwd.isNullOrBlank()) return null
    // Only the file:// form is escaped; a plain path is used as it is, so a folder named "100%" stays so.
    var path = if (pwd.startsWith("file://")) {
        val rest = pwd.removePrefix("file://")
        percentDecode("/" + rest.substringAfter('/', ""))
    } else pwd
    val home = Regex("^/(Users|home)/[^/]+").find(path)
    if (home != null) path = "~" + path.substring(home.value.length)
    if (path == "/root" || path.startsWith("/root/")) path = "~" + path.removePrefix("/root")
    return path
}

/**
 * A URL path's %XX escapes, as UTF-8. Unlike URLDecoder, a '+' stays a '+' (so "c++" survives) and a
 * '%' not followed by two hex digits is kept as it is rather than throwing while Home draws.
 */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hi = if (c == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
        val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
        if (lo >= 0) {
            bytes.write(hi * 16 + lo)
            i += 3
        } else {
            // A whole code point, so a surrogate pair isn't split.
            val cp = s.codePointAt(i)
            bytes.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
    }
    // Bytes that aren't valid UTF-8 become U+FFFD; decoding never throws.
    return bytes.toString(Charsets.UTF_8.name())
}

/** The program a block runs, for picking its icon. */
fun programOf(meta: BlockMeta?): String {
    if (meta == null) return ""
    val app = meta.app?.lowercase()
    if (app != null && app.isNotBlank()) return app
    val argv0 = meta.argv0?.substringAfterLast('/')?.removePrefix("-")?.lowercase()
    val proc = meta.process?.lowercase().orEmpty()
    // A wrapper such as a shim often runs with the real program's name in argv0.
    return if (!argv0.isNullOrBlank() && argv0 != proc && proc !in SHELLS) argv0 else proc
}

private val SHELLS = setOf(
    "zsh", "bash", "fish", "sh", "ash", "nu", "nushell", "dash", "ksh", "tcsh", "login", "pwsh", "powershell", "elvish", "xonsh", "cmd",
)

private val DEFAULT_WINDOW_LABEL = Regex("^Window [0-9a-f]{6,}$")

/** The main line for a block: an agent's task, the terminal title, or the program. */
fun blockTitle(meta: BlockMeta?, fallback: String = "Terminal"): String {
    if (meta == null) return fallback
    meta.taskTitle?.let { return it }
    val title = meta.title.trim()
    val program = displayProgram(meta)
    if (title.isNotEmpty() && !title.equals(program, ignoreCase = true) && !looksLikePrompt(title)) return title
    return program.ifBlank { fallback }
}

fun windowTitle(win: WindowView, meta: BlockMeta?): String {
    val label = win.label?.takeIf { it.isNotBlank() && !DEFAULT_WINDOW_LABEL.matches(it) }
    val fromBlock = blockTitle(meta, "")
    return when {
        meta?.taskTitle != null -> fromBlock
        label != null && (fromBlock.isBlank() || fromBlock == displayProgram(meta)) -> label
        else -> fromBlock.ifBlank { label ?: "Terminal" }
    }
}

/** True when the main line is just the program's name, so the path is worth showing beside it. */
fun titleIsProgram(meta: BlockMeta?, title: String) = meta != null && title.equals(displayProgram(meta), ignoreCase = true)

private fun looksLikePrompt(title: String) = title.contains('@') && title.contains(':')

fun displayProgram(meta: BlockMeta?): String = when (val p = programOf(meta)) {
    "claude-code", "claude" -> "Claude"
    "codex" -> "Codex"
    "nvim" -> "Neovim"
    "vim" -> "Vim"
    "python", "python3" -> "Python"
    "node" -> "Node"
    "" -> ""
    else -> p
}

/** The block to represent a window in lists: the one that most needs attention, else the focused one. */
fun representativeBlock(win: WindowView, metas: Map<String, BlockMeta>): String? {
    val ids = win.blocks.map { it.blockId }
    val urgent = ids.maxByOrNull { id -> metas[id]?.status?.let { com.jeffarese.rexmobile.model.statusRank(it.state) } ?: 0 }
    val urgentRank = urgent?.let { metas[it]?.status?.let { s -> com.jeffarese.rexmobile.model.statusRank(s.state) } } ?: 0
    return if (urgentRank >= 3) urgent else win.focusedBlockId?.takeIf { it in ids } ?: ids.firstOrNull()
}

/** Other names programs run under, mapped to the Rex glyph for them. */
private val GLYPH_ALIASES = mapOf(
    "claude-code" to "claude",
    "nvim" to "neovim",
    "vi" to "vim",
    "helix" to "hx",
    "kak" to "kakoune",
    "ipython" to "python",
    "npm" to "node",
    "npx" to "node",
    "deno" to "node",
    "bunx" to "bun",
    "lazygit" to "git",
    "tig" to "git",
    "gh" to "github",
    "glab" to "gitlab",
    "lazydocker" to "docker",
    "kubectl" to "kube",
    "cargo" to "rust",
    "rustc" to "rust",
    "irb" to "ruby",
    "iex" to "elixir",
    "mix" to "elixir",
    "uvx" to "uv",
    "sqlite3" to "sqlite",
    "psql" to "postgres",
    "wrangler" to "cloudflare",
    "cursor-agent" to "cursor",
    "kiro-cli" to "kiro",
    "buck2" to "buck",
    "tofu" to "terraform",
    "delta" to "hunk",
    "diff" to "hunk",
    "tail" to "log",
    "less" to "log",
    "glances" to "top",
)

/** The name of the Rex glyph for [program], as Rex.app picks it: any shell gets its Shell card, others go by name, then alias. */
private fun glyphName(program: String): String? = when {
    program in SHELLS -> "terminal"
    program.startsWith("claude") -> "claude"
    program.startsWith("python") -> "python"
    else -> (GLYPH_ALIASES[program] ?: program).takeIf { it in REX_GLYPHS }
}

@Composable
fun ProgramIcon(program: String, size: Dp = 30.dp, modifier: Modifier = Modifier, tile: Color = Rex.surfaceHigh) {
    val name = glyphName(program)
    // A program Rex has no glyph for shows the terminal glyph on the plain tile, not the Shell card.
    val glyph = REX_GLYPHS.getValue(name ?: "terminal")
    // Rex's own card where it has one, such as Claude's orange; otherwise the plain tile.
    val card = name?.let { REX_CARDS[it] }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .background(card?.background ?: tile),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(glyph.res),
            contentDescription = null,
            modifier = Modifier.size(size * 0.66f),
            colorFilter = when {
                glyph.colored -> null
                card != null -> ColorFilter.tint(card.foreground)
                else -> ColorFilter.tint(Rex.text)
            },
        )
    }
}
