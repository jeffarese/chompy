package com.jeffarese.rexmobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.BuildConfig
import com.jeffarese.rexmobile.CrashLog
import com.jeffarese.rexmobile.model.Alert
import com.jeffarese.rexmobile.model.Appearance
import com.jeffarese.rexmobile.model.RexTheme
import com.jeffarese.rexmobile.model.Themes
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.KeepScreenOn
import com.jeffarese.rexmobile.model.Notifier
import com.jeffarese.rexmobile.model.Settings
import com.jeffarese.rexmobile.model.UpdateState
import com.jeffarese.rexmobile.model.Updater
import android.text.format.DateUtils
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton

/** Settings' alert kinds, in the words the banner uses. */
private val ALERT_KINDS = listOf(
    Alert.Kind.BLOCKED to "Needs you",
    Alert.Kind.DONE to "Finished",
    Alert.Kind.ERROR to "Failed",
    Alert.Kind.BELL to "Bell",
    Alert.Kind.NOTIFICATION to "Notification",
)

/** The notification channels, whose sound and pop-up Android's settings set for each. */
private val ALERT_CHANNELS = listOf(
    Notifier.CHANNEL_NEEDS_YOU to "Needs you",
    Notifier.CHANNEL_RESULTS to "Finished and failed",
    Notifier.CHANNEL_PROGRAMS to "Program notifications",
)

private enum class Page { LICENSES, CRASH, RETURNS, KEYS, STRIP, THEMES }

/**
 * The app's settings, and the way to the hosts. [onShowWelcome], when there is a welcome to show,
 * adds a way back to it under About.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit, onHosts: () -> Unit, onShowWelcome: (() -> Unit)? = null) {
    val settings = model.settings
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    // Read when Settings opens: a crash report only appears on a later launch.
    val crash = remember { CrashLog.last(context) }
    val returns = remember { model.returnLog.read() }
    // Held above the pages, so coming back from one finds the list where it was, rather than at the top
    // with a tap aimed lower landing on whatever row is there now.
    val listState = rememberLazyListState()
    BackHandler(enabled = page != null) { page = null }

    when (page) {
        Page.LICENSES -> {
            LicensesPage(onBack = { page = null })
            return
        }
        Page.CRASH -> {
            ReportPage("Last crash report", crash.orEmpty(), "Chompy crash", "Copy report", onBack = { page = null })
            return
        }
        Page.RETURNS -> {
            ReportPage("Coming back to the app", returns.orEmpty(), "Chompy returns", "Copy log", onBack = { page = null })
            return
        }
        Page.KEYS -> {
            val hidden by settings.hiddenKeys.flow.collectAsState()
            val order by settings.keyOrder.flow.collectAsState()
            ArrangePage(
                "Key bar keys",
                "The first $PINNED_KEYS stay put at the start of the bar; the rest scroll.",
                shown = remember(hidden, order) { settings.shownKeys(KEY_BAR_IDS) },
                all = KEY_BAR_IDS, label = ::keyBarLabel, mono = true,
                onChange = { settings.showKeys(it, KEY_BAR_IDS) },
                onReset = { settings.showKeys(KEY_BAR_IDS, KEY_BAR_IDS) },
                onBack = { page = null },
            )
            return
        }
        Page.THEMES -> {
            ThemesPage(model, onBack = { page = null })
            return
        }
        Page.STRIP -> {
            val items by settings.stripItems.flow.collectAsState()
            ArrangePage(
                "Claude's strip",
                "Shown over the key bar in a Claude tab. Without the mode chip, the key bar has Shift+Tab.",
                shown = items?.filter { it in STRIP_IDS } ?: DEFAULT_STRIP,
                all = STRIP_IDS, label = ::stripLabel, mono = false,
                onChange = { settings.stripItems.value = it },
                onReset = { settings.stripItems.value = null },
                onBack = { page = null },
            )
            return
        }
        null -> Unit
    }

    val hosts by model.hostStore.hosts.collectAsState()
    val textPx by settings.textSizePx.flow.collectAsState()
    val phoneMode by settings.phoneMode.flow.collectAsState()
    val keepScreenOn by settings.keepScreenOn.flow.collectAsState()
    val haptics by settings.haptics.flow.collectAsState()
    val hiddenKeys by settings.hiddenKeys.flow.collectAsState()
    val keyOrder by settings.keyOrder.flow.collectAsState()
    val stripItems by settings.stripItems.flow.collectAsState()
    val muted by settings.mutedAlerts.flow.collectAsState()
    val vibrate by settings.alertVibrate.flow.collectAsState()
    val stayConnected by settings.stayConnectedSeconds.flow.collectAsState()
    val watching by settings.watchInBackground.flow.collectAsState()
    // Read again on each return, as from Android's settings, where the user may have just changed them.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val notificationsOn = remember(resumed, watching) { model.notifier.enabled }
    val batteryFree = remember(resumed, watching) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) != false
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }
    var updateSheet by remember { mutableStateOf(false) }
    if (updateSheet) UpdateSheet(model.updater, onDismiss = { updateSheet = false })

    Column(Modifier.fillMaxSize().background(Rex.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Rex.text) }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = Rex.text, modifier = Modifier.weight(1f))
        }
        LazyColumn(
            state = listState,
            contentPadding = readableListPadding(), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                Card {
                    SettingRow(
                        "Hosts",
                        hosts.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label } ?: "Add the Rex servers this phone connects to",
                        onClick = onHosts,
                    ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim) }
                }
            }

            item { SectionTitle("Look") }
            item {
                val appearance by settings.appearance.flow.collectAsState()
                val themeDark by settings.themeDark.flow.collectAsState()
                val themeLight by settings.themeLight.flow.collectAsState()
                val terminalColors by settings.terminalColorsFromTheme.flow.collectAsState()
                Card {
                    ChoiceRow("Appearance", appearance, Appearance.entries, label = { it.label }) { settings.appearance.value = it }
                    Divider()
                    SettingRow(
                        "Theme",
                        when (appearance) {
                            Appearance.DARK -> model.themes.name(themeDark)
                            Appearance.LIGHT -> model.themes.name(themeLight)
                            Appearance.SYSTEM -> "${model.themes.name(themeDark)} when dark, ${model.themes.name(themeLight)} when light"
                        } + ", from Rex's themes",
                        onClick = { page = Page.THEMES },
                    ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim) }
                    Divider()
                    SwitchRow(
                        "Terminals in the theme's colors",
                        "Off, they keep the colors the Mac sends. The Chompy look always does.",
                        terminalColors,
                    ) { settings.terminalColorsFromTheme.value = it }
                }
            }

            item { SectionTitle("Terminal") }
            item {
                Card {
                    SettingRow("Text size", "Pinching a terminal changes it too") {
                        Stepper(
                            value = "${settings.textSizeSp(textPx)}",
                            canDown = textPx > settings.minTextPx,
                            canUp = textPx < settings.maxTextPx,
                            onStep = settings::stepTextSize,
                        )
                    }
                    val previewSize = with(LocalDensity.current) { textPx.toSp() }
                    Text(
                        "~/code $ claude --continue", color = Rex.text, fontFamily = Rex.mono, fontSize = previewSize, maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp)).background(Rex.bg).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Divider()
                    SwitchRow(
                        "Fit to this phone", if (phoneMode) "Terminals resize to this screen" else "Terminals keep the desktop's size; pinch to zoom",
                        phoneMode,
                    ) { settings.phoneMode.value = it }
                    Divider()
                    ChoiceRow(
                        "Keep the screen on", keepScreenOn, KeepScreenOn.entries,
                        detail = when (keepScreenOn) {
                            KeepScreenOn.WORKING -> "Until ${WORKING_AWAKE_MS / 60_000} minutes after you last touch the terminal or type"
                            KeepScreenOn.ALWAYS -> "While a terminal is open"
                            KeepScreenOn.NEVER -> null
                        },
                        label = { it.label },
                    ) { settings.keepScreenOn.value = it }
                }
            }

            item { SectionTitle("Key bar") }
            item {
                Card {
                    val keys = remember(hiddenKeys, keyOrder) { settings.shownKeys(KEY_BAR_IDS) }
                    SettingRow("Keys", "${keys.size} of ${KEY_BAR_IDS.size}, in your order: ${keys.take(8).joinToString(" ")}…", onClick = { page = Page.KEYS }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim)
                    }
                    Divider()
                    val strip = stripItems?.filter { it in STRIP_IDS } ?: DEFAULT_STRIP
                    SettingRow("Claude's strip", strip.joinToString(", ") { if (it.startsWith("/")) it else stripLabel(it).lowercase() }, onClick = { page = Page.STRIP }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim)
                    }
                    Divider()
                    SwitchRow("Haptic feedback", "A tick when you press keys and buttons", haptics) { settings.haptics.value = it }
                }
            }

            item { SectionTitle("Alerts") }
            item {
                Card {
                    SettingRow(
                        "Banners and notifications",
                        "A banner at the top while the app is open, a notification when away. Tabs get their dot either way.",
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ALERT_KINDS.forEach { (kind, label) ->
                            val shown = kind !in muted
                            Chip(label, shown) { settings.mutedAlerts.value = if (shown) muted + kind else muted - kind }
                        }
                    }
                    Divider()
                    SwitchRow("Vibrate", "When an agent needs you or a program sends a notification", vibrate) { settings.alertVibrate.value = it }
                    Divider()
                    SwitchRow(
                        "Notify when away",
                        "Stay connected after you leave, so a notification says when an agent needs you, finishes or fails. " +
                            "Android shows that Chompy is running.",
                        watching,
                    ) { on ->
                        settings.watchInBackground.value = on
                        if (on && Build.VERSION.SDK_INT >= 33 && !model.notifier.enabled) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (watching && notificationsOn) {
                        Divider()
                        SettingRow("Sound and pop-up", "Set for each kind in Android's settings")
                        FlowRow(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ALERT_CHANNELS.forEach { (channel, label) ->
                                // Turned off there, a kind sends nothing even though it is on here.
                                val on = remember(resumed) { model.notifier.channelOn(channel) }
                                LinkChip(if (on) label else "$label: off") {
                                    runCatching {
                                        context.startActivity(
                                            Intent(SystemSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                                .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
                                                .putExtra(SystemSettings.EXTRA_CHANNEL_ID, channel),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (watching && !notificationsOn) {
                        Divider()
                        SettingRow("Notifications are off for Chompy", "Turn them on to hear from your agents", onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim) }
                    }
                    if (watching && !batteryFree) {
                        Divider()
                        SettingRow(
                            "Let Chompy run in the background",
                            "Otherwise the phone may close it to save battery, and the notifications stop",
                            onClick = {
                                runCatching {
                                    @SuppressLint("BatteryLife")
                                    val ask = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(ask)
                                }
                            },
                        ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim) }
                    }
                }
            }

            item { SectionTitle("Connection") }
            item {
                Card {
                    ChoiceRow(
                        "Stay connected after leaving", stayConnected,
                        (Settings.STAY_CONNECTED_CHOICES + stayConnected).distinct().sorted(),
                        detail = "Coming back before then needs no reconnecting",
                        label = Settings::stayConnectedLabel,
                    ) { settings.stayConnectedSeconds.value = it }
                    if (returns != null) {
                        Divider()
                        SettingRow("Coming back to the app", "How each return and network change went, newest first, to copy and send over", onClick = { page = Page.RETURNS }) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim)
                        }
                    }
                }
            }

            item { SectionTitle("About") }
            item {
                Card {
                    VersionRows(model.updater, onOpen = { updateSheet = true })
                    Divider()
                    SettingRow("Open-source licenses", null, onClick = { page = Page.LICENSES }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim)
                    }
                    if (crash != null) {
                        Divider()
                        SettingRow("Last crash report", "To copy and send over", onClick = { page = Page.CRASH }) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim)
                        }
                    }
                    if (onShowWelcome != null) {
                        Divider()
                        SettingRow("Show the welcome again", null, onClick = onShowWelcome) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The app's version, with whether it is up to date and a way to check; and a row for a newer version
 * that opens its notes through [onOpen].
 */
@Composable
private fun VersionRows(updater: Updater, onOpen: () -> Unit) {
    val state by updater.state.collectAsState()
    val version = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    if (!updater.enabled) {
        SettingRow("Chompy", version)
        return
    }
    val checked = updater.lastChecked
    val status = when (val s = state) {
        UpdateState.Checking -> "Checking for updates…"
        is UpdateState.Failed -> "Couldn't check: ${s.message}"
        is UpdateState.WithRelease -> "${s.release.version} is out"
        UpdateState.UpToDate, UpdateState.Idle -> if (checked > 0) {
            "Up to date, checked " + DateUtils.getRelativeTimeSpanString(checked, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                .toString().replaceFirstChar { it.lowercase() }
        } else null
    }
    SettingRow("Chompy", listOfNotNull(version, status).joinToString(" · ")) {
        if (state == UpdateState.Checking) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp, color = Rex.accent)
        } else if (state !is UpdateState.WithRelease) {
            TextButton(onClick = { updater.check() }) { Text("Check", color = Rex.accent) }
        }
    }
    (state as? UpdateState.WithRelease)?.let { s ->
        Divider()
        val detail = when (s) {
            is UpdateState.Available -> s.error ?: "See what's new and download it"
            is UpdateState.Downloading -> "Downloading… ${(s.progress * 100).toInt()}%"
            is UpdateState.Ready -> s.error ?: "Downloaded. See what's new and update"
            is UpdateState.Installing -> "Installing…"
        }
        SettingRow("Update to ${s.release.version}", detail, onClick = onOpen) {
            Box(Modifier.padding(end = 12.dp).size(8.dp).clip(CircleShape).background(Rex.accent))
        }
    }
}

/** Haptics in the app follow the setting: off, every Compose haptic does nothing. */
@Composable
fun HapticsFromSettings(settings: Settings, content: @Composable () -> Unit) {
    val on by settings.haptics.flow.collectAsState()
    if (on) content()
    else CompositionLocalProvider(LocalHapticFeedback provides NoHaptics, content = content)
}

internal object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

/** A section's name: a heading to a screen reader, which can then move from one section to the next. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = Rex.textDim, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Rex.surface)) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(color = Rex.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun SettingRow(
    title: String,
    detail: String?,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Rex.text, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = Rex.textDim, fontSize = 13.sp) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, and reads as one switch to a screen reader.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Rex.text, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = Rex.textDim, fontSize = 13.sp) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Rex.onAccent, checkedTrackColor = Rex.accent,
                uncheckedThumbColor = Rex.textDim, uncheckedTrackColor = Rex.surfaceHigh, uncheckedBorderColor = Rex.divider,
            ),
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    value: T,
    choices: List<T>,
    detail: String? = null,
    label: (T) -> String,
    onChoose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = true }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Rex.text, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = Rex.textDim, fontSize = 13.sp) }
        }
        Spacer(Modifier.width(12.dp))
        // The menu drops from the value it changes.
        Box {
            Text(label(value), color = Rex.accent, fontSize = 14.sp, maxLines = 1, modifier = Modifier.widthIn(max = 170.dp))
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(label(c)) },
                        trailingIcon = { if (c == value) Icon(Icons.Outlined.Check, "Chosen", tint = Rex.accent) },
                        onClick = {
                            open = false
                            onChoose(c)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Stepper(value: String, canDown: Boolean, canUp: Boolean, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }, enabled = canDown) {
            Icon(Icons.Outlined.Remove, "Smaller", tint = if (canDown) Rex.text else Rex.textFaint)
        }
        Text(value, color = Rex.text, fontFamily = Rex.mono, fontSize = 15.sp, modifier = Modifier.widthIn(min = 24.dp))
        IconButton(onClick = { onStep(1) }, enabled = canUp) {
            Icon(Icons.Outlined.Add, "Larger", tint = if (canUp) Rex.text else Rex.textFaint)
        }
    }
}

/** A toggle shaped like the key bar's keys: lit when on, outlined and faint when off. */
@Composable
private fun Chip(label: String, on: Boolean, mono: Boolean = false, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.height(36.dp).widthIn(min = 42.dp).clip(shape)
            .background(if (on) Rex.keyActive else Color.Transparent)
            .border(0.5.dp, if (on) Rex.accent else Rex.divider, shape)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (on) Rex.text else Rex.textFaint, fontSize = 13.sp,
            fontFamily = if (mono) Rex.mono else null,
        )
    }
}

/** A way into another screen, shaped like [Chip], with an arrow to say so. */
@Composable
private fun LinkChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.height(36.dp).clip(shape)
            .border(0.5.dp, Rex.divider, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 11.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Rex.text, fontSize = 13.sp)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Rex.textDim, modifier = Modifier.size(18.dp))
    }
}

/**
 * Choose which of [all] show, and in what order: those [shown] in a list to move up and down or
 * take off, then the rest to put back at the end.
 */
@Composable
private fun ArrangePage(
    title: String,
    detail: String,
    shown: List<String>,
    all: List<String>,
    label: (String) -> String,
    mono: Boolean,
    onChange: (List<String>) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val off = all.filterNot { it in shown }
    fun change(to: List<String>) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onChange(to)
    }
    fun move(i: Int, by: Int) = change(shown.toMutableList().apply { add(i + by, removeAt(i)) })
    SubPage(title, onBack) {
        LazyColumn(contentPadding = readableListPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(detail, color = Rex.textDim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }
            item { SectionTitle("Shown") }
            item {
                Card {
                    if (shown.isEmpty()) SettingRow("Nothing", "Add some from below")
                    shown.forEachIndexed { i, id ->
                        if (i > 0) Divider()
                        ArrangeRow(label(id), mono) {
                            IconButton(onClick = { move(i, -1) }, enabled = i > 0) {
                                Icon(Icons.Outlined.KeyboardArrowUp, "Move ${label(id)} up", tint = if (i > 0) Rex.text else Rex.textFaint)
                            }
                            IconButton(onClick = { move(i, 1) }, enabled = i < shown.lastIndex) {
                                Icon(Icons.Outlined.KeyboardArrowDown, "Move ${label(id)} down", tint = if (i < shown.lastIndex) Rex.text else Rex.textFaint)
                            }
                            IconButton(onClick = { change(shown - id) }) {
                                Icon(Icons.Outlined.Remove, "Hide ${label(id)}", tint = Rex.error)
                            }
                        }
                    }
                }
            }
            if (off.isNotEmpty()) {
                item { SectionTitle("Hidden") }
                item {
                    Card {
                        off.forEachIndexed { i, id ->
                            if (i > 0) Divider()
                            ArrangeRow(label(id), mono, dim = true) {
                                IconButton(onClick = { change(shown + id) }) {
                                    Icon(Icons.Outlined.Add, "Show ${label(id)}", tint = Rex.done)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card {
                    SettingRow("Reset to the defaults", null, onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onReset() })
                }
            }
        }
    }
}

@Composable
private fun ArrangeRow(label: String, mono: Boolean, dim: Boolean = false, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = if (dim) Rex.textDim else Rex.text, fontFamily = if (mono) Rex.mono else null,
            fontSize = 15.sp, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Plain pages under Settings, with their own back arrow. */
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Rex.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Rex.text) }
            Text(title, style = MaterialTheme.typography.titleLarge, color = Rex.text, modifier = Modifier.weight(1f))
        }
        content()
    }
}

/**
 * Rex's themes, dark then light, each with a swatch of its colors. Choosing one wears it at once,
 * and a light one chosen while the app is dark (or a dark one while light) makes it so.
 */
@Composable
private fun ThemesPage(model: AppModel, onBack: () -> Unit) {
    val settings = model.settings
    val themeDark by settings.themeDark.flow.collectAsState()
    val themeLight by settings.themeLight.flow.collectAsState()
    val appearance by settings.appearance.flow.collectAsState()
    val (dark, light) = remember { model.themes.all.partition { it.dark } }
    fun choose(theme: RexTheme?) {
        val isDark = theme?.dark ?: true
        if (isDark) settings.themeDark.value = theme?.id ?: Themes.DEFAULT else settings.themeLight.value = theme!!.id
        if (appearance != Appearance.SYSTEM) settings.appearance.value = if (isDark) Appearance.DARK else Appearance.LIGHT
    }
    SubPage("Theme", onBack) {
        LazyColumn(contentPadding = readableListPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Rex's own themes, as on the Mac. With Appearance as the phone is, the app wears your dark one or your light one.",
                    color = Rex.textDim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            item { SectionTitle("Dark") }
            item {
                Card {
                    ThemeRow(Themes.DEFAULT_NAME, "This app's own", Palette.Default.bg, Palette.Default.accent, DEFAULT_SWATCH, themeDark == Themes.DEFAULT) { choose(null) }
                    dark.forEach { t ->
                        Divider()
                        ThemeRow(t.name, null, Color(t.color("canvas")), Color(t.color("accent")), swatchOf(t), themeDark == t.id) { choose(t) }
                    }
                }
            }
            item { SectionTitle("Light") }
            item {
                Card {
                    light.forEachIndexed { i, t ->
                        if (i > 0) Divider()
                        ThemeRow(t.name, null, Color(t.color("canvas")), Color(t.color("accent")), swatchOf(t), themeLight == t.id) { choose(t) }
                    }
                }
            }
        }
    }
}

/** Red, green, yellow and blue from the emulator's own palette, for the Rex look's swatch. */
private val DEFAULT_SWATCH = listOf(Color(0xFFCD0000), Color(0xFF00CD00), Color(0xFFCDCD00), Color(0xFF6495ED))

/** A theme's red, green, yellow and blue, as its terminals show them. */
private fun swatchOf(t: RexTheme) = (1..4).map { Color(RexTheme.argb(t.terminal.palette[it])) }

@Composable
private fun ThemeRow(name: String, detail: String?, bg: Color, accent: Color, dots: List<Color>, chosen: Boolean, onChoose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onChoose)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(9.dp)).background(bg)
                .border(0.5.dp, Rex.divider, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            dots.forEach { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Rex.text, fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = Rex.textDim, fontSize = 13.sp) }
        }
        if (chosen) Icon(Icons.Outlined.Check, "Chosen", tint = Rex.accent)
    }
}

/** What the app is built from, with each one's license. */
private val NOTICES = listOf(
    "JetBrains Mono, as JetBrainsMono Nerd Font Mono" to "SIL Open Font License 1.1, below. Nerd Fonts' patching is MIT licensed.",
    "Noto Sans Symbols, Symbols 2 and Math" to "SIL Open Font License 1.1. Copyright The Noto Project Authors.",
    "libghostty-vt, from Ghostty" to "MIT License, below, with what is built into it.",
    "Termux terminal-emulator and terminal-view" to "Apache License 2.0. Its view, gestures, soft keyboard input and text selection, changed to draw a Rex server's terminal through libghostty-vt.",
    "OkHttp" to "Apache License 2.0.",
    "Kotlin, kotlinx.coroutines and kotlinx.serialization" to "Apache License 2.0.",
    "AndroidX, Jetpack Compose and Material icons" to "Apache License 2.0.",
    "Program logos in tabs" to "Simple Icons and Iconify's logos (CC0), Lobe Icons (MIT), Lucide (ISC), and some projects' own logos under their licenses. Each logo belongs to its owner.",
    "Jujutsu's logo" to "Copyright 2025 J. Jennings, SVG by Lucas Garron. CC BY 4.0.",
    "Chompy" to "Its style borrows from the Android robot, reproduced or modified from work created and shared by Google and used according to the Creative Commons 3.0 Attribution License.",
)

@Composable
private fun LicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val texts = remember {
        listOf("licenses/ENGINE-LICENSES.txt", "licenses/FONT-LICENSE-OFL.txt").mapNotNull { name ->
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()?.let(::reflow)
        }
    }
    SubPage("Open-source licenses", onBack) {
        LazyColumn(contentPadding = readableListPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card {
                    NOTICES.forEachIndexed { i, (name, license) ->
                        SettingRow(name, license)
                        if (i < NOTICES.lastIndex) Divider()
                    }
                }
            }
            item {
                Text(
                    "The Apache License 2.0 is at apache.org/licenses/LICENSE-2.0.",
                    color = Rex.textFaint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(texts) { text ->
                Text(
                    text, color = Rex.textDim, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Rex.surface).padding(12.dp),
                )
            }
        }
    }
}

/**
 * Join a text file's hard-wrapped lines so it wraps to the screen. Blank lines between paragraphs
 * stay, and so do the breaks around headings and rules, lines with no lowercase letters.
 */
internal fun reflow(text: String): String =
    text.replace("\r\n", "\n").trim().split(Regex("\n\\s*\n")).joinToString("\n\n") { para ->
        val lines = para.lines().map { it.trim() }
        fun heading(line: String) = line.none { it.isLowerCase() }
        buildString {
            lines.forEachIndexed { i, line ->
                if (i > 0) append(if (heading(line) || heading(lines[i - 1])) "\n" else " ")
                append(line)
            }
        }
    }

@Composable
private fun ReportPage(title: String, report: String, clipLabel: String, copyLabel: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    SubPage(title, onBack) {
        Column(Modifier.weight(1f).readableWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Long lines wrap inside the card: scrolled sideways, they ran past its edge and padding.
            Text(
                report, color = Rex.text, fontFamily = Rex.mono, fontSize = 11.sp,
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Rex.surface)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            )
            Button(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(clipLabel, report))
                    copied = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Rex.accent),
            ) { Text(if (copied) "Copied" else copyLabel) }
        }
    }
}
