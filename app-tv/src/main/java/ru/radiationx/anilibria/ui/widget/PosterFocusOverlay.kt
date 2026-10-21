package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.graphics.Typeface
import ru.radiationx.anilibria.common.LibriaCard
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.exp

/** One decorative highlight for the visible poster surface; never participates in focus. */
class PosterFocusOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), ViewTreeObserver.OnPreDrawListener {
    private val density = resources.displayMetrics.density
    private val frame = RectF()
    private val target = RectF()
    private val renderedFrame = RectF()
    private val visibleBounds = Rect()
    private val origin = IntArray(2)
    private var moving = false
    private var previousTime = 0L
    private var shown = false
    private var lastFocused: View? = null
    private var animationsEnabled = true
    private var previewCard: LibriaCard? = null
    private var previewVisible = false
    private val previewBox = RectF()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // CSS filter order: brightness(.05), then contrast(.90).
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            .045f, 0f, 0f, 0f, 12.75f,
            0f, .045f, 0f, 0f, 12.75f,
            0f, 0f, .045f, 0f, 12.75f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    private val previewText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val previewLayouts = mutableListOf<Pair<StaticLayout, Float>>()
    private val showPreview = Runnable {
        previewVisible = true
        invalidate()
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xCCFFFFFF.toInt()
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
        removeCallbacks(showPreview)
        previewCard = null
        previewLayouts.clear()
        shown = false
        lastFocused = null
        previousTime = 0L
        super.onDetachedFromWindow()
    }

    override fun onPreDraw(): Boolean {
        var focused: View? = rootView.findFocus()
        while (focused != null && focused !is LauncherCardView) {
            focused = focused.parent as? View
        }
        val image = (focused as? LauncherCardView)?.mainImageView
        if (image == null || !image.getGlobalVisibleRect(visibleBounds) || !image.isShown) {
            if (shown) invalidate()
            shown = false
            previewVisible = false
            previewCard = null
            removeCallbacks(showPreview)
            previousTime = 0L
            return true
        }
        getLocationOnScreen(origin)
        target.set(visibleBounds)
        target.offset(-origin[0].toFloat(), -origin[1].toFloat())
        val now = System.nanoTime()
        val card = (focused as? LauncherCardView)?.previewCard
        if (focused !== lastFocused || card !== previewCard) {
            lastFocused = focused
            previewCard = card
            previewVisible = false
            previewLayouts.clear()
            invalidate()
            removeCallbacks(showPreview)
            if (card?.preview != null) postDelayed(showPreview, 420L)
            animationsEnabled = Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
        }
        if (!shown || !animationsEnabled) {
            frame.set(target)
            moving = false
        } else {
            val elapsed = min((now - previousTime) / 1_000_000_000f, 0.05f)
            val amount = 1f - exp(-elapsed * 28f)
            frame.left = advance(frame.left, target.left, amount)
            frame.top = advance(frame.top, target.top, amount)
            frame.right = advance(frame.right, target.right, amount)
            frame.bottom = advance(frame.bottom, target.bottom, amount)
            moving = frame != target
        }
        val needsFrame = !shown || frame != renderedFrame || moving
        shown = true
        previousTime = now
        // Refit during Leanback zoom and scrolling, without an idle rendering loop.
        if (needsFrame) invalidate()
        return true
    }

    /** A non-focusable web-style information surface. The original card keeps DPAD/OK. */
    private fun drawPreview(canvas: Canvas) {
        val card = previewCard ?: return
        val info = card.preview ?: return
        val margin = 16f * density
        val panelWidth = min(310f * density, width - 2f * margin)
        val panelHeight = min(322f * density, height - 2f * margin)
        val left = frame.left.coerceIn(margin, (width - panelWidth - margin).coerceAtLeast(margin))
        val top = frame.top.coerceIn(margin, (height - panelHeight - margin).coerceAtLeast(margin))
        previewBox.set(left, top, left + panelWidth, top + panelHeight)
        val save = canvas.save()
        val path = android.graphics.Path().apply {
            addRoundRect(previewBox, 12f * density, 12f * density, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(path)
        panelPaint.color = Color.rgb(33, 33, 33)
        panelPaint.shader = null
        canvas.drawRect(previewBox, panelPaint)
        // Reuse the loaded poster. No image load or server request on focus.
        val artwork = (lastFocused as? LauncherCardView)?.mainImageView?.drawable
        if (artwork != null && artwork.intrinsicWidth > 0 && artwork.intrinsicHeight > 0) {
            val oldBounds = Rect(artwork.bounds)
            val filteredLayer = canvas.saveLayer(previewBox, artworkPaint)
            val artSave = canvas.save()
            val scale = maxOf(panelWidth / artwork.intrinsicWidth, panelHeight / artwork.intrinsicHeight)
            canvas.translate(left + (panelWidth - artwork.intrinsicWidth * scale) / 2f,
                top + (panelHeight - artwork.intrinsicHeight * scale) / 2f)
            canvas.scale(scale, scale)
            artwork.setBounds(0, 0, artwork.intrinsicWidth, artwork.intrinsicHeight)
            artwork.draw(canvas)
            artwork.bounds = oldBounds
            canvas.restoreToCount(artSave)
            canvas.restoreToCount(filteredLayer)
        }
        val padding = 18f * density
        val textWidth = (panelWidth - 2 * padding).toInt()
        if (previewLayouts.isEmpty()) {
            var y = padding
            fun add(text: String, size: Float, lines: Int, color: Int, bold: Boolean = false) {
                if (text.isBlank()) return
                val paint = TextPaint(previewText).apply {
                    textSize = size * resources.displayMetrics.scaledDensity
                    this.color = color
                    typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
                }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END).build()
                previewLayouts.add(layout to y)
                y += layout.height + 8f * density
            }
            add(card.title, 20f, 2, Color.WHITE, true)
            add(info.alternateTitle, 14f, 1, 0xFFBDBDBD.toInt())
            add(info.metadata, 14f, 2, 0xFFEEEEEE.toInt())
            add(info.genres, 14f, 2, 0xFFBDBDBD.toInt())
            add(info.status, 14f, 1, Color.WHITE)
            val room = panelHeight - y - 48f * density
            val synopsisLines = (room / (17f * resources.displayMetrics.scaledDensity)).toInt().coerceIn(0, 5)
            if (synopsisLines > 0) add(info.synopsis, 15f, synopsisLines, 0xFFEEEEEE.toInt())
        }
        val bodySave = canvas.save()
        canvas.clipRect(left, top, previewBox.right, previewBox.bottom - 42f * density)
        previewLayouts.forEach { (layout, y) ->
            val lineSave = canvas.save()
            canvas.translate(left + padding, top + y)
            layout.draw(canvas)
            canvas.restoreToCount(lineSave)
        }
        canvas.restoreToCount(bodySave)
        panelPaint.color = Color.rgb(254, 54, 53)
        canvas.drawRoundRect(left + padding, previewBox.bottom - 36f * density,
            previewBox.right - padding, previewBox.bottom - 10f * density, 8f * density, 8f * density, panelPaint)
        previewText.color = Color.WHITE
        previewText.textSize = 14f * resources.displayMetrics.scaledDensity
        previewText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText("OK · Открыть тайтл", left + padding + 12f * density, previewBox.bottom - 18f * density, previewText)
        canvas.restoreToCount(save)
        canvas.drawRoundRect(previewBox, 12f * density, 12f * density, rim)
    }

    private fun advance(value: Float, goal: Float, amount: Float): Float {
        return if (abs(goal - value) < 0.5f) goal else value + (goal - value) * amount
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        renderedFrame.set(frame)
        val radius = 12f * density
        for (i in 1..2) {
            glow.strokeWidth = i * 2f * density
            glow.color = ((24 / i) shl 24) or 0xFFFFFF
            canvas.drawRoundRect(frame, radius, radius, glow)
        }
        sheen.shader = LinearGradient(frame.left, frame.top, frame.left, frame.bottom,
            0x24FFFFFF, 0x04FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(frame, radius, radius, sheen)
        canvas.drawRoundRect(frame, radius, radius, rim)
        if (previewVisible && previewCard?.preview != null) drawPreview(canvas)
        if (moving) postInvalidateOnAnimation()
    }
}
