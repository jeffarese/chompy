package com.jeffarese.rexmobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.BlockMeta
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.KeepScreenOn
import com.jeffarese.rexmobile.rex.ProgramStatus
import com.jeffarese.rexmobile.rex.StreamState
import com.jeffarese.rexmobile.rex.WindowView
import com.jeffarese.rexmobile.terminal.ClassicSwitch
import com.jeffarese.rexmobile.terminal.ClaudeSurvey
import com.jeffarese.rexmobile.terminal.FyiAction
import com.jeffarese.rexmobile.terminal.FyiNote
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.RemoteKey
import com.jeffarese.rexmobile.terminal.RemoteTerminal
import com.jeffarese.rexmobile.terminal.ScreenInfo
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import com.jeffarese.rexmobile.terminal.SurveyAnswer
import com.jeffarese.rexmobile.terminal.TerminalViewport
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val NO_INFO = MutableStateFlow(ScreenInfo())
private val NO_SWITCH = MutableStateFlow<ClassicSwitch.State>(ClassicSwitch.State.Idle)
private val NOT_DISMISSED = MutableStateFlow(false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(model: AppModel, target: Target, onBack: () -> Unit) {
    val conn = model.connection(target.hostId)
    if (conn == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val state by conn.state.collectAsState()
    val sid = target.sessionId
    val view = state.views[sid]
    var windowId by rememberSaveable { mutableStateOf(target.windowId) }
    val windows = view?.windows.orEmpty()
    val win = windows.firstOrNull { it.windowId == windowId } ?: windows.firstOrNull()
    var chosenBlock by rememberSaveable(win?.windowId) {
        mutableStateOf(if (win?.windowId == target.windowId) target.blockId else null)
    }
    val blockIds = win?.terminalBlocks?.map { it.blockId }.orEmpty()
    val blockId = chosenBlock?.takeIf { it in blockIds } ?: win?.let(::shownBlock)

    val sessionGone = view == null && state.status == ConnStatus.ONLINE && state.sessions.none { it.sessionId == sid }
    LaunchedEffect(sessionGone) { if (sessionGone) onBack() }

    val settings = model.settings
    val phoneMode by settings.phoneMode.flow.collectAsState()
    val headerCollapsed by settings.terminalHeaderCollapsed.flow.collectAsState()
    var directTyping by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var menuLinks by remember { mutableStateOf(emptyList<String>()) }
    var linksDialog by remember { mutableStateOf<List<String>?>(null) }
    var confirmClosePane by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A new tab or split on its way, as the pill says it: one at a time, so a second tap doesn't make two.
    var opening by remember { mutableStateOf<String?>(null) }
    /** Asks the host for a tab or a split, saying what is on its way, and why if it didn't come. */
    fun <T : Any> open(working: String, what: String, call: suspend () -> T?, then: (T) -> Unit = {}) {
        if (opening != null) return
        opening = working
        scope.launch {
            val made = try {
                attempt(context, conn, what, call)
            } finally {
                opening = null
            }
            made?.let(then)
        }
    }
    fun newTab() = open("Opening a tab…", "open a tab", { conn.newWindow(sid) }) { windowId = it }

    val viewport = remember {
        TerminalViewport(context, RexFonts.terminal(context)).apply {
            phoneTextSize = settings.textSizePx.value
            onPhoneTextSizeChanged = { settings.textSizePx.value = it }
        }
    }

    val terminal = remember(blockId) { blockId?.let { model.terminal(conn, sid, it) } }
    DisposableEffect(terminal) {
        if (terminal != null) {
            viewport.attach(terminal.session)
            terminal.view = viewport.terminalView
            model.visibleBlockId = terminal.blockId
            conn.markSeen(terminal.blockId)
        }
        onDispose {
            terminal?.let {
                if (it.view === viewport.terminalView) it.view = null
                it.setPhoneSized(false)
            }
            model.visibleBlockId = null
        }
    }
    LaunchedEffect(terminal, phoneMode) {
        viewport.mode = if (phoneMode) TerminalViewport.Mode.PHONE else TerminalViewport.Mode.MIRROR
        terminal?.setPhoneSized(phoneMode)
    }
    val remoteSize = terminal?.remoteSize?.collectAsState()?.value
    LaunchedEffect(remoteSize) { viewport.remoteSize = remoteSize }
    val streamState = terminal?.streamState?.collectAsState()?.value
    val syncing = terminal?.syncing?.collectAsState()?.value ?: true
    val catchingUp = terminal?.catchingUp?.collectAsState()?.value ?: false
    // On a server that compresses streams, the tabs either side start streaming once this one is
    // drawn, so swiping or tapping to them shows them at once. Uncompressed, they'd cost too much.
    val compressed by conn.dataCompressed.collectAsState()
    val neighbours = windows.indexOfFirst { it.windowId == win?.windowId }.let { i ->
        if (i < 0) emptyList() else listOfNotNull(windows.getOrNull(i - 1), windows.getOrNull(i + 1)).mapNotNull(::shownBlock)
    }
    LaunchedEffect(neighbours, compressed, syncing) {
        if (compressed == true && !syncing) neighbours.forEach { model.prewarm(conn, sid, it) }
    }
    LaunchedEffect(blockId, state.blocks[blockId]?.unseen) { blockId?.let { conn.markSeen(it) } }

    val modifiers = remember { StickyModifiers() }
    viewport.onModifiersConsumed = { modifiers.clear() }
    viewport.onTap = { if (directTyping) viewport.showKeyboard() }
    // Esc from a hardware keyboard goes to the server as a key, encoded for the program's keyboard mode.
    viewport.onRemoteKey = terminal?.let { t -> { key: RemoteKey -> t.sendKey(key) } }
    LaunchedEffect(modifiers) {
        snapshotFlow { Triple(modifiers.shift, modifiers.ctrl, modifiers.alt) }.collect { (s, c, a) ->
            viewport.stickyShift = s
            viewport.stickyCtrl = c
            viewport.stickyAlt = a
        }
    }
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Whether direct typing was on the last time it changed, so turning it off can be told from opening a tab.
    val typingBefore = remember { booleanArrayOf(false) }
    LaunchedEffect(directTyping, terminal) {
        val leaving = typingBefore[0] && !directTyping
        typingBefore[0] = directTyping
        when {
            directTyping -> {
                delay(50)
                viewport.showKeyboard()
            }
            // Back to the message box, which takes the keyboard over rather than closing it.
            leaving -> {
                viewport.terminalView.clearFocus()
                delay(50)
                runCatching { composerFocus.requestFocus() }
                keyboard?.show()
            }
            else -> viewport.hideKeyboard()
        }
    }
    // Only whether the view is back in history matters here, not by how much.
    var scrolledBack by remember { mutableStateOf(false) }
    viewport.onScrollbackChanged = { scrolledBack = it > 0 }
    LaunchedEffect(terminal) { scrolledBack = false }

    val controls = remember(terminal) { TerminalControls(ScreenSink(viewport, terminal), modifiers) }
    val commandsOverlay = remember { CommandsOverlay() }
    // Read the screen narrowly: this composable already redraws for every host state change.
    val info = (terminal?.screenInfo ?: NO_INFO).collectAsState()
    val claudeFooter by remember(info) { derivedStateOf { info.value.claudeMode != null || info.value.claudeEffort != null } }
    val interruptible by remember(info) { derivedStateOf { info.value.interruptible } }
    val fullscreen by remember(info) { derivedStateOf { info.value.fullscreen } }
    // After "Chat in main session", Claude's input box holds the note, waiting for a question.
    val noteInBox by remember(info) { derivedStateOf { info.value.claudeInput?.startsWith(NOTE_FOR_CLAUDE) == true } }
    val switchState by (terminal?.classicSwitch?.state ?: NO_SWITCH).collectAsState()
    // Waved away for this block, here or before the terminal was dropped from the cache or the app restarted.
    val dismissedBlocks by settings.classicOfferDismissed.flow.collectAsState()
    val switchDismissed = (terminal?.classicSwitch?.dismissed ?: NOT_DISMISSED).collectAsState().value || blockId in dismissedBlocks

    // Once Claude's footer has shown, keep its strip: hiding it whenever the footer scrolls away
    // would resize a phone-sized terminal, and the redraw could bring the footer straight back.
    var footerSeen by remember(blockId) { mutableStateOf(false) }
    LaunchedEffect(blockId, claudeFooter) { if (claudeFooter) footerSeen = true }

    val meta = blockId?.let { state.blocks[it] }
    val status = meta?.status
    val isClaude = programOf(meta).startsWith("claude") || claudeFooter || footerSeen
    // Only while connected is the status and screen read current: a dropped connection leaves them as they were.
    val live = state.status == ConnStatus.ONLINE && streamState == StreamState.LIVE
    val working = agentWorking(live, status?.state, interruptible)
    val keepScreenOn by settings.keepScreenOn.flow.collectAsState()
    // A touch or a key restarts the time an agent's work keeps the screen on. Read only by the effect,
    // so a touch doesn't redraw the screen.
    val lastTouch = remember { MutableStateFlow(SystemClock.uptimeMillis()) }
    val touched = { lastTouch.value = SystemClock.uptimeMillis() }
    viewport.onTyped = touched
    LaunchedEffect(working, keepScreenOn) {
        lastTouch.collectLatest { t ->
            val left = keepScreenOnFor(keepScreenOn, working, SystemClock.uptimeMillis() - t)
            viewport.keepScreenOn = left > 0
            if (left in 1 until Long.MAX_VALUE) {
                delay(left)
                viewport.keepScreenOn = false
            }
        }
    }
    // Compose's haptics follow the setting app-wide; the terminal view's own (a long press to select) follow it here.
    val haptics by settings.haptics.flow.collectAsState()
    LaunchedEffect(haptics) { viewport.terminalView.isHapticFeedbackEnabled = haptics }
    DisposableEffect(viewport) { onDispose { viewport.keepScreenOn = false } }
    // While typing, the tabs give their room to the terminal.
    val imeVisible = WindowInsets.isImeVisible
    // In landscape the header, tabs and panes would leave the terminal a few rows: the header folds
    // there whatever the setting says, and a tap unfolds it for a while without changing the setting.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var landscapeHeader by remember { mutableStateOf(false) }
    LaunchedEffect(landscape) { landscapeHeader = false }
    val collapsed = if (landscape) !landscapeHeader else headerCollapsed

    // Only while the stream is live and its screen drawn is what's on it current. Before then, what
    // would be sent could land on a menu that turned up meanwhile, or be lost, so input waits. A
    // screen shown ahead of a long snapshot was current when read, so it takes input while the
    // stream catches up, rather than holding it for the snapshot's many seconds.
    val waiting = when {
        blockId == null && view != null -> "This tab has no terminal"
        streamState == StreamState.ENDED -> "This pane has closed"
        streamState == StreamState.FAILED -> "Can't stream this pane"
        !live || syncing -> "Waiting for the connection…"
        else -> null
    }
    val agentPane = programOf(meta).startsWith("claude") || meta?.statuses?.isNotEmpty() == true

    Column(
        // Any touch on the screen counts, the terminal's included: seen on its way down, and left for what it lands on.
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) touched()
            }
        }
            .fillMaxSize().background(Rex.bg).statusBarsPadding()
            // A cutout or a navigation bar at the side, as in landscape, keeps off the terminal's columns and the back button.
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)),
    ) {
        // Top bar, or the one line it folds to
        if (collapsed) {
            val elsewhere = windows.filter { it.windowId != win?.windowId }.flatMap { it.blocks }.mapNotNull { state.blocks[it.blockId] }
            CollapsedHeader(
                title = win?.let { windowTitle(it, meta) } ?: "Terminal",
                status = state.status,
                agent = agentState(status),
                // Other tabs can't be seen from here, so a dot says when one of them wants a look.
                elsewhere = when {
                    elsewhere.any { it.status?.state == "blocked" } -> Rex.blocked
                    elsewhere.any { it.unseen } -> Rex.unseen
                    else -> null
                },
                onExpand = { if (landscape) landscapeHeader = true else settings.terminalHeaderCollapsed.value = false },
            )
        } else Row(Modifier.fillMaxWidth().height(52.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Rex.text) }
            Column(Modifier.weight(1f)) {
                Text(
                    win?.let { windowTitle(it, meta) } ?: "Terminal", color = Rex.text, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionDot(state.status)
                    Spacer(Modifier.width(6.dp))
                    agentState(status)?.let { (label, color) ->
                        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(" · ", color = Rex.textDim, fontSize = 12.sp)
                    }
                    Text(
                        listOfNotNull(conn.config.label, view?.label, prettyPath(meta?.pwd)).joinToString(" · "),
                        color = Rex.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { if (landscape) landscapeHeader = false else settings.terminalHeaderCollapsed.value = true }) {
                Icon(Icons.Outlined.ExpandLess, "Hide the header", tint = Rex.text)
            }
            Box {
                IconButton(onClick = {
                    menuLinks = terminal?.let(::screenLinks).orEmpty()
                    menu = true
                }) { Icon(Icons.Outlined.MoreVert, "More", tint = Rex.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("New tab") }, enabled = opening == null, onClick = {
                        menu = false
                        newTab()
                    })
                    if (blockId != null) {
                        DropdownMenuItem(text = { Text("Split right") }, enabled = opening == null, onClick = {
                            menu = false
                            open("Splitting the pane…", "split the pane", { conn.splitBlock(sid, blockId, "horizontal") })
                        })
                        DropdownMenuItem(text = { Text("Split down") }, enabled = opening == null, onClick = {
                            menu = false
                            open("Splitting the pane…", "split the pane", { conn.splitBlock(sid, blockId, "vertical") })
                        })
                    }
                    DropdownMenuItem(
                        text = { Text("Fit to this phone") },
                        trailingIcon = { if (phoneMode) Icon(Icons.Outlined.Check, "On", tint = Rex.accent) },
                        onClick = { menu = false; settings.phoneMode.value = !phoneMode },
                    )
                    if (!phoneMode) DropdownMenuItem(text = { Text("Reset zoom") }, onClick = { menu = false; viewport.resetZoom() })
                    if (menuLinks.isNotEmpty()) DropdownMenuItem(text = { Text("Open link…") }, onClick = {
                        menu = false
                        linksDialog = menuLinks
                    })
                    fun copy(text: String) {
                        menu = false
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
                        // Android 13 and later confirm a copy themselves.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    DropdownMenuItem(text = { Text("Copy screen") }, onClick = {
                        copy(terminal?.let { visibleText(it, viewport.terminalView.topRow) }.orEmpty())
                    })
                    DropdownMenuItem(text = { Text("Copy all text") }, onClick = {
                        copy(terminal?.session?.emulator?.screen?.transcriptTextWithFullLinesJoined.orEmpty())
                    })
                    DropdownMenuItem(text = { Text("Reconnect") }, onClick = {
                        menu = false
                        conn.ensureAlive(force = true)
                        terminal?.reconnectNow()
                    })
                    HorizontalDivider(color = Rex.divider)
                    DropdownMenuItem(text = { Text("Close pane", color = Rex.error) }, onClick = { menu = false; confirmClosePane = true })
                }
            }
        }

        // Tabs
        if (windows.isNotEmpty() && !imeVisible && !collapsed) {
            WindowTabs(windows, win?.windowId, state.blocks, onSelect = { windowId = it }, adding = opening != null, onAdd = ::newTab)
        }
        if (win != null && win.blocks.size > 1 && !imeVisible && !collapsed) {
            PaneStrip(win, blockId, state.blocks) { chosenBlock = it }
        }

        // Terminal
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Its background shows beside a fitted grid, so it is the theme's.
            val bg = Rex.bg.toArgb()
            AndroidView(factory = { viewport }, update = { it.setBackgroundColor(bg) }, modifier = Modifier.fillMaxSize())
            // The last screen stays while it reconnects, faded so it doesn't pass for current. Not at
            // once: a stream resumed on return reconnects in a few dozen milliseconds, which would flicker.
            val faded = terminal != null && screenFaded(state.status, streamState)
            LaunchedEffect(faded) {
                if (faded) delay(FADE_AFTER_MS)
                viewport.animate().alpha(if (faded) FADED_ALPHA else 1f).setDuration(FADE_MS)
            }
            val noTerminal = blockId == null && view != null
            val overlay = when {
                noTerminal -> "This tab has no terminal"
                streamState == StreamState.ENDED -> "This pane has closed"
                streamState == StreamState.FAILED -> "Can't stream this pane"
                // Retries go through connecting and offline in turn: the same words for both keep the pill still.
                state.status != ConnStatus.ONLINE -> "Reconnecting to ${conn.config.label}…"
                streamState == StreamState.RECONNECTING -> "Reconnecting…"
                syncing -> "Loading…"
                // Shown ahead of a long snapshot, and usable: new output follows once it is in.
                catchingUp -> "Catching up…"
                else -> opening
            }
            StatusOverlay(
                overlay,
                // Only something on its way gets the dots: nothing will come of a closed pane, or a tab with no terminal.
                spinning = !noTerminal && streamState != StreamState.ENDED && streamState != StreamState.FAILED,
                action = if (streamState == StreamState.FAILED && terminal != null) "Retry" to {
                    conn.ensureAlive(force = true)
                    terminal.reconnectNow()
                } else null,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            // Claude in fullscreen keeps no history here to scroll: offer to restart it in its classic renderer.
            val offerSwitch = switchState != ClassicSwitch.State.Idle || (live && !syncing && isClaude && fullscreen && !switchDismissed)
            if (terminal != null && overlay == null && offerSwitch) {
                ClassicSwitchBanner(
                    switchState,
                    onSwitch = terminal.classicSwitch::start,
                    onDismiss = {
                        terminal.classicSwitch.dismiss()
                        settings.dismissClassicOffer(terminal.blockId)
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            BottomOverlay(
                info = info,
                // A prompt read before the stream dropped, or before it has redrawn, may be gone.
                live = live && !syncing,
                blocked = status?.takeIf { it.state == "blocked" }?.let { it.msg ?: "Waiting for you" },
                scrolledBack = { scrolledBack },
                onJump = viewport::scrollToBottom,
                onChoose = controls::choose,
                onTab = controls::goToTab,
                onOwn = controls::answerOwn,
                modifier = Modifier.align(Alignment.BottomCenter),
                onFyi = { note, action ->
                    terminal?.pressFyi(note, action)
                    viewport.scrollToBottom()
                },
                onReadFyi = { terminal?.readFyi() },
                onSurvey = { survey, answer ->
                    terminal?.answerSurvey(survey, answer)
                    viewport.scrollToBottom()
                },
            )
            commandsOverlay.content?.let { panel ->
                Box(Modifier.align(Alignment.BottomCenter).keepTapsOffTerminal()) { panel() }
            }
        }

        // Input
        InputPanel(
            blockId = blockId,
            model = model,
            controls = controls,
            info = info,
            isClaude = isClaude,
            working = working,
            directTyping = directTyping,
            hint = when {
                noteInBox -> "Ask Claude about the note…"
                agentPane -> "Message ${displayProgram(meta).ifBlank { "the agent" }}…"
                else -> "Run a command…"
            },
            onPaste = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(controls::paste)
            },
            onToggleTyping = { directTyping = !directTyping },
            overlay = commandsOverlay,
            waiting = waiting,
            shell = !agentPane && !isClaude,
            composerFocus = composerFocus,
            onTyped = touched,
        )
    }

    linksDialog?.let { links -> LinksDialog(links, context, onDismiss = { linksDialog = null }) }

    if (confirmClosePane && blockId != null) {
        AlertDialog(
            onDismissRequest = { confirmClosePane = false },
            title = { Text("Close this pane?") },
            text = { Text("The program running in it will be ended.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClosePane = false
                    scope.launch {
                        // A pane that didn't close keeps its terminal, rather than a frozen one cut off from it.
                        runCatching { conn.closeBlock(sid, blockId) }
                            .onSuccess { model.dropTerminal(blockId) }
                            .onFailure { e ->
                                // Gone from the screen meanwhile; a call that ran out of time is a failure to tell about.
                                if (e is CancellationException && e !is TimeoutCancellationException) return@launch
                                val reason = failureReason(e, conn.state.value.status) ?: "the server didn't answer"
                                Toast.makeText(context, "Couldn't close the pane: $reason", Toast.LENGTH_LONG).show()
                            }
                    }
                }) { Text("Close", color = Rex.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClosePane = false }) { Text("Cancel") } },
        )
    }
}

/** Input from the screen's controls goes to the terminal, which then shows its live screen again. */
private class ScreenSink(private val viewport: TerminalViewport, private val terminal: RemoteTerminal?) : TerminalSink {
    override fun key(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean) = sent {
        val remote = terminal?.let { remoteKey(code, ctrl, alt, shift) }
        if (remote != null) terminal.sendKey(remote) else viewport.sendKey(code, ctrl, alt, shift)
    }
    override fun bytes(bytes: ByteArray) = sent { terminal?.typeBytes(bytes) }
    override fun typeHeld(text: String, ctrl: Boolean, alt: Boolean, shift: Boolean) = sent {
        terminal?.typeBytes(viewport.encodeTyped(text, ctrl, alt, shift) ?: typedBytes(text, ctrl = ctrl, alt = alt))
    }
    override fun paste(text: String) = sent { terminal?.paste(text) }
    override fun choose(prompt: ScreenPrompt, option: PromptOption) = sent { terminal?.choose(prompt, option) }
    override fun goToTab(prompt: ScreenPrompt, index: Int) = sent { terminal?.goToTab(prompt, index) }
    override fun answerOwn(prompt: ScreenPrompt, text: String) = sent { terminal?.answerOwn(prompt, text) }

    private inline fun sent(send: () -> Unit) {
        send()
        viewport.scrollToBottom()
    }
}

/** True when the menu's highlighted option takes a typed answer, as Claude's "Type something." does: the message box fills it. */
internal fun ScreenPrompt.awaitsText(): Boolean =
    options.firstOrNull { it.selected }?.let { it.role == PromptOption.Role.OWN || it.label.startsWith("Type something", ignoreCase = true) } == true

/**
 * Esc and Shift+Tab go to the server as key events, which it encodes for the program's keyboard
 * mode (Claude Code turns on the Kitty protocol). As typed bytes, an Esc could also run into the
 * next bytes typed and be read as Alt plus that key. Other keys stay as bytes, which batch better.
 */
internal fun remoteKey(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): RemoteKey? {
    val base = when {
        code == KeyEvent.KEYCODE_ESCAPE -> RemoteKey.ESCAPE
        code == KeyEvent.KEYCODE_TAB && shift -> RemoteKey.TAB
        else -> return null
    }
    val mods = listOfNotNull("shift".takeIf { shift }, "ctrl".takeIf { ctrl }, "alt".takeIf { alt }).joinToString("+")
    return base.copy(mods = mods, text = if (mods.isEmpty()) base.text else "")
}

/** Links in the terminal's text and history, newest first. */
private fun screenLinks(terminal: RemoteTerminal): List<String> {
    val emu = terminal.session.emulator ?: return emptyList()
    val screen = emu.screen
    val text = screen.getSelectedText(0, -screen.activeTranscriptRows, emu.mColumns, emu.mRows, true, false)
    return findLinks(text.lines(), emu.mColumns)
}

/**
 * Whether the pane's program is busy, which keeps the screen on and turns Send into Stop: it says
 * it is working, or the screen offers to interrupt it. Only while [live] (host online, stream live):
 * after a drop the last status and screen read stay, and would keep the screen on until the user left.
 */
internal fun agentWorking(live: Boolean, state: String?, interruptible: Boolean): Boolean =
    live && (state == "working" || interruptible)

/** How long an agent's work keeps the screen on after the last touch or key: a phone left on a long run still goes dark. */
internal const val WORKING_AWAKE_MS = 10 * 60_000L

/**
 * How much longer the terminal keeps the screen on, [sinceTouch] ms after the user last touched it or
 * typed: [Long.MAX_VALUE] for as long as it shows, 0 for not at all. While an agent is [working] it is
 * until [WORKING_AWAKE_MS] after that touch; then the system's own timeout applies.
 */
internal fun keepScreenOnFor(setting: KeepScreenOn, working: Boolean, sinceTouch: Long): Long = when (setting) {
    KeepScreenOn.ALWAYS -> Long.MAX_VALUE
    KeepScreenOn.NEVER -> 0
    KeepScreenOn.WORKING -> if (working) (WORKING_AWAKE_MS - sinceTouch).coerceAtLeast(0) else 0
}

/**
 * What the Claude strip shows for its mode or effort: the value [read] from the screen, else the
 * [last] known one. While a prompt [covered] Claude's footer the last known one comes first, as the
 * only effort left on screen then may be the welcome banner's, from before any change.
 */
internal fun stripValue(read: String?, last: String?, covered: Boolean): String? =
    if (covered) last ?: read else read ?: last

private const val FADED_ALPHA = 0.45f
private const val FADE_MS = 250L
private const val FADE_AFTER_MS = 700L

/** Whether the screen on show is out of date because its host or stream is reconnecting. */
internal fun screenFaded(host: ConnStatus, stream: StreamState?): Boolean =
    host != ConnStatus.ONLINE || stream == StreamState.RECONNECTING

/** The terminal a tab opens to: its zoomed one, else its focused one, else its first. */
internal fun shownBlock(w: WindowView): String? {
    val ids = w.terminalBlocks.map { it.blockId }
    return w.zoomedBlockId?.takeIf { it in ids } ?: w.focusedBlockId?.takeIf { it in ids } ?: ids.firstOrNull()
}

/** The agent's state for the top bar, if it has one worth showing. */
private fun agentState(status: ProgramStatus?): Pair<String, Color>? = when (status?.state) {
    "working" -> "Working" to Rex.working
    "blocked" -> "Needs you" to Rex.blocked
    "error" -> "Failed" to Rex.error
    "done" -> "Done" to Rex.done
    else -> null
}

/**
 * Where the input panel puts its panel of every command: over the bottom of the terminal, which
 * keeps its size, rather than in the panel, which would push the terminal up and resize it.
 */
internal class CommandsOverlay {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

/**
 * The controls under the terminal: Claude's strip or command suggestions, the key bar, and the
 * message box. With an [overlay] to show it in, the strip can open every command at once, and
 * suggestions for a command being typed show there while the strip is hidden.
 *
 * @param waiting why input waits, such as the screen not being known to be current: what would be
 *   sent could land on a menu that turned up meanwhile, or be lost. Send and the keys are then
 *   dimmed and inert, and the message box keeps its text. Null when input goes through.
 * @param shell the pane runs a shell, so the keyboard leaves case and spelling alone.
 * @param composerFocus focuses the message box, as when leaving direct typing.
 * @param onTyped is told of each change to the message box, as the user being there.
 */
@Composable
internal fun InputPanel(
    blockId: String?,
    model: AppModel,
    controls: TerminalControls,
    info: State<ScreenInfo>,
    isClaude: Boolean,
    working: Boolean,
    directTyping: Boolean,
    hint: String,
    onPaste: () -> Unit,
    onToggleTyping: () -> Unit,
    overlay: CommandsOverlay? = null,
    waiting: String? = null,
    shell: Boolean = false,
    composerFocus: FocusRequester? = null,
    onTyped: () -> Unit = {},
) {
    var draft by remember(blockId) {
        val saved = blockId?.let { model.drafts[it] }.orEmpty()
        mutableStateOf(TextFieldValue(saved, TextRange(saved.length)))
    }
    fun updateDraft(v: TextFieldValue) {
        if (v.text != draft.text) onTyped()
        draft = v
        if (blockId != null) {
            if (v.text.isEmpty()) model.drafts.remove(blockId) else model.drafts[blockId] = v.text
        }
    }
    // While a menu is on screen its Enter (and Shift+Tab) answers it, so what would send them waits,
    // except when the highlighted option is one the user answers in words, such as "Type something.".
    val menuOpen by remember(info) {
        derivedStateOf { info.value.prompt?.let { it.kind == ScreenPrompt.Kind.MENU && !it.awaitsText() } == true }
    }
    // Claude's mode and effort as last known for this block, kept while a dialog hides its footer.
    var lastMode by remember(blockId) { mutableStateOf(blockId?.let { model.modes[it] }) }
    var lastEffort by remember(blockId) { mutableStateOf(blockId?.let { model.efforts[it] }) }
    LaunchedEffect(blockId, info) {
        snapshotFlow { info.value }.collect { i ->
            if (i.prompt != null) return@collect
            i.claudeMode?.let { m ->
                lastMode = m
                if (blockId != null) model.modes[blockId] = m
            }
            i.claudeEffort?.let { e ->
                lastEffort = e
                if (blockId != null) model.efforts[blockId] = e
            }
        }
    }
    val ready = waiting == null
    /** Paste [text] and press Enter, unless a menu would take that Enter as its answer. True if it was sent. */
    fun send(text: String): Boolean {
        if (!ready || (text.isNotEmpty() && menuOpen)) return false
        controls.submit(text)
        effortSetBy(text)?.let { level ->
            lastEffort = level
            if (blockId != null) model.efforts[blockId] = level
        }
        return true
    }
    // A command that ends the conversation, or Claude itself, waiting for the user to confirm it, and
    // whether it was picked from what the box suggested, which empties the box once it is sent. One
    // picked from every command leaves a message being written where it is.
    var confirming by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val stripShown by model.settings.claudeStripShown.flow.collectAsState()
    val stripItems by model.settings.stripItems.flow.collectAsState()
    val strip = remember(stripItems) { stripItems?.filter { it in STRIP_IDS } ?: DEFAULT_STRIP }
    var allCommands by remember(blockId) { mutableStateOf(false) }
    val suggestions = if (isClaude && !directTyping) slashSuggestions(draft.text) else null
    // A command being typed brings its suggestions up even while the strip is hidden.
    val commandsShown = allCommands && isClaude && !directTyping && (stripShown || suggestions != null)
    BackHandler(commandsShown) { allCommands = false }
    val sendEnabled = !menuOpen && ready
    fun command(text: String) {
        if (text in CONFIRMED_COMMANDS) confirming = text to false else send(text)
    }
    fun pick(s: Suggestion) {
        val typed = suggestions != null
        if (!s.send) updateDraft(TextFieldValue(s.text, TextRange(s.text.length)))
        else if (s.text in CONFIRMED_COMMANDS) confirming = s.text to typed
        else if (send(s.text) && typed) updateDraft(TextFieldValue())
    }
    val toggleCommands = if (overlay == null) null else ({ allCommands = !allCommands })
    // With the strip hidden, suggestions show over the terminal: a row turning up in this panel would
    // shrink a terminal sized to the phone, and make the program redraw for every "/" typed.
    val suggestionsOver = suggestions != null && !stripShown && overlay != null

    if (overlay != null) {
        val panel: (@Composable () -> Unit)? = if (!commandsShown && !suggestionsOver) null else {
            {
                Column {
                    if (commandsShown) CommandPanel(suggestions ?: ALL_COMMANDS, sendEnabled = sendEnabled, onPick = { s ->
                        allCommands = false
                        pick(s)
                    })
                    if (suggestionsOver && suggestions != null) {
                        Column(Modifier.fillMaxWidth().background(Rex.surface)) {
                            HorizontalDivider(thickness = 0.5.dp, color = Rex.divider)
                            SlashSuggestions(suggestions, sendEnabled = sendEnabled, expanded = commandsShown, onExpand = toggleCommands, onPick = ::pick)
                        }
                    }
                }
            }
        }
        SideEffect { overlay.content = panel }
        DisposableEffect(overlay) { onDispose { overlay.content = null } }
    }

    Column(Modifier.fillMaxWidth().background(Rex.surface).imePadding().navigationBarsPadding()) {
        HorizontalDivider(thickness = 0.5.dp, color = Rex.divider)
        if (suggestions != null && !suggestionsOver) {
            SlashSuggestions(suggestions, sendEnabled = sendEnabled, expanded = commandsShown, onExpand = toggleCommands, onPick = ::pick)
        } else if (isClaude && stripShown) {
            val covered = info.value.prompt != null
            ClaudeBar(
                mode = stripValue(info.value.claudeMode, lastMode, covered),
                effort = stripValue(info.value.claudeEffort, lastEffort, covered),
                onCycleMode = { if (sendEnabled) controls.cycleMode() },
                onEffort = { send("/effort $it") },
                onCommand = ::command,
                enabled = sendEnabled,
                disabledReason = waiting,
                expanded = commandsShown,
                onExpand = toggleCommands,
                items = strip,
            )
        }
        // The strip's mode chip sends Shift+Tab for Claude, so while it shows the bar keeps its room for other keys.
        // Taken off the strip, the mode is back to the bar's Shift+Tab.
        val hiddenKeys by model.settings.hiddenKeys.flow.collectAsState()
        val keyOrder by model.settings.keyOrder.flow.collectAsState()
        val keys = remember(hiddenKeys, keyOrder) { model.settings.shownKeys(KEY_BAR_IDS) }
        KeyBar(
            controls, directTyping, onPaste, onToggleTyping, shiftTab = !(isClaude && stripShown && STRIP_MODE in strip), keys = keys,
            stripShown = stripShown,
            onToggleStrip = if (isClaude) ({ model.settings.claudeStripShown.value = !stripShown }) else null,
            enabled = ready,
        )
        if (!directTyping) {
            Composer(
                draft, ::updateDraft,
                when {
                    waiting != null -> waiting
                    menuOpen -> "Pick an option above"
                    else -> hint
                },
                working, controls,
                onSend = { text -> if (send(text)) updateDraft(TextFieldValue()) },
                menuOpen = menuOpen,
                enabled = ready,
                shell = shell,
                focusRequester = composerFocus,
            )
        }
    }

    confirming?.let { (cmd, picked) ->
        val (title, body, action) = when (cmd) {
            "/exit" -> Triple("Quit Claude?", "Claude ends here. If it wasn't started from a shell, the pane closes.", "Quit")
            else -> Triple("Clear the conversation?", "Claude starts a new conversation. /resume can bring this one back.", "Clear")
        }
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(enabled = sendEnabled, onClick = {
                    confirming = null
                    if (send(cmd) && picked) updateDraft(TextFieldValue())
                }) { Text(action, color = if (sendEnabled) Rex.error else Rex.textDim) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

/** Commands that end the conversation, or Claude itself, so a stray tap asks first. */
private val CONFIRMED_COMMANDS = setOf("/clear", "/exit")

/** How long a card that just appeared ignores taps: the enter animation, and then some. */
private const val CARD_ARM_MS = 500L

/** How long an answered card stays hidden if the screen still shows the same prompt, as when the answer was lost. */
private const val ANSWERED_HIDE_MS = 1500L

/** Claude Code ignores a note's digits for 600 ms after its buttons show, and a survey's keys after it does. */
private const val FYI_ARM_MS = 700L

/**
 * How long a pressed note button waits for the note to change before it can be pressed again: past
 * the time the press may take to scroll Claude's panel to the button and wait for its digits.
 */
private const val FYI_PRESSED_MS = 3500L

/**
 * What sits over the bottom of the terminal: the answers to a prompt on screen (or the blocked
 * notice), else Claude's survey and its "You should know" note, and the jump back to the live
 * screen while reading history. The cards show only while the screen is [live]. Touches on any of
 * it stay off the terminal, which would pass them to the program as mouse clicks.
 *
 * @param onTab moves one of Claude's questions to another of its tabs.
 * @param onOwn answers one of Claude's questions in the user's own words.
 * @param onFyi presses a button of the note.
 * @param onReadFyi has the rest of a note that doesn't fit Claude's panel read.
 * @param onSurvey answers Claude's survey.
 */
@Composable
internal fun BottomOverlay(
    info: State<ScreenInfo>,
    live: Boolean,
    blocked: String?,
    scrolledBack: () -> Boolean,
    onJump: () -> Unit,
    onChoose: (ScreenPrompt, PromptOption) -> Unit,
    modifier: Modifier = Modifier,
    onTab: (ScreenPrompt, Int) -> Unit = { _, _ -> },
    onOwn: (ScreenPrompt, String) -> Unit = { _, _ -> },
    onFyi: (FyiNote, FyiAction) -> Unit = { _, _ -> },
    onReadFyi: () -> Unit = {},
    onSurvey: (ClaudeSurvey, SurveyAnswer) -> Unit = { _, _ -> },
) {
    val prompt = info.value.prompt?.takeIf { live }
    val haptics = rememberViewHaptics()
    // Claude's note shows when no prompt does. The same note opened or scrolled is the same card; a
    // new one opens again, and new buttons wait a moment, as Claude ignores their digits at first.
    val fyi = info.value.fyi?.takeIf { live && prompt == null }
    var fyiCollapsed by remember(fyi?.headline) { mutableStateOf(false) }
    val fyiButtons = fyi?.let { it.headline to it.actions }
    var fyiReady by remember(fyiButtons) { mutableStateOf(false) }
    LaunchedEffect(fyiButtons) {
        delay(FYI_ARM_MS)
        fyiReady = true
    }
    // The button pressed, until the note changes, or for a while if it doesn't, as when the press was lost.
    var fyiPressed by remember { mutableStateOf<Pair<FyiNote, FyiAction>?>(null) }
    LaunchedEffect(fyi) { if (fyiPressed?.let { (n, a) -> fyi != null && fyi.sameNote(n) && a in fyi.actions } != true) fyiPressed = null }
    LaunchedEffect(fyiPressed) {
        if (fyiPressed != null) {
            delay(FYI_PRESSED_MS)
            fyiPressed = null
        }
    }
    // Claude's survey shows when no prompt does, over any note, which has no buttons meanwhile. Its
    // answers wait a moment too, and one tapped holds the rest until the survey changes, or for a
    // while if it doesn't.
    val survey = info.value.survey?.takeIf { live && prompt == null }
    var surveyReady by remember(survey) { mutableStateOf(false) }
    LaunchedEffect(survey) {
        delay(FYI_ARM_MS)
        surveyReady = true
    }
    var surveyPressed by remember { mutableStateOf<Pair<ClaudeSurvey, SurveyAnswer>?>(null) }
    LaunchedEffect(survey) { if (surveyPressed?.first != survey) surveyPressed = null }
    LaunchedEffect(surveyPressed) {
        if (surveyPressed != null) {
            delay(FYI_PRESSED_MS)
            surveyPressed = null
        }
    }
    val surveyBlocked = "Claude's message box has text. Send or clear it to answer.".takeIf {
        survey != null && surveyPressed == null && !info.value.claudeInput.isNullOrEmpty()
    }
    val lastSurvey = remember { arrayOfNulls<ClaudeSurvey>(1) }
    if (survey != null) lastSurvey[0] = survey
    // Claude's classic panel is only scrolled while Claude is idle, where an Esc that missed it
    // interrupts nothing: until then, buttons out of its sight can't be read or pressed.
    val fyiOutOfReach = fyi != null && !info.value.fullscreen && info.value.interruptible &&
        fyi.actions.none { fyi.shows(it) } && !(fyi.complete && fyi.actions.isEmpty())
    val fyiBlocked = when {
        fyi == null || fyiPressed != null -> null
        !info.value.claudeInput.isNullOrEmpty() -> "Claude's message box has text. Send or clear it to use these buttons."
        survey != null && fyi.actions.isEmpty() -> "Claude brings back the note's buttons once its survey is answered or dismissed."
        fyiOutOfReach && !fyi.complete -> "Claude is working. The rest of the note and its buttons come once it stops."
        fyiOutOfReach -> "Claude is working, so its panel can't scroll to these buttons. They work once it stops."
        else -> null
    }
    val lastFyi = remember { arrayOfNulls<FyiNote>(1) }
    if (fyi != null) lastFyi[0] = fyi
    // A note longer than Claude's panel has the rest read, by scrolling the panel; again once Claude
    // stops working, as the classic panel isn't scrolled while it works.
    LaunchedEffect(fyi?.headline, fyi?.rows?.size, fyi?.complete, fyiCollapsed, info.value.interruptible) {
        if (fyi != null && !fyi.complete && !fyiCollapsed) onReadFyi()
    }
    // A new question opens the card again; the highlight or ticks changing within the same one don't.
    val key = prompt?.let { p -> Triple(p.question, p.options.map { it.label }, p.context) }
    var collapsed by remember(key) { mutableStateOf(false) }
    // The prompt as it was when answered. While the screen still shows it, the card is hidden and
    // ignores taps, so a double tap can't answer twice; it comes back after a while in case the
    // answer was lost. Ticks in a multi-select question aren't answers and leave the card up.
    var answered by remember { mutableStateOf<ScreenPrompt?>(null) }
    LaunchedEffect(prompt) { if (answered?.let { a -> prompt?.sameAs(a) } != true) answered = null }
    LaunchedEffect(answered) {
        if (answered != null) {
            delay(ANSWERED_HIDE_MS)
            answered = null
        }
    }
    // Nor can the second tap answer a prompt that appeared right after the first.
    var ready by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(CARD_ARM_MS)
        ready = true
    }
    val shown = prompt?.takeIf { p -> answered?.let { p.sameAs(it) } != true }
    val lastShown = remember { arrayOfNulls<ScreenPrompt>(1) }
    if (shown != null) lastShown[0] = shown

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            scrolledBack(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.End),
        ) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.keepTapsOffTerminal().padding(end = 12.dp, bottom = 8.dp)
                    .reaching(Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onJump))
                    .size(42.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                    .background(Rex.surfaceHigh).border(0.5.dp, Rex.divider, CircleShape).indication(interaction, ripple()),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.ArrowDownward, "Jump to the bottom", tint = Rex.text, modifier = Modifier.size(20.dp)) }
        }
        AnimatedVisibility(shown != null && !collapsed, enter = slideInVertically { it / 3 } + fadeIn(), exit = fadeOut()) {
            lastShown[0]?.let { p ->
                // Only the card the screen still shows, once settled, and not after it was answered.
                fun live() = ready && answered == null && info.value.prompt == p
                val choose: (PromptOption) -> Unit = choose@{ option ->
                    if (!live()) return@choose
                    haptics.confirm()
                    if (!(p.multiSelect && option.checked != null)) answered = p
                    onChoose(p, option)
                }
                if (p.claudeQuestion) {
                    QuestionCard(
                        p,
                        onChoose = choose,
                        onTab = tab@{ i ->
                            if (!live()) return@tab
                            onTab(p, i)
                        },
                        // A single choice is answered with it; in a multi-select question it is one more tick.
                        onOwn = own@{ text ->
                            if (!live()) return@own
                            haptics.confirm()
                            if (!p.multiSelect) answered = p
                            onOwn(p, text)
                        },
                        onCollapse = { collapsed = true },
                        modifier = Modifier.keepTapsOffTerminal(),
                    )
                } else {
                    PromptCard(p, onChoose = choose, onCollapse = { collapsed = true }, modifier = Modifier.keepTapsOffTerminal())
                }
            }
        }
        AnimatedVisibility(survey != null, enter = slideInVertically { it / 3 } + fadeIn(), exit = fadeOut()) {
            lastSurvey[0]?.let { s ->
                SurveyCard(
                    s,
                    pressed = surveyPressed?.second,
                    blocked = surveyBlocked,
                    onAnswer = answer@{ answer ->
                        // Only the survey the screen still shows, once Claude takes its keys, one answer at a time.
                        if (!surveyReady || surveyPressed != null || surveyBlocked != null || info.value.survey != s) return@answer
                        haptics.confirm()
                        surveyPressed = s to answer
                        onSurvey(s, answer)
                    },
                    modifier = Modifier.keepTapsOffTerminal(),
                )
            }
        }
        AnimatedVisibility(fyi != null && !fyiCollapsed, enter = slideInVertically { it / 3 } + fadeIn(), exit = fadeOut()) {
            lastFyi[0]?.let { n ->
                FyiCard(
                    n,
                    pressed = fyiPressed?.second,
                    blocked = fyiBlocked,
                    onPress = press@{ action ->
                        // Only the note the screen still shows, once Claude takes its digits, one press at a time.
                        val now = info.value.fyi ?: return@press
                        if (!fyiReady || fyiPressed != null || fyiBlocked != null || !now.sameNote(n)) return@press
                        haptics.confirm()
                        fyiPressed = now to action
                        onFyi(now, action)
                    },
                    onCollapse = { fyiCollapsed = true },
                    modifier = Modifier.keepTapsOffTerminal(),
                )
            }
        }
        when {
            shown != null && collapsed -> CollapsedPrompt(shown, onExpand = { collapsed = false })
            fyi != null && fyiCollapsed -> CollapsedFyi(fyi, onExpand = { fyiCollapsed = false })
            prompt == null && blocked != null -> Text(
                blocked,
                color = Rex.onBlocked, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                modifier = Modifier.keepTapsOffTerminal().padding(10.dp).clip(RoundedCornerShape(12.dp))
                    .background(Rex.blocked).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Offers to restart Claude Code in its classic renderer while it runs fullscreen, where every scroll
 * goes to Claude and waits for it to redraw. Shows the switch's progress, or why it couldn't.
 */
@Composable
internal fun ClassicSwitchBanner(state: ClassicSwitch.State, onSwitch: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.keepTapsOffTerminal().fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).shadow(6.dp, shape).clip(shape)
            .background(Rex.surfaceHigh).border(0.5.dp, Rex.divider, shape).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is ClassicSwitch.State.Working) {
            WorkingDots(color = Rex.textDim)
            Spacer(Modifier.width(10.dp))
            Text(state.step, color = Rex.text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        } else {
            Text(
                (state as? ClassicSwitch.State.Failed)?.message
                    ?: "Claude is in fullscreen, so scrolling waits on Claude. Restart it in classic mode to scroll on the phone? " +
                    "Same conversation; your settings stay as they are.",
                color = Rex.text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onSwitch) {
                Text(if (state is ClassicSwitch.State.Failed) "Retry" else "Switch", color = Rex.accent, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss", tint = Rex.textDim) }
        }
    }
}

/** The same prompt whatever its highlight, which the arrow keys sent to answer it move. */
private fun ScreenPrompt.sameAs(other: ScreenPrompt): Boolean =
    this == other || unhighlighted() == other.unhighlighted()

private fun ScreenPrompt.unhighlighted() = copy(options = options.map { it.copy(selected = false) })

/** Takes taps that land here, so they don't reach the terminal under it as mouse clicks for the program. */
internal fun Modifier.keepTapsOffTerminal(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** A pill saying why the terminal isn't live, with dots while something is on its way, and an [action] such as Retry. */
@Composable
private fun StatusOverlay(text: String?, spinning: Boolean, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    // The last of each, so the pill fades out as it was.
    val last = remember { arrayOfNulls<Any>(3) }
    if (text != null) {
        last[0] = text
        last[1] = spinning
        last[2] = action
    }
    AnimatedVisibility(text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(top = 12.dp)) {
        @Suppress("UNCHECKED_CAST")
        val shownAction = last[2] as Pair<String, () -> Unit>?
        Row(
            Modifier.keepTapsOffTerminal().clip(RoundedCornerShape(50)).background(Rex.surfaceHigh)
                .padding(start = 14.dp, end = if (shownAction != null) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (last[1] == true) {
                WorkingDots(color = Rex.textDim)
                Spacer(Modifier.width(8.dp))
            }
            Text(last[0] as String? ?: "", color = Rex.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            shownAction?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(label, color = Rex.accent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** The top bar folded to one line: the tab's title and state, and a tap anywhere on it to unfold it. */
@Composable
private fun CollapsedHeader(title: String, status: ConnStatus, agent: Pair<String, Color>?, elsewhere: Color?, onExpand: () -> Unit) {
    // A thin line to look at, with a tap target as tall as a button's.
    Row(
        Modifier.fillMaxWidth().reachingTap(drawn = 28.dp, onClickLabel = "Show the header", onClick = onExpand)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionDot(status)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, color = Rex.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            agent?.let { (label, color) ->
                Text(" · ", color = Rex.textDim, fontSize = 12.sp)
                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        elsewhere?.let { Box(Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape).background(it)) }
        Icon(Icons.Outlined.ExpandMore, "Show the header", tint = Rex.textDim, modifier = Modifier.size(18.dp))
    }
}

/** The height a tap target should have at least, as for a small button. */
internal val TAP_MIN = 40.dp

/**
 * Something drawn [drawn] tall that takes taps over [TAP_MIN], reaching equally above and below it
 * without taking more room. The reach overlaps its neighbours, so it is put over them; the press
 * shows on what is drawn.
 */
internal fun Modifier.reachingTap(drawn: Dp, onClickLabel: String, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    zIndex(1f)
        .layout { measurable, constraints ->
            val d = drawn.roundToPx()
            val t = max(d, TAP_MIN.roundToPx())
            val p = measurable.measure(constraints.copy(minHeight = t, maxHeight = t))
            layout(p.width, d) { p.place(0, (d - t) / 2) }
        }
        .clickable(interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick)
        .layout { measurable, constraints ->
            val d = drawn.roundToPx()
            val t = max(d, TAP_MIN.roundToPx())
            val p = measurable.measure(constraints.copy(minHeight = d, maxHeight = d))
            layout(p.width, t) { p.place(0, (t - d) / 2) }
        }
        .indication(interaction, ripple())
}

/** The size a touch target should have at least, each way, as Android's accessibility guidelines ask. */
internal val TOUCH_MIN = 48.dp

/**
 * Takes touches over at least [TOUCH_MIN] each way, centred on what is drawn, without taking more room:
 * [touch], such as a click and its semantics, gets the larger area, and the reach is put over the
 * neighbours. What follows in the chain is what is drawn, where a press should show.
 */
internal fun Modifier.reaching(touch: Modifier): Modifier =
    zIndex(1f)
        .layout { measurable, constraints ->
            val p = measurable.measure(constraints.loosenedBy(TOUCH_MIN.roundToPx()))
            // The drawn size, which the touch area passes out, so this takes only that room.
            val w = p[DrawnWidth].takeIf { it != AlignmentLine.Unspecified } ?: p.width
            val h = p[DrawnHeight].takeIf { it != AlignmentLine.Unspecified } ?: p.height
            layout(w, h) { p.place((w - p.width) / 2, (h - p.height) / 2) }
        }
        .then(touch)
        .layout { measurable, constraints ->
            val reach = TOUCH_MIN.roundToPx()
            val p = measurable.measure(constraints.loosenedBy(-reach))
            val w = max(p.width, reach)
            val h = max(p.height, reach)
            layout(w, h, mapOf(DrawnWidth to p.width, DrawnHeight to p.height)) { p.place((w - p.width) / 2, (h - p.height) / 2) }
        }

/** What [reaching] draws, passed out of its touch area as alignment lines: they carry a size, not a position. */
private val DrawnWidth = VerticalAlignmentLine { a, b -> min(a, b) }
private val DrawnHeight = HorizontalAlignmentLine { a, b -> min(a, b) }

/** These constraints with [by] more room at most, each way, where they have a limit. */
private fun Constraints.loosenedBy(by: Int) = copy(
    maxWidth = if (hasBoundedWidth) maxWidth + by else maxWidth,
    maxHeight = if (hasBoundedHeight) maxHeight + by else maxHeight,
)

/** The text of the rows shown from [topRow], as the screen has them. */
private fun visibleText(terminal: RemoteTerminal, topRow: Int): String {
    val emu = terminal.session.emulator ?: return ""
    return emu.screen.getSelectedText(0, topRow, emu.mColumns, topRow + emu.mRows - 1, true, true).trimEnd()
}

/** The window's tabs, and a "+" for a new one that shows a spinner and takes no more taps while one is [adding]. */
@Composable
private fun WindowTabs(
    windows: List<WindowView>,
    selected: String?,
    metas: Map<String, BlockMeta>,
    onSelect: (String) -> Unit,
    adding: Boolean,
    onAdd: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val i = windows.indexOfFirst { it.windowId == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // The "+" is shorter than a tab: centred on them rather than sitting at their top.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(windows, key = { it.windowId }) { w ->
            val bid = representativeBlock(w, metas)
            val meta = bid?.let { metas[it] }
            val isSel = w.windowId == selected
            val unseen = !isSel && w.blocks.any { metas[it.blockId]?.unseen == true }
            val st = meta?.status?.state
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .reaching(
                        Modifier.selectable(isSel, interaction, indication = null, role = Role.Tab) { onSelect(w.windowId) }
                            // What the dots after the title show, which screen readers can't see.
                            .semantics {
                                when {
                                    st == "working" -> stateDescription = "Working"
                                    st == "blocked" -> stateDescription = "Needs you"
                                    unseen -> stateDescription = "New output"
                                }
                            },
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) Rex.surfaceHigh else Color.Transparent)
                    .border(0.5.dp, if (isSel) Rex.divider else Color.Transparent, RoundedCornerShape(10.dp))
                    .indication(interaction, ripple())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgramIcon(programOf(meta), size = 18.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    windowTitle(w, meta), color = if (isSel) Rex.text else Rex.textDim, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
                )
                if (st == "working") WorkingDots(Modifier.padding(start = 6.dp))
                else if (st == "blocked") Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(Rex.blocked))
                else if (unseen) Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(Rex.unseen))
            }
        }
        item("add") {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.reaching(Modifier.clickable(interaction, indication = null, enabled = !adding, role = Role.Button, onClick = onAdd))
                    .clip(RoundedCornerShape(10.dp)).indication(interaction, ripple()).padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The spinner takes the icon's place, so the strip doesn't move.
                if (adding) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp).padding(1.dp).semantics { contentDescription = "Opening a tab" },
                        strokeWidth = 1.5.dp, color = Rex.textDim,
                    )
                } else Icon(Icons.Outlined.Add, "New tab", tint = Rex.textDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A small map of the window's panes, and a chip for each, to switch between them. */
@Composable
private fun PaneStrip(win: WindowView, selected: String?, metas: Map<String, BlockMeta>, onSelect: (String) -> Unit) {
    val placed = win.placedBlocks
    // No padding below: the chips' taller tap targets take that room.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier.size(width = 54.dp, height = 34.dp).pointerInput(placed) {
                detectTapGestures { pos ->
                    val hit = placed.lastOrNull { (_, r) ->
                        pos.x / size.width in r[0]..(r[0] + r[2]) && pos.y / size.height in r[1]..(r[1] + r[3])
                    }
                    hit?.let { onSelect(it.first) }
                }
            },
        ) {
            val gap = 2.dp.toPx()
            placed.forEach { (id, r) ->
                val topLeft = Offset(r[0].toFloat() * size.width + gap / 2, r[1].toFloat() * size.height + gap / 2)
                val sz = Size(r[2].toFloat() * size.width - gap, r[3].toFloat() * size.height - gap)
                val isSel = id == selected
                drawRoundRect(
                    color = if (isSel) Rex.accent else Rex.surfaceHigh, topLeft = topLeft, size = sz,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
                if (!isSel) drawRoundRect(
                    color = Rex.divider, topLeft = topLeft, size = sz, style = Stroke(1f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            placed.forEach { (id, _) ->
                val m = metas[id]
                val isSel = id == selected
                // The chip is drawn small in a button's height, and takes taps beyond it; the press shows on the chip.
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier.heightIn(min = TAP_MIN).wrapContentHeight()
                        .reaching(Modifier.selectable(isSel, interaction, indication = null, role = Role.Tab) { onSelect(id) })
                        .clip(RoundedCornerShape(50))
                        .background(if (isSel) Rex.accentSoft else Rex.surface)
                        .indication(interaction, ripple())
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgramIcon(programOf(m), size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(blockTitle(m), color = if (isSel) Rex.text else Rex.textDim, fontSize = 12.sp, maxLines = 1)
                    if (m?.status?.state == "working") WorkingDots(Modifier.padding(start = 5.dp))
                    if (m?.status?.state == "blocked") Box(Modifier.padding(start = 5.dp).size(6.dp).clip(CircleShape).background(Rex.blocked))
                }
            }
        }
    }
}
