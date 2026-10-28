package com.jeffarese.rexmobile

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.graphics.Color as AndroidColor
import com.jeffarese.rexmobile.model.Notifier
import com.jeffarese.rexmobile.ui.AlertBanner
import com.jeffarese.rexmobile.ui.CrashScreen
import com.jeffarese.rexmobile.ui.HomeScreen
import com.jeffarese.rexmobile.ui.HapticsFromSettings
import com.jeffarese.rexmobile.ui.HostsScreen
import com.jeffarese.rexmobile.ui.Rex
import com.jeffarese.rexmobile.ui.RexTheme
import com.jeffarese.rexmobile.ui.SettingsScreen
import com.jeffarese.rexmobile.ui.Target
import com.jeffarese.rexmobile.ui.TerminalScreen
import com.jeffarese.rexmobile.ui.onboarding.FirstRun
import com.jeffarese.rexmobile.ui.onboarding.OnboardingScreen

private sealed interface Screen {
    data object Welcome : Screen
    data object Home : Screen
    data object Settings : Screen
    data object Hosts : Screen
    data class Terminal(val target: Target) : Screen
}

/** A pane to open, from a notification's tap. */
private data class PaneRequest(val hostId: String, val sessionId: String, val blockId: String)

private fun Intent.paneRequest(): PaneRequest? {
    if (action != Notifier.ACTION_OPEN_PANE) return null
    return PaneRequest(
        getStringExtra(Notifier.EXTRA_HOST) ?: return null,
        getStringExtra(Notifier.EXTRA_SESSION) ?: return null,
        getStringExtra(Notifier.EXTRA_BLOCK) ?: return null,
    )
}

class MainActivity : ComponentActivity() {
    private var paneRequest by mutableStateOf<PaneRequest?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.paneRequest()?.let { paneRequest = it }
    }

    override fun onResume() {
        super.onResume()
        // Back from allowing Rex to install apps, or from Android's confirmation: the update goes on from there.
        if (CrashLog.read(this) == null) (application as RexMobileApp).model.updater.onResume()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (CrashLog.read(this) == null) (application as RexMobileApp).model.onConfigurationChanged(newConfig)
    }

    /** Status and navigation bar icons that show up on the theme: light ones on a dark theme. */
    private fun barsFor(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Not again when the activity is recreated, which would open it over wherever the user went since.
        if (savedInstanceState == null) paneRequest = intent?.paneRequest()
        // Started now unless a crash report is waiting, so the first frame is in the theme's colors.
        if (CrashLog.read(this) == null) (application as RexMobileApp).model
        barsFor(Rex.palette.dark)
        super.onCreate(savedInstanceState)
        setContent {
            val dark = Rex.palette.dark
            LaunchedEffect(dark) { barsFor(dark) }
            RexTheme {
                // After a crash, show what happened before starting the part of the app that crashed.
                var crash by remember { mutableStateOf(CrashLog.read(this)) }
                crash?.let { report ->
                    CrashScreen(report, onDismiss = {
                        CrashLog.clear(this)
                        crash = null
                    })
                    return@RexTheme
                }
                val model = remember { (application as RexMobileApp).model }
                val firstRun = remember { FirstRun(this) }
                var stack by remember {
                    mutableStateOf(listOf(if (firstRun.shouldShow(model.hostStore.hosts.value)) Screen.Welcome else Screen.Home))
                }
                val screen = stack.last()
                // Whether the last move went back, for the direction screens slide: Settings is under Hosts.
                var back by remember { mutableStateOf(false) }
                val nav = updateTransition(screen, label = "nav")
                fun push(s: Screen) {
                    // A double tap would push the same screen twice, so the first Back seemed to do nothing; a tap
                    // landing while the last move is still on its way (or sliding in) is as unmeant.
                    if (s == stack.last() || nav.isRunning || nav.currentState != stack.last()) return
                    back = false
                    stack = stack + s
                }
                fun pop() {
                    back = true
                    if (stack.size > 1) stack = stack.dropLast(1)
                }
                BackHandler(enabled = stack.size > 1) { pop() }
                val saved = rememberSaveableStateHolder()

                HapticsFromSettings(model.settings) { Box(Modifier.fillMaxSize().background(Rex.bg)) {
                    nav.AnimatedContent(
                        transitionSpec = {
                            val forward = !back && targetState !is Screen.Home
                            if (forward) {
                                (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(160))
                            } else {
                                fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180)))
                            }
                        },
                        contentKey = { s -> if (s is Screen.Terminal) "terminal" else s.toString() },
                    ) { s ->
                        when (s) {
                            // Home leaves composition while another screen is on top: keep its search,
                            // filter, folded hosts and scroll position for when the user comes back.
                            Screen.Home -> saved.SaveableStateProvider("home") {
                                HomeScreen(
                                    model, onOpen = { push(Screen.Terminal(it)) }, onHosts = { push(Screen.Hosts) },
                                    onSettings = { push(Screen.Settings) },
                                )
                            }
                            Screen.Settings -> SettingsScreen(
                                model, onBack = ::pop, onHosts = { push(Screen.Hosts) },
                                onShowWelcome = { push(Screen.Welcome) },
                            )
                            Screen.Hosts -> HostsScreen(model, onBack = ::pop)
                            // Finishing or skipping lands on Home, whether it opened at launch or from Settings.
                            Screen.Welcome -> OnboardingScreen(model, onDone = {
                                firstRun.markDone()
                                back = true
                                stack = listOf(Screen.Home)
                            })
                            // A new target is a new screen: its tab, pane and terminal view start fresh.
                            is Screen.Terminal -> key(s.target) { TerminalScreen(model, s.target, onBack = ::pop) }
                        }
                    }
                    AlertBanner(model, onOpen = { target ->
                        back = false
                        stack = listOf(Screen.Home, Screen.Terminal(target))
                    })
                    paneRequest?.let { request ->
                        LaunchedEffect(request) {
                            val target = model.paneTarget(request.hostId, request.sessionId, request.blockId)
                            if (target != null) {
                                back = false
                                stack = listOf(Screen.Home, Screen.Terminal(target))
                            }
                            paneRequest = null
                        }
                    }
                } }
            }
        }
    }
}
