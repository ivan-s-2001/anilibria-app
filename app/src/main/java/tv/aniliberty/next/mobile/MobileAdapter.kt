package tv.aniliberty.next.mobile

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import tv.aniliberty.next.*

internal data class MobileRow(
    val text: String = "",
    val subtitle: String = "",
    val image: String? = null,
    val poster: Release? = null,
    val hero: Release? = null,
    val heading: Boolean = false,
    val primary: Boolean = false,
    val progress: Pair<Long, Long>? = null,
    val shelf: List<MobileRow>? = null,
    val click: (() -> Unit)? = null,
)

/** Phone grids and touch shelves do not reuse TV card sizes or D-pad focus handling. */
internal class MobileAdapter(private val horizontal: Boolean = false) : RecyclerView.Adapter<MobileAdapter.Holder>() {
    var rows: List<MobileRow> = emptyList()
        private set

    fun submit(items: List<MobileRow>) {
        rows = items
        notifyDataSetChanged()
    }

    class Holder(val box: FrameLayout) : RecyclerView.ViewHolder(box)

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(FrameLayout(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(if (horizontal) dp(240) else -1, -2)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    })

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val box = holder.box
        box.removeAllViews()
        if (horizontal) box.layoutParams.width = box.dp(if (row.poster != null) 132 else 240)
        row.hero?.let { release ->
            box.addView(HomeHeroView(box.context).apply { bind(release) { row.click?.invoke() } }, FrameLayout.LayoutParams(-1, -2))
            return
        }
        if (row.shelf != null) {
            box.addView(RecyclerView(box.context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = MobileAdapter(true).apply { submit(row.shelf) }
                isNestedScrollingEnabled = false
            }, FrameLayout.LayoutParams(-1, box.dp(if (row.shelf.any { it.poster != null }) 248 else 214)))
            return
        }
        val card = LinearLayout(box.context).apply {
            orientation = LinearLayout.VERTICAL
            if (row.click != null) {
                isFocusable = true
                isClickable = true
                minimumHeight = dp(48)
                foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                    try { it.getDrawable(0) } finally { it.recycle() }
                }
                setOnClickListener { row.click.invoke() }
            }
        }
        box.addView(card, FrameLayout.LayoutParams(-1, -2))
        val release = row.poster
        val poster = release != null
        val source = release?.poster ?: row.image
        if (poster || source != null) {
            val width = (box.resources.displayMetrics.widthPixels / 3f / box.resources.displayMetrics.density).toInt() - 16
            card.addView(ImageView(box.context).apply {
                scaleType = if (row.heading) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                background = shape(0xFF22252C.toInt(), dp(10).toFloat())
                clipToOutline = true
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                load(source)
            }, LinearLayout.LayoutParams(-1, box.dp(if (poster) (if (horizontal) 180 else (width * 1.45f).toInt()) else if (row.heading) 210 else 132)))
        }
        row.progress?.let { (positionMs, durationMs) ->
            card.addView(ProgressBar(box.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (durationMs > 0) ((positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) * 1000).toInt() else 0
                contentDescription = "Просмотрено ${progress / 10}%"
            }, LinearLayout.LayoutParams(-1, box.dp(3)))
        }
        card.addView(TextView(box.context).apply {
            label(release?.title ?: row.text, if (row.heading) 21f else if (poster) 13f else 16f, WHITE, row.heading || row.primary)
            setPadding(dp(if (row.click != null && !poster) 12 else 0), dp(10), dp(4), dp(10))
            if (poster || horizontal) {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            if (row.primary) {
                gravity = Gravity.CENTER
                background = shape(RED, dp(10).toFloat())
                minimumHeight = dp(52)
            }
        })
        if (row.subtitle.isNotBlank()) card.addView(TextView(box.context).apply {
            label(row.subtitle, 12f, MUTED)
            maxLines = if (horizontal) 1 else 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), dp(8))
        })
    }
}
