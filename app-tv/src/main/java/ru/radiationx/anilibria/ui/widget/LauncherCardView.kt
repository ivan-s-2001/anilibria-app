package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import androidx.leanback.widget.ImageCardView

/** Glass styling only: Leanback retains ownership of focus, zoom and actions. */
class LauncherCardView(context: Context) : ImageCardView(context) {
    private val density = resources.displayMetrics.density
    private val radius = 16f * density
    private val bounds = RectF()
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xCCFFFFFF.toInt()
    }

    init {
        setBackgroundColor(Color.rgb(28, 28, 30))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sheen.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP
        )
        val inset = rim.strokeWidth / 2
        bounds.set(inset, inset, w - inset, h - inset)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!hasFocus()) return
        canvas.drawRoundRect(bounds, radius, radius, sheen)
        canvas.drawRoundRect(bounds, radius, radius, rim)
    }
}
