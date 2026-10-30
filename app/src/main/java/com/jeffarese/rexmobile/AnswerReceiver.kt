package com.jeffarese.rexmobile

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.Notifier
import com.jeffarese.rexmobile.model.PanePrompts
import kotlinx.coroutines.launch

/**
 * A tap on one of the answers a Needs you notification offers for Claude's prompt. The answer is
 * pressed only if the pane still shows that prompt (see [PanePrompts.answer]), and the notification
 * says how it went: answered, or that the pane is the place to answer it.
 */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tap = Notifier.answerTap(intent) ?: return
        // Before Android 12 a notification's button can't ask for the phone to be unlocked, so from
        // the lock screen it opens the pane rather than type into it.
        if (Build.VERSION.SDK_INT < 31 && context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            openPane(context, tap)
            return
        }
        // Watching keeps the process and its connections; one started for this connects as it starts.
        val model = (context.applicationContext as RexMobileApp).model
        val pending = goAsync()
        model.scope.launch {
            try {
                answer(context, model, tap)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Answer [tap] in its pane, saying in its notification how that went. */
        suspend fun answer(context: Context, model: AppModel, tap: Notifier.AnswerTap) {
            model.notifier.answering(tap)
            val host = model.connection(tap.prompt.hostId)
            val result = host?.let { model.prompts.answer(it, tap.prompt, tap.option) } ?: PanePrompts.Answer.UNREACHABLE
            model.notifier.answered(tap, result)
            // From Android 12 a notification's button can't open an app through here, so the
            // notification asks for a tap instead.
            if (Build.VERSION.SDK_INT < 31 && result in NOT_ANSWERED) openPane(context, tap)
        }

        private val NOT_ANSWERED = setOf(PanePrompts.Answer.UNCONFIRMED, PanePrompts.Answer.CHANGED, PanePrompts.Answer.UNREACHABLE)

        private fun openPane(context: Context, tap: Notifier.AnswerTap) {
            val p = tap.prompt
            runCatching {
                context.startActivity(Notifier.paneIntent(context, p.hostId, p.sessionId, p.blockId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
