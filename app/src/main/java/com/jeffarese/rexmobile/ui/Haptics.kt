package com.jeffarese.rexmobile.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Haptics Compose's own types don't cover, played with the View's constants: a key tap, which follows
 * the system's keyboard vibration setting, and a confirm and a reject. Android 10 and older have no
 * confirm or reject, and give a long press's buzz for both.
 *
 * Like Compose's, they follow the app's Haptics setting: when [HapticsFromSettings] has swapped
 * Compose's out for [NoHaptics], these stay quiet too.
 */
internal class ViewHaptics(private val view: View, private val on: Boolean) {
    /** A key of the key bar. */
    fun keyTap() = play(HapticFeedbackConstants.KEYBOARD_TAP)

    /** An answer sent, or a button of Claude's note pressed. */
    fun confirm() = play(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)

    /** A tap on something dimmed, which does nothing. */
    fun reject() = play(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    private fun play(constant: Int) {
        if (on) view.performHapticFeedback(constant)
    }
}

@Composable
internal fun rememberViewHaptics(): ViewHaptics {
    val view = LocalView.current
    val on = LocalHapticFeedback.current !== NoHaptics
    return remember(view, on) { ViewHaptics(view, on) }
}
