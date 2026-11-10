package com.aivicventures.subtitletranslator.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aivicventures.subtitletranslator.R
import com.aivicventures.subtitletranslator.app
import com.aivicventures.subtitletranslator.settings.LiveSettings
import com.aivicventures.subtitletranslator.settings.Languages
import com.aivicventures.subtitletranslator.ui.dp
import com.aivicventures.subtitletranslator.ui.themeColor
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

/**
 * The floating windows over other apps (TYPE_APPLICATION_OVERLAY):
 *
 * - **Subtitles**: outlined text on an optional translucent box, anchored by its bottom edge
 *   at [LiveSettings.position]. They let every touch through to the video app underneath
 *   (FLAG_NOT_TOUCHABLE). On Android 12+ Android delivers such touches only if the overlay
 *   is at most InputManager's maximum obscuring opacity (0.8), so the window uses that
 *   opacity. It takes touches only in move mode, while the user drags it.
 * - **Control bubble**: a small round button at a screen edge that can be dragged. Tapping it
 *   opens the controls: pause, move, text size, open the app, stop. When playback capture is
 *   blocked it also offers the microphone, after asking. It fades when not touched.
 *
 * Positions are kept as shares of the screen, so they survive rotation. Must be used on the
 * main thread.
 */
class SubtitleOverlay(service: Context, private val actions: Actions) {
    interface Actions {
        fun togglePause()
        fun stop()
        fun useMicrophone()
        fun openApp()
    }

    private val settings: LiveSettings = service.app.liveSettings
    private val ctx: Context = ContextThemeWrapper(windowContext(service), R.style.AppTheme_Player)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var fillColor = Color.WHITE
    private val text = OutlinedTextView(ctx).apply {
        gravity = Gravity.CENTER
        includeFontPadding = true
        setShadowLayer(0f, 0f, 0f, 0)
    }
    private val latencyView = TextView(ctx).apply {
        textSize = 11f
        setTextColor(Color.WHITE)
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val subtitleBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(latencyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // Full width: a WRAP_CONTENT window is first measured at the dialog width (320 dp), which
    // wraps the text early. The window ignores touches (outside move mode), so width costs nothing.
    private val subParams = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        title = "Live subtitles"
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private val bubble = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_closed_caption)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE6163A63.toInt())
            setStroke(ctx.dp(2), 0xFFF2B134.toInt())
        }
        contentDescription = ctx.getString(R.string.live_bubble)
        elevation = ctx.dp(4).toFloat()
    }
    private val status = TextView(ctx).apply {
        setTextAppearance(themeAttr(M.attr.textAppearanceBodySmall))
        setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
    }
    private val pauseButton = panelButton(R.string.live_pause, R.drawable.ic_pause) { actions.togglePause() }
    private val moveButton = panelButton(R.string.live_move, R.drawable.ic_open_with) { setMoving(!moving) }
    private val blockedBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val blockedText = TextView(ctx).apply {
        setTextAppearance(themeAttr(M.attr.textAppearanceBodySmall))
        setTextColor(ctx.themeColor(M.attr.colorOnSurface))
    }
    private val micAsk = panelButton(R.string.live_use_mic, R.drawable.ic_mic) { confirmMic(true) }
    private val micConfirm = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val panel: MaterialCardView = MaterialCardView(ctx).apply {
        radius = ctx.dp(16).toFloat()
        cardElevation = ctx.dp(6).toFloat()
        setCardBackgroundColor(ctx.themeColor(M.attr.colorSurfaceContainerHigh))
        visibility = View.GONE
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.live_title)
                setTextAppearance(themeAttr(M.attr.textAppearanceTitleSmall))
                setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            })
            addView(status)
            addView(blockedBox.apply {
                addView(blockedText.apply { setPadding(0, ctx.dp(8), 0, ctx.dp(4)) })
                addView(micAsk)
                addView(micConfirm.apply {
                    addView(TextView(ctx).apply {
                        text = ctx.getString(R.string.live_mic_confirm)
                        setTextAppearance(themeAttr(M.attr.textAppearanceBodySmall))
                        setTextColor(ctx.themeColor(M.attr.colorOnSurface))
                    })
                    addView(pair(
                        panelButton(R.string.live_use_mic_yes, R.drawable.ic_mic, filled = true) { confirmMic(false); actions.useMicrophone() },
                        panelButton(R.string.cancel, 0) { confirmMic(false) },
                    ))
                })
            })
            addView(pair(pauseButton, moveButton))
            addView(pair(
                panelButton(R.string.live_smaller, R.drawable.ic_minus) { resize(-2) },
                panelButton(R.string.live_larger, R.drawable.ic_add) { resize(2) },
            ))
            addView(pair(
                panelButton(R.string.live_open_app, R.drawable.ic_open_in_new) { collapse(); actions.openApp() },
                panelButton(R.string.live_stop, R.drawable.ic_stop) { actions.stop() },
            ))
        })
    }
    private val bubbleRoot = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
    }
    private val bubbleParams = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Live subtitles controls"
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private var shown = false
    var moving = false
        private set
    private var lastLines: List<LiveSession.Line> = emptyList()
    private var notice: String? = null
    private val bubbleSize = ctx.dp(52)
    private val fade = Runnable { if (panel.visibility != View.VISIBLE) bubbleRoot.animate().alpha(IDLE_ALPHA).setDuration(400).start() }

    fun show() {
        if (shown) return
        applyAppearance()
        layoutBubble()
        bubbleRoot.addView(bubble, LinearLayout.LayoutParams(bubbleSize, bubbleSize))
        wm.addView(subtitleBox, subParams)
        wm.addView(bubbleRoot, bubbleParams)
        installBubbleTouch()
        shown = true
        wake()
    }

    fun remove() {
        if (!shown) return
        handler.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(subtitleBox) }
        runCatching { wm.removeView(bubbleRoot) }
        shown = false
    }

    /** Re-reads the look from [LiveSettings] (size, colour, outline, box, position, lines). */
    fun applyAppearance() {
        val a = settings.appearance()
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, a.sizeSp.toFloat())
        fillColor = a.color
        text.typeface = Typeface.create(Typeface.SANS_SERIF, if (a.bold) Typeface.BOLD else Typeface.NORMAL)
        text.outline = a.outline
        text.maxLines = a.maxLines + 1 // a provisional tail may add a line for a moment
        val pad = ctx.dp(10)
        text.setPadding(pad, ctx.dp(4), pad, ctx.dp(6))
        text.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(8).toFloat()
            setColor(Color.argb(a.backgroundPercent * 255 / 100, 0, 0, 0))
            if (moving) setStroke(ctx.dp(2), 0xFFF2B134.toInt(), ctx.dp(8).toFloat(), ctx.dp(5).toFloat())
        }
        layoutSubtitles()
        render()
    }

    /** Lines to show now (oldest first) and the optional latency readout. */
    fun setLines(lines: List<LiveSession.Line>, latency: String?) {
        lastLines = lines
        latencyView.text = latency ?: ""
        latencyView.visibility = if (latency != null && (lines.isNotEmpty() || moving)) View.VISIBLE else View.GONE
        render()
    }

    /** Session state: panel status, pause label, blocked-capture help. */
    fun setState(s: LiveSession.State) {
        val parts = mutableListOf<String>()
        parts += when (s.phase) {
            LiveSession.Phase.LOADING -> ctx.getString(R.string.live_phase_loading)
            LiveSession.Phase.PAUSED -> ctx.getString(R.string.live_phase_paused)
            LiveSession.Phase.ERROR -> s.error ?: ctx.getString(R.string.live_phase_error)
            else -> ctx.getString(if (s.hearing) R.string.live_phase_hearing else R.string.live_phase_listening)
        }
        if (s.language.isNotEmpty()) parts += Languages.name(s.language)
        s.latency?.let { parts += ctx.getString(R.string.live_latency_short, it.totalMs / 1000.0) }
        status.text = parts.joinToString(" · ")
        pauseButton.text = ctx.getString(if (s.phase == LiveSession.Phase.PAUSED) R.string.live_resume else R.string.live_pause)
        pauseButton.setIconResource(if (s.phase == LiveSession.Phase.PAUSED) R.drawable.ic_play else R.drawable.ic_pause)
        val blocked = s.captureBlocked && s.mode == AudioMode.INTERNAL
        blockedText.text = ctx.getString(R.string.live_blocked_panel)
        blockedBox.visibility = if (blocked) View.VISIBLE else View.GONE
        if (!blocked) confirmMic(false)
        val newNotice = when {
            s.phase == LiveSession.Phase.ERROR -> s.error
            blocked -> ctx.getString(R.string.live_blocked_short)
            s.phase == LiveSession.Phase.LOADING -> ctx.getString(R.string.live_phase_loading)
            s.phase == LiveSession.Phase.PAUSED -> ctx.getString(R.string.live_phase_paused)
            else -> null
        }
        if (newNotice != notice) {
            notice = newNotice
            render()
        }
        if (blocked && panel.visibility != View.VISIBLE) expand()
    }

    fun onConfigurationChanged() {
        if (!shown) return
        layoutSubtitles()
        layoutBubble()
        runCatching { wm.updateViewLayout(bubbleRoot, bubbleParams) }
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        val showNotice = lastLines.isEmpty() && notice != null
        // The view's colour, not a colour span: a span would also colour the outline pass.
        text.setTextColor(if (showNotice) NOTICE_COLOR else fillColor)
        if (showNotice) {
            sb.append(notice)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            for ((i, l) in lastLines.withIndex()) {
                if (i > 0) sb.append('\n')
                val from = sb.length
                sb.append(l.text)
                // Provisional text (the line still being spoken) in italics until it is final.
                if (l.provisional) sb.setSpan(StyleSpan(Typeface.ITALIC), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sb.isEmpty() && moving) sb.append(ctx.getString(R.string.live_move_hint))
        }
        text.text = sb
        subtitleBox.visibility = if (sb.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun layoutSubtitles() {
        val screen = screenSize()
        text.maxWidth = (screen.width() * 0.92f).toInt()
        subParams.y = ((1f - settings.position) * screen.height()).toInt()
        // Touches pass through to the video app; Android 12+ allows that only below the maximum obscuring opacity.
        subParams.alpha = if (moving) 1f else passThroughAlpha()
        if (moving) {
            subParams.flags = subParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            subParams.flags = subParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (shown) runCatching { wm.updateViewLayout(subtitleBox, subParams) }
    }

    private fun passThroughAlpha(): Float =
        if (Build.VERSION.SDK_INT >= 31) minOf(1f, ctx.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch) else 1f

    @SuppressLint("ClickableViewAccessibility")
    private fun setMoving(on: Boolean) {
        moving = on
        moveButton.text = ctx.getString(if (on) R.string.live_move_done else R.string.live_move)
        if (on) {
            var startY = 0f
            var startPos = 0
            subtitleBox.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = ev.rawY
                        startPos = subParams.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val h = screenSize().height()
                        subParams.y = (startPos - (ev.rawY - startY).toInt()).coerceIn(0, h - subtitleBox.height)
                        runCatching { wm.updateViewLayout(subtitleBox, subParams) }
                    }
                    MotionEvent.ACTION_UP -> {
                        settings.position = 1f - subParams.y.toFloat() / screenSize().height()
                    }
                }
                true
            }
        } else {
            subtitleBox.setOnTouchListener(null)
        }
        applyAppearance()
    }

    private fun resize(step: Int) {
        settings.sizeSp = settings.sizeSp + step
        applyAppearance()
    }

    private fun confirmMic(ask: Boolean) {
        micConfirm.visibility = if (ask) View.VISIBLE else View.GONE
        micAsk.visibility = if (ask) View.GONE else View.VISIBLE
        refreshBubbleWindow()
    }

    private fun expand() {
        // The panel opens towards the middle of the screen.
        bubbleRoot.removeView(panel)
        if (settings.bubbleLeft) bubbleRoot.addView(panel) else bubbleRoot.addView(panel, 0)
        (panel.layoutParams as LinearLayout.LayoutParams).apply {
            width = ctx.dp(260)
            if (settings.bubbleLeft) leftMargin = ctx.dp(8) else rightMargin = ctx.dp(8)
        }
        panel.visibility = View.VISIBLE
        layoutBubble()
        refreshBubbleWindow()
        wake()
    }

    private fun collapse() {
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        bubbleRoot.removeView(panel)
        confirmMic(false)
        layoutBubble()
        refreshBubbleWindow()
        wake()
    }

    private fun refreshBubbleWindow() {
        if (shown) runCatching { wm.updateViewLayout(bubbleRoot, bubbleParams) }
    }

    /** Puts the bubble (and panel) at its saved side and height. */
    private fun layoutBubble() {
        val screen = screenSize()
        val panelW = if (panel.visibility == View.VISIBLE) ctx.dp(268) else 0
        bubbleParams.x = if (settings.bubbleLeft) 0 else (screen.width() - bubbleSize - panelW).coerceAtLeast(0)
        bubbleParams.y = (settings.bubbleY * (screen.height() - bubbleSize)).toInt().coerceIn(0, maxOf(0, screen.height() - bubbleSize))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installBubbleTouch() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        bubbleRoot.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapse()
                return@setOnTouchListener false
            }
            false
        }
        bubble.setOnTouchListener { _, ev ->
            wake()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > ctx.dp(8) || abs(dy) > ctx.dp(8))) {
                        dragging = true
                        collapse()
                        startX = bubbleParams.x
                        startY = bubbleParams.y
                    }
                    if (dragging) {
                        bubbleParams.x = (startX + dx).toInt()
                        bubbleParams.y = (startY + dy).toInt()
                        refreshBubbleWindow()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val screen = screenSize()
                        settings.bubbleLeft = bubbleParams.x + bubbleSize / 2 < screen.width() / 2
                        settings.bubbleY = bubbleParams.y.toFloat() / (screen.height() - bubbleSize).coerceAtLeast(1)
                        layoutBubble()
                        refreshBubbleWindow()
                    } else if (panel.visibility == View.VISIBLE) {
                        collapse()
                    } else {
                        expand()
                    }
                }
            }
            true
        }
    }

    private fun wake() {
        bubbleRoot.animate().cancel()
        bubbleRoot.alpha = 1f
        handler.removeCallbacks(fade)
        handler.postDelayed(fade, FADE_MS)
    }

    private fun screenSize(): Rect =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else ctx.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private fun pair(a: View, b: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, ctx.dp(6), 0, 0)
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(4) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(4) })
    }

    private fun panelButton(label: Int, icon: Int, filled: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, if (filled) M.attr.materialButtonStyle else R.attr.appTonalButtonStyle).apply {
            text = ctx.getString(label)
            isAllCaps = false
            textSize = 13f
            minHeight = ctx.dp(40)
            insetTop = 0
            insetBottom = 0
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            if (icon != 0) {
                setIconResource(icon)
                iconSize = ctx.dp(18)
                iconPadding = ctx.dp(4)
            }
            setOnClickListener {
                wake()
                onClick()
            }
        }

    private fun themeAttr(attr: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.resourceId else 0
    }

    companion object {
        private const val NOTICE_COLOR = 0xFFFFE14D.toInt()
        private const val FADE_MS = 4000L
        private const val IDLE_ALPHA = 0.55f

        /** On Android 11+ overlay views need a window context for the right configuration. */
        private fun windowContext(c: Context): Context =
            if (Build.VERSION.SDK_INT >= 30) {
                val display = c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                c.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else c
    }
}
