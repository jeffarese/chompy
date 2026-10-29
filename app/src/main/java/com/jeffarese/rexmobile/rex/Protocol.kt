package com.jeffarese.rexmobile.rex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shapes from the Rex control API, revision 7. Only the fields this app reads are modelled; the
// server documents the whole API at GET /llms.txt.

const val API_REVISION = 7
const val CONTROL_PROTOCOL = "rex.control.v1"
const val TERMINAL_SUBPROTOCOL = "rex.data.terminal.v1"
const val TERMINAL_CREATOR = "com.superlogical.terminal"
const val SHELL_FLAVOR = "com.superlogical.terminal.shell"

val RexJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

@Serializable
data class SessionSummary(
    @SerialName("session_id") val sessionId: String,
    val label: String = "",
)

@Serializable
data class SessionList(val sessions: List<SessionSummary>? = null)

@Serializable
data class LayoutNode(
    val rect: List<Double> = listOf(0.0, 0.0, 1.0, 1.0),
    @SerialName("block_id") val blockId: String? = null,
    val split: Split? = null,
) {
    @Serializable
    data class Split(
        val direction: String = "horizontal",
        val ratio: Double = 0.5,
        val before: LayoutNode,
        val after: LayoutNode,
    )

    /** Every block in this node, with its rect, in layout order. */
    fun leaves(): List<Pair<String, List<Double>>> =
        if (split != null) split.before.leaves() + split.after.leaves()
        else listOfNotNull(blockId?.let { it to rect })
}

@Serializable
data class BlockInfo(
    @SerialName("block_id") val blockId: String,
    val label: String? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    val flavor: String? = null,
    @SerialName("host_id") val hostId: String? = null,
    val owner: String? = null,
) {
    /** A terminal, the only kind of block the app shows, rather than another kind, such as Rex's walkthrough. */
    val isTerminal: Boolean get() = creatorName == null || creatorName == TERMINAL_CREATOR
}

@Serializable
data class Layer(
    val kind: String = "tiled",
    val bounds: List<Double> = listOf(0.0, 0.0, 1.0, 1.0),
    val layout: LayoutNode? = null,
    val blocks: List<BlockInfo>? = null,
)

@Serializable
data class WindowView(
    @SerialName("window_id") val windowId: String,
    val label: String? = null,
    @SerialName("focused_block_id") val focusedBlockId: String? = null,
    @SerialName("zoomed_block_id") val zoomedBlockId: String? = null,
    val layers: List<Layer>? = null,
) {
    val blocks: List<BlockInfo> get() = layers.orEmpty().flatMap { it.blocks.orEmpty() }

    /** The [blocks] the app can open. */
    val terminalBlocks: List<BlockInfo> get() = blocks.filter { it.isTerminal }

    /** Blocks with their rects, tiled ones in layout order, then floating ones. */
    val placedBlocks: List<Pair<String, List<Double>>>
        get() = layers.orEmpty().flatMap { layer ->
            val leaves = layer.layout?.leaves().orEmpty()
            if (layer.kind == "tiled") leaves
            else leaves.map { (id, r) ->
                // A floating layer's layout rects are fractions of the window already.
                id to r
            }
        }
}

@Serializable
data class SessionView(
    val revision: Long = 0,
    @SerialName("session_id") val sessionId: String? = null,
    val label: String? = null,
    @SerialName("active_window_id") val activeWindowId: String? = null,
    val windows: List<WindowView>? = null,
)

@Serializable
data class AttachResult(val view: SessionView)

@Serializable
data class AttachedSession(val session: SessionSummary, val view: SessionView)

@Serializable
data class BootstrapResult(
    @SerialName("client_id") val clientId: String,
    @SerialName("attached_sessions") val attachedSessions: List<AttachedSession>? = null,
)

@Serializable
data class ProgramStatus(
    val id: String? = null,
    val state: String = "idle",
    val kind: String? = null,
    val progress: Int? = null,
    val app: String? = null,
    @SerialName("effective_app") val effectiveApp: String? = null,
    val title: String? = null,
    val msg: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ProgramStatusList(val records: List<ProgramStatus> = emptyList())

@Serializable
data class ProcessRef(val pid: Int = 0, val name: String = "", val argv0: String? = null, val cwd: String? = null)

@Serializable
data class ProcessResult(val foreground: ProcessRef? = null)

@Serializable
data class TitleResult(val title: String = "")

@Serializable
data class PwdResult(val pwd: String? = null)

@Serializable
data class TerminalSizeResult(
    val columns: Int = 0,
    val rows: Int = 0,
    @SerialName("cell_width_px") val cellWidthPx: Int = 0,
    @SerialName("cell_height_px") val cellHeightPx: Int = 0,
    val owner: String? = null,
)

@Serializable
data class FormatResult(val content: String = "", val id: Long? = null, val total: Long? = null)

@Serializable
data class TicketResult(val ticket: String, @SerialName("expires_in_ms") val expiresInMs: Long = 0)

@Serializable
data class RemoteHost(
    val id: String = "",
    val label: String = "",
    val endpoints: List<String> = emptyList(),
    val icon: String? = null,
)

@Serializable
data class RemoteHostList(val hosts: List<RemoteHost> = emptyList())

@Serializable
data class RexErrorBody(val code: String = "internal", val message: String = "", val status: Int = 500)
