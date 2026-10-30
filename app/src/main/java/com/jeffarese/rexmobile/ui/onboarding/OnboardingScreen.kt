package com.jeffarese.rexmobile.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.R
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.HostStore
import com.jeffarese.rexmobile.ui.Rex
import com.jeffarese.rexmobile.ui.readableWidth
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jeffarese.rexmobile.model.NetworkWatch
import kotlinx.coroutines.launch

/** For a host's address: a URL keyboard with nothing corrected, moving on to the name. Hosts' dialog uses it too. */
val AddressKeyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false)

private const val TAILSCALE_PLAY = "https://play.google.com/store/apps/details?id=com.tailscale.ipn"

/**
 * The first-run walkthrough: what the app is, what to turn on on the Mac and this phone, adding the Mac
 * with a connection test, and then whether to notify when away. Every step can be skipped; [onDone]
 * goes to Home.
 */
@Composable
fun OnboardingScreen(model: AppModel, onDone: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    // Once the Mac is added, going back would only offer to add it again: Back leaves, as Skip does.
    val added = step == NOTIFY_STEP
    BackHandler(enabled = step > 0) { if (added) onDone() else step-- }
    // Already on, as when the welcome is shown again from Settings, there is nothing to ask.
    fun onAdded() {
        if (model.settings.watchInBackground.value) onDone() else step = NOTIFY_STEP
    }

    Column(Modifier.fillMaxSize().background(Rex.bg).statusBarsPadding().navigationBarsPadding().imePadding().readableWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0 && !added) IconButton(onClick = { step-- }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Rex.text) }
            else Spacer(Modifier.width(48.dp))
            StepDots(step, count = NOTIFY_STEP + 1, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Skip", color = Rex.textDim) }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(260)) { dir * it / 4 } + fadeIn(tween(200))) togetherWith fadeOut(tween(140))
            },
            label = "onboarding",
            modifier = Modifier.weight(1f),
        ) { s ->
            when (s) {
                0 -> WelcomeStep(onNext = { step = 1 })
                1 -> MacStep(onNext = { step = 2 })
                2 -> ConnectStep(model, onAdded = ::onAdded)
                else -> NotifyStep(model, onDone)
            }
        }
    }
}

/** The last step, once a host is added. */
private const val NOTIFY_STEP = 3

@Composable
private fun StepDots(step: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.padding(horizontal = 4.dp).size(width = if (i == step) 18.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape).background(if (i == step) Rex.accent else Rex.divider),
            )
        }
    }
}

/** A step's scrolling body above its main button, which stays put. */
@Composable
private fun StepLayout(action: String, onAction: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
        Button(
            onClick = onAction,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Rex.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).height(50.dp),
        ) { Text(action, fontSize = 16.sp) }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepLayout("Get started", onNext) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.chompy), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(4.dp))
        Text("Chompy", style = MaterialTheme.typography.headlineMedium, color = Rex.text)
        Text(
            "A client for Rex: watch and drive the terminals and agents running in Rex on your Mac.",
            color = Rex.textDim, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Rex.surface).padding(vertical = 6.dp)) {
            Feature(Icons.Outlined.Terminal, "Every session and tab", "Open any terminal, type, and answer prompts.")
            Feature(Icons.Outlined.NotificationsActive, "Know when you're needed", "A banner when an agent finishes or waits for you.")
            Feature(Icons.Outlined.Lock, "Private to your tailnet", "Your phone reaches the Mac over Tailscale, nowhere else.")
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Rex.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Rex.text, fontWeight = FontWeight.Medium)
            Text(body, color = Rex.textDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun MacStep(onNext: () -> Unit) {
    val context = LocalContext.current
    StepLayout("I've done this", onNext) {
        Text("Set up your Mac", style = MaterialTheme.typography.headlineMedium, color = Rex.text)
        Text("Your phone reaches Rex over Tailscale. Rex has it built in, so the Mac needs nothing else installed.", color = Rex.textDim)
        SetupCard(1, "Turn on Serve over Tailscale") {
            Text(
                "In Rex on your Mac, open Settings, then Server. The first time, finish signing in to Tailscale in your browser.",
                color = Rex.textDim, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Command("rex server tailscale on", "also works, until the server restarts")
        }
        SetupCard(2, "Connect this phone to Tailscale") {
            Text(
                "Install Tailscale here and sign in to the same tailnet. Use the Mac's Tailscale account, " +
                    "unless Limit access to your Tailscale account is off in Rex.",
                color = Rex.textDim, fontSize = 14.sp, lineHeight = 20.sp,
            )
            TextButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY))) } },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(32.dp),
            ) { Text("Get Tailscale", color = Rex.accent) }
        }
        SetupCard(3, "Find the Mac's address") {
            Text(
                "Rex's Server settings show it once HTTPS is ready, such as mac-rex.tail1234.ts.net. Or on the Mac run",
                color = Rex.textDim, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Command("rex server tailscale pair", "prints it, with a QR code")
        }
    }
}

@Composable
private fun SetupCard(n: Int, title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Rex.surface).padding(16.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Rex.accentSoft), contentAlignment = Alignment.Center) {
            Text("$n", color = Rex.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Rex.text, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun Command(command: String, note: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = Rex.mono, color = Rex.text)) { append(command) }
            append("  ")
            withStyle(SpanStyle(color = Rex.textFaint)) { append(note) }
        },
        fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Rex.surfaceHigh).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun ConnectStep(model: AppModel, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val step = remember { AddHostStep(model.hostStore, { tailscaleState(context) }) { url -> testConnection(model.http, url) } }
    var address by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var probe by remember { mutableStateOf<Probe>(Probe.Idle) }
    val connected = probe as? Probe.Connected

    fun connect() {
        if (probe == Probe.Testing) return
        focus.clearFocus()
        probe = Probe.Testing
        scope.launch { probe = step.connect(address, label) }
    }

    fun scan() = scanPairingCode(
        context,
        onAddress = { scanned ->
            address = scanned.removePrefix("https://")
            probe = Probe.Idle
            connect()
        },
        onProblem = { probe = Probe.Invalid(it) },
    )

    // Waiting for Tailscale: carry on by itself once it connects, which shows as a change of network,
    // or when the user comes back from its app.
    (probe as? Probe.NeedsTailscale)?.let { waiting ->
        fun retryIfOn() {
            if (probe != waiting || tailscaleState(context) != Tailscale.ON) return
            probe = Probe.Idle
            connect()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { retryIfOn() }
        DisposableEffect(waiting) {
            val watch = NetworkWatch(context) { retryIfOn() }
            watch.start()
            onDispose { watch.stop() }
        }
    }

    StepLayout(
        action = when {
            connected != null -> if (model.settings.watchInBackground.value) "Open Chompy" else "Next"
            probe == Probe.Testing -> "Connecting…"
            probe is Probe.Failed -> "Try again"
            else -> "Connect"
        },
        onAction = { if (connected != null) onAdded() else connect() },
        enabled = probe != Probe.Testing,
    ) {
        Text("Add your Mac", style = MaterialTheme.typography.headlineMedium, color = Rex.text)
        Text("Scan the Mac's pairing code, from rex server tailscale pair, or enter the address from Rex's Server settings. " +
            "The app checks it can connect before adding it.", color = Rex.textDim)
        val editable = connected == null && probe != Probe.Testing
        OutlinedButton(onClick = ::scan, enabled = editable, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.QrCodeScanner, null, tint = Rex.accent)
            Spacer(Modifier.width(8.dp))
            Text("Scan a pairing code", color = Rex.text)
        }
        OutlinedTextField(
            address, { address = it; probe = Probe.Idle },
            label = { Text("Address") }, placeholder = { Text("mac-rex.tail1234.ts.net") },
            singleLine = true, enabled = editable,
            keyboardOptions = AddressKeyboard,
            modifier = Modifier.fillMaxWidth(),
        )
        val suggested = (checkAddress(address) as? AddressCheck.Ok)?.let { HostStore.hostLabelFrom(it.url) }
        OutlinedTextField(
            label, { label = it },
            label = { Text("Name (optional)") }, placeholder = { Text(suggested ?: "Studio") },
            singleLine = true, enabled = editable,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        when (val p = probe) {
            Probe.Idle -> {}
            Probe.Testing -> ResultCard {
                CircularProgressIndicator(Modifier.padding(top = 3.dp).size(14.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
                Text("Connecting to ${suggested ?: address}…", color = Rex.textDim, fontSize = 14.sp)
            }
            is Probe.Invalid -> Text(p.message, color = Rex.error, fontSize = 13.sp)
            is Probe.NeedsTailscale -> ResultCard {
                CircularProgressIndicator(Modifier.padding(top = 3.dp).size(14.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val missing = p.state == Tailscale.MISSING
                    Text(if (missing) "This phone needs Tailscale" else "Tailscale is off", color = Rex.text, fontWeight = FontWeight.Medium)
                    Text(
                        "${hostOf(p.url)} is on a tailnet. " +
                            (if (missing) "Install Tailscale and sign in to the Mac's tailnet" else "Turn Tailscale on") +
                            ", and Rex connects by itself.",
                        color = Rex.textDim, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                val open = if (missing) Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY))
                                else context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
                                context.startActivity(open)
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text(if (missing) "Get Tailscale" else "Open Tailscale", color = Rex.accent) }
                }
            }
            is Probe.Connected -> ResultCard {
                Dot(Rex.online)
                Column {
                    Text("Connected to ${p.host.label}", color = Rex.text, fontWeight = FontWeight.Medium)
                    val sessions = when (p.sessions) {
                        0 -> "no sessions yet"
                        1 -> "1 session"
                        else -> "${p.sessions} sessions"
                    }
                    Text(listOfNotNull(p.version?.let { "Rex $it" }, sessions).joinToString(" · "), color = Rex.textDim, fontSize = 13.sp)
                }
            }
            is Probe.Failed -> {
                ResultCard {
                    Dot(Rex.offline)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Couldn't connect", color = Rex.text, fontWeight = FontWeight.Medium)
                        Text(p.hint, color = Rex.textDim, fontSize = 13.sp, lineHeight = 18.sp)
                        if (p.problem != Problem.OTHER) p.detail?.let { Text(it, color = Rex.textFaint, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2) }
                    }
                }
                // The Mac may just be asleep or offline right now.
                TextButton(onClick = { if (step.addAnyway(address, label) != null) onAdded() }) {
                    Text("Add it anyway", color = Rex.accent)
                }
            }
        }
    }
}

/**
 * What the welcome promised: a notification when an agent needs you. Turns on Notify when away as
 * Settings' switch does, asking for notifications on Android 13 and later, then says what still stands
 * in its way, as Settings does.
 */
@Composable
internal fun NotifyStep(model: AppModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = model.settings
    val watching by settings.watchInBackground.flow.collectAsState()
    // Read again on each return, as from Android's settings, where the user may have just changed them.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val notificationsOn = remember(resumed, watching) { model.notifier.enabled }
    val batteryFree = remember(resumed, watching) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) != false
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }

    StepLayout(
        action = if (watching) "Open Chompy" else "Notify me",
        onAction = {
            if (watching) onDone()
            else {
                settings.watchInBackground.value = true
                if (Build.VERSION.SDK_INT >= 33 && !model.notifier.enabled) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    ) {
        Text("Know when you're needed", style = MaterialTheme.typography.headlineMedium, color = Rex.text)
        Text(
            "Chompy can stay connected after you leave, so a notification says when an agent needs you, finishes or fails. " +
                "Android shows that Chompy is running while it does.",
            color = Rex.textDim,
        )
        if (!watching) {
            TextButton(onClick = onDone, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                Text("Not now", color = Rex.accent)
            }
            Text("Notify when away is in Settings, under Alerts, for later.", color = Rex.textFaint, fontSize = 13.sp)
            return@StepLayout
        }
        ResultCard {
            Dot(Rex.online)
            Column {
                Text("Notify when away is on", color = Rex.text, fontWeight = FontWeight.Medium)
                Text("Settings, under Alerts, turns it off.", color = Rex.textDim, fontSize = 13.sp)
            }
        }
        if (!notificationsOn) {
            FixCard("Notifications are off for Chompy", "Turn them on to hear from your agents", "Turn them on") {
                context.startActivity(
                    Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
        if (!batteryFree) {
            FixCard("Let Chompy run in the background", "Otherwise the phone may close it to save battery, and the notifications stop", "Allow") {
                @SuppressLint("BatteryLife")
                val ask = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                context.startActivity(ask)
            }
        }
    }
}

/** Something still to do on the phone, and the button that goes there. */
@Composable
private fun FixCard(title: String, body: String, action: String, onAction: () -> Unit) {
    ResultCard {
        Dot(Rex.blocked)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Rex.text, fontWeight = FontWeight.Medium)
            Text(body, color = Rex.textDim, fontSize = 13.sp, lineHeight = 18.sp)
            TextButton(onClick = { runCatching(onAction) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                Text(action, color = Rex.accent)
            }
        }
    }
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Rex.surface).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) { content() }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
}
