package ru.radiationx.anilibria.screen.details

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.radiationx.shared_app.imageloader.showImageUrl
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.LibriaDetailsRow
import ru.radiationx.anilibria.ui.presenter.LibriaReleaseViewHolder

internal val releasePageTabs = listOf("Эпизоды", "Связанное", "Комментарии", "Команда", "Торренты")
internal fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal class ReleaseHeaderAdapter(
    private val onContinue: () -> Unit, private val onEpisodes: () -> Unit,
    private val onFavorite: () -> Unit, private val onOther: () -> Unit,
) : RecyclerView.Adapter<ReleaseHeaderAdapter.Holder>() {
    val row = LibriaDetailsRow(0)
    class Holder(view: View, val presenter: LibriaReleaseViewHolder) : RecyclerView.ViewHolder(view)
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_detail_release, parent, false)
        return Holder(view, LibriaReleaseViewHolder(view, onContinue, onEpisodes, onFavorite, onOther))
    }
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.presenter.bind(row)
}

internal class ReleaseTabsAdapter(private val select: (Int) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var selected = 0
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val scroll = HorizontalScrollView(parent.context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            setPadding(dp(44), dp(8), dp(44), dp(12))
        }
        val line = LinearLayout(parent.context)
        releasePageTabs.forEachIndexed { index, title ->
            line.addView(TextView(parent.context).apply {
                text = title; textSize = 16f
                gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated), intArrayOf()), intArrayOf(Color.WHITE, Color.rgb(255, 70, 70), Color.rgb(170, 170, 170))))
                setBackgroundResource(R.drawable.detail_page_tab)
                minimumHeight = dp(40)
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(8) }
                setOnClickListener { selected = index; updateSelection(line); select(index) }
            })
        }
        scroll.addView(line)
        return object : RecyclerView.ViewHolder(scroll) {}
    }
    private fun updateSelection(line: LinearLayout) {
        for (index in 0 until line.childCount) line.getChildAt(index).isActivated = index == selected
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        updateSelection((holder.itemView as HorizontalScrollView).getChildAt(0) as LinearLayout)
    }
}

internal data class ReleasePageText(val title: String, val description: String = "", val click: (() -> Unit)? = null, val heading: Boolean = false)
internal class ReleaseTextAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var rows: List<ReleasePageText> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val text = TextView(parent.context).apply {
            textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundResource(R.drawable.bg_details_description)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                setMargins(dp(48), dp(4), dp(48), dp(8))
            }
        }
        return object : RecyclerView.ViewHolder(text) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        (holder.itemView as TextView).apply {
            val value = listOf(row.title, row.description).filter { it.isNotBlank() }.joinToString("\n")
            text = SpannableString(value).apply {
                setSpan(AbsoluteSizeSpan(if (row.heading) 22 else 17, true), 0, row.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, row.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (value.length > row.title.length) {
                    setSpan(AbsoluteSizeSpan(14, true), row.title.length, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(Color.rgb(160, 160, 160)), row.title.length, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            isFocusable = !row.heading
            isClickable = row.click != null
            background = if (row.heading) null else context.getDrawable(R.drawable.bg_details_description)
            setOnClickListener { row.click?.invoke() }
        }
    }
}

internal class ReleaseCardsAdapter(private val click: (ru.radiationx.anilibria.common.LibriaCard) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var cards: List<ru.radiationx.anilibria.common.LibriaCard> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun getItemCount() = cards.size
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val box = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setBackgroundResource(R.drawable.bg_details_description)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        }
        box.addView(android.widget.ImageView(parent.context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(-1, box.dp(220)))
        box.addView(TextView(parent.context).apply {
            textSize = 18f; setTextColor(Color.WHITE); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, box.dp(52)))
        return object : RecyclerView.ViewHolder(box) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val card = cards[position]
        val box = holder.itemView as LinearLayout
        (box.getChildAt(0) as android.widget.ImageView).showImageUrl(card.image)
        (box.getChildAt(1) as TextView).text = card.title
        box.setOnClickListener { click(card) }
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        ((holder.itemView as LinearLayout).getChildAt(0) as android.widget.ImageView).showImageUrl(null)
    }
}
