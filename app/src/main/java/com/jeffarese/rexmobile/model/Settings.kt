package com.jeffarese.rexmobile.model

import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue
import com.jeffarese.rexmobile.terminal.TerminalViewport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** When a terminal keeps the phone's screen from turning off. */
enum class KeepScreenOn(val label: String) {
    WORKING("While an agent works"),
    ALWAYS("Always"),
    NEVER("Never"),
}

/** One setting: its current value, which screens can watch, and what saves it. */
class Setting<T>(initial: T, private val save: (T) -> Unit) {
    private val _flow = MutableStateFlow(initial)
    val flow: StateFlow<T> = _flow

    var value: T
        get() = _flow.value
        set(v) {
            if (v == _flow.value) return
            save(v)
            _flow.value = v
        }
}

/**
 * What the user can change in Settings, kept in the "ui" preferences beside the screens' own.
 * Every default is how the app behaved before Settings existed, and the terminal's text size and
 * size mode keep the keys the terminal screen already saved them under.
 */
class Settings(context: Context, private val prefs: SharedPreferences) {
    private val metrics = context.resources.displayMetrics

    /** Phone-mode text size in pixels. Pinching a terminal changes it too. */
    val textSizePx = Setting(prefs.getInt(TEXT_SIZE, defaultTextPx).coerceIn(minTextPx, maxTextPx)) {
        prefs.edit().putInt(TEXT_SIZE, it).apply()
    }

    /** Resize terminals to this phone (true), or mirror the desktop's size. The terminal's top bar toggles it too. */
    val phoneMode = bool(PHONE_MODE, true)

    /** The terminal's top bar folded to one thin line, giving the terminal its room. */
    val terminalHeaderCollapsed = bool(TERMINAL_HEADER_COLLAPSED, false)

    /** Claude's strip of mode, effort and commands shown over the key bar; hidden until the key bar's button shows it. */
    val claudeStripShown = bool(CLAUDE_STRIP_SHOWN, false)

    val keepScreenOn = Setting(
        prefs.getString(KEEP_SCREEN_ON, null)?.let { s -> KeepScreenOn.entries.firstOrNull { it.name == s } } ?: KeepScreenOn.WORKING,
    ) { prefs.edit().putString(KEEP_SCREEN_ON, it.name).apply() }

    /** Dark, light, or as the phone is: which of [themeDark] and [themeLight] the app wears. */
    val appearance = Setting(
        prefs.getString(APPEARANCE, null)?.let { s -> Appearance.entries.firstOrNull { it.name == s } } ?: Appearance.DARK,
    ) { prefs.edit().putString(APPEARANCE, it.name).apply() }

    /** The theme while dark, by its Rex id, or [Themes.DEFAULT] for the app's own look. */
    val themeDark = string(THEME_DARK, Themes.DEFAULT)

    /** The theme while light, by its Rex id. */
    val themeLight = string(THEME_LIGHT, Themes.DEFAULT_LIGHT)

    /** Terminals in the theme's colors (true), or in those the Mac sends. The app's own look always takes the Mac's. */
    val terminalColorsFromTheme = bool(TERMINAL_COLORS_FROM_THEME, true)

    /** A tick when keys and buttons are pressed. */
    val haptics = bool(HAPTICS, true)

    /** Labels of the key bar's extra keys the user took off it. Hidden rather than shown, so keys added later show. */
    val hiddenKeys = Setting(prefs.getStringSet(HIDDEN_KEYS, null)?.toSet() ?: emptySet()) {
        prefs.edit().putStringSet(HIDDEN_KEYS, it).apply()
    }

    /** The key bar's keys in the order the user put them; keys missing from it go after, in the bar's own order. */
    val keyOrder = list(KEY_ORDER)

    /** What Claude's strip shows, in order, or null for its defaults. Shown rather than hidden: most commands start off it. */
    val stripItems = Setting(prefs.getString(STRIP_ITEMS, null)?.split('\n')?.filter { it.isNotEmpty() }) {
        if (it == null) prefs.edit().remove(STRIP_ITEMS).apply() else prefs.edit().putString(STRIP_ITEMS, it.joinToString("\n")).apply()
    }

    /** The keys of [all] on the bar, in the user's order. */
    fun shownKeys(all: List<String>): List<String> = arrange(keyOrder.value, all).filterNot { it in hiddenKeys.value }

    /** Put exactly [shown] on the bar, in that order, out of [all]; the rest keep their place behind them for when they come back. */
    fun showKeys(shown: List<String>, all: List<String>) {
        keyOrder.value = shown + arrange(keyOrder.value, all).filterNot { it in shown }
        hiddenKeys.value = all.filterNot { it in shown }.toSet()
    }

    /** Alert kinds that show no banner and don't vibrate. */
    val mutedAlerts = Setting(
        prefs.getStringSet(MUTED_ALERTS, null).orEmpty().mapNotNull { s -> Alert.Kind.entries.firstOrNull { it.name == s } }.toSet(),
    ) { kinds -> prefs.edit().putStringSet(MUTED_ALERTS, kinds.map { it.name }.toSet()).apply() }

    /** Vibrate when an agent needs you or a program sends a notification, while the app is open. */
    val alertVibrate = bool(ALERT_VIBRATE, true)

    /**
     * Stay connected to the hosts after leaving the app, so a notification tells the user when an
     * agent needs them, finishes or fails. Terminals still pause after [stayConnectedSeconds].
     */
    val watchInBackground = bool(WATCH_IN_BACKGROUND, false)

    /** How long connections stay up after leaving the app, in seconds. */
    val stayConnectedSeconds = Setting(prefs.getInt(STAY_CONNECTED, DEFAULT_STAY_CONNECTED).coerceAtLeast(0)) {
        prefs.edit().putInt(STAY_CONNECTED, it).apply()
    }

    /**
     * Blocks whose offer to switch Claude to classic mode the user waved away, newest last. Kept here
     * rather than with the terminal, which is dropped from the cache and gone after a restart.
     */
    val classicOfferDismissed = Setting(prefs.getString(CLASSIC_OFFER_DISMISSED, null).orEmpty().split('\n').filter { it.isNotEmpty() }) {
        prefs.edit().putString(CLASSIC_OFFER_DISMISSED, it.joinToString("\n")).apply()
    }

    /** Keep the classic-mode offer away from [blockId], forgetting the oldest beyond [MAX_CLASSIC_DISMISSED]. */
    fun dismissClassicOffer(blockId: String) {
        classicOfferDismissed.value = (classicOfferDismissed.value - blockId + blockId).takeLast(MAX_CLASSIC_DISMISSED)
    }

    val defaultTextPx: Int get() = spToPx(TerminalViewport.DEFAULT_TEXT_SP)
    val minTextPx: Int get() = spToPx(TerminalViewport.MIN_TEXT_SP)
    val maxTextPx: Int get() = spToPx(TerminalViewport.MAX_TEXT_SP)

    /** The text size in whole sp, for showing: pinching can leave it between two. */
    fun textSizeSp(px: Int = textSizePx.value): Int =
        (TerminalViewport.MIN_TEXT_SP.toInt()..TerminalViewport.MAX_TEXT_SP.toInt()).minBy { abs(spToPx(it.toFloat()) - px) }

    /** One sp larger ([steps] > 0) or smaller, from the nearest whole size, within the terminal's range. */
    fun stepTextSize(steps: Int) {
        val sp = (textSizeSp() + steps).coerceIn(TerminalViewport.MIN_TEXT_SP.toInt(), TerminalViewport.MAX_TEXT_SP.toInt())
        textSizePx.value = spToPx(sp.toFloat())
    }

    /** Whether an alert of [kind] should show and buzz. */
    fun alertShown(kind: Alert.Kind) = kind !in mutedAlerts.value

    // As TerminalViewport converts, so the default here is the size it starts at.
    private fun spToPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics).toInt()

    private fun list(key: String) = Setting(prefs.getString(key, null).orEmpty().split('\n').filter { it.isNotEmpty() }) {
        prefs.edit().putString(key, it.joinToString("\n")).apply()
    }

    private fun string(key: String, default: String) =
        Setting(prefs.getString(key, null) ?: default) { prefs.edit().putString(key, it).apply() }

    private fun bool(key: String, default: Boolean) =
        Setting(prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }

    companion object {
        const val TEXT_SIZE = "phone_text_px"
        const val PHONE_MODE = "phone_mode"
        const val TERMINAL_HEADER_COLLAPSED = "terminal_header_collapsed"
        const val CLAUDE_STRIP_SHOWN = "claude_strip_shown"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val HAPTICS = "haptics"
        const val APPEARANCE = "appearance"
        const val THEME_DARK = "theme_dark"
        const val THEME_LIGHT = "theme_light"
        const val TERMINAL_COLORS_FROM_THEME = "terminal_colors_from_theme"
        const val HIDDEN_KEYS = "key_bar_hidden"
        const val KEY_ORDER = "key_bar_order"
        const val STRIP_ITEMS = "claude_strip_items"
        const val MUTED_ALERTS = "alerts_muted"
        const val ALERT_VIBRATE = "alert_vibrate"
        const val STAY_CONNECTED = "stay_connected_s"
        const val WATCH_IN_BACKGROUND = "watch_in_background"
        const val CLASSIC_OFFER_DISMISSED = "classic_offer_dismissed"

        /** Blocks come and go: only the most recent dismissals are worth keeping. */
        const val MAX_CLASSIC_DISMISSED = 50

        const val DEFAULT_STAY_CONNECTED = 90

        /** The choices Settings offers for [stayConnectedSeconds]. */
        val STAY_CONNECTED_CHOICES = listOf(0, DEFAULT_STAY_CONNECTED, 5 * 60, 15 * 60)

        /** [all] in [order], with what [order] leaves out after it in its own order; ids no longer in [all] drop out. */
        fun arrange(order: List<String>, all: List<String>): List<String> =
            order.filter { it in all }.distinct() + all.filterNot { it in order }

        fun stayConnectedLabel(seconds: Int): String = when {
            seconds == 0 -> "Disconnect right away"
            seconds < 60 || seconds % 60 != 0 -> "$seconds seconds"
            seconds == 60 -> "1 minute"
            else -> "${seconds / 60} minutes"
        }
    }
}
