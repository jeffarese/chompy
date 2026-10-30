package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostStore
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.ui.onboarding.AddressCheck
import com.jeffarese.rexmobile.ui.onboarding.AddressKeyboard
import com.jeffarese.rexmobile.ui.onboarding.checkAddress
import com.jeffarese.rexmobile.ui.onboarding.scanPairingCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun HostsScreen(model: AppModel, onBack: () -> Unit) {
    val hosts by model.hostStore.hosts.collectAsState()
    val states by model.hostStates.collectAsState()
    var editing by remember { mutableStateOf<HostConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HostConfig?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Rex.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Rex.text) }
            Text("Hosts", style = MaterialTheme.typography.titleLarge, color = Rex.text, modifier = Modifier.weight(1f))
            IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add host", tint = Rex.text) }
        }
        LazyColumn(contentPadding = readableListPadding(), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.navigationBarsPadding()) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Rex.surface)) {
                    if (hosts.isEmpty()) {
                        Text("No hosts yet.", color = Rex.textDim, modifier = Modifier.padding(16.dp))
                    }
                    hosts.forEachIndexed { i, h ->
                        val st = states.firstOrNull { it.first.id == h.id }?.second
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            st?.let { ConnectionDot(it.status) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.label, color = Rex.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.url.removePrefix("https://"), color = Rex.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (st?.error != null && st.status != ConnStatus.ONLINE) {
                                    HostProblem(st.error, h.url, color = Rex.error, fontSize = 12.sp)
                                }
                            }
                            IconButton(onClick = { editing = h }) { Icon(Icons.Outlined.Edit, "Edit", tint = Rex.textDim) }
                            IconButton(onClick = { removing = h }) { Icon(Icons.Outlined.Delete, "Remove", tint = Rex.textDim) }
                        }
                        if (i < hosts.lastIndex) HorizontalDivider(color = Rex.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 36.dp))
                    }
                }
            }
            // Importing asks the hosts already here, so with none there is no one to ask.
            if (hosts.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Rex.surface).clickable(enabled = !importing) {
                        importing = true
                        scope.launch {
                            try {
                                importMessage = importHosts(model)
                            } finally {
                                importing = false
                            }
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Rex.accent)
                        else Icon(Icons.Outlined.CloudDownload, null, tint = Rex.accent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (importing) "Asking your servers…" else "Import hosts from your servers",
                            color = if (importing) Rex.textDim else Rex.text, fontWeight = FontWeight.Medium,
                        )
                        Text("Adds the hosts each connected server lists (rex hosts).", color = Rex.textDim, fontSize = 13.sp)
                    }
                }
            }
            item {
                Text(
                    "Each host is a Rex server serving over Tailscale (rex server tailscale on). This phone must be on the same " +
                        "tailnet, signed in as the same Tailscale user unless the server allows other members.",
                    color = Rex.textFaint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (adding || editing != null) {
        HostDialog(model.hostStore, editing, onClose = { adding = false; editing = null })
    }
    removing?.let { h ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${h.label}?") },
            text = { Text("This only removes it from this phone. Nothing changes on the server.") },
            confirmButton = { TextButton(onClick = { model.hostStore.remove(h.id); removing = null }) { Text("Remove", color = Rex.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    importMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            title = { Text("Import hosts") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importMessage = null }) { Text("OK") } },
        )
    }
}

/**
 * The URL to keep for [address], or why not: it isn't an address, or another of [hosts] than [editing] has it.
 * The store would quietly hand back that host, and the dialog close as if it had added one, so this names it.
 */
internal fun checkHostAddress(address: String, hosts: List<HostConfig>, editing: HostConfig?): AddressCheck {
    val check = checkAddress(address)
    if (check !is AddressCheck.Ok) return check
    val other = hosts.firstOrNull { it.id != editing?.id && it.url.equals(check.url, ignoreCase = true) }
    return if (other != null) AddressCheck.Invalid("Already added as ${other.label}") else check
}

/** Add a host, or edit [host]. */
@Composable
private fun HostDialog(store: HostStore, host: HostConfig?, onClose: () -> Unit) {
    var label by remember(host) { mutableStateOf(host?.label ?: "") }
    var address by remember(host) { mutableStateOf(host?.url?.removePrefix("https://") ?: "") }
    var error by remember(host) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current

    fun save() {
        val url = when (val check = checkHostAddress(address, store.hosts.value, host)) {
            is AddressCheck.Invalid -> {
                error = check.message
                return
            }
            is AddressCheck.Ok -> check.url
        }
        if (host == null) store.add(label.trim(), url)
        else store.update(host.copy(label = label.trim().ifBlank { host.label }, url = url))
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (host == null) "Add a host" else "Edit host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The Mac's pairing code, as in the welcome: the scanned address fills the field, to name and save.
                OutlinedButton(
                    onClick = {
                        scanPairingCode(
                            context,
                            onAddress = { scanned ->
                                address = scanned.removePrefix("https://")
                                error = null
                            },
                            onProblem = { error = it },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, null, tint = Rex.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan pairing code", color = Rex.text)
                }
                OutlinedTextField(
                    address, { address = it; error = null },
                    label = { Text("Address") }, placeholder = { Text("studio-rex.tail1234.ts.net") }, singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = AddressKeyboard,
                    modifier = Modifier.focusRequester(focus),
                )
                OutlinedTextField(
                    label, { label = it },
                    label = { Text("Name") }, placeholder = { Text("Studio") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            }
            // The address first: here, in the dialog's own window, where the field is by the time this runs.
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = ::save) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Ask every connected host, all at once, which hosts it knows, and add those this phone doesn't have. */
internal suspend fun importHosts(model: AppModel): String = coroutineScope {
    val conns = model.connections.value
    // A host that isn't online can't answer: asking would only wait out the call's timeout.
    val lists = conns.filter { it.state.value.status == ConnStatus.ONLINE }
        .map { conn -> async { runCatching { conn.remoteHosts() }.getOrNull() } }
        .awaitAll().filterNotNull()
    val added = mutableListOf<String>()
    for (list in lists) {
        for (h in list.hosts) {
            val endpoint = h.endpoints.firstOrNull() ?: continue
            val url = runCatching { ControlConnection.normalizeHostUrl(endpoint) }.getOrNull() ?: continue
            if (model.hostStore.hosts.value.any { it.url.equals(url, ignoreCase = true) }) continue
            model.hostStore.add(h.label, endpoint, h.icon)
            added += h.label
        }
    }
    val unreachable = when (val n = conns.size - lists.size) {
        0 -> ""
        1 -> " One host couldn't be reached."
        else -> " $n hosts couldn't be reached."
    }
    when {
        lists.isEmpty() -> "None of your hosts could be reached."
        added.isEmpty() -> "No new hosts found.$unreachable"
        else -> "Added ${added.joinToString(", ")}.$unreachable"
    }
}
