package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
            previousTime = 0L
            return true
        }
        getLocationOnScreen(origin)
        target.set(visibleBounds)
        target.offset(-origin[0].toFloat(), -origin[1].toFloat())
        val now = System.nanoTime()
        if (focused !== lastFocused) {
            lastFocused = focused
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
        if (moving) postInvalidateOnAnimation()
    }
}
