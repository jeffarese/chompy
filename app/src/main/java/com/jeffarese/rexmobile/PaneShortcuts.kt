package com.jeffarese.rexmobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostState
import com.jeffarese.rexmobile.model.Notifier
import com.jeffarese.rexmobile.ui.REX_CARDS
import com.jeffarese.rexmobile.ui.REX_GLYPHS
import com.jeffarese.rexmobile.ui.Target
import com.jeffarese.rexmobile.ui.glyphName
import com.jeffarese.rexmobile.ui.programOf
import com.jeffarese.rexmobile.ui.representativeBlock
import com.jeffarese.rexmobile.ui.windowTitle
import kotlin.math.roundToInt

/**
 * Launcher shortcuts to the panes the user opened last, so a long press on the app's icon goes straight
 * back to one. They open it as a notification's tap does. The [MAX] most recent are kept, and one goes
 * once its pane is known to have closed.
 */
class PaneShortcuts(private val context: Context) {
    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    /** The shortcuts' ids, newest first. Kept here rather than read from their ranks, which launchers and Android versions treat differently. */
    internal var recent: List<String>
        get() = prefs.getString(RECENT, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        private set(ids) {
            prefs.edit().putString(RECENT, ids.joinToString("\n")).apply()
        }

    /** The user opened [target], on a host in [state]: its shortcut comes first, and the oldest past [MAX] goes. */
    fun opened(target: Target, state: HostState) {
        val view = state.views[target.sessionId] ?: return
        val win = view.windows?.firstOrNull { it.windowId == target.windowId } ?: return
        // A tab opens on its pane that Home shows for it.
        val blockId = target.blockId ?: representativeBlock(win, state.blocks) ?: return
        val meta = state.blocks[blockId]
        // As Home names it: the agent's task, the terminal's title, the program, or the tab.
        val title = windowTitle(win, meta)
        val session = (view.label ?: state.sessions.firstOrNull { it.sessionId == target.sessionId }?.label)
            ?.takeIf { it.isNotBlank() && it != title }
        val id = idOf(target.hostId, blockId)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Notifier.ACTION_OPEN_PANE)
            .putExtra(Notifier.EXTRA_HOST, target.hostId)
            .putExtra(Notifier.EXTRA_SESSION, target.sessionId)
            .putExtra(Notifier.EXTRA_BLOCK, blockId)
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(title)
            .setLongLabel(listOfNotNull(title, session).joinToString(" · "))
            .setIcon(iconOf(programOf(meta)))
            .setIntent(intent)
            .setRank(0)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        val ids = listOf(id) + (recent - id)
        remove(ids.drop(MAX))
        recent = ids.take(MAX)
    }

    /**
     * Drop the shortcuts to panes that are gone: their host was removed, or is connected and no longer
     * has them. A host that isn't connected says nothing either way. [states] are by host id.
     */
    fun prune(hostIds: Set<String>, states: Map<String, HostState>) {
        val gone = recent.filter { closed(it, hostIds, states) }
        if (gone.isEmpty()) return
        remove(gone)
        recent = recent - gone.toSet()
    }

    private fun remove(ids: List<String>) {
        if (ids.isNotEmpty()) runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, ids) }
    }

    /**
     * The program's glyph on its card, as Home draws it, filling an adaptive icon, which the launcher masks
     * to its own shape. A program with no card of its own gets the Shell card: Home's plain tile follows the
     * app's theme, which the launcher doesn't wear.
     */
    private fun iconOf(program: String): IconCompat {
        val name = glyphName(program)
        val glyph = REX_GLYPHS.getValue(name ?: "terminal")
        val card = name?.let { REX_CARDS[it] } ?: REX_CARDS.getValue("terminal")
        val drawable = ContextCompat.getDrawable(context, glyph.res) ?: return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val size = (ADAPTIVE_DP * context.resources.displayMetrics.density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(card.background.toArgb())
        if (!glyph.colored) drawable.setTint(card.foreground.toArgb())
        val side = (size * GLYPH_SHARE).roundToInt()
        val at = (size - side) / 2
        drawable.setBounds(at, at, at + side, at + side)
        drawable.draw(canvas)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    companion object {
        const val MAX = 4
        private const val RECENT = "recent"
        /** An adaptive icon's full size, of which the middle 72 dp show. */
        private const val ADAPTIVE_DP = 108
        /** The glyph's share of the icon's width: about Home's share of its card, within what shows. */
        private const val GLYPH_SHARE = 0.4f

        internal fun idOf(hostId: String, blockId: String) = "$hostId/$blockId"

        /** Whether the pane of shortcut [id] is known to have closed. */
        internal fun closed(id: String, hostIds: Set<String>, states: Map<String, HostState>): Boolean {
            val hostId = id.substringBefore('/')
            val blockId = id.substringAfter('/')
            if (hostId !in hostIds) return true
            val state = states[hostId]?.takeIf { it.status == ConnStatus.ONLINE } ?: return false
            return state.views.values.none { v -> v.windows.orEmpty().any { w -> w.blocks.any { it.blockId == blockId } } }
        }
    }
}
