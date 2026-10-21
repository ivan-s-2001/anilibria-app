package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.leanback.widget.ImageCardView

/** Leanback owns focus and zoom; PosterFocusOverlay draws the shared moving highlight. */
class LauncherCardView(context: Context) : ImageCardView(context) {
    private val radius = 16f * resources.displayMetrics.density

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setInfoAreaBackgroundColor(Color.TRANSPARENT)
        // Round only the artwork; captions remain outside the glass poster surface.
        mainImageView?.apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }
    }
}
