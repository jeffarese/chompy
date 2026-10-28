package com.jeffarese.rexmobile.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.jeffarese.rexmobile.terminal.emulator.Keys
import com.jeffarese.rexmobile.terminal.emulator.TerminalSession
import com.jeffarese.rexmobile.terminal.view.TerminalView
import com.jeffarese.rexmobile.terminal.view.TerminalViewClient
import kotlin.math.ceil
import kotlin.math.max

/**
 * Hosts a [TerminalView] and decides how big its text is.
 *
 * - Mirror: the remote terminal keeps its size, the whole grid is fitted on screen, and two fingers
 *   zoom and pan it. One finger still scrolls history and selects text.
 * - Phone: the remote terminal is resized to this screen, and pinching changes the font size.
 */
@SuppressLint("ViewConstructor")
class TerminalViewport(context: Context, typeface: Typeface) : FrameLayout(context) {
    enum class Mode { MIRROR, PHONE }

    val terminalView = TerminalView(context, null)
    private val client = ViewClient()
    private val measurePaint = Paint().apply { this.typeface = typeface }

    var mode = Mode.MIRROR
        set(value) {
            if (field == value) return
            field = value
            resetZoom()
            relayout()
        }

    /** The remote grid, used in mirror mode. */
    var remoteSize: RemoteSize? = null
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** Font size in phone mode, in pixels. */
    var phoneTextSize: Int = sp(DEFAULT_TEXT_SP)
        set(value) {
            val v = value.coerceIn(minText, maxText)
            if (field == v) return
            field = v
            relayout()
        }

    var onPhoneTextSizeChanged: (Int) -> Unit = {}
    var onTap: () -> Unit = {}

    /** Called on the main thread when the view scrolls into history: rows above the live screen, 0 at the bottom. */
    var onScrollbackChanged: (rows: Int) -> Unit = {}

    /**
     * When set, Esc from a hardware keyboard goes here as a key event instead of as a byte, with the
     * modifiers held and the sticky ones from the key bar. A lone Esc byte could run into the next
     * bytes typed and reach the program as Alt plus that key.
     */
    var onRemoteKey: ((RemoteKey) -> Unit)? = null

    /** Rows scrolled up into history, 0 at the live bottom. */
    val scrollbackRows: Int get() = terminalView.scrollbackRows

    /** Glide back to the live screen, stopping any fling. */
    fun scrollToBottom() = terminalView.scrollToBottom()

    /** Sticky modifiers from the key bar, used once by the next typed key. */
    var stickyShift = false
    var stickyCtrl = false
    var stickyAlt = false
    var onModifiersConsumed: () -> Unit = {}

    private val minText = sp(MIN_TEXT_SP)
    private val maxText = sp(MAX_TEXT_SP)
    private var appliedTextSize = -1

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics).toInt()

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pinchStartText = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            terminalView.stopScrolling()
            pinchStartText = phoneTextSize
            if (zoom == 1f) {
                panX = terminalView.left.toFloat()
                panY = terminalView.top.toFloat()
            }
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (mode == Mode.PHONE) {
                phoneTextSize = (phoneTextSize * detector.scaleFactor).toInt().coerceIn(minText, maxText)
                return true
            }
            val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            val f = newZoom / zoom
            // Keep the point under the fingers still while zooming, and follow the fingers.
            panX = detector.focusX - (detector.focusX - panX) * f + (detector.focusX - lastFocusX)
            panY = detector.focusY - (detector.focusY - panY) * f + (detector.focusY - lastFocusY)
            zoom = newZoom
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            applyTransform()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (mode == Mode.PHONE && phoneTextSize != pinchStartText) onPhoneTextSizeChanged(phoneTextSize)
        }
    })

    init {
        setBackgroundColor(0xff0e0e12.toInt())
        // A little room at the sides, so text doesn't run into the screen's edges. It shows this view's
        // background, the terminal's own default one, and phone mode fits its columns inside it.
        val side = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SIDE_PADDING_DP, resources.displayMetrics).toInt()
        setPadding(side, 0, side, 0)
        // A zoomed grid still reaches the edges.
        clipToPadding = false
        clipChildren = true
        terminalView.setTerminalViewClient(client)
        terminalView.setTextSize(phoneTextSize)
        terminalView.setTypeface(typeface)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        addView(terminalView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun attach(session: TerminalSession) {
        // A zoom into one terminal means nothing for another.
        if (terminalView.currentSession !== session) resetZoom()
        terminalView.attachSession(session)
        appliedTextSize = -1
        relayout()
        terminalView.onScreenUpdated()
    }

    fun resetZoom() {
        zoom = 1f
        applyTransform()
    }

    fun showKeyboard() {
        terminalView.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        terminalView.clearFocus()
    }

    /** Send a special key, encoded for the modes the program has turned on. */
    fun sendKey(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
        if (terminalView.currentSession?.emulator != null) terminalView.handleKeyCode(keyCode, Keys.mods(ctrl, alt, shift))
    }

    /**
     * The bytes for [text], one character typed with ctrl or alt held, encoded as a key press for the modes the
     * program has turned on. Null without a terminal, or for longer text.
     */
    fun encodeTyped(text: String, ctrl: Boolean, alt: Boolean, shift: Boolean): ByteArray? {
        val emulator = terminalView.currentSession?.emulator ?: return null
        if (text.isEmpty() || Character.charCount(text.codePointAt(0)) != text.length) return null
        val mods = Keys.mods(ctrl, alt, shift)
        return when (val cp = text.codePointAt(0)) {
            '\n'.code, '\r'.code -> emulator.encodeKey(KeyEvent.KEYCODE_ENTER, mods)
            '\t'.code -> emulator.encodeKey(KeyEvent.KEYCODE_TAB, mods)
            else -> emulator.encodeCodePoint(cp, mods)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { relayout() }
    }

    private fun relayout() {
        // Not laid out yet: the terminal's first layout should already measure the saved text size.
        if (width == 0 || height == 0) return setText(phoneTextSize)
        when (mode) {
            Mode.PHONE -> {
                setText(phoneTextSize)
                setTerminalSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            Mode.MIRROR -> {
                val size = remoteSize
                if (size == null) {
                    setText(phoneTextSize)
                    setTerminalSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    return
                }
                val text = fitTextSize(size.columns, size.rows, width - paddingLeft - paddingRight, height)
                setText(text)
                val w = ceil(size.columns * terminalView.fontWidth).toInt() + 2
                val h = size.rows * terminalView.fontLineSpacing + terminalView.fontLineSpacingAndAscent + 2
                setTerminalSize(w, h)
            }
        }
        applyTransform()
    }

    private fun setText(px: Int) {
        if (px == appliedTextSize) return
        appliedTextSize = px
        terminalView.setTextSize(px)
    }

    private fun setTerminalSize(w: Int, h: Int) {
        val lp = terminalView.layoutParams as LayoutParams
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        terminalView.layoutParams = lp
    }

    /** The largest text size, up to the phone size, that fits the whole grid on screen. */
    private fun fitTextSize(cols: Int, rows: Int, w: Int, h: Int): Int {
        var lo = minText
        var hi = max(minText, phoneTextSize + sp(2f))
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            measurePaint.textSize = mid.toFloat()
            val cw = measurePaint.measureText("X")
            val line = ceil(measurePaint.fontSpacing).toInt()
            val lineAsc = line + ceil(measurePaint.ascent()).toInt()
            if (cols * cw <= w && rows * line + lineAsc <= h) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Place the grid: `panX`/`panY` are its visible top-left corner in this view. */
    private fun applyTransform() {
        val tv = terminalView
        if (mode == Mode.PHONE || zoom <= 1.001f) {
            zoom = 1f
            panX = tv.left.toFloat()
            panY = tv.top.toFloat()
        } else {
            val contentW = tv.width * zoom
            val contentH = tv.height * zoom
            panX = if (contentW <= width) (width - contentW) / 2 else panX.coerceIn(width - contentW, 0f)
            panY = if (contentH <= height) tv.top.toFloat() else panY.coerceIn(height - contentH, 0f)
        }
        tv.pivotX = 0f
        tv.pivotY = 0f
        tv.scaleX = zoom
        tv.scaleY = zoom
        tv.translationX = panX - tv.left
        tv.translationY = panY - tv.top
    }

    // Two fingers zoom and pan; one finger belongs to the terminal (history, selection, taps).
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = ev.pointerCount >= 2

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        return true
    }

    companion object {
        /** Phone-mode text sizes in sp: where it starts, and the range pinching and Settings keep to. */
        const val DEFAULT_TEXT_SP = 13f
        const val MIN_TEXT_SP = 5f
        const val MAX_TEXT_SP = 24f

        /** The room left at each side of the terminal, in dp. */
        const val SIDE_PADDING_DP = 4f
    }

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1f
        override fun onSingleTapUp(e: MotionEvent) = onTap()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            val send = onRemoteKey ?: return false
            if (keyCode != KeyEvent.KEYCODE_ESCAPE) return false
            // Read both sticky modifiers, so they are used up by this key even when the keyboard's are held.
            val ctrl = readControlKey() or e.isCtrlPressed
            val alt = readAltKey() or e.isAltPressed
            terminalView.jumpToBottom()
            send(escapeKey(ctrl = ctrl, alt = alt, shift = readShiftKey() or e.isShiftPressed))
            return true
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey(): Boolean = stickyCtrl.also { if (it) consume() }
        override fun readAltKey(): Boolean = stickyAlt.also { if (it) consume() }
        override fun readShiftKey(): Boolean = stickyShift.also { if (it) consume() }
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() = Unit
        override fun onScrollbackChanged(rows: Int) = this@TerminalViewport.onScrollbackChanged(rows)
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit

        private fun escapeKey(ctrl: Boolean, alt: Boolean, shift: Boolean): RemoteKey {
            val mods = listOfNotNull("shift".takeIf { shift }, "ctrl".takeIf { ctrl }, "alt".takeIf { alt })
            return RemoteKey.ESCAPE.copy(mods = mods.joinToString("+"))
        }

        private fun consume() {
            post {
                stickyShift = false
                stickyCtrl = false
                stickyAlt = false
                onModifiersConsumed()
            }
        }
    }
}
