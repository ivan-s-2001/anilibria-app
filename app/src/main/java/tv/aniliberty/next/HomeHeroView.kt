package tv.aniliberty.next

import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.load

/** One featured title, with a phone composition and a separate distance-readable TV layout. */
internal class HomeHeroView(context: Context, private val television: Boolean = false) : LinearLayout(context) {
    private val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = shape(0xFF242830.toInt(), dp(10).toFloat())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val info = LinearLayout(context).apply { orientation = VERTICAL }
    private val badge = TextView(context).apply { label("Актуальное", 12f, MUTED) }
    private val title = TextView(context).apply {
        label("", if (television) 28f else 17f, WHITE, true)
        maxLines = if (television) 2 else 3
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val description = TextView(context).apply {
        label("", if (television) 16f else 13f, MUTED)
        maxLines = if (television) 3 else 4
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xFF22252C.toInt(), dp(16).toFloat(), WHITE, dp(2)))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF292D35.toInt(), dp(16).toFloat()))
            addState(intArrayOf(), shape(0xFF191B20.toInt(), dp(16).toFloat()))
        }
        info.addView(badge, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        info.addView(title, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        info.addView(description, LayoutParams(-1, -2))
        val imageParams = LayoutParams(dp(if (television) 150 else 108), -1)
        val textParams = LayoutParams(0, -2, 1f)
        if (television) {
            textParams.marginEnd = dp(28)
            addView(info, textParams)
            addView(poster, imageParams)
        } else {
            imageParams.marginEnd = dp(16)
            addView(poster, imageParams)
            addView(info, textParams)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(if (television) 248 else 208), MeasureSpec.EXACTLY))
    }

    fun bind(release: Release, click: () -> Unit) {
        title.text = release.title
        description.text = android.text.Html.fromHtml(release.description, android.text.Html.FROM_HTML_MODE_LEGACY)
            .toString().trim().ifBlank { release.metadata }
        badge.text = release.ratings.firstOrNull()?.let { "Рейтинг ${number(it.value)} · ${release.year}" } ?: "Актуальное"
        poster.load(release.poster)
        contentDescription = "${release.title}. Открыть тайтл"
        setOnClickListener { click() }
    }
}
