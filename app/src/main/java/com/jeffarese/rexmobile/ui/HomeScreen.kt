package com.jeffarese.rexmobile.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.BlockMeta
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.model.HostState
import com.jeffarese.rexmobile.model.Liveness
import com.jeffarese.rexmobile.model.PanePrompt
import com.jeffarese.rexmobile.model.PanePrompts
import com.jeffarese.rexmobile.rex.ProgramStatus
import com.jeffarese.rexmobile.rex.RexCallException
import com.jeffarese.rexmobile.rex.SessionSummary
import com.jeffarese.rexmobile.rex.WindowView
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.R
import com.jeffarese.rexmobile.ui.onboarding.Problem
import com.jeffarese.rexmobile.ui.onboarding.classify
import com.jeffarese.rexmobile.ui.onboarding.hint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Where to go when a row is tapped. */
data class Target(val hostId: String, val sessionId: String, val windowId: String, val blockId: String?)

/** How long a host with sessions may be away before Home shows them as out of date. */
private const val STALE_AFTER_MS = 1000L

/** The longest a pull to refresh waits for the hosts to be back before it names those that aren't. */
internal const val REFRESH_MS = 6_000L

/** How wide Home's content grows: on a tablet, an unfolded phone or in landscape, a column in the middle. */
private val MAX_CONTENT_WIDTH = 640.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, onOpen: (Target) -> Unit, onHosts: () -> Unit, onSettings: () -> Unit = onHosts) {
    // Current from the first frame, so a restored scroll position isn't lost to an empty list.
    val hosts by model.hostStates.collectAsState()
    // A preference, not just screen state: it stays as the user left it across launches.
    var watchOnly by remember { mutableStateOf(model.prefs.getBoolean("watch_only", false)) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Set when the user opens search, so the keyboard comes up then, not when Home comes back with it open.
    var focusSearch by remember { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var refreshing by remember { mutableStateOf(false) }
    // Sessions a new tab is being opened in, so a second tap on "+" doesn't open another.
    var addingTab by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val context = LocalContext.current
    var updateSheet by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Done and failed cards cleared with a swipe or their group's Clear. They hide at once but are marked seen only
    // when the snackbar goes without an Undo, or Home does, so Undo has nothing to take back.
    val cleared = remember { mutableStateMapOf<String, Cleared>() }
    DisposableEffect(Unit) { onDispose { cleared.values.forEach { it.markSeen(model) } } }
    // Claude's prompts on the Needs you cards, read while Home shows them, and the answers under way.
    val prompts = remember { BoardPrompts() }
    val haptics = rememberViewHaptics()

    fun setWatchOnly(on: Boolean) {
        watchOnly = on
        model.prefs.edit().putBoolean("watch_only", on).apply()
    }

    fun clear(entries: List<BoardEntry>) {
        val batch = entries.map { Cleared(it.hostId, it.blockId, it.meta.statuses) }
        batch.forEach { cleared[it.key] = it }
        val message = entries.singleOrNull()?.let { "Cleared “${blockTitle(it.meta)}”" }
            ?: "Cleared ${entries.size} ${entries.first().group.label.lowercase()}"
        // One still up for an earlier clear goes, which settles that one.
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val undone = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
            batch.forEach { if (undone) cleared.remove(it.key) else it.markSeen(model) }
        }
    }

    /**
     * Answer [e]'s Claude with [option] of [prompt], then say in the snackbar how that went, as its notification
     * would. The answer goes on if Home doesn't: only what Home says of it goes with Home.
     */
    fun answer(e: BoardEntry, prompt: PanePrompt, option: PromptOption) {
        if (e.key in prompts.answering) return
        prompts.answering[e.key] = option
        haptics.confirm()
        scope.launch {
            val result = try {
                model.scope.async {
                    model.connection(prompt.hostId)?.let { model.prompts.answer(it, prompt, option) } ?: PanePrompts.Answer.UNREACHABLE
                }.await()
            } finally {
                prompts.answering.remove(e.key)
            }
            prompts.answered(e, result)
            val message = when (result) {
                PanePrompts.Answer.ANSWERED -> "Answered: ${option.label}"
                PanePrompts.Answer.UNCONFIRMED -> "Sent ${option.label}, but Claude didn't seem to take it"
                PanePrompts.Answer.CHANGED -> "Not answered: the screen has changed since"
                PanePrompts.Answer.UNREACHABLE -> "Not answered: couldn't reach ${e.hostLabel}"
                // The answer under way says how it went.
                PanePrompts.Answer.BUSY -> return@launch
            }
            // Taken, the card leaves once Claude goes on; otherwise the pane is the place to see why.
            val open = if (result == PanePrompts.Answer.ANSWERED) null else "Open"
            snackbar.currentSnackbarData?.dismiss()
            val opened = snackbar.showSnackbar(
                message, actionLabel = open, duration = if (open == null) SnackbarDuration.Short else SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed
            if (opened) onOpen(Target(e.hostId, e.sessionId, e.window.windowId, e.blockId))
        }
    }

    // Back closes search before it leaves the app.
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val stale = rememberStaleHosts(hosts)
    // What an unreachable host last said may no longer be true, so it isn't counted or offered as waiting.
    val live = hosts.filter { it.first.id !in stale }
    val board = remember(live) { agentBoard(live.map { (c, st) -> c.config to st }) }.filterNot { cleared[it.key]?.hides(it) == true }
    val needsYou = board.count { it.group == BoardGroup.NEEDS_YOU }
    val working = board.count { it.group == BoardGroup.WORKING }
    // A search hides the board, and with it the answers.
    if (query.isBlank()) {
        prompts.ReadScreens(model, board, online = hosts.filter { it.second.status == ConnStatus.ONLINE }.map { it.first.id }.toSet())
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val filtering = watchOnly || query.isNotBlank()
    // The tabs whose agent is on the board, which the list below leaves out rather than show twice. A search
    // hides the board, so then it finds them in the list.
    val onBoard = remember(board, query) { if (query.isBlank()) board.map { it.hostId to it.window.windowId }.toSet() else emptySet() }
    // Each host with its sessions and the tabs the search, the filter and the board leave. The list is built
    // from this alone: it runs later than this composition, when the hosts flow may already hold a newer list.
    val shown = remember(hosts, query, watchOnly, onBoard) {
        hosts.map { (conn, state) ->
            ShownHost(conn, state, state.sessions.map { s ->
                s to state.views[s.sessionId]?.windows.orEmpty().filter { w ->
                    (conn.id to w.windowId) !in onBoard && matches(w, state.blocks, query) &&
                        (!watchOnly || w.blocks.any { state.blocks[it.blockId]?.statuses?.isNotEmpty() == true })
                }
            })
        }
    }
    // Agents only, with every agent on the board, leaves nothing below; that isn't worth a card saying so.
    val nothingShown = filtering && board.isEmpty() && shown.any { it.state.sessions.isNotEmpty() } &&
        shown.all { h -> h.sessions.all { it.second.isEmpty() } }

    val pullState = rememberPullToRefreshState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Rex.bg)) {
        // Wider than a phone, the list keeps a phone's measure in the middle, and scrolls from anywhere across.
        val side = ((maxWidth - MAX_CONTENT_WIDTH) / 2).coerceAtLeast(0.dp)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    val away = refreshHosts(model.connections.value)
                    refreshing = false
                    if (away.isNotEmpty()) {
                        Toast.makeText(context, "Still away: ${away.joinToString(", ")}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            state = pullState,
            // Below the status bar, which the list scrolls under.
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState, isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                // The end clears the bottom bar, and the keyboard when search has it up.
                contentPadding = PaddingValues(
                    start = side, end = side,
                    bottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding() + 88.dp,
                ),
            ) {
                item("title") {
                    Row(
                        Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(painterResource(R.drawable.chompy_head), contentDescription = null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Chompy", style = MaterialTheme.typography.headlineMedium, fontSize = 26.sp, color = Rex.text)
                        Spacer(Modifier.width(12.dp))
                        val summary = buildList {
                            if (hosts.isNotEmpty()) add("${hosts.count { it.second.status == ConnStatus.ONLINE }}/${hosts.size} hosts")
                            if (working > 0) add("$working working")
                            if (needsYou > 0) add("$needsYou waiting")
                        }.joinToString(" · ")
                        Text(
                            summary, color = Rex.textDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                        )
                    }
                }
                item("update") { UpdateCard(model.updater, onOpen = { updateSheet = true }) }
                if (searching) item("search") { SearchField(query, autoFocus = focusSearch, onFocused = { focusSearch = false }) { query = it } }
                // The agents first, most urgent at the top; the tabs follow. Each card is its own item, so one
                // that moves between groups (working to needs you, say) slides there rather than jumping.
                if (board.isNotEmpty() && query.isBlank()) {
                    val multiHost = live.size > 1
                    board.groupBy { it.group }.toSortedMap().forEach { (group, entries) ->
                        // What's done or failed has only to be looked at, so it can be put away unopened; what needs
                        // you or still works can't.
                        val clearable = group == BoardGroup.FAILED || group == BoardGroup.DONE
                        item("board-${group.name}") {
                            BoardHeader(group, entries.size, onClear = if (clearable) ({ clear(entries) }) else null, Modifier.animateItem())
                        }
                        itemsIndexed(entries, key = { _, e -> "agent-${e.key}" }) { i, e ->
                            AgentCard(
                                e, multiHost, now, first = i == 0, last = i == entries.lastIndex, onOpen,
                                onClear = if (clearable) ({ clear(listOf(e)) }) else null,
                                prompt = prompts.promptOf(e), answering = prompts.answering[e.key],
                                onAnswer = { p, o -> answer(e, p, o) }, modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    item("board-end") { Spacer(Modifier.height(6.dp)) }
                }
                if (shown.isEmpty()) item("empty") { EmptyState(onHosts) }
                if (nothingShown) item("no-match") { NothingShown(query, watchOnly, onShowAll = { setWatchOnly(false) }) }
                shown.forEach { (conn, state, sessions) ->
                    val isStale = conn.id in stale
                    item("host-${conn.id}") { Box(Modifier.animateItem()) { HostHeader(conn, state, isStale) } }
                    if (state.sessions.isEmpty()) {
                        item("host-status-${conn.id}") { Box(Modifier.animateItem()) { HostStatusCard(conn, state) } }
                    }
                    sessions.forEach { (s, windows) ->
                        // A session the search or filter empties is left out; the card above says so if all are.
                        if (filtering && windows.isEmpty()) return@forEach
                        val view = state.views[s.sessionId]
                        val key = "${conn.id}/${s.sessionId}"
                        val isCollapsed = key in collapsed && query.isBlank()
                        // Every tab is on the board: the session is a line, there to open a new tab in.
                        val allOnBoard = windows.isEmpty() && view?.windows.orEmpty().any { (conn.id to it.windowId) in onBoard }
                        item("session-$key") {
                            SessionHeader(
                                label = view?.label ?: s.label,
                                collapsed = isCollapsed,
                                count = windows.size,
                                onBoard = allOnBoard,
                                adding = key in addingTab,
                                onToggle = { collapsed = if (key in collapsed) collapsed - key else collapsed + key },
                                onAdd = {
                                    if (key !in addingTab) {
                                        addingTab = addingTab + key
                                        scope.launch {
                                            val wid = try {
                                                attempt(context, conn, "open a tab") { conn.newWindow(s.sessionId) }
                                            } finally {
                                                addingTab = addingTab - key
                                            }
                                            if (wid != null) onOpen(Target(conn.id, s.sessionId, wid, null))
                                        }
                                    }
                                },
                                modifier = Modifier.animateItem().dimmed(isStale),
                            )
                        }
                        item("windows-$key") {
                            AnimatedVisibility(
                                !isCollapsed && !allOnBoard, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
                                modifier = Modifier.animateItem().dimmed(isStale),
                            ) {
                                if (windows.isEmpty()) EmptySession(loaded = view != null)
                                else WindowGroup(conn, s.sessionId, windows, state.blocks, live = !isStale, onOpen)
                            }
                        }
                    }
                }
            }
        }

        // The list scrolls under the see-through status bar; this keeps the clock and icons readable over it.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Rex.bg.copy(alpha = .92f)))

        BottomBar(
            watchOnly = watchOnly,
            searching = searching,
            hasUnseen = board.any { it.group != BoardGroup.WORKING },
            updatePending = updatePending(model.updater),
            onSettings = onSettings,
            onWatch = { setWatchOnly(!watchOnly) },
            onSearch = {
                searching = !searching
                if (searching) {
                    focusSearch = true
                    // The box goes at the top of the list, which may be scrolled away; it takes the focus once it shows.
                    scope.launch { list.animateScrollToItem(0) }
                } else query = ""
            },
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = MAX_CONTENT_WIDTH),
        )

        // Just above the bottom bar, which is 80dp tall.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).widthIn(max = MAX_CONTENT_WIDTH)
                .navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 80.dp),
        ) { Snackbar(it, containerColor = Rex.surfaceHigh, contentColor = Rex.text, actionColor = Rex.accent) }
    }
    if (updateSheet) UpdateSheet(model.updater, onDismiss = { updateSheet = false })
}

/** A card cleared from the board, and the results it showed, so that a newer one shows again. */
private class Cleared(val hostId: String, val blockId: String, val statuses: List<ProgramStatus>) {
    val key get() = "$hostId/$blockId"
    private var marked = false

    fun hides(e: BoardEntry) = e.meta.statuses == statuses

    /** Once only, and only if the agent still shows what was cleared: what it reported since hasn't been seen. */
    fun markSeen(model: AppModel) {
        if (marked) return
        marked = true
        val conn = model.connection(hostId) ?: return
        if (conn.state.value.blocks[blockId]?.statuses == statuses) conn.markSeen(blockId)
    }
}

/**
 * At the user's asking, a pull to refresh: checks every host's connection, replacing one that doesn't answer,
 * and waits until all are online again, or [REFRESH_MS] at most. Returns the names of those that aren't.
 */
internal suspend fun refreshHosts(connections: List<HostConnection>, timeoutMs: Long = REFRESH_MS): List<String> {
    val checks = connections.map { c ->
        val answer = CompletableDeferred<Liveness>()
        c.connect()
        c.ensureAlive(force = true) { result, _ -> answer.complete(result) }
        c to answer
    }
    withTimeoutOrNull(timeoutMs) {
        checks.forEach { (c, answer) ->
            // One that didn't answer is being replaced, though it may not have gone offline yet.
            if (answer.await() == Liveness.SILENT) c.state.first { it.status != ConnStatus.ONLINE }
            c.state.first { it.status == ConnStatus.ONLINE }
        }
    }
    return connections.filter { it.state.value.status != ConnStatus.ONLINE }.map { it.config.label }
}

/**
 * The hosts whose sessions are shown but which aren't online, so what Home shows of them is out of date.
 * A host joins only after [STALE_AFTER_MS] away, so a quick reconnect on coming back to the app doesn't
 * flash it, and leaves the moment it is back online.
 */
@Composable
private fun rememberStaleHosts(hosts: List<Pair<HostConnection, HostState>>): Set<String> {
    val away = hosts.filter { (_, s) -> s.status != ConnStatus.ONLINE && s.sessions.isNotEmpty() }.map { it.first.id }.toSet()
    var stale by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(away) {
        stale = stale intersect away
        if (!stale.containsAll(away)) {
            delay(STALE_AFTER_MS)
            stale = away
        }
    }
    return stale intersect away
}

/** Faded to half, for a host's rows while what they show is out of date. */
@Composable
private fun Modifier.dimmed(on: Boolean): Modifier {
    val alpha by animateFloatAsState(if (on) 0.5f else 1f, tween(200), label = "dim")
    return graphicsLayer { this.alpha = alpha }
}

/**
 * Runs [call] against [conn]. When it fails or comes back empty, a toast says so, such as "Couldn't open a
 * tab on Studio: not connected", and the result is null.
 */
internal suspend fun <T : Any> attempt(context: Context, conn: HostConnection, what: String, call: suspend () -> T?): T? {
    val failure: Throwable? = try {
        val result = call()
        if (result != null) return result
        null
    } catch (e: TimeoutCancellationException) {
        e
    } catch (e: CancellationException) {
        // Home went away while waiting: there's no one to tell.
        throw e
    } catch (e: Exception) {
        e
    }
    val reason = failureReason(failure, conn.state.value.status)
    Toast.makeText(context, "Couldn't $what on ${conn.config.label}" + (reason?.let { ": $it" } ?: ""), Toast.LENGTH_SHORT).show()
    return null
}

/** Why a call to a host failed, in a few words, or null when there's nothing more to say. */
internal fun failureReason(e: Throwable?, status: ConnStatus): String? = when {
    // Calls wait for the host to come online, so a host that isn't is the reason whatever the exception.
    status != ConnStatus.ONLINE -> "not connected"
    e is TimeoutCancellationException -> "no answer"
    e is RexCallException -> e.error.message.ifBlank { null }
    else -> e?.message?.ifBlank { null }
}

private data class ShownHost(val conn: HostConnection, val state: HostState, val sessions: List<Pair<SessionSummary, List<WindowView>>>)

/** The board's groups, in the order Home shows them: what needs a person first. */
internal enum class BoardGroup(val label: String) { NEEDS_YOU("Needs you"), FAILED("Failed"), DONE("Done"), WORKING("Working") }

/** One agent on the board: a block whose program reports its status (OSC 7501), or has news not yet seen. */
internal data class BoardEntry(
    val hostId: String,
    val hostLabel: String,
    val sessionId: String,
    val sessionLabel: String?,
    val window: WindowView,
    val blockId: String,
    val meta: BlockMeta,
    val group: BoardGroup,
) {
    val key get() = "$hostId/$blockId"
}

/**
 * Every agent worth a look, grouped and ordered. Blocked and working agents stay while they are; a finished or
 * failed one stays until it is seen, since OSC 7501's done means "finished, awaiting review". A bell or
 * notification with no status counts as needing you. Within a group the order is stable, so cards don't
 * reshuffle each time a working agent reports progress: needs you by longest waiting, done and failed by
 * newest, working by name.
 */
internal fun agentBoard(hosts: List<Pair<HostConfig, HostState>>): List<BoardEntry> = hosts.flatMap { (host, st) ->
    st.views.values.flatMap { v ->
        v.windows.orEmpty().flatMap { w ->
            w.blocks.mapNotNull { b ->
                val m = st.blocks[b.blockId] ?: return@mapNotNull null
                val state = m.status?.state
                val group = when {
                    state == "blocked" -> BoardGroup.NEEDS_YOU
                    state == "working" -> BoardGroup.WORKING
                    !m.unseen -> null
                    state == "error" -> BoardGroup.FAILED
                    state == "done" -> BoardGroup.DONE
                    else -> BoardGroup.NEEDS_YOU
                } ?: return@mapNotNull null
                BoardEntry(host.id, host.label, v.sessionId ?: m.sessionId, v.label, w, b.blockId, m, group)
            }
        }
    }
}.sortedWith(
    compareBy<BoardEntry> { it.group }.thenComparator { a, b ->
        val (ta, tb) = a.meta.status?.updatedAt.orEmpty() to b.meta.status?.updatedAt.orEmpty()
        when (a.group) {
            BoardGroup.NEEDS_YOU -> ta.compareTo(tb)
            BoardGroup.FAILED, BoardGroup.DONE -> tb.compareTo(ta)
            BoardGroup.WORKING -> blockTitle(a.meta).compareTo(blockTitle(b.meta), ignoreCase = true)
        }
    }.thenBy { it.blockId },
)

/** How long ago [updatedAt] (RFC 3339, the server's clock) was, as "now", "4m", "2h" or "3d"; null if it can't be read. */
internal fun since(updatedAt: String?, now: Long): String? {
    val mins = minutesSince(updatedAt, now) ?: return null
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 24 * 60 -> "${mins / 60}h"
        else -> "${mins / (24 * 60)}d"
    }
}

/** [since] as a screen reader says it: "just now", "4 minutes ago", "1 hour ago". */
internal fun spokenSince(updatedAt: String?, now: Long): String? {
    val mins = minutesSince(updatedAt, now) ?: return null
    fun ago(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        mins < 1 -> "just now"
        mins < 60 -> ago(mins, "minute")
        mins < 24 * 60 -> ago(mins / 60, "hour")
        else -> ago(mins / (24 * 60), "day")
    }
}

private fun minutesSince(updatedAt: String?, now: Long): Long? {
    val then = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
    // A server clock a little ahead of the phone's reads as now, not as the future.
    return ((now - then) / 60_000).coerceAtLeast(0)
}

private fun matches(w: WindowView, metas: Map<String, BlockMeta>, q: String): Boolean {
    if (q.isBlank()) return true
    val needle = q.trim().lowercase()
    return (w.label?.lowercase()?.contains(needle) == true) || w.blocks.any { b ->
        val m = metas[b.blockId] ?: return@any false
        listOfNotNull(m.title, m.taskTitle, m.process, prettyPath(m.pwd), displayProgram(m)).any { it.lowercase().contains(needle) }
    }
}

/** The search box; it takes the focus (and brings the keyboard up) when [autoFocus] is set, then calls [onFocused]. */
@Composable
private fun SearchField(query: String, autoFocus: Boolean, onFocused: () -> Unit, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focus.requestFocus()
            onFocused()
        }
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(Rex.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Rex.textDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search tabs, programs, folders", color = Rex.textFaint)
            BasicTextField(
                query, onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Rex.text),
                cursorBrush = SolidColor(Rex.accent),
                // Results filter as you type, so the search key just puts the keyboard away to see them.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        // 18dp to see and 48dp to tap: the button spills past the icon's room rather than making the box taller.
        if (query.isNotEmpty()) {
            Box(Modifier.size(18.dp).wrapContentSize(unbounded = true)) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, "Clear", tint = Rex.textDim, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** In place of a list the search or the agents-only filter has emptied, which looked like nothing was there. */
@Composable
private fun NothingShown(query: String, watchOnly: Boolean, onShowAll: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Rex.surface).padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
    ) {
        val q = query.trim()
        Text(
            if (q.isNotEmpty()) "No tabs match “$q”" else "No agent tabs right now",
            color = Rex.text, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (watchOnly) {
            Text("Only tabs running an agent are shown.", color = Rex.textDim, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = onShowAll) { Text("Show all tabs", color = Rex.accent) }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun BoardGroup.color() = when (this) {
    BoardGroup.NEEDS_YOU -> Rex.blocked
    BoardGroup.FAILED -> Rex.error
    BoardGroup.DONE -> Rex.done
    BoardGroup.WORKING -> Rex.working
}

/** A group's name and count; [onClear], when set, puts all its cards away. */
@Composable
private fun BoardHeader(group: BoardGroup, count: Int, onClear: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
            Text(group.label, color = group.color(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("$count", color = Rex.textFaint, fontSize = 13.sp)
        }
        // As small as the name beside it, which keeps the header low; Compose still takes taps within 48dp of it.
        if (onClear != null) {
            Text(
                "Clear", color = Rex.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClear).padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * One agent: its program, its task, and what it last said. Waiting, finished and failed cards say how long ago
 * that was; a working one shows its progress when it reports one, and any subtasks under it. A tap opens its
 * terminal, on the very block. The cards of a group join into one rounded box, like a session's tabs below;
 * [first] and [last] round its ends. A swipe either way clears a card that has [onClear].
 *
 * With [prompt], the Claude prompt its pane shows, the card offers its answers too, which [onAnswer] gives;
 * [answering] is the one under way. The rest of the options are a tap away, in the terminal.
 */
@Composable
private fun AgentCard(
    e: BoardEntry,
    showHost: Boolean,
    now: Long,
    first: Boolean,
    last: Boolean,
    onOpen: (Target) -> Unit,
    onClear: (() -> Unit)?,
    prompt: PanePrompt? = null,
    answering: PromptOption? = null,
    onAnswer: (PanePrompt, PromptOption) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val status = e.meta.status
    val color = e.group.color()
    val title = blockTitle(e.meta)
    val detail = agentDetail(e.meta, title)
    val shape = RoundedCornerShape(
        topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp,
    )
    // Space under the group's box, before the next group's name.
    SwipeToClear(onClear, modifier.padding(start = 16.dp, end = 16.dp, bottom = if (last) 4.dp else 0.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(shape).background(Rex.surface)
                .testTag("agent:${e.blockId}")
                .clickable { onOpen(Target(e.hostId, e.sessionId, e.window.windowId, e.blockId)) }
                .semantics {
                    stateDescription = boardState(e, title, now)
                    if (onClear != null) customActions = listOf(CustomAccessibilityAction("Clear") { onClear(); true })
                },
        ) {
            if (!first) HorizontalDivider(color = Rex.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 52.dp))
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The group's colour as a dot on the icon's corner, ringed in the card's colour so it sits apart. It
                    // has nothing to say to a screen reader: the card's state says the group.
                    Box {
                        ProgramIcon(programOf(e.meta), size = 26.dp)
                        Box(
                            Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(11.dp)
                                .background(Rex.surface, CircleShape).padding(2.dp).background(color, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Rex.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (detail != null) {
                            Text(
                                detail, color = if (e.group == BoardGroup.NEEDS_YOU) Rex.blocked else Rex.textDim, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Where it runs. The title and message come from the program, so this is what tells a real
                        // agent from a program dressed up as one in another terminal, as OSC 7501 asks.
                        val source = agentSource(e, title, showHost)
                        if (source != null) {
                            Text(source, color = Rex.textFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    if (e.group == BoardGroup.WORKING) WorkingDots(color = color)
                    // Spoken in the card's state, in words rather than as "4m".
                    else since(status?.updatedAt, now)?.let { Text(it, color = Rex.textFaint, fontSize = 13.sp, modifier = Modifier.clearAndSetSemantics {}) }
                }
                val progress = status?.progress?.takeIf { e.group == BoardGroup.WORKING }
                if (progress != null) {
                    val shown by animateFloatAsState(progress.coerceIn(0, 100) / 100f, tween(400), label = "progress")
                    LinearProgressIndicator(
                        progress = { shown }, color = color, trackColor = Rex.surfaceHigh, strokeCap = StrokeCap.Round,
                        modifier = Modifier.padding(start = 38.dp, top = 8.dp).fillMaxWidth().height(3.dp),
                    )
                }
                Subtasks(e.meta, status, title)
                if (prompt != null) {
                    BoardAnswers(prompt, detail, answering, onAnswer = { onAnswer(prompt, it) }, Modifier.padding(start = 38.dp, top = 10.dp))
                }
            }
        }
    }
}

/**
 * What a screen reader says of an agent's card after what it reads on it: its group, what it waits for when the
 * card doesn't already say, and how long ago, as in "Needs you, waiting for permission, 4 minutes ago".
 */
internal fun boardState(e: BoardEntry, title: String, now: Long): String {
    val status = e.meta.status
    val waitingFor = kindText(status)
        ?.takeIf { e.group == BoardGroup.NEEDS_YOU && status?.kind in setOf("permission", "question", "auth") && it != agentDetail(e.meta, title) }
    val ago = spokenSince(status?.updatedAt, now).takeIf { e.group != BoardGroup.WORKING }
    return listOfNotNull(e.group.label, waitingFor?.replaceFirstChar { it.lowercase() }, ago).joinToString(", ")
}

/** [content], which a swipe either way slides off and clears when [onClear] is set, with "Clear" showing behind it. */
@Composable
private fun SwipeToClear(onClear: (() -> Unit)?, modifier: Modifier, content: @Composable () -> Unit) {
    if (onClear == null) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Not saved with the list's item, as rememberSwipeToDismissBoxState's is: a card back from Undo would come back swiped.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, density, positionalThreshold = threshold) }
    LaunchedEffect(state.currentValue) { if (state.currentValue != SwipeToDismissBoxValue.Settled) onClear() }
    SwipeToDismissBox(
        state,
        backgroundContent = {
            val direction = state.dismissDirection
            Box(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) Text("Clear", color = Rex.textDim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        },
        modifier = modifier,
    ) { content() }
}

/**
 * The line under an agent's title: what it last said, else its notification, else what it waits for when that
 * says more than its group does ("Has a question", not "Waiting for you"). Null rather than repeat the title,
 * as a notification often starts with it, or the group's name, as "Finished" would under Done. What it said
 * may come from the record the title came from, which [subtasks] leaves out.
 */
internal fun agentDetail(meta: BlockMeta, title: String): String? {
    val status = meta.status
    val waitingFor = kindText(status).takeIf { status?.state == "blocked" && status.kind in setOf("permission", "question", "auth") }
    val said = status?.msg?.takeIf { it.isNotBlank() }
        ?: meta.statuses.firstOrNull { isTitled(it, title) }?.msg?.takeIf { it.isNotBlank() }
    return (said ?: withoutTitle(meta.notification, title) ?: waitingFor)?.takeUnless { it.equals(title, ignoreCase = true) }
}

/**
 * The other records the agent reports (build, build/test…) that are doing something. A record titled like the
 * card isn't one: Claude Code reports its task as a record of its own beside the bare working one, and the card
 * already shows that title.
 */
internal fun subtasks(meta: BlockMeta, top: ProgramStatus?, title: String): List<ProgramStatus> =
    meta.statuses.filter { it !== top && it.id != null && it.state != "idle" && !isTitled(it, title) }

private fun isTitled(s: ProgramStatus, title: String) = s.title?.trim().equals(title.trim(), ignoreCase = true)

/** A notification ("title · body") without a leading [title] the card already shows; null if that was all of it. */
internal fun withoutTitle(text: String?, title: String): String? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (t.equals(title, ignoreCase = true)) return null
    val prefix = "$title · "
    return if (t.startsWith(prefix, ignoreCase = true)) t.substring(prefix.length).ifBlank { null } else t
}

/**
 * Where an agent runs: its host when there are several, its session and its folder. A session named like the
 * task or the folder (a session "billing" in ~/src/billing) says nothing more, so it's left out.
 */
internal fun agentSource(e: BoardEntry, title: String, showHost: Boolean): String? {
    val path = prettyPath(e.meta.pwd)
    val session = e.sessionLabel?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals(title, ignoreCase = true) && !it.equals(path?.substringAfterLast('/'), ignoreCase = true)
    }
    return listOfNotNull(e.hostLabel.takeIf { showHost }, session, path).joinToString(" · ").ifEmpty { null }
}

/** The agent's [subtasks], a line each, at most three. */
@Composable
private fun Subtasks(meta: BlockMeta, top: ProgramStatus?, title: String) {
    val subs = subtasks(meta, top, title)
    if (subs.isEmpty()) return
    Column(Modifier.padding(start = 38.dp, top = 6.dp)) {
        subs.take(3).forEach { s ->
            val depth = s.id!!.count { it == '/' }
            Row(Modifier.padding(start = (depth * 12).dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor(s.state)))
                Spacer(Modifier.width(8.dp))
                Text(
                    s.title?.takeIf { it.isNotBlank() } ?: s.id.substringAfterLast('/'),
                    color = Rex.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                val what = s.msg ?: kindText(s) ?: s.progress?.let { "$it%" }
                if (what != null) Text("  $what", color = Rex.textFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (subs.size > 3) Text("+${subs.size - 3} more", color = Rex.textFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun statusColor(state: String) = when (state) {
    "blocked" -> Rex.blocked
    "error" -> Rex.error
    "done" -> Rex.done
    "working" -> Rex.working
    else -> Rex.textFaint
}

private fun kindText(s: ProgramStatus?): String? = when (s?.state) {
    "blocked" -> when (s.kind) {
        "permission" -> "Waiting for permission"
        "question" -> "Has a question"
        "auth" -> "Needs a password"
        else -> "Waiting for you"
    }
    "done" -> "Finished"
    "error" -> "Failed"
    else -> null
}

@Composable
private fun HostHeader(conn: HostConnection, state: HostState, stale: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var newSession by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (conn.config.icon == "desktop" || conn.config.icon == "studio") Icons.Outlined.Computer else Icons.Outlined.Laptop,
                null, tint = Rex.textDim, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            // A long name ellipsizes rather than pushing the dot and the menu off the row.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conn.config.label, style = MaterialTheme.typography.titleLarge, color = Rex.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                ConnectionDot(state.status)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreHoriz, "Host menu", tint = Rex.textDim) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("New session") }, onClick = { menu = false; newSession = true })
                    DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; conn.connect(); conn.ensureAlive(force = true) })
                }
            }
        }
        if (stale) StaleLine(conn, state)
    }
    if (newSession) NewSessionDialog(conn, onClose = { newSession = false })
}

/** Under an unreachable host's name: that its sessions below are as last seen, and why. */
@Composable
private fun StaleLine(conn: HostConnection, state: HostState) {
    Column(Modifier.padding(start = 34.dp, end = 12.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.status != ConnStatus.IDLE) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when {
                    state.status == ConnStatus.IDLE -> "Not connected"
                    state.error != null -> "Offline · trying again…"
                    else -> "Reconnecting…"
                },
                color = Rex.textDim, fontSize = 13.sp, maxLines = 1,
            )
        }
        state.error?.let {
            Text(hint(classify(it), conn.config.url, it), color = Rex.textFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NewSessionDialog(conn: HostConnection, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // The dialog stays up until the host answers, so a failure can be tried again with the name still there.
    fun create() {
        if (creating) return
        creating = true
        scope.launch {
            val id = try {
                attempt(context, conn, "start a session") { conn.newSession(label) }
            } finally {
                creating = false
            }
            if (id != null) onClose()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New session on ${conn.config.label}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                label, { label = it }, label = { Text("Name (optional)") }, singleLine = true, enabled = !creating,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.focusRequester(focus),
            )
            // Here, in the dialog's own window, where the field is by the time this runs.
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = ::create, enabled = !creating) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
fun ConnectionDot(status: ConnStatus) {
    when (status) {
        ConnStatus.CONNECTING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
        else -> Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                when (status) {
                    ConnStatus.ONLINE -> Rex.online
                    ConnStatus.OFFLINE -> Rex.offline
                    else -> Rex.textFaint
                },
            ),
        )
    }
}

/** Why a host can't be reached, in terms of what to check, with the connection's own words faint under it. */
@Composable
internal fun HostProblem(error: String, url: String, color: Color = Rex.textDim, fontSize: TextUnit = 14.sp) {
    val problem = classify(error)
    Text(hint(problem, url, error), color = color, fontSize = fontSize)
    // The hint for an error it can't place already quotes it.
    if (problem != Problem.OTHER) {
        Text(error, color = Rex.textFaint, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** In place of the sessions of a host that has none to show. */
@Composable
private fun HostStatusCard(conn: HostConnection, state: HostState) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Rex.surface).padding(16.dp),
    ) {
        val error = state.error
        when {
            state.status == ConnStatus.ONLINE -> Text("No sessions yet. Start one from the ⋯ menu.", color = Rex.textDim, fontSize = 14.sp)
            // The host keeps its last error while it tries again, so the card stays put between attempts
            // instead of flicking to "Connecting…" and back each time.
            state.status == ConnStatus.OFFLINE || (state.status == ConnStatus.CONNECTING && error != null) -> {
                Text("Can't reach this host", color = Rex.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                if (error != null) HostProblem(error, conn.config.url)
                else Text("Is Tailscale on, and Rex running there?", color = Rex.textDim, fontSize = 14.sp)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Rex.textFaint)
                    Spacer(Modifier.width(8.dp))
                    Text("Trying again…", color = Rex.textFaint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { conn.connect(); conn.ensureAlive(force = true) }) { Text("Retry", color = Rex.accent) }
                }
            }
            state.status == ConnStatus.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
                Spacer(Modifier.width(8.dp))
                Text("Connecting…", color = Rex.textDim, fontSize = 14.sp)
            }
            else -> Text("Not connected", color = Rex.textDim, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SessionHeader(
    label: String,
    collapsed: Boolean,
    count: Int,
    onBoard: Boolean,
    adding: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A session whose tabs are all on the board has nothing here to fold away.
    Row(
        modifier.fillMaxWidth().clickable(enabled = !onBoard, onClick = onToggle).padding(start = 20.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = Rex.textDim, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (onBoard) Text("on the board", color = Rex.textFaint, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp))
        else if (collapsed) Text("$count", color = Rex.textFaint, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
        // 40dp to tap, next to the chevron; while a tab is being opened it spins and takes no second tap.
        IconButton(onClick = onAdd, enabled = !adding, modifier = Modifier.size(40.dp)) {
            if (adding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
            else Icon(Icons.Outlined.Add, "New tab", tint = Rex.textDim, modifier = Modifier.size(20.dp))
        }
        // Without the chevron, its room keeps "+" in line with the other sessions'.
        if (onBoard) Spacer(Modifier.width(30.dp))
        else Icon(
            Icons.Outlined.ExpandMore, if (collapsed) "Expand" else "Collapse", tint = Rex.textDim,
            modifier = Modifier.padding(end = 10.dp).size(20.dp).rotate(if (collapsed) -90f else 0f),
        )
    }
}

/** A session with no tabs, or whose tabs haven't arrived yet. */
@Composable
private fun EmptySession(loaded: Boolean) {
    Text(
        if (loaded) "No tabs" else "Loading…", color = Rex.textFaint, fontSize = 14.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowGroup(
    conn: HostConnection,
    sessionId: String,
    windows: List<WindowView>,
    metas: Map<String, BlockMeta>,
    live: Boolean,
    onOpen: (Target) -> Unit,
) {
    if (windows.isEmpty()) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmClose by remember { mutableStateOf<WindowView?>(null) }
    // Tabs asked to close: they stay, faded, until the host's event removes them.
    var closing by remember { mutableStateOf(setOf<String>()) }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(Rex.surface).animateContentSize(),
    ) {
        windows.forEachIndexed { i, w ->
            var menu by remember { mutableStateOf(false) }
            val isClosing = w.windowId in closing
            Box {
                WindowRow(
                    w, metas, live = live, closing = isClosing,
                    modifier = Modifier.testTag("window:${w.windowId}").combinedClickable(
                        enabled = !isClosing,
                        onClick = { onOpen(Target(conn.id, sessionId, w.windowId, null)) },
                        onLongClickLabel = "Close tab",
                        onLongClick = { menu = true },
                    ),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Close tab", color = Rex.error) }, onClick = { menu = false; confirmClose = w })
                }
            }
            if (i < windows.lastIndex) HorizontalDivider(color = Rex.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 60.dp))
        }
    }
    confirmClose?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text("Close this tab?") },
            text = { Text("Its programs will be ended on ${conn.config.label}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = null
                    closing = closing + w.windowId
                    scope.launch {
                        if (attempt(context, conn, "close the tab") { conn.closeWindow(sessionId, w.windowId) } == null) {
                            closing = closing - w.windowId
                        }
                    }
                }) { Text("Close", color = Rex.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClose = null }) { Text("Cancel") } },
        )
    }
}

/** One tab. [live] is false while its host is unreachable, when a working agent may well have stopped. */
@Composable
private fun WindowRow(w: WindowView, metas: Map<String, BlockMeta>, live: Boolean, closing: Boolean, modifier: Modifier = Modifier) {
    val bid = representativeBlock(w, metas)
    val meta = bid?.let { metas[it] }
    val title = windowTitle(w, meta)
    val path = prettyPath(meta?.pwd)
    val unseen = w.blocks.any { metas[it.blockId]?.unseen == true }
    val status = meta?.status
    // Working dots would claim the agent is still at it, which an unreachable host can't confirm.
    val showState = !(closing || !live && status?.state == "working")
    Row(
        modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
            .graphicsLayer { alpha = if (closing) 0.5f else 1f }
            .semantics { tabState(status.takeIf { showState }, unseen, closing)?.let { stateDescription = it } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ProgramIcon(programOf(meta))
            if (unseen) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(10.dp)
                        .clip(CircleShape).background(Rex.surface).padding(1.5.dp).clip(CircleShape).background(Rex.unseen),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = if (meta?.exited == true) Rex.textDim else Rex.text, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (path != null && titleIsProgram(meta, title)) {
                    Spacer(Modifier.width(6.dp))
                    Text(path, color = Rex.textFaint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            val notification = withoutTitle(meta?.notification, title)
            val sub = when {
                status?.state == "blocked" -> status.msg ?: kindText(status)
                status?.state == "error" -> status.msg ?: "Failed"
                notification != null -> notification
                !titleIsProgram(meta, title) && path != null -> path
                else -> null
            }
            if (!sub.isNullOrBlank()) {
                Text(
                    sub, color = if (status?.state == "blocked") Rex.blocked else Rex.textFaint, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (w.blocks.size > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 6.dp).clearAndSetSemantics { contentDescription = "${w.blocks.size} panes" },
            ) {
                Icon(Icons.AutoMirrored.Outlined.ViewQuilt, null, tint = Rex.textFaint, modifier = Modifier.size(14.dp))
                Text("${w.blocks.size}", color = Rex.textFaint, fontSize = 12.sp)
            }
        }
        when {
            closing -> CircularProgressIndicator(Modifier.padding(end = 6.dp).size(14.dp), strokeWidth = 1.5.dp, color = Rex.textDim)
            !showState -> Unit
            // The row's state says what the badge shows.
            else -> Box(Modifier.clearAndSetSemantics {}) { StatusBadge(status, false) }
        }
    }
}

/** What a screen reader says of a tab after its name: "Working", "Needs you", "Failed" or "Done", and "new" if unseen. */
internal fun tabState(status: ProgramStatus?, unseen: Boolean, closing: Boolean = false): String? {
    if (closing) return "Closing"
    val state = when (status?.state) {
        "working" -> "Working"
        "blocked" -> "Needs you"
        "error" -> "Failed"
        "done" -> "Done"
        else -> null
    }
    return listOfNotNull(state, "new".takeIf { unseen }).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
}

@Composable
fun StatusBadge(status: ProgramStatus?, unseen: Boolean) {
    when (status?.state) {
        "working" -> WorkingDots(Modifier.padding(end = 6.dp))
        "blocked" -> Box(
            Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(Rex.blocked.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text("Needs you", color = Rex.blocked, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        "done" -> Icon(Icons.Outlined.Check, "Done", tint = Rex.done, modifier = Modifier.padding(end = 6.dp).size(18.dp))
        "error" -> Box(
            Modifier.padding(end = 6.dp).size(18.dp).clip(CircleShape).background(Rex.error.copy(alpha = 0.2f))
                .clearAndSetSemantics { contentDescription = "Failed" },
            contentAlignment = Alignment.Center,
        ) { Text("!", color = Rex.error, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        else -> if (unseen) Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(Rex.unseen))
    }
}

/**
 * Three dots lit in turn while something works. They step a few times a second instead of fading on every frame,
 * so a screen of working agents lets the display slow its refresh rate between steps, and they stand still with
 * the phone's animations off. A screen reader skips them: what they're on says the state.
 */
@Composable
fun WorkingDots(modifier: Modifier = Modifier, color: Color = Rex.working) {
    val context = LocalContext.current
    val still = remember(context) { animationsOff(context) }
    var lit by remember { mutableIntStateOf(0) }
    if (!still) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(DOTS_STEP_MS)
                lit = (lit + 1) % 3
            }
        }
    }
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            // Read in the layer, so each step only redraws the dots rather than recomposing every row with dots.
            Box(Modifier.size(4.dp).graphicsLayer { alpha = if (still) 1f else dotAlpha(i, lit) }.clip(CircleShape).background(color))
        }
    }
}

/** How long each of [WorkingDots]' steps lasts: a little over three a second. */
private const val DOTS_STEP_MS = 300L

/** The lit dot is full, the one it came from fading, the other faint: a wave that runs left to right. */
internal fun dotAlpha(dot: Int, lit: Int) = when ((lit - dot + 3) % 3) {
    0 -> 1f
    1 -> 0.55f
    else -> 0.25f
}

/** Whether the phone's animations are off: the animator duration scale at 0, as "Remove animations" sets it. */
private fun animationsOff(context: Context) =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
private fun EmptyState(onHosts: () -> Unit) {
    Column(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Rex.surface).padding(20.dp),
    ) {
        Text("Connect to a Rex server", style = MaterialTheme.typography.titleMedium, color = Rex.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Turn on Tailscale serving on your Mac (rex server tailscale on), then add its address, such as studio-rex.tail1234.ts.net.",
            color = Rex.textDim, fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onHosts) { Text("Add a host") }
    }
}

@Composable
private fun BottomBar(
    watchOnly: Boolean,
    searching: Boolean,
    hasUnseen: Boolean,
    updatePending: Boolean,
    onSettings: () -> Unit,
    onWatch: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // Rides above the keyboard while search has it up.
        modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Rex.surfaceHigh.copy(alpha = 0.96f))
                .border(0.5.dp, Rex.divider, RoundedCornerShape(50)).padding(horizontal = 6.dp),
        ) {
            Box {
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Rex.text) }
                if (updatePending) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 9.dp).size(8.dp).clip(CircleShape).background(Rex.accent))
            }
            Box {
                IconButton(onClick = onWatch) {
                    Icon(Icons.Outlined.Visibility, if (watchOnly) "Show all tabs" else "Show agents only", tint = if (watchOnly) Rex.accent else Rex.text)
                }
                if (hasUnseen) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 9.dp).size(8.dp).clip(CircleShape).background(Rex.unseen))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (searching) Rex.accent else Rex.surfaceHigh.copy(alpha = 0.96f))
                .border(0.5.dp, Rex.divider, CircleShape).clickable(onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) { Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search", tint = Rex.text) }
    }
}
